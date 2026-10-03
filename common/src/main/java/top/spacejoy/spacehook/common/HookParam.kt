package top.spacejoy.spacehook.common

/**
 * Hook 功能参数的可视化声明。
 *
 * 由 [HookMeta.params] 持有，UI 层据此渲染配置控件，
 * 读写统一走 `cfg::<hookId>::<key>` 的 RemotePreferences。
 *
 * 页面形态参考 HyperCeiler 的功能设置页（PreferenceScreen）：
 * 总开关独占一张卡 → 按 [group] 分组，每组一张卡 →
 * 组内每项是「开关行 / 下拉行 / 滑条行」，[dependsOn] 命中时整行置灰不可点。
 *
 * ⚠️ 本类会被 UI 进程读取，不允许引用任何 Hook 实现。
 */
sealed interface HookParam {

    /** 参数键名（最终偏好键为 `cfg::<hookId>::<key>`） */
    val key: String

    /** 展示标题 */
    val title: String

    /** 补充说明（显示在标题下方，可为空） */
    val summary: String

    /** 默认值 */
    val default: Int

    /** 所属分组标题；空串表示「不分组」，在页面里独占一张卡片 */
    val group: String

    /**
     * 依赖的参数键：该键当前值非 0 时本项才可用；空串表示无依赖。
     *
     * 对应 HyperCeiler 的 `android:dependency="<key>"`。
     */
    val dependsOn: String

    /**
     * 开关：渲染为右侧 `Switch`。
     *
     * 与其它参数一样以 `Int` 存储（0 = 关 / 1 = 开），
     * 以便 Hook 侧统一用 `PrefsBridge.getInt` 读取。
     */
    data class Toggle(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val default: Int = 0,
        override val group: String = "",
        override val dependsOn: String = "",
    ) : HookParam

    /** 单选：渲染为「当前值 + 箭头」，点按弹出候选对话框 */
    data class Select(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val default: Int,
        val options: List<Option>,
        override val group: String = "",
        override val dependsOn: String = "",
    ) : HookParam {
        data class Option(val value: Int, val label: String)
    }

    /** 数值范围：渲染为下方滑条，标题行右侧显示「当前值 + [unit]」 */
    data class IntRange(
        override val key: String,
        override val title: String,
        override val summary: String = "",
        override val default: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        /** 显示后缀，如 `" dp"` / `" ms"` / `" KB/s"`；空串表示纯数字 */
        val unit: String = "",
        override val group: String = "",
        override val dependsOn: String = "",
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
        override val group: String = "",
        override val dependsOn: String = "",
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
        override val group: String = "",
        override val dependsOn: String = "",
    ) : HookParam

    /**
     * 整屏参数页标记：带此参数的 Hook 点按后进入**独立整屏配置页**
     * （[top.spacejoy.spacehook.ui.screens.HookParamsScreen]），
     * 而不是列表里的内联参数对话框。
     *
     * 适用于参数较多、需要更大展示空间的 Hook（如状态栏仪表的槽位配置）。
     * 自身不存储任何值，仅作路由标记 —— 与 [CardOrder] 同理。
     */
    data class FullPage(
        override val key: String = "page",
        override val title: String = "参数",
        override val summary: String = "在独立页面里调整全部参数",
        override val default: Int = 0,
        override val group: String = "",
        override val dependsOn: String = "",
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
