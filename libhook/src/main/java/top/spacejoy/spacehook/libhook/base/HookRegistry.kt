package top.spacejoy.spacehook.libhook.base

import top.spacejoy.spacehook.libhook.rules.amap.AmapDefaultRouteHook
import top.spacejoy.spacehook.libhook.rules.cloudmusic.CloudMusicNoSmartPlayLoadingHook
import top.spacejoy.spacehook.libhook.rules.clock.AlarmSwapSnoozeDismissHook
import top.spacejoy.spacehook.libhook.rules.clock.AlarmSwipeDismissDistanceHook
import top.spacejoy.spacehook.libhook.rules.douyin.DouYinNoCropHook
import top.spacejoy.spacehook.libhook.rules.generic.InstallerRedirectHook
import top.spacejoy.spacehook.libhook.rules.soundrecorder.DisableListAutoPlayHook
import top.spacejoy.spacehook.libhook.rules.system.AdbStayAwakeHook
import top.spacejoy.spacehook.libhook.rules.systemui.CardStyleTilesHook
import top.spacejoy.spacehook.libhook.rules.systemui.ControlCenterOrderHook
import top.spacejoy.spacehook.libhook.rules.systemui.DeviceCenterSizeHook
import top.spacejoy.spacehook.libhook.rules.systemui.LockColonHook
import top.spacejoy.spacehook.libhook.rules.systemui.MiniHeadsUpHook
import top.spacejoy.spacehook.libhook.rules.systemui.StatusBarMeterHook
import top.spacejoy.spacehook.libhook.rules.systemui.StatusBarSpeedHook

/**
 * Hook 实现注册表 —— id 与实现类的唯一映射。
 *
 * 新增 Hook 时，除了在 [top.spacejoy.spacehook.common.HookCatalog] 登记元数据外，
 * 还需要在这里加一行映射。仅此两处，UI 无需改动。
 *
 * 使用「工厂 lambda」而非 Class 对象，是为了保证类加载时机可控：
 * 只有真正需要分发时才会加载对应的 Hook 实现类。
 */
object HookRegistry {

    private val factories: Map<String, () -> BaseHook> = mapOf(
        // ===== 系统界面 =====
        "lock_colon" to { LockColonHook() },
        "mini_heads_up" to { MiniHeadsUpHook() },
        "device_center_size" to { DeviceCenterSizeHook() },
        "cc_card_order" to { ControlCenterOrderHook() },
        "cc_card_tiles" to { CardStyleTilesHook() },
        "status_bar_meter" to { StatusBarMeterHook() },
        "status_bar_speed" to { StatusBarSpeedHook() },

        // ===== 录音机 =====
        "disable_list_autoplay" to { DisableListAutoPlayHook() },

        // ===== 时钟 =====
        "alarm_swap_snooze_dismiss" to { AlarmSwapSnoozeDismissHook() },
        "alarm_swipe_dismiss_distance" to { AlarmSwipeDismissDistanceHook() },

        // ===== 安装器 =====
        // 注意：目标安装器（InstallerX Revived）只是改道的接收方，
        // 不需要被 Hook，因此不在 scope.list 中。
        "installer_redirect" to { InstallerRedirectHook() },

        // ===== 抖音 =====
        "douyin_nocrop" to { DouYinNoCropHook() },

        // ===== 网易云音乐 =====
        "cloudmusic_no_smartplay_loading" to { CloudMusicNoSmartPlayLoadingHook() },

        // ===== 高德地图 =====
        "amap_default_route" to { AmapDefaultRouteHook() },

        // ===== 系统框架（system_server） =====
        "adb_stay_awake" to { AdbStayAwakeHook() },
    )

    /** 已注册的全部 Hook id */
    val ids: Set<String> get() = factories.keys

    /** 按 id 创建 Hook 实例；未注册返回 null */
    fun create(id: String): BaseHook? = try {
        factories[id]?.invoke()
    } catch (t: Throwable) {
        null
    }
}
