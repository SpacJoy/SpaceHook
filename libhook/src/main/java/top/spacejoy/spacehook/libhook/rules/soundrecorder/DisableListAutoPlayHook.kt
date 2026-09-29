package top.spacejoy.spacehook.libhook.rules.soundrecorder

import android.os.Bundle
import android.view.View
import top.spacejoy.spacehook.libhook.base.BaseHook

/**
 * 录音机：点击列表条目不自动播放。
 *
 * 目标 APK：`D:/Code/Android/APK/Hook/录音机_8.1.5-779121d13.apk`
 * 目标进程：`com.android.soundrecorder`（包名已用 `aapt2 dump badging` 确认）
 *
 * ## 已用 jadx 复核的结论（2026-09-26，jadx 1.5.6 CLI，反编译 5385 个类）
 *
 * 数据链（行号来自 jadx 反编译结果）：
 * ```
 * N.java:1798   public final void z(View view, int i2)          <- 列表项点击
 * N.java:1880   bundle.putBoolean("playback_auto", true)
 * N.java:1886   this.f6464o.q1(bundle)                          <- 真正汇聚点
 * ContentFragment.java:475 / 819 / 859 / 902   另有 4 处 putBoolean("playback_auto", true)
 * ContentFragment.java:2028   public final void q1(Bundle bundle)
 *          \- 日志字符串 "SoundRecorder:ContentFragment", "startPlayback"   <- 确认是播放入口
 * PlaybackFragment.java:1496   this.f5825R = bundle.getBoolean("playback_auto", false)
 * ```
 *
 * ## ⚠️ 与旧 BeanShell 脚本的差异（本次复核的核心产出）
 *
 * | 旧脚本写的 | jadx 实测 | 结论 |
 * |---|---|---|
 * | `fragment.N#z(View, int)` | `fragment.N#z(View, int)`（N.java:1798） | ✅ 正确 |
 * | `ContentFragment#o1(Bundle)` | **`ContentFragment#q1(Bundle)`**（2028 行） | ❌ **已失效** |
 * | `PlaybackFragment#J1(Bundle)` | 读取动作在 `PlaybackFragment`（1496 行） | ⚠️ 读取点，无需 hook |
 *
 * 混淆名随版本变动，因此本实现改为「**候选名列表 + 自动探测**」，
 * 下次录音机更新后即使 `q1` 改名，也只需往候选表里补一个名字。
 *
 * ## 策略
 * 1. `N#z(View, int)` 打 ThreadLocal 标记 —— 只锁定「列表点击」这一条来源；
 * 2. 在播放入口消费标记，把 `playback_auto` 改为 false；
 *    通知栏（`C0357g`）、工具服务（`SoundRecorderToolService`）等其他入口不受影响。
 */
class DisableListAutoPlayHook : BaseHook("disable_list_autoplay") {

    private companion object {
        const val KEY_AUTO_PLAY = "playback_auto"
        const val CLS_RECORDS_FRAGMENT = "com.android.soundrecorder.fragment.N"
        const val CLS_CONTENT_FRAGMENT = "com.android.soundrecorder.fragment.ContentFragment"

        /**
         * 播放入口的方法名候选，按优先级排列。
         *
         * 该 APK 经过混淆，这类短方法名**每次版本更新都可能变化**：
         * - `q1` —— 8.1.5 实测正确（ContentFragment.java:2028）
         * - `o1` / `p1` —— 旧 BeanShell 脚本记录的历史名字，留作回退
         */
        val PLAYBACK_ENTRY_CANDIDATES = listOf("q1", "o1", "p1", "startPlayback")
    }

    /** 标记「本次调用来自录音列表点击」 */
    private val fromListClick = ThreadLocal<Boolean>()

    override fun onHook() {
        val recordsFragment = findClass(CLS_RECORDS_FRAGMENT)
        val contentFragment = findClass(CLS_CONTENT_FRAGMENT)

        if (recordsFragment == null || contentFragment == null) {
            logw("未找到目标类：records=$recordsFragment content=$contentFragment")
            return
        }

        hookListItemClick(recordsFragment)

        val entry = resolvePlaybackEntry(contentFragment)
        if (entry == null) {
            loge("在 $CLS_CONTENT_FRAGMENT 中找不到播放入口，候选：$PLAYBACK_ENTRY_CANDIDATES")
            return
        }
        hookPlaybackEntry(contentFragment, entry)
    }

    /** Hook 1：列表项点击 → 打上「来自列表」标记 */
    private fun hookListItemClick(recordsFragment: Class<*>) {
        val handle = before(
            recordsFragment,
            "z",
            arrayOf(View::class.java, Int::class.javaPrimitiveType!!),
        ) {
            fromListClick.set(true)
        }
        if (handle != null) {
            log("已挂载列表点击标记：N#z(View, int)")
        } else {
            logw("N#z 未挂载（方法名可能已变化，需重新用 jadx 定位）")
        }
    }

    /** 在候选名中挑出真实存在的播放入口 */
    private fun resolvePlaybackEntry(contentFragment: Class<*>): String? =
        PLAYBACK_ENTRY_CANDIDATES.firstOrNull { name ->
            findMethod(contentFragment, name, arrayOf(Bundle::class.java)) != null
        }

    /** Hook 2：播放入口 → 消费标记并清掉自动播放 */
    private fun hookPlaybackEntry(contentFragment: Class<*>, entryName: String) {
        val handle = before(contentFragment, entryName, arrayOf(Bundle::class.java)) { chain ->
            val isFromList = fromListClick.get() == true
            fromListClick.set(false)
            if (!isFromList) return@before

            val bundle = chain.args.getOrNull(0) as? Bundle ?: return@before
            if (bundle.getBoolean(KEY_AUTO_PLAY, false)) {
                bundle.putBoolean(KEY_AUTO_PLAY, false)
                logd("已禁止列表点击自动播放（入口=$entryName）")
            }
        }

        if (handle != null) {
            log("已挂载播放入口：ContentFragment#$entryName")
        } else {
            loge("播放入口 $entryName 挂载失败")
        }
    }
}
