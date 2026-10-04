# AGENTS.md — SpaceHook 开发交接指南

> 本文件是**跨 Agent 工具的交接入口**。任何 Agent（Claude Code / CodeBuddy / Cursor / Cline …）接手本项目时，
> 请先完整阅读本文件，再按需查阅 `docs/` 下的详细文档。

---

## 1. 项目是什么

**SpaceHook** —— 一个基于 **libxposed API 102** 的 LSPosed 模块（Xposed 模块），
用于把多个针对不同 App 的 Hook 功能聚合到一个应用里，并提供图形化开关管理。

| 项 | 值 |
|---|---|
| 项目根目录 | `D:/Code/Android/Projects/LSPosed/Xposed.spacejoy.top` |
| 模块包名 / ApplicationId | `top.spacejoy.spacehook` |
| Xposed API | **102**（modern API，非 legacy XposedBridge） |
| UI 框架 | **Miuix 0.9.4**（Compose Multiplatform，小米 HyperOS 设计规范） |
| 模块 | `miuix-ui` / `miuix-preference` / `miuix-icons` / **`miuix-nav`**（导航：`NavDisplay` + Y 轴转场） |
| 语言 | Kotlin（JVM Target：`:app` = **21**，`common`/`libhook` = 17） |
| 构建 | Gradle 9.3.1 + AGP 9.1.0 + Kotlin 2.4.20 |
| compileSdk / minSdk / targetSdk | 37 / 26 / 37 |
| 后端 | 无（纯 Kotlin，无 Native） |

---

## 2. 三分钟上手

```bash
# 环境（本机路径）
export JAVA_HOME="C:/Program Files/Java/latest/jdk-21"
export ANDROID_HOME="D:/Code/Android/SDK"

cd D:/Code/Android/Projects/LSPosed/Xposed.spacejoy.top

# 本机已解压好的 Gradle（无需 wrapper 下载）
/d/Code/Android/tools/gradle-9.3.1/bin/gradle --no-daemon :app:assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

> ⚠️ `local.properties` 里的 `sdk.dir=D:\Code\Android\SDK` 是本机 SDK 路径，换机器需要改。
> ⚠️ 国内网络下 `settings.gradle.kts` 已配置阿里云镜像优先；若仍失败，需要给 Gradle 配代理：
> `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<port> -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<port>`

---

## 3. 目录结构

```
Xposed.spacejoy.top/
├── AGENTS.md                    ← 你正在读的文件
├── docs/
│   ├── ARCHITECTURE.md          架构与设计决策
│   ├── PROGRESS.md              开发进度（每次改动请更新！）
│   ├── HOOKS.md                 Hook 功能清单 + hook 点定位表
│   └── ANALYSIS-SoundMan.md     SoundMan 移植分析报告
├── common/                      纯数据层（UI 与 Hook 进程共用，禁止依赖 libxposed / Compose）
│   └── src/main/java/top/spacejoy/spacehook/common/
│       ├── Constants.kt         全局常量
│       ├── HookMeta.kt          HookMeta / TargetApp / 分类枚举
│       ├── HookCatalog.kt       ★ 能力注册表（新增 Hook 的第一站）
│       └── HookPrefs.kt         偏好键名集中管理
├── libhook/                     Hook 实现层（compileOnly libxposed API）
│   └── src/main/
│       ├── resources/META-INF/xposed/
│       │   ├── module.prop      minApiVersion / targetApiVersion
│       │   ├── java_init.list   模块入口类名
│       │   └── scope.list       ★ 作用域包名清单
│       └── java/top/spacejoy/spacehook/libhook/
│           ├── SpaceHookEntry.kt        ★ XposedModule 入口 + 分发逻辑
│           ├── base/
│           │   ├── BaseHook.kt          ★ Hook 工具基类（before/replace/hookAllMethods…）
│           │   ├── HookContext.kt       运行时上下文
│           │   └── HookRegistry.kt      ★ id → Hook 实现类映射（新增 Hook 的第二站）
│           ├── utils/
│           │   ├── XLog.kt              统一日志
│           │   ├── PrefsBridge.kt       配置读取 + 开关判定
│           │   └── ClassLoaderUtils.kt  子 ClassLoader 查找（MIUI plugin 场景）
│           └── rules/                   各 Hook 实现
│               ├── systemui/LockColonHook.kt（lock_colon）
│               ├── systemui/DeviceCenterSizeHook.kt（device_center_size）
│               ├── systemui/ControlCenterOrderHook.kt（cc_card_order）
│               ├── systemui/CardStyleTilesHook.kt（cc_card_tiles）
│               ├── systemui/StatusBarMeterHook.kt（status_bar_meter）
│               ├── systemui/StatusBarSpeedHook.kt（status_bar_speed）
│               ├── systemui/meter/      状态栏仪表共用层（hook 点 / 组装 / 数据源）
│               │   ├── StatusBarMeterHookBase.kt   hook 点安装、视图写入、样式与排布
│               │   ├── StatusBarMeterState.kt      两个 Hook 的贡献者与文本组装
│               │   └── MeterReader.kt              sysfs / 粘性电池广播 / TrafficStats 只读数据源
│               ├── soundrecorder/DisableListAutoPlayHook.kt
│               ├── douyin/DouYinNoCropHook.kt（+ NoCropFitApplier / NoCropReflect / NoCropVideoAspect）
│               ├── system/AdbStayAwakeHook.kt（system_server）
│               └── generic/InstallerRedirectHook.kt
└── app/                         UI 层（模块 App）
    └── src/main/java/top/spacejoy/spacehook/
        ├── SpaceHookApp.kt / MainActivity.kt
        ├── SettingsStore.kt          设置读写（RemotePreferences ↔ 本地兜底）
        ├── AppearanceState.kt        外观设置的 Compose 状态
        ├── FeatureState.kt           实验性功能开关的 Compose 状态（导航宿主直接订阅）
        ├── xposed/XposedServiceManager.kt  ★ 与框架通信（激活状态 / 框架信息 / 生效进程 / 写配置）
        └── ui/
            ├── SpaceHookNavHost.kt   ★ 顶层路由（Miuix `NavDisplay` + `NavTransitions.Modal` Y 轴转场）
            ├── MainScreen.kt         一级外壳：SmallTopAppBar + NavigationBar（三页签）
            ├── theme/SpaceHookTheme.kt
            └── screens/{Home,HookList,Settings,CardOrder,TilePicker}Screen.kt
