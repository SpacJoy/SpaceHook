package top.spacejoy.spacehook.libhook.utils

import java.lang.ref.WeakReference

/**
 * 宿主 ClassLoader 查找工具。
 *
 * ## 为什么需要它
 * MIUI 的部分代码（如控制中心「融合设备中心」）被打包在独立的
 * `MIUI SystemUI Plugin`（`miui.systemui.plugin`）里，由 SystemUI 用一个
 * **子 ClassLoader** 动态加载。此时从 `PackageReadyParam.classLoader` 拿到的
 * 主 ClassLoader 里**找不到**这些类，必须拿到 SystemUI 自己创建的那条 loader。
 *
 * 本工具通过反射读取 `ActivityThread.mPackages` 反查已加载 APK 的 ClassLoader。
 *
 * > 注意：`android.app.ActivityThread` 属于 Android 隐藏 API，不在公开 SDK 中，
 * > 因此这里全程使用反射，不做直接引用。
 */
object ClassLoaderUtils {

    private const val CLS_ACTIVITY_THREAD = "android.app.ActivityThread"

    private val cache = HashMap<String, WeakReference<ClassLoader>>()

    /**
     * 找出「能加载 [probeClassName]」的 ClassLoader。
     *
     * @param probeClassName 用于探测的类名（例如 devicecenter 包下的任意类）
     * @param packageHint 目标 APK 的包名前缀，用于缩小扫描范围（可空）
     */
    fun findLoaderFor(
        probeClassName: String,
        packageHint: String? = null,
    ): ClassLoader? {
        cache[probeClassName]?.get()?.let { return it }

        for (loader in collectLoadedClassLoaders(packageHint)) {
            try {
                // 不初始化，避免触发静态块副作用
                Class.forName(probeClassName, false, loader)
                cache[probeClassName] = WeakReference(loader)
                return loader
            } catch (_: Throwable) {
                // 继续试下一个
            }
        }
        return null
    }

    /** 收集当前进程内所有已加载包的 ClassLoader */
    private fun collectLoadedClassLoaders(packageHint: String?): List<ClassLoader> {
        val result = ArrayList<ClassLoader>(8)
        val activityThread = currentActivityThread()
        if (activityThread != null) {
            for (fieldName in arrayOf("mPackages", "mResourcePackages")) {
                try {
                    val field = activityThread.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val map = field.get(activityThread) as? Map<*, *> ?: continue
                    map.forEach { (key, value) ->
                        val name = key?.toString() ?: return@forEach
                        if (packageHint != null && !name.startsWith(packageHint)) return@forEach
                        val loader = loaderOf(value)
                        if (loader != null && loader !in result) result.add(loader)
                    }
                } catch (_: Throwable) {
                    // 字段名在不同 Android 版本可能不同，忽略
                }
            }
        }
        // 兜底：当前线程上下文 ClassLoader
        try {
            Thread.currentThread().contextClassLoader?.let { if (it !in result) result.add(it) }
        } catch (_: Throwable) {
            // 忽略
        }
        return result
    }

    /** 从 mPackages 的值（WeakReference<LoadedApk> 或 LoadedApk）中取出 ClassLoader */
    private fun loaderOf(value: Any?): ClassLoader? {
        if (value == null) return null
        val target: Any = if (value is WeakReference<*>) value.get() ?: return null else value
        return try {
            val method = target.javaClass.getMethod("getClassLoader")
            method.isAccessible = true
            method.invoke(target) as? ClassLoader
        } catch (_: Throwable) {
            null
        }
    }

    /** 反射获取 ActivityThread.currentActivityThread() */
    private fun currentActivityThread(): Any? = try {
        val clazz = Class.forName(CLS_ACTIVITY_THREAD)
        val method = clazz.getDeclaredMethod("currentActivityThread")
        method.isAccessible = true
        method.invoke(null)
    } catch (_: Throwable) {
        null
    }

    fun clear() = cache.clear()
}
