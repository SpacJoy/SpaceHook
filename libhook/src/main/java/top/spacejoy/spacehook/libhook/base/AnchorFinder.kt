package top.spacejoy.spacehook.libhook.base

import java.lang.reflect.Method

/**
 * 混淆名锚点解析工具。
 *
 * ## 解决什么问题
 *
 * 第三方 App 的类名 / 方法名是 R8 混淆名，**每次发版都可能整批变化**
 * （实例：网易云 9.6.08 的 `activity.ha` 到 9.6.13 变成 `activity.ma`，
 * 旧名字还被另一个无关的 lambda 类占用了 —— 类存在但方法不存在，
 * Hook 因此「静默失效」，不崩溃也不报错）。
 *
 * 应对办法不是记名字，而是**记关系**：R8 会改名字，但改不了
 * 「谁继承谁」「谁声明了什么方法」这些结构事实。
 *
 * ## 三种锚点（按稳健程度排序）
 *
 * 1. **manifest 锚点（最稳）**：在 `AndroidManifest.xml` 里注册过的类名
 *    被 R8 强制保留（否则系统找不到组件，App 直接崩），例如
 *    `com.netease.cloudmusic.activity.PlayerActivity`。拿它当起点最可靠。
 * 2. **继承链（entailed）**：从稳定类沿父类向上爬，找第一个声明了目标方法的祖先。
 *    适用于「目标方法写在某层基类里、具体页面是它的子类」这种情况。
 * 3. **已知名单（兜底）**：把历次适配确认过的类名记成列表。命中即用，
 *    但**只作为加速与兜底**，不能作为唯一手段。
 *
 * ## 为什么不用 DexKit
 *
 * DexKit 能在运行时扫 dex 按特征定位，是参考项目（HyperCeiler / AmapRouteDefault）
 * 的主力方案，但代价是引入原生库（arm64 so 约 395 KB）、首次扫描 100–150 ms，
 * 且每个 Hook 都要改写成查询 DSL。目前本项目只有极少数 Hook 依赖混淆名，
 * 因此先用手写锚点；等依赖混淆名的 Hook 多到维护不过来再统一上 DexKit。
 * 届时本文件的三层策略可以直接作为 DexKit 的「快速校验 + 兜底」。
 */
object AnchorFinder {

    /**
     * 从 [start] 开始沿父类链向上，返回第一个满足 [predicate] 的类。
     *
     * @param includeSelf 是否把 [start] 自身也纳入判断（默认 true）
     */
    fun findAncestor(
        start: Class<*>?,
        includeSelf: Boolean = true,
        predicate: (Class<*>) -> Boolean,
    ): Class<*>? {
        var c: Class<*>? = if (includeSelf) start else start?.superclass
        var guard = 0
        while (c != null && c != Any::class.java && guard < MAX_DEPTH) {
            if (predicate(c)) return c
            c = c.superclass
            guard++
        }
        return null
    }

    /**
     * 按「类名 + 方法名 + 参数类型」查找方法所在的类（只认**声明**该方法的类，不含继承）。
     *
     * 用于识别「这个类就是目标」，而不是「它能调用到目标」。
     */
    fun declaresMethod(
        clazz: Class<*>?,
        methodName: String,
        paramTypes: Array<Class<*>>,
    ): Boolean {
        if (clazz == null) return false
        return try {
            clazz.getDeclaredMethod(methodName, *paramTypes)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 按签名查找方法本身（含公有方法，不含继承链之外的搜索）。
     * 找不到返回 null，不抛异常。
     */
    fun findDeclaredMethod(
        clazz: Class<*>?,
        methodName: String,
        paramTypes: Array<Class<*>>,
    ): Method? = try {
        clazz?.getDeclaredMethod(methodName, *paramTypes)?.apply { isAccessible = true }
    } catch (_: Throwable) {
        null
    }

    /** 父类链最大爬升深度 —— 防止异常 Class 结构导致死循环 */
    private const val MAX_DEPTH = 32
}
