package top.spacejoy.spacehook.libhook.base

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import top.spacejoy.spacehook.libhook.utils.XLog
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 单个 Hook 功能的基类。
 *
 * 子类只需实现 [onHook]，在内部调用本类提供的 [before] / [replace] / [hookAllMethods] 等便捷方法即可。
 *
 * ## libxposed API 102 的拦截语义
 * 与旧版 XposedBridge 不同，新 API 采用「拦截器链」模型：
 * - [Chain.proceed]     → 继续执行原方法，返回值即最终返回给调用方的值
 * - [Chain.proceedWith] → 跳过原方法，直接返回指定值
 * - [Chain.args]        → **只读**！改参数要拷贝数组后 `proceed(newArgs)`，见下
 * - 拦截块 `return` 的值就是方法返回值（不要忘了 `return chain.proceed()`）
 *
 * ## ⚠️ 改参数不能用下标赋值
 * API 102 的 `Chain.getArgs()` 返回的是框架给的 `Collections$UnmodifiableList`，
 * `chain.args[0] = x` 会抛 `UnsupportedOperationException`（被 try/catch 吞掉后
 * 表现为「hook 静默不生效」或日志刷异常）。正确写法：
 * ```kotlin
 * val newArgs = chain.args.toTypedArray()
 * newArgs[0] = "被改掉的参数"
 * chain.proceed(newArgs)
 * ```
 *
 * ## 写一个 Hook 的标准流程
 * ```kotlin
 * class MyLoad : BaseLoad("my_hook") {
 *     override fun onHook() {
 *         val clazz = requireClass("com.example.Target") ?: return
 *         replace(clazz, "doSomething") { chain ->
 *             val newArgs = chain.args.toTypedArray()
 *             newArgs[0] = "被改掉的参数"
 *             chain.proceed(newArgs)
 *         }
 *     }
 * }
 * ```
 */
abstract class BaseHook(val id: String) {

    lateinit var ctx: HookContext
        private set

    val classLoader: ClassLoader get() = ctx.classLoader
    val packageName: String get() = ctx.packageName

    /** 目标 App 的 base.apk 路径（DexKit 扫描用；system_server 为 null） */
    val apkPath: String? get() = ctx.apkPath

    /** 由框架注入运行时上下文 */
    fun attach(ctx: HookContext) {
        this.ctx = ctx
    }

    /** 子类在此编写具体 Hook 逻辑 */
    abstract fun onHook()

    // ==================== 生效状态统计 ====================
    //
    // 用于「启动提示」汇总：装了几个、几个没装上。
    // 只统计**明确判定为失败**的情况（目标类/方法解析不到），
    // 不统计「本来就该什么都不做」的分支，避免误报。

    private var installedCount = 0
    private var failedCount = 0

    /** 单个 Hook 的装载结果 */
    enum class LoadResult {
        /** 装上了且没有失败 */
        OK,

        /** 有关键锚点解析不到，需要适配新版 */
        FAILED,

        /** 主动跳过（如非主进程），属于正常的不适用 */
        SKIPPED,
    }

    /** 成功挂上的拦截器数量 */
    val installedHooks: Int get() = installedCount

    /** 解析失败的关键锚点数量 */
    val failedAnchors: Int get() = failedCount

    /**
     * 装载结果（供启动提示汇总）：
     * - [LoadResult.OK]        至少装上一个，且没有失败
     * - [LoadResult.FAILED]    有关键锚点解析不到（哪怕部分装上了，也算需要适配）
     * - [LoadResult.SKIPPED]   什么都没做 —— 例如「非主进程，主动跳过」，
     *                          属于正常的「不适用」，不算失败
     */
    val loadResult: LoadResult
        get() = when {
            failedCount > 0 -> LoadResult.FAILED
            installedCount > 0 -> LoadResult.OK
            else -> LoadResult.SKIPPED
        }

    /** 是否确实生效 */
    val isEffective: Boolean get() = loadResult == LoadResult.OK

    /**
     * 标记一次「关键锚点解析失败」。
     *
     * 场景：目标类/方法因版本更新改名后找不到。**必须调用**，
     * 否则 Hook 会安静地什么都不做，UI 与日志都无法区分「失效」和「正常」。
     */
    protected fun markAnchorFailed(reason: String) {
        failedCount++
        loge(reason)
    }

