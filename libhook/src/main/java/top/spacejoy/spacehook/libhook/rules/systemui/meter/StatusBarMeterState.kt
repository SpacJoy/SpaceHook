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
 * - 双行：第 1 段占第一行，其余占第二行
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
        // 按贡献者分组：仪表一组、网速一组，组内保持槽位顺序
        val groups = ArrayList<List<String>>(2)
        val parts = ArrayList<String>(5)
        for (contributor in contributors.values.sortedBy { it.order }) {
            val rendered = try {
                contributor.render(source)
            } catch (_: Throwable) {
                emptyList()
            }
            val kept = rendered.filter { it.isNotEmpty() }
            if (kept.isNotEmpty()) {
                groups.add(kept)
                parts.addAll(kept)
            }
        }
        // 有贡献者但都没内容 = 主动留空（例如设了「仅充电时显示」而当前没插电），
        // 这时要写空串把原生内容顶掉，而不是交还原生。
        if (parts.isEmpty()) return "" to ""
        // 左右双列：左列 = 仪表（功耗/电流/温度），右列 = 网速（下行/上行），两列各自可占两行。
        // 单行：全部挤进「数字」框，右列留空。
        val result = if (primary()?.layout == LAYOUT_DUAL && parts.size > 1) {
            val rows = if (groups.any { it.size > COLUMN_MAX_PARTS_PER_ROW }) 2 else 1
            column(groups.getOrNull(0).orEmpty(), rows) to
                column(groups.drop(1).flatten(), rows)
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
     * 把一组片段排成一列：[rows] 行时均衡切分（4 段 → 2+2，3 段 → 2+1），行间换行。
     * 单行时全部用空格连起来。
     */
    private fun column(list: List<String>, rows: Int): String {
        if (list.isEmpty()) return ""
        if (rows <= 1) return list.joinToString(" ")
        val half = (list.size + 1) / 2
        return list.take(half).joinToString(" ") + "\n" + list.drop(half).joinToString(" ")
    }

    private const val DEFAULT_REFRESH_MS = 1000

    /** 单列里一行最多放几段；超过就分两行 */
    private const val COLUMN_MAX_PARTS_PER_ROW = 2
}
