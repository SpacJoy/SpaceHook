package top.spacejoy.spacehook.libhook.rules.cloudmusic

import top.spacejoy.spacehook.libhook.base.AnchorFinder
import top.spacejoy.spacehook.libhook.base.BaseHook
import top.spacejoy.spacehook.libhook.utils.DexAnchorScanner
import top.spacejoy.spacehook.libhook.utils.XLog

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
 * ## 为什么不再挂 `showDialog`（9.6.13 二次失效的教训）
 *
 * 历史上挂的是 `showDialog(boolean, String)`（弹窗构建与显示）。它的问题在于
 * **范围太窄、位置太靠下游**，两个版本连续踩坑：
 *
 * 1. **9.6.08 → 9.6.13 类名漂移**：播放页基类 `activity.ha` 变成 `activity.ma`。
 * 2. **9.6.13 出现第二份实现**：全 APK 有**两个类**各自声明了
 *    `showAIModeLoadingDialog(Z,String)V` —— 播放页基类 `activity.ma` 一份、
 *    `i1/c`（`com.cmbridge` 桥接实现）另一份。只挂 `ma#showDialog` 时，
 *    另一条路径照旧弹窗，表现为「日志说挂载成功、弹窗没消失」。
 *
 * 所以现在改为挂 **`showAIModeLoadingDialog`**（上游唯一入口），并且
 * **遍历所有声明该方法的类逐个挂**，不再赌「只有一个」。
 *
 * ## 为什么挂上游比挂 `showDialog` 更安全
 *
 * `showAIModeLoadingDialog(Z,String)` 是弹窗的唯一入口，内部只做三件事：
 * ```
 * if (isPrepareShow) return;          // ① 防重复
 * isPrepareShow = true;               // ② 置标志
 * if (mWebpDrawable != null) showDialog(...)   // ③ 弹窗
 * else 下载红心 webp，回调里再 showDialog
 * ```
 * 在它入口直接返回，三件事全不发生：
 * - 弹窗不创建 → `aiModeLoadingDialog` 永远是 null；
 * - `dismissAIModeLoadingDialog()` 首句就是 `if (kVar == null || !kVar.isShowing()) return;`
 *   → 后续所有 dismiss 调用（onResume / onDestroy / 802 收起分支）全部安全空转，不 NPE；
 * - **`isPrepareShow` 保持 false**，不会像旧方案那样留下「已置 true 但永远不弹」的状态；
 * - 红心 webp 连下载都不发起（旧方案只拦 `showDialog` 时，动画已经 start 却没人 stop）。
 *
 * 心动模式本身**不受影响**：它只是 UI 提示，真正的模式切换走 `utils.jj` 的 RPC 播放链，
 * 不经过本方法的返回值（方法签名是 `void`）。
 *
 * ⚠️ **绝对不要往上游再挂 `handleMessage` / `dispatchPlayCommand`**：
 * 那两个方法覆盖切歌、播放错误、音量、进度同步等 12+ 个分支，拦在那里会直接搞坏播放页。
 *
 * ## 定位策略（三层，见 [AnchorFinder]）
 *
 * 1. **已知名单**：历次适配确认过的类名，命中且**自己声明**了目标方法才用。
 * 2. **manifest 锚点 + 继承链**：`activity.PlayerActivity` 在 manifest 里按名字注册，
 *    R8 不能改名；沿父类向上找第一个自己声明了目标方法的祖先。
 * 3. **DexKit 扫 dex**：前两层都落空时的兜底，返回**全部**命中（不要求唯一）。
 */
class CloudMusicNoSmartPlayLoadingHook : BaseHook("cloudmusic_no_smartplay_loading") {

