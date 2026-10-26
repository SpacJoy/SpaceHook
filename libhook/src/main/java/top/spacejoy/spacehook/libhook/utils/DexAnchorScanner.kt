package top.spacejoy.spacehook.libhook.utils

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * 用 DexKit 在目标 App 的 dex 里按「方法签名」定位混淆类。
 *
 * ## 什么时候用
 *
 * 只在**快速路径失效**时才调用：
 * ```
 * 已知名单（反射，≈0 耗时）
 *    ↓ 没命中
 * manifest 锚点 + 继承链（反射，≈0 耗时）
 *    ↓ 没命中
 * DexKit 扫 dex（首次 100~150ms，之后走缓存）
 * ```
 *
 * 这样绝大多数版本的启动完全不碰原生库。
 *
 * ## 缓存
 *
 * 扫描结果按「规则版本 + APK 路径 + 大小 + 修改时间」做指纹，存进目标 App 的
 * `cacheDir`。App 更新（路径/大小/mtime 必然变化）或规则版本升级时自动失效重扫。
 * 缓存命中**仍会反射校验**，避免指向已不存在的方法。
 *
 * 缓存只是本模块自己的一份属性文件，不读写目标 App 的任何数据。
 */
object DexAnchorScanner {

    /** 规则版本：改动扫描逻辑/判据时加一，让所有缓存失效 */
    private const val RULE_REVISION = 2

    private const val CACHE_FILE = "spacehook_anchor_cache.properties"
    private const val LIBRARY = "dexkit"

    /** 缓存里多个类名的分隔符（类名用点分形式，不会出现逗号） */
    private const val CLASS_SEPARATOR = ","

    /** 原生库只在真正要扫描时才加载（绝大多数进程永远走不到这里） */
    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var libraryError: String? = null

    /**
     * 在 [apkPath] 指向的 dex 里，找**唯一一个**声明了
     * `methodName(paramTypes...)returnType` 的类。
     *
     * @param usingStrings 可选的特征字符串（方法体内出现的常量），用于进一步缩小范围
     * @param log 诊断日志回调
     * @return 唯一命中时返回类名（点分形式，如 `com.a.b`），否则 null
     */
    fun findDeclaringClass(
        loader: ClassLoader,
        apkPath: String?,
        methodName: String,
        paramTypes: Array<Class<*>>,
        returnType: Class<*>,
        usingStrings: List<String> = emptyList(),
        log: (String) -> Unit = {},
    ): String? {
        val all = findDeclaringClasses(loader, apkPath, methodName, paramTypes, returnType, usingStrings, log)
        return if (all.size == 1) all.first() else null
    }

    /**
     * 找**所有**声明了 `methodName(paramTypes...)returnType` 的类（点分类名）。
     *
     * 为什么需要「多个」：同一个功能可能被编译出多份实现。实例是网易云 9.6.13 ——
     * 「心动模式加载弹窗」一共有两个类各自声明了同名同签名的 `showAIModeLoadingDialog`，
     * 一份在播放页基类里、另一份在 `com.cmbridge` 桥接实现里。只挂其中一个，
     * 另一条路径照旧弹窗，表现为「Hook 挂载成功但功能失效」。
     *
     * 因此这里返回全部命中，由调用方决定是否全挂。
     * 注意：返回结果不做「唯一性」判断，空列表表示没找到。
     *
     * @param extraScanPaths 额外的扫描目标（Tinker 热更新补丁等）。见 [tinkerPatchDexPaths]。
     */
    fun findDeclaringClasses(
        loader: ClassLoader,
        apkPath: String?,
        methodName: String,
        paramTypes: Array<Class<*>>,
        returnType: Class<*>,
        usingStrings: List<String> = emptyList(),
        log: (String) -> Unit = {},
        extraScanPaths: List<String> = emptyList(),
    ): List<String> {
        if (apkPath == null) {
            log("拿不到 APK 路径，无法扫描")
            return emptyList()
        }

        val cache = currentApplication()?.let { Cache(File(it.cacheDir, CACHE_FILE)) }
        val cacheKey = cacheKeyOf(apkPath, methodName, paramTypes, returnType, extraScanPaths)

        // 1) 缓存命中：仍要反射校验，校验不过就当没有
        cache?.get(cacheKey)?.let { cached ->
            val valid = cached.split(CLASS_SEPARATOR)
                .filter { it.isNotBlank() }
                .filter { verify(loader, it, methodName, paramTypes, returnType) }
            if (valid.isNotEmpty()) {
                log("DexKit 缓存命中：$valid")
                return valid
            }
            log("DexKit 缓存失效（方法已不存在）：$cached")
        }

        // 2) 真扫：主 APK + 每个额外路径，结果取并集
        val matched = LinkedHashMap<String, String>()
        val targets = listOf(apkPath) + extraScanPaths
        for (target in targets) {
            val found = scan(loader, target, methodName, paramTypes, returnType, usingStrings, log)
            for (name in found) matched.putIfAbsent(name, name)
        }
        val result = matched.keys.toList()
        if (result.isNotEmpty()) cache?.put(cacheKey, result.joinToString(CLASS_SEPARATOR))
        return result
    }

