package top.spacejoy.spacehook.libhook.rules.systemui.meter

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import top.spacejoy.spacehook.libhook.utils.XLog
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

    /** 是否正在充电（充电中 / 已充满） */
    fun isCharging(): Boolean {
        val status = battery()?.getIntExtra(BatteryManager.EXTRA_STATUS, Int.MIN_VALUE) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ==================== CPU 温度 ====================

    private var thermalGroups: List<List<String>>? = null

    /** 上次扫描节点的时刻，用于扫空后的重扫退避（见 [thermalGroups]） */
    private var thermalScanAt = 0L

    /**
     * 按优先级分好组的温度区 temp 节点：cpu → soc → ap → gpu。
     *
     * 关键在**剔除阈值节点**。设备上的 thermal_zone 里混着大量非传感器节点
     * （`cpu-hw-trip-0`、`*-bcl-lvl0`、`*-ibat-lvl0`、`sdr0`…），它们恒等于
     * 95 / 0 / −273 这类定值。`File.listFiles()` 的返回顺序不保证，一旦按顺序
     * 撞上 `cpu-hw-trip-0`，温度就会永远卡在 95℃ 不动。
     *
     * ## 缓存策略
     * 节点表在运行期不会变，所以正常只扫一次。但**扫空时不能每秒重扫**：
     * 以前 `if (result.any { it.isNotEmpty() }) thermalGroups = result` 只在扫到节点时
     * 才缓存，扫空就每秒重新 `listFiles()` + 读 96 个 `type` 文件——而这个函数是
     * 每秒调一次的主线程路径，等于白白烧 CPU。
     *
     * 现在用 `thermalScanAt` 做退避：扫空后 30 秒内直接返回空分组、不碰文件系统。
     * 30 秒足够覆盖开机早期节点还没就绪的情况，又不会长期空转。
     */
    private fun thermalGroups(): List<List<String>> {
        thermalGroups?.let { return it }

        // 扫空后的退避：30 秒内直接返回，不碰文件系统
        val now = SystemClock.elapsedRealtime()
        if (thermalScanAt != 0L && now - thermalScanAt < THERMAL_RESCAN_MS) return EMPTY_GROUPS
        thermalScanAt = now

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
        if (result.any { it.isNotEmpty() }) {
            thermalGroups = result
            logOnce("thermal-ready", "thermal 节点已就绪：${result.map { it.size }}")
        } else {
            logOnce("thermal-empty", "thermal 节点扫描为空，${THERMAL_RESCAN_MS / 1000}s 后重试")
        }
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

    /** 一次采样：下行 / 上行（字节/秒） */
    internal data class NetSpeed(val rx: Double, val tx: Double)

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    /** 同一帧内的重复调用直接复用上次结果，避免二次采样把间隔压平（网速恒 0） */
    private var cachedSpeed: NetSpeed? = null
    private var cachedAt = 0L

    /**
     * 实时网速（字节/秒），基于 TrafficStats 收发总量的差分。
     *
     * 收发分开差分——「上下行分排」样式需要两个独立的值；[netSpeedBytesPerSec]
     * 只是两者之和，给合并显示的样式用。**同一次刷新里只调一次**：每次调用都会
     * 推进采样基线，连着调第二次会得到 0。
     */
    fun netSpeed(): NetSpeed {
        val now = SystemClock.elapsedRealtime()
        // 一次刷新里 compose() 可能被调多次（原生推送一次 + 定时器一次），
        // 每次都重新差分会把采样间隔压成 0，网速就永远是 0.0KB/s
        cachedSpeed?.let { if (now - cachedAt < SPEED_CACHE_MS) return it }
        val rx = try {
            TrafficStats.getTotalRxBytes()
        } catch (_: Throwable) {
            -1L
        }
        val tx = try {
            TrafficStats.getTotalTxBytes()
        } catch (_: Throwable) {
            -1L
        }
        val prevRx = lastRx
        val prevTx = lastTx
        val prevAt = lastAt
        lastRx = rx
        lastTx = tx
        lastAt = now
        if (rx < 0 || tx < 0 || prevRx < 0 || prevTx < 0 || prevAt == 0L || now <= prevAt) {
            val zero = NetSpeed(0.0, 0.0)
            cachedSpeed = zero
            cachedAt = now
            return zero
        }
        val span = (now - prevAt).toDouble()
        val result = NetSpeed(
            rx = (rx - prevRx).coerceAtLeast(0L) * 1000.0 / span,
            tx = (tx - prevTx).coerceAtLeast(0L) * 1000.0 / span,
        )
        cachedSpeed = result
        cachedAt = now
        return result
    }

    /** 收发合计（字节/秒） */
    fun netSpeedBytesPerSec(): Double {
        val speed = netSpeed()
        return speed.rx + speed.tx
    }

    /** 同一 key 只打一次日志，避免每秒刷屏（转给 XLog.once） */
    private fun logOnce(key: String, message: String) = XLog.once("MeterReader:$key", message)

    internal companion object {

        private const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"
        private const val THERMAL_ROOT = "/sys/class/thermal"

        /** 同一帧内网速结果的复用窗口 */
        private const val SPEED_CACHE_MS = 200L

        /**
         * thermal 节点扫描失败后的重扫间隔。
         *
         * 这个函数每秒被调一次（仪表刷新节奏），扫空时不能跟着每秒重扫
         * 96 个 zone 的 `type` 文件，否则白白烧 CPU。30 秒足够覆盖开机早期
         * 节点还没就绪的情况，又不会长期空转。
         */
        private const val THERMAL_RESCAN_MS = 30_000L

        /** current_now 常见挂载点 */
        private val POWER_SUPPLY_DIRS = arrayOf("battery", "bms", "Battery", "BATTERY", "battery2")

        /** 温度区 type 的匹配优先级 */
        private val THERMAL_KEYS = arrayOf("cpu", "soc", "ap", "gpu")

        /** 扫空时返回的空分组（避免每次分配新对象；须声明在 THERMAL_KEYS 之后） */
        private val EMPTY_GROUPS: List<List<String>> = List(THERMAL_KEYS.size) { emptyList() }

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
