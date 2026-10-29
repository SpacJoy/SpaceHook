package top.spacejoy.spacehook.common

/**
 * Hook 能力清单 —— 模块唯一的「功能注册表」。
 *
 * ## 如何新增一个 Hook
 * 1. 用 jadx 分析目标 APK，定位 hook 点，记录到 `docs/HOOKS.md`；
 * 2. 在 [targetApps] 中登记目标 App（若已存在则跳过）；
 * 3. 在 [hooks] 中加入一条 [HookMeta]；
 * 4. 在 `:libhook` 的 `HookRegistry` 中把 `HookMeta.id` 映射到 `BaseLoad` 实现类；
 * 5. 实现该 `BaseLoad` 子类（详见 `:libhook` 内 `base/BaseLoad.kt` 注释）。
 *
 * UI 完全由本文件驱动：Hook 列表页展示 [targetApps]，每个 App 展开后展示 [hooksFor] 的结果。
 * 因此新增功能**不需要改动任何 UI 代码**。
 */
object HookCatalog {

    // ==================== 目标应用 ====================

    val targetApps: List<TargetApp> = listOf(
        TargetApp(
            packageName = "com.android.systemui",
            displayName = "系统界面",
            category = AppCategory.SYSTEM,
            note = "锁屏 / 状态栏 / 控制中心；也承载「系统界面组件」的插件代码",
        ),
        TargetApp(
            packageName = "com.miui.aod",
            displayName = "息屏显示",
            category = AppCategory.SYSTEM,
            note = "锁屏时钟编辑预览",
        ),
        // 录音机虽然位于系统分区，但属于「功能型应用」而非系统框架，
        // 因此按用户应用归类（二级菜单）。
        TargetApp(
            packageName = "com.android.soundrecorder",
            displayName = "录音机",
            category = AppCategory.THIRD_PARTY,
            note = "录音列表 / 播放界面",
        ),
        // 时钟属于系统应用，但响铃界面是独立 Activity，改动只需重启时钟进程。
        TargetApp(
            packageName = "com.android.deskclock",
            displayName = "时钟",
            category = AppCategory.SYSTEM,
            note = "闹钟响铃界面的上滑 / 延迟提醒操作",
        ),
        // 123云盘：安装请求回归系统默认安装器的目标应用。
        // 本 Hook 不指定具体安装器 —— 它只解开应用对系统原装安装器的硬绑定，
        // 最终由谁接管取决于系统的「默认安装器」设置。
        TargetApp(
            packageName = "com.mfcloudcalculate.networkdisk",
            displayName = "123云盘",
            category = AppCategory.THIRD_PARTY,
            note = "安装请求回归系统默认安装器",
        ),
        TargetApp(
            packageName = "com.ss.android.ugc.aweme",
            displayName = "抖音",
            category = AppCategory.THIRD_PARTY,
            note = "视频 / 图集按原始宽高比完整显示（不裁剪）",
        ),
        TargetApp(
            packageName = "com.netease.cloudmusic",
            displayName = "网易云音乐",
            category = AppCategory.THIRD_PARTY,
            note = "心动模式加载弹窗",
        ),
        TargetApp(
            packageName = "com.autonavi.minimap",
            displayName = "高德地图",
            category = AppCategory.THIRD_PARTY,
            note = "普通路线规划的默认出行方式",
        ),
    )

    // ==================== Hook 功能 ====================

    /** 状态栏仪表的可选指标（索引与 Hook 侧常量一一对应） */
    private val METER_SLOT_OPTIONS = listOf(
        HookParam.Select.Option(0, "关闭"),
        HookParam.Select.Option(1, "功耗"),
        HookParam.Select.Option(2, "电流"),
        HookParam.Select.Option(3, "CPU 温度"),
        HookParam.Select.Option(4, "电池温度"),
    )