```

---

## 4. 关键概念

### 4.1 配置如何跨进程生效

```
[模块 App UI 进程]                          [被注入的目标 App 进程]
SettingsStore                              PrefsBridge
   ↓ 写                                       ↑ 读
XposedService.getRemotePreferences(group)  XposedModule.getRemotePreferences(group)
   ↓                                          ↑
   └────────► LSPosed 数据库（group = "spacehook_config"）◄────────┘
```

- **UI 侧**：`XposedServiceManager.remotePrefs()` → `SharedPreferences`
- **Hook 侧**：`SpaceHookEntry.onModuleLoaded` 里 `getRemotePreferences(Constants.PREFS_GROUP)`
- 若模块**未激活**，UI 侧写入会退化到本地 `SharedPreferences`（`spacehook_local`），Hook 侧读不到。
  界面已对此做提示。

### 4.2 开关的四级闸门

Hook 是否加载，按顺序判断（见 `PrefsBridge.isHookEnabled`）：

1. `global_master_enable` —— 模块总开关
2. `global_safe_mode` —— 安全模式（暂停一切）
3. `app_master::<包名>` —— 该 App 的总开关
4. `hook::<hookId>` —— 单个功能开关

**UI 上**：Hook 列表页每个 App 卡片头部的 Switch = 第 3 级；展开后的每个 Switch = 第 4 级。

### 4.3 libxposed API 102 的 hook 语义（重要！）

与旧版 XposedBridge **完全不同**，是「拦截器链」模型：

```kotlin
module.hook(method)
    .setPriority(XposedInterface.PRIORITY_DEFAULT)
    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
    .intercept { chain ->
        chain.args[0] = "改参数"        // List<Any?> 可直接改
        chain.proceed()                 // 执行原方法，返回值即最终返回值
        // 或 return 某值 / chain.proceedWith(某值)  → 跳过原方法
    }
