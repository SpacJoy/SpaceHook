package top.spacejoy.spacehook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.spacejoy.spacehook.AppearanceState
import top.spacejoy.spacehook.BuildConfig
import top.spacejoy.spacehook.FeatureState
import top.spacejoy.spacehook.SettingsStore
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.ui.theme.ThemeMode
import top.spacejoy.spacehook.xposed.XposedServiceManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：通用 / Hook 列表 / 外观 / 实验性功能 / 关于。
 *
 * 开关一律用本地 [androidx.compose.runtime.mutableStateOf] 承载并写回 [SettingsStore]，
 * 保证点击后立即刷新 UI（而不是等切换页面才读到新值）。
 */
@Composable
fun SettingsScreen() {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }

    // ===== 开关状态（点击即时反馈 + 持久化）=====
    var masterEnabled by remember { mutableStateOf(SettingsStore.masterEnabled) }
    var safeMode by remember { mutableStateOf(SettingsStore.safeMode) }
    var debugMode by remember { mutableStateOf(SettingsStore.debugMode) }
    // 走 FeatureState：导航宿主直接订阅它，关掉动画当场生效
    val predictiveBack = FeatureState.predictiveBack
    var hotReload by remember { mutableStateOf(SettingsStore.hotReload) }
    var toastOnStart by remember { mutableStateOf(SettingsStore.toastOnStart) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ==================== 通用 ====================
        item { SmallTitle("通用") }
        item {
            SettingsCard {
                SwitchPreference(
                    title = "模块总开关",
                    summary = "关闭后所有 Hook 一律不加载",
                    checked = masterEnabled,
                    onCheckedChange = {
                        masterEnabled = it
                        SettingsStore.masterEnabled = it
                    },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
                SwitchPreference(
                    title = "安全模式",
                    summary = "暂停全部 Hook，用于排查因模块导致的开机异常",
                    checked = safeMode,
                    onCheckedChange = {
                        safeMode = it
                        SettingsStore.safeMode = it
                    },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
                SwitchPreference(
                    title = "调试模式",
                    summary = "输出详细日志，便于定位 Hook 未生效的问题",
                    checked = debugMode,
                    onCheckedChange = {
                        debugMode = it
                        SettingsStore.debugMode = it
                    },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ==================== Hook 列表 ====================
        item { SmallTitle("Hook 列表") }
        item {
            SettingsCard {
                ArrowPreference(
                    title = "刷新已生效状态",
                    summary = "重新向框架查询当前已注入的进程",
                    onClick = { XposedServiceManager.refreshTargets() },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ==================== 外观 ====================
        item { SmallTitle("外观") }
        item {
            SettingsCard {
                ArrowPreference(
                    title = "主题",
                    summary = ThemeMode.labelOf(AppearanceState.themeMode),
                    onClick = { showThemeDialog = true },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
                ArrowPreference(
                    title = "主题色",
                    summary = if (AppearanceState.colorKey != 0L) "自定义" else "跟随主题",
                    onClick = { showColorDialog = true },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
                ArrowPreference(
                    title = "字体大小",
                    summary = "${AppearanceState.fontScale}%",
                    onClick = { showFontDialog = true },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ==================== 实验性功能 ====================
        item { SmallTitle("实验性功能") }
        item {
            SettingsCard {
                SwitchPreference(
                    title = "预测性返回动画",
                    summary = "子页面自下而上滑入、返回时跟手下滑（Miuix Y 轴转场）；关闭则立即切换",
                    checked = predictiveBack,
                    onCheckedChange = { FeatureState.updatePredictiveBack(it) },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
                SwitchPreference(
                    title = "模块热重载",
                    summary = "模块更新后尝试重载已注入进程，无需重启目标应用",
                    checked = hotReload,
                    onCheckedChange = {
                        hotReload = it
                        SettingsStore.hotReload = it
                    },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
                SwitchPreference(
                    title = "启动时提示 Hook 状态",
                    summary = "目标应用启动时用 Toast 显示本次装载结果（成功几个 / 失败几个）",
                    checked = toastOnStart,
                    onCheckedChange = {
                        toastOnStart = it
                        SettingsStore.toastOnStart = it
                    },
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ==================== 关于 ====================
        item { SmallTitle("关于") }
        item {
            SettingsCard {
                AboutRow("模块名称", "SpaceHook")
                AboutRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                AboutRow("Xposed API", "102")
                AboutRow("已注册 Hook", "${HookCatalog.hooks.size} 个")
                AboutRow(
                    "框架",
                    XposedServiceManager.framework.name.ifEmpty { "未连接" },
                )
            }
        }

        item {
            Text(
                text = "SpaceHook · 仅供学习与研究使用",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // ==================== 弹窗 ====================

    if (showThemeDialog) {
        OverlayDialog(
            title = "选择主题",
            show = showThemeDialog,
            onDismissRequest = { showThemeDialog = false },
        ) {
            Column {
                ThemeMode.entries.forEach { (mode, label) ->
                    TextButton(
                        text = if (AppearanceState.themeMode == mode) "✓  $label" else label,
                        onClick = {
                            AppearanceState.updateThemeMode(mode)
                            showThemeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showColorDialog) {
        OverlayDialog(
            title = "选择主题色",
            show = showColorDialog,
            onDismissRequest = { showColorDialog = false },
        ) {
            Column {
                ColorPicker(
                    color = if (AppearanceState.colorKey != 0L) {
                        Color(AppearanceState.colorKey.toInt())
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    // 用 RGBA 分量构造 0xAARRGGBB，避免直接操作 Color.value 的 64 位内部表示导致色值/亮度/透明度丢失
                    onColorChanged = { color ->
                        val argb = ((color.alpha * 255f).roundToInt() shl 24) or
                            ((color.red * 255f).roundToInt() shl 16) or
                            ((color.green * 255f).roundToInt() shl 8) or
                            (color.blue * 255f).roundToInt()
                        AppearanceState.updateColorKey(argb.toLong())
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "恢复默认",
                        onClick = {
                            AppearanceState.updateColorKey(0L)
                            showColorDialog = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "完成",
                        onClick = { showColorDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showFontDialog) {
        OverlayDialog(
            title = "字体大小",
            show = showFontDialog,
            onDismissRequest = { showFontDialog = false },
        ) {
            Column {
                Text(
                    text = "当前：${AppearanceState.fontScale}%",
                    style = MiuixTheme.textStyles.body1,
                )
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = AppearanceState.fontScale.toFloat(),
                    onValueChange = { AppearanceState.updateFontScale(it.toInt()) },
                    valueRange = 80f..160f,
                    steps = 15,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("80%", style = MiuixTheme.textStyles.footnote2)
                    Text("160%", style = MiuixTheme.textStyles.footnote2)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = "完成",
                    onClick = { showFontDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(vertical = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun AboutRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
