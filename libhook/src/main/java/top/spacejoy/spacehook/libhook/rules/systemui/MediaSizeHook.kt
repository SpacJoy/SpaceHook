package top.spacejoy.spacehook.libhook.rules.systemui

import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.base.MiuiPluginHook
import top.spacejoy.spacehook.libhook.utils.PrefsBridge

/**
 * 媒体播放器卡片大小调整。
 *
 * hook `miui.systemui.controlcenter.panel.main.media.MediaPlayerController#getSpanSize()`，
 * 返回模块配置的列数（1..4）；0 = 跟随系统（默认：半宽 2 列，折叠屏宽屏整行 4 列）。
 *
 * GridLayoutManager 会把 span 收敛到 1..4，所以这里允许 1..4。
 */
class MediaSizeHook : MiuiPluginHook("cc_media_size") {

    private companion object {
        const val CLS_MEDIA_CONTROLLER = "miui.systemui.controlcenter.panel.main.media.MediaPlayerController"
        const val CFG_SPAN_SIZE = "span_size"
    }

    override fun onPluginLoaded(loader: ClassLoader) {
        val mediaController = try {
            Class.forName(CLS_MEDIA_CONTROLLER, false, loader)
        } catch (_: Throwable) {
            null
        }
        if (mediaController == null) {
            logw("MediaPlayerController 不存在，媒体播放器大小调整不可用")
            return
        }

        replace(mediaController, "getSpanSize") { chain ->
            val span = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_SPAN_SIZE), 0)
            if (span in 1..4) span else chain.proceed()
        }
        logd("已挂 MediaPlayerController#getSpanSize")
    }
}
