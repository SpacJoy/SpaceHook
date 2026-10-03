package top.spacejoy.spacehook.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.spacejoy.spacehook.SettingsStore
import top.spacejoy.spacehook.common.Constants
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.common.HookMeta
import top.spacejoy.spacehook.common.HookParam
import top.spacejoy.spacehook.data.ProcessRestarter
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 整屏参数配置页 —— 形态参考 HyperCeiler 的功能设置页（`PreferenceScreen`）。
 *
 * 页面结构自上而下：
 * 1. **总开关卡**：功能名 + 说明 + 右侧 `Switch`（对应 HyperCeiler 页首那个无标题分类）；
 * 2. **分组卡**：按 [HookParam.group] 分组，每组一个小标题 + 一张卡；
 *    组内每项按类型渲染成 **开关行 / 下拉行 / 滑条行**；
 * 3. **底部动作**：「恢复默认」与「重启目标应用生效」。
 *
 * 与 HyperCeiler 的差异（有意为之）：
 * - 用 Miuix 的 Compose 控件实现，不引入其 `fan.preference.*` 依赖；
 * - 电池信息页保留本模块既有的「4 槽位自选指标」模型，
 *   而不是 HyperCeiler 那种「预设组合下拉」—— 前者可自由组合、表达力更强。
 *
 * 参数声明全部来自 [HookMeta.params]，本页只负责渲染与读写 `cfg::<hookId>::<key>`，
 * 因此新增参数不需要改动这里。
 *
 * 由导航宿主 [top.spacejoy.spacehook.ui.SpaceHookNavHost] 作为整屏路由渲染；
 * 带 [HookParam.FullPage] 标记的 Hook 才会走到这里。
 */
