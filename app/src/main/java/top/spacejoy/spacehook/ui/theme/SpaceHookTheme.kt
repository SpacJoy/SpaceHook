package top.spacejoy.spacehook.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 主题模式常量（与 [top.spacejoy.spacehook.common.HookPrefs.THEME_MODE] 对应）
 */
object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
    const val MONET_SYSTEM = 3
    const val MONET_LIGHT = 4
    const val MONET_DARK = 5

    val entries = listOf(
        SYSTEM to "跟随系统",
        LIGHT to "浅色",
        DARK to "深色",
        MONET_SYSTEM to "莫奈 · 跟随系统",
        MONET_LIGHT to "莫奈 · 浅色",
        MONET_DARK to "莫奈 · 深色",
    )

    fun labelOf(mode: Int): String =
        entries.firstOrNull { it.first == mode }?.second ?: "跟随系统"

    fun toColorSchemeMode(mode: Int): ColorSchemeMode = when (mode) {
        LIGHT -> ColorSchemeMode.Light
        DARK -> ColorSchemeMode.Dark
        MONET_SYSTEM -> ColorSchemeMode.MonetSystem
        MONET_LIGHT -> ColorSchemeMode.MonetLight
        MONET_DARK -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.System
    }
}

/**
 * 全局主题包装。
 *
 * @param themeMode 主题模式（见 [ThemeMode]）
 * @param keyColor  自定义主题色（0 表示交给框架决定）
 * @param fontScale 字体缩放百分比（100 = 原始大小）
 */
@Composable
fun SpaceHookTheme(
    themeMode: Int = ThemeMode.SYSTEM,
    keyColor: Long = 0L,
    fontScale: Int = 100,
    content: @Composable () -> Unit,
) {
    val controller = remember(themeMode, keyColor) {
        if (keyColor != 0L) {
            ThemeController(
                colorSchemeMode = ThemeMode.toColorSchemeMode(themeMode),
                keyColor = Color(keyColor.toInt()),
            )
        } else {
            ThemeController(colorSchemeMode = ThemeMode.toColorSchemeMode(themeMode))
        }
    }

    MiuixTheme(controller = controller) {
        val density = LocalDensity.current
        val scale = (fontScale.coerceIn(80, 160) / 100f) * density.fontScale
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, scale),
        ) {
            content()
        }
    }
}
