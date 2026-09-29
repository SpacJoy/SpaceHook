package top.spacejoy.spacehook.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.TimeUnit

/**
 * 重启指定应用的进程。
 *
 * ## 为什么需要「只重启一个应用」
 * Hook 生效需要重启目标进程。但重启**整个作用域**代价很大 ——
 * 重启系统界面（SystemUI）会让状态栏、控制中心、锁屏全部重建，明显卡顿。
 * 因此这里支持**只重启某一个应用**，改完哪个就重启哪个。
 *
 * ## 权限
 * `killall` / `am force-stop` 都需要特权，因此走 `su`。
 * LSPosed 用户必然已有 root，所以这是首选路径。
 * 若设备没有 root（或用户拒绝了授权），回退到**打开应用详情页**，
 * 由用户手动点「强行停止」。
 */
object ProcessRestarter {

    // ==================== Root 授权状态 ====================

    /**
     * root 授权状态。
     *
     * LSPosed 本身需要 root 环境，因此绝大多数情况下是可用的；
     * 但 **App 调用 `su` 需要在 KernelSU / Magisk 管理器中单独授权本应用**，
     * 这与 `adb shell su` 是两条独立的授权路径。
     */
    enum class RootState(val displayName: String) {
        UNKNOWN("未检测"),
        CHECKING("检测中…"),
        GRANTED("已授权"),
        DENIED("未授权"),
    }

    /** 当前 root 状态（供 UI 观察） */
    var rootState by mutableStateOf(RootState.UNKNOWN)
        private set

    /** 状态补充说明 */
    var rootDetail by mutableStateOf("")
        private set

    /**
     * 检测 root 是否可用。
     *
     * ⚠️ **首次调用会弹出 KernelSU / Magisk 的授权框**，因此需要由用户主动触发，
     * 且必须在 IO 线程执行（内部会阻塞等待）。
     */
    fun detectRoot(): RootState {
        rootState = RootState.CHECKING
        rootDetail = ""
        val result = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            when {
                !finished -> {
                    process.destroy()
                    rootDetail = "等待授权超时，请在弹出的授权框中允许"
                    RootState.DENIED
                }

                process.inputStream.bufferedReader().readText().contains("uid=0") -> {
                    rootDetail = "SpaceHook 已获得 root 权限"
                    RootState.GRANTED
                }

                else -> {
                    rootDetail = "su 返回非 root 身份，请在 KernelSU / Magisk 中授权 SpaceHook"
                    RootState.DENIED
                }
            }
        } catch (t: Throwable) {
            rootDetail = "无法调用 su：${t.message ?: t.javaClass.simpleName}"
            RootState.DENIED
        }
        rootState = result
        return result
    }

    /** 免阻塞地把状态标记为「未检测」（用于刷新前重置） */
    fun resetRootState() {
        rootState = RootState.UNKNOWN
        rootDetail = ""
    }

    // ==================== 重启 ====================

    /**
     * 单次 root 调用的超时（毫秒）—— 首次会弹授权框，留足时间。
     * 用户在弹窗上确认后再点授权，因此给得宽一些。
     */
    private const val TIMEOUT_MS = 30_000L

    /** 重启失败的归类，便于 UI 给出针对性提示 */
    enum class Failure { NO_ROOT_PERMISSION, TIMEOUT, OTHER }

    /** 重启结果 */
    sealed interface RestartResult {
        /** 成功 */
        data object Success : RestartResult

        /** 失败，带上原因分类与详情 */
        data class Failed(val kind: Failure, val detail: String) : RestartResult
    }

    /**
     * 用 root 重启指定包。
     *
     * 注意：首次调用会触发 **KernelSU / Magisk 的授权弹窗**，
     * 若用户未授权，`su` 会直接失败 —— 这种情况归类为 [Failure.NO_ROOT_PERMISSION]，
     * 由 UI 引导用户去管理器中授权，或回退到手动停止。
     */
    fun restartByRoot(packageName: String): RestartResult {
        return try {
            val script = buildString {
                append("killall ").append(packageName).append(" 2>/dev/null; ")
                append("am force-stop ").append(packageName).append(" 2>/dev/null; ")
                append("echo __DONE__")
            }
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))

            val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return RestartResult.Failed(
                    Failure.TIMEOUT,
                    "等待 root 授权超时。请在弹出的授权框中允许 SpaceHook 获取 root 权限",
                )
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            when {
                exitCode == 0 && stdout.contains("__DONE__") -> RestartResult.Success

                // KernelSU / Magisk 拒绝时会给出这类提示
                stderr.contains("permission denied", ignoreCase = true) ||
                    stderr.contains("not allowed", ignoreCase = true) ||
                    stdout.contains("permission denied", ignoreCase = true) ->
                    RestartResult.Failed(
                        Failure.NO_ROOT_PERMISSION,
                        "未获得 root 权限，请在 KernelSU / Magisk 管理器中为 SpaceHook 授权",
                    )

                stderr.isNotBlank() -> RestartResult.Failed(Failure.OTHER, stderr.trim().take(180))
                exitCode != 0 -> RestartResult.Failed(Failure.OTHER, "命令退出码 $exitCode")
                else -> RestartResult.Failed(Failure.OTHER, "命令未正常完成")
            }
        } catch (t: Throwable) {
            RestartResult.Failed(
                Failure.NO_ROOT_PERMISSION,
                t.message?.take(180) ?: "无法调用 su（${t.javaClass.simpleName}）",
            )
        }
    }

    /** 是否具备 root（粗判：su 可执行且能拿到 uid=0） */
    fun hasRoot(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            process.inputStream.bufferedReader().readText().contains("uid=0")
        }
    } catch (_: Throwable) {
        false
    }

    /** 打开应用的系统详情页，引导用户手动「强行停止」 */
    fun openAppDetails(context: Context, packageName: String) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {
            // 部分 ROM 无此页面，忽略
        }
    }
}
