package top.spacejoy.spacehook.libhook.utils

import io.github.libxposed.api.XposedModule
import top.spacejoy.spacehook.common.Constants

/**
 * 统一日志。
 *
 * Hook 侧运行在被注入的目标 App 进程里，写 /sdcard 往往受分区存储限制，
 * 因此这里统一走 [XposedModule.log]（会同时出现在 LSPosed 日志与 logcat 中）：
 *
 * ```
 * adb logcat -s Xposed:V | grep SpaceHook
 * ```
 */
object XLog {

    const val LEVEL_OFF = 0
    const val LEVEL_ERROR = 1
    const val LEVEL_WARN = 2
    const val LEVEL_INFO = 3
    const val LEVEL_DEBUG = 4

    private var module: XposedModule? = null
    private var processName: String = "?"

    /** 当前生效的日志级别（默认 DEBUG：诊断日志必须可见） */
    @Volatile
    var logLevel: Int = LEVEL_DEBUG

    /** 是否输出调用栈（排障用） */
    @Volatile
    var verbose: Boolean = false

    /** 每个 tag 只打印一次的 key 集合，避免刷屏 */
    private val onceKeys = HashSet<String>()

    fun init(module: XposedModule, processName: String) {
        this.module = module
        this.processName = processName
    }

    fun applyConfig(level: Int, verbose: Boolean) {
        this.logLevel = level
        this.verbose = verbose
    }

    fun processName(): String = processName

    // ==================== 各级别输出 ====================

    fun d(message: String, tag: String = Constants.LOG_TAG) = log(LEVEL_DEBUG, tag, message)

    fun i(message: String, tag: String = Constants.LOG_TAG) = log(LEVEL_INFO, tag, message)

    fun w(message: String, tag: String = Constants.LOG_TAG) = log(LEVEL_WARN, tag, message)

    fun w(message: String, throwable: Throwable?, tag: String = Constants.LOG_TAG) =
        log(LEVEL_WARN, tag, message, throwable)

    fun e(message: String, tag: String = Constants.LOG_TAG) = log(LEVEL_ERROR, tag, message)

    fun e(message: String, throwable: Throwable?, tag: String = Constants.LOG_TAG) =
        log(LEVEL_ERROR, tag, message, throwable)

    /** 同一 key 只输出一次（用于「已 hook 成功」这类噪音日志） */
    fun once(key: String, message: String, tag: String = Constants.LOG_TAG) {
        val first = synchronized(onceKeys) { onceKeys.add(key) }
        if (first) log(LEVEL_INFO, tag, message)
    }

    fun log(level: Int, tag: String, message: String, throwable: Throwable? = null) {
        if (level > logLevel) return
        val line = "[$processName] $message"
        // XposedModule.log 的 priority 是 android.util.Log 级别（VERBOSE=2..ERROR=6），
        // 与本类的 LEVEL_*（0..4）不同轴，必须映射，否则 ERROR(1) 会被 LSPosed 丢弃。
        val priority = when (level) {
            LEVEL_ERROR -> 6   // Log.ERROR
            LEVEL_WARN -> 5    // Log.WARN
            LEVEL_INFO -> 4    // Log.INFO
            LEVEL_DEBUG -> 3   // Log.DEBUG
            else -> return
        }
        try {
            when {
                throwable != null -> module?.log(priority, tag, line, throwable)
                else -> module?.log(priority, tag, line)
            }
        } catch (_: Throwable) {
            // module 尚未初始化时忽略
        }
    }

    /** 把异常转成简短字符串，避免日志过长 */
    fun brief(t: Throwable?): String = when (t) {
        null -> "null"
        else -> "${t.javaClass.simpleName}: ${t.message ?: ""}"
    }
}
