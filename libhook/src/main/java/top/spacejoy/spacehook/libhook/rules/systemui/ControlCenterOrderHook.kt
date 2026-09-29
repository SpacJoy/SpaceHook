package top.spacejoy.spacehook.libhook.rules.systemui

import top.spacejoy.spacehook.common.CC_ORDER_CARDS
import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.base.MiuiPluginHook
import top.spacejoy.spacehook.libhook.utils.PrefsBridge

/**
 * 控制中心卡片排序（位置调整）。
 *
 * 参考 HyperStar `QSControlCenterList`：hook 各 `MainPanelContent` 控制器的
 * `getPriority()`，返回模块配置的优先级（数值越小越靠上）。
 *
 * UI 侧在模块内提供仿控制中心的长按拖拽排序页，按拖拽结果写入连续优先级；
 * 某张卡未配置（或为 -1）时回退原生值，行为与系统完全一致。
 *
 * 排序的消费方：`MainPanelContentDistributor` 在构造时按 `getPriority()`
 * 升序排列 childControllers —— 因此**修改配置后需重启系统界面**才会重排。
 */
class ControlCenterOrderHook : MiuiPluginHook("cc_card_order") {

    private fun priorityOf(cardKey: String, native: Int): Int =
        PrefsBridge.getInt(HookPrefs.configKey(id, cardKey), -1).let { if (it >= 0) it else native }

    override fun onPluginLoaded(loader: ClassLoader) {
        var hooked = 0
        for (card in CC_ORDER_CARDS) {
            val cls = try {
                Class.forName(card.className, false, loader)
            } catch (_: Throwable) {
                null
            }
            if (cls == null) {
                logw("控制器不存在，跳过：${card.className}")
                continue
            }

            replace(cls, "getPriority") { chain ->
                priorityOf(card.key, card.nativePriority)
            }
            hooked++
        }
        logd("已挂 $hooked/${CC_ORDER_CARDS.size} 个控制器的 getPriority")
        if (hooked == 0) logw("一个控制器都没挂上，卡片排序不会生效")
    }
}
