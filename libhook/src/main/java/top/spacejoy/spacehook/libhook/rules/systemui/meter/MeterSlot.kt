package top.spacejoy.spacehook.libhook.rules.systemui.meter

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 仪表专用槽位：一个**独立于原生网速视图**的显示载体。
 *
 * ## 为什么要自己造一个视图
 * 原生状态栏只有 `network_speed` 一个位置承载网速。功耗仪表和网速原本都写进
 * 那**同一个** NetworkSpeedView，而超级岛的遮挡是**逐个 View** 判定的 ——
 * 所以那一个 View 被挤掉时，两个指标会一起消失。分成两个 View 才能各自独立。
 *
 * ## 为什么复用 NetworkSpeedView 类
 * `MiuiStatusIconContainer.onMeasure()` 会把每个子 View **直接强转**成
 * `StatusIconDisplayable`（没有判空），插入的 View 必须实现该接口，否则状态栏直接崩。
 * 而 libhook 编译期没有系统界面的类，没法写 `class X : StatusIconDisplayable`。
 * NetworkSpeedView 本身就是 `LinearLayout + StatusIconDisplayable`，反射 new 一个最省事。
 *
 * ## 插入位置：永远追加到末尾
 * `IconManager.onRemoveIcon(i)` / `onSetIcon(i, ...)` 都是**按索引**操作子 View，
 * 索引由 `StatusBarIconList.getViewIndex()` 算出、只统计图标槽位，不知道我们多塞了一个。
 * 插在中间会让它删错/强转错对象（后者直接 ClassCastException 崩状态栏）。
 * **追加到末尾时所有原生图标的索引都不受影响**，这是用「位置靠外」换「绝不崩溃」。
 */
internal object MeterSlot {

    private var slotView: View? = null
    private var textView: TextView? = null
    private var dummyUnit: TextView? = null
    private var containerRef: WeakReference<ViewGroup>? = null

    /** 这个对象是不是我们造的槽位视图（Hook 里靠它跳过自己的视图） */
    fun isSlotView(target: Any?): Boolean = target != null && target === slotView

    /**
     * 已记录的容器（弱引用）。
     *
     * 网速视图可能被超级岛 / 状态栏重建清空，这时拿不到 anchor，
     * 但容器通常还在，靠这个兜底继续刷新仪表。
     */
    fun liveContainer(): ViewGroup? = containerRef?.get()?.takeIf { it.isAttachedToWindow }

    /** 是否已经成功挂上 */
    val attached: Boolean
        get() = slotView?.parent != null

    /** 诊断用：拿槽位视图本身 */
    fun viewForLog(): View? = slotView

    /**
     * 确保槽位视图存在并挂在容器末尾。
     *
     * @param anchor 原生 NetworkSpeedView（用来拿容器、取主题颜色）。
     *   它的 parent 就是 `MiuiStatusIconContainer`。
     * @return true 表示视图已就位
     */
    fun ensure(anchor: View): Boolean {
        val container = anchor.parent as? ViewGroup ?: return false
        containerRef = WeakReference(container)
        var view = slotView
        if (view == null) {
            view = create(anchor) ?: return false
            slotView = view
        }
        return attachTo(container, view)
    }

    /**
     * 兜底挂载：只知道容器、拿不到 anchor 时用。
     *
     * 网速视图被清空（超级岛占位、状态栏重建）后 anchor 会消失，
     * 但容器通常还在，靠它继续刷新仪表，避免仪表跟着停摆。
     */
    fun ensureIn(container: ViewGroup?): Boolean {
        if (container == null) return false
        var view = slotView
        if (view == null) {
            // 没 anchor 就拿容器自己当锚点：构造只需要一个 Context
            view = create(container) ?: return false
            slotView = view
        }
        return attachTo(container, view)
    }

