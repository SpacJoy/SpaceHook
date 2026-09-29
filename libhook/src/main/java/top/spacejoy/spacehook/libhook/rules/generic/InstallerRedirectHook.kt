package top.spacejoy.spacehook.libhook.rules.generic

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import top.spacejoy.spacehook.libhook.base.BaseHook

/**
 * 安装请求回归「系统当前默认安装器」。
 *
 * 目标进程：发起安装的应用（当前为 **123云盘** `com.mfcloudcalculate.networkdisk`）。
 *
 * ## 这个 Hook 到底做什么（重要）
 *
 * 很多国产应用会把安装请求**硬绑定**到系统原装安装器
 * （例如 `com.miui.packageinstaller`）—— 通过 `setPackage()` 或 `setComponent()` 写死。
 * 这样一来，用户在系统设置里选的「默认安装器」就被绕过了。
 *
 * 本 Hook **不做任何指定**，它只做一件事：**把硬绑定解开**，
 * 让这个 Intent 重新走系统的 resolver，从而按**用户当前设置的默认安装器**解析。
 *
 * > 也就是说：最终由谁接管**取决于系统设置**，而不是由本模块指定。
 * > 用户把默认安装器设成 InstallerX 就走 InstallerX，设成别的就走那个。
 * > 常量 [FORCE_COMPONENT] 默认留空正是为了保持这一语义 ——
 * > 它只是留给特殊场景的逃生口，**正常使用时不要填**。
 *
 * ## 两条路径
 * 1. **Intent 路径** —— 所有 `startActivity` 最终汇聚到
 *    `android.app.Instrumentation#execStartActivity(...)` 的多个重载。
 *    在这里把「安装类 Intent」规范化成
 *    `ACTION_VIEW + application/vnd.android.package-archive`，
 *    并清掉 `setPackage()` / `setComponent()` 的硬绑定，交回系统解析。
 * 2. **Session 路径** —— 走 `PackageInstaller` 的应用不经过 resolver，
 *    任何默认安装器都拦不到；因此在 `Session#commit()` 前拦下，
 *    用捕获到的 APK 源文件转发给默认安装器。
 */
class InstallerRedirectHook : BaseHook("installer_redirect") {

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val ACT_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE"
        const val EXTRA_RETURN_RESULT = "android.intent.extra.RETURN_RESULT"

        /**
         * **逃生口，正常使用请保持为空！**
         *
         * 留空 = 把 Intent 交回系统 resolver，由「系统当前默认安装器」接管 —— 这是本功能的正常语义。
         * 仅当你确实想写死某个安装器组件时才填写，格式 `包名/类名`。
         */
        const val FORCE_COMPONENT = ""

        const val SESSION_TAKEOVER = true
        const val STRIP_RETURN_RESULT = true
        const val FRESH_APK_WINDOW_MS = 60_000L

