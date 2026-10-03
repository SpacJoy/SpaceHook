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
 * 显示位置的排布 / 字号 / 刷新间隔以本 Hook 为准；同时开启「状态栏实时网速」时，
 * 网速那段会排在仪表后面一起显示。
 */
internal class StatusBarMeterHook : StatusBarMeterHookBase("status_bar_meter") {

    override fun createContributor(): StatusBarMeterState.Contributor =
        object : StatusBarMeterState.Contributor {

            override val order: Int = ORDER

            override val layout: Int get() = cfg("layout", StatusBarMeterState.LAYOUT_DUAL)

            override val bold: Boolean get() = cfg("bold", OFF) == ON

            override val fontSizeSp: Int get() = cfg("font_size_sp", 0).coerceIn(0, 24)

            override val refreshMs: Int get() = cfg("refresh_ms", 1000).coerceIn(500, 10000)

            /** 一个槽位一段；读不到数据的槽位直接跳过 */
            override fun render(reader: MeterReader): List<String> {
                val parts = ArrayList<String>(4)
                for (slot in slots) {
                    if (slot == SLOT_OFF) continue
                    val text = when (slot) {
                        SLOT_POWER ->
                            reader.powerWatts()?.let { withUnit(MeterReader.fmt(it, decimals), "w") }

                        SLOT_CURRENT ->
                            reader.currentMilliAmps()?.let { withUnit(MeterReader.fmt(it, decimals), "mA") }

                        SLOT_CPU_TEMP ->
                            reader.cpuTempCelsius()?.let { withUnit(MeterReader.fmt(it, 0), "℃") }

                        SLOT_BATTERY_TEMP ->
                            reader.batteryTempCelsius()?.let { withUnit(MeterReader.fmt(it, 1), "℃") }

                        else -> null
                    } ?: continue
                    parts.add(text)
                }
                return parts
            }
        }

    // ==================== 配置 ====================

    private fun cfg(key: String, def: Int): Int = PrefsBridge.getInt(HookPrefs.configKey(id, key), def)

    private val slots: IntArray
        get() = intArrayOf(
            cfg("slot1", SLOT_POWER),
            cfg("slot2", SLOT_BATTERY_TEMP),
            cfg("slot3", SLOT_OFF),
            cfg("slot4", SLOT_OFF),
        )

    private val decimals: Int get() = cfg("decimals", 1).coerceIn(0, 2)

    private val hideUnit: Boolean get() = cfg("hide_unit", OFF) == ON

    private fun withUnit(value: String, unit: String): String = if (hideUnit) value else value + unit

    private companion object {

        /** 功耗仪表排在网速前面 */
        const val ORDER = 0

        const val SLOT_OFF = 0
        const val SLOT_POWER = 1
        const val SLOT_CURRENT = 2
        const val SLOT_CPU_TEMP = 3
        const val SLOT_BATTERY_TEMP = 4

        const val OFF = 0
        const val ON = 1
    }
}
