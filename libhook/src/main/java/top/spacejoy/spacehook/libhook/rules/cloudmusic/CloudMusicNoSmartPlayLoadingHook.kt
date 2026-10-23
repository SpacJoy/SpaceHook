package top.spacejoy.spacehook.libhook.rules.cloudmusic

import top.spacejoy.spacehook.libhook.base.AnchorFinder
import top.spacejoy.spacehook.libhook.base.BaseHook
import top.spacejoy.spacehook.libhook.utils.DexAnchorScanner

/**
 * 网易云音乐：去掉「心动模式」启动时的加载弹窗。
 *
 * 目标进程：`com.netease.cloudmusic`
 *
 * ## 现象
 *
 * 开启心动模式、或在歌曲列表里手动切歌时，屏幕中央弹出一个黑底圆角、
 * 红心动画 +「正在打开心动模式...」的加载弹窗，持续到推荐接口返回（约 2–5 秒），
 * 期间下方界面不可点。
 *
 * ## 定位过程（首次，9.6.08）
 *
 * 先用 `Dialog#show` 抓调用栈，直接命中：
 * ```
 * com.netease.cloudmusic.activity.ha#showDialog(boolean, String)   ← 弹窗构建与显示
 *   ← com.netease.cloudmusic.activity.ha#W7(int)
 *     ← com.netease.cloudmusic.activity.ha$c#onLoadSuccess(...)     ← 心动模式数据加载回调
 * ```
 *
 * 弹窗就在 `showDialog` 里本地构建，与 `sendMessageToClient(903 = SMARTPLAY_LOADING)` 无关
 * （那条方案已试过并回滚，勿再尝试）。
 *
 * ## 为什么不能写死类名（9.6.13 的教训）
 *
 * `activity.ha` 与 `showDialog` 都是 **R8 混淆名**。9.6.13 重新出包后：
 * - 真正的播放页基类变成了 `activity.ma`（`showDialog(Z,String)V` 签名一字未变）；
 * - `activity.ha` 这个名字**没有被删除**，而是被一个毫不相关的 lambda 类占用。
 *
 * 于是 `requireClass("activity.ha")` 仍能拿到类，但类里没有 `showDialog` ——
 * Hook 静默失效：不崩溃、不报错，只是弹窗照旧。
 *
 * ## 现在的定位策略（三层，见 [AnchorFinder]）
 *
 * 1. **manifest 锚点**：`com.netease.cloudmusic.activity.PlayerActivity`
 *    在 manifest 里按名字注册，R8 不能改（改了系统找不到 Activity）。
 * 2. **继承链**：从 PlayerActivity 沿父类向上，找第一个**自己声明**了
 *    `showDialog(boolean, String)` 的祖先 —— 实测 9.6.08 爬到 `ha`、
 *    9.6.13 爬到 `ma`，都是 3 层之内。
 * 3. **已知名单**：历次适配确认过的类名，命中直接用（省掉爬链，也防 PlayerActivity 改名）。
 *
 * 全 APK 里声明 `showDialog(Z,String)V` 的类只有 2 个（另一个是与 Activity 无关的普通类），
 * 因此「沿 PlayerActivity 祖先链」天然只会命中正确的那个。
 *
 * ⚠️ 若哪天 PlayerActivity 不再继承那个基类（网易换架构），三层会全部落空，
 * 届时需按首次定位流程重新抓 `Dialog#show` 调用栈。
 */
class CloudMusicNoSmartPlayLoadingHook : BaseHook("cloudmusic_no_smartplay_loading") {

