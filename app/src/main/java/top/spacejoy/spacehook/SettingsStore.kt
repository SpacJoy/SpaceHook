package top.spacejoy.spacehook

import android.content.Context
import android.content.SharedPreferences
import top.spacejoy.spacehook.common.Constants
import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.xposed.XposedServiceManager

/**
 * 设置读写统一入口。
 *
 * ## 存储策略
 * - **Hook 相关配置**（开关 / 安全模式 / 功能参数）写入框架的 RemotePreferences，
 *   这样被注入的目标 App 进程才能读到；
 * - 若模块尚未激活（拿不到 RemotePreferences），退化为写本地 SharedPreferences，
 *   UI 仍可操作，但不会生效 —— 界面上会给出「未激活」提示。
 */
object SettingsStore {

    private lateinit var local: SharedPreferences

    fun init(context: Context) {
        local = context.applicationContext
            .getSharedPreferences(Constants.LOCAL_PREFS, Context.MODE_PRIVATE)
    }

    /** 实际生效的偏好存储：优先远程，回退本地 */
    private fun store(): SharedPreferences = XposedServiceManager.remotePrefs() ?: local

    val isRemote: Boolean get() = XposedServiceManager.remotePrefs() != null

    // ==================== 基础读写 ====================

    fun getBoolean(key: String, def: Boolean): Boolean =
        try { store().getBoolean(key, def) } catch (_: Throwable) { def }

    fun putBoolean(key: String, value: Boolean) {
        try { store().edit().putBoolean(key, value).apply() } catch (_: Throwable) { }
    }

    fun getInt(key: String, def: Int): Int =
        try { store().getInt(key, def) } catch (_: Throwable) { def }

    fun putInt(key: String, value: Int) {
        try { store().edit().putInt(key, value).apply() } catch (_: Throwable) { }
    }

    fun getString(key: String, def: String?): String? =
        try { store().getString(key, def) } catch (_: Throwable) { def }

    fun putString(key: String, value: String?) {
        try { store().edit().putString(key, value).apply() } catch (_: Throwable) { }
    }

    // ==================== 全局开关 ====================

    var masterEnabled: Boolean
        get() = getBoolean(HookPrefs.MASTER_ENABLE, true)
        set(v) = putBoolean(HookPrefs.MASTER_ENABLE, v)

    var safeMode: Boolean
        get() = getBoolean(HookPrefs.SAFE_MODE, false)
        set(v) = putBoolean(HookPrefs.SAFE_MODE, v)

    var debugMode: Boolean
        get() = getBoolean(HookPrefs.DEBUG_MODE, false)
        set(v) = putBoolean(HookPrefs.DEBUG_MODE, v)

    var logLevel: Int
        get() = getInt(HookPrefs.LOG_LEVEL, 3)
        set(v) = putInt(HookPrefs.LOG_LEVEL, v)

    var predictiveBack: Boolean
        get() = getBoolean(HookPrefs.PREDICTIVE_BACK, true)
        set(v) = putBoolean(HookPrefs.PREDICTIVE_BACK, v)

    var blurEnabled: Boolean
        get() = getBoolean(HookPrefs.BLUR_ENABLE, false)
        set(v) = putBoolean(HookPrefs.BLUR_ENABLE, v)

    var hotReload: Boolean
        get() = getBoolean(HookPrefs.HOT_RELOAD, true)
        set(v) = putBoolean(HookPrefs.HOT_RELOAD, v)

    /** 目标 App 启动时用 Toast 提示 Hook 装载结果 */
    var toastOnStart: Boolean
        get() = getBoolean(HookPrefs.TOAST_ON_START, false)
        set(v) = putBoolean(HookPrefs.TOAST_ON_START, v)

    // ==================== 界面设置 ====================

    var themeMode: Int
        get() = getInt(HookPrefs.THEME_MODE, 0)
        set(v) = putInt(HookPrefs.THEME_MODE, v)

    var colorKey: Long
        get() = getString(HookPrefs.COLOR_KEY, null)?.toLongOrNull() ?: 0L
        set(v) = putString(HookPrefs.COLOR_KEY, v.toString())

    var fontScale: Int
        get() = getInt(HookPrefs.FONT_SCALE, 100)
        set(v) = putInt(HookPrefs.FONT_SCALE, v)

