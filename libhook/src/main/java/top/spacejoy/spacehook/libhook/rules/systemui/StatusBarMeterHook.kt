package top.spacejoy.spacehook.libhook.rules.systemui

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.base.BaseHook
import top.spacejoy.spacehook.libhook.rules.systemui.meter.MeterReader
import top.spacejoy.spacehook.libhook.utils.PrefsBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 状态栏功耗 / 温度 / 网速仪表。
 *
 * ## 原理
 * 接管状态栏原生「实时网速」视图（[CLS_VIEW]）作为显示载体：原生网速本来就占着状态栏
 * 右侧一个位置，直接把它的文本换成仪表内容，不需要自己插布局、不需要 ByteBuddy 造图标槽。
 *
 * ## 两条链路都要拦
 * 1. **文本链路**：`NetworkSpeedController#updateText(String[])` 与
 *    `NetworkSpeedView#setNetworkSpeed(String,String)` —— 原生每次刷新都会走这里，
 *    把参数换成仪表文本即可，原生内容永远不落地。
 * 2. **可见性链路**：原生「显示实时网速」开关关闭时 `mShowNetworkSpeed == false`，
 *    图标根本不会被创建（IconManager 拿不到 visible=true），也就没有视图可改。
 *    因此拦截 D8 合成的静态桥 `-$$Nest$mupdateVisibility`，在它计算 visible 之前
 *    把 `mShowNetworkSpeed` / `mNetworkConnected` 钉成 true。
 *
 * ## 自己的刷新节奏
 * 原生只在网速变化时推送，功耗/温度会「冻住」，所以另起一个主线程 Handler 定时直接
 * 往视图写文本（刷新间隔可配）。
 */
class StatusBarMeterHook : BaseHook("status_bar_meter") {

    // ==================== 运行期状态 ====================

    @Volatile
    private var viewRef: WeakReference<View>? = null

    @Volatile
    private var reader: MeterReader? = null

    private var handler: Handler? = null

    private val tick = object : Runnable {
        override fun run() {
            val h = handler ?: return
            val view = viewRef?.get()
            if (view == null) {
                // 状态栏被重建，等新视图回调 onFinishInflate 再启动
                handler = null
                return
            }
            try {
                if (anySlotOn) {
                    callMethod(view, "setVisibilityByController", true)
                    callMethod(view, "setBlocked", false)
                    composeText()?.let { callMethod(view, "setNetworkSpeed", it.first, it.second) }
                }
            } catch (t: Throwable) {
                loge("仪表刷新异常", t)
            }
            h.postDelayed(this, refreshMs.toLong())
        }
    }

    // ==================== 入口 ====================

    override fun onHook() {
        val controller = requireClass(CLS_CONTROLLER)
        val view = requireClass(CLS_VIEW)
        if (controller == null && view == null) {
            loge("未找到 NetworkSpeedController / NetworkSpeedView，跳过")
            return
        }
        controller?.let { hookController(it) }
        view?.let { hookView(it) }
        logd("仪表已挂载：槽位=${slots.joinToString("/")} 布局=$layoutMode 刷新=${refreshMs}ms")
    }

    /** 文本链路 + 可见性链路 */
    private fun hookController(clazz: Class<*>) {
        replace(clazz, "updateText", arrayOf<Class<*>>(Array<String>::class.java)) { chain ->
            val text = composeText()
            if (text == null) chain.proceed()
            else chain.proceed(arrayOf<Any?>(text.first, text.second))
        }

        val bridge = findVisibilityBridge(clazz)
        if (bridge == null) {
            logw("未找到 \$Nest\$mupdateVisibility：系统「显示实时网速」关闭时仪表不会出现")
            return
        }
        hookExecutable(bridge) { chain ->
            if (anySlotOn) {
                val controller = chain.args.getOrNull(0)
                setBooleanField(controller, FIELD_SHOW_NETWORK_SPEED, true)
                setBooleanField(controller, FIELD_NETWORK_CONNECTED, true)
            }
            chain.proceed()
        }
    }

