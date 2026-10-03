package top.spacejoy.spacehook.libhook.rules.systemui

import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.rules.systemui.meter.MeterReader
import top.spacejoy.spacehook.libhook.rules.systemui.meter.StatusBarMeterHookBase
import top.spacejoy.spacehook.libhook.rules.systemui.meter.StatusBarMeterState
import top.spacejoy.spacehook.libhook.utils.PrefsBridge

/**
 * 状态栏实时网速。
 *
 * 与「状态栏功耗/温度仪表」共用原生网速视图这一个显示位置（hook 点由
 * [StatusBarMeterHookBase] 统一安装），本类只负责把 TrafficStats 的收发差分
 * 格式化成速率文本。
 *
 * 四种样式：
 * - 0 值和单位单行（`12.3KB/s`）
 * - 1 值和单位双排（数字一行、单位一行）
 * - 2 上下行单行（`↓12.3KB/s ↑1.2KB/s`）
 * - 3 上下行双排（下行一行、上行一行）
 *
 * 单独开启时按自己的字号 / 刷新间隔显示；与仪表同时开启时，排布与样式以仪表为准，
 * 网速那段接在仪表后面。
 */
internal class StatusBarSpeedHook : StatusBarMeterHookBase("status_bar_speed") {

    override fun createContributor(): StatusBarMeterState.Contributor =
        object : StatusBarMeterState.Contributor {

            override val order: Int = ORDER

            /**
             * 单行 / 双行。
             *
             * 页面上的「行数」独立开关优先；没设过（[LAYOUT_FOLLOW_STYLE]）时按 [style] 推导——
             * style 的 1 / 3 本来就是「值和单位双排」「上下行双排」，语义上就是双行。
             *
             * 双行的含义：单独显示时下行一行、上行一行；与功耗/温度仪表同时开启时，
             * 仪表占第一行、网速占第二行（见 [StatusBarMeterState.compose]）。
             */
            override val layout: Int
                get() {
                    val explicit = cfg("layout", LAYOUT_FOLLOW_STYLE)
                    if (explicit == StatusBarMeterState.LAYOUT_SINGLE ||
                        explicit == StatusBarMeterState.LAYOUT_DUAL
                    ) {
                        return explicit
                    }
                    return if (style == STYLE_VALUE_UNIT_DUAL || style == STYLE_RX_TX_DUAL) {
                        StatusBarMeterState.LAYOUT_DUAL
                    } else {
                        StatusBarMeterState.LAYOUT_SINGLE
                    }
                }

            override val bold: Boolean get() = cfg("bold", OFF) == ON

            override val fontStyle: Int get() = cfg("font_style", StatusBarMeterState.FONT_DEFAULT)

            override val align: Int get() = cfg("align", StatusBarMeterState.ALIGN_NATIVE)

            override val fixedWidthDp: Int get() = cfg("fixed_width", 0).coerceIn(0, 120)

            override val leftMarginDp: Int get() = cfg("left_margin", 0).coerceIn(0, 16)

            override val rightMarginDp: Int get() = cfg("right_margin", 0).coerceIn(0, 16)

            override val verticalOffsetDp: Int get() = cfg("vertical_offset", 0).coerceIn(-20, 20)

            override val fontSizeSp: Int get() = cfg("font_size_sp", 0).coerceIn(0, 24)

            override val refreshMs: Int get() = cfg("refresh_ms", 1000).coerceIn(500, 5000)

            override fun render(reader: MeterReader): List<String> {
                val speed = reader.netSpeed()
                // 「隐藏网速」：全部隐藏，或低于阈值时留空（阈值按收发合计算）
                if (cfg("hide", OFF) == ON) {
                    val threshold = cfg("hide_slow", DEFAULT_HIDE_SLOW_KB).coerceIn(1, 2048)
                    val totalKb = (speed.rx + speed.tx) / 1024.0
                    if (cfg("hide_all", OFF) == ON || totalKb < threshold) return listOf("")
                }
                val swap = cfg("swap_places", OFF) == ON
                return when (style) {
                    STYLE_VALUE_UNIT_DUAL -> {
                        val (value, unit) = rateParts(speed.rx + speed.tx)
                        listOf(value, unit)
                    }

                    STYLE_RX_TX_SINGLE -> {
                        val (first, second) = rxTx(speed, swap)
                        listOf("$first $second")
                    }

                    STYLE_RX_TX_DUAL -> {
                        val (first, second) = rxTx(speed, swap)
                        listOf(first, second)
                    }

                    else -> listOf(rateText(speed.rx + speed.tx))
                }
            }
        }

    // ==================== 配置 ====================

    private fun cfg(key: String, def: Int): Int = PrefsBridge.getInt(HookPrefs.configKey(id, key), def)

    private val style: Int get() = cfg("style", STYLE_VALUE_UNIT_SINGLE)

    private val decimals: Int get() = cfg("decimals", 1).coerceIn(0, 2)

    private val hideUnit: Boolean get() = cfg("hide_unit", OFF) == ON

    /** 速率拆成「数字 / 单位」两段；`hide_unit` 时单位段为空 */
    private fun rateParts(bytesPerSec: Double): Pair<String, String> {
        val kb = bytesPerSec / 1024.0
        val value: String
        val unit: String
        if (kb >= 1024.0) {
            value = MeterReader.fmt(kb / 1024.0, decimals)
            unit = "MB/s"
        } else {
            value = MeterReader.fmt(kb, decimals)
            unit = "KB/s"
        }
        return if (hideUnit) value to "" else value to unit
    }

    private fun rateText(bytesPerSec: Double): String {
        val (value, unit) = rateParts(bytesPerSec)
        return value + unit
    }

    /** 上下行两段，下行在前；`swap_places` 交换顺序 */
    private fun rxTx(speed: MeterReader.NetSpeed, swap: Boolean): Pair<String, String> {
        val down = "↓" + rateText(speed.rx)
        val up = "↑" + rateText(speed.tx)
        return if (swap) up to down else down to up
    }

    private companion object {

        /** 网速排在功耗仪表后面 */
        const val ORDER = 10

        const val STYLE_VALUE_UNIT_SINGLE = 0
        const val STYLE_VALUE_UNIT_DUAL = 1
        const val STYLE_RX_TX_SINGLE = 2
        const val STYLE_RX_TX_DUAL = 3

        /** 「行数」没设过，按 [style] 推导单行 / 双行 */
        const val LAYOUT_FOLLOW_STYLE = -1

        /** 低速隐藏阈值默认值（KB/s） */
        const val DEFAULT_HIDE_SLOW_KB = 64

        const val OFF = 0
        const val ON = 1
    }
}
