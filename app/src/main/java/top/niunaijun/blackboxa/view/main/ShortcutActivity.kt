package top.niunaijun.blackboxa.view.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore


class ShortcutActivity:AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra("pkg")
        val userID = intent.getIntExtra("userId",0)

        // 本 Activity 对外导出（桌面快捷方式需要），入口参数来自外部：缺参或没装过的包直接退出
        if (pkg.isNullOrEmpty() || !BlackBoxCore.get().isInstalled(pkg, userID)) {
            finish()
            return
        }

        lifecycleScope.launch {
            BlackBoxCore.get().launchApk(pkg,userID)
            finish()
        }
    }
}