    /** 把视图挂到容器末尾；已挂则直接返回 true */
    private fun attachTo(container: ViewGroup, view: View): Boolean {
        // 已经挂在这个容器里就不用动
        if (view.parent === container) return true

        // 被摘掉过（IconManager.destroy() 会 removeAllViews）→ 先脱离旧父容器
        (view.parent as? ViewGroup)?.removeView(view)

        // 两侧留白用**视图自身的 padding** 表达，而不是 layoutParams 的 margin。
        //
        // 容器 onLayout 按「子 View 宽度 + padding」累加 translationX 来摆位置，
        // 它会读 getPaddingStart()/getPaddingEnd()，所以 padding 能被正确计入；
        // 而 margin 在它的坐标系里完全不被考虑，设了也没用。
        val density = container.resources.displayMetrics.density
        val padStart = (marginStartDp * density).toInt()
        val padEnd = (marginEndDp * density).toInt()
        if (view.paddingLeft != padStart || view.paddingRight != padEnd) {
            view.setPadding(padStart, view.paddingTop, padEnd, view.paddingBottom)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        return try {
            // 追加到末尾（下标最大）。
            //
            // 容器 onLayout 把所有子 View 都 layout 到 x=0，位置完全由 translationX 决定；
            // 它倒序遍历子 View、逐个向左累加，所以**下标越大 → translationX 越大 → 越靠右**。
            // 实测：下标 32 → tx=326（屏X=977，紧贴电池左侧，正确）；
            //       下标 0  → tx=144（屏X=795，落在容器中间，不对）。
            //
            // 追加到末尾还有个好处：原生图标的索引全都不受影响。
            container.addView(view, params)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 反射构造一个 NetworkSpeedView 实例，并把它的三个关键字段换成我们自己的 TextView。
     *
     * - `mContainer` 指向自己：原生 `setVisibleState()` 会拿它 setVisibility，
     *   为 null 会 NPE（`onFinishInflate` 没跑过，字段是空的）
     * - `mNetworkSpeedNumberText` 是真正显示文字的那个 TextView
     * - `mNetworkSpeedUnitText` 给一个隐藏的占位，原生 `updateLightDarkTint$1()`
     *   会同时对它 setTextColor，为 null 会 NPE
     * - `mVisibleByController = true` 让 `isIconVisible()` 返回 true，否则不参与排布
     *
     * **不调用 `setVisibilityByController()`**：它会连锁触发 `updateVisibility()`
     * → `updateNetworkSpeed$1()`，把原生格式（带空格、单行）覆盖到我们的文本上。
     */
    private fun create(anchor: View): View? {
        val context = anchor.context
        val clazz = try {
            Class.forName(CLS_VIEW, false, anchor.javaClass.classLoader)
        } catch (_: Throwable) {
            return null
        }
        val instance = try {
            clazz.getConstructor(Context::class.java).newInstance(context) as View
        } catch (_: Throwable) {
            return null
        }

        val number = TextView(context).apply {
            includeFontPadding = false
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = false
            maxLines = MAX_LINES
            // 两行文本默认行距偏大，状态栏高度有限，压紧一点
            setLineSpacing(0f, 0.92f)
        }
        val unit = TextView(context).apply {
            visibility = View.GONE
        }

        // LinearLayout 默认就是 HORIZONTAL，这里用竖直排布承载「双行」
        (instance as? LinearLayout)?.orientation = LinearLayout.VERTICAL
        (instance as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL
        // 容器可能 clipChildren，位移后右缘会被裁掉；抬高一点也避免被相邻图标压住
        (instance as? ViewGroup)?.clipChildren = false
        (instance as? ViewGroup)?.clipToPadding = false
        instance.elevation = 1f
        (instance as? ViewGroup)?.addView(
            number,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        (instance as? ViewGroup)?.addView(
            unit,
            LinearLayout.LayoutParams(0, 0),
        )

        setField(instance, "mContainer", instance)
        setField(instance, "mNetworkSpeedNumberText", number)
        setField(instance, "mNetworkSpeedUnitText", unit)
        setField(instance, "mNetworkSpeedNumber", "")
        setField(instance, "mNetworkSpeedUnit", "")
        setField(instance, "mVisibleByController", true)
        setField(instance, "mBlocked", false)

        textView = number
        dummyUnit = unit

        // 不要动 translationX！
        //
        // 容器的 onLayout 不调用 super.onLayout，而是把所有子 View 都 layout 到 x=0，
        // 再用 translationX 摆位置（实测容器屏X=651、宽=380，所有子 View 的 x 都是 0）。
        // 也就是说 translationX 才是唯一的位置来源，归零会让视图停在容器左边界。
        return instance
    }

    /** 写入仪表文本；[anchor] 用来同步主题色与字号 */
    fun write(text: String, anchor: View?, fontSizeSp: Int, fontStyle: Int, align: Int) {
        val number = textView ?: return
        if (number.text?.toString() != text) number.text = text
        // 不要碰 translationX：容器只靠它定位（onLayout 把所有子 View 都摆在 x=0）
        // translationY 必须归零：原生 updateResources$15() 会写负偏移（见 applyTextStyle 注释）
        resetTranslationY()

        // 颜色跟原生网速视图保持一致，主题切换（深/浅色）才能对上。
        // anchor 可能已经消失（超级岛占位），这时沿用上次记下的颜色，别把文字弄成默认黑。
        val anchorNumber = anchor?.let { getField(it, "mNetworkSpeedNumberText") as? TextView }
        if (anchorNumber != null) {
            lastTextColor = anchorNumber.currentTextColor
            lastTextSizePx = anchorNumber.textSize
        }
        val color = if (anchorNumber != null) anchorNumber.currentTextColor else lastTextColor
        if (color != 0 && number.currentTextColor != color) {
            number.setTextColor(color)
        }

        // 字号：0 = 跟随原生
        val target = when {
            fontSizeSp > 0 -> fontSizeSp.toFloat()
            // 原生是 px，转成 sp 再设回去；用 TypedValue 反算避免 scaledDensity 的废弃告警
            lastTextSizePx > 0f -> TypedValue.deriveDimension(
                TypedValue.COMPLEX_UNIT_SP,
                lastTextSizePx,
                number.resources.displayMetrics,
            )
            else -> 0f
        }
        if (target > 0f) {
            number.setTextSize(TypedValue.COMPLEX_UNIT_SP, target)
        }

        number.typeface = when (fontStyle) {
            FONT_LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            FONT_BOLD -> Typeface.create(number.typeface, Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
        number.paint.isFakeBoldText = fontStyle == FONT_BOLD

        number.gravity = when (align) {
            ALIGN_LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            ALIGN_CENTER -> Gravity.CENTER
            else -> Gravity.END or Gravity.CENTER_VERTICAL
        }
    }

    /**
     * 槽位视图自己的样式通道：**只改字号 / 字重 / 对齐 / 位置归零，绝不碰 maxLines**。
     *
     * ## 为什么必须单独一条路
     * 槽位视图是反射 new 出来的 `NetworkSpeedView`（原因见类注释），所以它同样会被
     * `NetworkSpeedView.onDensityOrFontScaleChanged` / `onMiuiThemeChanged` 的 hook 扫到。
     * 那条 hook 会调 `StatusBarMeterHookBase.applyStyle()` → `applyLayout()`，而 `applyLayout()`
     * 里有 `number.maxLines = 1` —— 那是写给**原生网速视图**的规则（它把数字和单位拆成两个框）。
     *
     * 槽位视图是**单 TextView + `\n` 双行**，完全依赖 `maxLines = 2`。一旦被改成 1，
     * 第二行（温度）就被裁掉，但字符串还在，于是「日志里有温度、屏幕上没有」。
     * 实测：改一次系统字号即复现，重启 SystemUI 才恢复。
     *
     * ## 为什么必须在这里归零 translationY
     * 原生 `NetworkSpeedView.updateResources$15()` 每次配置刷新都会执行：
     * ```
     * mNetworkSpeedNumberText.setTranslationY(-number_offset)   // 负值，向上抬
     * mNetworkSpeedUnitText.setTranslationY(+unit_offset)
     * ```
     * 这是给原生「两个框上下错位叠成两行」用的。槽位视图复用同一个字段
     * `mNetworkSpeedNumberText`，于是也被抬上去，表现为「文字整体上移、底部被切」。
     * 原来 `applyLayout()` 末尾会归零 translationY，但那里已经对槽位视图 return 了，
     * 所以必须在这里补回来 —— 否则还原字号也不会恢复。
     */
    fun applyTextStyle(fontSizeSp: Int, fontStyle: Int, align: Int) {
        val number = textView ?: return
        if (fontSizeSp > 0) {
            number.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
        }
        number.typeface = when (fontStyle) {
            FONT_LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            FONT_BOLD -> Typeface.create(number.typeface, Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
        number.paint.isFakeBoldText = fontStyle == FONT_BOLD
        number.gravity = when (align) {
            ALIGN_LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            ALIGN_CENTER -> Gravity.CENTER
            else -> Gravity.END or Gravity.CENTER_VERTICAL
        }
        // 位置归零：原生 updateResources$15() 会把这个 TextView 的 translationY 设成
        // 负偏移（给原生错位双行用的），在槽位里表现为整体抬升
        resetTranslationY()
    }

    /**
     * 把槽位 TextView 的**垂直**位移归零。
     *
     * 槽位靠 `LinearLayout` 正常排布 + `\n` 换行，不需要任何 translationY 错位；
     * 一旦被原生写入非 0 值，文字就会偏出状态栏可视区（表现为「整体抬升、底部被切」）。
     *
     * **只动 translationY，绝不碰 translationX。** 容器 `MiuiStatusIconContainer.onLayout`
     * 把所有子 View 都 layout 到 x=0，位置完全由 translationX 决定（见 [attachTo] 的注释）；
     * 归零 X 会让视图滑到容器左边界。
     */
    private fun resetTranslationY() {
        textView?.let { if (it.translationY != 0f) it.translationY = 0f }
        // 占位单位框同样会被原生写 translationY，一并归零避免撑高容器
        dummyUnit?.let { if (it.translationY != 0f) it.translationY = 0f }
    }

    /**
     * 自愈：`maxLines` / `isSingleLine` / 垂直位移被外部改动过就改回去。
     *
     * 兜住所有已知和未知的样式刷新路径（原生 `setTextAppearance`、`updateResources$15()`、
     * 主题切换、状态栏重建…），让用户看不到故障，而不是等重启 SystemUI。
     *
     * @return true 表示这次确实修过（调用方应打一条 warn，方便发现还有别的路径在改它）
     */
    fun repairIfBroken(): Boolean {
        val number = textView ?: return false
        var repaired = false
        if (number.isSingleLine) {
            number.isSingleLine = false
            repaired = true
        }
        if (number.maxLines != MAX_LINES) {
            number.maxLines = MAX_LINES
            repaired = true
        }
        // 垂直位移也要兜：原生 updateResources$15() 每次配置刷新都会重设
        if (number.translationY != 0f) {
            resetTranslationY()
            repaired = true
        }
        return repaired
    }

    /** 诊断用：当前实际行数配置（心跳日志里打出来，下次出问题不用靠截图反推） */
    fun maxLinesForLog(): Int = textView?.maxLines ?: -1

    /** 诊断用：当前是否被置成单行 */
    fun singleLineForLog(): Boolean = textView?.isSingleLine ?: false

    /** 诊断用：槽位视图自身高度（被裁时这里会异常小） */
    fun heightForLog(): Int = slotView?.height ?: -1

    /** 诊断用：文字框当前的垂直位移（非 0 说明被原生 updateResources$15() 抬升过） */
    fun translationYForLog(): Float = textView?.translationY ?: 0f

    /** 卸下槽位（两个 Hook 不再同时启用时调用） */
    fun detach() {
        val view = slotView ?: return
        try {
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (_: Throwable) {
            // 容器已销毁，忽略
        }
    }

    /** 彻底重置（Hook 卸载 / 配置全关时） */
    fun reset() {
        detach()
        slotView = null
        textView = null
        dummyUnit = null
        containerRef = null
    }

    private fun setField(target: Any, name: String, value: Any?): Boolean {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(target, value)
                return true
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return false
    }

    private fun getField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    /** 槽位名：刻意避开 network_speed，免得被极简模式黑名单连坐 */
    const val SLOT_NAME = "spacehook_meter"

    /** 与左侧相邻图标的间距（dp），由 Hook 侧按配置写入 */
    @Volatile var marginStartDp = 6

    /**
     * 与右侧相邻图标（通常是电池）的间距（dp）。
     *
     * 槽位视图排在容器最右侧，右边紧邻电池图标，太贴会显得挤。
     */
    @Volatile var marginEndDp = 6



    /** 上次从原生网速视图读到的文字颜色 / 字号，anchor 消失后沿用 */
    private var lastTextColor = 0
    private var lastTextSizePx = 0f


    private const val CLS_VIEW = "com.android.systemui.statusbar.views.NetworkSpeedView"

    /**
     * 槽位 TextView 的固定行数。
     *
     * 槽位用 `\n` 把多段文本拼进**同一个** TextView（见 `StatusBarMeterState.composeSlot()`），
     * 双行显示完全依赖这个值，所以必须是常量、并且要能被 [repairIfBroken] 强制写回。
     */
    private const val MAX_LINES = 2

    private const val FONT_LIGHT = 1
    private const val FONT_BOLD = 2

    private const val ALIGN_LEFT = 2
    private const val ALIGN_CENTER = 3
}
