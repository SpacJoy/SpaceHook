package top.spacejoy.spacehook.libhook.rules.douyin

import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 「抖音不裁剪」移植版：按原始宽高比把视频/图集「完整贴合」到全屏播放容器。
 *
 * 移植自 `douyinnocrop-v1.1.0.apk`（io.github.dunxuan.douyinnocrop.FitApplier，769 行），
 * 保留其状态机（pending/STATES/undo/watch/refitAll）与跳过条件（评论区、详情面板、
 * 非全屏容器等），并**修正原实现的 2 个 bug**（见 [onPlayerLayoutSize] 与注释标注
 * 「bugfix」处）：
 * 1. 原 `final float f = i / i2;` 是 int 除法（竖屏视频得 0 被 sanity 区间拦掉）；
 * 2. 原 NoCropModule 宽高对换写错顺序，两个变量都变成了 height。
 *
 * 线程约定：所有改布局操作都收敛到主线程（[runOnMain]）。
 */
internal object NoCropFitApplier {

    /** 一次适配的完整上下文 */
    data class FitState(
        val aspect: Float,
        val imagePost: Boolean,
        /** 图集封面走 ScaleType 路径（Fresco Drawee），视频走 LayoutParams 路径 */
        val draweeFit: Boolean,
        val aid: String?,
        val params: WeakReference<Any>?,
        val player: WeakReference<Any>?,
    )

    private class Watch(val container: View, val listener: View.OnLayoutChangeListener)

    // ==================== 状态表（弱引用，防止泄漏） ====================

    private val STATES = WeakHashMap<View, FitState>()
    private val ORIGINALS = WeakHashMap<View, IntArray>()      // [原宽, 原高, 已写宽, 已写高]
    private val SCALE_ORIGS = WeakHashMap<View, Array<Any?>>() // [imageViewScaleType, draweeScaleType]
    private val WATCHES = WeakHashMap<View, Watch>()
    private val REAL = WeakHashMap<View, Float>()              // 播放器回报的真实宽高比
    private val REAL_AID = WeakHashMap<View, String>()
    private val CLEAN_VMS = WeakHashMap<Any, Boolean>()        // CleanModeViewModel -> 是否激活

    /** 最近一次「视频 bind / getAweme」的状态，等待 KeepSurfaceTextureView attach 后落地 */
    @Volatile
    private var pending: FitState? = null

    /** Fresco ScalingUtils$ScaleType.FIT_CENTER 常量缓存 */
    @Volatile
    private var fitCenterConst: Any? = null

    // ==================== 入口（由 DouYinNoCropHook 调用） ====================

    /** 视频/图集 bind 时记录并适配；aspect 无效时只做清理 */
    fun apply(view: View?, state: FitState) {
        if (view == null) return
        if (!state.draweeFit && state.aspect <= 0.05f) {
            release(view)
            return
        }
        if (!state.draweeFit) pending = state
        runOnMain(view) { applyMain(view, state) }
    }

    /** KeepSurfaceTextureView#onAttachedToWindow：补挂 pending 或重放已有状态 */
    fun applyOnAttach(view: View) {
        val known = STATES[view]
        if (known != null) {
            runOnMain(view) { applyMain(view, known) }
            return
        }
        val p = pending ?: return
        if (!isPendingOwner(p, view)) return
        pending = null
        runOnMain(view) { applyMain(view, p) }
    }

    /** 解绑/换视频时恢复原布局 */
    fun release(view: View?) {
        if (view == null) return
        runOnMain(view) {
            STATES.remove(view)
            undo(view, parentOf(view))
        }
    }

