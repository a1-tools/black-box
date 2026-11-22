package top.niunaijun.blackboxa.update

import android.app.Activity
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import kotlinx.coroutines.launch
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.update.UpdateManager.State

/**
 * 更新弹窗宿主：把 [UpdateManager] 的 StateFlow 映射到 MaterialDialog。
 *
 * 用法：在 Activity 的 onCreate 里调用 [bind]。
 * 同一个状态类型内（例如下载进度每次变化）只更新自定义视图内容，不重建对话框，
 * 避免闪烁和输入框/按钮状态丢失。
 */
object UpdateDialog {

    private var dialog: MaterialDialog? = null
    private var currentKind: String = ""
    private var dialogView: View? = null

    /** 在 Activity onCreate 中调用，绑定状态收集。 */
    fun bind(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect { render(activity, it) }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.dialogOpen.collect { open ->
                    if (!open) dismiss()
                }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.manual.collect { /* 手动标记只影响文案，无需单独处理 */ }
            }
        }
    }

    private fun kindOf(state: State): String = when (state) {
        is State.Idle -> "idle"
        is State.Checking -> "checking"
        is State.UpToDate -> "upToDate"
        is State.Available -> "available"
        is State.Downloading -> "downloading"
        is State.Ready -> "ready"
        is State.NeedPermission -> "needPermission"
        is State.Failed -> "failed"
    }

    private fun render(activity: Activity, state: State) {
        if (activity.isFinishing || activity.isDestroyed) return
        val open = UpdateManager.dialogOpen.value
        if (!open || state is State.Idle) {
            dismiss()
            return
        }
        val kind = kindOf(state)
        if (dialog?.isShowing == true && kind == currentKind) {
            // 同类型状态：原地刷新内容即可（主要是下载进度）
            updateContent(dialogView, state)
            return
        }
        // 状态切换：重建对话框
        dismiss()
        show(activity, state, kind)
    }

    private fun show(activity: Activity, state: State, kind: String) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)
        dialogView = view
        currentKind = kind

        val title: String
        val positiveText: String
        val negativeText: String?
        var cancelable = true

        when (state) {
            is State.Checking -> {
                title = "检查更新"
                positiveText = "关闭"
                negativeText = null
            }
            is State.UpToDate -> {
                title = "已是最新版本"
                positiveText = "好"
                negativeText = null
            }
            is State.Available -> {
                title = "发现新版本"
                val info = state.info
                positiveText = "立即更新"
                negativeText = if (!info.force) "忽略此版本" else null
                if (info.force) cancelable = false
            }
            is State.Downloading -> {
                title = "正在下载 ${state.info.name}"
                positiveText = "后台下载"
                negativeText = "取消"
                cancelable = false
            }
            is State.Ready -> {
                title = "新版本已下载好"
                positiveText = "立即安装"
                negativeText = "稍后"
            }
            is State.NeedPermission -> {
                title = "需要允许安装"
                positiveText = "去允许"
                negativeText = "取消"
            }
            is State.Failed -> {
                title = if (state.info == null) "检查更新失败" else "下载中断"
                positiveText = "重试"
                negativeText = "关闭"
            }
            State.Idle -> return
        }

        val dlg = MaterialDialog(activity).show {
            title(text = title)
            customView(view = view, scrollable = true)
            positiveButton(text = positiveText) {
                onPositive(activity, state)
            }
            if (!negativeText.isNullOrEmpty()) negativeButton(text = negativeText) {
                onNegative(activity, state)
            }
            cancelable(cancelable)
            cancelOnTouchOutside(cancelable)
        }
        dialog = dlg
        updateContent(view, state)
    }

    private fun onPositive(activity: Activity, state: State) {
        when (state) {
            is State.Checking -> UpdateManager.dismiss()
            is State.UpToDate -> UpdateManager.dismiss()
            is State.Available -> UpdateManager.download(activity)
            is State.Downloading -> UpdateManager.dismiss() // 后台下载
            is State.Ready -> UpdateManager.install(activity, state.file)
            is State.NeedPermission -> {
                if (!UpdateManager.openInstallSettings(activity)) {
                    UpdateManager.tip = "这台设备没有直达页面。请到 系统设置 → 应用 → 特殊权限 → 安装未知应用，打开本应用后再点检查更新。"
                    updateContent(dialogView, state)
                }
            }
            is State.Failed -> {
                if (state.info != null) UpdateManager.download(activity) else UpdateManager.recheck(activity)
            }
            State.Idle -> Unit
        }
    }

    private fun onNegative(activity: Activity, state: State) {
        when (state) {
            is State.Available -> UpdateManager.ignore(activity)
            is State.Downloading -> UpdateManager.cancelDownload()
            is State.Failed -> UpdateManager.dismiss()
            is State.NeedPermission -> UpdateManager.dismiss()
            is State.Ready -> UpdateManager.dismiss()
            else -> UpdateManager.dismiss()
        }
    }

    /** 原地刷新自定义视图：副标题、说明、进度、临时提示。 */
    private fun updateContent(view: View?, state: State) {
        if (view == null) return
        val subtitle = view.findViewById<TextView>(R.id.update_subtitle)
        val notes = view.findViewById<TextView>(R.id.update_notes)
        val progressBox = view.findViewById<View>(R.id.update_progress_box)
        val progress = view.findViewById<ProgressBar>(R.id.update_progress)
        val progressText = view.findViewById<TextView>(R.id.update_progress_text)
        val tipView = view.findViewById<TextView>(R.id.update_tip)

        when (state) {
            is State.Checking -> {
                subtitle.text = "正在获取最新版本…"
                notes.visibility = View.GONE
                progressBox.visibility = View.VISIBLE
                progress.isIndeterminate = true
                progressText.text = ""
            }
            is State.UpToDate -> {
                subtitle.text = "当前版本 v${state.version}，没有更新的版本了。"
                notes.visibility = View.GONE
                progressBox.visibility = View.GONE
            }
            is State.Available -> {
                val info = state.info
                val size = UpdateManager.sizeText(info.size).takeIf { it.isNotEmpty() }?.let { " · 安装包 $it" } ?: ""
                subtitle.text = "${info.name} · 当前 v${UpdateManager.currentVersion}$size"
                notes.text = info.notes.ifBlank { "修复已知问题，优化使用体验。" }
                notes.visibility = View.VISIBLE
                progressBox.visibility = View.GONE
            }
            is State.Downloading -> {
                val pct = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
                subtitle.text = "正在下载 ${state.info.name}"
                notes.visibility = View.GONE
                progressBox.visibility = View.VISIBLE
                progress.isIndeterminate = state.total <= 0
                progress.progress = pct
                progressText.text = if (state.total > 0) {
                    "${UpdateManager.sizeText(state.done)} / ${UpdateManager.sizeText(state.total)} · $pct%"
                } else {
                    "已下载 ${UpdateManager.sizeText(state.done)}"
                }
            }
            is State.Ready -> {
                subtitle.text = "${state.info.name} · 点击安装即可完成更新"
                notes.text = state.info.notes.ifBlank { "修复已知问题，优化使用体验。" }
                notes.visibility = View.VISIBLE
                progressBox.visibility = View.GONE
            }
            is State.NeedPermission -> {
                subtitle.text = UpdateManager.tip
                    ?: "下载已完成。系统要求先允许本应用安装应用（只需允许这一次），返回后会自动继续安装。"
                notes.visibility = View.GONE
                progressBox.visibility = View.GONE
            }
            is State.Failed -> {
                subtitle.text = state.message +
                    (if (state.kept > 0) "\n已下载 ${UpdateManager.sizeText(state.kept)}，下次会接着下。" else "")
                notes.visibility = View.GONE
                progressBox.visibility = View.GONE
            }
            State.Idle -> Unit
        }

        // 临时提示
        val t = UpdateManager.tip
        if (!t.isNullOrBlank() && state is State.NeedPermission) {
            tipView.text = t
            tipView.visibility = View.VISIBLE
        } else {
            tipView.visibility = View.GONE
        }
    }

    private fun dismiss() {
        runCatching {
            dialog?.dismiss()
        }
        dialog = null
        dialogView = null
        currentKind = ""
    }
}
