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

    private var thermalPath: String? = null
    private var thermalScanned = false

    /**
     * 扫描 thermal_zone：优先 type 含 "cpu" 的区，其次 "soc" / "ap" / "gpu"。
     * 只扫一次并缓存路径——节点表在运行期不会变。
     */
    private fun resolveThermalPath(): String? {
        if (thermalScanned) return thermalPath
        thermalScanned = true
        val zones = File(THERMAL_ROOT).listFiles() ?: return null
        var fallback: String? = null
        for (key in THERMAL_KEYS) {
            for (zone in zones) {
                val type = try {
                    File(zone, "type").takeIf { it.exists() }?.readText()?.trim()?.lowercase(Locale.US)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (!type.contains(key)) continue
                val temp = File(zone, "temp")
                if (!temp.exists()) continue
                val path = temp.absolutePath
                if (key == THERMAL_KEYS[0]) {
                    thermalPath = path
                    return path
                }
                if (fallback == null) fallback = path
            }
        }
        thermalPath = fallback
        return fallback
    }

    /** CPU 温度（℃）；原始值 > 1000 视为毫摄氏度 */
    fun cpuTempCelsius(): Double? {
        val raw = readLong(resolveThermalPath() ?: return null) ?: return null
        val celsius = if (raw > 1000L || raw < -1000L) raw / 1000.0 else raw.toDouble()
        return if (celsius > -50.0 && celsius < 200.0) celsius else null
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

        /** 固定小数位格式化，强制点号小数点 */
        fun fmt(value: Double, digits: Int): String =
            String.format(Locale.US, "%.${digits.coerceIn(0, 3)}f", value)
    }
}
