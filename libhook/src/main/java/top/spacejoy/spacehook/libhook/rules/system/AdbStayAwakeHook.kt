package top.spacejoy.spacehook.libhook.rules.system

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import top.spacejoy.spacehook.libhook.base.BaseHook
import java.lang.reflect.Method

/**
 * ADB 调试常亮 —— **已连接调试工具**时屏幕永不因超时熄屏，断开调试工具立即恢复原有屏幕超时。
 *
 * 移植自参考项目 **AdbStayAwake**（`GeneralPeople1970/AdbStayAwake`，libxposed API 102）。
 * 按用户需求（2026-10-03）把触发条件从原实现的「开启 USB / 无线调试（`adb_enabled = 1`）」
 * **收紧**为「存在活动的 ADB 连接」—— 只开着调试开关但没连电脑时不再强制常亮。
 *
 * 目标进程：**system_server**（`scope.list` 的 `system`），经
 * [top.spacejoy.spacehook.libhook.SpaceHookEntry.onSystemServerStarting] 分发。
 *
 * ## 判定逻辑（本次改动的核心）
 *
 * ```
 * 常亮 = adb_enabled == 1  &&  ( adb_connected == 1  ||  USB 线上启用 adb function )
 * ```
 *
 * - **`adb_enabled`**（`Settings.Global.ADB_ENABLED`）：前置条件。USB 调试开关关闭时
 *   adbd 不可用，必然没有调试连接。
 * - **`adb_connected`**（`Settings.Global.ADB_CONNECTED`，key 字符串 "adb_connected"）：
 *   AOSP 的 adbd 连接状态，调试客户端连上 / 断开时由 AdbService / AdbDebuggingManager 更新，
 *   USB 与无线连接均会翻转。HyperOS 上是否维护需真机验证，故再加 USB 兜底。
 * - **USB 兜底**：`android.hardware.usb.action.USB_STATE`（sticky 系统广播），
 *   `connected=true` 且 `functions` 含 "adb"（或 extra `adb=true`）时视为 USB 调试连接。
 *
 * ## 实现要点（沿用原实现的四个需求点，仅语义收紧）
 * 1. **监听 ADB 状态**：[ContentObserver] 同时跟踪 `adb_enabled` / `adb_connected` 两个
 *    Global 设置，另注册 [BroadcastReceiver] 跟踪 USB_STATE，全部缓存在 companion。
 * 2. **强制保持常亮**：Hook `PowerManagerService#getScreenOffTimeoutLocked(...)`（按名字匹配所有重载），
 *    判定为真时返回 `Integer.MAX_VALUE`（约 24.8 天），系统永不因超时自动熄屏。
 * 3. **允许手动锁屏**：不 Hook 任何电源键（KEYCODE_POWER）路径 —— 电源键触发的 `goToSleep`
 *    不经过屏幕超时计算，按电源键依旧能立即锁屏。
 * 4. **断开后恢复**：不永久修改任何系统字段；连接断开后直接透传 `chain.proceed()` 原值，
 *    用户原有的屏幕超时设置立即自动生效。
 *
 * ## 两个关键坑（原实现注释中实测踩过，务必保留）
 * - **返回值类型必须与被 Hook 方法的真实返回类型一致**：不同 ROM 上
 *   `getScreenOffTimeoutLocked` 返回 int 或 long（Xiaomi HyperOS / Android 15 为 long）。
 *   类型不匹配时框架 PROTECTIVE 模式回填会抛 `ClassCastException`，曾导致 system_server
 *   开机阶段崩溃进而 bootloop —— 因此以原返回值的运行时类型为准返回同类型极大值。
 * - **`ensureAdbObserver` 在持有 `PowerManagerService.mLock` 时被调用**：
 *   绝不能在其中做 Binder IPC（读 Settings / 注册 ContentObserver），否则可能锁序颠倒 /
 *   Watchdog 超时。只做廉价反射取 Context，真正的 IPC 投递到主线程 Looper 执行。
 */
class AdbStayAwakeHook : BaseHook("adb_stay_awake") {