    var showSystemApps: Boolean
        get() = getBoolean(HookPrefs.SHOW_SYSTEM_APPS, true)
        set(v) = putBoolean(HookPrefs.SHOW_SYSTEM_APPS, v)

    // ==================== Hook 开关 ====================

    fun isAppEnabled(packageName: String): Boolean =
        getBoolean(HookPrefs.appMasterKey(packageName), true)

    fun setAppEnabled(packageName: String, enabled: Boolean) =
        putBoolean(HookPrefs.appMasterKey(packageName), enabled)

    fun isHookEnabled(hookId: String, default: Boolean = true): Boolean =
        getBoolean(HookPrefs.hookKey(hookId), default)

    fun setHookEnabled(hookId: String, enabled: Boolean) =
        putBoolean(HookPrefs.hookKey(hookId), enabled)

    /** 某个 Hook 是否已在至少一个进程内生效 */
    fun isHookActive(hookId: String): Boolean {
        val prefs = store()
        return try {
            prefs.all.keys.any { it.startsWith("active::$hookId::") && prefs.getBoolean(it, false) }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 全部「已生效」记录：`hookId -> 进程名列表`。
     *
     * 数据由 Hook 侧的 `PrefsBridge.markActive()` 持久化写入，
     * **不依赖目标进程当前是否在运行** —— 所以录音机这类按需启动的 App
     * 关闭后依然能看到「曾生效」记录。
     */
    fun activeRecords(): Map<String, List<String>> {
        val prefs = store()
        val result = LinkedHashMap<String, MutableList<String>>()
        try {
            prefs.all.forEach { (key, value) ->
                if (!key.startsWith("active::") || value != true) return@forEach
                val parts = key.split("::")
                if (parts.size == 3) {
                    result.getOrPut(parts[1]) { mutableListOf() }.add(parts[2])
                }
            }
        } catch (_: Throwable) {
            // 读取失败时返回已收集到的部分
        }
        return result
    }

    /** 曾生效过的进程名集合（跨所有 Hook 去重） */
    fun activeProcessNames(): Set<String> = seenProcessNames()

    // ==================== 本地「曾生效」记录 ====================

    private const val KEY_SEEN_PROCESSES = "local_seen_processes"

    /**
     * 记录本次从框架看到的已注入进程。
     *
     * 为什么记在**本地**而不是依赖 Hook 侧写 RemotePreferences：
     * Hook 侧通过 `getRemotePreferences()` 回写「已生效」标记实测不稳定
     * （LSPosed 的 `module_configs` 表始终为空），而 UI 侧本来就能从
     * `getRunningTargets()` 拿到实时进程列表。把它记在本地，
     * 效果一样但完全在 UI 进程内闭环，不依赖跨进程写入。
     */
    fun rememberActiveProcesses(processNames: Collection<String>) {
        if (processNames.isEmpty()) return
        try {
            val existing = local.getStringSet(KEY_SEEN_PROCESSES, emptySet())
                ?.toMutableSet() ?: mutableSetOf()
            val before = existing.size
            existing.addAll(processNames)
            if (existing.size != before) {
                local.edit().putStringSet(KEY_SEEN_PROCESSES, existing).apply()
            }
        } catch (_: Throwable) {
            // 记录失败不影响主流程
        }
    }

    /** 历史记录中出现过的进程名 */
    fun seenProcessNames(): Set<String> = try {
        local.getStringSet(KEY_SEEN_PROCESSES, emptySet()) ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }

    /** 清空「曾生效」历史 */
    fun clearSeenProcesses() {
        try {
            local.edit().remove(KEY_SEEN_PROCESSES).apply()
        } catch (_: Throwable) {
        }
    }

    // ==================== Hook 功能参数 ====================

    fun getConfigInt(hookId: String, key: String, def: Int): Int =
        getInt(HookPrefs.configKey(hookId, key), def)

    fun setConfigInt(hookId: String, key: String, value: Int) =
        putInt(HookPrefs.configKey(hookId, key), value)

    fun getConfigString(hookId: String, key: String, def: String?): String? =
        getString(HookPrefs.configKey(hookId, key), def)

    fun setConfigString(hookId: String, key: String, value: String?) =
        putString(HookPrefs.configKey(hookId, key), value)
}
