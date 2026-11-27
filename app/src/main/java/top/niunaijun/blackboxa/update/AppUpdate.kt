package top.niunaijun.blackboxa.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import top.niunaijun.blackboxa.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 应用内更新。版本清单放在仓库 `update/app-version.json`：
 *
 * ```json
 * {"versionCode":401,"versionName":"4.0.1","url":"https://…/BlackBox_4.0.1_arm64-v8a-release.apk",
 *  "sha256":"…","size":9000000,"notes":"…","force":false}
 * ```
 *
 * 下载完成后按 sha256 校验；即便校验被绕过，Android 也只接受与已装应用同一签名的安装包，
 * 被篡改的包无法覆盖安装。
 *
 * 移植自 photoai 的同名实现，改为本项目的包名 / BuildConfig / UA / 缓存文件名。
 */
object AppUpdate {

    class Info(
        val code: Int,
        val name: String,
        val url: String,
        val sha256: String,
        val notes: String,
        val size: Long,
        val force: Boolean,
        /** 清单里显式给的备用下载地址（可选），会排在自动展开的镜像前面。 */
        val mirrors: List<String> = emptyList()
    ) {
        /** 这条版本可以尝试的所有下载地址：显式备用地址 → 直连 → 各代理。 */
        fun candidates(): List<String> {
            val out = LinkedHashSet<String>()
            mirrors.filter { it.startsWith("http://") || it.startsWith("https://") }.forEach { out.add(it) }
            out.addAll(mirrorsOf(url, size))
            return out.toList().ifEmpty { listOf(url) }
        }
    }

    /** [info] 为空表示：已是最新、用户已忽略该版本，或清单不存在。 */
    class Check(val info: Info?, val error: String?)

    const val DEFAULT_BASE = "https://raw.githubusercontent.com/a1-tools/black-box/main/update"

    fun manifestUrl(): String = BuildConfig.UPDATE_BASE.trim().trimEnd('/') + "/app-version.json"

    private const val GH_PATH = "a1-tools/black-box/main/update/app-version.json"
    private const val RAW_PREFIX = "https://raw.githubusercontent.com/"
    private const val GH_PROXY = "https://gh-proxy.com/" + RAW_PREFIX

    /** 默认源在 GitHub，国内直连不稳，同一份文件同时问几个代理，谁快用谁。 */
    private val MIRRORS: List<String> = mirrorsOf(GH_PROXY + GH_PATH)

    private const val RELEASE_PREFIX = "https://github.com/"
    private const val JSDELIVR_LIMIT = 20L * 1024 * 1024

    /**
     * 同一个 GitHub 文件经各个镜像的地址，顺序与参考项目 tv 一致：gh-proxy → ghfast → jsDelivr → 直连 → ghproxy.net。
     * - raw 地址（含已套了 gh-proxy 的）：五条线路齐全；[size] 已知且超过 20MB 时跳过 jsDelivr（单文件上限）。
     * - GitHub Releases 地址：jsDelivr 不托管 Release 附件，只有三个代理和直连。
     * - 其它地址原样返回。
     */
    fun mirrorsOf(url: String, size: Long = -1L): List<String> {
        val path = when {
            url.startsWith(GH_PROXY) -> url.removePrefix(GH_PROXY)
            url.startsWith(RAW_PREFIX) -> url.removePrefix(RAW_PREFIX)
            url.startsWith(RELEASE_PREFIX) && "/releases/download/" in url -> {
                return listOf(
                    "https://gh-proxy.com/$url",
                    "https://ghfast.top/$url",
                    url,
                    "https://ghproxy.net/$url"
                )
            }
            else -> return listOf(url)
        }
        val out = mutableListOf(GH_PROXY + path, "https://ghfast.top/" + RAW_PREFIX + path)
        if (size <= 0 || size <= JSDELIVR_LIMIT) {
            out.add("https://cdn.jsdelivr.net/gh/" + path.replaceFirst("/main/", "@main/"))
        }
        out.add(RAW_PREFIX + path)
        out.add("https://ghproxy.net/" + RAW_PREFIX + path)
        return out
    }