    companion object {
        /** system_server 中电源管理服务的完整类名 */
        private const val CLASS_PMS = "com.android.server.power.PowerManagerService"

        /** 计算「屏幕超时」的方法名（不同 Android 版本参数不同，按名字匹配所有重载） */
        private const val METHOD_TIMEOUT = "getScreenOffTimeoutLocked"

        /** `Settings.Global.ADB_CONNECTED` 的 key（用字符串常量，避免编译期绑定隐藏/弃用 API） */
        private const val KEY_ADB_CONNECTED = "adb_connected"

        /** USB 状态 sticky 系统广播 */
        private const val USB_STATE_ACTION = "android.hardware.usb.action.USB_STATE"

        /** 已知会调用超时方法的调用者，去优化以规避方法内联导致 Hook 不生效 */
        private val CANDIDATE_CALLERS = listOf(
            "updateUserActivitySummaryLocked",
            "updatePowerStateLocked",
        )

        /** 缓存：USB / 无线调试总开关（前置条件，关闭则必然没有调试连接） */
        @Volatile
        private var adbEnabled: Boolean = false

        /** 缓存：adbd 连接状态（`Settings.Global.ADB_CONNECTED`） */
        @Volatile
        private var adbConnected: Boolean = false

        /** 缓存：USB 线连接且启用 adb function（USB_STATE 广播兜底信号） */
        @Volatile
        private var usbAdbActive: Boolean = false

        /** ContentObserver / BroadcastReceiver 是否已注册成功（幂等） */
        @Volatile
        private var observerRegistered: Boolean = false

        /** 是否已把注册任务投递到主线程（避免在锁内重复投递） */
        @Volatile
        private var registrationScheduled: Boolean = false

        /** 常亮判定：调试开关打开 **且**（adbd 已连接客户端 **或** USB 线启用 adb） */
        fun shouldKeepAwake(): Boolean = adbEnabled && (adbConnected || usbAdbActive)
    }

    override fun onHook() {
        val pms = requireClass(CLASS_PMS) ?: return

        var hooked = 0
        for (m in pms.declaredMethods) {
            if (m.name != METHOD_TIMEOUT) continue
            if (hookTimeout(m)) hooked++
        }

        // best-effort：去优化已知调用者，降低因内联而 Hook 不触发的概率
        for (caller in CANDIDATE_CALLERS) {
            for (m in pms.declaredMethods) {
                if (m.name == caller) tryDeoptimize(m)
            }
        }

        if (hooked == 0) {
            logw("未找到 $METHOD_TIMEOUT，当前 ROM 可能改动了实现，本功能可能不生效")
        } else {
            log("$CLASS_PMS 已 Hook，$METHOD_TIMEOUT 共 $hooked 个重载")
        }
    }

    /** 对单个 `getScreenOffTimeoutLocked` 重载挂拦截器 */
    private fun hookTimeout(method: Method): Boolean {
        val handle = hookExecutable(method) { chain ->
            // 先执行原始逻辑（纯 getter，无副作用），保持拦截器链礼貌并拿到原值
            val original = chain.proceed()

            // 首次进入时利用 PowerManagerService 实例懒注册 ADB 状态监听
            ensureAdbObserver(chain.thisObject)

            if (shouldKeepAwake()) {
                // 关键：返回值类型必须与被 Hook 方法的真实返回类型一致（见类注释「两个关键坑」）
                if (original is Long) {
                    Integer.MAX_VALUE.toLong()
                } else {
                    Integer.MAX_VALUE
                }
            } else {
                original
            }
        }
        if (handle != null) {
            tryDeoptimize(method)
            return true
        }
        return false
    }

    private fun tryDeoptimize(m: Method) {
        try {
            ctx.module.deoptimize(m)
        } catch (_: Throwable) {
            // 去优化失败不影响主流程
        }
    }

    /**
     * 懒调度 ADB 状态监听的注册。
     *
     * ⚠️ 本方法由 `getScreenOffTimeoutLocked` 在持有 `PowerManagerService.mLock` 的情况下调用，
     * 因此**绝不**做任何 Binder IPC（读 Settings / 注册 ContentObserver），否则可能锁序颠倒 /
     * Watchdog 超时。只做廉价反射取 Context，然后把真正的 IPC 工作投递到主线程 Looper，脱离 mLock。
     */
    private fun ensureAdbObserver(pmsInstance: Any?) {
        if (registrationScheduled || pmsInstance == null) return
        synchronized(AdbStayAwakeHook::class.java) {
            if (registrationScheduled) return
            // 仅做反射（读取实例字段，无 IPC），拿到 Context
            val ctxObj = findContext(pmsInstance) ?: return // 拿不到 Context，下次调用再试
            registrationScheduled = true

            // 把 IPC 密集的注册工作放到主线程执行，避免在 mLock 下进行 Binder 调用
            Handler(Looper.getMainLooper()).post { registerObserver(ctxObj) }
        }
    }