    private fun countInstalled(handle: HookHandle?) {
        if (handle != null) installedCount++ else failedCount++
    }

    // ==================== 日志 ====================

    protected fun log(message: String) = XLog.i(message, id)
    protected fun logd(message: String) = XLog.d(message, id)
    protected fun logw(message: String) = XLog.w(message, id)
    protected fun loge(message: String, t: Throwable? = null) = XLog.e(message, t, id)

    // ==================== 类查找 ====================

    /**
     * 查找目标类。优先使用宿主 ClassLoader，失败后回退到线程上下文 ClassLoader。
     * 找不到返回 null（不抛异常），便于「类不存在就安静跳过」。
     */
    fun findClass(name: String, initialize: Boolean = false): Class<*>? {
        try {
            return Class.forName(name, initialize, classLoader)
        } catch (_: Throwable) { /* 继续尝试 */ }
        try {
            return Class.forName(name, initialize, Thread.currentThread().contextClassLoader)
        } catch (_: Throwable) { /* 继续尝试 */ }
        try {
            return Class.forName(name)
        } catch (_: Throwable) { /* 放弃 */ }
        return null
    }

    /** 查找类，找不到时打一条警告日志并记为一次失败 */
    fun requireClass(name: String): Class<*>? {
        val clazz = findClass(name)
        if (clazz == null) {
            logw("目标类不存在，跳过：$name")
            failedCount++
        }
        return clazz
    }

    // ==================== 方法查找 ====================