    private companion object {
        /** manifest 里注册的播放页（R8 无法改名），作为最可靠的锚点 */
        const val CLS_PLAYER_ACTIVITY = "com.netease.cloudmusic.activity.PlayerActivity"

        /**
         * 弹窗入口方法名。
         *
         * 与 `showDialog` 不同，这个方法名**没被混淆**（R8 只混淆私有/内部方法，
         * 这个方法是 `public` 且被桥接层跨模块调用，名字被保留）。
         */
        const val METHOD_SHOW_AI_LOADING = "showAIModeLoadingDialog"

        /**
         * 历次适配确认过的承载类（混淆名，随版本变化）。
         *
         * ⚠️ 只放**播放页基类**。桥接实现（9.6.13 的 `i1/c`、9.6.08 的 `j1/c`）是纯混淆名、
         * 且版本间会换名，写死反而误导 —— 它们靠下面的 DexKit 扫描兜底覆盖。
         * 每个都要验证它**自己声明**了目标方法，避免「名字还在、方法没了」的误判。
         */
        val KNOWN_HOSTS = listOf(
            "com.netease.cloudmusic.activity.ma",  // 9.6.13 播放页基类
            "com.netease.cloudmusic.activity.ha",   // 9.6.08 播放页基类（注意：9.6.13 里此名已被别的类占用）
        )

        /** showAIModeLoadingDialog(Z, String) 的参数类型 */
        val LOADING_PARAMS: Array<Class<*>> =
            arrayOf(Boolean::class.javaPrimitiveType!!, String::class.java)

        /**
         * 第三方库的 MaterialDialog 基类 —— **最稳的兜底切入点**。
         *
         * `com.afollestad.materialdialogs.*` 是**第三方库**，包名由库作者决定，
         * 网易云的 R8 配置改不了它（改了库就跑不起来），所以这个类名跨版本稳定。
         *
         * 心动模式加载弹窗最终一定要调 `show()` 才会显示，因此挂这里能覆盖
         * 「网易云内部换了几条创建路径、几个承载类」的所有情况。
         */
        const val CLS_MATERIAL_DIALOG = "com.afollestad.materialdialogs.g"

        /** Dialog 的显示方法（框架/库方法，名字不会被混淆） */
        const val METHOD_SHOW = "show"

        /** 心动模式加载弹窗的文案特征（用于过滤，避免误拦其他弹窗） */
        const val LOADING_TEXT_HINT = "正在打开心动模式"

        /** Tinker 补挂轮询间隔：补丁通常在 APK 加载后几十毫秒完成，2 秒足够 */
        const val TINKER_POLL_INTERVAL_MS = 2000L

        /** Tinker 补挂最多轮询次数（约 20 秒后放弃，避免常驻线程） */
        const val TINKER_POLL_TIMES = 10

        /** 遍历 View 树的最大深度（防御性上限，避免异常 View 结构递归过深） */
        const val MAX_VIEW_DEPTH = 20
    }

    override fun onHook() {
        // ① 主方案：挂 MaterialDialog#show()（第三方库方法，类名跨版本稳定）
        //    这是最不容易失效的切入 —— 不管网易云内部走哪条创建路径，
        //    弹窗最终都要调 show() 才会显示。
        mountMaterialDialogShow(classLoader)

        // ② 精准方案：挂 showAIModeLoadingDialog（上层入口，命中时更早拦住）
        val hosts = resolveHosts(classLoader)
        if (hosts.isEmpty()) {
            // 不直接 return —— ① 已经挂上了 show() 兜底，功能仍可能生效。
            // 但必须在日志里说清楚「精准入口没找到」，否则排查时会误判。
            logw(
                "解析不到承载 $METHOD_SHOW_AI_LOADING 的类（$CLS_PLAYER_ACTIVITY 链 + DexKit 均落空）。" +
                    "已退化为只挂 ${CLS_MATERIAL_DIALOG}#$METHOD_SHOW 兜底"
            )
        } else {
            log("解析到 ${hosts.size} 个承载类：${hosts.joinToString { describe(it) }}")
            val mounted = mountHosts(hosts)
            if (mounted == 0) {
                loge("承载类全部挂载失败，仅靠 ${CLS_MATERIAL_DIALOG}#$METHOD_SHOW 兜底")
            } else {
                log("共挂上 $mounted/${hosts.size} 个承载类拦截点")
            }
        }

        // ③ Tinker 热更新：Hook 在补丁装载**之前**就已经挂完（实测早 35ms），
        //    而 Tinker 会用 DelegateLastClassLoader 让补丁里的类覆盖 APK 里的同名类。
        //    于是「挂上的 Method」与「实际执行的 Method」是两个不同对象 → 零触发。
        //    这里安排补挂：等补丁装完，用**当前生效的类加载器**重新解析并挂上。
        scheduleTinkerRemount()
    }

