package top.spacejoy.spacehook.libhook.rules.systemui.meter

import android.view.View
import java.lang.ref.WeakReference

/**
 * 状态栏仪表的共享状态。
 *
 * 「功耗/温度仪表」和「实时网速」是两个独立的 Hook（独立开关、独立参数页）。
 * 改造前它们写进**同一个**原生网速视图，而超级岛遮挡是逐个 View 判定的，
 * 所以一个被挤掉两个一起消失。现在拆成两条输出链路：
 *
 * - **网速**（[Contributor.separate] = false）→ 继续写原生 `NetworkSpeedView`
 * - **仪表**（[Contributor.separate] = true）→ 写 [MeterSlot] 造的独立槽位视图
 *
 * 两个 View 各自参与遮挡计算，互不影响。
 */
internal object StatusBarMeterState {

    /**
     * 一个显示贡献者：负责渲染自己那段文本。
     *
     * 除 [order] / [separate] / [render] 外全部给了默认值——排布与样式只由
     * 各自的贡献者说了算，另一方的字段会被忽略。
     */
    interface Contributor {
        /** 排序权重，小的排前面（功耗仪表 0、实时网速 10） */
        val order: Int

        /** 是否走独立槽位：仪表 true，网速 false */
        val separate: Boolean get() = false

        /** 单行 [LAYOUT_SINGLE] / 自动多行 [LAYOUT_DUAL] */
        val layout: Int get() = LAYOUT_DUAL

        /** 0 = 跟随系统原生字号 */
        val fontSizeSp: Int get() = 0

        /** [FONT_DEFAULT] / [FONT_LIGHT] / [FONT_BOLD] */
        val fontStyle: Int get() = FONT_DEFAULT

        /** [ALIGN_NATIVE] / [ALIGN_LEFT] / [ALIGN_CENTER] / [ALIGN_RIGHT] */
        val align: Int get() = ALIGN_NATIVE

        /** 0 = 不固定宽度 */
        val fixedWidthDp: Int get() = 0

        val leftMarginDp: Int get() = 0
        val rightMarginDp: Int get() = 0

        /** 负值上移、正值下移 */
        val verticalOffsetDp: Int get() = 0

        val refreshMs: Int get() = 1000

        /** 渲染自己那段文本（可以多段，例如仪表的一个槽位一段）；空列表 = 本次无内容 */
        fun render(reader: MeterReader): List<String>
    }

    const val LAYOUT_SINGLE = 0
    const val LAYOUT_DUAL = 1

    const val FONT_DEFAULT = 0
    const val FONT_LIGHT = 1
    const val FONT_BOLD = 2

    const val ALIGN_NATIVE = 1
    const val ALIGN_LEFT = 2
    const val ALIGN_CENTER = 3
    const val ALIGN_RIGHT = 4

    private val contributors = LinkedHashMap<String, Contributor>()

    @Volatile
    private var installed = false

    /**
     * 承载网速的原生视图。原生 `NetworkSpeedView` **不止一个实例**
     * （状态栏一处，系统别处也会 inflate），只记一个的话其余实例会冻在旧文本上。
     */
    private val views = ArrayList<WeakReference<View>>()

    @Volatile
    internal var reader: MeterReader? = null

    /** 诊断回调：组装结果发生变化时触发一次 */
    @Volatile
    var onCompose: ((List<String>, Pair<String, String>) -> Unit)? = null

    private var lastComposed: Pair<String, String>? = null

    /**
     * 文本外空隙（dp），由两个 Hook 各自写自己那一份。
     * 现在只有网速还用得上（仪表已独立成槽位）。
     */
    @Volatile var speedGapLeftDp = 0
    @Volatile var speedGapRightDp = 0
    @Volatile var meterGapLeftDp = 0
    @Volatile var meterGapRightDp = 0

    /** 登记一个承载网速的视图 */
    @Synchronized
    fun addView(view: View) {
        views.removeAll { it.get() == null }
        if (views.none { it.get() === view }) views.add(WeakReference(view))
    }

    /** 当前存活的承载视图（顺带清掉已被回收的） */
    @Synchronized
    fun liveViews(): List<View> {
        views.removeAll { it.get() == null }
        return views.mapNotNull { it.get() }
    }

    /**
     * 抢占安装权：hook 点只能装一次，两个 Hook 里谁先跑到谁装。
     * 返回 true 表示这次该由调用方安装。
     */
    @Synchronized
    fun claimInstaller(): Boolean {
        if (installed) return false
        installed = true
        return true
    }

    @Synchronized
    fun register(owner: String, contributor: Contributor) {
        contributors[owner] = contributor
    }

