package top.spacejoy.spacehook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 实验性功能的 Compose 状态载体（与 [AppearanceState] 同款用法）。
 *
 * 设置页改动这里 → 立即触发整棵 UI 重组（例如导航宿主的转场动画当场切换）；
 * 同时写回 [SettingsStore]，保证下次启动能恢复。
 */
object FeatureState {

    /** 预测性返回动画：`true` = Miuix Y 轴转场（Modal），`false` = 立即切换（None） */
    var predictiveBack by mutableStateOf(SettingsStore.predictiveBack)
        private set

    /**
     * 「启动提示」被单独关掉提示的用户应用数量。
     *
     * 设置页用它写副标题；子页面每改一个开关就刷新一次，
     * 否则返回设置页时看到的还是旧数字（[SettingsStore] 不是 Compose 状态）。
     */
    var toastAppsClosedCount by mutableStateOf(countToastAppsClosed())
        private set

    /** 从持久化存储重新加载 */
    fun reload() {
        predictiveBack = SettingsStore.predictiveBack
        toastAppsClosedCount = countToastAppsClosed()
    }

    fun updatePredictiveBack(value: Boolean) {
        predictiveBack = value
        SettingsStore.predictiveBack = value
    }

    /** 重新统计并刷新「被关掉提示」的应用数量 */
    fun refreshToastAppsClosedCount() {
        toastAppsClosedCount = countToastAppsClosed()
    }

    private fun countToastAppsClosed(): Int =
        top.spacejoy.spacehook.common.HookCatalog.userApps.count {
            !SettingsStore.isToastAppEnabled(it.packageName)
        }
}