@Composable
fun HookParamsScreen(hook: HookMeta) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 路由标记与不支持的形态不参与渲染
    val params = remember(hook) {
        hook.params.filterNot { it is HookParam.CardOrder || it is HookParam.FullPage }
    }

    // 全页共用的取值表：既是数据源，也让 dependsOn 能跨行联动
    val values = remember(hook.id) {
        mutableStateMapOf<String, Int>().apply {
            params.forEach { put(it.key, SettingsStore.getConfigInt(hook.id, it.key, it.default)) }
        }
    }
    var hookEnabled by remember(hook.id) {
        mutableStateOf(SettingsStore.isHookEnabled(hook.id, hook.defaultEnabled))
    }
    var openSelectKey by remember(hook.id) { mutableStateOf<String?>(null) }
    var restarting by remember { mutableStateOf(false) }

    // 重启目标：跳过 system_server 与通配作用域（这两者无法单独重启）
    val target = remember(hook) {
        hook.targetPackages.firstOrNull {
            it != Constants.TARGET_SYSTEM && it != Constants.TARGET_WILDCARD_ALL
        }
    }
    val targetName = remember(target) {
        target?.let { pkg ->
            HookCatalog.targetApps.firstOrNull { it.packageName == pkg }?.displayName ?: pkg
        }
    }

    fun put(key: String, value: Int) {
        values[key] = value
        SettingsStore.setConfigInt(hook.id, key, value)
    }

    fun isRowEnabled(param: HookParam): Boolean =
        param.dependsOn.isEmpty() || (values[param.dependsOn] ?: 0) != 0

    fun resetAll() {
        params.forEach { param ->
            when (param) {
                is HookParam.Toggle -> put(param.key, param.default)
                is HookParam.Select -> put(param.key, param.default)
                is HookParam.IntRange -> put(param.key, param.default)
                is HookParam.MultiSelect -> Unit
                is HookParam.CardOrder -> Unit
                is HookParam.FullPage -> Unit
            }
        }
        openSelectKey = null
        toast(context, "已恢复默认参数")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // ① 总开关卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = hook.name, style = MiuixTheme.textStyles.body1)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = hook.description,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = hookEnabled,
                    onCheckedChange = { checked ->
                        hookEnabled = checked
                        SettingsStore.setHookEnabled(hook.id, checked)
                    },
                )
            }
        }

        // ② 分组卡（groupBy 保留声明顺序）
        params.groupBy { it.group }.forEach { (group, items) ->
            Spacer(Modifier.height(16.dp))
            SmallTitle(group.ifEmpty { "参数" })
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column {
                    items.forEachIndexed { index, param ->
                        val enabled = isRowEnabled(param)
                        when (param) {
                            is HookParam.Toggle -> ToggleRow(
                                param = param,
                                checked = (values[param.key] ?: param.default) != 0,
                                enabled = enabled,
                                onChange = { put(param.key, if (it) 1 else 0) },
                            )

                            is HookParam.Select -> SelectRow(
                                param = param,
                                current = values[param.key] ?: param.default,
                                enabled = enabled,
                                onClick = { openSelectKey = param.key },
                            )

                            is HookParam.IntRange -> RangeRow(
                                param = param,
                                value = values[param.key] ?: param.default,
                                enabled = enabled,
                                onChange = { put(param.key, it) },
                            )

                            // 多选型参数（磁贴内容）走 TilePickerScreen，不会出现在本页
                            is HookParam.MultiSelect -> Unit
                            is HookParam.CardOrder -> Unit
                            is HookParam.FullPage -> Unit
                        }
                        if (index != items.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "恢复默认",
                onClick = { resetAll() },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = when {
                    restarting -> "重启中…"
                    target == null -> "需重启设备生效"
                    else -> "重启${targetName}生效"
                },
                onClick = {
                    if (restarting || target == null) return@TextButton
                    restarting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ProcessRestarter.restartByRoot(target)
                        }
                        restarting = false
                        when (result) {
                            is ProcessRestarter.RestartResult.Success ->
                                toast(context, "✅ 已重启${targetName}")

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

    // 下拉候选对话框
    openSelectKey?.let { key ->
        val param = params.firstOrNull { it.key == key } as? HookParam.Select
        if (param == null) {
            openSelectKey = null
        } else {
            OverlayDialog(
                title = param.title,
                show = true,
                onDismissRequest = { openSelectKey = null },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    param.options.forEach { option ->
                        val selected = option.value == (values[param.key] ?: param.default)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    put(param.key, option.value)
                                    openSelectKey = null
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.label,
                                style = MiuixTheme.textStyles.body2,
                                color = if (selected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = "✓",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 开关行：标题 + 说明在左，`Switch` 在右 */
@Composable
private fun ToggleRow(
    param: HookParam.Toggle,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParamLabel(param)
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
        )
    }
}

/** 下拉行：标题 + 说明在左，当前取值 + 箭头在右；点按弹出候选对话框 */
@Composable
private fun SelectRow(
    param: HookParam.Select,
    current: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParamLabel(param)
        Spacer(Modifier.width(12.dp))
        Text(
            text = param.options.firstOrNull { it.value == current }?.label ?: "—",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = "选择${param.title}",
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 滑条行：标题 + 说明 + 当前值在上，滑条在下 */
@Composable
private fun RangeRow(
    param: HookParam.IntRange,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ParamLabel(param)
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatRangeValue(param, value),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = (value - param.min).toFloat(),
            onValueChange = { if (enabled) onChange(snap(param, it)) },
            valueRange = 0f..(param.max - param.min).toFloat(),
            steps = stepsOf(param),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 行左侧的标题 + 说明（须在 Row 内调用，靠 [RowScope] 撑满剩余宽度） */
@Composable
private fun RowScope.ParamLabel(param: HookParam) {
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
}

/** 把滑条的浮点位置吸附到 [HookParam.IntRange.step] 的整数刻度上 */
private fun snap(param: HookParam.IntRange, raw: Float): Int {
    if (param.step <= 0) return param.min
    val offset = (raw / param.step).roundToInt() * param.step
    return (param.min + offset).coerceIn(param.min, param.max)
}

/** Compose `Slider` 的 steps 语义是「两端点之间的刻度数」，比区间数少 1 */
private fun stepsOf(param: HookParam.IntRange): Int {
    val span = param.max - param.min
    if (param.step <= 0 || span <= 0) return 0
    return ((span / param.step) - 1).coerceAtLeast(0)
}

/** 当前值文案；区间跨 0 时正数带 `+` 号，避免「上下偏移 4」看不出方向 */
private fun formatRangeValue(param: HookParam.IntRange, value: Int): String {
    val sign = if (param.min < 0 && value > 0) "+" else ""
    return "$sign$value${param.unit}"
}

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
