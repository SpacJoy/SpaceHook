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
        // 系统框架（system_server）：特殊目标，包名固定为 Constants.TARGET_SYSTEM（"system"）。
        // 与普通 App 不同，它只能通过「重启设备」重新加载 Hook，UI 侧据此隐藏单独重启按钮。
        TargetApp(
            packageName = Constants.TARGET_SYSTEM,
            displayName = "系统框架",
            category = AppCategory.SYSTEM,
            note = "system_server；改动需重启设备生效",
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
    )

    // ==================== Hook 功能 ====================

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
            id = "adb_stay_awake",
            name = "ADB 调试常亮",
            description = "**已连接调试工具时**（adb connect 在线 / USB 线启用 adb）屏幕永不因超时熄屏，" +
                "方便长时间调试；断开调试工具后自动恢复原有屏幕超时设置。电源键锁屏不受影响。" +
                "作用于系统框架（system_server），需重启设备生效。",
            targetPackages = listOf(Constants.TARGET_SYSTEM),
            category = HookCategory.SYSTEM_FRAMEWORK,
            defaultEnabled = true,
            requiresReboot = true,
            hookPoints = listOf(
                "com.android.server.power.PowerManagerService#getScreenOffTimeoutLocked(所有重载)",
                "com.android.server.power.PowerManagerService#updateUserActivitySummaryLocked（去优化，规避内联）",
                "com.android.server.power.PowerManagerService#updatePowerStateLocked（去优化，规避内联）",
                "android.provider.Settings\$Global#ADB_ENABLED（ContentObserver 监听，前置条件）",
                "android.provider.Settings\$Global#ADB_CONNECTED（ContentObserver 监听，adbd 连接状态）",
                "android.hardware.usb.action.USB_STATE（BroadcastReceiver，USB adb 兜底信号）",
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
