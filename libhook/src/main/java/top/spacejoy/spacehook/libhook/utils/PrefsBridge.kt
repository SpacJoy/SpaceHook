package top.spacejoy.spacehook.libhook.utils

import android.content.SharedPreferences
import top.spacejoy.spacehook.common.HookPrefs

/**
 * Hook 侧的配置读取桥。
 *
 * 数据源是框架下发的 [SharedPreferences]（即 LSPosed 的 RemotePreferences），
 * 由模块 App（UI 进程）写入，这里是只读消费方。
 */
object PrefsBridge {

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 本进程内已写过「生效状态」的键，避免重复写 */
    private val markedActive = HashSet<String>()

    fun init(prefs: SharedPreferences?) {
        this.prefs = prefs
        XLog.i("PrefsBridge 已接入，prefs=${prefs != null}")
    }

    fun isReady(): Boolean = prefs != null

    // ==================== 原始读写 ====================

    fun getBoolean(key: String, def: Boolean): Boolean =
        try { prefs?.getBoolean(key, def) ?: def } catch (_: Throwable) { def }

    fun getInt(key: String, def: Int): Int =
        try { prefs?.getInt(key, def) ?: def } catch (_: Throwable) { def }

    fun getString(key: String, def: String?): String? =
        try { prefs?.getString(key, def) ?: def } catch (_: Throwable) { def }

    /**
     * 诊断：导出本进程当前看到的、键名包含 [prefix] 的全部配置。
     *
     * RemotePreferences 是**带缓存的快照**——App 侧写入后要靠框架广播回灌。
     * 排查「DB 里明明有值、Hook 却读到默认值」时，这里才是 Hook 视角的真相。
     */
    fun dump(prefix: String): String = try {
        val all = prefs?.all
        if (all == null) "prefs=null"
        else all.entries
            .filter { it.key.contains(prefix) }
            .sortedBy { it.key }
            .joinToString(", ") { "${it.key}=${it.value}" }
            .ifEmpty { "（无匹配键，共 ${all.size} 个键）" }
    } catch (t: Throwable) {
        "读取失败：$t"
    }

    /**
     * Hook 侧写回「生效状态」，供 UI 展示。
     *
     * **必须用 `commit()` 而不是 `apply()`**：`apply()` 是异步的，
     * 而 Hook 装载发生在新进程刚起来的时刻，异步写入可能随进程状态变化而丢失
     * （实测 `apply()` 写入的键在 LSPosed 的 `module_configs` 表里查不到）。
     * `commit()` 同步返回结果，能确认是否真正落盘。
     *
     * 该调用只在 Hook 装载时发生一次，同步开销可接受。
     */
    fun putBoolean(key: String, value: Boolean): Boolean {
        val p = prefs ?: return false
        return try {
            val ok = p.edit().putBoolean(key, value).commit()
            if (!ok) XLog.w("配置写入未成功（commit=false）：$key")
            ok
        } catch (t: Throwable) {
            XLog.e("配置写入异常：$key", t)
            false
        }
    }

    // ==================== 语义化判断 ====================

    /** 总开关 */
    fun isMasterEnabled(): Boolean = getBoolean(HookPrefs.MASTER_ENABLE, true)

    /** 安全模式（暂停一切 Hook） */
    fun isSafeMode(): Boolean = getBoolean(HookPrefs.SAFE_MODE, false)

    /** 调试模式 */
    fun isDebugMode(): Boolean = getBoolean(HookPrefs.DEBUG_MODE, false)

    /** 某个目标 App 的总开关 */
    fun isAppEnabled(packageName: String): Boolean =
        getBoolean(HookPrefs.appMasterKey(packageName), true)

    /**
     * 该目标 App 是否要弹「启动提示」。
     *
     * 先用总开关 `exp_toast_on_start` 拦一道；总开关打开时，
     * 再看该 App 的单独设置 `exp_toast_app::<包名>`（默认 true，即跟着总开关）。
     * 这样既能一键全开，也能只留某几个 App 不弹。
     */
    fun isToastEnabled(packageName: String): Boolean {
        if (!getBoolean(HookPrefs.TOAST_ON_START, false)) return false
        return getBoolean(HookPrefs.toastAppKey(packageName), true)
    }

    /**
     * 综合判断某个 Hook 是否应当加载：
     * 总开关 → 安全模式 → App 总开关 → 功能开关，全部通过才加载。
     */
    fun isHookEnabled(
        hookId: String,
        packageName: String,
        defaultEnabled: Boolean,
    ): Boolean {
        if (!isMasterEnabled()) {
            XLog.d("跳过 $hookId：全局总开关已关闭")
            return false
        }
        if (isSafeMode()) {
            XLog.d("跳过 $hookId：安全模式已开启")
            return false
        }
        if (!isAppEnabled(packageName)) {
            XLog.d("跳过 $hookId：目标应用 $packageName 总开关已关闭")
            return false
        }
        return getBoolean(HookPrefs.hookKey(hookId), defaultEnabled)
    }

    /**
     * 回写「某 Hook 在某进程内已生效」。
     *
     * 这是 UI 展示「已生效」的**持久化**来源 —— 不依赖该进程当前是否在运行，
     * 因此录音机这类按需启动的 App 关闭后依然能显示它上次生效过。
     */
    fun markActive(hookId: String, processName: String) {
        val key = HookPrefs.hookActiveKey(hookId, processName)
        synchronized(markedActive) {
            if (!markedActive.add(key)) return   // 本进程内已写过，跳过
        }
        val written = putBoolean(key, true)
        val readBack = getBoolean(key, false)
        // 用 ERROR 级别输出诊断 —— 这是排查「配置写入是否真的落盘」的关键证据
        XLog.e("markActive 诊断: key=$key 写入=$written 回读=$readBack prefs=${prefs != null}")
    }

    fun syncLogConfig() {
        XLog.applyConfig(
            level = getInt(HookPrefs.LOG_LEVEL, XLog.LEVEL_DEBUG),
            verbose = isDebugMode(),
        )
    }
}
