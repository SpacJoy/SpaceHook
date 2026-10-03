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
 * [StatusBarMeterHookBase] 统一安装），本类只负责把 TrafficStats 的收发总量差分
 * 格式化成速率文本：不足 1024 KB/s 显示 KB/s，超过则换算成 MB/s。
 *
 * 单独开启时按自己的字号 / 刷新间隔显示；与仪表同时开启时，排布与样式以仪表为准，
 * 网速那段接在仪表后面。
 */
internal class StatusBarSpeedHook : StatusBarMeterHookBase("status_bar_speed") {

    override fun createContributor(): StatusBarMeterState.Contributor =
        object : StatusBarMeterState.Contributor {

            override val order: Int = ORDER

            override val layout: Int get() = StatusBarMeterState.LAYOUT_SINGLE

            override val bold: Boolean get() = cfg("bold", OFF) == ON

            override val fontSizeSp: Int get() = cfg("font_size_sp", 0).coerceIn(0, 24)

            override val refreshMs: Int get() = cfg("refresh_ms", 1000).coerceIn(500, 10000)

            override fun render(reader: MeterReader): List<String> {
                val kb = reader.netSpeedBytesPerSec() / 1024.0
                val text = if (kb >= 1024.0) {
                    withUnit(MeterReader.fmt(kb / 1024.0, decimals), "MB/s")
                } else {
                    withUnit(MeterReader.fmt(kb, decimals), "KB/s")
                }
                return listOf(text)
            }
        }

    // ==================== 配置 ====================

    private fun cfg(key: String, def: Int): Int = PrefsBridge.getInt(HookPrefs.configKey(id, key), def)

    private val decimals: Int get() = cfg("decimals", 1).coerceIn(0, 2)

    private val hideUnit: Boolean get() = cfg("hide_unit", OFF) == ON

    private fun withUnit(value: String, unit: String): String = if (hideUnit) value else value + unit

    private companion object {

        /** 网速排在功耗仪表后面 */
        const val ORDER = 10

        const val OFF = 0
        const val ON = 1
    }
}