    /**
     * 列出目标 App 的 **Tinker 热更新补丁** 里的 dex 路径，供 [findDeclaringClasses] 扩展扫描。
     *
     * ## 为什么必须扫补丁（2026-10-26 实测教训）
     *
     * 网易云音乐用 Tinker 做热更新。实测日志显示：
     * ```
     * 14:39:43.278  SpaceHook 已挂载 ma#showAIModeLoadingDialog
     * 14:39:43.313  Tinker.TinkerLoader: tryLoad ...     ← 比 Hook 晚 35ms
     * 14:39:43.318  after loaded classloader:
     *               DelegateLastClassLoader[DexPathList[[
     *                 .../tinker/patch-<id>/dex/tinker_classN.apk]]]
     * ```
     *
     * 两个后果：
     * 1. **时序**：Hook 在补丁装载**之前**就挂完了，挂到的是 base.apk 里的那份类；
     * 2. **优先级**：`DelegateLastClassLoader` 让补丁中的类**覆盖** APK 里的同名类。
     *
     * 于是「挂上的 Method」和「实际执行的 Method」是两个不同对象 → 日志显示挂载成功、
     * 却一次都不触发。而且只扫 base.apk 的话，补丁里若新增了实现类根本发现不了。
     *
     * @param packageName 目标包名
     * @param log 诊断日志回调
     * @return 补丁 dex 的绝对路径列表（补丁不存在时为空）
     */
    fun tinkerPatchDexPaths(packageName: String, log: (String) -> Unit = {}): List<String> {
        return try {
            // Tinker 补丁目录：/data/user/0/<包名>/tinker/  或  /data/data/<包名>/tinker/
            //
            // ⚠️ 这两个路径在现代 Android 上**指向同一个目录**（/data/data 是 /data/user/0 的
            // 兼容软链）。实测日志会把同一个 dex 报两遍、DexKit 也白扫一遍，
            // 所以这里按 **canonicalPath** 去重，只扫一次。
            val roots = listOf(
                File("/data/user/0/$packageName/tinker"),
                File("/data/data/$packageName/tinker"),
            )
            val seenDex = LinkedHashSet<String>()
            for (root in roots) {
                if (!root.isDirectory) continue
                // 每个补丁一个 patch-<id> 目录，dex 在其下的 dex/ 里
                val patches = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("patch-") }
                    ?: continue
                for (patch in patches) {
                    val dexDir = File(patch, "dex")
                    val dexFiles = dexDir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }
                        ?: continue
                    for (dex in dexFiles) {
                        val path = dex.canonicalPath
                        if (!seenDex.add(path)) log("跳过重复的补丁路径：$path")
                    }
                }
            }
            val result = seenDex.toList()
            if (result.isEmpty()) log("未发现 Tinker 补丁 dex") else log("发现 Tinker 补丁 dex：$result")
            result
        } catch (t: Throwable) {
            // 读不到（SELinux / 权限）不影响主流程，只是退回「只扫 base.apk」
            log("读取 Tinker 补丁目录失败：${XLog.brief(t)}")
            emptyList()
        }
    }

    // ==================== 扫描 ====================

    private fun scan(
        loader: ClassLoader,
        apkPath: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        returnType: Class<*>,
        usingStrings: List<String>,
        log: (String) -> Unit,
    ): List<String> {
        if (!ensureLibrary(log)) return emptyList()

        var bridge: DexKitBridge? = null
        return try {
            val started = System.nanoTime()
            bridge = DexKitBridge.create(apkPath)

            val matcher = MethodMatcher.create()
                .name(methodName)
                .paramCount(paramTypes.size)
                .returnType(returnType.name)
            if (usingStrings.isNotEmpty()) matcher.usingStrings(usingStrings)

            val hits = bridge.findMethod(FindMethod.create().matcher(matcher))

            // DexKit 的 matcher 可能放宽，逐个用反射精确核对参数类型
            val matched = LinkedHashMap<String, String>()
            for (data in hits) {
                val className = descriptorToName(data.declaredClassName)
                if (className == null) continue
                if (verify(loader, className, methodName, paramTypes, returnType)) {
                    matched[className] = className
                }
            }

            val elapsed = (System.nanoTime() - started) / 1_000_000
            log("DexKit：同名候选 ${hits.size} 个，签名核对通过 ${matched.size} 个，耗时 ${elapsed}ms")

            matched.keys.toList()
        } catch (t: Throwable) {
            log("DexKit 扫描失败：${XLog.brief(t)}")
            emptyList()
        } finally {
            try { bridge?.close() } catch (_: Throwable) { /* 释放失败不影响结果 */ }
        }
    }

    /** `Lcom/a/b;` → `com.a.b` */
    private fun descriptorToName(descriptor: String): String? {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            if (descriptor.startsWith("[")) return null   // 数组类不可能是目标
            return descriptor
        }
        return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    }

    /** 反射核对：类存在、确实自己声明了该方法、参数与返回类型完全一致 */
    private fun verify(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        returnType: Class<*>,
    ): Boolean = try {
        val clazz = Class.forName(className, false, loader)
        val method: Method = clazz.getDeclaredMethod(methodName, *paramTypes)
        method.returnType == returnType
    } catch (_: Throwable) {
        false
    }

    private fun ensureLibrary(log: (String) -> Unit): Boolean {
        libraryError?.let {
            log("DexKit 原生库不可用：$it")
            return false
        }
        if (libraryLoaded) return true
        synchronized(this) {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary(LIBRARY)
                libraryLoaded = true
                log("DexKit 原生库已加载")
                true
            } catch (t: Throwable) {
                libraryError = XLog.brief(t)
                log("DexKit 原生库加载失败（设备 ABI 不支持？）：$libraryError")
                false
            }
        }
    }

    // ==================== 缓存 ====================

    private class Cache(private val file: File) {

        fun get(key: String): String? = try {
            if (!file.exists()) null
            else {
                val props = java.util.Properties()
                file.inputStream().use { props.load(it) }
                props.getProperty(key)
            }
        } catch (_: Throwable) {
            null
        }

        fun put(key: String, value: String) {
            try {
                val props = java.util.Properties()
                if (file.exists()) file.inputStream().use { props.load(it) }
                props.setProperty(key, value)
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { props.store(it, "SpaceHook DexKit anchor cache") }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (_: Throwable) {
                // 缓存写失败不影响功能：下次重新扫描即可
            }
        }
    }

    /**
     * 规则版本 + 各扫描目标的路径/大小/修改时间 + 目标签名，任一变化都会重扫。
     *
     * 补丁 dex 的 size/mtime 必须计入：Tinker 换了新补丁后，
     * 若不失效缓存就会继续用上一版补丁的扫描结果，指向早已不存在的类。
     */
    private fun cacheKeyOf(
        apkPath: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        returnType: Class<*>,
        extraScanPaths: List<String> = emptyList(),
    ): String {
        val identity = buildString {
            append(RULE_REVISION).append('|')
            for (path in listOf(apkPath) + extraScanPaths) {
                val f = File(path)
                append(path).append('|')
                append(f.length()).append('|')
                append(f.lastModified()).append('|')
            }
            append(methodName).append('|')
            paramTypes.forEach { append(it.name).append(',') }
            append('|').append(returnType.name)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ==================== 宿主上下文 ====================

    /**
     * 拿目标 App 的 Application（只为缓存目录）。
     *
     * Hook 侧没有 Context 参数，只能反射 `ActivityThread#currentApplication`。
     * 拿不到就退化为「不缓存」——功能仍然工作，只是每次启动重扫。
     */
    private fun currentApplication(): Context? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val method = activityThread.getDeclaredMethod("currentApplication")
        method.isAccessible = true
        method.invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}
