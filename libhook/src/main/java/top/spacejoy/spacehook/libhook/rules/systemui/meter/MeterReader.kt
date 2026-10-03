package top.spacejoy.spacehook.libhook.rules.systemui.meter

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * 状态栏仪表的数据源：功耗 / 电流 / CPU 温度 / 电池温度 / 实时网速。
 *
 * 全部走只读接口（sysfs 节点 + 粘性电池广播 + TrafficStats），不依赖 MIUI 私有 API；
 * 节点缺失时返回 null，由上层决定该项是「不显示」还是「显示占位」。
 */
internal class MeterReader(private val context: Context) {

    // ==================== 电池 ====================

    private var batteryIntent: Intent? = null
    private var batteryAt = 0L

    /**
     * 取电池广播。
     * ACTION_BATTERY_CHANGED 是粘性广播，registerReceiver(null, ...) 直接拿到当前值；
     * 每秒最多读一次，避免高频刷新时反复走 Binder。
     */
    private fun battery(): Intent? {
        val now = SystemClock.elapsedRealtime()
        batteryIntent?.let { if (now - batteryAt < 1000L) return it }
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
        batteryIntent = intent
        batteryAt = now
        return intent
    }

    private fun readLong(path: String): Long? = try {
        val file = File(path)
        if (file.exists()) file.readText().trim().toLongOrNull() else null
    } catch (_: Throwable) {
        null
    }

    /** 定位可用的电源节点目录（不同机型/内核命名不同） */
    private fun powerSupplyDir(): String? {
        for (name in POWER_SUPPLY_DIRS) {
            val dir = "$POWER_SUPPLY_ROOT/$name"
            if (File("$dir/current_now").exists()) return dir
        }
        return null
    }

    /** 电流（安培，保留符号）：sysfs current_now（µA） */
    private fun currentAmps(): Double? =
        powerSupplyDir()?.let { readLong("$it/current_now")?.div(1_000_000.0) }

    /** 电压（伏特）：sysfs voltage_now（µV）→ 兜底电池广播 "voltage"（mV） */
    private fun voltageVolts(): Double? {
        powerSupplyDir()?.let { dir ->
            readLong("$dir/voltage_now")?.let { return it / 1_000_000.0 }
        }
        battery()?.getIntExtra("voltage", -1)?.let { if (it > 0) return it / 1000.0 }
        return null
    }

    /** 功耗（瓦特，取绝对值）；电流方向因机型而异，取绝对值才是「耗电多少」 */
    fun powerWatts(): Double? {
        val volts = voltageVolts() ?: return null
        val amps = currentAmps() ?: return null
        val watts = kotlin.math.abs(volts * amps)
        return if (watts.isFinite() && watts < 100.0) watts else null
    }

    /** 电流（毫安，取绝对值） */
    fun currentMilliAmps(): Double? {
        val millis = kotlin.math.abs(currentAmps() ?: return null) * 1000.0
        return if (millis.isFinite() && millis < 100_000.0) millis else null
    }

    /** 电池温度（℃）：电池广播 "temperature" 为十分之一摄氏度 */
    fun batteryTempCelsius(): Double? {
        val tenths = battery()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: return null
        if (tenths == Int.MIN_VALUE || tenths == 0) return null
        return tenths / 10.0
    }

    // ==================== CPU 温度 ====================

    private var thermalGroups: List<List<String>>? = null

    /**
     * 按优先级分好组的温度区 temp 节点：cpu → soc → ap → gpu。
     *
     * 关键在**剔除阈值节点**。设备上的 thermal_zone 里混着大量非传感器节点
     * （`cpu-hw-trip-0`、`*-bcl-lvl0`、`*-ibat-lvl0`、`sdr0`…），它们恒等于
     * 95 / 0 / −273 这类定值。`File.listFiles()` 的返回顺序不保证，一旦按顺序
     * 撞上 `cpu-hw-trip-0`，温度就会永远卡在 95℃ 不动。
     *
     * 只扫一次并缓存分组——节点表在运行期不会变。
     */
    private fun thermalGroups(): List<List<String>> {
        thermalGroups?.let { return it }
        val zones = File(THERMAL_ROOT).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
        val groups = ArrayList<MutableList<String>>(THERMAL_KEYS.size)
        repeat(THERMAL_KEYS.size) { groups.add(ArrayList()) }
        for (zone in zones) {
            val type = try {
                File(zone, "type").takeIf { it.exists() }?.readText()?.trim()?.lowercase(Locale.US)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (THERMAL_BLACKLIST.any { type.contains(it) }) continue
            val index = THERMAL_KEYS.indexOfFirst { type.contains(it) }
            if (index < 0) continue
            val temp = File(zone, "temp")
            if (!temp.exists()) continue
            groups[index].add(temp.absolutePath)
        }
        val result: List<List<String>> = groups
        if (result.any { it.isNotEmpty() }) thermalGroups = result
        return result
    }

    /** 原始值归一化：绝对值 > 1000 视为毫摄氏度；范围外的（阈值节点残值）当无效 */
    private fun normalizeTemp(raw: Long?): Double? {
        if (raw == null) return null
        val celsius = if (raw > 1000L || raw < -1000L) raw / 1000.0 else raw.toDouble()
        return if (celsius >= 1.0 && celsius <= 150.0) celsius else null
    }

    /** CPU 温度（℃）：同一优先级组内取最高值（即最热的核心） */
    fun cpuTempCelsius(): Double? {
        for (group in thermalGroups()) {
            var best: Double? = null
            for (path in group) {
                val celsius = normalizeTemp(readLong(path)) ?: continue
                val current = best
                if (current == null || celsius > current) best = celsius
            }
            if (best != null) return best
        }
        return null
    }

    // ==================== 网速 ====================

    private var lastBytes = -1L
    private var lastAt = 0L

    /** 实时网速（字节/秒），基于 TrafficStats 收发总量的差分 */
    fun netSpeedBytesPerSec(): Double {
        val now = SystemClock.elapsedRealtime()
        val total = try {
            TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        } catch (_: Throwable) {
            -1L
        }
        val prevBytes = lastBytes
        val prevAt = lastAt
        lastBytes = total
        lastAt = now
        if (total < 0 || prevBytes < 0 || prevAt == 0L || now <= prevAt) return 0.0
        val delta = total - prevBytes
        if (delta <= 0) return 0.0
        return delta * 1000.0 / (now - prevAt)
    }

    internal companion object {

        private const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"
        private const val THERMAL_ROOT = "/sys/class/thermal"

        /** current_now 常见挂载点 */
        private val POWER_SUPPLY_DIRS = arrayOf("battery", "bms", "Battery", "BATTERY", "battery2")

        /** 温度区 type 的匹配优先级 */
        private val THERMAL_KEYS = arrayOf("cpu", "soc", "ap", "gpu")

        /**
         * 阈值 / 上限 / 无效节点的特征词。
         * type 命中任意一个的 thermal_zone 不是真实传感器，直接排除。
         */
        private val THERMAL_BLACKLIST = arrayOf(
            "trip", "lvl", "limit", "ibat", "bcl", "sdr", "vsense", "virtual", "dummy",
        )

        /** 固定小数位格式化，强制点号小数点 */
        fun fmt(value: Double, digits: Int): String =
            String.format(Locale.US, "%.${digits.coerceIn(0, 3)}f", value)
    }
}
