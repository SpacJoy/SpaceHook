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
    private const val RULE_REVISION = 1

    private const val CACHE_FILE = "spacehook_anchor_cache.properties"
    private const val LIBRARY = "dexkit"

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
        if (apkPath == null) {
            log("拿不到 APK 路径，无法扫描")
            return null
        }

        val cache = currentApplication()?.let { Cache(File(it.cacheDir, CACHE_FILE)) }
        val cacheKey = cacheKeyOf(apkPath, methodName, paramTypes, returnType)

        // 1) 缓存命中：仍要反射校验，校验不过就当没有
        cache?.get(cacheKey)?.let { cached ->
            if (verify(loader, cached, methodName, paramTypes, returnType)) {
                log("DexKit 缓存命中：$cached")
                return cached
            }
            log("DexKit 缓存失效（方法已不存在）：$cached")
        }

        // 2) 真扫
        val found = scan(loader, apkPath, methodName, paramTypes, returnType, usingStrings, log)
        if (found != null) cache?.put(cacheKey, found)
        return found
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
    ): String? {
        if (!ensureLibrary(log)) return null

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

            if (matched.size != 1) {
                if (matched.isNotEmpty()) log("命中不唯一，放弃：${matched.keys}")
                return null
            }
            matched.keys.first()
        } catch (t: Throwable) {
            log("DexKit 扫描失败：${XLog.brief(t)}")
            null
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

    /** 规则版本 + APK 路径/大小/修改时间 + 目标签名，任一变化都会重扫 */
    private fun cacheKeyOf(
        apkPath: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        returnType: Class<*>,
    ): String {
        val apk = File(apkPath)
        val identity = buildString {
            append(RULE_REVISION).append('|')
            append(apkPath).append('|')
            append(apk.length()).append('|')
            append(apk.lastModified()).append('|')
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
