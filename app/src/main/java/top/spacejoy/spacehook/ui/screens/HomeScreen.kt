package top.spacejoy.spacehook.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.spacejoy.spacehook.BuildConfig
import top.spacejoy.spacehook.SettingsStore
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.data.ProcessRestarter
import top.spacejoy.spacehook.xposed.TargetInfo
import top.spacejoy.spacehook.xposed.XposedServiceManager
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 首页 —— 状态总览。
 *
 * 视觉语言参考 **InstallerX Revived** 的 Miuix 首页与 **HyperIsland**：
 * - 顶部一张**大状态卡**：按激活状态改变底色，右下角叠一枚超大低透明度图标，整卡可点击刷新
 * - 其下**两张等宽统计卡**：标题在上、数值在下
 * - 再往下是信息类卡片，统一用 `BasicComponent(title, summary)` 排列
 */
@Composable
fun HomeScreen() {
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick, XposedServiceManager.activated) {
        XposedServiceManager.refreshTargets()
    }

    val activated = XposedServiceManager.activated
    val targets = XposedServiceManager.targets

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ModuleStatusCard(activated = activated, onRefresh = { refreshTick++ })
        }

        item { StatCards(refreshTick = refreshTick, runningCount = targets.size) }

        item { SmallTitle("模块信息") }
        item { FrameworkCard() }

        item { ScopeCard(activated) }

        item { SmallTitle("设备信息") }
        item { DeviceCard() }

        item { SmallTitle("Hook 加载情况") }
        item { TargetsCard(activated, targets, refreshTick) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ==================== 大状态卡 ====================

/**
 * 模块激活状态大卡片。
 *
 * 设计要点（借鉴 InstallerX 的 `MiuixStatusGrid`）：
 * - 底色随状态切换：激活→绿色容器色，未激活→错误容器色
 * - 右下角一枚 170dp 的超大图标，`offset` 出卡片边缘形成视觉延伸
 * - 文字三级层次：20sp 标题 → 14sp 描述 → 14sp 辅助信息（alpha .8）
 * - 整卡可点击（点一下刷新状态），带 Tilt 按压反馈
 */
@Composable
private fun ModuleStatusCard(activated: Boolean, onRefresh: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val isDark = !MiuixTheme.isDynamicColor && isSystemInDarkThemeSafe()

    val containerColor = if (activated) {
        if (MiuixTheme.isDynamicColor) scheme.secondaryContainer
        else if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    } else {
        if (MiuixTheme.isDynamicColor) scheme.errorContainer
        else if (isDark) Color(0xFF381A1A) else Color(0xFFFAEEEE)
    }

    val textColor = if (activated) {
        if (MiuixTheme.isDynamicColor) scheme.onSecondaryContainer else scheme.onSurface
    } else {
        if (MiuixTheme.isDynamicColor) scheme.onErrorContainer else scheme.onSurface
    }
    val descColor = textColor.copy(alpha = 0.8f)

    // 图标用主题色而非文字色，保持视觉亮点
    val iconTint = (if (activated) scheme.primary else scheme.error).copy(alpha = 0.8f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = containerColor),
        onClick = onRefresh,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 右下角超大背景图标
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 50.dp, y = 38.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    imageVector = if (activated) MiuixIcons.Ok else MiuixIcons.Report,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(170.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (activated) "模块已激活" else "模块未激活",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (activated) {
                        "SpaceHook 已与 LSPosed 建立连接"
                    } else {
                        "请在 LSPosed 中启用本模块并勾选作用域"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = descColor,
                )

                Spacer(Modifier.height(36.dp))

                val framework = XposedServiceManager.framework
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (framework.isAvailable) {
                        "${framework.name} ${framework.version} · API ${framework.apiVersion}"
                    } else {
                        "点击刷新状态"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = descColor,
                )
            }
        }
    }
}

// ==================== 统计小卡 ====================

@Composable
private fun StatCards(refreshTick: Int, runningCount: Int) {
    val totalHooks = HookCatalog.hooks.size
    val activeApps = remember(refreshTick, runningCount) {
        val seen = SettingsStore.seenProcessNames()
        HookCatalog.hooks.count { hook ->
            hook.targetPackages.any { pkg -> seen.any { it == pkg || it.startsWith("$pkg:") } }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            title = "已注册功能",
            value = totalHooks.toString(),
        )
        StatCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            title = "已生效目标",
            value = activeApps.toString(),
        )
    }
}

@Composable
private fun StatCard(modifier: Modifier, title: String, value: String) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

// ==================== 信息卡片 ====================

/**
 * 模块信息卡片。
 *
 * 框架名称 / 版本 / Xposed API / 连接状态**已经在顶部大状态卡展示**
 * （「模块已激活」+「LSPosed 2.2.0-it · API 102」），这里不再重复，
 * 只保留顶部卡片没有的内容：模块自身版本与 Root 权限。
 */
