package top.spacejoy.spacehook.libhook.rules.clock

import android.view.View
import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.base.BaseHook
import top.spacejoy.spacehook.libhook.utils.PrefsBridge
import java.lang.reflect.Field

/**
 * 时钟：加长闹钟响铃界面「上滑」所需的滑动距离。
 *
 * 目标 APK：`D:/Code/Android/APK/Hook/com.android.deskclock_18.28.0.apk`（18.28.0，真机同版本）
 * 目标进程：`com.android.deskclock`
 *
 * ## 原生判定（jadx 18.28.0，`DefaultAlarmAlertScreenController`）
 * ```java
 * private static final float SCREEN_SLIDE_THRESHOLD = 0.15f;   // 快速甩动的最小距离
 * // DragCallback#onViewReleased：
 * if ((yvel >= -mFlingVelocity || mScrollPercent <= 0.15f) && mScrollPercent <= 0.5f) {
 *     startBounceAnimation(...);   // 回弹
 * } else {
 *     settleCapturedViewAt(0, -view.getHeight());  // 关闭
 * }
 * ```
 * 也就是说有两条关闭通道：
 * 1. 滑过屏幕高度 50%（慢速）；
 * 2. 只滑 15% 以上 + 向上快速一甩（`mFlingVelocity`）。
 *
 * ## 策略
 * 通道 2 才是误触的根源。这里把判定改成「只看距离」：
 * - 在 [DragCallback#onViewReleased] 之前读出 `mScrollPercent`；
 * - 少于配置比例（[CFG_THRESHOLD]，默认 60%，原生 50%）→ **强制回弹**，不走原生逻辑；
 * - 达到比例 → 交给原生逻辑（此时原生必然判定关闭）。
 *
 * 顺带把拖动时的淡出进度改成「达到设定比例才淡完」，否则默认 50% 就透明，
 * 用户看不到自己还要滑多远（[DragCallback#onViewPositionChanged] 里重设 `mRoot` 的 alpha）。
 *
 * ⚠️ 参数 `threshold` 在拖动/松手时实时读取，改完参数无需重启时钟进程。
 */
class AlarmSwipeDismissDistanceHook : BaseHook("alarm_swipe_dismiss_distance") {

    private companion object {
        const val CLS_DRAG_CALLBACK =
            "com.android.deskclock.alarm.alert.DefaultAlarmAlertScreenController\$DragCallback"
        const val CLS_CONTROLLER =
            "com.android.deskclock.alarm.alert.DefaultAlarmAlertScreenController"

        const val CFG_THRESHOLD = "threshold"
        const val DEFAULT_THRESHOLD = 60
        const val MIN_THRESHOLD = 30
        const val MAX_THRESHOLD = 90
    }

    /** 关闭所需滑动比例（百分比，30..90） */
    private val thresholdPercent: Int
        get() = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_THRESHOLD), DEFAULT_THRESHOLD)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /** DragCallback 持有外部 controller 的字段（`this$0`，混淆下按类型定位） */
    private var controllerField: Field? = null

    /** controller 的 `mRoot`（ViewDragHelperLayout） */
    private var rootField: Field? = null

    override fun onHook() {
        val cbCls = requireClass(CLS_DRAG_CALLBACK) ?: run {
            loge("找不到 $CLS_DRAG_CALLBACK，上滑距离定制不可用")
            return
        }
        resolveFields(cbCls)
        hookPositionChanged(cbCls)
        hookReleased(cbCls)
    }

    // ==================== 反射字段缓存 ====================

    private fun resolveFields(cbCls: Class<*>) {
        try {
            controllerField = cbCls.declaredFields.firstOrNull { it.type.name == CLS_CONTROLLER }
                ?: cbCls.declaredFields.firstOrNull { it.name == "this\$0" }
            controllerField?.isAccessible = true
        } catch (t: Throwable) {
            loge("定位 controller 字段失败", t)
        }

        val controllerCls = controllerField?.type ?: return
        try {
            rootField = controllerCls.declaredFields.firstOrNull { it.name == "mRoot" }
            rootField?.isAccessible = true
        } catch (t: Throwable) {
            loge("定位 mRoot 字段失败", t)
        }
    }

    private fun controllerOf(callback: Any?): Any? = try {
        controllerField?.get(callback)
    } catch (_: Throwable) {
        null
    }

    private fun rootViewOf(callback: Any?): View? = try {
        val controller = controllerOf(callback)
        if (controller == null) null else rootField?.get(controller) as? View
    } catch (_: Throwable) {
        null
    }

    // ==================== Hook 点 ====================

    /** 拖动过程中：让淡出进度与「所需比例」一致 */
    private fun hookPositionChanged(cbCls: Class<*>) {
        val handle = replace(
            cbCls,
            "onViewPositionChanged",
            arrayOf(
                View::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ),
        ) { chain ->
            val result = chain.proceed()
            try {
                val pct = getField(chain.thisObject, "mScrollPercent") as? Float
                if (pct != null) {
                    val threshold = thresholdPercent / 100f
                    val alpha = (1f - pct / threshold).coerceIn(0f, 1f)
                    rootViewOf(chain.thisObject)?.alpha = alpha
                }
            } catch (t: Throwable) {
                logd("淡出进度调整失败：$t")
            }
            result
        }
        if (handle != null) {
            log("已挂载：$CLS_DRAG_CALLBACK#onViewPositionChanged（淡出进度）")
        } else {
            logw("onViewPositionChanged 未挂载，淡出进度保持原生")
        }
    }

    /** 松手时：没滑够比例一律回弹 */
    private fun hookReleased(cbCls: Class<*>) {
        val handle = replace(
            cbCls,
            "onViewReleased",
            arrayOf(
                View::class.java,
                Float::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!,
            ),
        ) { chain ->
            try {
                val callback = chain.thisObject
                val pct = getField(callback, "mScrollPercent") as? Float ?: 0f
                val threshold = thresholdPercent / 100f
                if (pct <= threshold) {
                    val view = chain.args.getOrNull(0) as? View
                    val controller = controllerOf(callback)
                    if (view != null && controller != null) {
                        logd("上滑 ${percent(pct)} 未达到 ${percent(threshold)}，强制回弹")
                        // 明确标记「不关闭」，避免 onViewDragStateChanged 误触发 dismiss
                        setField(callback, "mDetermineDismiss", false)
                        callMethod(controller, "startBounceAnimation", view.top)
                        callMethod(controller, "startAlphaRestore")
                        return@replace null
                    }
                }
            } catch (t: Throwable) {
                loge("onViewReleased 处理失败，交回原生逻辑", t)
            }
            chain.proceed()
        }
        if (handle != null) {
            log("已挂载：$CLS_DRAG_CALLBACK#onViewReleased（关闭阈值 ${thresholdPercent}%）")
        } else {
            loge("onViewReleased 挂载失败，上滑距离定制不可用（方法名可能随版本变化）")
        }
    }

    private fun percent(value: Float): String = "${(value * 100).toInt()}%"
}
