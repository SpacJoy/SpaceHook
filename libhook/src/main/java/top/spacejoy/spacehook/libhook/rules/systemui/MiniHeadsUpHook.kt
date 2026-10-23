package top.spacejoy.spacehook.libhook.rules.systemui

import android.app.Notification
import android.content.Context
import top.spacejoy.spacehook.libhook.base.BaseHook
import java.lang.reflect.Method

/**
 * 悬浮通知迷你化 —— 把多行大卡片压成单行小卡片。
 *
 * 移植自 `D:/Code/Android/APK/Hook/高德默认路线.apk`
 * （包名 `com.example.miniheadsup`，应用名「悬浮通知迷你化」，libxposed API 102 单文件模块）。
 *
 * 目标 APK：`D:/Code/Android/APK/Hook/MiuiSystemUI_phone.apk`（= 真机 `17.03.260226.r`，md5 已核对）
 * 目标进程：`com.android.systemui`
 *
 * ## 原理
 * MIUI 的悬浮通知布局由 [CLS_BINDER] 决定，两个开关方法：
 * - `isTransparent(Context)` → 选**透明背景**模板（无实心底色）而不是带底色卡片；
 * - `useOneLine(Context, Context, Notification)` → 选**单行**模板
 *   `miui_notification_template_material_one_line`，标题与正文合并成一行。
 *
 * 原生只在「游戏模式」或「横屏且状态栏隐藏」时返回 true。本 Hook 让它们恒为 true，
 * 于是所有悬浮通知都用单行小卡片渲染。
 *
 * ## 两个必须保留的原生豁免
 * 单行模板塞不下复杂内容，以下两类通知必须继续走多行（见 [isExempt]）：
 * - **自定义 View 通知**（`contentView` / `bigContentView` 非空，如音乐播放器）——
 *   单行模板会丢弃自定义布局；
 * - **来电类通知**（国际版 MIUI：`category == "call"` 且非默认拨号应用）——
 *   按钮与信息量放不进一行。
 *
 * 两者都直接调用宿主自己的 `MiuiBaseNotifUtil` 静态方法，不在模块里重写判定逻辑，
 * 这样国际版 / 国内版差异以及系统后续改判定都会自动跟随。
 *
 * ## 为什么不做「仅悬浮通知」开关
 * `useOneLine` / `isTransparent` 不只在悬浮通知里调用，通知栏列表与展开态也走同一套判断，
 * 而**通知中心并不使用这条路径**（通知中心的卡片由独立组件渲染），所以按「是否悬浮」
 * 收窄范围既拦不住通知中心、又只会让行为难以预测。故固定为全局生效，
 * 效果正好是「全部悬浮通知迷你化，通知中心不受影响」。
 *
 * ⚠️ 改动开关后需要重启系统界面（SystemUI）：已渲染的通知不会重建。
 */
class MiniHeadsUpHook : BaseHook("mini_heads_up") {

    private companion object {
        const val CLS_BINDER =
            "com.android.systemui.statusbar.notification.row.NotificationRowContentBinderInjectorImpl"
        const val CLS_UTIL = "com.miui.systemui.notification.MiuiBaseNotifUtil"
    }

    /** 原生豁免判定用（`MiuiBaseNotifUtil` 的两个静态方法） */
    @Volatile
    private var mIsCustomView: Method? = null

    @Volatile
    private var mIsGlobalInCall: Method? = null

    override fun onHook() {
        resolveUtilMethods()

        val binder = requireClass(CLS_BINDER) ?: return

        hookTransparent(binder)
        hookUseOneLine(binder)
    }

    // ==================== 背景透明 ====================

    /**
     * `isTransparent(Context)` 恒为 true → 透明背景模板。
     *
     * 原生：游戏模式，或「横屏 + 状态栏被隐藏」时才透明。恒 true 后单行卡片没有实心底色，
     * 只留文字与图标，视觉上更「迷你」。
     */
    private fun hookTransparent(binder: Class<*>) {
        val method = findMethod(binder, "isTransparent", arrayOf(Context::class.java))
        if (method == null) {
            logw("方法不存在，跳过：${binder.name}#isTransparent")
            return
        }
        hookExecutable(method) { _ ->
            try {
                true
            } catch (t: Throwable) {
                loge("isTransparent 回调异常，退回原生行为", t)
                null
            }
        }
        logd("isTransparent 已挂载：恒 true（透明背景）")
    }

    // ==================== 单行布局 ====================

    /**
     * `useOneLine(Context, Context, Notification)` → 恒为 true，但保留原生豁免。
     *
     * 返回值含义：`true` = 单行模板，`false` = 多行卡片。
     * 直接给出 `true` 而不是「`proceed()` 取反」—— 原生只在很窄的条件下返回 true，
     * 取反会把本该多行的也变成单行。
     */
    private fun hookUseOneLine(binder: Class<*>) {
        val method = findMethod(
            binder,
            "useOneLine",
            arrayOf(Context::class.java, Context::class.java, Notification::class.java),
        )
        if (method == null) {
            logw("方法不存在，跳过：${binder.name}#useOneLine")
            return
        }

        hookExecutable(method) { chain ->
            try {
                val notification = chain.args.getOrNull(2) as? Notification
                when {
                    // 参数异常：不强行改布局
                    notification == null -> chain.proceed()
                    isExempt(chain, notification) -> {
                        logd("useOneLine → 多行（原生豁免）")
                        false
                    }
                    else -> true
                }
            } catch (t: Throwable) {
                loge("useOneLine 回调异常，退回原生行为", t)
                chain.proceed()
            }
        }
        logd("useOneLine 已挂载：单行布局")
    }

    /**
     * 原生豁免：自定义 View 通知 / 来电通知。
     */
    private fun isExempt(
        chain: io.github.libxposed.api.XposedInterface.Chain,
        notification: Notification,
    ): Boolean {
        // 1) 自定义 View 通知（音乐播放器、下载进度等自带 RemoteViews 的通知）
        try {
            val m = mIsCustomView
            if (m != null && m.invoke(null, notification) == true) {
                logd("豁免：自定义 View 通知")
                return true
            }
        } catch (t: Throwable) {
            logd("isCustomViewNotification 调用失败，按非豁免处理：$t")
        }

        // 2) 来电通知
        try {
            val m = mIsGlobalInCall
            val ctx = chain.args.getOrNull(0) as? Context
            val pkgCtx = chain.args.getOrNull(1) as? Context
            if (m != null && ctx != null) {
                if (m.invoke(null, ctx, pkgCtx?.packageName, notification) == true) {
                    logd("豁免：来电通知")
                    return true
                }
            }
        } catch (t: Throwable) {
            logd("isGlobalInCallNotification 调用失败，按非豁免处理：$t")
        }

        return false
    }

    // ==================== 工具 ====================

    private fun resolveUtilMethods() {
        val util = findClass(CLS_UTIL)
        if (util == null) {
            logw("$CLS_UTIL 不存在，自定义 View / 来电豁免将不可用")
            return
        }
        mIsCustomView = try {
            util.getMethod("isCustomViewNotification", Notification::class.java)
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            logw("isCustomViewNotification 未找到：${t.message}")
            null
        }
        mIsGlobalInCall = try {
            util.getMethod(
                "isGlobalInCallNotification",
                Context::class.java,
                String::class.java,
                Notification::class.java,
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            logw("isGlobalInCallNotification 未找到：${t.message}")
            null
        }
    }
}
