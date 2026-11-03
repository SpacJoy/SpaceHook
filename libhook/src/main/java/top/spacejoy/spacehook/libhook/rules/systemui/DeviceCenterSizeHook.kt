package top.spacejoy.spacehook.libhook.rules.systemui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.base.MiuiPluginHook
import top.spacejoy.spacehook.libhook.utils.PrefsBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * 控制中心「融合设备中心」卡片尺寸定制。
 *
 * 目标 APK：`/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk`
 *          （即「系统界面组件」`miui.systemui.plugin`，动态加载插件）
 *
 * ## 实现来源
 * - 行数模式 / 高度 / 设备条目上限：旧 BeanShell 方案（[D:/Code/Android/Hook/DeviceCenterSize/main.java]）
 *   —— hook `getMode` / `changeMode` / `handleUpdateList` / `getDeviceCenterEntryHeight`
 * - 卡片宽度（span）与窄卡方形磁贴样式：参考 HyperStar 2.2.0 `os2/DeviceCenterRow`
 *   —— hook `DeviceCenterEntryController#getSpanSize`，span < 4 时后处理
 *   `DetailViewHolder` / `DeviceItemViewHolder` 的 `onConfigurationChanged` 与
 *   `LayoutInflater#inflate`（`device_center_device_item` / `device_center_empty_item`）
 *
 * ## 与 HyperStar 的差异（当前插件 18.3.2.22.0 的适配）
 * - HyperStar hook 的 `miui.systemui.devicecenter.a` 构造器在该版本已变成
 *   lambda 合成的 Runnable，**不可用**；其「限制设备条目数」的职责由本实现的
 *   `handleUpdateList` 截断 `pendingUpdateList` 等价承接。
 *
 * ## 配置（每次调用实时读取，改参数后重开控制中心即可见效）
 * - `mode`       : 0=自动 1=强制单行 2=强制双行
 * - `height_dp`  : >0 时强制卡片高度（dp）
 * - `span_size`  : 1..4，4=整行（默认）；<4 时窄卡 + 方形磁贴样式
 * - `max_single` : >0 时限制设备条目数；窄卡模式下默认不超过 span×2−1
 */
class DeviceCenterSizeHook : MiuiPluginHook("device_center_size") {

    private companion object {
        const val PREFIX = "miui.systemui.controlcenter.panel.main.devicecenter"
        const val CLS_VIEW_HOLDER = "$PREFIX.entry.DeviceCenterEntryViewHolder"
        const val CLS_MODE = "$PREFIX.entry.DeviceCenterEntryViewHolder\$Mode"
        const val CLS_CONTROLLER = "$PREFIX.devices.DeviceCenterCardController"
        const val CLS_COMPANION = "$PREFIX.entry.DeviceCenterEntryViewHolder\$Companion"
        const val CLS_ENTRY_CONTROLLER = "$PREFIX.entry.DeviceCenterEntryController"
        const val CLS_DETAIL_VH = "$PREFIX.devices.DetailViewHolder"
        const val CLS_DEVICE_ITEM_VH = "$PREFIX.devices.DeviceItemViewHolder"
        const val CLS_PLUGIN_COMMON_UTILS = "miui.systemui.util.CommonUtils"

        const val PLUGIN_PACKAGE = "miui.systemui.plugin"

        const val LAYOUT_DEVICE_ITEM = "device_center_device_item"
        const val LAYOUT_EMPTY_ITEM = "device_center_empty_item"
        const val DIMEN_ITEM_HEIGHT = "device_center_item_height"

        const val MODE_AUTO = 0
        const val MODE_SINGLE = 1
        const val MODE_DOUBLE = 2

        const val CFG_MODE = "mode"
        const val CFG_HEIGHT_DP = "height_dp"
        const val CFG_MAX_SINGLE = "max_single"
        const val CFG_SPAN_SIZE = "span_size"
    }

