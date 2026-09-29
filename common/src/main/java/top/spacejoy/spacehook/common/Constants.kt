package top.spacejoy.spacehook.common

/**
 * 全局常量。
 *
 * ⚠️ 本文件同时被「模块 App 进程」和「被注入的目标 App 进程」使用，
 * 因此不允许出现任何 libxposed / Compose 依赖。
 */
object Constants {

    /** 模块 ApplicationId（同时作为模块标识） */
    const val MODULE_PACKAGE = "top.spacejoy.spacehook"

    /** 模块显示名 */
    const val MODULE_NAME = "SpaceHook"

    /**
     * RemotePreferences 组名。
     * 框架侧（Hook 进程）通过 getRemotePreferences(PREFS_GROUP) 读取；
     * 模块 App 侧经 XposedService 写入同一组，实现跨进程配置同步。
     */
    const val PREFS_GROUP = "spacehook_config"

    /** 模块 App 自身使用的本地 SharedPreferences 文件名（仅 UI 侧，如主题等） */
    const val LOCAL_PREFS = "spacehook_local"

    /** 模块广播 Action 前缀 */
    const val ACTION_PREFIX = "top.spacejoy.spacehook.action."

    /** 日志 TAG */
    const val LOG_TAG = "SpaceHook"

    /** 日志文件目录（便于 adb 抓取） */
    const val LOG_DIR = "/sdcard/SpaceHook/logs"

    /** 安全模式：开机后自动关闭全部 Hook，防止无限崩溃 */
    const val SAFE_MODE_FLAG_FILE = "/sdcard/SpaceHook/.safe_mode"

    /** 作用域通配：表示该 Hook 对「任意被勾选的应用」生效 */
    const val TARGET_WILDCARD_ALL = "*"

    /** 作用域通配：系统框架（system_server） */
    const val TARGET_SYSTEM = "system"

    /** 是否系统应用判定前缀 */
    val SYSTEM_PACKAGE_PREFIXES = listOf("com.android.", "com.miui.", "com.xiaomi.", "miui.")
}