    @Synchronized
    fun unregister(owner: String) {
        contributors.remove(owner)
    }

    /** 是否至少有一个 Hook 在提供内容 */
    val active: Boolean
        @Synchronized get() = contributors.isNotEmpty()

    /** 是否有贡献者走独立槽位（即仪表开着） */
    val hasSeparate: Boolean
        @Synchronized get() = contributors.values.any { it.separate }

    /** 是否有贡献者写原生视图（即网速开着） */
    val hasNative: Boolean
        @Synchronized get() = contributors.values.any { !it.separate }

    /** 刷新间隔取所有贡献者的最小值 */
    @Synchronized
    fun refreshMs(): Int = contributors.values.minOfOrNull { it.refreshMs } ?: DEFAULT_REFRESH_MS

    /** 独立槽位的贡献者（仪表），按 order 排序 */
    @Synchronized
    fun separateContributor(): Contributor? =
        contributors.values.filter { it.separate }.minByOrNull { it.order }

    /**
     * 写原生网速视图的那个贡献者（网速）。
     *
     * 原生视图的排布与样式只由它决定——仪表已经搬去独立槽位了，
     * 不能再用仪表的字号/对齐去改网速视图。
     */
    @Synchronized
    fun nativeContributor(): Contributor? =
        contributors.values.filter { !it.separate }.minByOrNull { it.order }

    /**
     * 组装要写进**原生网速视图**的文本（数字框, 单位框）；null = 交还原生行为。
     *
     * 网速自己决定单行还是双行：双行时值是数字框、单位是单位框。
     */
    @Synchronized
    fun composeNative(): Pair<String, String>? {
        val source = reader ?: return null
        val parts = ArrayList<String>(5)
        for (contributor in contributors.values.sortedBy { it.order }) {
            if (contributor.separate) continue
            parts.addAll(safeRender(contributor, source))
        }
        // 有贡献者但都没内容 = 主动留空（例如设了「仅充电时显示」而当前没插电），
        // 这时要写空串把原生内容顶掉，而不是交还原生。
        if (parts.isEmpty()) return "" to ""

        // 排布决定「两段文本怎么落到两个文本框上」：
        // - 单行：全部用空格连成一行，只写数字框，单位框留空（→ 单位框会被 GONE）
        // - 双行：第一段写数字框、其余写单位框，由视图层把两个框**上下堆叠**
        //
        // 注意这里是「按排布切分」，不是「按段数切分」。以前用 parts.size >= 2 直接切，
        // 会把「显式设了单行但样式产出两段」的情况也切成两列，结果只能是单行。
        val single = nativeContributor()?.layout == LAYOUT_SINGLE
        val result = if (single) {
            parts.joinToString(" ") to ""
        } else {
            parts[0] to parts.drop(1).joinToString(" ")
        }
        if (result != lastComposed) {
            lastComposed = result
            onCompose?.invoke(parts, result)
        }
        return result
    }

    /**
     * 组装要写进**独立槽位**的文本；null = 无内容（不写）。
     *
     * 多个槽位按 [Contributor.layout] 决定排成一行还是自动切两行。
     */
    @Synchronized
    fun composeSlot(): String? {
        val source = reader ?: return null
        val primary = separateContributor() ?: return null
        val parts = ArrayList<String>(5)
        for (contributor in contributors.values.sortedBy { it.order }) {
            if (!contributor.separate) continue
            parts.addAll(safeRender(contributor, source))
        }
        if (parts.isEmpty()) return ""
        return if (primary.layout == LAYOUT_SINGLE) {
            parts.joinToString(" ")
        } else {
            column(parts)
        }
    }

    /** 渲染一个贡献者，异常时当空处理，绝不让它拖垮状态栏 */
    private fun safeRender(contributor: Contributor, source: MeterReader): List<String> {
        val rendered = try {
            contributor.render(source)
        } catch (_: Throwable) {
            emptyList()
        }
        return rendered.filter { it.isNotEmpty() }
    }

    /**
     * 把一段内容排成 1–2 行：2 段以上就均衡切分（4 段 → 2+2，3 段 → 2+1），行间换行。
     * 只有 1 段就保持单行——免得留下空的第二行。
     */
    private fun column(list: List<String>): String {
        if (list.isEmpty()) return ""
        if (list.size <= 1) return list.joinToString(" ")
        val half = (list.size + 1) / 2
        return list.take(half).joinToString(" ") + "\n" + list.drop(half).joinToString(" ")
    }

    private const val DEFAULT_REFRESH_MS = 1000
}