    /**
     * 挂 `com.afollestad.materialdialogs.g#show()`，**按文案过滤**只拦心动模式加载弹窗。
     *
     * ## 为什么这是最稳的切入点
     *
     * - `com.afollestad.materialdialogs` 是第三方库，包名/类名由库作者决定，
     *   网易云的 R8 配置动不了（动了库就跑不起来）→ **跨版本稳定**；
     * - `show()` 是 `Dialog` 语义的必经方法，不管弹窗从 `ma`、`i1.c` 还是未来第三条路径创建，
     *   最终都要调它。
     *
     * ## 为什么必须按文案过滤
     *
     * 这个基类被网易云到处使用（会员续费、私信、网络提示…）。
     * 不加过滤直接拦 `show()` 会把**所有** MaterialDialog 都干掉。
     *
     * 过滤方式：读 `Dialog#findViewById` 拿不到文案（弹窗内容由 MaterialDialog 内部管理），
     * 因此改用更可靠的判据 —— 反射读 `mDialogIdentify` / `view` 不好使，
     * 这里直接读 Dialog 的 **Window 标题 + 所有 TextView 的文本**，命中特征串才拦。
     *
     * @return 是否挂载成功
     */
    private fun mountMaterialDialogShow(loader: ClassLoader): Boolean {
        val dialogCls = try {
            Class.forName(CLS_MATERIAL_DIALOG, false, loader)
        } catch (_: Throwable) {
            null
        }
        if (dialogCls == null) {
            logw("找不到 ${CLS_MATERIAL_DIALOG}（第三方库被裁掉了？），无法使用 show() 兜底")
            return false
        }

        val handle = replace(dialogCls, METHOD_SHOW, emptyArray()) { chain ->
            if (shouldBlockDialog(chain.thisObject)) {
                // 同一个弹窗会被反复 show（每次切歌都可能再弹一次），
                // 首次打全文便于确认，之后按 XLog.once 只记一次 + 计数，避免刷屏
                val n = hitCount.incrementAndGet()
                XLog.once(
                    "cloudmusic_show_blocked",
                    "已拦截心动模式加载弹窗（show 兜底），文案命中「$LOADING_TEXT_HINT」",
                    id,
                )
                if (n > 1) logd("show 兜底累计拦截 $n 次")
                // 短路：不调 super.show()，弹窗不显示
                null
            } else {
                chain.proceed()
            }
        }
        if (handle != null) {
            log("已挂载：${CLS_MATERIAL_DIALOG}#$METHOD_SHOW（show 兜底，按文案过滤）")
            return true
        }
        loge("${CLS_MATERIAL_DIALOG}#$METHOD_SHOW 挂载失败")
        return false
    }

    /**
     * 判断这个 Dialog 是不是「心动模式加载弹窗」。
     *
     * ## 为什么能读到文本
     *
     * 网易云在 `show()` **之前**就已经把文案塞进 TextView 了（`ma.showDialog`：
     * 先 `textView.setText(str)`，再 `aiModeLoadingDialog.show()`），所以拦截 `show()`
     * 时内容已经在 View 树里。
     *
     * ## 为什么用两条路径读
     *
     * `getDecorView()` 在 Dialog **尚未 attach 到 WindowManager 时**可能返回一个
     * 还没有内容的 DecorView。所以优先走 `findViewById(android.R.id.content)`
     * （MaterialDialog 在构造时就已经 setContentView，这条路径一定拿得到），
     * 失败再退回 DecorView。
     */
    private fun shouldBlockDialog(dialog: Any?): Boolean {
        if (dialog == null) return false
        return try {
            val getWindow = dialog.javaClass.getMethod("getWindow")
            val window = getWindow.invoke(dialog) ?: return false

            // ① 优先：Window 的 contentView（MaterialDialog 构造时就已填充）
            val content: android.view.View? = try {
                val getContentView = window.javaClass.getMethod("getContentView")
                (getContentView.invoke(window) as? android.view.View)
                    ?: (window as? android.view.Window)?.findViewById(android.R.id.content)
            } catch (_: Throwable) {
                (window as? android.view.Window)?.findViewById(android.R.id.content)
            }

            val text = if (content != null) collectText(content, 0) else ""

            // ② 兜底：DecorView（有些实现 contentView 为空但 DecorView 有内容）
            val finalText = if (text.isBlank()) {
                try {
                    val getDecorView = window.javaClass.getMethod("getDecorView")
                    val decor = getDecorView.invoke(window) as? android.view.View
                    if (decor != null) collectText(decor, 0) else text
                } catch (_: Throwable) {
                    text
                }
            } else text

            val hit = finalText.contains(LOADING_TEXT_HINT, ignoreCase = true)
            if (hit) logd("弹窗文本命中：${finalText.take(80)}")
            else if (finalText.isNotBlank()) logd("非目标弹窗，放行：${finalText.take(60)}")
            hit
        } catch (t: Throwable) {
            logd("读取弹窗文本失败：${XLog.brief(t)}")
            false
        }
    }