```

- **没有** `setResult()` / `getResult()`
- `Chain` 提供：`getExecutable / getThisObject / getArgs / getArg(i) / proceed() / proceed([args]) / proceedWith(result[,args])`
- `HookHandle.unhook()` 可取消；`HookBuilder.setId()` 可给 hook 命名
- 模块生命周期：`onModuleLoaded` → `onSystemServerStarting` / `onPackageReady`
- `XposedModule` 还提供：`log()`、`getRemotePreferences()`、`getModuleApplicationInfo()`、
  `getFrameworkName/Version/VersionCode/Properties()`、`deoptimize()`、`getInvoker()`

**本项目已把上述细节封装进 `BaseHook`**，写 Hook 时优先用 `before` / `replace` / `hookAllMethods`。

### 4.4 界面导航（Miuix `miuix-nav`，重要！）

**子页面一律是顶层整屏路由，不要在某页容器内做局部切换。**

```
SpaceHookNavHost (ui/SpaceHookNavHost.kt)
 └─ NavDisplay(nav, transition = NavTransitions.Modal, effects = …)     ← Y 轴转场 + 内置预测性返回
     ├─ entry<Route.Main>        → MainScreen：SmallTopAppBar + NavigationBar（首页/Hook/设置）
     ├─ entry<Route.UserApps>    → SubPageScaffold + UserAppsScreen
     └─ entry<Route.HookConfig>  → SubPageScaffold + TilePickerScreen / CardOrderScreen
```

- 每个路由**自带 Miuix `Scaffold`**：页面的 `OverlayDialog` 必须有 Scaffold 宿主，切页时整屏
  （含标题栏、底部页签）一起动；返回键放 `SmallTopAppBar(navigationIcon = …)`，**不要**再写「← 返回」卡片
- 转场只用库里的预设：`NavTransitions.Modal`（Y 轴，自下而上）/ `MiuixDefault`（X 轴）/ `None`（立即）
- 预测性返回、边缘侧滑、下滑关闭（`entry(swipeDismiss = NavSwipeDirection.TopToBottom)`）都是库内置的，
  **不要再手写 `PredictiveBackHandler` + graphicsLayer 转场**
- 路由键用 `data object` / `data class`（相同 `hookId` 不能重复入栈，否则 contentKey 冲突会抛异常）
- ⚠️ **Miuix 0.9.4 的 class 文件是 JVM 21**：`entry { }` 是 inline 函数，`:app` 的
  `jvmTarget`/`compileOptions` 必须 ≥ 21，否则报 `Cannot inline bytecode built with JVM target 21 …`
- 做 Miuix 界面改动时加载 **`miuix-style` skill**（已装在 `~/.agents/skills/miuix-style`），
  自带只读盘点与风格审计脚本：`inspect-project.ps1` / `check-miuix-style.ps1`

---

## 5. 如何新增一个 Hook（标准流程）

> 目标：新增功能时**只改两处 + 加一个文件**，UI 自动适配。

### Step 1 — 用 jadx 定位 hook 点

见第 6 节。

### Step 2 — 登记元数据

编辑 `common/.../HookCatalog.kt`：

```kotlin
// 若目标 App 未登记，先加到 targetApps
TargetApp(
    packageName = "com.example.app",
    displayName = "示例应用",
    category = AppCategory.THIRD_PARTY,
    note = "一句话说明",
),

