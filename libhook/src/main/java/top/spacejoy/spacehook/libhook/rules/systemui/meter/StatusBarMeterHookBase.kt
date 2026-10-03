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
import top.spacejoy.spacehook.libhook.utils.PrefsBridge
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
                    if (text != null) {
                        for (view in views) {
                            writeText(view, text)
                            applyStructure(view)
                        }
                    }
                }
                // 布局期之后再抓一次结构：onFinishInflate 时 isShown 还没算出来
                if (dumpedViews.size < views.size) for (view in views) dumpView(view)
                collapseDuplicates(views)
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
        // Hook 视角的配置真相：DB 里有值但这里没有，说明是框架快照没回灌
        log("配置快照: ${PrefsBridge.dump(id)}")
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
        val dual = StatusBarMeterState.primary()?.layout == StatusBarMeterState.LAYOUT_DUAL
        applyLayout(view, dual)
        // 左右双列：左列写数字框、右列写单位框。容器已被 applyLayout 换成横排 LinearLayout，
        // 所以单位框现在真的画得出来（原生 FrameLayout 里它被 wrap_content 裁掉）。
        // 单行时右列留空、单位框直接 GONE。
        val left = text.first
        val right = text.second
        if (number.text?.toString() != left) number.text = left
        if (right.isEmpty()) {
            if (unit.text.isNotEmpty()) unit.text = ""
            if (unit.visibility != View.GONE) unit.visibility = View.GONE
        } else {
            if (unit.text?.toString() != right) unit.text = right
            if (unit.visibility != View.VISIBLE) unit.visibility = View.VISIBLE
        }
        if (writeDiagLeft > 0) {
            writeDiagLeft--
            logd(
                "写入回读: #${System.identityHashCode(view)} id=${resId(view)} vis=${view.visibility}" +
                    " shown=${view.isShown} 父=${view.parent?.javaClass?.simpleName}" +
                    " 列=${if (dual) 2 else 1} 左[${number.text}] 右[${unit.text}]",
            )
        }
        // 首次写入时视图一定已 attach，此时打来源链才有内容
        dumpChain(view)
    }

    /**
     * 只在定时器里跑的结构调整：空隙 + 靠右。
     *
     * 列序固定「网速在左、仪表在右」：整块左边距取网速的左空隙，整块右边距取仪表的右空隙，
     * 两列间距 = 网速的右空隙 + 仪表的左空隙 + 内置的 [COLUMN_GAP_DP]（保证默认观感不变）。
     *
     * 图标容器比文本宽，内容左对齐就在右侧空出一大截（实测容器 380px、文本 183px）；
     * 让承载视图撑满容器、内容靠右，文本就贴到电池图标那一侧。
     *
     * **不放进 `writeText`**：那条路会被原生的 `setNetworkSpeed` / `updateNetworkSpeed$1`
     * 从布局过程中调进来，在那里改 `layoutParams` / `padding` 等于在布局期 `requestLayout`。
     * 定时器是 `Handler` 投递的，跑在两帧之间，安全。
     */
    private fun applyStructure(view: View) {
        if (!view.isShown) return
        val number = getField(view, FIELD_NUMBER_TEXT) as? TextView ?: return
        val unit = getField(view, FIELD_UNIT_TEXT) as? TextView ?: return
        val density = view.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val padLeft = dp(StatusBarMeterState.speedGapLeftDp)
        val padRight = dp(StatusBarMeterState.meterGapRightDp)
        if (view.paddingLeft != padLeft || view.paddingRight != padRight) {
            view.setPadding(padLeft, view.paddingTop, padRight, view.paddingBottom)
        }

        val dual = StatusBarMeterState.primary()?.layout == StatusBarMeterState.LAYOUT_DUAL
        val gap = if (dual) {
            dp(COLUMN_GAP_DP + StatusBarMeterState.speedGapRightDp + StatusBarMeterState.meterGapLeftDp)
        } else {
            0
        }
        val unitParams = unit.layoutParams as? LinearLayout.LayoutParams
        if (unitParams != null && unitParams.leftMargin != gap) {
            unitParams.leftMargin = gap
            unit.layoutParams = unitParams
        }

        val row = number.parent as? LinearLayout ?: return
        val want = Gravity.END or Gravity.CENTER_VERTICAL
        if (row.gravity != want) row.gravity = want
        val params = view.layoutParams ?: return
        if (params.width == ViewGroup.LayoutParams.MATCH_PARENT) return
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        if (params is LinearLayout.LayoutParams) params.weight = 0f
        view.layoutParams = params
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
     * 单行 / 左右双列的实际排布。
     *
     * 原生容器 `network_speed_container` 是 **FrameLayout**，两个文本框都 `gravity=CENTER`
     * 叠在同一位置，靠数字框 -4.6dp / 单位框 +3.8dp 的 `translationY` 错位凑出上下两行；
     * 容器 `wrap_content` 高、`clipChildren=true`，所以单位框贴在容器下沿，字号一放大就被裁。
     * 试过 BOTTOM gravity、错位归零、容器撑 MATCH_PARENT、`clipChildren=false`——**都不画**。
     *
     * 左右并排在这个结构里做不到：两个框居中叠放，平移出去立刻被 wrap_content 的容器裁掉。
     * 所以先把容器换成横排 `LinearLayout`（[ensureRow]），两个文本框才是真的左右相邻，
     * 各自的宽度由内容撑开，不再有裁剪问题。
     *
     * 之后只做：错位归零、按列数配换行能力、列内左对齐（多行才对齐成栅格）、
     * 单行时把右列 GONE。
     */
    private fun applyLayout(view: View, dual: Boolean) {
        val number = getField(view, FIELD_NUMBER_TEXT) as? TextView ?: return
        val unit = getField(view, FIELD_UNIT_TEXT) as? TextView ?: return

        ensureRow(view, number, unit)

        if (number.translationX != 0f) number.translationX = 0f
        if (number.translationY != 0f) number.translationY = 0f
        if (unit.translationX != 0f) unit.translationX = 0f
        if (unit.translationY != 0f) unit.translationY = 0f

        val lines = if (dual) 2 else 1
        number.isSingleLine = false
        if (number.maxLines != lines) number.maxLines = lines
        unit.isSingleLine = false
        if (unit.maxLines != lines) unit.maxLines = lines

        // 列内左对齐：两行时上下行才对齐成栅格（原生是 CENTER）
        number.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        unit.gravity = Gravity.START or Gravity.CENTER_VERTICAL

        val target = if (dual) View.VISIBLE else View.GONE
        if (unit.visibility != target) unit.visibility = target

        // 容器别裁：字号放大后文本可能超出容器高度
        val container = number.parent as? ViewGroup
        if (container != null && container.clipChildren) container.clipChildren = false
    }

    /**
     * 把原生 `FrameLayout network_speed_container` 换成横排 `LinearLayout`，两个文本框并排。
     *
     * 幂等：已经是「横排 + 两个子视图」就直接返回。两个 TextView 本身不重建，
     * 只是换父容器，所以 `mNetworkSpeedNumberText` / `mNetworkSpeedUnitText` 的字段引用一直有效，
     * 原生 `updateResources$15()` 的 `findViewById` 也照旧能找到。
     */
    private fun ensureRow(view: View, number: TextView, unit: TextView): LinearLayout? {
        val container = number.parent as? ViewGroup ?: return null
        // 文本框直接挂在 NetworkSpeedView 下（代码构造的那批实例，没有 resId）：
        // 没有中间容器可换，就把 NetworkSpeedView 本身当横排行，两个框重排一次并补列间距。
        if (container === view) {
            val row = view as? LinearLayout ?: return null
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            if (row.getTag(ROW_TAG) == null) {
                row.setTag(ROW_TAG, java.lang.Boolean.TRUE)
                if (number.parent === row) row.removeView(number)
                if (unit.parent === row) row.removeView(unit)
                row.addView(
                    number,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                row.addView(
                    unit,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { it.leftMargin = view.dp(COLUMN_GAP_DP) },
                )
                logd("容器改为横排(内联): #${System.identityHashCode(view)} NetworkSpeedView 直接横排")
            }
            return row
        }
        if (container is LinearLayout &&
            container.orientation == LinearLayout.HORIZONTAL &&
            container.childCount == 2
        ) {
            return container
        }
        val root = view as? ViewGroup ?: return null
        val index = root.indexOfChild(container)
        if (index < 0) return null
        return try {
            val params = container.layoutParams
            val row = LinearLayout(view.context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            container.removeView(number)
            container.removeView(unit)

            row.addView(
                number,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            row.addView(
                unit,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = view.dp(COLUMN_GAP_DP) },
            )

            root.removeViewAt(index)
            root.addView(row, index, params)
            logd("容器改为横排: ${container.javaClass.simpleName} → LinearLayout(HORIZONTAL)")
            row
        } catch (t: Throwable) {
            logw("容器改横排失败：${t.message}")
            null
        }
    }

    /**
     * 屏幕上只留一个承载视图。
     *
     * 原生 `NetworkSpeedView` 会 inflate 出多个实例（实测 4 个）。可见性桥把控制器的
     * `mShowNetworkSpeed` 钉成 true 之后，状态栏那个容器里会同时露出两个——屏幕上就是
     * 两块网速并排。凡是同时「已附着 + 可见」的实例，只留一个，其余处理掉。
     *
     * 留哪一个：优先留单位框有内容的那个（原生控制器真正在推的那个）；都为空时留最后注册的。
     *
     * **带资源 id 的那支（XML 膨胀出来的副本）直接 `removeView` 摘掉，不是 GONE。**
     * 原生控制器每次刷新都会把它重新点亮，只 GONE 的话屏幕右侧会一闪一闪；
     * 从父容器摘掉之后它就再也点不亮，而且它只是一份副本，摘掉没有副作用。
     * 代码 `new` 出来的那支（`id == NO_ID`）是真正在用的，只做 GONE 兜底。
     */
    private fun collapseDuplicates(views: List<View>) {
        val shown = views.filter { it.isAttachedToWindow && it.isShown }
        if (shown.size < 2) return
        val keep = shown.lastOrNull { unitHasText(it) } ?: shown.last()
        for (view in shown) {
            if (view === keep) continue
            val parent = view.parent as? ViewGroup
            if (view.id != View.NO_ID && parent != null) {
                parent.removeView(view)
                logd("摘掉重复视图: #${System.identityHashCode(view)} 保留 #${System.identityHashCode(keep)}")
            } else if (view.visibility != View.GONE) {
                view.visibility = View.GONE
                logd("隐藏重复视图: #${System.identityHashCode(view)} 保留 #${System.identityHashCode(keep)}")
            }
        }
    }

    private fun unitHasText(view: View): Boolean =
        ((getField(view, FIELD_UNIT_TEXT) as? TextView)?.text?.isNotEmpty() == true)

    /** 一次性视图结构诊断：搞清原生到底怎么摆这两个文本框、一共几个实例 */
    private fun dumpView(view: View) {
        if (!dumpedViews.add(System.identityHashCode(view))) return
        try {
            val number = getField(view, FIELD_NUMBER_TEXT) as? TextView ?: return
            val unit = getField(view, FIELD_UNIT_TEXT) as? TextView ?: return
            val parent = number.parent
            val sb = StringBuilder()
            sb.append("视图诊断: 视图=").append(view.javaClass.simpleName)
                .append(" #").append(System.identityHashCode(view))
                .append(" id=").append(resId(view))
                .append(" 父容器=").append(parent?.javaClass?.simpleName)
                .append("#").append(parent?.let { System.identityHashCode(it) })
                .append(" 子数=").append((parent as? ViewGroup)?.childCount)
            if (parent is LinearLayout) sb.append("(orientation=").append(parent.orientation).append(")")
            sb.append(" shown=").append(view.isShown)
                .append(" vis=").append(view.visibility)
                .append(" 视图宽=").append(view.width)
                .append(" lp宽=").append(view.layoutParams?.width)
                .append(" weight=").append((view.layoutParams as? LinearLayout.LayoutParams)?.weight)
                .append(" 父宽=").append((view.parent as? View)?.width)
                .append(" 数字框[").append(number.text).append("] size=").append(number.textSize)
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

        /** 左右两列之间的间距（dp） */
        const val COLUMN_GAP_DP = 6

        /** 标记「NetworkSpeedView 本身已重排成横排行」，避免每帧重排 */
        private const val ROW_TAG = 0x7F0F0001

        val STYLE_REFRESH_METHODS = arrayOf("onDensityOrFontScaleChanged", "onMiuiThemeChanged")
    }
}
