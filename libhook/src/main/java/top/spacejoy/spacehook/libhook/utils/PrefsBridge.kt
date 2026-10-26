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
     *
     * ⚠️ **写不进去是常态，不是故障。** LSPosed 下发给模块的 RemotePreferences
     * 在某些版本/配置下是**只读实现**，`edit()` 直接抛
     * `UnsupportedOperationException: Read only implementation`。
     * 此时「已生效」状态只能退化为「本进程内可见」，UI 侧另有 `local_seen_processes`
     * 本地兜底记录，功能与展示都不受影响。因此这里用 WARN 而不是 ERROR ——
     * 原来每个 Hook 每次启动都刷一串 ERROR，会把真正的故障淹掉。
     */
    fun putBoolean(key: String, value: Boolean): Boolean {
        val p = prefs ?: return false
        return try {
            val ok = p.edit().putBoolean(key, value).commit()
            if (!ok) XLog.w("配置写入未成功（commit=false）：$key")
            ok
        } catch (t: Throwable) {
            if (isReadOnly(t)) {
                XLog.w("配置为只读实现，跳过写入（不影响功能）：$key —— ${XLog.brief(t)}")
            } else {
                XLog.e("配置写入异常：$key", t)
            }
            false
        }
    }

    /**
     * 判断这次写入失败是否只是「框架下发的是只读 Preferences」。
     *
     * 判据以**异常类型**为主：`SharedPreferences.Editor#commit()` 在正常实现里不会抛
     * `UnsupportedOperationException`（那表示「这个操作根本没实现」），只有只读的代理实现才会。
     * 消息文本仅作补充（不同 LSPosed 版本措辞可能不同，不能作为唯一依据）。
     *
     * 其他异常（IO、权限、序列化）一律按真异常报 ERROR，避免把故障当正常现象吞掉。
     */
    private fun isReadOnly(t: Throwable): Boolean {
        if (t !is UnsupportedOperationException) return false
        // 消息为空也判为只读（类型已经足够说明问题），不再因措辞不同而漏判成噪声
        val msg = t.message?.lowercase() ?: return true
        return "read only" in msg || "readonly" in msg || "read-only" in msg ||
            "unsupported" in msg || "not supported" in msg
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
     *
     * 写入失败（只读 Preferences）是常态，不是故障，所以诊断日志用 DEBUG：
     * 排查时调高日志级别就能看到，平时不刷屏。
     */
    fun markActive(hookId: String, processName: String) {
        val key = HookPrefs.hookActiveKey(hookId, processName)
        synchronized(markedActive) {
            if (!markedActive.add(key)) return   // 本进程内已写过，跳过
        }
        val written = putBoolean(key, true)
        // 只写成功时才回读校验；失败情形已在 putBoolean 里按只读/真异常分级别记录。
        // 失败时仍保留占位、不重试：本方法在 Hook 装载时只调用一次，
        // 只读 Preferences 重试多少次都是失败，没必要重复刷日志。
        if (written) {
            val readBack = getBoolean(key, false)
            XLog.d("markActive：key=$key 写入成功 回读=$readBack")
        }
    }

    fun syncLogConfig() {
        XLog.applyConfig(
            level = getInt(HookPrefs.LOG_LEVEL, XLog.LEVEL_DEBUG),
            verbose = isDebugMode(),
        )
    }
}