    /** 按名称 + 可选参数类型查找方法（含父类） */
    fun findMethod(
        clazz: Class<*>,
        methodName: String,
        paramTypes: Array<Class<*>> = emptyArray(),
    ): Method? {
        if (paramTypes.isNotEmpty()) {
            var c: Class<*>? = clazz
            while (c != null) {
                try {
                    return c.getDeclaredMethod(methodName, *paramTypes)
                } catch (_: Throwable) {
                    c = c.superclass
                }
            }
            return null
        }
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == methodName }?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** 查找类中所有同名方法（重载） */
    fun findMethods(clazz: Class<*>, methodName: String): List<Method> {
        val result = ArrayList<Method>(4)
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.filterTo(result) { it.name == methodName }
            c = c.superclass
        }
        return result
    }

    // ==================== 核心 Hook ====================

    /** 对任意 Executable 挂拦截器 */
    fun hookExecutable(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: ExceptionMode = ExceptionMode.DEFAULT,
        block: (Chain) -> Any?,
    ): HookHandle? {
        return try {
            val handle = ctx.module
                .hook(executable)
                .setPriority(priority)
                .setExceptionMode(exceptionMode)
                .intercept(Hooker { chain -> block(chain) })
            countInstalled(handle)
            handle
        } catch (t: Throwable) {
            loge("hook 失败：${describe(executable)}", t)
            failedCount++
            null
        }
    }

    /**
     * 在方法执行「前」做点事，之后正常执行原方法。
     * 只关心副作用（改参数 / 改字段 / 打日志）时用它。
     */
    fun before(
        clazz: Class<*>,
        methodName: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        block: (Chain) -> Unit,
    ): HookHandle? {
        val method = findMethod(clazz, methodName, paramTypes)
        if (method == null) {
            logw("方法不存在，跳过：${clazz.name}#$methodName")
            failedCount++
            return null
        }
        return hookExecutable(method) { chain ->
            try {
                block(chain)
            } catch (t: Throwable) {
                loge("before 回调异常：${clazz.simpleName}#$methodName", t)
            }
            chain.proceed()
        }
    }

    /**
     * 拦截方法并按 [block] 的返回值决定结果。
     * - 想「改掉返回值跳过原方法」：直接 `return 新值`
     * - 想「拿到原结果再加工」：`val r = chain.proceed(); return 加工(r)`
     */
    fun replace(
        clazz: Class<*>,
        methodName: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        block: (Chain) -> Any?,
    ): HookHandle? {
        val method = findMethod(clazz, methodName, paramTypes)
        if (method == null) {
            logw("方法不存在，跳过：${clazz.name}#$methodName")
            failedCount++
            return null
        }
        return hookExecutable(method, priority) { chain ->
            try {
                block(chain)
            } catch (t: Throwable) {
                loge("replace 回调异常：${clazz.simpleName}#$methodName", t)
                chain.proceed()
            }
        }
    }

    /** 对同名方法的所有重载统一挂拦截器 */
    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        block: (Chain) -> Any?,
    ): List<HookHandle> {
        val methods = findMethods(clazz, methodName)
        if (methods.isEmpty()) {
            logw("方法不存在，跳过：${clazz.name}#$methodName")
            failedCount++
            return emptyList()
        }
        logd("${clazz.simpleName}#$methodName 发现 ${methods.size} 个重载")
        return methods.mapNotNull { method ->
            hookExecutable(method) { chain ->
                try {
                    block(chain)
                } catch (t: Throwable) {
                    loge("重载回调异常：${clazz.simpleName}#$methodName", t)
                    chain.proceed()
                }
            }
        }
    }

    /** 拦截构造方法 */
    fun hookConstructor(
        clazz: Class<*>,
        paramTypes: Array<Class<*>> = emptyArray(),
        block: (Chain) -> Any?,
    ): HookHandle? {
        val ctor: Constructor<*>? = try {
            if (paramTypes.isEmpty()) clazz.declaredConstructors.firstOrNull()
            else clazz.getDeclaredConstructor(*paramTypes)
        } catch (_: Throwable) {
            null
        }
        if (ctor == null) {
            logw("构造方法不存在，跳过：${clazz.name}(...${paramTypes.size})")
            failedCount++
            return null
        }
        return hookExecutable(ctor) { chain ->
            try {
                block(chain)
            } catch (t: Throwable) {
                loge("构造回调异常：${clazz.simpleName}", t)
                chain.proceed()
            }
        }
    }

    /** 拦截全部构造方法 */
    fun hookAllConstructors(clazz: Class<*>, block: (Chain) -> Any?): List<HookHandle> {
        val ctors = clazz.declaredConstructors
        if (ctors.isEmpty()) {
            failedCount++
            return emptyList()
        }
        logd("${clazz.simpleName} 发现 ${ctors.size} 个构造方法")
        return ctors.mapNotNull { ctor ->
            hookExecutable(ctor) { chain ->
                try {
                    block(chain)
                } catch (t: Throwable) {
                    loge("构造回调异常：${clazz.simpleName}", t)
                    chain.proceed()
                }
            }
        }
    }

    // ==================== 反射小工具 ====================

    /**
     * 改写对象字段（沿继承链向上查找）。
     * 常用于把某个私有 boolean 字段钉死。
     */
    fun setField(target: Any?, fieldName: String, value: Any?): Boolean {
        if (target == null) return false
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(target, value)
                return true
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return false
    }

    fun setBooleanField(target: Any?, fieldName: String, value: Boolean): Boolean =
        setField(target, fieldName, value)

    fun getField(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(target)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    fun getStaticObjectField(clazz: Class<*>?, fieldName: String): Any? {
        if (clazz == null) return null
        return try {
            val f = clazz.getDeclaredField(fieldName)
            f.isAccessible = true
            f.get(null)
        } catch (_: Throwable) {
            null
        }
    }

    /** 调用对象方法（按名称 + 参数自动匹配） */
    fun callMethod(target: Any?, methodName: String, vararg args: Any?): Any? {
        if (target == null) return null
        return callAny(target.javaClass, target, methodName, args)
    }

    fun callStaticMethod(clazz: Class<*>?, methodName: String, vararg args: Any?): Any? {
        if (clazz == null) return null
        return callAny(clazz, null, methodName, args)
    }

    private fun callAny(clazz: Class<*>, target: Any?, methodName: String, args: Array<out Any?>): Any? {
        val candidates = findMethods(clazz, methodName)
        for (m in candidates) {
            if (m.parameterCount != args.size) continue
            try {
                m.isAccessible = true
                return m.invoke(target, *args)
            } catch (_: Throwable) { /* 试下一个重载 */ }
        }
        return null
    }

    /** 取枚举常量 */
    fun getEnumConstant(clazz: Class<*>?, name: String): Any? {
        if (clazz == null || !clazz.isEnum) return null
        return try {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            field.get(null)
        } catch (_: Throwable) {
            null
        }
    }

    protected fun describe(executable: Executable): String =
        "${executable.declaringClass.simpleName}#${executable.name}"

    protected fun isStatic(method: Method): Boolean = Modifier.isStatic(method.modifiers)
}
