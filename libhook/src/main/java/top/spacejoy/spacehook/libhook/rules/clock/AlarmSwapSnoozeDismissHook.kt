package top.spacejoy.spacehook.libhook.rules.clock

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import top.spacejoy.spacehook.libhook.base.BaseHook

/**
 * 时钟：交换闹钟响铃界面的「上滑关闭」与「点击延迟提醒」。
 *
 * 目标 APK：`D:/Code/Android/APK/Hook/com.android.deskclock_18.28.0.apk`（18.28.0，真机同版本）
 * 目标进程：`com.android.deskclock`
 *
 * ## 现象
 * 闹钟响铃时，上滑一下就会关闭闹钟（`EVENT_ALARM_DISMISS_BY_SWIPE`），屏幕中央的
 * 「1 分钟后提醒」按钮才是延迟提醒。前者极易误触，把闹钟直接关掉。
 *
 * ## 定位（jadx 18.28.0）
 * ```
 * DefaultAlarmAlertScreenController$DragCallback#onViewReleased
 *   → AlertScreenController#dismiss()            → listener.onDismiss(false)   ← 上滑
 * DefaultAlarmAlertScreenController#init 里 mSnooze.setOnClickListener
 *   → AlertScreenController#snooze()             → listener.onSnooze()        ← 点按钮
 * AlarmAlertFullScreenActivity 实现 AlertScreenController.AlertScreenListener
 *   onDismiss(boolean) / onSnooze()
 * ```
 * 也就是说两条手势最终都汇聚到 Activity 的两个回调，在这里交换即可覆盖
 * 「默认界面」与「生活早报（LifePost）界面」两种布局。
 *
 * ## 策略
 * - `onSnooze()` → 改为执行原本 `onDismiss(false)` 的动作（`dismiss(false, false, true)`）；
 * - `onDismiss(boolean)` → 改为执行原本 `onSnooze()` 的动作（`snooze(true)`）。
 *
 * ⚠️ `onDismiss` 里还有计时器分支（`mTimerViewRoot` 可见时 `dismissTimer()`），
 * 计时器响铃不参与交换，原样放行。
 *
 * ## 界面文案
 * 行为交换后按钮文字必须同步，否则「1分钟后提醒」会变成关掉闹钟的陷阱。
 * Hook `DefaultAlarmAlertScreenController#init()`，在原生文案设置完之后改写：
 * - 按钮（`mSnoozeHint`）→ 应用自带的「关闭闹钟」字符串（多语言自动适配）；
 * - 底部提示（`mAlertSliderHide`）→ 把「关闭闹钟」替换成「延迟提醒」，
 *   即「滑动延迟提醒」。非中文语种匹配不到时保持原文案。
 */
class AlarmSwapSnoozeDismissHook : BaseHook("alarm_swap_snooze_dismiss") {

    private companion object {
        const val CLS_ACTIVITY = "com.android.deskclock.alarm.alert.AlarmAlertFullScreenActivity"
        const val CLS_DEFAULT_CONTROLLER = "com.android.deskclock.alarm.alert.DefaultAlarmAlertScreenController"

        /** 应用自带的「关闭闹钟」字符串（`aapt2 dump resources` 复核） */
        const val STR_CLOSE_ALARM_NEW = "close_alarm_new"
        const val STR_CLOSE_ALARM = "close_alarm"

        /** 资源都解析不到时的兜底文案 */
        const val FALLBACK_CLOSE_ALARM = "关闭闹钟"

        /** 底部提示里的原始片段 → 替换为延迟提醒 */
        const val SLIDER_ORIGIN = "关闭闹钟"
        const val SLIDER_SHORT_ORIGIN = "关闭"
        const val SLIDER_NEW = "延迟提醒"
    }

    override fun onHook() {
        val activityCls = requireClass(CLS_ACTIVITY) ?: return
        hookSnoozeButton(activityCls)
        hookSlideDismiss(activityCls)
        hookWording()
    }

    // ==================== 行为交换 ====================

