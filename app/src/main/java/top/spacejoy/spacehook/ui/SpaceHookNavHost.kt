package top.spacejoy.spacehook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.spacejoy.spacehook.FeatureState
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.common.HookParam
import top.spacejoy.spacehook.ui.screens.BatchRestartDialog
import top.spacejoy.spacehook.ui.screens.CardOrderScreen
import top.spacejoy.spacehook.ui.screens.TilePickerScreen
import top.spacejoy.spacehook.ui.screens.UserAppsScreen
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶层路由。
 *
 * 一级页面（底部导航三页签）是 [Main]；二级「用户应用」与三级「功能配置」都是**整屏路由**，
 * 各自带自己的 Miuix `Scaffold`（含标题栏），因此子页面是**覆盖整个屏幕的顶层图层**，
 * 而不是嵌在某页容器内部做局部切换。
 */
sealed interface Route : NavKey {
    /** 一级：首页 / Hook / 设置 三页签外壳 */
    data object Main : Route

    /** 二级：已配置 Hook 的用户应用列表 */
    data object UserApps : Route

    /** 三级：某个功能的配置页（磁贴选择 / 磁贴卡片排序等） */
    data class HookConfig(val hookId: String) : Route
}

/**
 * 应用导航宿主（由 [top.spacejoy.spacehook.MainActivity] 直接调用）。
 *
 * 使用 Miuix 官方导航库 `miuix-nav`：
 * - [NavDisplay] 自己渲染整屏图层，并**内置预测性返回 / 边缘侧滑返回**（无需自己实现进度动画）；
 * - 转场用库自带的 **Y 轴动画** [NavTransitions.Modal]（新页面自下而上滑入、返回时下滑）；
 * - `entry(swipeDismiss = TopToBottom)` 让子页面可以直接**下滑关闭**，与 Y 轴位移一致。
 *
 * 「设置 → 实验性功能 → 预测性返回动画」关闭时改用 [NavTransitions.None]（立即切换）。
 */
@Composable
fun SpaceHookNavHost() {
    // 路由栈只在进程内保留（不引入 kotlinx.serialization）；Activity 已声明 configChanges，
    // 旋转/深色切换不会重建，因此无需序列化保存。
    val nav = remember { NavController(navBackStackOf(Route.Main)) }
    // 用 Compose 状态读取：设置页关掉动画后当场生效，不必重启 App
    val predictiveBack = FeatureState.predictiveBack

    NavDisplay(
        navController = nav,
        modifier = Modifier.fillMaxSize(),
        onBack = { nav.pop() },
        transition = if (predictiveBack) NavTransitions.Modal else NavTransitions.None,
        effects = NavDisplayEffects(
            // Modal 是整屏矩形上滑，不需要圆角裁剪（圆角只用于卡片式 / 横向滑入转场）
            enableCornerClip = false,
            dimAmount = 0.35f,
            backdropColor = MiuixTheme.colorScheme.surface,
            // 转场过程中屏蔽下层触摸，避免误触到半动画的页面
            blockInputDuringTransition = true,
        ),
    ) {
        entry<Route.Main> {
            MainScreen(
                onOpenUserApps = { nav.push(Route.UserApps) },
                onOpenConfig = { nav.push(Route.HookConfig(it.id)) },
            )
        }

        entry<Route.UserApps>(swipeDismiss = NavSwipeDirection.TopToBottom) {
            var showBatchRestart by remember { mutableStateOf(false) }
            if (showBatchRestart) {
                BatchRestartDialog(
                    apps = HookCatalog.userApps,
                    onDismiss = { showBatchRestart = false },
                )
            }
            SubPageScaffold(
                title = "用户应用",
                onBack = { nav.pop() },
                actions = {
                    IconButton(onClick = { showBatchRestart = true }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "批量重启应用",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            ) {
                UserAppsScreen(onOpenConfig = { nav.push(Route.HookConfig(it.id)) })
            }
        }

        entry<Route.HookConfig>(swipeDismiss = NavSwipeDirection.TopToBottom) { route ->
            val hook = remember(route.hookId) { HookCatalog.hooks.firstOrNull { it.id == route.hookId } }
            if (hook == null) {
                // 理论上不可达：只有 HookCatalog 中登记的功能才会被 push
                LaunchedEffect(Unit) { nav.pop() }
            } else {
                SubPageScaffold(
                    title = hook.name,
                    subtitle = "调整结果立即保存，重启对应应用后生效",
                    onBack = { nav.pop() },
                ) {
                    if (hook.params.any { it is HookParam.CardOrder }) {
                        CardOrderScreen(hook = hook)
                    } else {
                        TilePickerScreen(hook = hook)
                    }
                }
            }
        }
    }
}

/**
 * 子页面外壳：Miuix `Scaffold` + 带返回键的标题栏。
 *
 * 复用组件库的 `SmallTopAppBar`（`navigationIcon` 放返回键），
 * 不再在页面内容里手写「← 返回」卡片；每个路由都有自己的 `Scaffold`，
 * 页面内的 `OverlayDialog` / 弹层因此都有合法的宿主。
 */
@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = "返回",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            content()
        }
    }
}
