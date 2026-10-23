package top.spacejoy.spacehook.common

/**
 * 偏好键名集中管理。
 *
 * 这些键都写在 [Constants.PREFS_GROUP] 这个 RemotePreferences 组里，
 * 由「模块 App 进程」写入、「被注入的目标 App 进程」读取。
 */
object HookPrefs {

    // ==================== 全局开关 ====================

    /** 总开关：关闭后所有 Hook 一律不加载 */
    const val MASTER_ENABLE = "global_master_enable"

    /** 安全模式：开启后暂停全部 Hook（防崩溃兜底） */
    const val SAFE_MODE = "global_safe_mode"

    /** 调试模式：输出详细日志 */
    const val DEBUG_MODE = "global_debug_mode"

    /** 日志级别：0=关闭 1=错误 2=警告 3=信息 4=调试 */
    const val LOG_LEVEL = "global_log_level"

    /** 是否把日志写入文件（/sdcard/SpaceHook/logs） */
    const val LOG_TO_FILE = "global_log_to_file"

    // ==================== 实验性功能 ====================

    /** 预测性返回动画：模块 App 内二级/三级页面侧滑返回时跟手动画 */
    const val PREDICTIVE_BACK = "exp_predictive_back"

    /** 强制启用模糊效果（Miuix 界面） */
    const val BLUR_ENABLE = "exp_blur"

    /** 热重载：模块更新后自动重载已注入的进程（API 102 特性） */
    const val HOT_RELOAD = "exp_hot_reload"

    /**
     * 启动提示：目标 App 启动时用 Toast 显示本次 Hook 装载结果
     * （成功几个 / 失败几个）。默认关闭。
     */
    const val TOAST_ON_START = "exp_toast_on_start"

    // ==================== 界面设置 ====================

    /** 主题模式：0=跟随系统 1=浅色 2=深色 3=Monet 跟随系统 4=Monet 浅色 5=Monet 深色 */
    const val THEME_MODE = "ui_theme_mode"

    /** 主题色 Key（Monet 动态取色关闭时使用） */
    const val COLOR_KEY = "ui_color_key"

    /** 字体缩放百分比：80 ~ 150 */
    const val FONT_SCALE = "ui_font_scale"

    /** 列表密度：0=标准 1=紧凑 */
    const val LIST_DENSITY = "ui_list_density"

    /** 显示系统应用 */
    const val SHOW_SYSTEM_APPS = "ui_show_system_apps"

    /** Hook 列表排序：0=按分类 1=按名称 */
    const val SORT_MODE = "ui_sort_mode"

    // ==================== 动态键 ====================

    /**
     * 某个目标 App 的「全部 Hook」总开关。
     * 关闭后该 App 下所有 Hook 都不加载。
     */
    fun appMasterKey(packageName: String): String = "app_master::$packageName"

    /** 单个 Hook 功能的开关 */
    fun hookKey(hookId: String): String = "hook::$hookId"

    /**
     * 某个 Hook 自己的功能参数。
     * 例如 `configKey("device_center_size", "mode")` → 强制单行/双行。
     */
    fun configKey(hookId: String, key: String): String = "cfg::$hookId::$key"

    /** 某个 Hook 在某个进程内是否被视为「已生效」（由 Hook 侧回写，用于 UI 展示） */
    fun hookActiveKey(hookId: String, processName: String): String =
        "active::$hookId::$processName"
}
