package top.spacejoy.spacehook.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.spacejoy.spacehook.SettingsStore
import top.spacejoy.spacehook.common.AppCategory
import top.spacejoy.spacehook.common.CC_ORDER_CARDS
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.common.HookMeta
import top.spacejoy.spacehook.common.HookParam
import top.spacejoy.spacehook.common.OrderCard
import top.spacejoy.spacehook.common.TargetApp
import top.spacejoy.spacehook.data.InstalledAppsProvider
import top.spacejoy.spacehook.data.ProcessRestarter
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Hook 列表页（一级）：
 *
 * - 「用户应用」入口**置顶**，其下是系统应用卡片
 * - 点「用户应用」→ 由导航宿主 push 二级整屏路由（[UserAppsScreen]）
 * - 点某个功能的「点按调整参数 / 进入磁贴选择 / 进入拖拽排序」→ push 三级整屏路由
 *
 * 本页只负责一级内容；页面切换（含预测性返回与 Y 轴转场）由 [top.spacejoy.spacehook.ui.SpaceHookNavHost]
 * 里的 Miuix `NavDisplay` 统一处理，因此这里不再有任何导航状态。
 * 右上角「批量重启」的入口与确认框都归 [top.spacejoy.spacehook.ui.MainScreen] 所有（按钮在它的标题栏里），
 * 本页不再持有触发状态 —— 否则切页签重建时会把旧状态重新弹出来。
 *
 * 每张应用卡片右上角提供**单独重启该应用**的按钮 ——
 * Hook 生效需要重启目标进程，但重启整个作用域（尤其系统界面）代价很大，
 * 因此支持只重启改动的那一个。
 *
 * 声明了 [HookMeta.params] 的功能行可点击：
 * - 普通参数 → 弹出参数配置对话框（芯片选择 / 步进器）
 * - [HookParam.CardOrder] → 进入仿控制中心的**长按拖拽排序页**
 */
@Composable
fun HookListScreen(
    onOpenUserApps: () -> Unit = {},
    onOpenConfig: (HookMeta) -> Unit = {},
) {
    SystemAppsScreen(
        onOpenConfig = onOpenConfig,
        onOpenUserApps = onOpenUserApps,
    )
}

// ==================== 一级：用户应用入口置顶 + 系统应用 ====================

