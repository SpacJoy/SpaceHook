package top.spacejoy.spacehook.libhook.rules.cloudmusic

import top.spacejoy.spacehook.libhook.base.BaseHook

/**
 * 网易云音乐：去掉「心动模式」启动时的加载弹窗。
 *
 * 目标 APK：`D:/Code/Android/APK/Hook/com.netease.cloudmusic_9.6.08.apk`（9.6.08，真机同版本）
 * 目标进程：`com.netease.cloudmusic`
 *
 * ## 现象
 *
 * 开启心动模式、或在歌曲列表里手动切歌时，屏幕中央弹出一个黑底圆角、
 * 红心动画 +「正在打开心动模式...」的加载弹窗，持续到推荐接口返回（约 2–5 秒），期间下方界面不可点。
 *
 * ## 定位过程（jadx 反编译 + 真机抓栈）
 *
 * 先用 `Dialog#show` 抓调用栈，直接命中：
 * ```
 * com.netease.cloudmusic.activity.ha#showDialog(boolean, String)        ← 弹窗构建与显示
 *   ← com.netease.cloudmusic.activity.ha#W7(int)
 *     ← com.netease.cloudmusic.activity.ha$c#onLoadSuccess(...)          ← 心动模式数据加载回调
 * ```
 *
 * `showDialog` 的实现与现象逐条对应（`ha.java:378`）：
 * ```java
 * inflate(w21.e.f292516c)                                    // 弹窗布局
 * findViewById(w21.d.f292419b).setImageDrawable(mWebpDrawable) // 红心 WebP 动画
 * hk.a.a(this).b(false).r(view, false).e(-872415232)          // 背景 0xCC000000（黑底半透明）
 *     .j(false).s(new b()).g();
 * getWindow().setLayout(NeteaseMusicUtils.w(200.0f), -2);      // 宽 200dp、居中
 * str = getString(w21.f.U0);                                   // 文案「正在打开心动模式...」
 * mWebpDrawable.start();
 * aiModeLoadingDialog.show();
 * animHandler.postDelayed(..., 5000L);                         // 最长 5 秒后自动关闭
 * ```
 *
 * ## 为什么不用拦截 903（SMARTPLAY_LOADING）消息
 *
 * `qp0.z0#y4` 里确实会 `sendMessageToClient(903, ...)`，但实测拦截它之后弹窗照旧出现 ——
 * 真正的弹窗是播放页 Activity **本地**构建的 MaterialDialog，与那条消息无关（已回滚该方案）。
 *
 * ## 策略
 *
 * 直接跳过 `ha#showDialog`：
 * - 弹窗不再创建/显示，下方界面自然可点；
 * - `dismissAIModeLoadingDialog()` 里对 `aiModeLoadingDialog == null || !isShowing()` 会直接 return
 *   （`ha.java:426-429`），所以不会因为「没创建过」而 NPE；
 * - 心动模式本身（推荐接口、切歌续播）完全不受影响。
 *
 * ⚠️ `activity.ha` 与 `showDialog` 都是 **R8 混淆名**，随版本更新可能变化；
 * 更新失效时按本文档的定位过程重新抓一次 `Dialog#show` 调用栈即可。
 */
class CloudMusicNoSmartPlayLoadingHook : BaseHook("cloudmusic_no_smartplay_loading") {

    private companion object {
        /** 承载心动模式加载弹窗的播放页基类（混淆名） */
        const val CLS_PLAYER_ACTIVITY = "com.netease.cloudmusic.activity.ha"

        /** 弹窗构建 + 显示的方法名（混淆名） */
        const val METHOD_SHOW_DIALOG = "showDialog"
    }

    override fun onHook() {
        val activityCls = requireClass(CLS_PLAYER_ACTIVITY) ?: return
        val handle = replace(
            activityCls,
            METHOD_SHOW_DIALOG,
            arrayOf(Boolean::class.javaPrimitiveType!!, String::class.java),
        ) { chain ->
            logd("已拦截心动模式加载弹窗（showDialog），原始文案=${chain.args.getOrNull(1)}")
            // 返回 null 即跳过原方法：弹窗不会被创建，也不会 show
            null
        }
        if (handle != null) {
            log("已挂载：$CLS_PLAYER_ACTIVITY#$METHOD_SHOW_DIALOG（拦截加载弹窗）")
        } else {
            loge("$METHOD_SHOW_DIALOG 挂载失败，功能无效（类名/方法名可能已随版本变化）")
        }
    }
}