    private companion object {
        /** manifest 里注册的播放页（R8 无法改名），作为最可靠的锚点 */
        const val CLS_PLAYER_ACTIVITY = "com.netease.cloudmusic.activity.PlayerActivity"

        /** 弹窗构建 + 显示的方法名（这个字符串本身没被混淆，见下） */
        const val METHOD_SHOW_DIALOG = "showDialog"

        /**
         * 历次适配确认过的承载类（混淆名，随版本变化）。
         *
         * 只是「先试这些，省一次爬链」——真正决定能不能用的是后面的继承链解析。
         */
        val KNOWN_HOSTS = listOf(
            "com.netease.cloudmusic.activity.ma",  // 9.6.13
            "com.netease.cloudmusic.activity.ha",  // 9.6.08（注意：9.6.13 里此名已被别的类占用）
        )

        /** showDialog 的参数类型 */
        val SHOW_DIALOG_PARAMS: Array<Class<*>> =
            arrayOf(Boolean::class.javaPrimitiveType!!, String::class.java)
    }

    override fun onHook() {
        val host = resolveHost()
        if (host == null) {
            // 关键：这里必须报 ERROR 而不是安静跳过，否则「失效」和「正常」在日志与 UI 上无法区分
            loge(
                "解析不到承载 $METHOD_SHOW_DIALOG 的类 —— 该功能已失效，需要适配新版。" +
                    "已尝试：已知名单 ${KNOWN_HOSTS.size} 个 + $CLS_PLAYER_ACTIVITY 继承链"
            )
            return
        }

        log("解析到承载类：${host.name}")

        val handle = replace(host, METHOD_SHOW_DIALOG, SHOW_DIALOG_PARAMS) { chain ->
            logd("已拦截心动模式加载弹窗（showDialog），原始文案=${chain.args.getOrNull(1)}")
            // 返回 null 即跳过原方法：弹窗不会被创建，也不会 show
            null
        }
        if (handle != null) {
            log("已挂载：${host.name}#$METHOD_SHOW_DIALOG（拦截加载弹窗）")
        } else {
            loge("${host.name}#$METHOD_SHOW_DIALOG 挂载失败，功能无效")
        }
    }

    // ==================== 定位 ====================

    /**
     * 三层定位：已知名单 → manifest 锚点 + 继承链 → DexKit 扫 dex。
     *
     * 前两层是纯反射、几乎零耗时，覆盖绝大多数版本；
     * 只有前两层全落空（网易换架构、或名字整体位移）才动用 DexKit 扫 dex，
     * 避免每次启动都碰原生库。
     */
    private fun resolveHost(): Class<*>? {
        // ① 已知名单：命中即用，省一次 Class.forName + 爬链。
        //    每个都要验证它**自己声明**了 showDialog，避免 9.6.13 那种「名字还在、方法没了」的误判。
        for (name in KNOWN_HOSTS) {
            val clazz = findClass(name) ?: continue
            if (AnchorFinder.declaresMethod(clazz, METHOD_SHOW_DIALOG, SHOW_DIALOG_PARAMS)) {
                logd("已知名单命中：$name")
                return clazz
            }
            logd("已知名单失效（类在但无 $METHOD_SHOW_DIALOG）：$name")
        }

        // ② manifest 锚点 + 继承链：PlayerActivity 的名字 R8 动不了
        val player = findClass(CLS_PLAYER_ACTIVITY)
        if (player != null) {
            AnchorFinder.findAncestor(player) { clazz ->
                AnchorFinder.declaresMethod(clazz, METHOD_SHOW_DIALOG, SHOW_DIALOG_PARAMS)
            }?.let {
                logd("继承链命中：$CLS_PLAYER_ACTIVITY → ${it.name}")
                return it
            }
            logw("$CLS_PLAYER_ACTIVITY 的父类链上没有声明 $METHOD_SHOW_DIALOG 的类")
        } else {
            logw("manifest 锚点不存在：$CLS_PLAYER_ACTIVITY")
        }

        // ③ DexKit 扫 dex：前两层都失效时的兜底，结果会缓存（约 100–150ms，仅首次）
        val scanned = DexAnchorScanner.findDeclaringClass(
            loader = classLoader,
            apkPath = apkPath,
            methodName = METHOD_SHOW_DIALOG,
            paramTypes = SHOW_DIALOG_PARAMS,
            returnType = Void.TYPE,
            log = { logd(it) },
        ) ?: return null

        logd("DexKit 命中：$scanned")
        return findClass(scanned)
    }
}
