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

    /** 当前生效的日志级别 */
    @Volatile
    var logLevel: Int = LEVEL_INFO

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
        try {
            when {
                throwable != null -> module?.log(level, tag, line, throwable)
                else -> module?.log(level, tag, line)
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
