package top.niunaijun.blackboxa.update

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** 用本地 HTTP 服务器验证下载：线路竞速、Range 续传、忽略 Range、断流重试、校验失败。 */
class AppUpdateDownloadTest {
    private val data = ByteArray(600_000) { (it * 31 + 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private lateinit var dir: File
    private val servers = mutableListOf<ServerSocket>()

    @Before fun setUp() { dir = Files.createTempDirectory("upd").toFile() }

    @After fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun serve(
        supportRange: Boolean = true,
        delayMs: Long = 0,
        /** 第 n 次请求（从 1 数）只发一半就断开 */
        cutFirst: Boolean = false,
        /** 返回 200 + text/html 的错误页（模拟镜像限流页） */
        html: Boolean = false,
        /** 返回长度与清单不符的内容（Content-Length 不等于整包） */
        wrongLength: Boolean = false,
        /** 第一次请求不带 Content-Length，发一半就断（代理 / 分块被截断，读起来像正常结束） */
        noLengthCutFirst: Boolean = false,
        hits: AtomicInteger = AtomicInteger(),
        ranges: MutableList<String?> = mutableListOf(),
    ): String {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers.add(server)
        // 极简 HTTP/1.1：只处理 GET + 可选 Range，每个连接一个线程
        Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        sock.use {
                            val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            var range: String? = null
                            reader.readLine() // 请求行
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(":").trim()
                            }
                            val n = hits.incrementAndGet()
                            if (delayMs > 0) Thread.sleep(delayMs)
                            synchronized(ranges) { ranges.add(range) }
                            if (html) {
                                val page = "<html>rate limited</html>".toByteArray()
                                it.getOutputStream().write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n")
                                        .toByteArray() + page
                                )
                                return@runCatching
                            }
                            if (wrongLength) {
                                it.getOutputStream().write(
                                    ("HTTP/1.1 200 OK\r\nContent-Length: 123\r\nConnection: close\r\n\r\n").toByteArray() + ByteArray(123)
                                )
                                return@runCatching
                            }
                            if (noLengthCutFirst && n == 1) {
                                val o = it.getOutputStream()
                                o.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                                o.write(data, 0, data.size / 2)
                                o.flush()
                                return@runCatching // 直接关连接：没有长度，客户端只会看到 EOF
                            }
                            var from = 0
                            var status = "200 OK"
                            if (supportRange && range != null) {
                                from = range!!.removePrefix("bytes=").removeSuffix("-").toInt()
                                if (from >= data.size) {
                                    it.getOutputStream().write(
                                        "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                            .toByteArray()
                                    )
                                    return@runCatching
                                }
                                status = "206 Partial Content"
                            }
                            val len = data.size - from
                            val out = it.getOutputStream()
                            out.write("HTTP/1.1 $status\r\nContent-Length: $len\r\nConnection: close\r\n\r\n".toByteArray())
                            if (cutFirst && n == 1) {
                                out.write(data, from, len / 2) // 声明的长度没发完就断开
                                out.flush()
                            } else {
                                out.write(data, from, len)
                                out.flush()
                            }
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}/a.apk"
    }

