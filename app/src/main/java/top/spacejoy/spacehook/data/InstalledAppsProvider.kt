package top.spacejoy.spacehook.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设备上一个已安装的应用。
 *
 * @property isSystem 是否为系统应用（`ApplicationInfo.FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP`）
 */
data class InstalledApp(
    val packageName: String,
    val displayName: String,
    val isSystem: Boolean,
    val enabled: Boolean,
)

/**
 * 已安装应用查询。
 *
 * 结果缓存在内存里（一次查询开销不小），提供 [refresh] 手动刷新。
 * 需要 `QUERY_ALL_PACKAGES` 权限才能看到全部应用。
 */
object InstalledAppsProvider {

    /** 全部已安装应用（含系统） */
    var allApps by mutableStateOf<List<InstalledApp>>(emptyList())
        private set

    /** 是否已加载过 */
    var loaded by mutableStateOf(false)
        private set

    /** 用户应用（第三方） */
    val userApps: List<InstalledApp> get() = allApps.filter { !it.isSystem }

    /** 系统应用 */
    val systemApps: List<InstalledApp> get() = allApps.filter { it.isSystem }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        refresh(context)
    }

    fun refresh(context: Context) {
        allApps = query(context)
        loaded = true
    }

    private fun query(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }   // 排除模块自己
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        displayName = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        isSystem = info.isSystemApp(),
                        enabled = info.enabled,
                    )
                }
                .sortedWith(compareBy({ it.isSystem }, { it.displayName.lowercase() }))
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}
