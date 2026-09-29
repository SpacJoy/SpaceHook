package top.spacejoy.spacehook.libhook.base

import io.github.libxposed.api.XposedModule
import top.spacejoy.spacehook.common.Constants

/**
 * 一次 Hook 分发所处的运行时环境。
 *
 * 每个被注入的进程（普通 App 进程 / system_server）在 [SpaceHookEntry] 分发时构造一份，
 * 并透传给该进程内所有 [BaseHook]。
 */
class HookContext(
    /** 当前进程所属包名；system_server 时为 [Constants.TARGET_SYSTEM] */
    val packageName: String,

    /** 当前进程名（可能是 :remote 之类的子进程） */
    val processName: String,

    /** 宿主（目标 App）的类加载器 —— 查找目标类必须用它 */
    val classLoader: ClassLoader,

    /** 模块实例，用于执行真正的 hook / 读取框架信息 */
    val module: XposedModule,

    /** 是否运行在 system_server 中 */
    val isSystemServer: Boolean,
) {
    /** 当前进程是否就是该 App 的主进程 */
    val isMainProcess: Boolean = processName == packageName

    override fun toString(): String =
        "HookContext(pkg=$packageName, proc=$processName, systemServer=$isSystemServer)"
}
