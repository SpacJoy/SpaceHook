package top.spacejoy.spacehook.libhook.rules.systemui

import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.rules.systemui.meter.MeterReader
import top.spacejoy.spacehook.libhook.rules.systemui.meter.StatusBarMeterHookBase
import top.spacejoy.spacehook.libhook.rules.systemui.meter.StatusBarMeterState
import top.spacejoy.spacehook.libhook.utils.PrefsBridge

/**
 * 状态栏功耗 / 温度仪表。
 *
 * 接管状态栏原生「实时网速」的显示位置，改为显示电池功耗（W）、电流（mA）、
 * CPU 温度、电池温度中的最多 4 项。hook 点由 [StatusBarMeterHookBase] 统一安装，
 * 本类只负责把「槽位配置」翻译成显示文本。
 *
 * 显示位置的排布 / 样式 / 刷新间隔以本 Hook 为准；同时开启「状态栏实时网速」时，
 * 网速那段会排在仪表后面一起显示。
 */
internal class StatusBarMeterHook : StatusBarMeterHookBase("status_bar_meter") {

    override fun createContributor(): StatusBarMeterState.Contributor =
        object : StatusBarMeterState.Contributor {

            override val order: Int = ORDER

            /** 仪表恒在右列（网速恒在左列），与开了几个槽位无关 */
            override val column: Int = StatusBarMeterState.COLUMN_RIGHT

            override val layout: Int
                get() {
                    // 参数页的键是 layout（0 = 单行，1 = 左右双列）
                    val value = cfg("layout", -1)
                    if (value >= 0) {
                        return if (value == 1) StatusBarMeterState.LAYOUT_DUAL
                        else StatusBarMeterState.LAYOUT_SINGLE
                    }
                    // 旧键兼容：single_row=1 等价于单行
                    return if (cfg("single_row", OFF) == ON) {
                        StatusBarMeterState.LAYOUT_SINGLE
                    } else {
                        StatusBarMeterState.LAYOUT_DUAL
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

            /** 一个槽位一段；读不到数据的槽位直接跳过 */
            override fun render(reader: MeterReader): List<String> {
                // 仪表是右列：左空隙 = 与网速之间的间距，右空隙 = 与状态栏图标之间的间距
                StatusBarMeterState.meterGapLeftDp = cfg("gap_left", 0)
                StatusBarMeterState.meterGapRightDp = cfg("gap_right", 0)
                // 「仅充电时显示」：没插电就整段留空，把原生内容顶掉
                if (cfg("in_charge", OFF) == ON && !reader.isCharging()) return listOf("")
                val parts = ArrayList<String>(4)
                val trace = StringBuilder()
                for (slot in slots) {
                    if (slot == SLOT_OFF) {
                        trace.append("关 ")
                        continue
                    }
                    val text = when (slot) {
                        SLOT_POWER -> reader.powerWatts()
                            ?.let { withUnit(MeterReader.fmt(it, decimals), "w", UNIT_POWER) }

                        SLOT_CURRENT -> reader.currentMilliAmps()
                            ?.let { withUnit(MeterReader.fmt(it, decimals), "mA", UNIT_POWER) }

                        SLOT_CPU_TEMP -> reader.cpuTempCelsius()
                            ?.let { withUnit(MeterReader.fmt(it, tempDigits), "℃", UNIT_TEMP) }

                        SLOT_BATTERY_TEMP -> reader.batteryTempCelsius()
                            ?.let { withUnit(MeterReader.fmt(it, tempDigits), "℃", UNIT_TEMP) }

                        else -> null
                    }
                    trace.append(slotName(slot)).append(if (text == null) "=空" else "=$text").append(' ')
                    if (text == null) continue
                    parts.add(text)
                }
                if (renderDiagLeft > 0) {
                    renderDiagLeft--
                    log("槽位诊断: 配置[${slots.joinToString(",")}] → $trace")
                }
                return if (cfg("reverse_order", OFF) == ON) parts.asReversed() else parts
            }
        }

    // ==================== 配置 ====================

    /** 槽位诊断剩余次数（只打前几次，避免刷屏） */
    private var renderDiagLeft = 10

    private fun slotName(slot: Int): String = SLOT_NAMES.getOrElse(slot) { "?" }

    private fun cfg(key: String, def: Int): Int = PrefsBridge.getInt(HookPrefs.configKey(id, key), def)

    private val slots: IntArray
        get() = intArrayOf(
            cfg("slot1", SLOT_POWER),
            cfg("slot2", SLOT_BATTERY_TEMP),
            cfg("slot3", SLOT_OFF),
            cfg("slot4", SLOT_OFF),
        )

    private val decimals: Int get() = cfg("decimals", 1).coerceIn(0, 2)

    /** 温度是否保留小数（0 位 / 1 位） */
    private val tempDigits: Int get() = if (cfg("temp_decimal", OFF) == ON) 1 else 0

    /**
     * 隐藏单位：0 不隐藏 / 1 全部隐藏 / 2 仅隐藏温度单位 / 3 仅隐藏功耗与电流单位
     */
    private fun withUnit(value: String, unit: String, kind: Int): String {
        val hidden = when (cfg("hide_unit", HIDE_NONE)) {
            HIDE_ALL -> true
            HIDE_TEMP -> kind == UNIT_TEMP
            HIDE_POWER -> kind == UNIT_POWER
            else -> false
        }
        return if (hidden) value else value + unit
    }

    private companion object {

        /** 功耗仪表排在网速前面 */
        const val ORDER = 0

        const val SLOT_OFF = 0
        const val SLOT_POWER = 1
        const val SLOT_CURRENT = 2
        const val SLOT_CPU_TEMP = 3
        const val SLOT_BATTERY_TEMP = 4

        /** 诊断用槽位名，下标与 SLOT_* 对齐 */
        val SLOT_NAMES = arrayOf("关", "功耗", "电流", "CPU温度", "电池温度")

        /** [withUnit] 的单位类别 */
        const val UNIT_POWER = 1
        const val UNIT_TEMP = 2

        const val HIDE_NONE = 0
        const val HIDE_ALL = 1
        const val HIDE_TEMP = 2
        const val HIDE_POWER = 3

        const val OFF = 0
        const val ON = 1
    }
}