        const val CLS_INSTRUMENTATION = "android.app.Instrumentation"
        const val CLS_PENDING_INTENT = "android.app.PendingIntent"
        const val CLS_PACKAGE_INSTALLER = "android.content.pm.PackageInstaller"
        const val CLS_SESSION = "android.content.pm.PackageInstaller\$Session"
    }

    private var lastApkPath: String? = null
    private var lastApkAt: Long = 0L
    private var fileWatchInstalled = false
    private var redirected = 0
    private var sessionSeen = 0

    override fun onHook() {
        hookInstrumentation()
        hookPendingIntent()
        hookPackageInstaller()
    }

    // ==================== 1. Intent 路径 ====================

    private fun hookInstrumentation() {
        val clazz = findClass(CLS_INSTRUMENTATION)
        if (clazz == null) {
            loge("找不到 $CLS_INSTRUMENTATION，无法接管安装改道")
            return
        }
        val handles = hookAllMethods(clazz, "execStartActivity") { chain ->
            interceptIntent(chain.args, "Instrumentation.execStartActivity")
            chain.proceed()
        }
        log("execStartActivity 已挂载 ${handles.size} 个重载")
    }

    private fun hookPendingIntent() {
        findClass(CLS_PENDING_INTENT)?.let { clazz ->
            val handles = hookAllMethods(clazz, "getActivity") { chain ->
                interceptIntent(chain.args, "PendingIntent.getActivity")
                chain.proceed()
            }
            log("PendingIntent.getActivity 已挂载 ${handles.size} 个重载")
        }
    }

    // ==================== 2. Session 路径 ====================

    private fun hookPackageInstaller() {
        findClass(CLS_PACKAGE_INSTALLER)?.let { clazz ->
            hookAllMethods(clazz, "createSession") { chain ->
                sessionSeen++
                logw("检测到 session 安装路径（第 $sessionSeen 次）")
                ensureFileWatch()
                chain.proceed()
            }
            log("PackageInstaller.createSession 已挂载")
        }

        findClass(CLS_SESSION)?.let { clazz ->
            hookAllMethods(clazz, "openWrite") { chain ->
                ensureFileWatch()
                chain.proceed()
            }

            hookAllMethods(clazz, "commit") { chain ->
                if (!SESSION_TAKEOVER) return@hookAllMethods chain.proceed()

                val path = lastApkPath
                val fresh = path != null &&
                    System.currentTimeMillis() - lastApkAt < FRESH_APK_WINDOW_MS
                if (!fresh) {
                    logd("未捕获到新鲜 APK，放行给系统安装器")
                    return@hookAllMethods chain.proceed()
                }
                val file = File(path!!)
                if (!file.exists() || file.length() <= 0L) {
                    logd("捕获到的文件不可用，放行：$path")
                    return@hookAllMethods chain.proceed()
                }

                log("Session 拦截成功，改用默认安装器安装：$path")
                // 放弃原 session，交给默认安装器
                callMethod(chain.thisObject, "abandon")
                fireInstall(file)
                // 返回 null 表示不再执行原 commit
                null
            }
            log("PackageInstaller\$Session 已挂载")
        }
    }

    /** 监听 FileInputStream 构造，捕获「刚刚被读取的 APK 文件」 */
    private fun ensureFileWatch() {
        if (fileWatchInstalled) return
        fileWatchInstalled = true
        try {
            hookAllConstructors(FileInputStream::class.java) { chain ->
                val file = when (val arg = chain.args.getOrNull(0)) {
                    is File -> arg
                    is String -> File(arg)
                    else -> null
                }
                if (file != null && file.name.endsWith(".apk", ignoreCase = true) &&
                    file.length() > 0
                ) {
                    lastApkPath = file.absolutePath
                    lastApkAt = System.currentTimeMillis()
                }
                chain.proceed()
            }
            logd("已挂载 FileInputStream 构造监听")
        } catch (t: Throwable) {
            loge("挂载文件监听失败", t)
        }
    }

    // ==================== 改道逻辑 ====================

    private fun interceptIntent(args: List<Any?>, from: String) {
        val intent = args.firstOrNull { it is Intent } as? Intent ?: return
        if (isNormalized(intent) || !isApkIntent(intent)) return
        redirect(intent, from)
    }

    private fun isApkIntent(intent: Intent): Boolean {
        if (ACT_INSTALL_PACKAGE == intent.action) return true
        if (intent.type?.contains("package-archive") == true) return true

        val data = intent.data
        if (data != null) {
            val s = data.toString()
            if ((s.startsWith("content:") || s.startsWith("file:")) &&
                s.endsWith(".apk", ignoreCase = true)
            ) {
                return true
            }
        }
        intent.component?.packageName?.let { p ->
            if (p.contains("packageinstaller") || p.contains("installer")) return true
        }
        intent.`package`?.let { p ->
            if (p.contains("packageinstaller") || p.contains("installer")) return true
        }
        return false
    }

    /** 已经是标准安装 Intent 的，不重复处理，避免多路径重复改道 */
    private fun isNormalized(intent: Intent): Boolean {
        if (Intent.ACTION_VIEW != intent.action) return false
        if (APK_MIME != intent.type) return false
        if (intent.`package` != null) return false
        if (FORCE_COMPONENT.isEmpty() && intent.component != null) return false
        return true
    }

    private fun redirect(intent: Intent, from: String) {
        try {
            val oldAction = intent.action
            val oldPkg = intent.`package`

            intent.action = Intent.ACTION_VIEW
            val data = intent.data
            if (data != null) {
                intent.setDataAndType(data, APK_MIME)
            } else {
                intent.type = APK_MIME
            }
            if (intent.`package` != null) intent.`package` = null
            if (intent.component != null) intent.component = null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (STRIP_RETURN_RESULT) intent.removeExtra(EXTRA_RETURN_RESULT)

            if (FORCE_COMPONENT.isNotEmpty()) {
                val parts = FORCE_COMPONENT.split("/")
                if (parts.size == 2) intent.component = ComponentName(parts[0], parts[1])
            }
            grantTarget(intent)

            redirected++
            log("已解绑并交回系统解析 #$redirected（来源=$from, 原 action=$oldAction, 原 package=$oldPkg）")
        } catch (t: Throwable) {
            loge("改道失败", t)
        }
    }

    /** 把 content:// 的读权限显式授给最终处理者 */
    private fun grantTarget(intent: Intent) {
        try {
            val uri = intent.data ?: return
            if ("content" != uri.scheme) return
            val ctx = appContext() ?: return
            val pm = ctx.packageManager
            val info: ResolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: return
            val target = info.activityInfo?.packageName ?: return
            ctx.grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            logd("已授权 $target 读取 $uri")
        } catch (t: Throwable) {
            logd("授权失败：${t.message}")
        }
    }

    private fun fireInstall(file: File) {
        try {
            val ctx = appContext() ?: run {
                loge("拿不到 Context，无法转发")
                return
            }
            val uri = fileToUri(ctx, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (FORCE_COMPONENT.isNotEmpty()) {
                val parts = FORCE_COMPONENT.split("/")
                if (parts.size == 2) intent.component = ComponentName(parts[0], parts[1])
            }
            log("转发安装：${file.absolutePath}")
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            loge("转发安装失败", t)
        }
    }

    /** 优先用宿主 App 自己的 FileProvider，避免 file:// 在 API 24+ 被拒 */
    private fun fileToUri(ctx: Context, file: File): Uri {
        try {
            val authority = findProviderAuthority(ctx)
            if (authority != null) {
                val provider = Class.forName("androidx.core.content.FileProvider")
                val method = provider.getMethod(
                    "getUriForFile",
                    Context::class.java,
                    String::class.java,
                    File::class.java,
                )
                return method.invoke(null, ctx, authority, file) as Uri
            }
        } catch (t: Throwable) {
            logd("FileProvider 取 URI 失败：${t.message}")
        }
        logw("只能用 file:// 兜底，API >= 24 时目标安装器可能读不到")
        return Uri.fromFile(file)
    }

    private fun findProviderAuthority(ctx: Context): String? {
        return try {
            val info = ctx.packageManager.getPackageInfo(
                ctx.packageName,
                PackageManager.GET_PROVIDERS,
            )
            val providers = info.providers ?: return null
            providers.firstOrNull {
                it?.authority != null && it.grantUriPermissions &&
                    it.name?.contains("fileprovider", ignoreCase = true) == true
            }?.authority
                ?: providers.firstOrNull { it?.authority != null && it.grantUriPermissions }?.authority
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 拿到当前进程的 Application Context。
     *
     * `android.app.ActivityThread` 是隐藏 API，全部走反射。
     */
    private fun appContext(): Context? {
        val activityThread = try {
            val clazz = Class.forName("android.app.ActivityThread")
            val method = clazz.getDeclaredMethod("currentActivityThread")
            method.isAccessible = true
            method.invoke(null)
        } catch (_: Throwable) {
            null
        } ?: return null

        try {
            val method = activityThread.javaClass.getMethod("getApplication")
            method.isAccessible = true
            (method.invoke(activityThread) as? Context)?.let { return it }
        } catch (_: Throwable) {
            // 继续
        }

        try {
            val field = activityThread.javaClass.getDeclaredField("mInitialApplication")
            field.isAccessible = true
            (field.get(activityThread) as? Context)?.let { return it }
        } catch (_: Throwable) {
            // 放弃
        }

        return null
    }
}
