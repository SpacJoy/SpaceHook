package top.spacejoy.spacehook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 外观设置的 Compose 状态载体。
 *
 * 设置页改动这里 → 立即触发整棵 UI 重组；
 * 同时写回 [SettingsStore]，保证下次启动能恢复。
 */
object AppearanceState {

    var themeMode by mutableStateOf(SettingsStore.themeMode)
        private set

    var colorKey by mutableStateOf(SettingsStore.colorKey)
        private set

    var fontScale by mutableStateOf(SettingsStore.fontScale)
        private set

    /** 从持久化存储重新加载（激活状态变化后可调用） */
    fun reload() {
        themeMode = SettingsStore.themeMode
        colorKey = SettingsStore.colorKey
        fontScale = SettingsStore.fontScale
    }

    fun updateThemeMode(value: Int) {
        themeMode = value
        SettingsStore.themeMode = value
    }

    fun updateColorKey(value: Long) {
        colorKey = value
        SettingsStore.colorKey = value
    }

    fun updateFontScale(value: Int) {
        val coerced = value.coerceIn(80, 160)
        fontScale = coerced
        SettingsStore.fontScale = coerced
    }
}
