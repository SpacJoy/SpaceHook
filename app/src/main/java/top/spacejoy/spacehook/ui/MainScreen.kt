package top.spacejoy.spacehook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.common.HookMeta
import top.spacejoy.spacehook.ui.screens.BatchRestartDialog
import top.spacejoy.spacehook.ui.screens.HomeScreen
import top.spacejoy.spacehook.ui.screens.HookListScreen
import top.spacejoy.spacehook.ui.screens.SettingsScreen
import top.spacejoy.spacehook.xposed.XposedServiceManager
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings

/** 底部导航页面定义 */
private data class NavPage(
    val title: String,
    val icon: ImageVector,
)

private val PAGES = listOf(
    NavPage("首页", MiuixIcons.Home),
    NavPage("Hook", MiuixIcons.ListView),
    NavPage("设置", MiuixIcons.Settings),
)

/**
 * 一级页面外壳：顶部标题栏 + 底部三页签导航。
 *
 * 二级 / 三级页面**不在这里切换** —— 它们由 [SpaceHookNavHost] 作为整屏路由渲染，
 * 本函数只负责把「打开用户应用」「打开功能配置」两个意图通过回调抛给导航宿主。
 */
@Composable
fun MainScreen(
    onOpenUserApps: () -> Unit = {},
    onOpenConfig: (HookMeta) -> Unit = {},
) {
    var current by rememberSaveable { mutableStateOf(0) }
    // 「批量重启」确认框由**按钮自己所在的位置**持有：这样切页签回来不会被重新触发
    // （之前是用计数 + LaunchedEffect 传给 HookListScreen，切页签重建时会带着旧值重新弹窗）
    var showBatchRestart by remember { mutableStateOf(false) }

    // 生命周期内保持与框架服务的连接，退出时释放
    DisposableEffect(Unit) {
        XposedServiceManager.refreshTargets()
        onDispose { /* 保持监听，无需反注册 */ }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = PAGES[current].title,
                actions = {
                    if (current == 1) {
                        IconButton(onClick = { showBatchRestart = true }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "批量重启应用",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                PAGES.forEachIndexed { index, page ->
                    NavigationBarItem(
                        selected = current == index,
                        onClick = { current = index },
                        icon = page.icon,
                        label = page.title,
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (current) {
                0 -> HomeScreen()
                1 -> HookListScreen(
                    onOpenUserApps = onOpenUserApps,
                    onOpenConfig = onOpenConfig,
                )
                else -> SettingsScreen()
            }

            // 批量重启确认框（一级页面 = 系统应用列表）；OverlayDialog 的宿主是上面这个 Scaffold
            if (showBatchRestart) {
                BatchRestartDialog(
                    apps = HookCatalog.systemApps,
                    onDismiss = { showBatchRestart = false },
                )
            }
        }
    }
}
