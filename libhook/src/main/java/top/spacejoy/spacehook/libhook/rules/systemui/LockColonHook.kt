package top.spacejoy.spacehook.libhook.rules.systemui

import top.spacejoy.spacehook.libhook.base.BaseHook

/**
 * 锁屏「大时钟」强制显示冒号。
 *
 * 目标 APK：`D:/Code/Android/APK/Hook/系统界面_17.03.260226.r.apk`
 * 目标进程：`com.android.systemui`（渲染 + 写回）/ `com.miui.aod`（编辑界面预览）
 *
 * ## 问题背景（待 jadx 复核）
 * 锁屏时钟是否显示冒号由一段 JSON 决定：
 * ```
 * constant_template_editor_info
 *   -> KeyguardPanelViewController#constantLockscreenInfoObserver
 *   -> MiuiClockController#updateClockBeanFromJson
 *   -> ClockStyleInfo#convertInfoFromClockBean
 *   -> AllInOneBase#mColonShow
 *   -> refreshFullTimeStrings() 决定文本里带不带冒号
 * ```
 * 单纯改 `Settings.Secure` 会在**任意一次锁屏信息刷新**时被
 * `KeyguardOTAInteractor#generateEditorInfo` 重新生成并覆盖为 false。
 *
 * ## 本 Hook 的三层策略
 * - **A 渲染层**：钉死 `mColonShow` / `ClockBean.isColonShow`，不受存盘值影响；
 * - **B 写入层**：把所有写入 `constant_template_editor_info` 的路径
 *   （`generateEditorInfo` / `writeEditorInfoIntoSettings` / `Settings.Secure.putString*`）
 *   中的 `"isColonShow":false` 改写成 true，根治重启失效；
 * - **C 编辑进程**：在 `com.miui.aod` 中额外点亮独立的 `colon_view`，
 *   保证编辑界面的预览也有冒号。
 */
class LockColonHook : BaseHook("lock_colon") {

    private companion object {
        const val COLON_KEY = "constant_template_editor_info"
        const val JSON_FALSE = "\"isColonShow\":false"
        const val JSON_FALSE_SPACED = "\"isColonShow\": false"
        const val JSON_TRUE = "\"isColonShow\":true"
        const val JSON_TRUE_SPACED = "\"isColonShow\": true"

        const val CLS_ALL_IN_ONE = "com.miui.clock.allInOne.AllInOneBase"
        const val CLS_STYLE_INFO = "com.miui.clock.module.ClockStyleInfo"
        const val CLS_CLOCK_BEAN = "com.miui.clock.module.ClockBean"
        const val CLS_OTA_INTERACTOR = "com.android.keyguard.ota.KeyguardOTAInteractor"
        const val CLS_SECURE = "android.provider.Settings\$Secure"
    }

    /** SystemUI 主进程的冒号是「分钟内联」样式，不能再点亮独立 colon_view，否则会双冒号 */
    private val isSystemUiProcess: Boolean get() = packageName == "com.android.systemui"

    override fun onHook() {
        hookAllInOne()
        hookClockStyleInfo()
        hookClockBean()
        hookWriteBackPaths()
    }

    // ==================== A. 渲染层 ====================

    private fun hookAllInOne() {
        val clazz = findClass(CLS_ALL_IN_ONE)
        if (clazz == null) {
            logw("$CLS_ALL_IN_ONE 不存在，本进程跳过渲染层")
            return
        }

        before(clazz, "refreshFullTimeStrings") { chain ->
            setBooleanField(chain.thisObject, "mColonShow", true)
        }

        before(clazz, "setClockStyleInfo") { chain ->
            setBooleanField(chain.args.getOrNull(0), "isColonShow", true)
            setBooleanField(chain.thisObject, "mColonShow", true)
        }

        beforeBooleanArg(clazz, "setEditorColonShow")

        if (!isSystemUiProcess) {
            beforeBooleanArg(clazz, "setEditorColonViewVisible")
        } else {
            logd("SystemUI 进程跳过 setEditorColonViewVisible（避免双冒号）")
        }

        logd("渲染层已挂载")
    }

    private fun hookClockStyleInfo() {
        findClass(CLS_STYLE_INFO)?.let { clazz ->
            before(clazz, "convertInfoFromClockBean") { chain ->
                setBooleanField(chain.args.getOrNull(2), "isColonShow", true)
            }
            logd("ClockStyleInfo.convertInfoFromClockBean 已挂载")
        }
    }

    private fun hookClockBean() {
        findClass(CLS_CLOCK_BEAN)?.let { clazz ->
            beforeBooleanArg(clazz, "setColonShow")
            logd("ClockBean.setColonShow 已挂载")
        }
    }

    // ==================== B. 写入层 ====================

    private fun hookWriteBackPaths() {
        // 1) KeyguardOTAInteractor：生成 / 写回编辑器信息
        findClass(CLS_OTA_INTERACTOR)?.let { clazz ->
            for (method in arrayOf("generateEditorInfo", "writeEditorInfoIntoSettings")) {
                before(clazz, method, arrayOf(String::class.java)) { chain ->
                    if (patchStringArg(chain, 0)) logd("$method 已把 isColonShow 改写为 true")
                }
            }
            logd("KeyguardOTAInteractor 已挂载")
        }

        // 2) Settings.Secure：任何写入 COLON_KEY 的地方都改写
        findClass(CLS_SECURE)?.let { clazz ->
            for (method in arrayOf("putString", "putStringForUser")) {
                hookAllMethods(clazz, method) { chain ->
                    val key = chain.args.getOrNull(1) as? String
                    if (key == COLON_KEY && patchStringArg(chain, 2)) {
                        logd("$method 已把存盘值改写为 true")
                    }
                    chain.proceed()
                }
            }
            logd("Settings.Secure 写入层已挂载")
        }
    }

    // ==================== 工具 ====================

    /** 把第 [index] 个 String 参数里的 isColonShow:false 改写成 true */
    private fun patchStringArg(chain: io.github.libxposed.api.XposedInterface.Chain, index: Int): Boolean {
        val raw = chain.args.getOrNull(index) as? String ?: return false
        val patched = patchColonJson(raw) ?: return false
        if (patched == raw) return false
        @Suppress("UNCHECKED_CAST")
        (chain.args as MutableList<Any?>)[index] = patched
        return true
    }

    /** 把某个 boolean 参数强制为 true */
    private fun beforeBooleanArg(clazz: Class<*>, methodName: String) {
        before(clazz, methodName, arrayOf(Boolean::class.javaPrimitiveType!!)) { chain ->
            if (chain.args.getOrNull(0) != true) {
                chain.args[0] = true
            }
        }
    }

    private fun patchColonJson(source: String): String? {
        if (!source.contains("isColonShow")) return null
        return source
            .replace(JSON_FALSE, JSON_TRUE)
            .replace(JSON_FALSE_SPACED, JSON_TRUE_SPACED)
    }
}