// 再加入功能
HookMeta(
    id = "my_new_hook",                     // 全局唯一，用于偏好键，发布后不可改
    name = "功能名称",
    description = "这个功能做什么",
    targetPackages = listOf("com.example.app"),
    category = HookCategory.OTHER,
    defaultEnabled = false,
    hookPoints = listOf("com.example.app.Foo#bar"),
),
```

### Step 3 — 实现 Hook

新建 `libhook/.../rules/<分类>/MyNewHook.kt`：

```kotlin
class MyNewHook : BaseHook("my_new_hook") {
    override fun onHook() {
        val clazz = requireClass("com.example.app.Foo") ?: return
        replace(clazz, "bar", arrayOf(String::class.java)) { chain ->
            val origin = chain.proceed() as? Boolean ?: false
            !origin                       // 反转原返回值
        }
    }
}
```

### Step 4 — 注册映射

编辑 `libhook/.../base/HookRegistry.kt`：

```kotlin
"my_new_hook" to { MyNewHook() },
```

### Step 5 — 登记作用域

编辑 `libhook/src/main/resources/META-INF/xposed/scope.list`，加入目标包名。

### Step 6 — 更新文档

在 `docs/HOOKS.md` 补一行；在 `docs/PROGRESS.md` 记录本次改动。

---

## 6. 反编译分析（jadx）

> ✅ **推荐方案：jadx-headless-mcp**（无头，已在 2026-09-26 验证可用）
> 🚫 **已弃用（2026-10-03）**：`jadx-ai-mcp` 插件不再使用（8650 作废），jadx-GUI 不再打开
> （`jadx-gui-1.5.6-all.jar` 恢复保留，仅以 CLI 模式调用，见方式 B）。

### 方式 A：jadx-headless-mcp（首选）

`jadx-ai-mcp` 是**嵌在 jadx GUI 里的插件**，没有 GUI 上下文就退出，无法无头运行
（该插件已于 2026-10-03 弃用，jadx-GUI 不再打开）。
`jadx-headless-mcp` 是单进程 Java 重写版，jadx-core 与 MCP 服务同进程，
走 Streamable HTTP，天然适合自动化。

```bash
# 启动（jar 已在本地，无需下载）
export JAVA_HOME="C:/Program Files/Java/latest/jdk-21"
export JADX_CACHE_DIR="D:/Code/Android/APK/out/jadx-cache"   # 必须设置！默认值 E:\JADX_CACHE_DIR 不存在

"$JAVA_HOME/bin/java.exe" -Xmx8g -Djava.awt.headless=true \
  -jar "D:/Otheapps/Jadx-MCP/jadx-headless-mcp-v2.jar" \
  --host 127.0.0.1 --port 8660 \
  --apk "D:/Code/Android/APK/Hook/录音机_8.1.5-779121d13.apk"
```

- MCP 端点：`http://127.0.0.1:8660/mcp`
- ⚠️ **8650 端口作废** —— 那是已弃用的 jadx-ai-mcp 插件端口，勿使用
- ⚠️ **必须设 `JADX_CACHE_DIR`**，否则报 `Unable to determine if root directory exists`
- 已写入 `~/.workbuddy/mcp.json`，客户端可直接连（需先手动启动服务）

**调用工具**（项目内置客户端 `tools/jadx_mcp_cli.py`）：

```bash
PY="C:/Users/SpaceJoy/.workbuddy/binaries/python/versions/3.13.12/python.exe"
cd D:/Code/Android/Projects/LSPosed/Xposed.spacejoy.top

"$PY" tools/jadx_mcp_cli.py tools                          # 列出 32 个工具
"$PY" tools/jadx_mcp_cli.py current                        # 当前 APK 与索引状态
"$PY" tools/jadx_mcp_cli.py load "D:/path/to/app.apk"      # 加载/切换 APK

# 逆向主力：搜字符串常量（比按类名猜更可靠，混淆名也能定位）
"$PY" tools/jadx_mcp_cli.py call search_string_constants '{"keyword":"playback_auto"}'
# 调用关系
"$PY" tools/jadx_mcp_cli.py call get_xrefs_to_method \
    '{"class_name":"com.example.Foo","method_name":"bar"}'
"$PY" tools/jadx_mcp_cli.py call get_methods_of_class '{"class_name":"com.example.Foo"}'
"$PY" tools/jadx_mcp_cli.py call get_class_source '{"class_name":"com.example.Foo"}'
```

32 个工具里最常用的：`search_string_constants`、`find_string_usages`、
`get_xrefs_to_method` / `get_xrefs_from_method`、`get_methods_of_class`、
`get_class_source`、`get_subclasses`、`get_android_manifest`、`load_apk`。

