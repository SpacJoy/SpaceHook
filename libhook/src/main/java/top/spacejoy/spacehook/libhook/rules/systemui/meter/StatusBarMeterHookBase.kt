package top.spacejoy.spacehook.libhook.rules.systemui.meter

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import top.spacejoy.spacehook.libhook.base.BaseHook
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 状态栏仪表的共用实现。
 *
 * ## 原理
 * 接管状态栏原生「实时网速」视图（[CLS_VIEW]）作为显示载体：原生网速本来就占着状态栏
 * 右侧一个位置，直接把它的文本换成仪表内容，不需要自己插布局、不需要 ByteBuddy 造图标槽。
 *
 * ## 两条链路都要拦
 * 1. **文本链路**：`NetworkSpeedController#updateText(String[])` 与
 *    `NetworkSpeedView#setNetworkSpeed(String,String)` —— 原生每次刷新都会走这里，
 *    把参数换成仪表文本即可，原生内容永远不落地。
 * 2. **可见性链路**：原生「显示实时网速」开关关闭时 `mShowNetworkSpeed == false`，
 *    图标根本不会被创建（IconManager 拿不到 visible=true），也就没有视图可改。
 *    因此拦截 D8 合成的静态桥 `-$$Nest$mupdateVisibility`，在它计算 visible 之前
 *    把 `mShowNetworkSpeed` / `mNetworkConnected` 钉成 true。
 *
 * ## 自己的刷新节奏
 * 原生只在网速变化时推送，功耗/温度会「冻住」，所以另起一个主线程 Handler 定时直接
 * 往视图写文本（刷新间隔可配）。
 *
 * 子类只需实现 [createContributor]，提供自己那段文本；hook 点由这里统一安装一次
 * （见 [StatusBarMeterState.claimInstaller]）。
 */
internal abstract class StatusBarMeterHookBase(id: String) : BaseHook(id) {

    /** 子类实现：返回本 Hook 的显示贡献者（每次渲染都回读配置，改参数即时生效） */
    protected abstract fun createContributor(): StatusBarMeterState.Contributor

    private var handler: Handler? = null

    /** 原生默认字号（px）与字体，用于「字号 / 字重 = 跟随系统」时还原 */
    private var numberDefaultPx = 0f
    private var unitDefaultPx = 0f
    private var numberDefaultTypeface: Typeface? = null
    private var unitDefaultTypeface: Typeface? = null

    private val tick = object : Runnable {
        override fun run() {
            val h = handler ?: return
            val view = StatusBarMeterState.viewRef?.get()
            if (view == null) {
                // 状态栏被重建，等新视图回调 onFinishInflate 再启动
                handler = null
                return
            }
            try {
                if (StatusBarMeterState.active) {
                    callMethod(view, "setVisibilityByController", true)
                    callMethod(view, "setBlocked", false)
                    StatusBarMeterState.compose()?.let {
                        callMethod(view, "setNetworkSpeed", it.first, it.second)
                    }
                }
            } catch (t: Throwable) {
                loge("仪表刷新异常", t)
            }
            h.postDelayed(this, StatusBarMeterState.refreshMs().toLong())
        }
    }

    // ==================== 入口 ====================

    final override fun onHook() {
        StatusBarMeterState.register(id, createContributor())
        if (StatusBarMeterState.claimInstaller()) installHooks()
        logd("已挂载：与同位置的其它仪表合并显示，刷新 ${StatusBarMeterState.refreshMs()}ms")
    }

    private fun installHooks() {
        val controller = requireClass(CLS_CONTROLLER)
        val view = requireClass(CLS_VIEW)
        if (controller == null && view == null) {
            loge("未找到 NetworkSpeedController / NetworkSpeedView，跳过")
            return
        }
        controller?.let { hookController(it) }
        view?.let { hookView(it) }
    }

    /** 文本链路 + 可见性链路 */
    private fun hookController(clazz: Class<*>) {
        replace(clazz, "updateText", arrayOf<Class<*>>(Array<String>::class.java)) { chain ->
            val text = StatusBarMeterState.compose()
            // updateText(String[]) 只吃一个参数（String[]），不是两个 String
            if (text == null) chain.proceed()
            else chain.proceed(arrayOf<Any?>(arrayOf(text.first, text.second)))
        }

        val bridge = findVisibilityBridge(clazz)
        if (bridge == null) {
            logw("未找到 \$Nest\$mupdateVisibility：系统「显示实时网速」关闭时仪表不会出现")
            return
        }
        hookExecutable(bridge) { chain ->
            if (StatusBarMeterState.active) {
                val controller = chain.args.getOrNull(0)
                setBooleanField(controller, FIELD_SHOW_NETWORK_SPEED, true)
                setBooleanField(controller, FIELD_NETWORK_CONNECTED, true)
            }
            chain.proceed()
        }
    }