    /**
     * 播放器回报真实视频尺寸（VideoPatchLayout#onVideoSizeChanged）。
     *
     * bugfix：原实现 `final float f = i / i2;` 为 int 除法 —— 竖屏视频得 0 被
     * sanity 区间拦掉、横屏精度截断；此处改为浮点除法。
     */
    fun onPlayerLayoutSize(layout: View?, w: Int, h: Int) {
        if (layout == null || w <= 0 || h <= 0) return
        val f = w.toFloat() / h.toFloat()
        if (f <= 0.1f || f >= 20f) return
        runOnMain(layout) {
            val tracked = findTracked(layout) ?: return@runOnMain
            REAL[tracked] = f
            STATES[tracked]?.let { state ->
                REAL_AID[tracked] = state.aid ?: ""
                fit(tracked, parentOf(tracked), state)
            }
        }
    }

    /** CleanModeViewModel#sA：净屏模式状态变化（净屏时允许详情页适配） */
    fun noteCleanVm(vm: Any?, active: Boolean) {
        if (vm == null) return
        CLEAN_VMS[vm] = active
    }

    /** 净屏模式切换后重放全部已跟踪的适配 */
    fun refitAll() {
        runOnMain(null) {
            val snapshot = HashMap(STATES)
            for ((view, state) in snapshot) {
                fit(view, parentOf(view), state)
            }
        }
    }

    // ==================== 核心适配 ====================

    private fun applyMain(view: View, state: FitState) {
        STATES[view] = state
        val parent = parentOf(view) ?: return
        watch(view, parent)
        fit(view, parent, state)
    }

    private fun fit(view: View, parent: View?, state: FitState) {
        if (parent == null) return
        // 跳过条件 1：评论区上下文 —— 评论里的卡片不参与适配
        if (isCommentContext(state)) {
            undo(view, parent)
            return
        }
        // 跳过条件 2：非主/详情页（如全屏浮窗）不参与
        if (!inAllowedScope()) {
            undo(view, parent)
            return
        }
        // 跳过条件 3：图集 + 详情页面板已展开时恢复默认，避免面板内容错位
        if (state.imagePost && panelShown(state)) {
            undo(view, parent)
            return
        }
        // 跳过条件 4：容器不是「全屏级」（半屏卡片等）不参与
        if (!isFullscreenLevel(parent)) {
            undo(view, parent)
            return
        }
        if (state.draweeFit) {
            applyScaleType(view)
        } else {
            fitLayoutParams(view, parent, state)
        }
    }