### 方式 B：jadx CLI 全量反编译（快，适合 grep 工作流）

⚠️ **jadx-GUI 界面不再使用（2026-10-03）**；jadx 安装目录是 **`D:/Otheapps/jadx-gui`**，
其中的 `jadx-gui-1.5.6-all.jar` **仅以 CLI 模式调用**（`jadx.cli.JadxCLI`，已实测 `--version` = 1.5.6），
**不启动 `jadx-gui-1.5.6.exe`**。
25 MB 的 APK 约 30–40 秒出全量源码：

```bash
JAVA="C:/Program Files/Java/latest/jdk-21/bin/java.exe"
JADX_JAR="D:/Otheapps/jadx-gui/lib/jadx-gui-1.5.6-all.jar"

"$JAVA" -cp "$JADX_JAR" jadx.cli.JadxCLI \
  -d D:/Code/Android/APK/out/<名称> \
  --no-res -j 4 \
  "D:/Code/Android/APK/Hook/<目标>.apk"
```

已反编译产物（可直接 grep）：

| 目录 | APK | 类数 |
|---|---|---|
| `APK/out/systemui` | 系统界面 17.03.260226.r | 30,160 |
| `APK/out/sysuiplugin` | 系统界面组件 18.3.2.22.0 | 4,525 |
| `APK/out/soundrecorder` | 录音机 8.1.5 | 5,385 |
| `APK/out/hyperceiler` | **HyperCeiler 2.10.166-55d51aa8d-r4667**（参考模块，非 Hook 目标） | 3,999 |
| `APK/out/hyperstar` / `miuihome` / `miuihome2` | HyperStar / 系统桌面（UI 参考） | — |

### 参考项目（本地检出，**实现参考**，不是 Hook 目标）

本地参考项目都在 **`D:/Code/Android/Projects/Referenzprojekt/`** 下（**2026-10-02 从 `OSHook/references` 迁来**），这里**只登记、不更新**：

> ⚠️ **「本地最后提交」是写入本文档那一刻的快照**；**部分仓库可能落后于上游**，
> 作者有空会自行 `git pull` 更新 —— **不要据此断定版本**，用之前先自己 `git log -1` 核对。
> 另外 `HyperCeiler` 还有一份**发布版 APK 的反编译产物**（见上方「已反编译产物」表）。

| 项目 | origin（GitHub） | 本地最后提交（快照） | 用途 |
|---|---|---|---|
| **HyperCeiler** | `ReChronoRain/HyperCeiler` | `4f1402b6e` 2026-10-02（手头 APK 是 `55d51aa8d` 的构建，检出已领先） | **主要参考**：Hook 实现思路 + **明文目标类名**（查类名 grep 这份，别 grep APK） |
| HyperStar | `YunZiA/HyperStar` | `8a1362a` 2026-05-14（master） | 控制中心磁贴卡片编辑交互（本模块 `cc_card_tiles` 即参考其 `QSCardTileList`） |
| HyperIsland | `1812z/HyperIsland` | `f7d04c8a` 2026-10-02 | 首页状态卡 / 状态格子布局参考（Flutter 项目） |
| InstallerX-Revived | `wxxsfxyzm/InstallerX-Revived` | **非 git**（tarball 快照，2026-09-27 下载） | 首页 Miuix 布局（`MiuixHomePage.kt`） |
| janus | `penguinyzsh/janus` | `27e2831` 2026-04-06 | 各 App Hook 点线索（**本项目尚未用到**） |
| SignHook | `xihan123/SignHook` | `2185ff1` 2026-06-21 | 同上，仅备查（**未分析**） |
| Capsulyric | `FrancoGiudans/Capsulyric` | `dd020bc` 2026-09-28 | 同上，仅备查（**未分析**） |
| SoundMan | `killerprojecte/SoundMan` | `963c360` 2026-10-01 | 按应用音量 / 按应用固定输出设备 / HyperOS 音量侧栏入口（YukiHookAPI，hook 目录约 14,000 行）；**分析完成，见 `docs/ANALYSIS-SoundMan.md`**（2026-10-03，移植未开始） |
| AdbStayAwake | `GeneralPeople1970/AdbStayAwake` | `2eb2e74` 2026-09-21 | ADB 调试时保持屏幕常亮（libxposed API 102 单文件模块）；**已移植为 `adb_stay_awake`**（2026-10-03，见 `docs/HOOKS.md` 第 6 节） |

