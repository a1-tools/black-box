package top.niunaijun.blackboxa.view.list

import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.data.AppsRepository
import top.niunaijun.blackboxa.view.base.BaseViewModel


class ListViewModel(private val repo: AppsRepository) : BaseViewModel() {

    val appsLiveData = MutableLiveData<List<InstalledAppBean>>()

    val loadingLiveData = MutableLiveData<Boolean>()

    fun previewInstalledList() {
        launchOnUI { repo.previewInstallList() }
    }

    fun getInstallAppList(userID: Int) {
        launchOnUI { repo.getInstalledAppList(userID, loadingLiveData, appsLiveData) }
    }

    /**
     * 每次进入「安装应用」页都重新枚举宿主应用，避免只在 WelcomeActivity 启动时扫描一次。
     * 某些兼容环境（如卓易通容器）里宿主包列表在 App 启动后才会补齐，不刷新会导致列表空白。
     */
    fun refreshInstallableApps(userID: Int) {
        launchOnUI {
            repo.previewInstallList()
            repo.getInstalledAppList(userID, loadingLiveData, appsLiveData)
        }
    }
}
