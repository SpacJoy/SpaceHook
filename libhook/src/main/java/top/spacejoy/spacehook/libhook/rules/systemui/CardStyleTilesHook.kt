package top.spacejoy.spacehook.libhook.rules.systemui

import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.base.MiuiPluginHook
import top.spacejoy.spacehook.libhook.utils.PrefsBridge

/**
 * 磁贴卡片（控制中心顶部大磁贴）自定义内容。
 *
 * 参考 HyperStar `QSCardTileList`：hook
 * `miui.systemui.controlcenter.qs.QSController#getCardStyleTileSpecs()`，
 * 返回模块配置的 tile spec 列表（`wifi` / `cell` / `bt` / `flashlight` / `vowifi1` / `vowifi2`）。
 *
 * 系统原生 `getCardStyleTileSpecs()` 只返回 `wifi+cell`（支持移动数据）或 `wifi+bt`
 * 两组；本模块替换后可自由组合。留空 / 未配置时跟随系统。
 *
 * 注意：HyperStar 还额外 hook `QSCardItemView#updateBackground` 给非默认磁贴补上
 * 大卡片背景图 —— 这部分属于视觉美化，依赖插件内 drawable 资源名，暂未实现；
 * 非默认磁贴会使用默认卡片样式渲染，功能不受影响。
 */
class CardStyleTilesHook : MiuiPluginHook("cc_card_tiles") {

    private companion object {
        const val CLS_QS_CONTROLLER = "miui.systemui.controlcenter.qs.QSController"
        const val CFG_TILES = "tiles"
    }

    override fun onPluginLoaded(loader: ClassLoader) {
        val qsController = try {
            Class.forName(CLS_QS_CONTROLLER, false, loader)
        } catch (_: Throwable) {
            null
        }
        if (qsController == null) {
            logw("QSController 不存在，磁贴内容自定义不可用")
            return
        }

        replace(qsController, "getCardStyleTileSpecs") { chain ->
            val raw = PrefsBridge.getString(HookPrefs.configKey(id, CFG_TILES), null)
            if (raw.isNullOrEmpty()) {
                chain.proceed()
            } else {
                raw.split("|").filter { it.isNotEmpty() }
            }
        }
        logd("已挂 QSController#getCardStyleTileSpecs")
    }
}
