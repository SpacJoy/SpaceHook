package top.spacejoy.spacehook.libhook.rules.douyin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import io.github.libxposed.api.XposedInterface.ExceptionMode
import top.spacejoy.spacehook.libhook.base.BaseHook
import java.lang.ref.WeakReference

/**
 * 「抖音不裁剪」：Feed 流视频/图集按原始宽高比完整显示（fit 到全屏容器），
 * 不再裁剪填充 9:16。
 *
 * 移植自 `douyinnocrop-v1.1.0.apk`（io.github.dunxuan.douyinnocrop.NoCropModule，
 * libxposed API 102），hook 点已用抖音 40.6.0（versionCode 400601）dexdump 复核：
 *
 * | Hook 点 | 目标 | 验证结论 |
 * |---|---|---|
 * | `VideoItemParams#getAweme()` | classes29.dex | 未混淆，字段 `mAweme`/`mBaseFeedPlayerView` 均在 |
 * | `KeepSurfaceTextureView#onAttachedToWindow()` | classes55.dex | 未混淆 |
 * | `FeedImageViewHolder#bind(Aweme[,int])`（含 Full/Detail 子类） | classes29/19.dex | bind 在父类，子类继承 |
 * | `CleanModeViewModel#sA(CleanModeCommand,String,boolean)` | classes29.dex | 混淆名 sA 未变；按签名查找抗改名 |
 * | `VideoPatchLayout#onVideoSizeChanged(...II)` | classes47.dex | 未混淆 |
 *
 * 与原实现的差异（修正 2 个 bug，详见 NoCropFitApplier）：
 * 1. onVideoSizeChanged 的宽高比改为浮点除法（原为 int 除法）；
 * 2. 不再做宽高对换（原交换代码写错顺序，两个都变成 height）。
 *
 * 所有 hook 均为「proceed 后做副作用、返回原值」，ExceptionMode.PROTECTIVE
 * 保证本模块异常不拖垮抖音。
 */
class DouYinNoCropHook : BaseHook("douyin_nocrop") {

    companion object {
        private const val CLS_VIDEO_ITEM_PARAMS = "com.ss.android.ugc.aweme.feed.model.VideoItemParams"
        private const val CLS_AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
        private const val CLS_KEEP_SURFACE = "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView"
        private const val CLS_FEED_IMAGE_VH = "com.ss.android.ugc.aweme.feed.adapter.FeedImageViewHolder"
        private const val CLS_FULL_FEED_IMAGE_VH = "com.ss.android.ugc.aweme.feed.adapter.FullFeedImageViewHolder"
        private const val CLS_DETAIL_FEED_IMAGE_VH = "com.ss.android.ugc.aweme.detail.ui.DetailFeedImageViewHolder"
        private const val CLS_CLEAN_MODE_VM = "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.cleanmode.CleanModeViewModel"
        private const val CLS_CLEAN_MODE_COMMAND = "com.ss.android.ugc.aweme.feed.cleanmode.CleanModeCommand"
        private const val CLS_VIDEO_PATCH_LAYOUT = "com.ss.android.videoshop.mediaview.VideoPatchLayout"
        private const val CLS_VIDEO_STATE_INQUIRER = "com.ss.android.videoshop.api.VideoStateInquirer"
        private const val CLS_PLAY_ENTITY = "com.ss.android.videoshop.entity.PlayEntity"
        private const val CLS_REMOTE_IMAGE_VIEW = "com.ss.android.ugc.aweme.base.ui.RemoteImageView"

        private val IMAGE_VH_CLASSES = listOf(
            CLS_FEED_IMAGE_VH, CLS_FULL_FEED_IMAGE_VH, CLS_DETAIL_FEED_IMAGE_VH,
        )
    }

    override fun onHook() {
        hookGetAweme()
        hookKeepSurfaceAttach()
        hookImagePostBind()
        hookCleanMode()
        hookPlayerSizeChanged()
    }

    /** 1) 视频绑定：读宽高比，适配播放视图 */
    private fun hookGetAweme() {
        val clazz = findClass(CLS_VIDEO_ITEM_PARAMS) ?: return
        val m = findMethod(clazz, "getAweme")
        if (m == null) {
            logw("未找到 getAweme()，跳过视频适配")
            return
        }
        hookExecutable(m, exceptionMode = ExceptionMode.PROTECTIVE) { chain ->
            val r = chain.proceed()
            try {
                val params = chain.thisObject
                val aweme = r
                val player = NoCropReflect.field(params, "mBaseFeedPlayerView")
                val videoView = NoCropReflect.callNoArg(player, "getVideoView") as? View
                if (videoView != null) {
                    NoCropFitApplier.lastActivityName = activityNameOf(videoView.context)
                    val aspect = NoCropVideoAspect.read(aweme)
                    if (aspect > 0.05f) {
                        NoCropFitApplier.apply(
                            videoView,
                            NoCropFitApplier.FitState(
                                aspect = aspect,
                                imagePost = NoCropVideoAspect.isImagePost(aweme),
                                draweeFit = false,
                                aid = NoCropVideoAspect.aid(aweme),
                                params = WeakReference(params),
                                player = WeakReference(player),
                            ),
                        )
                    } else {
                        NoCropFitApplier.release(videoView)
                    }
                }
            } catch (t: Throwable) {
                loge("getAweme 适配失败", t)
            }
            r
        }
    }

