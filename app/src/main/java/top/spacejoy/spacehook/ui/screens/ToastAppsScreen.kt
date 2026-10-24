package top.spacejoy.spacehook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.spacejoy.spacehook.FeatureState
import top.spacejoy.spacehook.SettingsStore
import top.spacejoy.spacehook.common.HookCatalog
import top.spacejoy.spacehook.common.TargetApp
import top.spacejoy.spacehook.data.InstalledAppsProvider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「启动提示」的应用列表（整屏子页面）。
 *
 * 总开关（设置 → 实验性功能 → 启动时提示 Hook 状态）打开后，
 * 这里逐个决定**哪些用户应用**在启动时弹 Toast。
 * 只列用户应用：系统应用（SystemUI / system_server 等）本来就不弹，
 * 列出来只会让人以为「能关掉」。
 */
@Composable
fun ToastAppsScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { InstalledAppsProvider.ensureLoaded(context) }

    val installed = remember { InstalledAppsProvider.allApps.map { it.packageName }.toSet() }
    val apps = remember(installed) {
        HookCatalog.userApps.sortedWith(
            compareByDescending<TargetApp> { it.packageName in installed }
                .thenBy { it.displayName }
        )
    }

    // 每行自己持有开关状态（remember(packageName)）：
    // 初始值直接读 SettingsStore，列表异步加载完也不会把已有设置读成默认值；
    // 滚动复用时按包名重建，读到的仍是已写入的真实值。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "关闭后，该应用启动时不再弹出 Hook 状态提示；" +
                    "总开关本身关着时，这里怎么选都不会弹。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (apps.isEmpty()) {
            item {
                Text(
                    text = "目前还没有配置 Hook 的用户应用。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else {
            item { SmallTitle("用户应用") }

            items(apps, key = { it.packageName }) { app ->
                ToastAppRow(app = app, installed = app.packageName in installed)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 单个用户应用的一行开关 */
@Composable
private fun ToastAppRow(app: TargetApp, installed: Boolean) {
    // 按包名记住状态：列表重排/滚动复用都不会丢，也不依赖「列表已加载完」这个前提
    var enabled by remember(app.packageName) { mutableStateOf(SettingsStore.isToastAppEnabled(app.packageName)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = app.displayName,
            summary = buildString {
                append(app.packageName)
                if (!installed) append(" · 未安装")
            },
            checked = enabled,
            onCheckedChange = {
                enabled = it
                SettingsStore.setToastAppEnabled(app.packageName, it)
                // 让设置页的副标题数字跟着变
                FeatureState.refreshToastAppsClosedCount()
            },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