    /** 点屏幕中央的提醒按钮 → 关闭闹钟 */
    private fun hookSnoozeButton(activityCls: Class<*>) {
        val handle = replace(activityCls, "onSnooze") { chain ->
            val activity = chain.thisObject
            if (isTimerScreen(activity)) {
                // 计时器界面的响铃不参与交换
                chain.proceed()
            } else {
                logd("点击提醒按钮 → 改为关闭闹钟")
                setField(activity, "mIsAlarmDismissed", true)
                callMethod(activity, "dismiss", false, false, true)
                null
            }
        }
        if (handle != null) {
            log("已挂载：$CLS_ACTIVITY#onSnooze（按钮改为关闭闹钟）")
        } else {
            loge("onSnooze 挂载失败，交换行为不完整（方法名可能随版本变化）")
        }
    }

    /** 上滑 → 延迟提醒 */
    private fun hookSlideDismiss(activityCls: Class<*>) {
        val handle = replace(
            activityCls,
            "onDismiss",
            arrayOf(Boolean::class.javaPrimitiveType!!),
        ) { chain ->
            val activity = chain.thisObject
            if (isTimerScreen(activity)) {
                chain.proceed()
            } else {
                logd("上滑 → 改为延迟提醒")
                setField(activity, "mIsAlarmDismissed", true)
                // 原 onSnooze 走 runOnUiThread，这里保持一致；已在主线程时会立即执行
                (activity as? Activity)?.runOnUiThread {
                    callMethod(activity, "snooze", true)
                }
                null
            }
        }
        if (handle != null) {
            log("已挂载：$CLS_ACTIVITY#onDismiss（上滑改为延迟提醒）")
        } else {
            loge("onDismiss 挂载失败，交换行为不完整（方法名可能随版本变化）")
        }
    }

    /** 计时器响铃界面（`mTimerViewRoot` 可见）需要保持原生行为 */
    private fun isTimerScreen(activity: Any?): Boolean {
        val timerRoot = getField(activity, "mTimerViewRoot") as? View ?: return false
        return timerRoot.visibility == View.VISIBLE
    }

    // ==================== 界面文案 ====================

    private fun hookWording() {
        val controllerCls = requireClass(CLS_DEFAULT_CONTROLLER) ?: return
        val handle = replace(controllerCls, "init") { chain ->
            val result = chain.proceed()
            try {
                applySwappedWording(chain.thisObject)
            } catch (t: Throwable) {
                loge("界面文案替换失败", t)
            }
            result
        }
        if (handle != null) {
            log("已挂载：$CLS_DEFAULT_CONTROLLER#init（界面文案同步）")
        } else {
            logw("init 未挂载，界面文案不会同步（行为交换仍然生效）")
        }
    }

    private fun applySwappedWording(controller: Any?) {
        val ctx = getField(controller, "mContext") as? Context ?: return
        val closeText = resolveCloseAlarmText(ctx)

        // 中央按钮：显示「关闭闹钟」
        (getField(controller, "mSnoozeHint") as? TextView)?.text = closeText
        (getField(controller, "mSnooze") as? View)?.contentDescription = closeText

        // 底部提示：「滑动关闭闹钟」→「滑动延迟提醒」
        (getField(controller, "mAlertSliderHide") as? TextView)?.let { slider ->
            val origin = slider.text?.toString().orEmpty()
            if (origin.isEmpty()) return@let
            val replaced = if (origin.contains(SLIDER_ORIGIN)) {
                origin.replace(SLIDER_ORIGIN, SLIDER_NEW)
            } else {
                origin.replace(SLIDER_SHORT_ORIGIN, SLIDER_NEW)
            }
            if (replaced != origin) {
                slider.text = replaced
                logd("底部提示：$origin → $replaced")
            }
        }
    }

    /** 取应用自带的「关闭闹钟」文案（按当前语言） */
    private fun resolveCloseAlarmText(ctx: Context): String {
        return try {
            val res = ctx.resources
            val id = res.getIdentifier(STR_CLOSE_ALARM_NEW, "string", ctx.packageName)
                .takeIf { it != 0 }
                ?: res.getIdentifier(STR_CLOSE_ALARM, "string", ctx.packageName)
            if (id != 0) ctx.getString(id) else FALLBACK_CLOSE_ALARM
        } catch (_: Throwable) {
            FALLBACK_CLOSE_ALARM
        }
    }
}
