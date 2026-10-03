package top.spacejoy.spacehook.common

/**
 * 目标应用分类。
 */
enum class AppCategory(val displayName: String) {
    /** 系统应用（SystemUI / 系统框架 / MIUI 组件等） */
    SYSTEM("系统应用"),

    /** 第三方应用 */
    THIRD_PARTY("第三方应用"),
}

/**
 * 一个可以被 Hook 的目标应用。
 *
 * 这是纯展示层数据：只描述「有哪些 App 上了 Hook」，不包含任何 Hook 实现引用。
 */
data class TargetApp(
    /** 目标应用包名 */
    val packageName: String,

    /** 展示名称 */
    val displayName: String,

    /** 分类 */
    val category: AppCategory,

    /** 备注：这个 App 上挂了什么，用于列表副标题 */
    val note: String = "",

    /** 是否强制出现在列表中（即使当前设备未安装，也展示为「未安装」） */
    val alwaysShow: Boolean = false,
) {
    /** 特殊目标：系统框架 system_server */
    val isSystemServer: Boolean get() = packageName == Constants.TARGET_SYSTEM

    /** 特殊目标：任意应用通配 */
    val isWildcard: Boolean get() = packageName == Constants.TARGET_WILDCARD_ALL
}

/**
 * Hook 功能分类。
 */
enum class HookCategory(val displayName: String) {
    SYSTEM_UI("系统界面"),

    /** 控制中心类（卡片/磁贴相关），UI 上归入「控制中心」折叠子菜单 */
    CONTROL_CENTER("控制中心"),

    /** 状态栏类（图标/时钟等），UI 上归入「状态栏」折叠子菜单 */
    STATUS_BAR("状态栏"),
    SYSTEM_FRAMEWORK("系统框架"),
    SOUND_RECORDER("录音机"),
    INSTALLER("安装器"),
    NETWORK_DISK("网盘"),
    DOUYIN("抖音"),
    OTHER("其他"),
}

/**
 * 一个 Hook 功能的元数据。
 *
 * 注册新 Hook 时需要：
 *  1. 在 [HookCatalog.hooks] 中加入一条 [HookMeta]；
 *  2. 在 `:libhook` 的 `HookRegistry` 中把 [id] 映射到对应的 `BaseLoad` 实现类。
 *
 * ⚠️ 本类会被模块 App（UI 进程）读取，因此**不允许**持有任何 Hook 实现类引用。
 *
 * @property id 全局唯一 ID，用于偏好键名，一旦发布不可更改（建议小写下划线）
 * @property name 展示名称
 * @property description 功能说明
 * @property targetPackages 该 Hook 作用的目标包名列表（支持 [Constants.TARGET_SYSTEM] / [Constants.TARGET_WILDCARD_ALL]）
 * @property category 分类
 * @property defaultEnabled 出厂默认是否开启
 * @property experimental 是否属于实验性功能（UI 上会打标）
 * @property requiresReboot 开启/关闭后是否需要重启目标应用才生效
 */
data class HookMeta(
    val id: String,
    val name: String,
    val description: String,
    val targetPackages: List<String>,
    val category: HookCategory = HookCategory.OTHER,
    val defaultEnabled: Boolean = true,
    val experimental: Boolean = false,
    val requiresReboot: Boolean = true,
    /** Hook 点定位依据（类名/方法名），仅用于文档展示与排障 */
    val hookPoints: List<String> = emptyList(),
    /**
     * 可配置参数声明。非空时 UI 的 Hook 行可点击，弹出参数配置。
     * 参数读写走 `cfg::<id>::<key>` 的 RemotePreferences，Hook 侧每次调用实时读取。
     */
    val params: List<HookParam> = emptyList(),
)