    private fun manifestCandidates(): List<String> =
        if (BuildConfig.UPDATE_BASE.trimEnd('/') == DEFAULT_BASE) MIRRORS else listOf(manifestUrl())

    private const val UA = "VirtualFramework/${BuildConfig.VERSION_NAME} (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ---------------- 检查 ----------------

    suspend fun check(ignoredCode: Int?): Check = withContext(Dispatchers.IO) {
        // Fire all mirrors concurrently instead of serially: previously one dead mirror could
        // stall the whole check for its full timeout before the next one was even tried.
        val (newest, firstError) = fetchManifestsFast(manifestCandidates())
        val o = newest ?: return@withContext Check(null, firstError)
        val code = o.optInt("versionCode", 0)
        if (code <= BuildConfig.VERSION_CODE) return@withContext Check(null, null)
        val url = o.optString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext Check(null, "更新信息里没有下载地址")
        }
        // 清单可以额外给出备用下载地址（自建镜像 / 临时提速），有就排在自动展开的线路前面
        val mirrors = o.optJSONArray("mirrors")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
        } ?: emptyList()
        val info = Info(
            code = code,
            name = o.optString("versionName").ifEmpty { code.toString() },
            url = url,
            sha256 = o.optString("sha256").lowercase(),
            notes = o.optString("notes"),
            size = o.optLong("size"),
            force = o.optBoolean("force"),
            mirrors = mirrors
        )
        if (!info.force && ignoredCode == code) Check(null, null) else Check(info, null)
    }

    /** 拿到第一份可用清单后，其余镜像再多等这么久：慢镜像可能给出更新的版本（有的镜像会缓存旧文件好几个小时）。 */
    private const val GRACE_MS = 3_000L

    /**
     * 竞速请求跑在独立作用域里，不作为调用方协程的子任务：DNS 解析这类阻塞调用 Call.cancel() 打不断，
     * 若放在 coroutineScope 里，调用方要等最慢（比如域名被墙、解析超时）的那条线路结束才能返回。
     */
    private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 并发询问所有镜像，取 versionCode 最高的那份清单：等到第一份可用的，再最多等 [GRACE_MS]，
     * 之后取消还没回来的请求。404（该镜像没有清单）不算错误。
     */
    internal suspend fun fetchManifestsFast(urls: List<String>): Pair<JSONObject?, String?> {
        if (urls.isEmpty()) return null to null
        val channel = Channel<Pair<JSONObject?, String?>>(urls.size)
        val jobs = urls.map { url ->
            raceScope.launch {
                val result = try {
                    fetchManifest(url)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    null to reason(t)
                }
                channel.trySend(result)
            }
        }
        var newest: JSONObject? = null
        var firstError: String? = null
        var answered = 0
        while (answered < urls.size) {
            val (o, err) = if (newest == null) channel.receive()
            else (withTimeoutOrNull(GRACE_MS) { channel.receive() } ?: break)
            answered++
            if (o == null) {
                if (err != null && firstError == null) firstError = err
                continue
            }
            if (newest == null || o.optInt("versionCode", 0) > newest.optInt("versionCode", 0)) newest = o
        }
        jobs.forEach { it.cancel() }
        channel.close()
        return newest to firstError
    }

    private suspend fun fetchManifest(url: String): Pair<JSONObject?, String?> {
        val call = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).header("User-Agent", UA).build())
        val handle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute().use { r ->
                when {
                    r.code == 404 -> null to null // 这个镜像没有清单，不算错误
                    !r.isSuccessful -> null to "HTTP ${r.code}"
                    else -> {
                        val text = r.body?.string().orEmpty()
                        runCatching { JSONObject(text) to null }
                            .getOrElse { null to "更新信息格式不对" }
                    }
                }
            }
        } catch (e: Exception) {
            null to reason(e)
        } finally {
            handle?.dispose()
        }
    }

    /** 底层网络异常翻成用户看得懂的话；弹窗里直接显示，别再抛英文栈信息给人看。 */
    private fun reason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "连不上服务器，域名解析失败"
        is java.net.SocketTimeoutException -> "连接超时，换个网络试试"
        is java.net.ConnectException -> "服务器拒绝连接"
        is javax.net.ssl.SSLException -> "安全连接失败，稍后再试"
        is java.io.IOException -> "网络中断"
        else -> "网络异常（${e.javaClass.simpleName}）"
    }

    // ---------------- 下载 ----------------

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * 缓存里已下载完成且校验通过的包；同一版本号可能被重新发布，所以每次都重新算 sha256。
     * 会读整个文件，别在主线程调用。
     */
    fun ready(context: Context, info: Info): File? {
        val file = File(updatesDir(context), "blackbox-${info.code}.apk")
        if (!file.isFile || file.length() == 0L) return null
        if (info.size > 0 && file.length() != info.size) return null
        if (info.sha256.isNotEmpty() && sha256(file) != info.sha256) {
            file.delete()
            return null
        }
        return file
    }

    /** 上次中断时残留的字节数；下次从这里接着下。 */
    fun partialBytes(context: Context, info: Info): Long =
        File(updatesDir(context), "blackbox-${info.code}.apk.part").length()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 下载到缓存目录并校验 sha256，失败抛带可读中文原因的异常。
     *
     * 与参考项目 tv 一致：所有线路（清单里的备用地址 + 直连 + 各代理）同时发请求，最先返回成功响应的那条下载，
     * 其余立刻取消，所以走的是当下最快的线路。
     * 断点续传：上次留下的 `.part` 用 Range 接着下，已有字节先算进摘要，校验仍覆盖整个文件；
     * 镜像忽略 Range 返回 200 时从头重下；全部线路都回 416（残留不属于这个文件）时丢掉残留重来一次。
     * 下到一半断流时，已落盘的字节保留并再选一次线路接着下，最多三轮，一轮都没推进就放弃。
     */
    suspend fun download(
        context: Context,
        info: Info,
        onProgress: (done: Long, total: Long) -> Unit
    ): File = downloadTo(updatesDir(context), info, onProgress)

    /** [download] 的实际实现，与 Context 解耦以便单元测试直接指定目录。 */
    internal suspend fun downloadTo(
        dir: File,
        info: Info,
        onProgress: (done: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val part = File(dir, "blackbox-${info.code}.apk.part")
        val file = File(dir, "blackbox-${info.code}.apk")
        dir.listFiles()?.forEach { if (it != part) it.delete() } // 其它版本、或上次校验失败的包

        if (info.size > 0 && part.length() > info.size) part.delete() // 残留比整包还大，不能用；刚好等于则直接校验
        val digest = MessageDigest.getInstance("SHA-256")
        if (part.length() > 0) part.inputStream().use { hashInto(digest, it) }

        var rounds = 0
        var restarted = false
        var lastError: String? = null
        while (true) {
            ensureActive()
            val start = part.length()
            if (info.size > 0 && start >= info.size) break // 字节够了，进入校验
            val response = try {
                openFirst(info.candidates(), if (start > 0) mapOf("Range" to "bytes=$start-") else emptyMap(), start, info.size)
            } catch (e: IllegalStateException) {
                if (start > 0 && !restarted && e.message?.contains("416") == true) {
                    restarted = true
                    part.delete()
                    digest.reset()
                    continue
                }
                throw e
            }
            var finished = response.use { r -> streamBody(r, part, digest, start, info.size, onProgress) { lastError = it } }
            // 没有 Content-Length 的响应（分块 / 代理）被提前截断时读起来也是「正常结束」：长度不够不能算下完，
            // 否则只会在最后校验失败、丢掉整个 .part
            if (finished && info.size > 0 && part.length() < info.size) {
                lastError = "数据不完整"
                finished = false
            }
            if (finished) break
            if (++rounds >= 3 || part.length() <= start) {
                throw IllegalStateException("网络不稳定，没有下完（${lastError ?: "线路中断"}）")
            }
        }

        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256.isNotEmpty() && hex != info.sha256) {
            part.delete()
            throw IllegalStateException("校验失败：下载的文件和发布信息对不上，已丢弃")
        }
        if (!part.renameTo(file)) throw IllegalStateException("保存更新文件失败")
        file
    }

    private fun hashInto(digest: MessageDigest, input: java.io.InputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }

    /**
     * 响应像不像要下的安装包：错误页 / 限流页常常返回 200 + text/html，而且往往最快，竞速时会抢走第一名；
     * 清单给了 size 时，响应体长度还必须等于整包（200）或剩余部分（206）。
     */
    private fun plausible(r: Response, start: Long, size: Long): Boolean {
        if (r.header("Content-Type").orEmpty().lowercase().contains("text/html")) return false
        val len = r.body?.contentLength() ?: -1L
        if (size > 0 && len > 0 && len != size && len != size - start) return false
        return true
    }

    /**
     * 同时请求所有线路，第一个「可信」的成功响应胜出并取消其余请求，不等其余线路结束；全部失败时抛出带原因的异常
     * （含最后一个 HTTP 状态码，调用方靠其中的 416 判断残留不可续传）。被取消时关掉所有请求，包括没人会读的胜出者。
     */
    private suspend fun openFirst(urls: List<String>, headers: Map<String, String>, start: Long, size: Long): Response {
        val http = client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val calls = urls.map { u ->
            http.newCall(
                Request.Builder().url(u).header("User-Agent", UA)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            )
        }
        val answers = Channel<Pair<Call, Response?>>(calls.size)
        val decided = java.util.concurrent.atomic.AtomicBoolean(false)
        calls.forEach { call ->
            raceScope.launch {
                val r = runCatching { call.execute() }.getOrNull()
                // 比赛已经有结果：迟到的响应直接关掉，不再有人收
                if (r != null && decided.get()) r.close() else answers.trySend(call to r)
            }
        }
        var winner: Response? = null
        var lastCode = 0
        var rejected = false
        try {
            var left = calls.size
            while (left > 0 && winner == null) {
                val (call, r) = answers.receive()
                left--
                if (r == null) continue
                if (r.isSuccessful && plausible(r, start, size)) {
                    winner = r
                    calls.forEach { other -> if (other !== call) other.cancel() }
                } else {
                    if (r.isSuccessful) rejected = true else lastCode = r.code
                    r.close()
                }
            }
        } catch (e: CancellationException) {
            calls.forEach { it.cancel() }
            winner?.close()
            throw e
        } finally {
            decided.set(true)
            while (true) { // 已入队但没人收的响应也要关掉
                val late = answers.tryReceive().getOrNull() ?: break
                late.second?.close()
            }
            answers.close()
        }
        return winner ?: throw IllegalStateException(
            "下载失败（" + when {
                lastCode > 0 -> "HTTP $lastCode"
                rejected -> "线路返回的不是安装包"
                else -> "所有线路都连不上"
            } + "）"
        )
    }

    /** 把响应体写进 [part]；读到文件尾返回 true，中途 IO 异常返回 false（已写入的字节保留）。 */
    private suspend fun streamBody(
        r: Response,
        part: File,
        digest: MessageDigest,
        start: Long,
        size: Long,
        onProgress: (done: Long, total: Long) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val body = r.body ?: throw IllegalStateException("下载失败：没有内容")
        val resuming = start > 0 && r.code == 206
        if (!resuming) digest.reset() // 线路不支持 Range，整包重下
        var done = if (resuming) start else 0L
        val total = body.contentLength().takeIf { it > 0 }?.plus(done) ?: size
        try {
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resuming).use { out ->
                    val buf = ByteArray(64 * 1024)
                    onProgress(done, total)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(reason(e))
            return false
        }
        return true
    }

    // ---------------- 安装 ----------------

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 拉起系统安装器。传 Application 也行（下载完自动装的时候就是它），会自动补 NEW_TASK。 */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 少数机型没有直达页面，返回 false 时由界面引导用户手动去开。 */
    fun openInstallSettings(activity: Activity): Boolean = try {
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        )
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
}
