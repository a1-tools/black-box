package top.niunaijun.blackboxa.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.niunaijun.blackboxa.BuildConfig
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.main.MainActivity
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新流程的状态机：检查 → 提示 → 下载 → 校验 → 安装。
 *
 * 下载跑在应用级作用域里，不跟着对话框走：用户可以「后台下载」把它收起来，
 * 进度交给通知；下完时如果界面在前台就直接询问安装，否则留到下次打开应用。
 * 中断的下载保留已下载部分，下次接着下。
 *
 * 状态用 StateFlow 暴露，UI 层（UpdateDialog）用 lifecycleScope 收集。
 */
object UpdateManager {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val info: AppUpdate.Info) : State
        data class Downloading(val info: AppUpdate.Info, val done: Long, val total: Long) : State
        data class Ready(val info: AppUpdate.Info, val file: File) : State
        data class NeedPermission(val info: AppUpdate.Info, val file: File) : State
        data class Failed(val info: AppUpdate.Info?, val message: String, val kept: Long) : State
    }

    private const val CHANNEL = "updates"
    const val NOTIFY_ID = 7
    private const val DONE_ID = 8
    private const val PREFS = "update"
    private const val KEY_IGNORED = "ignored_version"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 用户主动点的检查：要给出「已是最新」这类反馈；自动检查则静默。 */
    private val _manual = MutableStateFlow(false)
    val manual: StateFlow<Boolean> = _manual

    /** 更新提示是否展开；「稍后」收起后仍保留状态，入口还能再次打开。 */
    private val _dialogOpen = MutableStateFlow(false)
    val dialogOpen: StateFlow<Boolean> = _dialogOpen

    /** 弹窗里临时显示的提示（比如系统没有直达的授权页面）。 */
    var tip: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var checkedOnLaunch = AtomicBoolean(false)

    /** 本次检查期间用户把弹窗关掉了：结果出来时不要再弹一次。 */
    private var closedWhileChecking = false
    private val running = AtomicBoolean(false)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun ignoredVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_IGNORED, 0)

    private fun setIgnored(context: Context, code: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_IGNORED, code).apply()
    }

    // ---------------- 检查 ----------------

    fun check(context: Context, manual: Boolean) {
        val app = context.applicationContext
        if (running.get()) return
        _manual.value = manual
        if (manual) {
            closedWhileChecking = false
            _state.value = State.Checking
            _dialogOpen.value = true
        }
        scope.launch {
            val r = AppUpdate.check(ignoredVersion(app))
            when {
                r.error != null -> {
                    _state.value = if (manual) State.Failed(null, r.error, 0) else State.Idle
                }
                r.info == null -> {
                    _state.value = if (manual) State.UpToDate(currentVersion) else State.Idle
                }
                else -> {
                    _state.value = State.Available(r.info)
                    // 用户检查到一半就把弹窗关了，就别再强行弹一次
                    _dialogOpen.value = !(manual && closedWhileChecking)
                }
            }
        }
    }

    /** 每次启动检查一次；忽略过的版本和已是最新的情况都不打扰。 */
    fun checkOnLaunch(context: Context) {
        if (!checkedOnLaunch.compareAndSet(false, true)) return
        check(context, manual = false)
    }

    fun recheck(context: Context) = check(context, manual = true)

    fun dismiss() {
        tip = null
        _dialogOpen.value = false
        if (_state.value is State.Checking) closedWhileChecking = true
        if (_state.value is State.UpToDate) _state.value = State.Idle
        if (_state.value is State.Failed) {
            _state.value = (_state.value as State.Failed).info?.let { State.Available(it) } ?: State.Idle
            _dialogOpen.value = false
        }
    }

    fun reopen() {
        if (_state.value !is State.Idle && _state.value !is State.Checking) _dialogOpen.value = true
    }

    fun ignore(context: Context) {
        (_state.value as? State.Available)?.let { setIgnored(context, it.info.code) }
        _dialogOpen.value = false
        _state.value = State.Idle
    }

    // ---------------- 下载 ----------------

    fun download(context: Context) {
        val info = when (val s = _state.value) {
            is State.Available -> s.info
            is State.Failed -> s.info
            is State.Ready -> s.info
            else -> null
        } ?: return
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val already = AppUpdate.partialBytes(app, info)
        _state.value = State.Downloading(info, already, info.size)
        _dialogOpen.value = true
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, UpdateService::class.java))
        }
        job = scope.launch {
            try {
                // 进度回调每 64KB 一次：状态与通知都按 1% 或 400ms 节流，避免频繁刷新
                var lastNotifyAt = 0L
                var lastPct = -1
                val file = AppUpdate.download(app, info) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    val now = System.currentTimeMillis()
                    if (pct != lastPct && now - lastNotifyAt >= 400) {
                        lastPct = pct
                        lastNotifyAt = now
                        _state.value = State.Downloading(info, done, total)
                        notifyProgress(app, info, done, total)
                    }
                }
                running.set(false)
                stopService(app)
                _state.value = State.Ready(info, file)
                if (_dialogOpen.value) {
                    install(app, file)
                } else {
                    notifyDone(app, info)
                }
            } catch (e: CancellationException) {
                running.set(false)
                stopService(app)
                _state.value = State.Available(info)
            } catch (e: Exception) {
                running.set(false)
                stopService(app)
                notifyCancel(app)
                val msg = e.message?.takeIf { e is IllegalStateException && it.isNotBlank() }
                    ?: "下载失败（${e.javaClass.simpleName}）"
                _state.value = State.Failed(info, msg, AppUpdate.partialBytes(app, info))
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    // ---------------- 安装 ----------------

    fun install(context: Context, file: File) {
        val info = when (val s = _state.value) {
            is State.Ready -> s.info
            is State.Downloading -> s.info
            else -> null
        } ?: return
        if (!file.isFile) {
            _state.value = State.Idle
            return
        }
        if (!AppUpdate.canInstall(context)) {
            _state.value = State.NeedPermission(info, file)
            _dialogOpen.value = true
            return
        }
        runCatching { AppUpdate.install(context, file) }
            .onFailure { e ->
                _state.value = State.Failed(info, "打开安装器失败：${e.message}", 0)
            }
    }

    fun openInstallSettings(context: Context): Boolean {
        val activity = context as? android.app.Activity ?: return false
        return AppUpdate.openInstallSettings(activity)
    }

    /** 从设置页回来：权限给过了就直接装。 */
    fun onResume(context: Context) {
        val s = _state.value
        if (s is State.NeedPermission && AppUpdate.canInstall(context)) {
            val activity = context as? android.app.Activity ?: return
            cancelNotification(context)
            _state.value = State.Ready(s.info, s.file)
            _dialogOpen.value = true
            runCatching { AppUpdate.install(activity, s.file) }
        }
    }

    /** 回到前台时，把「后台下载完的包」的提示补上。 */
    fun offerPendingInstall(context: Context) {
        val s = _state.value
        if (s is State.Ready && s.file.isFile) {
            cancelNotification(context)
            _dialogOpen.value = true
        }
    }

    // ---------------- 通知 ----------------

    private fun channel(app: Context): String {
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun builder(app: Context, title: String, text: String, ongoing: Boolean): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(app, channel(app))
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(!ongoing)
    }

    private fun notifyProgress(app: Context, info: AppUpdate.Info, done: Long, total: Long) {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        notify(app, builder(app, "正在下载 ${info.name}", "$pct%", true).setProgress(100, pct, total <= 0), NOTIFY_ID)
    }

    /** 前台服务启动时用它入场。 */
    fun progressNotification(app: Context): android.app.Notification {
        val s = _state.value
        val pct = if (s is State.Downloading && s.total > 0) (s.done * 100 / s.total).toInt() else 0
        return builder(app, "正在下载更新", "$pct%", true).setProgress(100, pct, pct == 0).build()
    }

    private fun notifyDone(app: Context, info: AppUpdate.Info) {
        notify(app, builder(app, "新版本 ${info.name} 已下载好", "点这里安装", false), DONE_ID)
    }

    private fun notifyCancel(app: Context) {
        NotificationManagerCompat.from(app).cancel(NOTIFY_ID)
    }

    private fun notify(app: Context, b: NotificationCompat.Builder, id: Int) {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, b.build())
        } catch (e: SecurityException) {
            // Android 13+ 没给通知权限：还有界面提示，不影响下载
        }
    }

    fun cancelNotification(app: Context) {
        NotificationManagerCompat.from(app).cancel(NOTIFY_ID)
        NotificationManagerCompat.from(app).cancel(DONE_ID)
    }

    private fun stopService(app: Context) {
        app.stopService(Intent(app, UpdateService::class.java))
        cancelNotification(app)
    }

    fun sizeText(bytes: Long): String =
        if (bytes <= 0) "" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
}
