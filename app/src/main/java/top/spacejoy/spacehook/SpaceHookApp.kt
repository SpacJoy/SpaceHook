package top.spacejoy.spacehook

import android.app.Application
import top.spacejoy.spacehook.common.Constants
import top.spacejoy.spacehook.xposed.XposedServiceManager

/**
 * SpaceHook 模块 App 入口。
 *
 * 这里只做两件事：初始化本地设置、注册 Xposed 服务监听（用于判断模块是否已激活）。
 */
class SpaceHookApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsStore.init(this)

        // 与 LSPosed 框架建立连接，用于读取框架信息 / 写入 RemotePreferences / 获取已生效进程
        XposedServiceManager.register(this)
    }

    companion object {
        @Volatile
        lateinit var instance: Application
            private set

        const val LOG_TAG = Constants.LOG_TAG
    }
}
