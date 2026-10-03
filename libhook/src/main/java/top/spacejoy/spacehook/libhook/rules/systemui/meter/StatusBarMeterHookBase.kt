package top.spacejoy.spacehook.libhook.rules.systemui.meter

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import top.spacejoy.spacehook.libhook.base.BaseHook
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
 * 往视图写文本（刷新间隔可配）。一次刷新只组装一次文本、写进**所有**承载视图
 * ——原生 `NetworkSpeedView` 有多个实例，只记一个会让其余实例冻在旧文本上。
 *
 * 子类只需实现 [createContributor]，提供自己那段文本；hook 点由这里统一安装一次
 * （见 [StatusBarMeterState.claimInstaller]）。
 */
internal abstract class StatusBarMeterHookBase(id: String) : BaseHook(id) {

    /** 子类实现：返回本 Hook 的显示贡献者（每次渲染都回读配置，改参数即时生效） */
    protected abstract fun createContributor(): StatusBarMeterState.Contributor

    private var handler: Handler? = null

    /** 诊断输出剩余条数（排查完置 0 即可静音） */
    private var diagLeft = 20

    /** 写入回读诊断剩余条数 */
    private var writeDiagLeft = 12

    /** 可见性桥诊断剩余条数 */
    private var bridgeDiagLeft = 8

    private val dumpedViews = HashSet<Int>()

    /** 视图来源链只打印一次 */
    private val chainDumped = HashSet<Int>()

    /** 原生默认字号（px）与字体，用于「字号 / 字重 = 跟随系统」时还原 */
    private var numberDefaultPx = 0f
    private var unitDefaultPx = 0f
    private var numberDefaultTypeface: Typeface? = null
    private var unitDefaultTypeface: Typeface? = null