    private fun deadUrl(): String = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}/a.apk" }

    private fun info(url: String, mirrors: List<String> = emptyList(), sha256: String = sha) =
        AppUpdate.Info(1, "1", url, sha256, "", data.size.toLong(), false, mirrors)

    private fun run(info: AppUpdate.Info) = runBlocking { AppUpdate.downloadTo(dir, info) { _, _ -> } }

    @Test fun `a dead line does not stop the download and the file is verified`() {
        val live = serve()
        val f = run(info(live, mirrors = listOf(deadUrl())))
        assertArrayEquals(data, f.readBytes())
        assertFalse(File(dir, "blackbox-1.apk.part").exists())
    }

    @Test fun `the fastest line wins over a slow one`() {
        val slowHits = AtomicInteger()
        val slow = serve(delayMs = 1500, hits = slowHits)
        val fast = serve()
        val t0 = System.currentTimeMillis()
        val f = run(info(slow, mirrors = listOf(fast)))
        assertTrue("took too long: ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 1400)
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun `a leftover part is continued with a Range request and still verified as a whole`() {
        File(dir, "blackbox-1.apk.part").writeBytes(data.copyOfRange(0, 250_000))
        val ranges = mutableListOf<String?>()
        val f = run(info(serve(ranges = ranges)))
        assertEquals(listOf<String?>("bytes=250000-"), ranges)
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun `a line that ignores Range makes the download start over`() {
        File(dir, "blackbox-1.apk.part").writeBytes(data.copyOfRange(0, 250_000))
        val f = run(info(serve(supportRange = false)))
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun `a stream cut half way is continued on the next round`() {
        val ranges = mutableListOf<String?>()
        val f = run(info(serve(cutFirst = true, ranges = ranges)))
        assertArrayEquals(data, f.readBytes())
        assertEquals(2, ranges.size)
        assertTrue(ranges[1]!!.startsWith("bytes="))
    }

    @Test fun `a part longer than the file gets 416 and the download restarts without it`() {
        // 清单没给 size，残留又比整包长：续传请求会得到 416，应丢掉残留从头来
        File(dir, "blackbox-1.apk.part").writeBytes(ByteArray(data.size + 5))
        val ranges = mutableListOf<String?>()
        val f = runBlocking {
            AppUpdate.downloadTo(dir, AppUpdate.Info(1, "1", serve(ranges = ranges), sha, "", 0, false)) { _, _ -> }
        }
        assertArrayEquals(data, f.readBytes())
        assertEquals(listOf<String?>("bytes=${data.size + 5}-", null), ranges)
    }

    @Test fun `a fast html error page does not win the race`() {
        val html = serve(html = true)
        val good = serve(delayMs = 300)
        val f = run(info(html, mirrors = listOf(good)))
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun `a response whose length does not match the manifest size is not accepted`() {
        val bad = serve(wrongLength = true)
        val good = serve(delayMs = 300)
        val f = run(info(bad, mirrors = listOf(good)))
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun `only bad lines gives a readable error`() {
        try {
            run(info(serve(html = true)))
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("不是安装包"))
        }
    }

    @Test fun `a short body without Content-Length is not mistaken for a finished download`() {
        val ranges = mutableListOf<String?>()
        val f = run(info(serve(noLengthCutFirst = true, ranges = ranges)))
        assertArrayEquals(data, f.readBytes())
        assertEquals(2, ranges.size) // 第一轮被截断，第二轮用 Range 接着下
    }

    @Test fun `a wrong hash is rejected and the part is discarded`() {
        try {
            run(info(serve(), sha256 = "0".repeat(64)))
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("校验失败"))
        }
        assertFalse(File(dir, "blackbox-1.apk").exists())
        assertFalse(File(dir, "blackbox-1.apk.part").exists())
    }

    @Test fun `every line dead gives a readable error`() {
        try {
            run(info(deadUrl(), mirrors = listOf(deadUrl())))
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("下载失败"))
        }
    }

    /** 返回固定 JSON 的清单服务，可延迟。 */
    private fun serveManifest(json: String, delayMs: Long = 0): String {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers.add(server)
        Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        sock.use {
                            val r = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                            if (delayMs > 0) Thread.sleep(delayMs)
                            val body = json.toByteArray()
                            it.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()
                            )
                            it.getOutputStream().write(body)
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}/m.json"
    }

    @Test fun `the newest manifest wins even when a stale mirror answers first`() {
        val stale = serveManifest("""{"versionCode":5}""")
        val fresh = serveManifest("""{"versionCode":9}""", delayMs = 800)
        val (o, _) = runBlocking { AppUpdate.fetchManifestsFast(listOf(stale, fresh, deadUrl())) }
        assertEquals(9, o!!.optInt("versionCode")) // 错误只在一份清单都没拿到时才会被用到
    }

    @Test fun `all manifest mirrors dead gives an error and no manifest`() {
        val (o, err) = runBlocking { AppUpdate.fetchManifestsFast(listOf(deadUrl(), deadUrl())) }
        assertEquals(null, o)
        assertTrue(err != null)
    }
}