**用 HyperCeiler 找参考时的两个坑（已实测）**：

1. **发布版 APK 里目标类名是加密的，grep 不到** —— 它用 `lsparanoid` 做字符串混淆，
   类名/方法名都变成 `Deobfuscator$HyperCeiler$libhook.getString(-1565086082435137L)`。
   要查目标类名请 grep **源码**（`library/libhook/...`，明文）；APK 反编译产物只用来看类结构与实现路径。
2. **`hooker/` 目录不是 Hook 实现** —— `com.sevtinge.hyperceiler.hooker.*` 下是**设置界面**
   （`*Settings.java` 都是 `DashboardFragment`）；Hook 实现全在
   **`com.sevtinge.hyperceiler.libhook.rules.<app>/...`**，别在 `hooker/` 里翻。

> ⚠️ HyperCeiler 体量很大且与本项目 API 版本不同，只作**线索来源**；
> 本项目仍按「jadx 复核 → 编译期 Kotlin 实现」的流程改写，不直接搬运代码。

### 网络注意事项

- 本机有系统代理（`http_proxy=127.0.0.1:58899`）+ Watt Toolkit（**host 模式**）加速 GitHub
- **访问 `localhost` 一律加 `--noproxy '*'`**，否则请求被代理吞掉，表现为「端口在监听但无响应」
- Python 客户端已内置 `ProxyHandler({})` 禁用代理
- GitHub 走 hosts 加速后，`--noproxy '*'` 可正常访问；Maven Central / dl.google.com 走代理

### APK 素材（`D:/Code/Android/APK/Hook`）

全部 12 个（包名 / 版本已用 `aapt2 dump badging` 核实，2026-10-03 全量复核）：

| APK | 包名 | 版本 | 用途 |
|---|---|---|---|
| `系统界面_17.03.260226.r.apk` | `com.android.systemui` | 17.03.260226.r | LockColon / DeviceCenterSize / cc_card_* 等 4 个 SystemUI hook |
| `系统界面组件_18.3.2.22.0.apk` | `miui.systemui.plugin` | 18.3.2.22.0 | 控制中心插件代码（DeviceCenterSize 的类来源） |
| `录音机_8.1.5-779121d13.apk` | `com.android.soundrecorder` | 8.1.5-779121d13 | DisableListAutoPlay |
| `123云盘_3.3.0.apk` | `com.mfcloudcalculate.networkdisk` | 3.3.0 | InstallerRedirect |
| `时钟_18.26.0.apk` | `com.android.deskclock` | 18.26.0 | 新增素材（2026-10-02），**用途待定** |
| `网易云音乐_9.6.05.apk` | `com.netease.cloudmusic` | 9.6.05 | 新增素材（2026-10-02），**用途待定** |
| `抖音不裁剪.apk` | `io.github.dunxuan.douyinnocrop` | 1.1.0 (8) | 新增素材（2026-10-03），第三方工具；**已移植为 `douyin_nocrop`**（见 `docs/HOOKS.md` 第 5 节） |
| `抖音.apk` | `com.ss.android.ugc.aweme` | 40.6.0 (400601) | 新增素材（2026-10-03），`douyin_nocrop` 的 hook 点复核依据（dexdump 按 class_defs 验证） |
| `系统桌面_RELEASE-8.01.02.7722-….apk` | `com.miui.home` | 8.01.02.7722 | 桌面参考（已有 `APK/out/miuihome*` 反编译产物） |
| `HyperCeiler_2.10.166-55d51aa8d-r4667.apk` | `com.sevtinge.hyperceiler` | 2.10.166 | 参考模块（**非 Hook 目标**） |
| `HyperStar_2.2.0_202604250117.apk` | `com.yunzia.hyperstar` | 2.2.0 | 参考模块（**非 Hook 目标**） |
| `三合一状态图标_1.4.3.apk` | `com.aiden.combinedstatus` | 1.4.3 (29) | 新增素材（2026-10-03），libxposed API 102 同源；移植尝试已撤销，**用途待定** |