@Composable
private fun FrameworkCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootState = ProcessRestarter.rootState

    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "模块版本",
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )

        // ---- Root 状态：点击检测 / 申请授权 ----
        BasicComponent(
            title = "Root 权限",
            summary = when (rootState) {
                ProcessRestarter.RootState.GRANTED -> "已授权"
                ProcessRestarter.RootState.DENIED -> "未授权 —— 点击申请"
                ProcessRestarter.RootState.CHECKING -> "检测中…"
                ProcessRestarter.RootState.UNKNOWN -> "点击检测（会弹出授权框）"
            },
            onClick = {
                if (rootState == ProcessRestarter.RootState.CHECKING) return@BasicComponent
                scope.launch {
                    val state = withContext(Dispatchers.IO) { ProcessRestarter.detectRoot() }
                    when (state) {
                        ProcessRestarter.RootState.GRANTED ->
                            toast(context, "✅ 已获得 root 权限")

                        ProcessRestarter.RootState.DENIED ->
                            toast(context, "⚠️ ${ProcessRestarter.rootDetail}")

                        else -> Unit
                    }
                }
            },
        )

        if (rootState == ProcessRestarter.RootState.DENIED &&
            ProcessRestarter.rootDetail.isNotEmpty()
        ) {
            BasicComponent(
                title = "提示",
                summary = ProcessRestarter.rootDetail,
            )
        }
    }
}

/**
 * 作用域卡片。
 *
 * 模块的 `scope.list` 只是**建议**（`staticScope=false`）。若用户没在 LSPosed 里
 * 勾选过作用域，框架会退化为「对所有应用生效」——功能虽然能用，但会给无关 App
 * 注入模块，既浪费性能也可能引发冲突。这里提供一键申请。
 */
@Composable
private fun ScopeCard(activated: Boolean) {
    val scope = XposedServiceManager.scope
    val expected = remember { HookCatalog.hookedPackages }
    val missing = remember(scope) { expected.filterNot { it in scope } }
    var requested by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "已授权作用域",
            summary = if (scope.isEmpty()) "未设置（对所有应用生效）" else "${scope.size} 个",
        )

        if (!activated) {
            BasicComponent(title = "说明", summary = "模块未激活，无法申请作用域")
            return@Card
        }

        if (missing.isNotEmpty()) {
            BasicComponent(
                title = "待授权目标",
                summary = missing.joinToString(", "),
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                TextButton(
                    text = if (requested) "已发送申请，请在 LSPosed 中确认" else "一键授权作用域",
                    onClick = {
                        requested = true
                        XposedServiceManager.requestScope(expected)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val message = XposedServiceManager.lastScopeMessage
        if (message.isNotEmpty()) {
            BasicComponent(title = "最近结果", summary = message)
        }
    }
}

@Composable
private fun DeviceCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(title = "设备型号", summary = "${Build.MANUFACTURER} ${Build.MODEL}")
        BasicComponent(
            title = "系统版本",
            summary = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        BasicComponent(title = "系统指纹", summary = Build.DISPLAY)
        BasicComponent(title = "ABI", summary = Build.SUPPORTED_ABIS.firstOrNull() ?: "—")
        BasicComponent(title = "安全模式", summary = if (SettingsStore.safeMode) "已开启" else "已关闭")
    }
}

/**
 * 已生效情况 —— 两组：
 * 1. **运行中**：LSPosed 当前报告的已注入进程（瞬态）
 * 2. **曾生效**：本地记录的进程名（录音机这类按需启动的 App 关闭后仍在）
 */
@Composable
private fun TargetsCard(
    activated: Boolean,
    targets: List<TargetInfo>,
    refreshTick: Int,
) {
    val historical = remember(refreshTick, targets) {
        val running = targets.map { it.processName }.toSet()
        SettingsStore.seenProcessNames() - running
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        if (!activated) {
            BasicComponent(title = "说明", summary = "模块未激活，无法获取已生效进程")
            return@Card
        }

        if (targets.isEmpty() && historical.isEmpty()) {
            BasicComponent(title = "已生效进程", summary = "暂无 —— 重启目标应用后出现")
            return@Card
        }

        if (targets.isNotEmpty()) {
            GroupLabel("运行中（${targets.size}）")
            targets.forEach { target ->
                BasicComponent(
                    title = target.processName,
                    summary = "PID ${target.pid} · ${stateLabel(target.state)}",
                )
            }
        }

        if (historical.isNotEmpty()) {
            GroupLabel("曾生效 · 当前未运行（${historical.size}）")
            historical.sorted().forEach { name ->
                BasicComponent(title = name, summary = "已记录")
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

private fun stateLabel(state: String): String = when (state) {
    "UP_TO_DATE" -> "已生效"
    "STALE" -> "需重载"
    "RELOADING" -> "重载中"
    "FAILED" -> "失败"
    else -> state
}

/** 供 [ModuleStatusCard] 判断深浅色（Miuix 非动态取色时用于挑固定色值） */
@Composable
private fun isSystemInDarkThemeSafe(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
