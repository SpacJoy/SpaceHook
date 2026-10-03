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

    /** 一个显示贡献者：负责渲染自己那段文本 */
    interface Contributor {
        /** 排序权重，小的排前面（功耗仪表 0、实时网速 10） */
        val order: Int

        /** 单行 [LAYOUT_SINGLE] / 双行 [LAYOUT_DUAL] */
        val layout: Int

        val bold: Boolean
        val fontSizeSp: Int
        val refreshMs: Int

        /** 渲染自己那段文本（可以多段，例如仪表的一个槽位一段）；空列表 = 本次无数据 */
        fun render(reader: MeterReader): List<String>
    }

    const val LAYOUT_SINGLE = 0
    const val LAYOUT_DUAL = 1

    private val contributors = LinkedHashMap<String, Contributor>()

    @Volatile
    private var installed = false

    @Volatile
    internal var viewRef: WeakReference<View>? = null

    @Volatile
    internal var reader: MeterReader? = null

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

    /** 组装当前要写进状态栏的文本（数字框, 单位框）；null = 交还原生行为 */
    @Synchronized
    fun compose(): Pair<String, String>? {
        val source = reader ?: return null
        val parts = ArrayList<String>(5)
        for (contributor in contributors.values.sortedBy { it.order }) {
            val rendered = try {
                contributor.render(source)
            } catch (_: Throwable) {
                emptyList()
            }
            for (part in rendered) if (part.isNotEmpty()) parts.add(part)
        }
        if (parts.isEmpty()) return null
        return if (primary()?.layout == LAYOUT_DUAL && parts.size > 1) {
            parts[0] to parts.drop(1).joinToString(" ")
        } else {
            parts.joinToString(" ") to ""
        }
    }

    private const val DEFAULT_REFRESH_MS = 1000
}
