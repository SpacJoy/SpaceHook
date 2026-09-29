package top.spacejoy.spacehook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import top.spacejoy.spacehook.ui.SpaceHookNavHost
import top.spacejoy.spacehook.ui.theme.SpaceHookTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SpaceHookTheme(
                themeMode = AppearanceState.themeMode,
                keyColor = AppearanceState.colorKey,
                fontScale = AppearanceState.fontScale,
            ) {
                // 顶层导航宿主：一级页签 + 整屏子路由（Miuix miuix-nav，Y 轴转场）
                SpaceHookNavHost()
            }
        }
    }
}