    /**
     * 真正的注册逻辑，运行在主线程（不持有 PMS 锁）：
     * 读取初始 adb_enabled / adb_connected，注册 ContentObserver 与 USB_STATE BroadcastReceiver。
     */
    private fun registerObserver(ctxObj: Context) {
        if (observerRegistered) return
        try {
            val cr = ctxObj.contentResolver

            // 读取初始 ADB 状态
            refreshAdbSettings(cr)

            // 在主线程 Looper 上分发变更回调
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    refreshAdbSettings(cr)
                }
            }
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(KEY_ADB_CONNECTED),
                false,
                observer,
            )

            // USB 兜底：USB_STATE 是 sticky 广播，注册即返回最近一次状态
            val usbFilter = IntentFilter(USB_STATE_ACTION)
            val usbReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != USB_STATE_ACTION) return
                    updateUsbAdbState(parseUsbAdbState(intent))
                }
            }
            val sticky = registerReceiverCompat(ctxObj, usbReceiver, usbFilter)
            if (sticky != null) {
                updateUsbAdbState(parseUsbAdbState(sticky))
            }

            observerRegistered = true
            log(
                "ADB 连接监听已注册，初始 enabled=$adbEnabled connected=$adbConnected usbAdb=$usbAdbActive"
            )
        } catch (t: Throwable) {
            // 注册失败：复位调度标志，允许后续再次尝试
            registrationScheduled = false
            loge("注册 ADB 连接监听失败", t)
        }
    }

    /** 重读 `adb_enabled` / `adb_connected` 两个全局设置并更新缓存 */
    private fun refreshAdbSettings(cr: ContentResolver) {
        val enabled = readGlobalBool(cr, Settings.Global.ADB_ENABLED)
        val connected = readGlobalBool(cr, KEY_ADB_CONNECTED)
        if (enabled != adbEnabled || connected != adbConnected) {
            adbEnabled = enabled
            adbConnected = connected
            log("adb state changed -> enabled=$enabled connected=$connected usbAdb=$usbAdbActive")
        }
    }

    /** 解析 USB_STATE 广播：USB 已连接且启用 adb function（`functions` 含 "adb" 或 extra `adb`） */
    private fun parseUsbAdbState(intent: Intent): Boolean {
        val connected = intent.getBooleanExtra("connected", false)
        if (!connected) return false
        val functions = intent.getStringExtra("functions") ?: ""
        val adbExtra = intent.getBooleanExtra("adb", false)
        return adbExtra || functions.contains("adb")
    }

    private fun updateUsbAdbState(now: Boolean) {
        if (now != usbAdbActive) {
            usbAdbActive = now
            log("USB adb state changed -> $now")
        }
    }

    /** 兼容注册：优先带 RECEIVER_NOT_EXPORTED 的重载（API 33+），失败退回旧重载 */
    private fun registerReceiverCompat(
        ctx: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ): Intent? {
        return try {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(receiver, filter)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 读取全局 int 设置，异常或缺失时视为 0（未激活） */
    private fun readGlobalBool(cr: ContentResolver, key: String): Boolean = try {
        Settings.Global.getInt(cr, key, 0) != 0
    } catch (_: Throwable) {
        false
    }

    /** 优先取名为 `mContext` 的字段；找不到则遍历（含父类）任意 Context 类型字段 */
    private fun findContext(instance: Any): Context? {
        val clazz = instance.javaClass
        // 先按常见字段名快速命中
        var k: Class<*>? = clazz
        while (k != null) {
            try {
                val f = k.getDeclaredField("mContext")
                f.isAccessible = true
                val v = f.get(instance)
                if (v is Context) return v
            } catch (_: NoSuchFieldException) {
                // 继续向上找
            } catch (_: Throwable) {
                break
            }
            k = k.superclass
        }
        // 兜底：扫描任意 Context 字段
        k = clazz
        while (k != null) {
            for (f in k.declaredFields) {
                if (Context::class.java.isAssignableFrom(f.type)) {
                    try {
                        f.isAccessible = true
                        val v = f.get(instance)
                        if (v is Context) return v
                    } catch (_: Throwable) {
                        // 跳过该字段
                    }
                }
            }
            k = k.superclass
        }
        return null
    }
}
