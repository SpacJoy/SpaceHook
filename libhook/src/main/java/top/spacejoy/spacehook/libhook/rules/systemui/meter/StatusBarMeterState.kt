package top.spacejoy.spacehook.libhook.rules.systemui.meter

import android.view.View
import java.lang.ref.WeakReference

/**
 * 状态栏仪表的共享状态。
 *
 * 「功耗/温度仪表」和「实时网速」是两个独立的 Hook（独立开关、独立参数页），
 * 但它们显示在同一个位置——原生网速视图。视图只有一个、hook 点只能装一次，
 * 所以两边各自注册一个 [Contributor] 提供自己那段文本，由这里统一组装。
 *
 * 组装规则：
 * - 单行：所有片段按 [Contributor.order] 升序用空格连成一行，放进「数字」文本框
 * - 左右双列：按 [Contributor.column] 分栏——仪表恒在左列、网速恒在右列，
 *   列内按 order / 槽位顺序排列，每列各自均衡切成 1–2 行。
 *   **分栏与「开了几个槽位」无关**，只有网速在时它照样在右列。
 * - 排布 / 字号 / 粗细 / 刷新间隔以 order 最小的贡献者为准（功耗仪表优先）
 */
internal object StatusBarMeterState {

    /**
     * 一个显示贡献者：负责渲染自己那段文本。
     *
     * 除 [order] / [layout] / [render] 外全部给了默认值——**排布与样式只由
     * [primary] 那个贡献者说了算**，另一方的样式字段会被忽略，所以次要的
     * 一方不必实现它们。
     */
    interface Contributor {
        /** 排序权重，小的排前面（功耗仪表 0、实时网速 10） */
        val order: Int

        /** 落在哪一列：[COLUMN_LEFT]（默认，仪表）/ [COLUMN_RIGHT]（网速） */
        val column: Int get() = COLUMN_LEFT

        /** 单行 [LAYOUT_SINGLE] / 双行 [LAYOUT_DUAL] */
        val layout: Int get() = LAYOUT_DUAL

        val bold: Boolean get() = false

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

    const val COLUMN_LEFT = 0
    const val COLUMN_RIGHT = 1

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
     * 承载视图。原生网速视图**不止一个实例**（状态栏一处，系统别处也会 inflate），
     * 只记一个的话其余实例会冻在旧文本上——所以全部登记、全部刷新。
     */
    private val views = ArrayList<WeakReference<View>>()

    @Volatile
    internal var reader: MeterReader? = null

    /** 诊断回调：组装结果发生变化时触发一次 */
    @Volatile
    var onCompose: ((List<String>, Pair<String, String>) -> Unit)? = null

    private var lastComposed: Pair<String, String>? = null

    /** 登记一个承载视图 */
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

    /** 排布 / 字号 / 粗细 / 刷新间隔以它为准 */
    @Synchronized
    fun primary(): Contributor? = contributors.values.minByOrNull { it.order }

    @Synchronized
    fun refreshMs(): Int = contributors.values.minOfOrNull { it.refreshMs } ?: DEFAULT_REFRESH_MS

    /** 组装当前要写进状态栏的文本（第一行, 第二行）；null = 交还原生行为 */
    @Synchronized
    fun compose(): Pair<String, String>? {
        val source = reader ?: return null
        // 按列分组：左列（仪表）、右列（网速），列内保持槽位顺序
        val columns = arrayOf(ArrayList<String>(5), ArrayList<String>(3))
        val parts = ArrayList<String>(5)
        for (contributor in contributors.values.sortedBy { it.order }) {
            val rendered = try {
                contributor.render(source)
            } catch (_: Throwable) {
                emptyList()
            }
            val kept = rendered.filter { it.isNotEmpty() }
            if (kept.isNotEmpty()) {
                columns[if (contributor.column == COLUMN_RIGHT) 1 else 0].addAll(kept)
                parts.addAll(kept)
            }
        }
        // 有贡献者但都没内容 = 主动留空（例如设了「仅充电时显示」而当前没插电），
        // 这时要写空串把原生内容顶掉，而不是交还原生。
        if (parts.isEmpty()) return "" to ""
        // 左右双列：仪表恒在左列（功耗/电流/温度），网速恒在右列（下行/上行），
        // 每列各自均衡切行。分栏与「开了几个槽位」无关。
        // 单行：全部挤进「数字」框，右列留空。
        val result = if (primary()?.layout == LAYOUT_DUAL) {
            column(columns[0]) to column(columns[1])
        } else {
            parts.joinToString(" ") to ""
        }
        if (result != lastComposed) {
            lastComposed = result
            onCompose?.invoke(parts, result)
        }
        return result
    }

    /**
     * 把一列片段排成 1–2 行：2 段以上就均衡切分（4 段 → 2+2，3 段 → 2+1），行间换行。
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