@Composable
private fun SystemAppsScreen(
    onOpenConfig: (HookMeta) -> Unit,
    onOpenUserApps: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { InstalledAppsProvider.ensureLoaded(context) }

    val systemApps = remember { HookCatalog.systemApps }
    val userApps = remember { HookCatalog.userApps }
    val installed = remember { InstalledAppsProvider.allApps.map { it.packageName }.toSet() }
    val installedUserCount = remember(userApps, installed) {
        userApps.count { it.packageName in installed }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 用户应用入口（置顶） ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(0.dp),
            ) {
                ArrowPreference(
                    title = "用户应用",
                    summary = buildString {
                        append("${userApps.size} 个已配置 Hook")
                        if (installedUserCount > 0) {
                            append(" · 已安装 $installedUserCount 个")
                        }
                    },
                    onClick = onOpenUserApps,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }

        item {
            Text(
                text = "关闭应用总开关后，该应用下的全部 Hook 都不会加载。点右上角 ⟳ 可单独重启该应用。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        item { SmallTitle("系统应用") }

        items(systemApps, key = { it.packageName }) { app ->
            AppHookCard(app = app, installed = true, onOpenConfig = onOpenConfig)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ==================== 二级：用户应用 ====================

/**
 * 二级整屏页面：已配置 Hook 的用户应用。
 *
 * 由导航宿主 [top.spacejoy.spacehook.ui.SpaceHookNavHost] 作为**顶层路由**渲染，
 * 自带标题栏（含返回键），因此页面内容里不再需要「← 返回」卡片。
 */
@Composable
fun UserAppsScreen(
    onOpenConfig: (HookMeta) -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { InstalledAppsProvider.ensureLoaded(context) }

    val installed = remember { InstalledAppsProvider.allApps.map { it.packageName }.toSet() }

    // 已安装的置顶，其余按名称排序
    val apps = remember(installed) {
        HookCatalog.userApps.sortedWith(
            compareByDescending<TargetApp> { it.packageName in installed }
                .thenBy { it.displayName }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "共 ${apps.size} 个已配置 Hook，已安装 ${apps.count { it.packageName in installed }} 个",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (apps.isEmpty()) {
            item {
                Text(
                    text = "还没有为任何用户应用配置 Hook。新增功能后在 HookCatalog 中登记即可出现在这里。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        items(apps, key = { it.packageName }) { app ->
            AppHookCard(app = app, installed = app.packageName in installed, onOpenConfig = onOpenConfig)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ==================== 应用卡片 ====================

@Composable
private fun AppHookCard(
    app: TargetApp,
    installed: Boolean,
    onOpenConfig: (HookMeta) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var appEnabled by remember { mutableStateOf(SettingsStore.isAppEnabled(app.packageName)) }
    var expanded by rememberSaveable(app.packageName) { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }

    val hooks = remember(app.packageName) { HookCatalog.hooksFor(app.packageName) }

    // ---- 重启确认弹窗（OverlayDialog 必须位于 Scaffold 内，这里由 MainScreen 提供）----
    if (showRestartDialog) {
        OverlayDialog(
            title = "重启应用",
            show = showRestartDialog,
            onDismissRequest = { showRestartDialog = false },
        ) {
            Column {
                Text(
                    text = "将强制停止「${app.displayName}」的进程，使其中的 Hook 立即重新加载。",
                    style = MiuixTheme.textStyles.body2,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = app.packageName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (app.packageName == "com.android.systemui") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "⚠️ 重启系统界面会让状态栏与锁屏短暂重建，可能出现瞬间闪烁。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { showRestartDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = if (restarting) "重启中…" else "确认重启",
                        onClick = {
                            showRestartDialog = false
                            if (restarting) return@TextButton
                            restarting = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ProcessRestarter.restartByRoot(app.packageName)
                                }
                                restarting = false
                                when (result) {
                                    is ProcessRestarter.RestartResult.Success ->
                                        toast(context, "✅ 已重启 ${app.displayName}")

                                    is ProcessRestarter.RestartResult.Failed -> {
                                        toast(context, "⚠️ ${result.detail}")
                                        if (result.kind == ProcessRestarter.Failure.NO_ROOT_PERMISSION) {
                                            // 回退：打开详情页让用户手动「强行停止」
                                            ProcessRestarter.openAppDetails(context, app.packageName)
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hooks.isNotEmpty()) { expanded = !expanded }
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = app.displayName, style = MiuixTheme.textStyles.title3)
                    Spacer(Modifier.width(6.dp))
                    if (app.category == AppCategory.SYSTEM) {
                        TagChip("系统")
                    } else {
                        TagChip(if (installed) "已安装" else "未安装")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = app.packageName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (app.note.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = app.note,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${hooks.size} 个功能",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            // ---- 单独重启该应用（点击先弹确认框）----
            IconButton(
                onClick = { if (!restarting) showRestartDialog = true },
            ) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = "重启 ${app.displayName}",
                    tint = if (restarting) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }

            Switch(
                checked = appEnabled,
                onCheckedChange = {
                    appEnabled = it
                    SettingsStore.setAppEnabled(app.packageName, it)
                },
            )
        }

        if (expanded) {
            HorizontalDivider()
            hooks.forEach { hook ->
                HookRow(hook = hook, appEnabled = appEnabled, onOpenConfig = onOpenConfig)
            }
        }
    }
}

@Composable
private fun HookRow(
    hook: HookMeta,
    appEnabled: Boolean,
    onOpenConfig: (HookMeta) -> Unit,
) {
    var enabled by remember(hook.id) {
        mutableStateOf(SettingsStore.isHookEnabled(hook.id, hook.defaultEnabled))
    }
    var showParams by remember(hook.id) { mutableStateOf(false) }

    val hasParams = hook.params.isNotEmpty()
    val hasCardOrder = hook.params.any { it is HookParam.CardOrder }
    val hasMultiSelect = hook.params.any { it is HookParam.MultiSelect }
    // 走独立页面：拖拽排序、磁贴多选等「页面型」参数
    val opensScreen = hasCardOrder || hasMultiSelect

    if (showParams) {
        HookParamsDialog(hook = hook, onDismiss = { showParams = false })
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .let {
                        if (hasParams) it.clickable {
                            if (opensScreen) onOpenConfig(hook) else showParams = true
                        } else it
                    }
                    .padding(horizontal = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = hook.name, style = MiuixTheme.textStyles.body1)
                    if (hook.experimental) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("实验")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hook.description,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (hasParams) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            hasCardOrder -> "⚙ 点按进入拖拽排序"
                            hasMultiSelect -> "⚙ 点按进入磁贴选择"
                            else -> "⚙ 点按调整参数"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled && appEnabled,
                enabled = appEnabled,
                onCheckedChange = {
                    enabled = it
                    SettingsStore.setHookEnabled(hook.id, it)
                },
            )
        }
        HorizontalDivider()
    }
}

// ==================== 参数配置弹窗 ====================

@Composable
private fun HookParamsDialog(hook: HookMeta, onDismiss: () -> Unit) {
    val simpleParams = hook.params.filterNot { it is HookParam.CardOrder }
    OverlayDialog(
        title = "参数设置 · ${hook.name}",
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            simpleParams.forEach { param ->
                when (param) {
                    is HookParam.Select -> SelectParamRow(hook.id, param)
                    is HookParam.IntRange -> RangeParamRow(hook.id, param)
                    is HookParam.MultiSelect -> MultiSelectParamRow(hook.id, param)
                    is HookParam.CardOrder -> Unit
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "参数实时生效：调整后重新展开控制中心即可看到变化。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun SelectParamRow(hookId: String, param: HookParam.Select) {
    var value by remember(hookId, param.key) {
        mutableStateOf(SettingsStore.getConfigInt(hookId, param.key, param.default))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = param.title, style = MiuixTheme.textStyles.body1)
        if (param.summary.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = param.summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            param.options.forEach { option ->
                val selected = option.value == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary else Color.Transparent
                        )
                        .clickable {
                            value = option.value
                            SettingsStore.setConfigInt(hookId, param.key, option.value)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = option.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (selected) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeParamRow(hookId: String, param: HookParam.IntRange) {
    var value by remember(hookId, param.key) {
        mutableStateOf(SettingsStore.getConfigInt(hookId, param.key, param.default))
    }

    fun update(next: Int) {
        val clamped = next.coerceIn(param.min, param.max)
        value = clamped
        SettingsStore.setConfigInt(hookId, param.key, clamped)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = param.title, style = MiuixTheme.textStyles.body1)
                if (param.summary.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = param.summary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            TextButton(text = "−", onClick = { update(value - param.step) })
            Box(
                modifier = Modifier.width(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "$value", style = MiuixTheme.textStyles.body2)
            }
            TextButton(text = "＋", onClick = { update(value + param.step) })
        }
        TextButton(
            text = "恢复默认（${param.default}）",
            onClick = { update(param.default) },
        )
    }
}

@Composable
private fun MultiSelectParamRow(hookId: String, param: HookParam.MultiSelect) {
    var selected by remember(hookId, param.key) {
        mutableStateOf(
            SettingsStore.getConfigString(hookId, param.key, null)
                ?.split(param.separator)
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        )
    }
    var customInput by remember { mutableStateOf("") }

    val presetValues = param.options.map { it.value }.toSet()
    val customSelected = selected.filter { it !in presetValues }

    fun toggle(value: String) {
        val next = if (value in selected) selected - value else selected + value
        selected = next
        SettingsStore.setConfigString(hookId, param.key, next.joinToString(param.separator))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = param.title, style = MiuixTheme.textStyles.body1)
        if (param.summary.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = param.summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))

        // ---- 预设芯片 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            param.options.forEach { option ->
                SelectChip(
                    label = option.label,
                    selected = option.value in selected,
                    onClick = { toggle(option.value) },
                )
            }
        }

        // ---- 自定义项芯片 ----
        if (customSelected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                customSelected.forEach { value ->
                    SelectChip(
                        label = "✕ $value",
                        selected = true,
                        onClick = { toggle(value) },
                    )
                }
            }
        }

        // ---- 自定义输入 ----
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = customInput,
                onValueChange = { customInput = it },
                label = "自定义 spec 名，如 hotspot",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = "添加",
                onClick = {
                    val v = customInput.trim()
                    if (v.isNotEmpty()) {
                        toggle(v)
                        customInput = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (selected) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ==================== 磁贴卡片自定义内容页 ====================

/**
 * 磁贴卡片自定义内容的独立设置页（参考 HyperStar「卡片磁贴编辑」）。
 *
 * - **已选磁贴**：长按拖动排序（顺序即控制中心大磁贴的展示顺序），点 ✕ 移除
 * - **可选磁贴**：点按添加到已选（已添加的置灰不可重复添加）
 * - **自定义磁贴**：手输任意磁贴 spec 名
 * - 改动即时保存；由导航宿主作为整屏路由渲染（标题栏自带返回键 + 侧滑/下滑返回）
 */
@Composable
fun TilePickerScreen(hook: HookMeta) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hookId = hook.id

    val param = remember(hook) { hook.params.filterIsInstance<HookParam.MultiSelect>().firstOrNull() }
    val options = param?.options ?: emptyList()
    val separator = param?.separator ?: "|"
    val configKey = param?.key ?: "tiles"

    // 已选磁贴（有序列表，顺序即展示顺序）
    var selectedTiles by remember {
        mutableStateOf(
            SettingsStore.getConfigString(hookId, configKey, null)
                ?.split(separator)?.filter { it.isNotEmpty() }
                ?: emptyList()
        )
    }
    var customInput by remember { mutableStateOf("") }
    var restarting by remember { mutableStateOf(false) }

    val nameOf: (String) -> String = { spec ->
        options.firstOrNull { it.value == spec }?.label ?: spec
    }

    fun persist(list: List<String>) {
        selectedTiles = list
        SettingsStore.setConfigString(hookId, configKey, list.joinToString(separator))
    }

    fun move(from: Int, to: Int) {
        if (from == to || to < 0 || to >= selectedTiles.size) return
        persist(selectedTiles.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun addTile(spec: String) {
        val v = spec.trim()
        if (v.isEmpty() || v in selectedTiles) return
        persist(selectedTiles + v)
    }

    // ---- 拖拽状态 ----
    var draggingSpec by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    val currentTiles by rememberUpdatedState(selectedTiles)
    val currentMove by rememberUpdatedState { f: Int, t: Int -> move(f, t) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "已选磁贴按顺序展示在控制中心顶部。长按拖动可调整顺序，点 ✕ 移除；留空 = 跟随系统。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )

        // ==================== 已选磁贴（可拖拽排序） ====================
        Spacer(Modifier.height(8.dp))
        SmallTitle("已选磁贴（${selectedTiles.size}）")
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Column {
                if (selectedTiles.isEmpty()) {
                    Text(
                        text = "还没有选择磁贴，留空将跟随系统。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
                currentTiles.forEachIndexed { index, spec ->
                    val isDragging = spec == draggingSpec
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else 0f
                                shadowElevation = if (isDragging) 24f else 0f
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isDragging) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .pointerInput(spec) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingSpec = spec
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        var idx = currentTiles.indexOf(draggingSpec)
                                        while (idx >= 0) {
                                            if (dragOffset > rowHeightPx && idx < currentTiles.lastIndex) {
                                                currentMove(idx, idx + 1)
                                                dragOffset -= rowHeightPx
                                                idx += 1
                                            } else if (dragOffset < -rowHeightPx && idx > 0) {
                                                currentMove(idx, idx - 1)
                                                dragOffset += rowHeightPx
                                                idx -= 1
                                            } else {
                                                break
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingSpec = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingSpec = null
                                        dragOffset = 0f
                                    },
                                )
                            }
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "≡",
                            style = MiuixTheme.textStyles.title3,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = nameOf(spec), style = MiuixTheme.textStyles.body1)
                            Text(
                                text = spec,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = "✕",
                            onClick = {
                                persist(selectedTiles.toMutableList().apply { remove(spec) })
                            },
                        )
                    }
                    if (index != currentTiles.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }

        // ==================== 可选磁贴 ====================
        Spacer(Modifier.height(16.dp))
        SmallTitle("可选磁贴")
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Column {
                options.forEachIndexed { index, option ->
                    val added = option.value in selectedTiles
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !added) { addTile(option.value) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MiuixTheme.textStyles.body1,
                                color = if (added) MiuixTheme.colorScheme.onSurfaceVariantSummary else Color.Unspecified,
                            )
                            Text(
                                text = option.value,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            text = if (added) "已添加" else "＋ 添加",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (added) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                MiuixTheme.colorScheme.primary
                            },
                        )
                    }
                    if (index != options.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }

        // ==================== 自定义磁贴 ====================
        Spacer(Modifier.height(16.dp))
        SmallTitle("自定义磁贴")
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = "spec 名，如 hotspot",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "添加",
                    onClick = {
                        addTile(customInput)
                        customInput = ""
                    },
                )
            }
        }

        // ==================== 生效 ====================
        Spacer(Modifier.height(16.dp))
        TextButton(
            text = if (restarting) "重启中…" else "重启系统界面生效",
            onClick = {
                if (restarting) return@TextButton
                restarting = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ProcessRestarter.restartByRoot("com.android.systemui")
                    }
                    restarting = false
                    when (result) {
                        is ProcessRestarter.RestartResult.Success ->
                            toast(context, "✅ 已重启系统界面")

                        is ProcessRestarter.RestartResult.Failed ->
                            toast(context, "⚠️ ${result.detail}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ==================== 控制中心卡片拖拽排序页 ====================

/**
 * 仿控制中心的卡片排序编辑页（HyperStar 同款交互）。
 *
 * - 长按卡片上下拖动排序；也提供 ↑/↓ 按钮微调
 * - 每次调整立即持久化（按顺序写入 20、22、24… 的连续优先级）
 * - 「恢复默认」清空全部配置，回到系统原生顺序
 * - 由导航宿主作为整屏路由渲染（标题栏自带返回键 + 侧滑/下滑返回）
 */
@Composable
fun CardOrderScreen(hook: HookMeta) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hookId = hook.id

    fun effectivePriority(card: OrderCard): Int {
        val configured = SettingsStore.getConfigInt(hookId, card.key, -1)
        return if (configured >= 0) configured else card.nativePriority
    }

    var cards by remember {
        mutableStateOf(CC_ORDER_CARDS.sortedBy { effectivePriority(it) })
    }
    var restarting by remember { mutableStateOf(false) }

    fun persist() {
        cards.forEachIndexed { index, card ->
            SettingsStore.setConfigInt(hookId, card.key, 20 + index * 2)
        }
    }

    fun move(from: Int, to: Int) {
        if (from == to || to < 0 || to >= cards.size) return
        cards = cards.toMutableList().apply { add(to, removeAt(from)) }
        persist()
    }

    fun resetOrder() {
        CC_ORDER_CARDS.forEach { card ->
            SettingsStore.setConfigInt(hookId, card.key, -1)
        }
        cards = CC_ORDER_CARDS.toList()
    }

    // ---- 拖拽状态 ----
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeight = 64.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    val currentCards by rememberUpdatedState(cards)
    val currentMove by rememberUpdatedState { from: Int, to: Int -> move(from, to) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "长按卡片拖动即可排序（优先级数值越小越靠上）。系统原生顺序：" +
                "磁贴卡片 → 媒体 → 亮度 → 音量 → 融合设备中心 → 设备控制 → 磁贴列表 → 编辑按钮。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp),
        ) {
            Column {
                currentCards.forEachIndexed { index, card ->
                    val isDragging = card.key == draggingKey
                    val priority = effectivePriority(card)
                    val isCustom = SettingsStore.getConfigInt(hookId, card.key, -1) >= 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else 0f
                                shadowElevation = if (isDragging) 24f else 0f
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isDragging) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .pointerInput(card.key) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingKey = card.key
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        var idx = currentCards.indexOfFirst { it.key == draggingKey }
                                        while (idx >= 0) {
                                            if (dragOffset > rowHeightPx && idx < currentCards.lastIndex) {
                                                currentMove(idx, idx + 1)
                                                dragOffset -= rowHeightPx
                                                idx += 1
                                            } else if (dragOffset < -rowHeightPx && idx > 0) {
                                                currentMove(idx, idx - 1)
                                                dragOffset += rowHeightPx
                                                idx -= 1
                                            } else {
                                                break
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingKey = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        dragOffset = 0f
                                    },
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "≡",
                            style = MiuixTheme.textStyles.title3,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = card.name, style = MiuixTheme.textStyles.body1)
                            Text(
                                text = if (isCustom) "优先级 $priority" else "默认 · $priority",
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (isCustom) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(text = "↑", onClick = { move(index, index - 1) })
                        TextButton(text = "↓", onClick = { move(index, index + 1) })
                    }

                    if (index != currentCards.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "恢复默认排序",
                onClick = { resetOrder() },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = if (restarting) "重启中…" else "重启系统界面生效",
                onClick = {
                    if (restarting) return@TextButton
                    restarting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ProcessRestarter.restartByRoot("com.android.systemui")
                        }
                        restarting = false
                        when (result) {
                            is ProcessRestarter.RestartResult.Success ->
                                toast(context, "✅ 已重启系统界面")

                            is ProcessRestarter.RestartResult.Failed ->
                                toast(context, "⚠️ ${result.detail}")
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ==================== 批量重启对话框 ====================

/**
 * 多选批量重启指定的一批应用（一级页面用 [HookCatalog.systemApps]，二级页面用 [HookCatalog.userApps]）。
 *
 * - 顶部提供「全选 / 全不选」切换
 * - 逐项勾选后一次性重启（每个应用独立调用 [ProcessRestarter.restartByRoot]）
 * - 重启系统界面会短暂闪烁，已在说明中提示
 */
@Composable
internal fun BatchRestartDialog(apps: List<TargetApp>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(apps.map { it.packageName }.toSet()) }
    var restarting by remember { mutableStateOf(false) }

    val allSelected = apps.isNotEmpty() && selected.size == apps.size

    fun toggle(packageName: String) {
        selected = if (packageName in selected) selected - packageName else selected + packageName
    }

    OverlayDialog(
        title = "批量重启应用",
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = "勾选要重启的应用，重启后其中的 Hook 会立即重新加载。",
                style = MiuixTheme.textStyles.body2,
            )
            if (apps.any { it.packageName == "com.android.systemui" }) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠️ 重启「系统界面」会让状态栏、控制中心、锁屏短暂重建。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            // 全选 / 全不选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = if (allSelected) emptySet() else apps.map { it.packageName }.toSet()
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    state = if (allSelected) ToggleableState.On else ToggleableState.Off,
                    onClick = {
                        selected = if (allSelected) emptySet() else apps.map { it.packageName }.toSet()
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (allSelected) "全不选" else "全选（${apps.size}）",
                    style = MiuixTheme.textStyles.body1,
                )
            }

            HorizontalDivider()

            apps.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggle(app.packageName) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (app.packageName in selected) ToggleableState.On else ToggleableState.Off,
                        onClick = { toggle(app.packageName) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = app.displayName, style = MiuixTheme.textStyles.body1)
                        Text(
                            text = app.packageName,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = if (restarting) "重启中…" else "重启 ${selected.size} 个",
                    onClick = {
                        if (restarting || selected.isEmpty()) return@TextButton
                        restarting = true
                        scope.launch {
                            val targets = apps.filter { it.packageName in selected }
                            var success = 0
                            var failed = 0
                            targets.forEach { app ->
                                val result = withContext(Dispatchers.IO) {
                                    ProcessRestarter.restartByRoot(app.packageName)
                                }
                                if (result is ProcessRestarter.RestartResult.Success) success++ else failed++
                            }
                            restarting = false
                            val msg = buildString {
                                append("✅ 重启成功 $success 个")
                                if (failed > 0) append("，失败 $failed 个")
                            }
                            toast(context, msg)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

// ==================== 小工具 ====================

@Composable
private fun TagChip(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.primary,
    )
}

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
