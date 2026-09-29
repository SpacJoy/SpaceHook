package top.spacejoy.spacehook.libhook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import top.spacejoy.spacehook.common.Constants
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.libhook.base.BaseHook
import top.spacejoy.spacehook.libhook.base.HookContext
import top.spacejoy.spacehook.libhook.base.HookRegistry
import top.spacejoy.spacehook.libhook.utils.PrefsBridge
import top.spacejoy.spacehook.libhook.utils.XLog

/**
 * SpaceHook 模块入口（libxposed API 102）。
 *
 * 由 `META-INF/xposed/java_init.list` 声明，框架实例化后按生命周期回调。
 *
 * ## 分发流程
 * ```
 * onModuleLoaded        → 记录进程名、初始化日志与配置桥
 * onSystemServerStarting→ 分发 system_server 专属 Hook
 * onPackageReady        → 按包名分发普通 App Hook（仅首次加载，避免重复）
 * ```
 */
class SpaceHookEntry : XposedModule() {

    @Volatile
    private var processName: String = "?"

    /** 记录本进程已经加载过的包，避免重复分发 */
    private val loadedPackages = HashSet<String>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        XLog.init(this, processName)
        XLog.i(
            "SpaceHook 已注入：process=$processName, " +
                "framework=${runCatching { frameworkName }.getOrNull()} " +
                "${runCatching { frameworkVersion }.getOrNull()} " +
                "(API ${runCatching { apiVersion }.getOrNull()})"
        )

        // 接入跨进程配置
        try {
            PrefsBridge.init(getRemotePreferences(Constants.PREFS_GROUP))
            PrefsBridge.syncLogConfig()
        } catch (t: Throwable) {
            XLog.e("RemotePreferences 初始化失败，开关将退化为默认值", t)
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        dispatch(
            packageName = Constants.TARGET_SYSTEM,
            classLoader = param.classLoader,
            isSystemServer = true,
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // ⚠️ 这里**不要**依赖 `param.isFirstPackage` 做过滤。
        // 实测某些应用（如 123云盘）该标记为 false，会导致 onPackageReady 被静默跳过。
        // 去重交给 dispatch() 内部的 loadedPackages 处理。
        //
        // 另注：实测 123云盘的 Hook 功能本身是正常的，但 LSPosed 日志里
        // 可能看不到「匹配到 N 个 Hook」这行 —— 属于日志侧的缓冲/分文件问题，
        // 不影响功能，排查时以实际行为为准。
        dispatch(
            packageName = param.packageName,
            classLoader = param.classLoader,
            isSystemServer = false,
        )
    }

    // ==================== 核心分发 ====================

    private fun dispatch(packageName: String, classLoader: ClassLoader, isSystemServer: Boolean) {
        synchronized(loadedPackages) {
            if (!loadedPackages.add(packageName)) {
                XLog.d("$packageName 已分发过，跳过")
                return
            }
        }

        // 全局闸门
        if (!PrefsBridge.isMasterEnabled()) {
            XLog.i("[$packageName] 全局总开关已关闭，跳过全部 Hook")
            return
        }
        if (PrefsBridge.isSafeMode()) {
            XLog.i("[$packageName] 安全模式已开启，跳过全部 Hook")
            return
        }

        val metas = HookCatalog.hooksFor(packageName)
        if (metas.isEmpty()) {
            // 用 INFO 级别：排查「为什么某个应用没生效」时这是关键线索
            XLog.i("[$packageName] 没有匹配的 Hook（HookCatalog 中未登记该包名）")
            return
        }

        val ctx = HookContext(
            packageName = packageName,
            processName = processName,
            classLoader = classLoader,
            module = this,
            isSystemServer = isSystemServer,
        )
        XLog.i("[$packageName] 匹配到 ${metas.size} 个 Hook：${metas.joinToString { it.id }}")

        var okCount = 0
        metas.forEach { meta ->
            if (!PrefsBridge.isHookEnabled(meta.id, packageName, meta.defaultEnabled)) {
                XLog.i("[$packageName] ${meta.id} 已关闭，跳过")
                return@forEach
            }

            val hook = HookRegistry.create(meta.id)
            if (hook == null) {
                XLog.e("[$packageName] ${meta.id} 未在 HookRegistry 中注册，跳过")
                return@forEach
            }

            if (loadHook(hook, ctx, meta.id)) okCount++
        }

        XLog.i("[$packageName] 分发完成：成功 $okCount / 共 ${metas.size}")
    }

    private fun loadHook(hook: BaseHook, ctx: HookContext, hookId: String): Boolean {
        return try {
            hook.attach(ctx)
            hook.onHook()
            // 回写「已生效」状态，供 UI 展示
            PrefsBridge.markActive(hookId, ctx.processName)
            XLog.i("[$hookId] Hook 装载成功 @ ${ctx.packageName}")
            true
        } catch (t: Throwable) {
            XLog.e("[$hookId] Hook 装载失败 @ ${ctx.packageName}", t)
            false
        }
    }
}
