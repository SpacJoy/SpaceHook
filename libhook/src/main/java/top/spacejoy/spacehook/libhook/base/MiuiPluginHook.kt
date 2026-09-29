package top.spacejoy.spacehook.libhook.base

import android.content.ContextWrapper
import top.spacejoy.spacehook.libhook.utils.ClassLoaderUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「系统界面组件」(miui.systemui.plugin) 类的 Hook 公共基类。
 *
 * ## 为什么需要它
 * MIUISystemUIPlugin 是 SystemUI **动态加载的插件 APK**，不作为独立进程运行，
 * 其类由 SystemUI 为插件单独创建的 ClassLoader 加载（parent 指向 MiuiSystemUI.apk）。
 * 因此本模块注入 `com.android.systemui` 进程后，还需要拿到那条插件 ClassLoader。
 *
 * ## 捕获路径（参考 HyperStar OS3，真机验证有效）
 * 1. **主路径**：hook `com.android.systemui.shared.plugins.PluginInstance$PluginFactory#createPluginContext()`
 *    （该类在 SystemUI 主 APK 里，主 ClassLoader 可直接加载）。SystemUI 每次创建插件
 *    Context 都会调用它；返回的 `PluginContextWrapper` 重写了 `getClassLoader()`，
 *    返回的正是插件 ClassLoader。按包名过滤出 miui.systemui.plugin。
 * 2. **兜底**：模块加载前插件已创建时，用 [ClassLoaderUtils] 反查
 *    `ActivityThread.mPackages`（三级：主 loader → 前缀过滤 → 全量扫描）。
 *
 * 子类实现 [onPluginLoaded]，在其中用传入的 loader 解析插件类并挂 hook。
 */
abstract class MiuiPluginHook(id: String) : BaseHook(id) {

    private companion object {
        const val CLS_PLUGIN_FACTORY = "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
        const val PLUGIN_PACKAGE = "miui.systemui.plugin"
        const val LOADER_HINT = "miui.systemui"

        /** 探测类：已知存在于 MIUISystemUIPlugin 中，用于兜底扫描验证 loader */
        const val PROBE_CLASS = "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder"
    }

    private val installed = AtomicBoolean(false)

    final override fun onHook() {
        hookPluginFactory()
        tryInstallViaActivityThreadScan()
    }

    /** 插件就绪后在插件 ClassLoader 下安装具体 hook */
    protected abstract fun onPluginLoaded(loader: ClassLoader)

    // ==================== 捕获路径 1：createPluginContext ====================

    private fun hookPluginFactory() {
        val factory = findClass(CLS_PLUGIN_FACTORY)
        if (factory == null) {
            logw("PluginFactory 类不存在（SystemUI 版本变动？），仅剩兜底扫描")
            return
        }
        val method = findMethod(factory, "createPluginContext")
        if (method == null) {
            logw("PluginFactory#createPluginContext 不存在，仅剩兜底扫描")
            return
        }
        hookExecutable(method) { chain ->
            val result = chain.proceed()
            try {
                if (result is ContextWrapper && result.packageName == PLUGIN_PACKAGE) {
                    val loader = result.classLoader
                    logd("捕获插件 ClassLoader：$loader")
                    installOnce(loader)
                }
            } catch (t: Throwable) {
                loge("createPluginContext 回调异常", t)
            }
            result
        }
        logd("已挂 createPluginContext 探针，等待插件加载")
    }

    // ==================== 捕获路径 2：ActivityThread 扫描兜底 ====================

    private fun tryInstallViaActivityThreadScan() {
        findClass(PROBE_CLASS)?.let {
            // 主 loader 就能加载（部分版本插件被合并进主 dex）
            logd("主 ClassLoader 即可加载插件类")
            installOnce(classLoader)
            return
        }
        ClassLoaderUtils.findLoaderFor(PROBE_CLASS, LOADER_HINT)?.let {
            logd("通过 mPackages(hint=\"$LOADER_HINT\") 找到插件 ClassLoader")
            installOnce(it)
            return
        }
        ClassLoaderUtils.findLoaderFor(PROBE_CLASS)?.let {
            logd("通过 mPackages 全量扫描找到插件 ClassLoader")
            installOnce(it)
        }
    }

    private fun installOnce(loader: ClassLoader?) {
        if (loader == null) return
        if (!installed.compareAndSet(false, true)) return
        try {
            onPluginLoaded(loader)
            log("插件 Hook 安装完成")
        } catch (t: Throwable) {
            installed.set(false)
            loge("onPluginLoaded 执行失败", t)
        }
    }
}
