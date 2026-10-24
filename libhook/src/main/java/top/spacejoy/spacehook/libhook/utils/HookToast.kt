package top.spacejoy.spacehook.libhook.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import top.spacejoy.spacehook.common.HookPrefs
import java.io.File

/**
 * 目标 App 启动时用 Toast 提示本次 Hook 装载结果。
 *
 * ## 为什么需要
 *
 * Hook 失效的典型表现是**静默**：不崩溃、不报错，功能就是没反应。
 * 用户看到的现象和「没装模块」一模一样，只能翻日志才能判断。
 *
 * 在 App 启动那一刻把「成功几个 / 失败几个」直接弹出来，
 * 一眼就知道这次更新有没有把功能弄坏。
 *
 * ## 时机
 *
 * Hook 分发发生在 `onPackageReady`，此时目标 App 还没有 Activity，
 * 直接 `Toast.show()` 会因为没有窗口而看不到。因此这里：
 *
 * 1. 先算出结果；
 * 2. **等主线程空闲再弹**（`Handler.post` + 轮询到有 Activity 为止）；
 * 3. 超过时限就放弃 —— 弹不出来也不能拖累目标 App。
 *
 * ## 开关
 *
 * 由设置页「启动时提示 Hook 状态」（[HookPrefs.TOAST_ON_START]）控制，默认关闭。
 * 总开关打开后，还可以在设置页的**应用列表**里逐个别关掉不需要提示的 App
 * （[HookPrefs.toastAppKey]，只看用户应用）。
 */
object HookToast {

    /** 等目标 App 出首屏的最长时间（毫秒） */
    private const val MAX_WAIT_MS = 15_000L

    /** 轮询间隔（毫秒） */
    private const val POLL_MS = 500L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 弹出装载结果。
     *
     * @param packageName 目标 App 包名（决定该 App 是否被单独关掉了提示）
     * @param total 本次参与分发的 Hook 总数（含被开关关掉的）
     * @param ok 装载成功
     * @param failed 关键锚点解析失败（需要适配新版）
     * @param skipped 主动跳过（如非主进程）
     * @param detail 失败详情，用于第二行提示
     */
    fun showResult(
        packageName: String,
        total: Int,
        ok: Int,
        failed: Int,
        skipped: Int,
        detail: List<String> = emptyList(),
    ) {
        if (!PrefsBridge.isToastEnabled(packageName)) return
        // 全被开关关掉时不弹：没有任何信息量，反而像出错了
        if (total <= 0) return

        val text = buildString {
            append("SpaceHook：")
            append("成功 ")
            append(ok)
            if (failed > 0) {
                append(" / 失败 ")
                append(failed)
            }
            if (skipped > 0) {
                append(" / 跳过 ")
                append(skipped)
            }
            append("（共 ")
            append(total)
            append("）")
            if (failed > 0) {
                append("\n需要适配：")
                append(detail.joinToString("、"))
            }
        }

        whenToastReady { context ->
            runCatching {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
            XLog.i("启动提示已弹出：${text.replace("\n", " / ")}")
        }
    }

    /**
     * 等到目标 App 有 Activity 了再回调。
     *
     * 判定依据：`ActivityThread#currentActivityThread().getApplication()` 可用
     * 且已有 Activity 记录（`mActivities` 非空）。
     */
    @SuppressLint("PrivateApi")
    private fun whenToastReady(block: (Context) -> Unit) {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS

        val attempt = object : Runnable {
            override fun run() {
                val context = applicationContext()
                if (context != null && hasActivity()) {
                    block(context)
                    return
                }
                if (System.currentTimeMillis() >= deadline) {
                    XLog.d("启动提示放弃：等了 ${MAX_WAIT_MS}ms 仍未出现首屏")
                    return
                }
                mainHandler.postDelayed(this, POLL_MS)
            }
        }
        mainHandler.postDelayed(attempt, POLL_MS)
    }

    private fun applicationContext(): Context? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getDeclaredMethod("currentApplication")
        current.isAccessible = true
        current.invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    /** 是否已经有 Activity（有窗口，Toast 才看得到） */
    private fun hasActivity(): Boolean = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getDeclaredMethod("currentActivityThread")
        current.isAccessible = true
        val thread = current.invoke(null) ?: return false
        val field = thread.javaClass.getDeclaredField("mActivities")
        field.isAccessible = true
        val map = field.get(thread) as? Map<*, *>
        map != null && map.isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /**
     * 只在 Debug 构建里写一个标记文件，方便 adb 确认开关真的读到了。
     *
     * （不写日志文件，避免影响目标 App 的存储）
     */
    @Suppress("unused")
    private fun debugMark(context: Context, text: String) {
        runCatching {
            File(context.cacheDir, "spacehook_toast_last.txt").writeText(text)
        }
    }
}