    /** 视频路径：按宽高比把 view 等比缩放到容器（contain，不裁剪） */
    private fun fitLayoutParams(view: View, parent: View, state: FitState) {
        val pw = parent.width
        val ph = parent.height
        if (pw <= 0 || ph <= 0) return
        val aspect = effectiveAspect(view, state)
        if (aspect <= 0.05f) return
        val w: Int
        val h: Int
        if (aspect >= pw.toFloat() / ph.toFloat()) {
            w = pw
            h = (pw / aspect).roundToInt()
        } else {
            h = ph
            w = (ph * aspect).roundToInt()
        }
        if (w <= 0 || h <= 0) return
        val lp = view.layoutParams ?: return
        val orig = ORIGINALS[view] ?: intArrayOf(lp.width, lp.height, -1, -1).also { ORIGINALS[view] = it }
        if (orig[2] == w && orig[3] == h) return // 已是目标尺寸，避免布局抖动
        lp.width = w
        lp.height = h
        if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.CENTER
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.topMargin = 0
            lp.bottomMargin = 0
        }
        orig[2] = w
        orig[3] = h
        view.layoutParams = lp
    }

    /** 图集路径：封面 View 改 FIT_CENTER（含 Fresco Drawee hierarchy） */
    private fun applyScaleType(view: View) {
        val iv = view as? ImageView ?: return
        if (!SCALE_ORIGS.containsKey(iv)) {
            SCALE_ORIGS[iv] = arrayOf(iv.scaleType, draweeScaleTypeOf(view))
        }
        // Fresco：RemoteImageView#mDraweeHolder -> getHierarchy() -> setActualImageScaleType(FIT_CENTER)
        val fitCenter = fitCenterConst(view) ?: return
        try {
            val holder = NoCropReflect.field(view, "mDraweeHolder") ?: return
            val hierarchy = NoCropReflect.callNoArg(holder, "getHierarchy") ?: return
            val m = hierarchy.javaClass.methods.firstOrNull {
                it.name == "setActualImageScaleType" && it.parameterTypes.size == 1
            } ?: return
            m.isAccessible = true
            m.invoke(hierarchy, fitCenter)
        } catch (_: Throwable) { /* Fresco 路径失败时退化为 ImageView 路径 */ }
        if (iv.scaleType == ImageView.ScaleType.CENTER_CROP) {
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    /** 适配生效的宽高比：播放器真实尺寸优先（同一作品），其次 bind 时读到的比例 */
    private fun effectiveAspect(view: View, state: FitState): Float {
        val real = REAL[view]
        val realAid = REAL_AID[view]
        if (real != null && realAid == (state.aid ?: "")) return real
        return state.aspect
    }

    /** 恢复原始布局与缩放（watch 保留，等下一次 apply 重放适配） */
    private fun undo(view: View, @Suppress("UNUSED_PARAMETER") parent: View?) {
        restoreLayoutParams(view)
        restoreScale(view)
    }

    private fun restoreLayoutParams(view: View) {
        val orig = ORIGINALS.remove(view) ?: return
        val lp = view.layoutParams ?: return
        if (orig[2] == orig[0] && orig[3] == orig[1]) return
        lp.width = orig[0]
        lp.height = orig[1]
        view.layoutParams = lp
    }

    private fun restoreScale(view: View) {
        val origs = SCALE_ORIGS.remove(view) ?: return
        (view as? ImageView)?.let { iv ->
            (origs[0] as? ImageView.ScaleType)?.let { iv.scaleType = it }
        }
        try {
            val draweeOrig = origs[1] ?: return
            val holder = NoCropReflect.field(view, "mDraweeHolder") ?: return
            val hierarchy = NoCropReflect.callNoArg(holder, "getHierarchy") ?: return
            val m = hierarchy.javaClass.methods.firstOrNull {
                it.name == "setActualImageScaleType" && it.parameterTypes.size == 1
            } ?: return
            m.isAccessible = true
            m.invoke(hierarchy, draweeOrig)
        } catch (_: Throwable) { /* 忽略 */ }
    }

    // ==================== 条件判定 ====================

    /** pending 归属校验：view 必须属于该 player 的播放视图子树 */
    private fun isPendingOwner(state: FitState, view: View): Boolean {
        val player = state.player?.get() ?: return true
        return try {
            val vv = NoCropReflect.callNoArg(player, "getVideoView") as? View
            vv == view || isDescendantOf(vv, view)
        } catch (_: Throwable) {
            true
        }
    }

    private fun isDescendantOf(root: View?, child: View): Boolean {
        if (root == null) return false
        var p: ViewParent? = child.parent
        while (p != null) {
            if (p === root) return true
            p = (p as? View)?.parent
        }
        return false
    }

    /** 评论区上下文：类名 / Activity 名 / VideoItemParams 的评论字段 */
    private fun isCommentContext(state: FitState): Boolean {
        val params = state.params?.get()
        if (params != null) {
            val pageId = NoCropReflect.field(params, "commentFeedPageId")
            if (pageId is String && pageId.isNotEmpty()) return true
            for (name in listOf("commentFragment", "feedItemFragment", "fragment", "layerFragment")) {
                val f = NoCropReflect.field(params, name)
                if (f != null) {
                    val cls = f.javaClass.name.lowercase()
                    if ("comment" in cls || "cmt" in cls) return true
                }
            }
        }
        val act = currentActivityName()
        return act != null && ("comment" in act.lowercase() || "cmt" in act.lowercase())
    }

    /** 只在主/详情页（或净屏模式）适配 */
    private fun inAllowedScope(): Boolean {
        if (cleanActive()) return true
        val act = currentActivityName() ?: return false
        val l = act.lowercase()
        return "mainactivity" in l || "detailactivity" in l
    }

    /** 图集面板是否展开（展开时图集不贴合） */
    private fun panelShown(state: FitState): Boolean {
        val params = state.params?.get()
        val flag = NoCropReflect.field(params, "isDetailPagePanelShow")
        if (flag == true) return true
        val act = currentActivityName()?.lowercase() ?: return false
        return "detailactivity" in act && !cleanActive()
    }

    /** 容器是否「全屏级」：宽 ≥ root 85%、高 ≥ root 40% */
    private fun isFullscreenLevel(parent: View): Boolean {
        val root = parent.rootView ?: return true
        if (root.width <= 0 || root.height <= 0) return true
        return parent.width >= root.width * 0.85f && parent.height >= root.height * 0.4f
    }

    private fun cleanActive(): Boolean = CLEAN_VMS.values.any { it }

    private fun currentActivityName(): String? {
        // 轻量实现：从最近一次 apply 的 view 链上拿不到 Activity 时返回 null（判定条件退化为「不跳过」的保守侧）
        return lastActivityName
    }

    /** 由 Hook 层在 context 可用时喂入（Activity 名，用于跳过条件判定） */
    @Volatile
    var lastActivityName: String? = null

    // ==================== 布局监听 / 尺寸探测 ====================

    private fun watch(view: View, parent: View) {
        if (WATCHES.containsKey(view)) return
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            STATES[v]?.let { fit(v, parentOf(v), it) }
        }
        parent.addOnLayoutChangeListener(listener)
        WATCHES[view] = Watch(parent, listener)
    }

    private fun findTracked(root: View, limit: Int = 500): View? {
        if (STATES.containsKey(root)) return root
        if (root !is ViewGroup) return null
        var count = 0
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty() && count < limit) {
            val cur = queue.removeFirst()
            count++
            if (cur is ViewGroup) {
                for (i in 0 until cur.childCount) {
                    val c = cur.getChildAt(i)
                    if (STATES.containsKey(c)) return c
                    queue.add(c)
                }
            }
        }
        return null
    }

    private fun parentOf(view: View): View? = view.parent as? View

    /** 主线程收敛：主线程直接跑，否则 post 到 view */
    private fun runOnMain(view: View?, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                block()
            } catch (_: Throwable) { /* 单个 view 适配失败不影响其他 */ }
        } else {
            val v = view
            if (v != null) {
                v.post {
                    try {
                        block()
                    } catch (_: Throwable) { /* 同上 */ }
                }
            } else {
                mainHandler.post {
                    try {
                        block()
                    } catch (_: Throwable) { /* 同上 */ }
                }
            }
        }
    }

    private val mainHandler by lazy { android.os.Handler(Looper.getMainLooper()) }

    /** Fresco ScalingUtils$ScaleType.FIT_CENTER 常量（反射取，取不到返回 null） */
    private fun fitCenterConst(view: View): Any? {
        fitCenterConst?.let { return it }
        return try {
            val cls = Class.forName(
                "com.facebook.drawee.drawable.ScalingUtils\$ScaleType",
                false,
                view.javaClass.classLoader,
            )
            val v = cls.getField("FIT_CENTER").get(null)
            fitCenterConst = v
            v
        } catch (_: Throwable) {
            null
        }
    }

    /** 当前 Fresco hierarchy 的缩放类型（恢复用） */
    private fun draweeScaleTypeOf(view: View): Any? {
        return try {
            val holder = NoCropReflect.field(view, "mDraweeHolder") ?: return null
            val hierarchy = NoCropReflect.callNoArg(holder, "getHierarchy") ?: return null
            val m = hierarchy.javaClass.methods.firstOrNull {
                it.name == "getActualImageScaleType" && it.parameterCount == 0
            }
            m?.isAccessible = true
            m?.invoke(hierarchy)
        } catch (_: Throwable) {
            null
        }
    }
}