> 💡 查任意 APK 包名的最快方式（**无需 jadx、无需反编译**）：
> ```bash
> "D:/Code/Android/SDK/build-tools/36.1.0/aapt2.exe" dump badging app.apk | grep "^package:"
> ```

**⚠️ 手机端 APK 版本更新时，改提手机端 APK 来分析**（本地素材可能是旧版，分析旧包会得出失效的类名/方法名）：

```bash
# 1) 对比版本：手机端 vs 本地素材
adb -s 192.168.31.5:5555 shell dumpsys package com.example.app | grep -E "versionName|versionCode"
# 2) 手机端更新 → 提取手机端 APK 到素材目录
adb -s 192.168.31.5:5555 shell pm path com.example.app      # 输出 package:/data/app/.../base.apk
adb -s 192.168.31.5:5555 pull /data/app/.../base.apk "D:/Code/Android/APK/Hook/<名称>.apk"
# 3) 只做小范围分析时，也可以不 pull，直接用手机端 MT 管理器 MCP（见下节）
```

### 手机端 MT 管理器 MCP（`http://192.168.31.5:8787/mcp`）

手机上 MT 管理器开的 **MCP 服务**（Streamable HTTP + JSON-RPC，server = `MT MCP 0.2.0`，
协议 `2025-11-25`），**已实测可用**：`POST initialize` → HTTP 200 返回 `serverInfo`，
`tools/list` 可直接取（当前 **52 个工具**，无需 session）。

- **`mt_apk_*`（31 个）—— 手机端 APK 工作区分析/改包**：
  `mt_apk_open` / `mt_apk_list` / `mt_apk_search` / `mt_apk_read_text` / `mt_apk_read_bytes`、
  **`mt_apk_dex_outline_class`（类大纲）、`mt_apk_dex_xref`（交叉引用）**、
  `mt_apk_resource_read` / `mt_apk_resource_xref`、
  `mt_apk_native_*`（so 反汇编 / CFG / 字符串与指令 patch）、
  `mt_apk_edit_*` + `mt_apk_build`（改包重打包）
- **`mt_file_*`（21 个）—— 手机端文件系统**：`mt_file_list` / `mt_file_search` /
  `mt_file_read_text` / `mt_file_search_text` / `mt_file_copy` / `mt_file_patch_bytes` …
- 用途：**手机端 APK 已更新、本地还没提包时**，直接在手机上做 dex 查类/交叉引用，
  或用 `mt_file_copy_workspace_entry` 把单个条目取下来看（注意它拒绝 `classes*.dex` / `resources.arsc` / `AXML`）
- ⚠️ **调用姿势（PowerShell 踩坑）**：JSON body 直接内联在命令行里会被引号解析吃掉，报
  `Parse error`（HTTP 400）；先把 JSON 写进临时文件，再用 `curl --data-binary @文件` 发

```bash
# 最小握手示例
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"dsh","version":"1"}}}' > /tmp/m.json
curl -sS -X POST http://192.168.31.5:8787/mcp -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" --data-binary @/tmp/m.json
```

### 旧实现参考（重要线索，但**不能直接复用**）

`D:/Code/Android/Hook/<Name>/{info.prop, main.java}` 是**旧 LSPilot 框架的 BeanShell 脚本**，
记录了作者当时定位到的类名/方法名/踩坑结论，是当前 Hook 实现的**主要线索来源**。
本项目按需求用 jadx 重新验证并改写为编译期 Kotlin，而非直接搬运。

---

## 6.5 修改模块代码后如何真正生效 ✅（已联网核实 + 实测验证）

**结论：覆盖安装 + 重启目标应用就够了，完全不需要卸载重装。**

LSPosed 官方文档与实际行为一致：

