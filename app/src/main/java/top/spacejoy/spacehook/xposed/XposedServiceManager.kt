package top.spacejoy.spacehook.xposed

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import top.spacejoy.spacehook.SettingsStore
import top.spacejoy.spacehook.common.Constants

/** 框架信息快照 */
data class FrameworkInfo(
    val name: String = "",
    val version: String = "",
    val apiVersion: Int = 0,
    val properties: Long = 0L,
) {
    val isAvailable: Boolean get() = name.isNotEmpty()
}

/** 一个已被注入且正在运行的进程 */
data class TargetInfo(
    val uid: Int,
    val pid: Int,
    val processName: String,
    val state: String,
    val loadedVersionCode: Long,
)

/**
 * 与 LSPosed 框架的连接管理（模块 App 侧）。
 *
 * - 判断模块是否已激活；
 * - 读取框架名称 / 版本 / API 版本；
 * - 获取「当前已注入并运行」的进程列表（首页「是否已生效」的依据）；
 * - 提供 [remotePrefs]，供 [top.spacejoy.spacehook.SettingsStore] 写入配置。
 */
object XposedServiceManager {

    /** 模块是否已激活（成功绑定到框架服务） */
    var activated by mutableStateOf(false)
        private set

    /** 框架信息 */
    var framework by mutableStateOf(FrameworkInfo())
        private set

    /** 当前正在运行的已注入进程 */
    var targets by mutableStateOf<List<TargetInfo>>(emptyList())
        private set

    /** 模块在框架中声明的 scope */
    var scope by mutableStateOf<List<String>>(emptyList())
        private set

    /** 最近一次作用域请求的结果提示（供 UI 展示） */
    var lastScopeMessage by mutableStateOf("")
        internal set

    /** 服务就绪回调（用于首次绑定后刷新数据） */
    var onReady: (() -> Unit)? = null

    private var service: XposedService? = null
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        registered = true
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(svc: XposedService) {
                    service = svc
                    framework = FrameworkInfo(
                        name = safeString { svc.frameworkName },
                        version = safeString { svc.frameworkVersion },
                        apiVersion = safeInt { svc.apiVersion },
                        properties = safeLong { svc.frameworkProperties },
                    )
                    scope = safeList { svc.scope }
                    activated = true
                    refreshTargets()
                    onReady?.invoke()
                }

                override fun onServiceDied(svc: XposedService) {
                    service = null
                    activated = false
                    targets = emptyList()
                    scope = emptyList()
                }
            })
        } catch (t: Throwable) {
            activated = false
        }
    }

    /** 拉取「已生效进程」列表 */
    fun refreshTargets() {
        val svc = service ?: run {
            targets = emptyList()
            return
        }
        targets = try {
            svc.runningTargets.map { t ->
                TargetInfo(
                    uid = t.uid,
                    pid = t.pid,
                    processName = t.processName ?: "?",
                    state = t.state?.name ?: "UNKNOWN",
                    loadedVersionCode = t.loadedVersionCode,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
        // 顺手把见过的进程记到本地 —— 这样录音机这类按需启动的 App
        // 关闭之后，首页依然能显示它「曾生效」。
        SettingsStore.rememberActiveProcesses(targets.map { it.processName })
    }

    /** 供 SettingsStore 写入的远程偏好；未激活返回 null */
    fun remotePrefs(): SharedPreferences? = try {
        service?.getRemotePreferences(Constants.PREFS_GROUP)
    } catch (_: Throwable) {
        null
    }

    /** 热重载某个已注入的进程（API 102 特性） */
    fun hotReload(processName: String): Boolean {
        val svc = service ?: return false
        val target = try {
            svc.runningTargets.firstOrNull { it.processName == processName }
        } catch (_: Throwable) {
            null
        } ?: return false

        return try {
            svc.hotReloadModule(target, android.os.Bundle()) { _, _ ->
                // 结果回来后刷新一次状态即可
                refreshTargets()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 向框架**申请作用域**。
     *
     * 模块声明了 `staticScope=false`，因此作用域完全由用户决定；
     * 而 scope 为空时 LSPosed 会把模块应用到**所有**应用，
     * 这在目标 App 固定的场景下既浪费性能也可能引发冲突。
     *
     * 调用后 LSPosed 会弹出授权确认框，用户点一下即可把这些包纳入作用域。
     *
     * 注意：[XposedService.OnScopeEventListener] 有**两个** default 方法，
     * 不是 SAM 接口，因此必须用匿名对象而**不能**用 lambda。
     *
     * @return 是否成功发出请求（不代表用户已同意）
     */
    fun requestScope(packages: List<String>): Boolean {
        val svc = service ?: return false
        if (packages.isEmpty()) return false
        return try {
            svc.requestScope(packages, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(requested: MutableList<String>) {
                    scope = safeList { svc.scope }
                    lastScopeMessage = "作用域已授权（${requested.size} 个）"
                }

                override fun onScopeRequestFailed(error: String) {
                    lastScopeMessage = "作用域授权失败：$error"
                }
            })
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 移除作用域（会从框架中撤销这些包） */
    fun removeScope(packages: List<String>): Boolean {
        val svc = service ?: return false
        return try {
            svc.removeScope(packages)
            scope = safeList { svc.scope }
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== 安全取值 ====================

    private inline fun safeString(block: () -> String?): String =
        try { block().orEmpty() } catch (_: Throwable) { "" }

    private inline fun safeInt(block: () -> Int): Int =
        try { block() } catch (_: Throwable) { 0 }

    private inline fun safeLong(block: () -> Long): Long =
        try { block() } catch (_: Throwable) { 0L }

    private inline fun safeList(block: () -> List<String>?): List<String> =
        try { block() ?: emptyList() } catch (_: Throwable) { emptyList() }
}
