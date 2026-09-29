package top.spacejoy.spacehook.common

/**
 * Hook 功能参数的可视化声明。
 *
 * 由 [HookMeta.params] 持有，UI 层据此渲染配置控件，
 * 读写统一走 `cfg::<hookId>::<key>` 的 RemotePreferences。
 *
 * ⚠️ 本类会被 UI 进程读取，不允许引用任何 Hook 实现。
 */
sealed interface HookParam {

    /** 参数键名（最终偏好键为 `cfg::<hookId>::<key>`） */
    val key: String

    /** 展示标题 */
    val title: String

    /** 补充说明（显示在标题下方） */
    val summary: String

    /** 默认值 */
    val default: Int

    /** 单选：渲染为一排可选芯片 */
    data class Select(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val default: Int,
        val options: List<Option>,
    ) : HookParam {
        data class Option(val value: Int, val label: String)
    }

    /** 数值范围：渲染为 −/+ 步进器 */
    data class IntRange(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val default: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
    ) : HookParam

    /**
     * 多选（字符串值）：渲染为一排可多选的芯片。
     *
     * 取值以 [separator] 分隔的字符串存储在 `cfg::<hookId>::<key>`，
     * 空串 / 未设置表示「跟随系统」。适用于磁贴内容这类字符串列表。
     */
    data class MultiSelect(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val default: Int = 0,
        val options: List<Option>,
        val separator: String = "|",
    ) : HookParam {
        data class Option(val value: String, val label: String)
    }

    /**
     * 卡片拖拽排序：渲染为独立的排序编辑页入口（仿控制中心长按拖动）。
     *
     * 排序数据由 [CC_ORDER_CARDS] 提供，UI 与 Hook 侧共用同一份声明。
     */
    data class CardOrder(
        override val key: String = "order",
        override val title: String = "卡片排序",
        override val summary: String = "长按拖动调整控制中心各卡片的位置",
        override val default: Int = 0,
    ) : HookParam
}

/**
 * 控制中心可排序卡片的声明。
 *
 * `key` 即偏好键（`cfg::cc_card_order::<key>`）；
 * `nativePriority` 为反编译核实过的系统原生优先级（数值越小越靠上）。
 */
data class OrderCard(
    val key: String,
    val name: String,
    val nativePriority: Int,
    /** 对应插件类名（Hook 侧使用，UI 不读） */
    val className: String,
)

/** 控制中心可排序卡片清单（顺序 = 原生默认顺序） */
val CC_ORDER_CARDS: List<OrderCard> = listOf(
    OrderCard("p_qs_cards", "磁贴卡片", 20, "miui.systemui.controlcenter.panel.main.qs.QSCardsController"),
    OrderCard("p_media", "媒体播放器", 30, "miui.systemui.controlcenter.panel.main.media.MediaPlayerController"),
    OrderCard("p_brightness", "亮度条", 31, "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController"),
    OrderCard("p_volume", "音量条", 32, "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"),
    OrderCard("p_device_center", "融合设备中心", 50, "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController"),
    OrderCard("p_device_controls", "设备控制", 51, "miui.systemui.controlcenter.panel.main.devicecontrol.DeviceControlsEntryController"),
    OrderCard("p_list", "磁贴列表", 100, "miui.systemui.controlcenter.panel.main.qs.QSListController"),
    OrderCard("p_edit", "编辑按钮", 200, "miui.systemui.controlcenter.panel.main.qs.EditButtonController"),
)

/**
 * 磁贴卡片（大磁贴）可选内容。
 *
 * 对应 `QSController#getCardStyleTileSpecs()` 返回的 tile spec 名；
 * 系统原生只支持 wifi/cell 或 wifi/bt 两组，本模块可自由组合。
 * 除下列预设外，也支持在 UI 里自定义输入任意 spec 名。
 */
val CARD_TILE_OPTIONS: List<HookParam.MultiSelect.Option> = listOf(
    HookParam.MultiSelect.Option("wifi", "WiFi"),
    HookParam.MultiSelect.Option("cell", "移动数据"),
    HookParam.MultiSelect.Option("bt", "蓝牙"),
    HookParam.MultiSelect.Option("flashlight", "手电筒"),
    HookParam.MultiSelect.Option("airplane", "飞行模式"),
    HookParam.MultiSelect.Option("mute", "静音"),
    HookParam.MultiSelect.Option("rotation", "自动旋转"),
    HookParam.MultiSelect.Option("batterysaver", "省电模式"),
    HookParam.MultiSelect.Option("vowifi1", "VoWiFi·卡1"),
    HookParam.MultiSelect.Option("vowifi2", "VoWiFi·卡2"),
)