    /**
     * 定位 D8 合成的静态访问桥。
     * 正常名字是 `-$$Nest$mupdateVisibility(Controller, String)`；不同工具链/版本命名可能变，
     * 因此加一条「静态 + 两参 + 首参是控制器 + 名字含 updateVisibility」的兜底匹配。
     */
    private fun findVisibilityBridge(clazz: Class<*>): Method? {
        findMethod(clazz, NEST_UPDATE_VISIBILITY, arrayOf(clazz, String::class.java))?.let { return it }
        return clazz.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == clazz &&
                it.name.contains("updateVisibility")
        }
    }

    /** 视图链路：缓存视图、套用字体样式、拦下所有写文本的入口 */
    private fun hookView(clazz: Class<*>) {
        replace(clazz, "onFinishInflate") { chain ->
            val result = chain.proceed()
            try {
                (chain.thisObject as? View)?.let { onViewReady(it) }
            } catch (t: Throwable) {
                loge("视图初始化处理失败", t)
            }
            result
        }

        replace(clazz, "setNetworkSpeed", arrayOf(String::class.java, String::class.java)) { chain ->
            val text = composeText()
            if (text == null) chain.proceed()
            else chain.proceed(arrayOf<Any?>(text.first, text.second))
        }

        for (name in STYLE_REFRESH_METHODS) {
            replace(clazz, name) { chain ->
                val result = chain.proceed()
                applyStyle(chain.thisObject)
                result
            }
        }
    }

    private fun onViewReady(view: View) {
        viewRef = WeakReference(view)
        if (reader == null) reader = MeterReader(view.context)
        applyStyle(view)
        startTicker()
    }

    private fun startTicker() {
        if (handler != null) return
        val h = Handler(Looper.getMainLooper())
        handler = h
        h.post(tick)
    }

    /** 字号 / 粗细；系统更新文字外观（字体缩放、主题切换）后重新套一遍 */
    private fun applyStyle(target: Any?) {
        val view = target as? View ?: return
        try {
            val number = getField(view, FIELD_NUMBER_TEXT) as? TextView ?: return
            val unit = getField(view, FIELD_UNIT_TEXT) as? TextView ?: return
            val sp = fontSizeSp
            if (sp > 0) {
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
                unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            }
            if (bold) {
                number.paint.isFakeBoldText = true
                unit.paint.isFakeBoldText = true
            }
        } catch (t: Throwable) {
            logw("应用字体样式失败：${t.message}")
        }
    }

    // ==================== 文本组装 ====================

    /**
     * 组装当前仪表文本。
     * - 单行：所有槽位用空格连成一行，放进「数字」文本框，单位框留空
     * - 双行：第一个槽位占第一行，其余槽位连成第二行
     * - 全部槽位关闭、或数据源暂时读不到 → 返回 null，交还原生行为
     */
    private fun composeText(): Pair<String, String>? {
        if (!anySlotOn) return null
        val source = reader ?: return null
        val parts = ArrayList<String>(4)
        for (slot in slots) {
            if (slot == SLOT_OFF) continue
            parts.add(slotText(source, slot) ?: continue)
        }
        if (parts.isEmpty()) return null
        return if (layoutMode == LAYOUT_DUAL && parts.size > 1) {
            parts[0] to parts.drop(1).joinToString(" ")
        } else {
            parts.joinToString(" ") to ""
        }
    }

    private fun slotText(source: MeterReader, slot: Int): String? = when (slot) {
        SLOT_POWER -> source.powerWatts()?.let { withUnit(MeterReader.fmt(it, decimals), "w") }
        SLOT_CURRENT -> source.currentMilliAmps()?.let { withUnit(MeterReader.fmt(it, decimals), "mA") }
        SLOT_CPU_TEMP -> source.cpuTempCelsius()?.let { withUnit(MeterReader.fmt(it, 0), "℃") }
        SLOT_BATTERY_TEMP -> source.batteryTempCelsius()?.let { withUnit(MeterReader.fmt(it, 1), "℃") }
        SLOT_SPEED -> {
            val kb = source.netSpeedBytesPerSec() / 1024.0
            if (kb >= 1024.0) withUnit(MeterReader.fmt(kb / 1024.0, decimals), "MB/s")
            else withUnit(MeterReader.fmt(kb, decimals), "KB/s")
        }
        else -> null
    }

    private fun withUnit(value: String, unit: String): String = if (hideUnit) value else value + unit

    // ==================== 配置 ====================

    private fun cfg(key: String, def: Int): Int = PrefsBridge.getInt(HookPrefs.configKey(id, key), def)

    private val slots: IntArray
        get() = intArrayOf(
            cfg("slot1", SLOT_POWER),
            cfg("slot2", SLOT_BATTERY_TEMP),
            cfg("slot3", SLOT_OFF),
            cfg("slot4", SLOT_OFF),
        )

    private val layoutMode: Int get() = cfg("layout", LAYOUT_DUAL)
    private val decimals: Int get() = cfg("decimals", 1).coerceIn(0, 2)
    private val hideUnit: Boolean get() = cfg("hide_unit", OFF) == ON
    private val bold: Boolean get() = cfg("bold", OFF) == ON
    private val fontSizeSp: Int get() = cfg("font_size_sp", 0).coerceIn(0, 24)
    private val refreshMs: Int get() = cfg("refresh_ms", 1000).coerceIn(500, 10000)
    private val anySlotOn: Boolean get() = slots.any { it != SLOT_OFF }

    private companion object {

        const val CLS_CONTROLLER = "com.android.systemui.statusbar.policy.NetworkSpeedController"
        const val CLS_VIEW = "com.android.systemui.statusbar.views.NetworkSpeedView"

        /** D8 合成的静态访问桥，dex 中的真实名字（Java 里非法，故 jadx 会改名） */
        const val NEST_UPDATE_VISIBILITY = "-\$\$Nest\$mupdateVisibility"

        const val FIELD_SHOW_NETWORK_SPEED = "mShowNetworkSpeed"
        const val FIELD_NETWORK_CONNECTED = "mNetworkConnected"
        const val FIELD_NUMBER_TEXT = "mNetworkSpeedNumberText"
        const val FIELD_UNIT_TEXT = "mNetworkSpeedUnitText"

        val STYLE_REFRESH_METHODS = arrayOf("onDensityOrFontScaleChanged", "onMiuiThemeChanged")

        const val SLOT_OFF = 0
        const val SLOT_POWER = 1
        const val SLOT_CURRENT = 2
        const val SLOT_CPU_TEMP = 3
        const val SLOT_BATTERY_TEMP = 4
        const val SLOT_SPEED = 5

        const val LAYOUT_SINGLE = 0
        const val LAYOUT_DUAL = 1

        const val OFF = 0
        const val ON = 1
    }
}