    /**
     * 定位 D8 合成的静态访问桥。
     * 正常名字是 `-$$Nest$mupdateVisibility(Controller, String)`；不同工具链/版本命名可能变，
     * 因此加一条「静态 + 两参 + 首参是控制器 + 名字含 updateVisibility」的兜底匹配。
     */
    private fun findVisibilityBridge(clazz: Class<*>): Method? {
        findMethod(clazz, NEST_UPDATE_VISIBILITY, arrayOf(clazz, String::class.java))?.let { return it }
        return clazz.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == clazz &&
                it.name.contains("updateVisibility")
        }
    }

    /** 视图链路：缓存视图、套用字体样式、拦下所有写文本的入口 */
    private fun hookView(clazz: Class<*>) {
        replace(clazz, "onFinishInflate") { chain ->
            val result = chain.proceed()
            try {
                (chain.thisObject as? View)?.let { onViewReady(it) }
            } catch (t: Throwable) {
                loge("视图初始化处理失败", t)
            }
            result
        }

        replace(clazz, "setNetworkSpeed", arrayOf(String::class.java, String::class.java)) { chain ->
            val text = StatusBarMeterState.compose()
            if (text == null) chain.proceed()
            else chain.proceed(arrayOf<Any?>(text.first, text.second))
        }

        for (name in STYLE_REFRESH_METHODS) {
            replace(clazz, name) { chain ->
                val result = chain.proceed()
                applyStyle(chain.thisObject)
                result
            }
        }
    }

    private fun onViewReady(view: View) {
        StatusBarMeterState.viewRef = WeakReference(view)
        if (StatusBarMeterState.reader == null) StatusBarMeterState.reader = MeterReader(view.context)
        applyStyle(view)
        startTicker()
    }

    private fun startTicker() {
        if (handler != null) return
        val h = Handler(Looper.getMainLooper())
        handler = h
        h.post(tick)
    }

    /**
     * 字号 / 字重 / 对齐 / 固定宽度 / 边距 / 垂直偏移。
     *
     * 除字号外**全部以「0 或默认值 = 不干预」为准**——原生视图的边距、宽度是系统
     * 状态栏布局给的，参数没设就不该去覆盖它。
     * 系统更新文字外观（字体缩放、主题切换）后由 [STYLE_REFRESH_METHODS] 重新套一遍。
     */
    private fun applyStyle(target: Any?) {
        val view = target as? View ?: return
        try {
            val number = getField(view, FIELD_NUMBER_TEXT) as? TextView ?: return
            val unit = getField(view, FIELD_UNIT_TEXT) as? TextView ?: return
            if (numberDefaultPx <= 0f) numberDefaultPx = number.textSize
            if (unitDefaultPx <= 0f) unitDefaultPx = unit.textSize
            if (numberDefaultTypeface == null) numberDefaultTypeface = number.typeface
            if (unitDefaultTypeface == null) unitDefaultTypeface = unit.typeface

            val primary = StatusBarMeterState.primary()

            // 字号：0 = 跟随系统（还原首次记下的原生 px）
            val sp = primary?.fontSizeSp ?: 0
            if (sp > 0) {
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
                unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            } else {
                number.setTextSize(TypedValue.COMPLEX_UNIT_PX, numberDefaultPx)
                unit.setTextSize(TypedValue.COMPLEX_UNIT_PX, unitDefaultPx)
            }

            // 字重：细体 / 粗体 / 跟随系统
            val style = primary?.fontStyle ?: StatusBarMeterState.FONT_DEFAULT
            val bold = primary?.bold == true || style == StatusBarMeterState.FONT_BOLD
            applyTypeface(number, numberDefaultTypeface, style, bold)
            applyTypeface(unit, unitDefaultTypeface, style, bold)

            // 水平对齐：1 = 保持原生（不碰 gravity）
            when (primary?.align ?: StatusBarMeterState.ALIGN_NATIVE) {
                StatusBarMeterState.ALIGN_LEFT -> {
                    number.gravity = Gravity.START
                    unit.gravity = Gravity.START
                }

                StatusBarMeterState.ALIGN_CENTER -> {
                    number.gravity = Gravity.CENTER
                    unit.gravity = Gravity.CENTER
                }

                StatusBarMeterState.ALIGN_RIGHT -> {
                    number.gravity = Gravity.END
                    unit.gravity = Gravity.END
                }

                else -> Unit
            }

            // 固定宽度（0 = 不干预）
            val widthDp = primary?.fixedWidthDp ?: 0
            if (widthDp > 0) view.minimumWidth = view.dp(widthDp)

            // 左右边距（0 = 保持原生）
            val leftDp = primary?.leftMarginDp ?: 0
            val rightDp = primary?.rightMarginDp ?: 0
            if (leftDp > 0 || rightDp > 0) {
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    if (leftDp > 0) params.leftMargin = view.dp(leftDp)
                    if (rightDp > 0) params.rightMargin = view.dp(rightDp)
                    view.layoutParams = params
                }
            }

            // 垂直偏移：参数为「正值下移」，translationY 正方向向下，故取负
            view.translationY = -view.dp(primary?.verticalOffsetDp ?: 0).toFloat()
        } catch (t: Throwable) {
            logw("应用字体样式失败：${t.message}")
        }
    }

    /** 细体走 `sans-serif-light`，粗体在原生字体上加粗，其余还原首次记下的原生字体 */
    private fun applyTypeface(text: TextView, fallback: Typeface?, style: Int, bold: Boolean) {
        val light = style == StatusBarMeterState.FONT_LIGHT
        text.typeface = when {
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            bold -> Typeface.create(fallback, Typeface.BOLD)
            else -> fallback
        }
        text.paint.isFakeBoldText = bold && !light
    }

    private fun View.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {

        const val CLS_CONTROLLER = "com.android.systemui.statusbar.policy.NetworkSpeedController"
        const val CLS_VIEW = "com.android.systemui.statusbar.views.NetworkSpeedView"

        /** D8 合成的静态访问桥，dex 中的真实名字（Java 里非法，故 jadx 会改名） */
        const val NEST_UPDATE_VISIBILITY = "-\$\$Nest\$mupdateVisibility"

        const val FIELD_SHOW_NETWORK_SPEED = "mShowNetworkSpeed"
        const val FIELD_NETWORK_CONNECTED = "mNetworkConnected"
        const val FIELD_NUMBER_TEXT = "mNetworkSpeedNumberText"
        const val FIELD_UNIT_TEXT = "mNetworkSpeedUnitText"

        val STYLE_REFRESH_METHODS = arrayOf("onDensityOrFontScaleChanged", "onMiuiThemeChanged")
    }
}