> **Upgrade**: Install over the previous version (same signing key);
> Confirm the module is enabled in LSPosed with scope limited to target apps;
> **Force-stop the target app and reopen.**

另一份指南也确认：
> If the modules were installed manually, simply **installing the new APK over the existing one
> and restarting** is usually sufficient.

### 连接真机（无线 adb）

手机是 `Xiaomi 25098PN5AC`（`192.168.31.5`，同一路由器下），**两种连法**：

```bash
# 首选：5555 是手机端「用模块固定」的 adb TCP 端口 —— 重启仍然有效，不用重开无线调试
adb connect 192.168.31.5:5555

# 备用：无线调试（TLS）
adb mdns services        # 找 _adb-tls-connect._tcp 直接 connect
adb pair <ip:port> <码>  # 仅首次/换电脑时需要；配对码每次打开配对弹窗都会刷新（旧码会报 protocol fault）
```

- 手机同时存在多条连接（5555 + TLS + 幽灵 `emulator-5554 offline`）时，命令必须带
  `-s 192.168.31.5:5555`，否则 adb 报 `more than one device`
- 幽灵 `emulator-5554 offline` 来自本地 5555 被 `Androws.exe` 占用，与手机无关，忽略即可
- 5555 是**明文 adb**，只在家庭网络可接受

### 标准更新流程

```bash
# 1. 构建
gradle :app:assembleDebug

# 2. 覆盖安装（不卸载！）
adb install -r -t app/build/outputs/apk/debug/app-debug.apk

# 3. 重启目标应用，让 Hook 重新加载
adb shell am force-stop com.android.soundrecorder
adb shell am start -n com.android.soundrecorder/.StartActivity
#   SystemUI 用：adb shell "su -c 'killall com.android.systemui'"

# 4. （可选）确认 LSPosed 已同步新路径
adb shell pm path top.spacejoy.spacehook
```

覆盖安装会**保留** LSPosed 的 `enabled` 与 `scope` 配置，**无需重新启用**。

### ⚠️ 一个必须记住的排查陷阱：查数据库一定要连 `-wal`

LSPosed 数据库是 **WAL 模式**。**只拉 `modules_config.db` 会读到旧快照**，
导致误判「LSPosed 没更新 `apk_path`」—— 这个误判曾让我们错误地以为必须卸载重装。

```bash
adb shell "su -c 'cd /data/adb/lspd/config && cp modules_config.db modules_config.db-wal /data/local/tmp/ && chmod 666 /data/local/tmp/modules_config.db*'"
adb pull /data/local/tmp/modules_config.db     ./mc.db
adb pull /data/local/tmp/modules_config.db-wal ./mc.db-wal   # ← 关键，不能少
```

### 什么时候才真的需要卸载

只有 **`scope.list` 有变动但 LSPosed 未刷新作用域建议** 时才考虑；
即便如此也优先尝试「在 LSPosed 里关闭再开启模块」或「重启设备」，
而不是卸载重装（重装会清空 `enabled` 与 `scope`，反而更麻烦）。

### LSPosed 数据库表结构（排查用）

| 表 | 内容 |
|---|---|
| `modules` | 模块包名 → `apk_path` |
| `modules_state` | `enabled`（是否启用） |
| `scope` | 实际勾选的作用域（**空 = 对所有应用生效**） |
| `module_configs` | RemotePreferences（`group_name` / `key_name` / `data`） |

---

## 7. 当前状态与下一步

详见 `docs/PROGRESS.md`。**每次实质性改动后请更新该文件**，这是多 Agent 协作的前提。

---

## 8. 代码约定

- **包名**：`top.spacejoy.spacehook.*`
- **类名** PascalCase；常量 UPPER_SNAKE_CASE；Hook id 小写下划线
- **`common` 模块禁止**引入 libxposed / Compose 依赖（会被 UI 进程加载）
- Hook 逻辑必须 `try-catch` 包裹（`BaseHook` 已内建），防止拖垮目标应用
- 新增 Kotlin 文件后注意 import；AndroidX 与 Miuix 的 `Text` 同名，优先用 Miuix 的
- 不要提交 `local.properties`