    private val cfgMode: Int get() = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_MODE), MODE_AUTO)
    private val cfgHeightDp: Int get() = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_HEIGHT_DP), 0)
    private val cfgMaxSingle: Int get() = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_MAX_SINGLE), 0)
    private val cfgSpanSize: Int get() = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_SPAN_SIZE), 4)

    /** 当前是否处于窄卡模式（span < 4） */
    private val isNarrow: Boolean get() = cfgSpanSize in 1..3

    // 缓存：插件资源里按名字解析出的 layout id（避免每次 inflate 都查 getIdentifier）
    private val layoutIdCache = ConcurrentHashMap<String, Int>()

    // miui.systemui.util.CommonUtils 的 INSTANCE 与 setLayoutSize(View,int,int,boolean)
    private var commonUtilsInstance: Any? = null
    private var commonUtilsReady = false

    override fun onPluginLoaded(loader: ClassLoader) {
        val modeClass = load(CLS_MODE, loader)
        val viewHolder = load(CLS_VIEW_HOLDER, loader)
        val controller = load(CLS_CONTROLLER, loader)
        val companion = load(CLS_COMPANION, loader)
        val entryController = load(CLS_ENTRY_CONTROLLER, loader)
        val detailVh = load(CLS_DETAIL_VH, loader)
        val deviceItemVh = load(CLS_DEVICE_ITEM_VH, loader)
        load(CLS_PLUGIN_COMMON_UTILS, loader)?.also { prepareCommonUtils(it) }

        logd(
            "类解析：Mode=${modeClass != null} VH=${viewHolder != null} CC=${controller != null} " +
                "Companion=${companion != null} EC=${entryController != null} " +
                "Detail=${detailVh != null} Item=${deviceItemVh != null} CommonUtils=${commonUtilsReady}"
        )

        // ---------- 尺寸：旧方案四件套 ----------
        hookCardController(controller, modeClass)
        hookViewHolder(viewHolder, modeClass)
        hookHeight(companion)

        // ---------- 尺寸：卡片宽度（span） ----------
        hookSpanSize(entryController)

        // ---------- 窄卡方形磁贴（span < 4） ----------
        hookSquareItems(detailVh, deviceItemVh)
        hookLayoutInflate()
    }

    private fun load(name: String, loader: ClassLoader): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: Throwable) {
        null
    }

    // ==================== 尺寸：旧方案 ====================

    /** Hook 1：尺寸决策的源头 `DeviceCenterCardController#getMode` */
    private fun hookCardController(controller: Class<*>?, modeClass: Class<*>?) {
        if (controller == null || modeClass == null) {
            logw("Controller / Mode 类缺失，行数模式相关 hook 跳过")
            return
        }
        val single = getEnumConstant(modeClass, "MODE_1_ROW")
        val double = getEnumConstant(modeClass, "MODE_2_ROWS")
        val collapsed = getEnumConstant(modeClass, "MODE_COLLAPSED")

        replace(controller, "getMode") { chain ->
            when (val mode = cfgMode) {
                MODE_SINGLE -> single ?: chain.proceed()
                MODE_DOUBLE -> double ?: chain.proceed()
                else -> {
                    // 自动 + 窄卡：参考 HyperStar —— 单设备折叠；超过列数转双行
                    if (isNarrow && single != null && double != null) {
                        val size = (getField(chain.thisObject, "deviceItems") as? List<*>)?.size ?: return@replace chain.proceed()
                        when {
                            size == 1 && collapsed != null -> collapsed
                            size > cfgSpanSize -> double
                            else -> single
                        }
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
        logd("已挂 getMode（mode/窄卡自动逻辑）")

        // 限制设备条目数量：截断 pendingUpdateList
        before(controller, "handleUpdateList") { chain ->
            val self = chain.thisObject ?: return@before
            val max = effectiveMaxSingle()
            if (max <= 0) return@before
            try {
                var c: Class<*>? = self.javaClass
                while (c != null) {
                    val field = try { c.getDeclaredField("pendingUpdateList") } catch (_: Throwable) { null }
                    if (field != null) {
                        field.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val list = field.get(self) as? MutableList<Any?> ?: return@before
                        if (list.size > max) {
                            val trimmed = ArrayList(list.subList(0, max))
                            field.set(self, trimmed)
                            logd("设备条目 ${list.size} -> ${trimmed.size}")
                        }
                        return@before
                    }
                    c = c.superclass
                }
            } catch (_: Throwable) {
            }
        }
        logd("已挂 handleUpdateList（条目上限）")
    }

    /** Hook 2：高度落地的汇聚点 `DeviceCenterEntryViewHolder#changeMode(Mode, boolean, boolean, boolean)` */
    private fun hookViewHolder(viewHolder: Class<*>?, modeClass: Class<*>?) {
        if (viewHolder == null || modeClass == null) return

        // 精确指定参数类型：这个类里还有 D8 合成的 `changeMode$default(…, int, Object)` 桥，
        // 不指定的话 `findMethod` 可能命中那个，参数下标全错位。
        val paramTypes = arrayOf(modeClass, Boolean::class.java, Boolean::class.java, Boolean::class.java)
        replace(viewHolder, "changeMode", paramTypes) { chain ->
            val mode = cfgMode
            val forced = when (mode) {
                MODE_SINGLE -> getEnumConstant(modeClass, "MODE_1_ROW")
                MODE_DOUBLE -> getEnumConstant(modeClass, "MODE_2_ROWS")
                else -> null
            }
            if (forced == null) {
                chain.proceed()
            } else {
                // **不能写 `chain.args[0] = forced`。**
                // API 102 的 `Chain.getArgs()` 返回的是框架给的**只读列表**
                // （堆栈里是 `Collections$UnmodifiableList.set` → `UnsupportedOperationException`），
                // 直接下标赋值必然抛异常，被 BaseHook 兜住后刷满日志。
                // 正确做法：拷一份数组、改完用 `proceed(newArgs)` 传进去。
                val newArgs = chain.args.toTypedArray()
                newArgs[0] = forced
                chain.proceed(newArgs)
            }
        }
        logd("已挂 changeMode（强制行数）")
    }

    /** Hook 3：直接固定像素高度 `Companion#getDeviceCenterEntryHeight(Context, Mode)` */
    private fun hookHeight(companion: Class<*>?) {
        if (companion == null) return
        replace(companion, "getDeviceCenterEntryHeight") { chain ->
            val dp = cfgHeightDp
            if (dp <= 0) return@replace chain.proceed()
            val ctx = chain.args.getOrNull(0) as? Context
            val density = ctx?.resources?.displayMetrics?.density ?: 1f
            (dp * density + 0.5f).toInt()
        }
        logd("已挂 getDeviceCenterEntryHeight（高度定制）")
    }

    // ==================== 尺寸：卡片宽度 ====================

    /** Hook 4：`DeviceCenterEntryController#getSpanSize` —— 控制中心网格占列数（1..4） */
    private fun hookSpanSize(entryController: Class<*>?) {
        if (entryController == null) {
            logw("DeviceCenterEntryController 缺失，卡片宽度调整不可用")
            return
        }
        replace(entryController, "getSpanSize") { chain ->
            val span = cfgSpanSize
            if (span in 1..3) span else chain.proceed()
        }
        logd("已挂 getSpanSize（宽度定制）")
    }

    // ==================== 窄卡方形磁贴（span < 4） ====================

    /** 生效的条目上限：显式配置优先；窄卡模式默认 span×2−1（HyperStar 同款语义） */
    private fun effectiveMaxSingle(): Int {
        val configured = cfgMaxSingle
        if (configured > 0) return configured
        return if (isNarrow) cfgSpanSize * 2 - 1 else 0
    }

    private fun prepareCommonUtils(commonUtilsClass: Class<*>) {
        try {
            commonUtilsInstance = getStaticObjectField(commonUtilsClass, "INSTANCE")
            commonUtilsReady = commonUtilsInstance != null
        } catch (_: Throwable) {
            commonUtilsReady = false
        }
    }

    /** Hook 5/6：设备磁贴尺寸在配置变化时被系统重置 —— 重新钉成方形 */
    private fun hookSquareItems(detailVh: Class<*>?, deviceItemVh: Class<*>?) {
        for (cls in listOfNotNull(detailVh, deviceItemVh)) {
            hookExecutable(findMethod(cls, "onConfigurationChanged", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!)) ?: continue) { chain ->
                val result = chain.proceed()
                try {
                    if (isNarrow) {
                        val self = chain.thisObject
                        val view = getField(self, "itemView") as? View
                        if (view != null) applySquareSize(view)
                    }
                } catch (t: Throwable) {
                    loge("方形磁贴回调异常：${cls.simpleName}", t)
                }
                result
            }
        }
        if (detailVh != null || deviceItemVh != null) logd("已挂 ViewHolder#onConfigurationChanged（方形磁贴）")
    }

    /** 用插件的 CommonUtils#setLayoutSize 把 itemView 钉成 device_center_item_height 见方 */
    private fun applySquareSize(view: View) {
        try {
            val res = view.resources ?: return
            val size = res.getDimensionPixelSize(res.getIdentifier(DIMEN_ITEM_HEIGHT, "dimen", PLUGIN_PACKAGE))
            if (size <= 0) return
            if (commonUtilsReady && commonUtilsInstance != null) {
                callMethod(commonUtilsInstance, "setLayoutSize", view, size, size, false)
            } else {
                val lp = view.layoutParams
                lp?.let {
                    it.width = size
                    it.height = size
                    view.layoutParams = it
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Hook 7：`LayoutInflater#inflate(int, ViewGroup, boolean)`（框架类，全局）。
     * 只处理插件自己资源里的两个布局：
     * - `device_center_device_item`：宽度 = 高度（方形磁贴）
     * - `device_center_empty_item`：span=1 时隐藏标题、清零图标起始边距（HyperStar 同款）
     */
    private fun hookLayoutInflate() {
        val inflaterClass = try {
            Class.forName("android.view.LayoutInflater")
        } catch (_: Throwable) {
            null
        } ?: return

        hookExecutable(
            findMethod(inflaterClass, "inflate", arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, ViewGroup::class.java, Boolean::class.javaPrimitiveType!!))
                ?: return
        ) { chain ->
            val result = chain.proceed()
            try {
                if (isNarrow && result is View) {
                    val inflater = chain.thisObject as? LayoutInflater
                    val ctx = inflater?.context
                    if (ctx?.packageName == PLUGIN_PACKAGE) {
                        val res = ctx.resources
                        val layoutId = chain.args.getOrNull(0) as? Int ?: -1
                        when (layoutId) {
                            resolveLayout(res, LAYOUT_DEVICE_ITEM) -> squareLayout(result)
                            resolveLayout(res, LAYOUT_EMPTY_ITEM) -> if (cfgSpanSize == 1) compactEmptyLayout(result, res)
                        }
                    }
                }
            } catch (t: Throwable) {
                loge("inflate 后处理异常", t)
            }
            result
        }
        logd("已挂 LayoutInflater#inflate（磁贴布局后处理）")
    }

    /** 按名字解析插件 layout id（带缓存；解析不到返回 0，不会匹配任何真实 id） */
    private fun resolveLayout(res: android.content.res.Resources, name: String): Int {
        return layoutIdCache.getOrPut(name) {
            try {
                res.getIdentifier(name, "layout", PLUGIN_PACKAGE)
            } catch (_: Throwable) {
                0
            }
        }
    }

    private fun squareLayout(view: View) {
        val lp = view.layoutParams ?: return
        lp.width = lp.height
        view.layoutParams = lp
    }

    private fun compactEmptyLayout(view: View, res: android.content.res.Resources) {
        try {
            val iconId = res.getIdentifier("icon", "id", PLUGIN_PACKAGE)
            if (iconId != 0) {
                (view.findViewById<View>(iconId)?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 0
                    view.findViewById<View>(iconId)?.layoutParams = it
                }
            }
            val titleId = res.getIdentifier("title", "id", PLUGIN_PACKAGE)
            if (titleId != 0) {
                view.findViewById<View>(titleId)?.visibility = View.GONE
            }
        } catch (_: Throwable) {
        }
    }
}
