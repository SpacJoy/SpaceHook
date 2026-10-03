package top.spacejoy.spacehook.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 整屏参数配置页（仿「控制中心卡片排序」的交互形态）。
 *
 * 与 [HookParamsDialog]（列表内联弹窗）的区别：
 * - **整屏路由**，自带标题栏与返回手势，长参数列表不再挤在弹窗里滚动；
 * - 每项参数占一整行，左侧标题 + 说明、右侧当前取值，**点按展开**候选列表（6 个以上选项时不会横向溢出）；
 * - 数值型参数用 −/＋ 步进器就地调整；
 * - 底部提供「恢复默认」（一次重置本 Hook 的全部参数）与「重启目标应用生效」。
 *
 * 参数声明仍来自 [HookMeta.params]，本页只负责渲染与读写 `cfg::<hookId>::<key>`，
 * 因此新增参数不需要改动这里。
 *
 * 由导航宿主 [top.spacejoy.spacehook.ui.SpaceHookNavHost] 作为整屏路由渲染；
 * 带 [HookParam.FullPage] 标记的 Hook 才会走到这里。
 */
@Composable
fun HookParamsScreen(hook: HookMeta) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 路由标记不参与渲染
    val params = remember(hook) {
        hook.params.filterNot { it is HookParam.CardOrder || it is HookParam.FullPage }
    }

    // 展开中的参数键（手风琴：同时只展开一项）
    var expandedKey by rememberSaveable(hook.id) { mutableStateOf<String?>(null) }
    // 「恢复默认」后自增，作为各行 remember 的 key 强制重新读取存储值
    var resetSignal by remember(hook.id) { mutableStateOf(0) }
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

    fun resetAll() {
        params.forEach { param ->
            when (param) {
                is HookParam.Select -> SettingsStore.setConfigInt(hook.id, param.key, param.default)
                is HookParam.IntRange -> SettingsStore.setConfigInt(hook.id, param.key, param.default)
                is HookParam.MultiSelect -> Unit
                is HookParam.CardOrder -> Unit
                is HookParam.FullPage -> Unit
            }
        }
        expandedKey = null
        resetSignal += 1
        toast(context, "已恢复默认参数")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "点按任意一项即可调整，改动立即保存。点「−/＋」调整数值。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        SmallTitle("参数")
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Column {
                params.forEachIndexed { index, param ->
                    when (param) {
                        is HookParam.Select -> SelectBlock(
                            hookId = hook.id,
                            param = param,
                            resetSignal = resetSignal,
                            expanded = expandedKey == param.key,
                            onToggle = {
                                expandedKey = if (expandedKey == param.key) null else param.key
                            },
                        )

                        is HookParam.IntRange -> RangeBlock(
                            hookId = hook.id,
                            param = param,
                            resetSignal = resetSignal,
                        )

                        // 多选型参数（磁贴内容）走 TilePickerScreen，不会出现在本页
                        is HookParam.MultiSelect -> Unit
                        is HookParam.CardOrder -> Unit
                        is HookParam.FullPage -> Unit
                    }
                    if (index != params.lastIndex) {
                        HorizontalDivider()
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
}

/**
 * 单选参数行：点按展开候选列表（竖直排列 + ✓ 标记当前值）。
 *
 * 竖直展开而不是横排芯片，是为了让「关闭 / 功耗 / 电流 / CPU 温度 / 电池温度 / 网速」
 * 这类 5 项以上的候选不会在窄屏上横向溢出。
 */
@Composable
private fun SelectBlock(
    hookId: String,
    param: HookParam.Select,
    resetSignal: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    var value by remember(hookId, param.key, resetSignal) {
        mutableStateOf(SettingsStore.getConfigInt(hookId, param.key, param.default))
    }
    val currentLabel = param.options.firstOrNull { it.value == value }?.label ?: "—"

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "paramArrow",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            Spacer(Modifier.width(12.dp))
            Text(
                text = currentLabel,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = if (expanded) "收起${param.title}" else "展开${param.title}",
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            ) {
                param.options.forEach { option ->
                    val selected = option.value == value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable {
                                value = option.value
                                SettingsStore.setConfigInt(hookId, param.key, option.value)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
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

/** 数值参数行：标题 + 说明在左，−/＋ 步进器在右 */
@Composable
private fun RangeBlock(
    hookId: String,
    param: HookParam.IntRange,
    resetSignal: Int,
) {
    var value by remember(hookId, param.key, resetSignal) {
        mutableStateOf(SettingsStore.getConfigInt(hookId, param.key, param.default))
    }

    fun update(next: Int) {
        val clamped = next.coerceIn(param.min, param.max)
        value = clamped
        SettingsStore.setConfigInt(hookId, param.key, clamped)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            modifier = Modifier.width(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "$value", style = MiuixTheme.textStyles.body2)
        }
        TextButton(text = "＋", onClick = { update(value + param.step) })
    }
}

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