    /** 2) 播放器 Surface 挂载：补挂 pending 或重放已有适配 */
    private fun hookKeepSurfaceAttach() {
        val clazz = findClass(CLS_KEEP_SURFACE) ?: return
        val m = findMethod(clazz, "onAttachedToWindow")
        if (m == null) {
            logw("未找到 onAttachedToWindow()，跳过 attach 补挂")
            return
        }
        hookExecutable(m, exceptionMode = ExceptionMode.PROTECTIVE) { chain ->
            val r = chain.proceed()
            try {
                (chain.thisObject as? View)?.let { NoCropFitApplier.applyOnAttach(it) }
            } catch (t: Throwable) {
                loge("onAttachedToWindow 补挂失败", t)
            }
            r
        }
    }

    /** 3) 图集绑定：封面走 Drawee FIT_CENTER 路径 */
    private fun hookImagePostBind() {
        val awemeCls = findClass(CLS_AWEME)
        val remoteImageCls = findClass(CLS_REMOTE_IMAGE_VIEW) ?: return
        val paramsCls = findClass(CLS_VIDEO_ITEM_PARAMS) ?: return
        for (clsName in IMAGE_VH_CLASSES) {
            val clazz = findClass(clsName) ?: continue
            for (m in findMethods(clazz, "bind")) {
                // 只挂 bind(Aweme) 与 bind(Aweme, int)
                val p = m.parameterTypes
                if (p.isEmpty() || p[0] != awemeCls) continue
                if (p.size > 2) continue
                hookExecutable(m, exceptionMode = ExceptionMode.PROTECTIVE) { chain ->
                    val r = chain.proceed()
                    try {
                        val aweme = chain.args.getOrNull(0)
                        if (NoCropVideoAspect.isImagePost(aweme)) {
                            val cover = NoCropReflect.fieldOfType(chain.thisObject, remoteImageCls) as? View
                            val params = NoCropReflect.fieldOfType(chain.thisObject, paramsCls)
                            if (cover != null) {
                                NoCropFitApplier.apply(
                                    cover,
                                    NoCropFitApplier.FitState(
                                        aspect = NoCropVideoAspect.read(aweme),
                                        imagePost = true,
                                        draweeFit = true,
                                        aid = NoCropVideoAspect.aid(aweme),
                                        params = WeakReference(params),
                                        player = null,
                                    ),
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        loge("图集 bind 适配失败", t)
                    }
                    r
                }
            }
        }
    }

    /** 4) 净屏模式状态变化（sA 为混淆名，按签名查找抗改名） */
    private fun hookCleanMode() {
        val clazz = findClass(CLS_CLEAN_MODE_VM) ?: return
        val commandCls = findClass(CLS_CLEAN_MODE_COMMAND) ?: return
        val m = NoCropReflect.findMethodBySignature(
            clazz,
            arrayOf(commandCls, String::class.java, java.lang.Boolean.TYPE),
        ) ?: findMethod(clazz, "sA", arrayOf(commandCls, String::class.java, java.lang.Boolean.TYPE))
        if (m == null) {
            logw("未找到 CleanModeViewModel#sA，跳过净屏联动")
            return
        }
        hookExecutable(m, exceptionMode = ExceptionMode.PROTECTIVE) { chain ->
            val r = chain.proceed()
            try {
                val vm = chain.thisObject
                val a = NoCropReflect.field(vm, "a")
                val active = a is Collection<*> && a.isNotEmpty()
                NoCropFitApplier.noteCleanVm(vm, active)
                NoCropFitApplier.refitAll()
            } catch (t: Throwable) {
                loge("净屏模式联动失败", t)
            }
            r
        }
    }

    /** 5) 播放器回报真实视频尺寸：用真实宽高比重算布局 */
    private fun hookPlayerSizeChanged() {
        val clazz = findClass(CLS_VIDEO_PATCH_LAYOUT) ?: return
        val inquirerCls = findClass(CLS_VIDEO_STATE_INQUIRER) ?: return
        val playEntityCls = findClass(CLS_PLAY_ENTITY) ?: return
        val m = findMethod(
            clazz,
            "onVideoSizeChanged",
            arrayOf(inquirerCls, playEntityCls, Integer.TYPE, Integer.TYPE),
        )
        if (m == null) {
            logw("未找到 onVideoSizeChanged()，跳过真实尺寸修正")
            return
        }
        hookExecutable(m, exceptionMode = ExceptionMode.PROTECTIVE) { chain ->
            val r = chain.proceed()
            try {
                val w = (chain.args.getOrNull(2) as? Number)?.toInt() ?: 0
                val h = (chain.args.getOrNull(3) as? Number)?.toInt() ?: 0
                NoCropFitApplier.onPlayerLayoutSize(chain.thisObject as? View, w, h)
            } catch (t: Throwable) {
                loge("onVideoSizeChanged 处理失败", t)
            }
            r
        }
    }

    /** 从 context 解包出 Activity 名（喂给 NoCropFitApplier 的跳过条件判定） */
    private fun activityNameOf(context: Context?): String? {
        var c = context
        var depth = 0
        while (c != null && depth < 8) {
            if (c is Activity) return c.javaClass.name
            c = (c as? ContextWrapper)?.baseContext
            depth++
        }
        return null
    }
}