    private val tick = object : Runnable {
        override fun run() {
            val h = handler ?: return
            val views = StatusBarMeterState.liveViews()
            if (views.isEmpty()) {
                // 状态栏被重建，等新视图回调 onFinishInflate 再启动
                handler = null
                return
            }
            try {
                if (StatusBarMeterState.active) {
                    // 一次刷新只组装一次：网速是差分采样，组装两遍会白白推进一次基线
                    val text = StatusBarMeterState.compose()
                    if (text != null) for (view in views) writeText(view, text)
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
        StatusBarMeterState.onCompose = { parts, result ->
            if (diagLeft > 0) {
                diagLeft--
                logd("组装诊断: 片段$parts → 数字框[${result.first}] 单位框[${result.second}]")
            }
        }
        if (StatusBarMeterState.claimInstaller()) installHooks()
        logd(
            "已挂载：与同位置的其它仪表合并显示，刷新 ${StatusBarMeterState.refreshMs()}ms" +
                " 状态实例=${System.identityHashCode(StatusBarMeterState)}" +
                " 类装载器=${javaClass.classLoader?.let { System.identityHashCode(it) }}",
        )
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
                if (bridgeDiagLeft > 0) {
                    bridgeDiagLeft--
                    logd("可见性桥: 控制器=#${controller?.let { System.identityHashCode(it) }}")
                }
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
            // 原生往哪个视图推文本，就把哪个视图补进登记表。
            // 状态栏那个实例通常在模块加载之前就 inflate 完了，onFinishInflate 拦不到它，
            // 只有这里能兜住——否则它会一直冻在旧文本上，而且永远不会被定时器刷新。
            val view = chain.thisObject as? View
            if (view != null) {
                StatusBarMeterState.addView(view)
                dumpChain(view)
            }
            val text = StatusBarMeterState.compose()
            if (text == null || view == null) {
                chain.proceed()
            } else {
                // 不 proceed：原生体会把两个参数存进 mNetworkSpeedNumber / mNetworkSpeedUnit，
                // 随后 updateNetworkSpeed$1() 就拿它们把文本覆盖回去。
                writeText(view, text)
                null
            }
        }

        // 原生真正写文本的地方。updateVisibility() 每次控制器刷新都会调它，
        // 用 mNetworkSpeedNumber / mNetworkSpeedUnit 把两个框重写成「数字 + 单位」单行形态——
        // 定时器刚写好的两行会在同一秒内被覆盖掉（实测屏幕上永远只有数字框那一行）。
        // 这里整个换掉，改成写组装结果。
        findMethod(clazz, METHOD_UPDATE_SPEED_TEXT)?.let { method ->
            hookExecutable(method) { chain ->
                val view = chain.thisObject as? View
                val text = StatusBarMeterState.compose()
                if (view == null || text == null) chain.proceed() else {
                    writeText(view, text)
                    null
                }
            }
        }

        // 原生这里会把 textAppearance 与 ±4.6/3.8dp 的错位重设回原生值，跑完再套一遍样式
        findMethod(clazz, METHOD_UPDATE_RESOURCES)?.let { method ->
            hookExecutable(method) { chain ->
                val result = chain.proceed()
                applyStyle(chain.thisObject)
                result
            }
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
        StatusBarMeterState.addView(view)
        if (StatusBarMeterState.reader == null) StatusBarMeterState.reader = MeterReader(view.context)
        applyStyle(view)
        dumpView(view)
        dumpChain(view)
        startTicker()
    }

    /**
     * 视图来源链（每个视图一次）。
     *
     * 屏幕上出现过**两块并排的仪表**，而登记表里有 8 个视图、父容器都叫
     * `MiuiStatusIconContainer`——只看父类名分不清哪个在状态栏、哪个在息屏 / 下拉栏 /
     * 锁屏。往上打 6 层容器名 + id，再带上 `isShown`，就能定位重复来自哪一支。
     */
    private fun dumpChain(view: View) {
        if (!chainDumped.add(System.identityHashCode(view))) return
        try {
            val sb = StringBuilder("视图来源: #").append(System.identityHashCode(view))
            sb.append(" 附着=").append(view.isAttachedToWindow).append(" 显示=").append(view.isShown)
            var node: ViewParent? = view.parent
            var depth = 0
            while (node != null && depth < 6) {
                sb.append(" ← ").append(node.javaClass.simpleName)
                if (node is View) sb.append("(").append(resId(node)).append(")")
                node = node.parent
                depth++
            }
            logd(sb.toString())
        } catch (t: Throwable) {
            logw("视图来源诊断失败：${t.message}")
        }
    }

    private fun startTicker() {
        if (handler != null) return
        val h = Handler(Looper.getMainLooper())
        handler = h
        h.post(tick)
    }

    /**
     * 直接写两个文本框。
     *
     * **不走原生 `setNetworkSpeed`**：那条路开头就有 `if (!mVisibleByController || mBlocked) return`，
     * 视图没被原生判为可见时写入会被静默吞掉（`callMethod` 连异常都吞，日志里什么都不留）。
     * 定时器自己写文本没有这层守卫，也不受原生推送节奏影响。
     */
    private fun writeText(view: View, text: Pair<String, String>) {
        val number = getField(view, FIELD_NUMBER_TEXT) as? TextView
        val unit = getField(view, FIELD_UNIT_TEXT) as? TextView
        if (number == null || unit == null) {
            if (writeDiagLeft > 0) {
                writeDiagLeft--
                logw("写入失败: id=${resId(view)} 数字框=${number != null} 单位框=${unit != null}")
            }
            return
        }
        applyLayout(view, StatusBarMeterState.primary()?.layout == StatusBarMeterState.LAYOUT_DUAL)
        // 两行都塞进数字框：单位框在原生布局里根本画不出来（见 applyLayout 注释）
        val body = if (text.second.isEmpty()) text.first else text.first + "\n" + text.second
        if (number.text?.toString() != body) number.text = body
        if (unit.text.isNotEmpty()) unit.text = ""
        if (writeDiagLeft > 0) {
            writeDiagLeft--
            logd(
                "写入回读: #${System.identityHashCode(view)} id=${resId(view)} vis=${view.visibility}" +
                    " shown=${view.isShown} 父=${view.parent?.javaClass?.simpleName}" +
                    " 行数=${number.maxLines} 文本[${number.text}]",
            )
        }
        // 首次写入时视图一定已 attach，此时打来源链才有内容
        dumpChain(view)
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

            // 单行 / 双行的实际排布
            applyLayout(view, primary?.layout == StatusBarMeterState.LAYOUT_DUAL)
        } catch (t: Throwable) {
            logw("应用字体样式失败：${t.message}")
        }
    }

    /**
     * 单行 / 双行的实际排布。
     *
     * 原生是「两个居中文本框 + 数字框 -4.6dp、单位框 +3.8dp 的 translationY 错位」凑出两行，
     * 容器 `network_speed_container` 是 `wrap_content` 高，ViewGroup 默认 `clipChildren=true`。
     * 原生字号（数字 7.0dp / 单位 6.4dp）时两个半高之和 6.7dp < 8.4dp 错位，正好不重叠也不越界。
     *
     * 但单位框的位置是死的：它贴在容器下沿，字号一旦放大就越出容器下边界被裁掉。实测把它改成
     * BOTTOM gravity、错位归零、容器撑到 MATCH_PARENT、`clipChildren=false`，**屏幕上依旧只有
     * 数字框那一行**——`写入回读` 证明单位框里文本写进去了，`视图诊断` 证明 `lp=81 ty=0.0` 生效了，
     * 就是不画。所以单位框放弃，两行都由数字框自己换行承担。
     *
     * 于是这里只做三件事：单位框清空、两个框的错位归零、按行数配好数字框的换行能力。
     */
    private fun applyLayout(view: View, dual: Boolean) {
        val number = getField(view, FIELD_NUMBER_TEXT) as? TextView ?: return
        val unit = getField(view, FIELD_UNIT_TEXT) as? TextView ?: return
        if (unit.text.isNotEmpty()) unit.text = ""
        if (number.translationY != 0f) number.translationY = 0f
        if (unit.translationY != 0f) unit.translationY = 0f

        if (dual) {
            if (number.isSingleLine) number.isSingleLine = false
            if (number.maxLines != 2) number.maxLines = 2
        } else {
            if (!number.isSingleLine) number.isSingleLine = true
            if (number.maxLines != 1) number.maxLines = 1
        }

        // 容器别裁：字号放大后文本可能超出容器高度
        val container = number.parent as? ViewGroup
        if (container != null && container.clipChildren) container.clipChildren = false
    }

    /** 一次性视图结构诊断：搞清原生到底怎么摆这两个文本框、一共几个实例 */
    private fun dumpView(view: View) {
        if (!dumpedViews.add(System.identityHashCode(view))) return
        try {
            val number = getField(view, FIELD_NUMBER_TEXT) as? TextView ?: return
            val unit = getField(view, FIELD_UNIT_TEXT) as? TextView ?: return
            val parent = number.parent
            val sb = StringBuilder()
            sb.append("视图诊断: 视图=").append(view.javaClass.simpleName)
                .append(" id=").append(resId(view))
                .append(" 父容器=").append(parent?.javaClass?.simpleName)
            if (parent is LinearLayout) sb.append("(orientation=").append(parent.orientation).append(")")
            sb.append(" 数字框[").append(number.text).append("] size=").append(number.textSize)
                .append(" ty=").append(number.translationY)
                .append(" lp=").append(gravityOf(number))
            sb.append(" 单位框[").append(unit.text).append("] size=").append(unit.textSize)
                .append(" ty=").append(unit.translationY)
                .append(" lp=").append(gravityOf(unit))
            sb.append(" 视图总数=").append(StatusBarMeterState.liveViews().size)
            logd(sb.toString())
        } catch (t: Throwable) {
            logw("视图诊断失败：${t.message}")
        }
    }

    private fun resId(view: View): String = try {
        if (view.id == View.NO_ID) "-" else view.resources.getResourceEntryName(view.id)
    } catch (_: Throwable) {
        "?"
    }

    private fun gravityOf(child: View): Int =
        (child.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: -1

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

        /** 原生唯一写文本的方法，`updateVisibility()` 每次刷新都调它 */
        const val METHOD_UPDATE_SPEED_TEXT = "updateNetworkSpeed\$1"

        /** 原生重设 textAppearance 与上下错位的方法 */
        const val METHOD_UPDATE_RESOURCES = "updateResources\$15"

        const val FIELD_SHOW_NETWORK_SPEED = "mShowNetworkSpeed"
        const val FIELD_NETWORK_CONNECTED = "mNetworkConnected"
        const val FIELD_NUMBER_TEXT = "mNetworkSpeedNumberText"
        const val FIELD_UNIT_TEXT = "mNetworkSpeedUnitText"

        val STYLE_REFRESH_METHODS = arrayOf("onDensityOrFontScaleChanged", "onMiuiThemeChanged")
    }
}
