package top.spacejoy.spacehook.libhook.rules.amap

import top.spacejoy.spacehook.common.HookPrefs
import top.spacejoy.spacehook.libhook.base.BaseHook
import top.spacejoy.spacehook.libhook.utils.PrefsBridge
import io.github.libxposed.api.XposedInterface.Chain

/**
 * 高德地图：给普通路线规划设置默认出行方式。
 *
 * 目标 APK：`D:/Code/Android/APK/Hook/高德地图_17.01.0.2012.apk`（17.01.0.2012，真机同版本）
 * 目标进程：`com.autonavi.minimap`（主进程）
 *
 * ## 功能
 *
 * 从首页「路线」或普通搜索的「路线」进入时，默认落在用户选好的出行方式上
 * （公共交通 / 步行 / 骑行 / 驾车）。选「跟随高德」则完全不干预。
 *
 * ## 为什么挂在「初始方式决策」上
 *
 * 高德 17.01 的路线页初始化链路（反编译 `classes4.dex`）：
 *
 * ```
 * PlanHomePresenter#onPageCreated / #onNewIntent
 *   → h15.a(g15 request, IPredictResultCallback callback)      ← 决定初始出行方式
 *       ├─ request.a != null  → SELECT   ：调用方明确指定了方式，直接用它
 *       ├─ 有起点/终点        → REASONABLE：走推荐策略
 *       └─ 都没有             → DEFAULT  ：回落到「上次使用的方式」(ij5.b())
 *   → callback.onPredictResult(s55) → lu#onPredictResult → NewPlanPageDataBuilder.c(...)
 * ```
 *
 * 我们要替换的正是 `h15.a` 的**第 1 个参数**：当请求里没指定方式时，
 * 塞一份「方式 = 用户选择」的请求副本进去，后续走的还是高德自己的显式分支，
 * 所以页面、方案查询、页内切换全部照常。
 *
 * ## 边界（刻意保留的行为）
 *
 * - 请求里已有明确方式（专门的驾车入口、带方式的第三方 scheme）→ 不覆盖；
 * - 请求为空 → 不覆盖（空请求会走高德自己的兜底，硬造一个反而可能把推荐逻辑挤掉）；
 * - 页内手动切换 → 不拦（每次进入页面重新决策，所以退出重进会回到默认方式）；
 * - 改设置后无需重启高德，下次进入普通路线页就生效。
 *
 * ## 兼容性
 *
 * 类名/方法名/字段名都是混淆名，随版本变化，因此定位与校验全部走结构特征
 * （见 [AmapRouteAdapter]）。结构对不上就安静不挂载，保持高德原样。
 */
class AmapDefaultRouteHook : BaseHook("amap_default_route") {

    private companion object {
        /** 高德地图包名 */
        const val AMAP_PACKAGE = "com.autonavi.minimap"

        const val CFG_MODE = "mode"
        const val CFG_SCOPE = "scope"

        /** 0 = 跟随高德（不覆盖） */
        const val MODE_OFF = 0
        const val MODE_BUS = 1
        const val MODE_ONFOOT = 2
        const val MODE_RIDE = 3
        const val MODE_CAR = 4

        /** 0 = 仅未指定时（保留专门入口 / 第三方链接的明确选择）1 = 全部入口都覆盖 */
        const val SCOPE_SMART = 0
        const val SCOPE_ALL = 1

        /** 配置值 → 枚举常量名 */
        val MODE_KEYS = mapOf(
            MODE_BUS to "BUS",
            MODE_ONFOOT to "ONFOOT",
            MODE_RIDE to "RIDE",
            MODE_CAR to "CAR",
        )
    }

    private val cfgMode: Int get() = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_MODE), MODE_OFF)
    private val cfgScope: Int get() = PrefsBridge.getInt(HookPrefs.configKey(id, CFG_SCOPE), SCOPE_SMART)

    /** 是否连「调用方已指定」的方式也一起覆盖 */
    private val forceOverride: Boolean get() = cfgScope == SCOPE_ALL

    override fun onHook() {
        // 只在高德主进程里工作：子进程（推送 / 定位等）不需要也不应该改路线决策
        if (!ctx.isMainProcess) {
            log("非主进程（${ctx.processName}），跳过")
            return
        }
        if (!packageName.equals(AMAP_PACKAGE, ignoreCase = true)) {
            logw("目标包名不是 $AMAP_PACKAGE，跳过")
            return
        }

        val adapter = AmapRouteAdapter.locate(classLoader) { logd(it) }
        if (adapter == null) {
            loge("未找到唯一且结构匹配的路线初始化方法，功能不生效（高德版本可能已变化）")
            return
        }

        val handle = hookExecutable(adapter.initializer) { chain -> applyDefault(adapter, chain) }

        if (handle != null) {
            log("已挂载路线初始化：${adapter.initializer.declaringClass.name}#${adapter.initializer.name}")
        } else {
            loge("路线初始化方法挂载失败")
        }

        // 冗余入口：服务实现类的同名同构方法（只是转发给上面那个），多挂一层不影响结果
        for (entry in adapter.secondaryEntries) {
            val extra = hookExecutable(entry) { chain -> applyDefault(adapter, chain) }
            if (extra != null) {
                log("已挂载冗余入口：${entry.declaringClass.name}#${entry.name}")
            } else {
                logw("冗余入口挂载失败：${entry.declaringClass.name}#${entry.name}")
            }
        }
    }

    /** 拦截体：按设置决定要不要换成默认方式 */
    private fun applyDefault(adapter: AmapRouteAdapter, chain: Chain): Any? {
        val modeKey = MODE_KEYS[cfgMode] ?: return chain.proceed()
        val original = chain.getArg(0)
        val replacement = try {
            adapter.replacement(original, modeKey, forceOverride)
        } catch (t: Throwable) {
            loge("构造默认方式请求失败，回退原始行为", t)
            null
        }
        if (replacement == null) {
            logd("不覆盖：请求方式=${adapter.explicitModeOf(original) ?: "null"} 目标=$modeKey 全量=${forceOverride}")
            return chain.proceed()
        }
        logd("已应用默认出行方式：$modeKey（原请求=${adapter.explicitModeOf(original) ?: "null"}）")
        // 只改参数副本，高德持有的原始请求保持原样
        return chain.proceed(arrayOf(replacement, chain.getArg(1)))
    }
}