    /** 递归收集 View 树里的所有文本 */
    private fun collectText(view: android.view.View, depth: Int): String {
        if (depth > MAX_VIEW_DEPTH) return ""
        val sb = StringBuilder()
        if (view is android.widget.TextView) {
            view.text?.let { sb.append(it).append(' ') }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                sb.append(collectText(view.getChildAt(i), depth + 1))
            }
        }
        return sb.toString()
    }

    /** 已经挂过的 Class（按对象身份比较，不是按名字 —— 同名不同加载器是两份不同的类） */
    private val mountedTypes = ArrayList<Class<*>>()

    /** show 兜底累计拦截次数（避免每次命中都打完整日志刷屏） */
    private val hitCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 承载类累计拦截次数 */
    private val hostHitCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 打印类的来源，便于判断它来自 base.apk 还是 Tinker 补丁 */
    private fun describe(clazz: Class<*>): String {
        val loader = clazz.classLoader
        val source = when {
            loader == null -> "boot"
            loader.javaClass.simpleName.contains("DelegateLast") -> "Tinker补丁"
            else -> "base.apk"
        }
        return "${clazz.name}@$source"
    }

    /** 挂载一组承载类，返回成功数量 */
    private fun mountHosts(hosts: List<Class<*>>): Int {
        var mounted = 0
        for (host in hosts) {
            // 同一个 Class 对象只挂一次；同名但来自不同加载器的是两份不同的类，都要挂
            if (mountedTypes.any { it === host }) {
                logd("已挂过，跳过：${describe(host)}")
                continue
            }
            val handle = replace(host, METHOD_SHOW_AI_LOADING, LOADING_PARAMS) { chain ->
                // 每次切歌都可能触发一次，首次打全文（含文案）便于确认，之后只计数
                val n = hostHitCount.incrementAndGet()
                XLog.once(
                    "cloudmusic_host_blocked",
                    "已拦截心动模式加载弹窗（${describe(host)}），原始文案=${chain.args.getOrNull(1)}",
                    id,
                )
                if (n > 1) logd("承载类累计拦截 $n 次")
                // 返回 null 即跳过原方法：弹窗不会被创建，不会 show，isPrepareShow 也不会被置 true
                null
            }
            if (handle != null) {
                mounted++
                mountedTypes.add(host)
                log("已挂载：${describe(host)}#$METHOD_SHOW_AI_LOADING（拦截加载弹窗）")
            } else {
                loge("${describe(host)}#$METHOD_SHOW_AI_LOADING 挂载失败")
            }
        }
        return mounted
    }

    /**
     * 等 Tinker 补丁装载完成后，用**当前生效的类加载器**重新解析并补挂。
     *
     * ## 为什么不能只等一会儿再调一次 findClass
     *
     * Tinker 装补丁的方式是创建一个新的 `DelegateLastClassLoader` 并替换进 App。
     * `Class.forName(name, false, 旧加载器)` 永远返回**旧加载器**里那份类 ——
     * 而实际执行的是新加载器里那份。两者是不同的 Method 对象，所以必须换加载器重解析。
     *
     * ## 为什么用轮询而不是固定延迟
     *
     * Tinker 装载耗时不确定（实测 9ms，但冷启动 / dex2oat 时会变长），
     * 固定延迟容易错过。这里按 [TINKER_POLL_INTERVAL_MS] 最多查 [TINKER_POLL_TIMES] 次，
     * 一旦挂上新类就停止。
     *
     * ⚠️ 必须放后台线程：`Class.forName` + `initialize=true` 可能触发类初始化，
     * 在主线程做有卡住启动页的风险。
     */
    private fun scheduleTinkerRemount() {
        val patchDexes = DexAnchorScanner.tinkerPatchDexPaths(packageName) { logd(it) }
        if (patchDexes.isEmpty()) {
            logd("没有 Tinker 补丁，无需补挂")
            return
        }

        logd("检测到 ${patchDexes.size} 个 Tinker 补丁 dex，开始等待补丁装载…")

        val task = Runnable {
            // 已挂上「承载类」的加载器：一旦成功就不再轮询
            var done = false
            for (attempt in 1..TINKER_POLL_TIMES) {
                if (done) break
                try {
                    Thread.sleep(TINKER_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Runnable
                }

                // 关键：用「当前正在生效」的加载器重新解析，而不是最初那个
                val loader = liveAppClassLoader() ?: continue
                logd("第 $attempt 次轮询：当前加载器=${loader.javaClass.name}")

                // 补丁换了加载器后，MaterialDialog 也可能来自新的那份 —— 也要重挂
                val dialogCls = try {
                    Class.forName(CLS_MATERIAL_DIALOG, false, loader)
                } catch (_: Throwable) {
                    null
                }
                if (dialogCls != null && mountedTypes.none { it === dialogCls }) {
                    log("补挂：${describe(dialogCls)}#$METHOD_SHOW（show 兜底）")
                    if (mountMaterialDialogShow(loader)) mountedTypes.add(dialogCls)
                }

                // 复用上面已经读到的补丁列表，避免 resolveHosts 再扫一遍目录
                val hosts = resolveHosts(loader, patchDexes)
                val fresh = hosts.filter { candidate -> mountedTypes.none { it === candidate } }
                if (fresh.isNotEmpty()) {
                    log("补挂（第 $attempt 次轮询）：发现 ${fresh.size} 个新承载类 ${fresh.map { describe(it) }}")
                    val mounted = mountHosts(fresh)
                    log("补挂完成：成功 $mounted/${fresh.size} 个")
                    if (mounted > 0) done = true
                }
            }
            if (!done) logd("Tinker 补挂轮询结束：未发现新承载类（共 $TINKER_POLL_TIMES 次）")
        }

        try {
            val thread = Thread(task, "SpaceHook-tinker-remount")
            thread.isDaemon = true
            thread.start()
        } catch (t: Throwable) {
            loge("启动 Tinker 补挂线程失败", t)
        }
    }

    /**
     * 取当前 App 真正在用的类加载器。
     *
     * 依次尝试（任一成功即用）：
     * 1. `ActivityThread.currentApplication().classLoader` —— Tinker 换过加载器后这里是最新的；
     * 2. `ActivityThread.currentActivityThread()` 反射 `mBoundApplication.info.mClassLoader`；
     * 3. 当前线程的 contextClassLoader。
     *
     * 全部失败返回 null（本轮跳过，下轮再试）。
     */
    private fun liveAppClassLoader(): ClassLoader? {
        // ① Application 的类加载器
        try {
            val at = Class.forName("android.app.ActivityThread")
            val currentApp = at.getDeclaredMethod("currentApplication").apply { isAccessible = true }
            val app = currentApp.invoke(null)
            if (app != null) {
                val getCl = app.javaClass.getMethod("getClassLoader")
                (getCl.invoke(app) as? ClassLoader)?.let { return it }
            }
        } catch (_: Throwable) { /* 继续 */ }

        // ② 反射 mBoundApplication.info.mClassLoader
        try {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
            val thread = current.invoke(null)
            if (thread != null) {
                val boundApp = at.getDeclaredField("mBoundApplication").apply { isAccessible = true }
                val appBindData = boundApp.get(thread)
                if (appBindData != null) {
                    val info = appBindData.javaClass.getDeclaredField("info").apply { isAccessible = true }
                    val loadedApk = info.get(appBindData)
                    if (loadedApk != null) {
                        val getCl = loadedApk.javaClass.getDeclaredMethod("getClassLoader")
                        getCl.isAccessible = true
                        (getCl.invoke(loadedApk) as? ClassLoader)?.let { return it }
                    }
                }
            }
        } catch (_: Throwable) { /* 继续 */ }

        // ③ 当前线程上下文加载器
        return Thread.currentThread().contextClassLoader
    }

    // ==================== 定位 ====================

    /**
     * 三层定位，返回**所有**找到的承载类（去重）。
     *
     * 为什么要返回多个：9.6.13 起同一功能存在两份实现（播放页基类 + com.cmbridge 桥接），
     * 只挂其中一个会漏掉另一条弹窗路径。详见类文档。
     *
     * 第 1、2 层是纯反射、几乎零耗时，用来「快速拿到至少一个」；
     * 第 3 层 DexKit **始终执行一次**，因为「前两层只找到一个」恰恰可能就是
     * 「还有第二份实现、但它的混淆名没进已知名单」的情况 —— 这正是 9.6.13 漏掉 `i1.c` 的原因。
     * 扫描结果按 APK 指纹缓存，App 不变时之后启动只读缓存、不碰原生库。
     */
    private fun resolveHosts(
        loader: ClassLoader,
        knownPatchDexes: List<String>? = null,
    ): List<Class<*>> {
        val result = LinkedHashMap<String, Class<*>>()

        fun add(clazz: Class<*>?, why: String) {
            if (clazz == null) return
            // 按「类名@加载器」去重：同名但来自不同加载器的是两份不同的类，不能互相覆盖
            val key = clazz.name + "@" + (clazz.classLoader?.javaClass?.simpleName ?: "boot")
            if (result.putIfAbsent(key, clazz) == null) {
                logd("$why → ${describe(clazz)}")
            }
        }

        fun load(name: String): Class<*>? = try {
            Class.forName(name, false, loader)
        } catch (_: Throwable) {
            null
        }

        // ① 已知名单：命中即用，省一次爬链。
        //    每个都要验证它**自己声明**了目标方法，避免「名字还在、方法没了」的误判。
        for (name in KNOWN_HOSTS) {
            val clazz = load(name)
            if (clazz == null) {
                logd("已知名单不存在：$name")
                continue
            }
            if (AnchorFinder.declaresMethod(clazz, METHOD_SHOW_AI_LOADING, LOADING_PARAMS)) {
                add(clazz, "已知名单命中")
            } else {
                logd("已知名单失效（类在但无 $METHOD_SHOW_AI_LOADING）：$name")
            }
        }

        // ② manifest 锚点 + 继承链：PlayerActivity 的名字 R8 动不了
        val player = load(CLS_PLAYER_ACTIVITY)
        if (player != null) {
            val ancestor = AnchorFinder.findAncestor(player) { clazz ->
                AnchorFinder.declaresMethod(clazz, METHOD_SHOW_AI_LOADING, LOADING_PARAMS)
            }
            if (ancestor != null) {
                add(ancestor, "继承链命中（$CLS_PLAYER_ACTIVITY 祖先）")
            } else {
                logw("$CLS_PLAYER_ACTIVITY 的父类链上没有声明 $METHOD_SHOW_AI_LOADING 的类")
            }
        } else {
            logw("manifest 锚点不存在：$CLS_PLAYER_ACTIVITY")
        }

        // ③ DexKit 扫 dex：把前面漏掉的补上。
        //
        //    为什么要扫：前两层只靠「名字 + 继承链」，拿到的一般只有 1 个；
        //    而 9.6.13 实际有 2 份实现，第二份（i1.c）是纯混淆名、名字每版都换，
        //    靠名单和继承链都拿不到。所以这里必须扫一次全 APK 才能补全。
        //
        //    ⚠️ 必须**连 Tinker 补丁一起扫**：网易云用 Tinker 热更新，
        //    补丁 dex 通过 DelegateLastClassLoader 覆盖 APK 里的同名类。
        //    只扫 base.apk 会漏掉补丁里的实现（2026-10-26 实测教训）。
        //
        //    性能：结果按「APK + 补丁」指纹缓存。App 不变时之后启动只读属性文件、
        //    不加载 libdexkit.so；只有 App/补丁更新或首次运行才真扫。
        //
        //    knownPatchDexes：补挂轮询时传进来复用，避免每次都重新列一遍 tinker 目录。
        val patchDexes = knownPatchDexes ?: DexAnchorScanner.tinkerPatchDexPaths(packageName) { logd(it) }
        val scanned = DexAnchorScanner.findDeclaringClasses(
            loader = loader,
            apkPath = apkPath,
            methodName = METHOD_SHOW_AI_LOADING,
            paramTypes = LOADING_PARAMS,
            returnType = Void.TYPE,
            log = { logd(it) },
            extraScanPaths = patchDexes,
        )
        if (scanned.isNotEmpty()) {
            logd("DexKit 命中 ${scanned.size} 个：$scanned")
            for (name in scanned) {
                // 前两层已命中的跳过；新增的才反射取类
                if (result.containsKey(name)) continue
                val clazz = findClass(name)
                if (clazz != null && AnchorFinder.declaresMethod(clazz, METHOD_SHOW_AI_LOADING, LOADING_PARAMS)) {
                    add(clazz, "DexKit 补全")
                } else {
                    logw("DexKit 命中的类不可用：$name")
                }
            }
        }

        return result.values.toList()
    }
}