    val hooks: List<HookMeta> = listOf(
        HookMeta(
            id = "lock_colon",
            name = "锁屏大时钟显示冒号",
            description = "强制锁屏「大时钟」样式显示冒号；同时修正设置写入层，重启系统界面后依然生效，并同步覆盖息屏显示的时钟编辑预览。",
            targetPackages = listOf("com.android.systemui", "com.miui.aod"),
            category = HookCategory.SYSTEM_UI,
            defaultEnabled = true,
            hookPoints = listOf(
                "com.miui.clock.allInOne.AllInOneBase#refreshFullTimeStrings",
                "com.miui.clock.allInOne.AllInOneBase#setClockStyleInfo",
                "com.miui.clock.module.ClockStyleInfo#convertInfoFromClockBean",
                "com.miui.clock.module.ClockBean#setColonShow",
                "com.android.keyguard.ota.KeyguardOTAInteractor#generateEditorInfo",
                "android.provider.Settings\$Secure#putString",
            ),
        ),
        HookMeta(
            id = "mini_heads_up",
            name = "悬浮通知迷你化",
            description = "把全部悬浮通知（横幅）从多行大卡片压成单行小卡片：标题与正文合并成一行、" +
                "背景透明，头像与按钮保留。来电通知与自定义 View 通知（音乐播放器等）自动维持原样，" +
                "避免内容被单行模板截断。",
            targetPackages = listOf("com.android.systemui"),
            category = HookCategory.SYSTEM_UI,
            defaultEnabled = true,
            requiresReboot = true,
            hookPoints = listOf(
                "com.android.systemui.statusbar.notification.row.NotificationRowContentBinderInjectorImpl#isTransparent(android.content.Context)",
                "com.android.systemui.statusbar.notification.row.NotificationRowContentBinderInjectorImpl#useOneLine(android.content.Context, android.content.Context, android.app.Notification)",
                "com.miui.systemui.notification.MiuiBaseNotifUtil#isCustomViewNotification(android.app.Notification)",
                "com.miui.systemui.notification.MiuiBaseNotifUtil#isGlobalInCallNotification(android.content.Context, java.lang.String, android.app.Notification)",
            ),
        ),
        HookMeta(
            id = "disable_list_autoplay",
            name = "录音列表点击不自动播放",
            description = "点击录音列表条目时不再自动开始播放，只进入详情界面，避免误触外放。",
            targetPackages = listOf("com.android.soundrecorder"),
            category = HookCategory.SOUND_RECORDER,
            defaultEnabled = true,
            hookPoints = listOf(
                "com.android.soundrecorder.fragment.N#z(android.view.View, int)",
                "com.android.soundrecorder.fragment.ContentFragment#q1(android.os.Bundle)",
            ),
        ),
        HookMeta(
            id = "alarm_swap_snooze_dismiss",
            name = "上滑改为延迟提醒",
            description = "交换闹钟响铃界面的两个操作：上滑不再是「关闭闹钟」，而是延迟提醒；" +
                "点击屏幕中央的提醒按钮则改为关闭闹钟。界面文案同步调整（按钮显示「关闭闹钟」）。" +
                "计时器响铃界面不受影响。",
            targetPackages = listOf("com.android.deskclock"),
            category = HookCategory.CLOCK,
            defaultEnabled = false,
            requiresReboot = true,
            hookPoints = listOf(
                "com.android.deskclock.alarm.alert.AlarmAlertFullScreenActivity#onSnooze()",
                "com.android.deskclock.alarm.alert.AlarmAlertFullScreenActivity#onDismiss(boolean)",
                "com.android.deskclock.alarm.alert.DefaultAlarmAlertScreenController#init()（界面文案）",
            ),
        ),
        HookMeta(
            id = "alarm_swipe_dismiss_distance",
            name = "加长上滑距离",
            description = "上滑不再由「快速一甩」触发：必须滑过设定的屏幕高度比例才关闭，" +
                "没滑够会回弹。拖动时的淡出进度与所需比例保持一致。",
            targetPackages = listOf("com.android.deskclock"),
            category = HookCategory.CLOCK,
            defaultEnabled = false,
            requiresReboot = true,
            hookPoints = listOf(
                "com.android.deskclock.alarm.alert.DefaultAlarmAlertScreenController\$DragCallback#onViewReleased(android.view.View, float, float)",
                "com.android.deskclock.alarm.alert.DefaultAlarmAlertScreenController\$DragCallback#onViewPositionChanged(android.view.View, int, int, int, int)",
            ),
            params = listOf(
                HookParam.IntRange(
                    key = "threshold",
                    title = "关闭所需滑动比例",
                    summary = "滑动距离达到屏幕高度的该比例才关闭（原生 50%，且支持快速一甩）",
                    default = 60,
                    min = 30,
                    max = 90,
                    step = 5,
                ),
            ),
        ),
        HookMeta(
            id = "installer_redirect",
            name = "安装请求回归系统默认安装器",
            description = "解开应用对「系统原装安装器」的硬绑定，让安装请求按系统「当前默认安装器」解析。" +
                "最终由谁接管取决于你的系统设置，本功能不指定具体安装器。同时覆盖 Intent 路径与 PackageInstaller Session 路径。",
            targetPackages = listOf("com.mfcloudcalculate.networkdisk"),
            category = HookCategory.INSTALLER,
            defaultEnabled = true,
            requiresReboot = true,
            hookPoints = listOf(
                "android.app.Instrumentation#execStartActivity(所有重载)",
                "android.app.PendingIntent#getActivity",
                "android.content.pm.PackageInstaller#createSession",
                "android.content.pm.PackageInstaller\$Session#commit",
            ),
        ),
        HookMeta(
            id = "douyin_nocrop",
            name = "抖音不裁剪",
            description = "Feed 流视频 / 图集按原始宽高比完整显示（等比贴合全屏容器），不再裁剪填充 9:16。" +
                "适配抖音 40.6.0；评论区、详情面板、非全屏卡片自动跳过。",
            targetPackages = listOf("com.ss.android.ugc.aweme"),
            category = HookCategory.DOUYIN,
            defaultEnabled = true,
            experimental = true,
            requiresReboot = true,
            hookPoints = listOf(
                "com.ss.android.ugc.aweme.feed.model.VideoItemParams#getAweme()",
                "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView#onAttachedToWindow()",
                "com.ss.android.ugc.aweme.feed.adapter.FeedImageViewHolder#bind(Aweme[,int])（含 Full/Detail 子类）",
                "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.cleanmode.CleanModeViewModel#sA(CleanModeCommand,String,boolean)（混淆名，按签名查找）",
                "com.ss.android.videoshop.mediaview.VideoPatchLayout#onVideoSizeChanged(...,int,int)",
            ),
        ),
        HookMeta(
            id = "cloudmusic_no_smartplay_loading",
            name = "去掉心动模式加载弹窗",
            description = "开启心动模式、或在歌曲列表里切歌触发心动模式时，不再弹出屏幕中央的加载遮罩" +
                "（红心图标 +「正在打开心动模式…」）。心动模式本身仍照常拉取推荐并播放。",
            targetPackages = listOf("com.netease.cloudmusic"),
            category = HookCategory.OTHER,
            defaultEnabled = true,
            requiresReboot = true,
            hookPoints = listOf(
                "com.netease.cloudmusic.activity.PlayerActivity（manifest 锚点，R8 不可改名）" +
                    " → 父类链上声明 showDialog(boolean,String) 的祖先（9.6.13 = activity.ma，9.6.08 = activity.ha）",
            ),
        ),
        HookMeta(
            id = "device_center_size",
            name = "融合设备中心卡片尺寸",
            description = "控制中心「融合设备中心」卡片：行数模式、高度、卡片宽度（窄卡时自动切换方形磁贴样式）、单行设备条目上限。",
            // ⚠️ 关键：目标进程必须是 com.android.systemui，**不是** miui.systemui.plugin。
            //
            // 「系统界面组件」(miui.systemui.plugin) 是 SystemUI 动态加载的**插件 APK**，
            // 它不作为独立进程运行；devicecenter 那些类由 SystemUI 自己创建的 ClassLoader 加载。
            // 因此只能注入 SystemUI 进程；插件 ClassLoader 通过 hook
            // PluginInstance$PluginFactory#createPluginContext 捕获（HyperStar OS3 同款路径）。
            targetPackages = listOf("com.android.systemui"),
            category = HookCategory.CONTROL_CENTER,
            defaultEnabled = false,
            hookPoints = listOf(
                "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory#createPluginContext（插件 ClassLoader 捕获入口）",
                "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController#getMode",
                "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder#changeMode",
                "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController#handleUpdateList",
                "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder\$Companion#getDeviceCenterEntryHeight",
                "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController#getSpanSize",
                "…devices.DetailViewHolder / DeviceItemViewHolder#onConfigurationChanged（窄卡方形磁贴）",
                "android.view.LayoutInflater#inflate（磁贴布局后处理）",
            ),
            params = listOf(
                HookParam.Select(
                    key = "mode",
                    title = "行数模式",
                    summary = "强制卡片按单行 / 双行展示，或交由系统自动判断",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "自动"),
                        HookParam.Select.Option(1, "单行"),
                        HookParam.Select.Option(2, "双行"),
                    ),
                ),
                HookParam.IntRange(
                    key = "height_dp",
                    title = "卡片高度",
                    summary = "0 = 跟随系统；大于 0 时强制固定高度（dp）",
                    default = 0,
                    min = 0,
                    max = 300,
                    step = 5,
                ),
                HookParam.IntRange(
                    key = "span_size",
                    title = "卡片宽度",
                    summary = "控制中心网格列数：4 = 整行（默认）；小于 4 时卡片变窄，设备条目自动切换为方形磁贴样式",
                    default = 4,
                    min = 1,
                    max = 4,
                ),
                HookParam.IntRange(
                    key = "max_single",
                    title = "设备条目上限",
                    summary = "限制卡片内显示的设备条目数量；0 = 跟随系统。窄卡模式下默认不超过 列数×2−1",
                    default = 0,
                    min = 0,
                    max = 7,
                ),
            ),
        ),
        HookMeta(
            id = "cc_card_order",
            name = "控制中心卡片排序",
            description = "在模块内仿控制中心的长按拖拽排序页调整各卡片位置（磁贴卡片 / 媒体 / 亮度 / 音量 / 融合设备中心 / 设备控制 / 列表 / 编辑按钮），通过改写各控制器的优先级生效。",
            // 参考 HyperStar QSControlCenterList：hook 各 MainPanelContent 控制器的 getPriority。
            // 原生优先级：卡片20 / 媒体30 / 亮度31 / 音量32 / 融合设备中心50 / 设备控制51 / 列表100 / 编辑200。
            // 数值越小越靠上；全部恢复默认后与系统完全一致。
            targetPackages = listOf("com.android.systemui"),
            category = HookCategory.CONTROL_CENTER,
            defaultEnabled = false,
            hookPoints = listOf(
                "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory#createPluginContext（插件 ClassLoader 捕获入口）",
                "…main.qs.QSCardsController#getPriority（原生 20）",
                "…main.media.MediaPlayerController#getPriority（原生 30）",
                "…main.brightness.BrightnessSliderController#getPriority（原生 31）",
                "…main.volume.VolumeSliderController#getPriority（原生 32）",
                "…devicecenter.entry.DeviceCenterEntryController#getPriority（原生 50）",
                "…devicecontrol.DeviceControlsEntryController#getPriority（原生 51）",
                "…main.qs.QSListController#getPriority（原生 100）",
                "…main.qs.EditButtonController#getPriority（原生 200）",
            ),
            params = listOf(
                HookParam.CardOrder(),
            ),
        ),
        HookMeta(
            id = "cc_card_tiles",
            name = "磁贴卡片自定义内容",
            description = "自定义控制中心顶部大磁贴卡片的内容（WiFi / 移动数据 / 蓝牙 / 手电筒 / VoWiFi），替换系统原生的磁贴组合。",
            // 参考 HyperStar QSCardTileList：hook QSController#getCardStyleTileSpecs()，
            // 返回用户配置的 tile spec 列表（系统原生只支持 wifi/cell 或 wifi/bt 两组）。
            targetPackages = listOf("com.android.systemui"),
            category = HookCategory.CONTROL_CENTER,
            defaultEnabled = false,
            hookPoints = listOf(
                "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory#createPluginContext（插件 ClassLoader 捕获入口）",
                "miui.systemui.controlcenter.qs.QSController#getCardStyleTileSpecs（替换磁贴内容）",
            ),
            params = listOf(
                HookParam.MultiSelect(
                    key = "tiles",
                    title = "大磁贴内容",
                    summary = "按顺序展示所选磁贴（建议 2 或 4 个）；留空 = 跟随系统",
                    options = CARD_TILE_OPTIONS,
                ),
            ),
        ),
        HookMeta(
            id = "status_bar_meter",
            name = "状态栏功耗/温度仪表",
            description = "在状态栏显示所选指标：电池功耗（W）、电流（mA）、CPU 温度、电池温度。" +
                "最多 4 项，可单行或双行排布，字号与粗细可调。与「实时网速」各占独立位置，" +
                "被超级岛遮挡时互不影响。",
            targetPackages = listOf("com.android.systemui"),
            category = HookCategory.STATUS_BAR,
            defaultEnabled = false,
            requiresReboot = true,
            hookPoints = listOf(
                "com.android.systemui.statusbar.policy.NetworkSpeedController#updateText(String[])（文本接管）",
                "com.android.systemui.statusbar.policy.NetworkSpeedController#-\$\$Nest\$mupdateVisibility(...,String)（强制可见）",
                "com.android.systemui.statusbar.views.NetworkSpeedView#setNetworkSpeed(String,String)",
                "com.android.systemui.statusbar.views.NetworkSpeedView#onFinishInflate()（缓存视图）",
                "com.android.systemui.statusbar.views.NetworkSpeedView#onDensityOrFontScaleChanged() / onMiuiThemeChanged(boolean)（样式复套）",
            ),
            params = listOf(
                // 参数较多（4 槽位 + 5 项显示设置），走独立整屏配置页
                HookParam.FullPage(),
                HookParam.Select(
                    key = "slot1",
                    title = "第 1 项",
                    summary = "仪表第 1 个指标（单行时排最左）",
                    default = 1,
                    options = METER_SLOT_OPTIONS,
                ),
                HookParam.Select(
                    key = "slot2",
                    title = "第 2 项",
                    summary = "仪表第 2 个指标；不需要就选「关闭」",
                    default = 4,
                    options = METER_SLOT_OPTIONS,
                ),
                HookParam.Select(
                    key = "slot3",
                    title = "第 3 项",
                    summary = "仪表第 3 个指标；不需要就选「关闭」",
                    default = 0,
                    options = METER_SLOT_OPTIONS,
                ),
                HookParam.Select(
                    key = "slot4",
                    title = "第 4 项",
                    summary = "仪表第 4 个指标；不需要就选「关闭」",
                    default = 0,
                    options = METER_SLOT_OPTIONS,
                ),
                HookParam.Select(
                    key = "layout",
                    title = "排布方式",
                    summary = "单行 = 全部指标挤成一行；双行 = 分上下两行显示（各指标均分到两行）",
                    default = 1,
                    options = listOf(
                        HookParam.Select.Option(0, "单行"),
                        HookParam.Select.Option(1, "双行"),
                    ),
                ),
                HookParam.IntRange(
                    key = "decimals",
                    title = "小数位数",
                    summary = "功耗 / 电流保留的小数位（温度固定：CPU 取整、电池一位）",
                    default = 1,
                    min = 0,
                    max = 2,
                ),
                HookParam.Select(
                    key = "hide_unit",
                    title = "单位",
                    summary = "是否隐藏 w / mA / ℃ 后缀；可按「只隐藏温度」或「只隐藏功耗与电流」细分",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "显示"),
                        HookParam.Select.Option(1, "全部隐藏"),
                        HookParam.Select.Option(2, "仅隐藏温度"),
                        HookParam.Select.Option(3, "仅隐藏功耗与电流"),
                    ),
                ),
                HookParam.Select(
                    key = "temp_decimal",
                    title = "温度小数位",
                    summary = "温度固定取整或保留一位小数（功耗 / 电流的小数位由上面的「小数位数」管）",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "取整"),
                        HookParam.Select.Option(1, "一位小数"),
                    ),
                ),
                HookParam.Select(
                    key = "reverse_order",
                    title = "槽位顺序",
                    summary = "反转 4 个槽位的排列顺序",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "正序"),
                        HookParam.Select.Option(1, "反转"),
                    ),
                ),
                HookParam.Select(
                    key = "in_charge",
                    title = "显示时机",
                    summary = "「仅充电时」会在没插电时整段留空，把原生网速一起顶掉",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "始终显示"),
                        HookParam.Select.Option(1, "仅充电时"),
                    ),
                ),
                HookParam.Select(
                    key = "font_style",
                    title = "字体样式",
                    summary = "细体走 sans-serif-light；加粗在原生字体上加粗",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "跟随系统"),
                        HookParam.Select.Option(1, "细体"),
                        HookParam.Select.Option(2, "加粗"),
                    ),
                ),
                HookParam.IntRange(
                    key = "font_size_sp",
                    title = "字号",
                    summary = "0 = 跟随系统；大于 0 时强制字号（sp）",
                    default = 0,
                    min = 0,
                    max = 24,
                ),
                HookParam.Select(
                    key = "align",
                    title = "水平对齐",
                    summary = "文本在框内的对齐方式；「保持原生」不碰 gravity",
                    default = 1,
                    options = listOf(
                        HookParam.Select.Option(1, "保持原生"),
                        HookParam.Select.Option(2, "靠左"),
                        HookParam.Select.Option(3, "居中"),
                        HookParam.Select.Option(4, "靠右"),
                    ),
                ),
                HookParam.IntRange(
                    key = "fixed_width",
                    title = "固定宽度",
                    summary = "给整块设最小宽度（dp）；0 = 不干预，让内容自己撑开",
                    default = 0,
                    min = 0,
                    max = 120,
                ),
                HookParam.IntRange(
                    key = "left_margin",
                    title = "左外边距",
                    summary = "整块左侧与相邻图标之间的外边距（dp）；0 = 保持原生。" +
                        "与下面的「左空隙」不同：那个是框内留白，这个是框外间距",
                    default = 0,
                    min = 0,
                    max = 16,
                ),
                HookParam.IntRange(
                    key = "right_margin",
                    title = "右外边距",
                    summary = "整块右侧与相邻图标之间的外边距（dp）；0 = 保持原生",
                    default = 0,
                    min = 0,
                    max = 16,
                ),
                HookParam.IntRange(
                    key = "vertical_offset",
                    title = "垂直偏移",
                    summary = "整块上下微调（dp）：正值下移、负值上移；0 = 保持原生",
                    default = 0,
                    min = -20,
                    max = 20,
                ),
                HookParam.IntRange(
                    key = "gap_left",
                    title = "左空隙",
                    summary = "仪表左边缘与左侧相邻内容（通常是网速）之间的间距（dp）",
                    default = 6,
                    min = 0,
                    max = 30,
                ),
                HookParam.IntRange(
                    key = "gap_right",
                    title = "右空隙",
                    summary = "仪表右边缘与右侧相邻图标（通常是电池）之间的间距（dp）",
                    default = 6,
                    min = 0,
                    max = 30,
                ),
                HookParam.IntRange(
                    key = "refresh_ms",
                    title = "刷新间隔",
                    summary = "仪表自行刷新的周期（毫秒）",
                    default = 1000,
                    min = 500,
                    max = 5000,
                    step = 500,
                ),
            ),
        ),
        HookMeta(
            id = "status_bar_speed",
            name = "状态栏实时网速",
            description = "在状态栏显示实时收发速率（KB/s、MB/s 自动切换），格式可自定义。" +
                "与「功耗/温度仪表」各占独立位置，被超级岛遮挡时互不影响。",
            targetPackages = listOf("com.android.systemui"),
            category = HookCategory.STATUS_BAR,
            defaultEnabled = false,
            requiresReboot = true,
            hookPoints = listOf(
                "com.android.systemui.statusbar.policy.NetworkSpeedController#updateText(String[])（文本接管）",
                "com.android.systemui.statusbar.policy.NetworkSpeedController#-\$\$Nest\$mupdateVisibility(...,String)（强制可见）",
                "com.android.systemui.statusbar.views.NetworkSpeedView#setNetworkSpeed(String,String)",
                "com.android.systemui.statusbar.views.NetworkSpeedView#onFinishInflate()（缓存视图）",
            ),
            params = listOf(
                // 与功耗/温度仪表共用一套独立整屏配置页
                HookParam.FullPage(),
                HookParam.Select(
                    key = "style",
                    title = "显示样式",
                    summary = "决定这段文本显示什么内容：值+单位 / 值、单位分开 / " +
                        "上下行并排 / 上下行分开。实际显示一行还是两行，由下面的「排布方式」决定",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "值和单位合并"),
                        HookParam.Select.Option(1, "值和单位分开"),
                        HookParam.Select.Option(2, "上下行合并"),
                        HookParam.Select.Option(3, "上下行分开"),
                    ),
                ),
                HookParam.Select(
                    key = "swap_places",
                    title = "上下行顺序",
                    summary = "默认上传在上、下载在下",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "上传在上"),
                        HookParam.Select.Option(1, "下载在上"),
                    ),
                ),
                HookParam.Select(
                    key = "layout",
                    title = "排布方式",
                    summary = "单行 = 全部内容排在同一行；双行 = 分上下两行显示。" +
                        "这里的「双行」决定行数，与「显示样式」无关",
                    default = -1,
                    options = listOf(
                        HookParam.Select.Option(-1, "跟随样式"),
                        HookParam.Select.Option(0, "单行"),
                        HookParam.Select.Option(1, "双行"),
                    ),
                ),
                HookParam.IntRange(
                    key = "decimals",
                    title = "小数位数",
                    summary = "速率保留的小数位",
                    default = 1,
                    min = 0,
                    max = 2,
                ),
                HookParam.Select(
                    key = "hide_unit",
                    title = "单位",
                    summary = "是否隐藏 KB/s、MB/s 后缀（整段单位一起隐藏）",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "显示"),
                        HookParam.Select.Option(1, "隐藏"),
                    ),
                ),
                HookParam.Select(
                    key = "hide_arrow",
                    title = "上下行符号",
                    summary = "是否隐藏 ↑ / ↓ 箭头",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "显示"),
                        HookParam.Select.Option(1, "隐藏"),
                    ),
                ),
                HookParam.Select(
                    key = "hide_per_s",
                    title = "速率后缀 /s",
                    summary = "隐藏 /s，只留 KB、MB；与上面的「单位」互不影响",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "显示"),
                        HookParam.Select.Option(1, "隐藏"),
                    ),
                ),
                HookParam.Select(
                    key = "hide",
                    title = "低速隐藏",
                    summary = "开启后，速率低于阈值时整段留空",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "不隐藏"),
                        HookParam.Select.Option(1, "低于阈值时隐藏"),
                    ),
                ),
                HookParam.IntRange(
                    key = "hide_slow",
                    title = "隐藏阈值",
                    summary = "收发合计低于这个值就隐藏（KB/s）；需先打开上面的「低速隐藏」",
                    default = 64,
                    min = 1,
                    max = 2048,
                ),
                HookParam.Select(
                    key = "hide_all",
                    title = "全部隐藏",
                    summary = "配合「低速隐藏」使用：不看阈值，一律留空（相当于临时关掉显示）",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "正常显示"),
                        HookParam.Select.Option(1, "一律留空"),
                    ),
                ),
                HookParam.Select(
                    key = "font_style",
                    title = "字体样式",
                    summary = "细体走 sans-serif-light；加粗在原生字体上加粗",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "跟随系统"),
                        HookParam.Select.Option(1, "细体"),
                        HookParam.Select.Option(2, "加粗"),
                    ),
                ),
                HookParam.IntRange(
                    key = "font_size_sp",
                    title = "字号",
                    summary = "0 = 跟随系统；大于 0 时强制字号（sp）",
                    default = 0,
                    min = 0,
                    max = 24,
                ),
                HookParam.Select(
                    key = "align",
                    title = "水平对齐",
                    summary = "文本在框内的对齐方式；「保持原生」不碰 gravity",
                    default = 1,
                    options = listOf(
                        HookParam.Select.Option(1, "保持原生"),
                        HookParam.Select.Option(2, "靠左"),
                        HookParam.Select.Option(3, "居中"),
                        HookParam.Select.Option(4, "靠右"),
                    ),
                ),
                HookParam.IntRange(
                    key = "fixed_width",
                    title = "固定宽度",
                    summary = "给整块设最小宽度（dp）；0 = 不干预，让内容自己撑开",
                    default = 0,
                    min = 0,
                    max = 120,
                ),
                HookParam.IntRange(
                    key = "left_margin",
                    title = "左外边距",
                    summary = "整块左侧与相邻图标之间的外边距（dp）；0 = 保持原生。" +
                        "与下面的「左空隙」不同：那个是框内留白，这个是框外间距",
                    default = 0,
                    min = 0,
                    max = 16,
                ),
                HookParam.IntRange(
                    key = "right_margin",
                    title = "右外边距",
                    summary = "整块右侧与相邻图标之间的外边距（dp）；0 = 保持原生",
                    default = 0,
                    min = 0,
                    max = 16,
                ),
                HookParam.IntRange(
                    key = "vertical_offset",
                    title = "垂直偏移",
                    summary = "整块上下微调（dp）：正值下移、负值上移；0 = 保持原生",
                    default = 0,
                    min = -20,
                    max = 20,
                ),
                HookParam.IntRange(
                    key = "gap_left",
                    title = "左空隙",
                    summary = "网速左边缘与通知图标之间的额外间距（dp）",
                    default = 0,
                    min = 0,
                    max = 30,
                ),
                HookParam.IntRange(
                    key = "gap_right",
                    title = "右空隙",
                    summary = "网速右边缘与右侧相邻图标（通常是电池）之间的间距（dp）",
                    default = 0,
                    min = 0,
                    max = 30,
                ),
                HookParam.IntRange(
                    key = "refresh_ms",
                    title = "刷新间隔",
                    summary = "网速自行刷新的周期（毫秒）",
                    default = 1000,
                    min = 500,
                    max = 5000,
                    step = 500,
                ),
            ),
        ),
        HookMeta(
            id = "amap_default_route",
            name = "默认出行方式",
            description = "从首页「路线」或普通搜索的「路线」进入时，默认选中你指定的出行方式" +
                "（公共交通 / 步行 / 骑行 / 驾车）。路线页内仍可随时手动切换；" +
                "专门的驾车等入口、以及调用方已明确指定方式的链接保持原样。",
            targetPackages = listOf("com.autonavi.minimap"),
            category = HookCategory.AMAP,
            defaultEnabled = false,
            requiresReboot = true,
            hookPoints = listOf(
                "defpackage.h15#a(defpackage.g15, com.amap.bundle.planhome.api.IPredictResultCallback)（17.01.0.2012，混淆名，按结构特征定位）",
                "调用链：PlanHomePresenter#onPageCreated / #onNewIntent → h15.a",
                "defpackage.p91#a(defpackage.s91, …)（15.10.x 的历史位置，同一结构）",
            ),
            params = listOf(
                HookParam.Select(
                    key = "mode",
                    title = "默认出行方式",
                    summary = "普通路线规划进入时默认选中的方式；「跟随高德」= 不干预，沿用高德自己的上次选择",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "跟随高德"),
                        HookParam.Select.Option(1, "公共交通"),
                        HookParam.Select.Option(2, "步行"),
                        HookParam.Select.Option(3, "骑行"),
                        HookParam.Select.Option(4, "驾车"),
                    ),
                ),
                HookParam.Select(
                    key = "scope",
                    title = "生效范围",
                    summary = "「仅未指定时」保留专门的驾车入口与带方式的第三方链接；" +
                        "若首页推荐卡片的导航按钮仍走驾车，改成「全部入口」",
                    default = 0,
                    options = listOf(
                        HookParam.Select.Option(0, "仅未指定时"),
                        HookParam.Select.Option(1, "全部入口"),
                    ),
                ),
            ),
        ),
    )

    // ==================== 查询辅助 ====================

    private val hookById: Map<String, HookMeta> by lazy { hooks.associateBy { it.id } }

    fun hookOf(id: String): HookMeta? = hookById[id]

    /** 该 App 上挂载的 Hook 列表 */
    fun hooksFor(packageName: String): List<HookMeta> =
        hooks.filter { meta -> meta.targetPackages.any { it == packageName || it == Constants.TARGET_WILDCARD_ALL } }

    /** 系统应用（一级菜单直接展示） */
    val systemApps: List<TargetApp> by lazy {
        targetApps.filter { it.category == AppCategory.SYSTEM }
    }

    /**
     * 用户应用（二级菜单展示）。
     *
     * 只包含**已配置 Hook** 的用户应用 —— 不列出设备上全部已安装应用，
     * 避免二级菜单变成冗长的应用清单。设备上是否安装由 UI 侧标注。
     */
    val userApps: List<TargetApp> by lazy {
        targetApps.filter { it.category == AppCategory.THIRD_PARTY && hooksFor(it.packageName).isNotEmpty() }
    }

    /** 是否已有任何用户应用配置了 Hook */
    val hasUserAppHooks: Boolean get() = userApps.any { hooksFor(it.packageName).isNotEmpty() }

    /** 所有被 Hook 的包名（去重，用于 scope 提示） */
    val hookedPackages: List<String> by lazy {
        (hooks.flatMap { it.targetPackages } + targetApps.map { it.packageName })
            .filter { it != Constants.TARGET_WILDCARD_ALL }
            .distinct()
    }

    /** [hookedPackages] 中，哪些当前设备已安装 */
    fun installedPackages(installed: Collection<String>): List<String> =
        hookedPackages.filter { it in installed || it == Constants.TARGET_SYSTEM }
}
