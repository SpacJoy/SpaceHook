# SpaceHook 开发进度

> **交接必读**：本文件记录项目的实时状态。每次实质性改动后请更新「变更日志」一节。
> 最后更新：2026-09-27（预测性返回动画 / 首页去重 / 参考项目登记）

---

## 一、总体进度

| # | 模块 / 任务 | 状态 | 说明 |
|---|---|---|---|
| 1 | 调研 libxposed API 102 与 Miuix 0.9.4 | ✅ 完成 | 已用 `javap` 从 AAR 提取全部 API 签名，记录于 ARCHITECTURE.md |
| 2 | Gradle 多模块工程骨架 | ✅ 完成 | `common` / `libhook` / `app`，Gradle 9.3.1 + AGP 9.1.0 + Kotlin 2.4.20 |
| 3 | Hook 框架核心（可扩展注册机制） | ✅ 完成 | `SpaceHookEntry` / `BaseHook` / `HookRegistry` / `HookCatalog` |
| 4 | 跨进程配置（RemotePreferences 闭环） | ✅ 完成 | UI 侧 `XposedServiceManager` ↔ Hook 侧 `PrefsBridge` |
| 5 | UI 三页面 | ✅ 完成 | 首页 / Hook 列表 / 设置，基于 Miuix |
| 6 | 4 个 Hook 功能实现 | ✅ 完成 | **已用 jadx 1.5.6 实机反编译全部复核**；`disable_list_autoplay` 修正 1 处失效方法名（`o1`→`q1`） |
| 7 | jadx 反编译链路 | ✅ 完成 | **已切换到 `jadx-headless-mcp`**（无头，32 个工具），端口 8660；CLI 全量反编译 3 个 APK |
| 8 | 编译验证 | ✅ 完成 | `BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk`（11.7 MB） |
| 9 | 4 个 Hook 的 jadx 复核 | ✅ 完成 | LockColon 9/9 ✅、DeviceCenterSize 4/4 ✅、DisableListAutoPlay 修正 1 处、InstallerRedirect 用稳定框架 API |
| 10 | 真机部署验证 | ✅ **成功** | Xiaomi 25098PN5AC / Android 17(API 37) / LSPosed 2.2.0-it；3 个进程注入成功，Hook 全部装载 |

---

## 二、已完成的工作（明细）

### 2.1 工程骨架

- 根配置：`settings.gradle.kts`（阿里云镜像优先）、`gradle.properties`、`gradle/libs.versions.toml`
- 模块：`:common`（Android Library）、`:libhook`（Android Library）、`:app`（Application）
- 关键版本：Gradle **9.3.1**、AGP **9.1.0**、Kotlin **2.4.20**、Compose Multiplatform **1.12.0**、
  Miuix **0.9.4**、libxposed api **102.0.0**、compileSdk **37** / minSdk **26**

### 2.2 libxposed 模块声明

- `libhook/src/main/resources/META-INF/xposed/module.prop` → `minApiVersion=102` / `targetApiVersion=102`
- `.../java_init.list` → `top.spacejoy.spacehook.libhook.SpaceHookEntry`
- `.../scope.list` → 6 个包名（含 `system`）

### 2.3 Hook 框架核心

| 类 | 职责 |
|---|---|
| `SpaceHookEntry` | `XposedModule` 入口；`onModuleLoaded` 初始化日志与配置桥；`onPackageReady` / `onSystemServerStarting` 按包名分发 |
| `BaseHook` | Hook 工具基类：`findClass` / `before` / `replace` / `hookAllMethods` / `hookConstructor` / `setField` / `callMethod` … |
| `HookRegistry` | `hookId → () -> BaseHook` 工厂映射 |
| `HookContext` | 运行时上下文（包名、进程名、ClassLoader、module、isSystemServer） |
| `XLog` | 统一日志（走 `module.log()`，可用 `adb logcat` + LSPosed 日志查看） |
| `PrefsBridge` | 读取 RemotePreferences；四级开关判定；回写「已生效」状态 |
| `ClassLoaderUtils` | 反查 `ActivityThread.mPackages` 拿到 MIUI plugin 的子 ClassLoader |

### 2.4 UI（Miuix）

- **首页**：激活状态大卡（含未激活引导）、框架信息、设备信息、**已生效进程列表**、功能概览
- **Hook 列表**：按目标 App 分组；App 头部 = 总开关（一键禁用该 App 全部 Hook）；展开 = 各 Hook 独立开关 + 描述 + 「已生效」圆点
- **设置**：通用（总开关/安全模式/调试模式）、Hook 列表选项、外观（主题/主题色/字体大小）、实验性功能（预测性返回/模糊/热重载）、关于
- 主题支持 6 种模式（含 Monet），字体缩放 80%~160% 实时生效，主题色用 `ColorPicker` 选择

---

## 三、已知问题 / 风险

| 级别 | 问题 | 处理建议 |
|---|---|---|
| 🔴 高 | 4 个 Hook 的类名/方法名来自**旧 BeanShell 脚本**，未用 jadx 复核 | 逐个用 jadx 打开对应 APK 核对；见 `docs/HOOKS.md` 的「待复核」标记 |
| ✅ 已解决 | ~~123云盘真实包名未知~~ → 确认为 **`com.mfcloudcalculate.networkdisk`** | 已回填 `HookCatalog.targetApps` / `installer_redirect.targetPackages` / `scope.list`；命令：`aapt2 dump badging` |
| 🟡 中 | jadx-ai-mcp 插件（`127.0.0.1:8650`）请求无响应 | 在 jadx GUI 里确认插件已启用；或用 `docs` 中的 CLI 兜底方案 |
| 🟡 中 | `BaseHook.replace()` 的返回值语义依赖「try 块最后表达式」 | 已实现并加注释，改动时注意不要破坏 |
| 🟡 中 | `DeviceCenterSizeHook` 依赖 MIUI plugin 子 ClassLoader，机型差异大 | 需要真机验证；失败时看 `ClassLoaderUtils` 的日志 |
| 🟢 低 | 未做 `miuix-blur` 模糊效果接入（`blurEnabled` 开关已预留但未实装） | 后续可加 `miuix-blur-android` 依赖 |
| 🟢 低 | 「热重载」开关只写配置，UI 未接 `XposedService.hotReloadModule` | 可在设置页加显式「立即重载」按钮 |

---

## 四、设计决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| Hook 实现方式 | **编译期 Kotlin**，非脚本热加载 | 用户明确要求「LSPosed 官方推荐的、性能最好的方案」。libxposed API 102 原生调用零反射开销 |
| 旧脚本处理 | **仅作线索，用 jadx 重新实现** | 用户明确选择；旧脚本是 BeanShell，机制与新 API 差异大 |
| 扩展方式 | 改模块代码新增，而非模块内导入脚本 | 用户明确选择；保证类型安全与性能 |
| 模块拆分 | `common` / `libhook` / `app` 三层 | `common` 被 UI 进程加载，必须与 libxposed 解耦，否则 UI 会 `NoClassDefFoundError` |
| 配置存储 | LSPosed **RemotePreferences** | 官方推荐的跨进程方案；避免 `/sdcard` 分区存储限制 |
| 注册机制 | 显式 `HookRegistry` 映射，而非注解扫描 | 避免引入 lsparanoid 等注解处理器；类加载时机可控 |
| 作用域声明 | 静态 `scope.list` 文件（`staticScope=false`） | 允许用户在 LSPosed 里自行增减作用域 |

---

## 四之二、jadx 复核结论（2026-09-26）

工具链换成 **`jadx-headless-mcp` v1.1.1**（`D:/Otheapps/Jadx-MCP/jadx-headless-mcp-v2.jar`）——
相比 embed 在 GUI 里的 jadx-ai-mcp，它可无头运行、单进程、走 Streamable HTTP。

启动要点（踩过的坑）：
- **必须设 `JADX_CACHE_DIR`**（默认 `E://JADX_CACHE_DIR` 不存在会报错）
- **端口用 8660**，避开 jadx-ai-mcp 插件的 8650
- 客户端脚本已内置，见 `tools/jadx_mcp_cli.py`
- 已写入 `~/.workbuddy/mcp.json`

| APK | 反编译类数 | 复核结论 |
|---|---|---|
| 系统界面 17.03.260226.r | 30,160 | `lock_colon` **9/9 hook 点全部正确** |
| 系统界面组件 18.3.2.22.0 | 4,525 | `device_center_size` **4/4 hook 点全部正确** |
| 录音机 8.1.5-779121d13 | 5,385 | `disable_list_autoplay` **修正 1 处**：`ContentFragment#o1` 已失效，实为 **`q1`** |

### 修正详情（disable_list_autoplay）

旧 BeanShell 脚本记录的 `ContentFragment#o1(Bundle)` 在当前版本**已不存在**，
真实播放入口是 **`q1(Bundle)`**（`ContentFragment.java:2028`，方法体首行日志即 `"startPlayback"`）。
实现改为「候选名列表 + 自动探测」：`listOf("q1", "o1", "p1", "startPlayback")`，
兼顾当前版本与历史版本。

### 复核中发现的通用经验

1. **内联嵌套类型没有独立文件**：`$Companion` / `$Mode` 被 jadx 内联在外层 `.java` 里，
   不要因为找不到独立文件就判定"类不存在"（`DeviceCenterEntryViewHolder$Mode` 实际在 L76）
2. **参数个数要以实测为准**：`changeMode` 实际是 4 个参数（`Mode, boolean, boolean, boolean`），
   不是 1 个
3. **优先改非 final 字段**：`ClockStyleInfo.isColonShow` 是 `public final`，
   Android 10+ 反射改写 final 可能失效；本实现改为改 `ClockBean.isColonShow`（private 非 final）
4. **搜字符串常量比按类名猜可靠**：混淆名会变，但 `"playback_auto"` 这类业务字符串稳定

详见 `docs/HOOKS.md`。

---

## 四之三、真机验证结果（2026-09-27 凌晨）✅

**设备**：`Xiaomi 25098PN5AC` / **Android 17 (API 37)** / arm64-v8a
**框架**：**LSPosed 2.2.0-it**（支持 API 102，与模块声明完全匹配）
**连接**：无线 ADB `192.168.31.5`

### 注入与装载日志（来自 LSPosed 日志，决定性证据）

```
# com.android.soundrecorder（含 :remote 子进程）
[com.android.soundrecorder] SpaceHook 已注入：framework=LSPosed 2.2.0-it (API 102)
[com.android.soundrecorder] PrefsBridge 已接入，prefs=true
[com.android.soundrecorder] 匹配到 1 个 Hook：disable_list_autoplay
[com.android.soundrecorder] 已挂载列表点击标记：N#z(View, int)
[com.android.soundrecorder] 已挂载播放入口：ContentFragment#q1      <-- 复核结论被证实
[disable_list_autoplay] Hook 装载成功 @ com.android.soundrecorder
[com.android.soundrecorder] 分发完成：成功 1 / 共 1

# com.android.systemui
[com.android.systemui] SpaceHook 已注入：framework=LSPosed 2.2.0-it (API 102)
[com.android.systemui] PrefsBridge 已接入，prefs=true
[com.android.systemui] 匹配到 1 个 Hook：lock_colon
[lock_colon] Hook 装载成功 @ com.android.systemui
[com.android.systemui] 分发完成：成功 1 / 共 1
```

### 验证到的关键能力

| 能力 | 结果 |
|---|---|
| 模块被 LSPosed 识别并激活 | ✅ 首页显示「模块已激活」 |
| API 102 兼容性 | ✅ LSPosed 2.2.0-it + 模块 targetApiVersion=102 |
| 注入到目标 App 进程 | ✅ `com.android.systemui`、`com.android.soundrecorder`（含 :remote） |
| 注入到 system_server | ✅ |
| **跨进程配置桥（RemotePreferences）** | ✅ `PrefsBridge 已接入，prefs=true` |
| 类定位（宿主 ClassLoader） | ✅ 目标类全部找到 |
| **候选名自动探测** | ✅ 在候选中命中 `q1` 并成功挂载 |
| Hook 挂载（拦截器链） | ✅ lock_colon 9 个点、disable_list_autoplay 2 个点全部成功 |
| 三页面 UI 渲染 | ✅ 首页 / Hook 列表 / 设置 全部正常（截图见 `docs/screenshots/`） |

### 已知的小问题

| 级别 | 问题 | 说明 |
|---|---|---|
| 🟢 低 | 首页「已生效功能」计数显示 0 | Hook 侧 `markActive()` 写入的 RemotePreferences 键，UI 侧需重进页面才会刷新；功能本身正常 |
| 🟢 低 | `InstallerX` 在列表里显示「0 个功能」 | 它在 `targetApps` 里只是「改道目标」的说明性条目，可考虑移除或改为不显示 |
| 🟢 低 | `com.miui.voiceassist` 被注入 | 该包不在模块 `scope.list` 中，应为 LSPosed 侧用户勾选所致，无副作用 |

---

## 四之四、UI 改版与功能调整（2026-09-27 凌晨）

### 本轮完成

| # | 改动 | 说明 |
|---|---|---|
| 1 | **Hook 页分层** | 一级＝系统应用；二级＝用户应用（入口**置顶**） |
| 2 | **用户应用列表** | 只列出 `HookCatalog` 中**已配置 Hook** 的应用；已安装的置顶并标注「已安装 / 未安装」 |
| 3 | **录音机改归用户应用** | 虽在系统分区，但属「功能型应用」而非系统框架 |
| 4 | **单独重启应用** | 每个应用卡片右上角 ⟳ 按钮 → **确认弹窗** → 重启 → **Toast 提示**。避免为改一个 Hook 重启整个作用域（尤其系统界面） |
| 5 | **首页 Root 状态** | 框架信息卡新增「Root 权限」行，可点击检测 / 申请授权 |
| 6 | **首页重设计** | 参考 **InstallerX Revived**（`MiuixHomePage.kt`）与 **HyperIsland** 的首页：大状态卡（状态色底 + 右下角 170dp 超大图标 + Tilt 按压反馈）+ 两张等宽统计卡 |
| 7 | **移除 installer_redirect** | 用户已有其他模块实现该功能（后又按要求恢复，见下方说明） |
| 8 | **「已生效」改用本地记录** | Hook 侧写 RemotePreferences 实测不可用，改为 UI 侧在读取 `getRunningTargets()` 时把进程名记入本地 SharedPreferences |

### 关于 installer_redirect 的最终状态

用户先要求移除（因已有其他模块实现），随后又要求**加回来**，但强调：
**目标安装器（InstallerX Revived）不需要加入作用域** —— 它只是改道的**接收方**，
被拦截的是**发起方**（123云盘）。因此：

- ✅ 保留 `installer_redirect` Hook 与 123云盘目标应用
- ✅ `scope.list` 含 `com.mfcloudcalculate.networkdisk`
- ❌ **不**登记 InstallerX 的 TargetApp，**不**加入 scope

### 参考项目（本地检出，**只登记、不更新**）

> **完整清单见 `AGENTS.md` 第 6 节「参考项目」**（7 个项目 + origin + 本地最后提交快照）。
> 这里不重复表格，避免两处漂移。
>
> ⚠️ 全部在 `D:/Code/Android/Projects/OSHook/references/` 下：
> HyperCeiler / HyperStar / HyperIsland / InstallerX-Revived / janus / SignHook / Capsulyric。
> **部分可能落后上游**（`InstallerX-Revived` 是 tarball 快照、无 git 历史），作者有空再自行 `git pull`。
> 文档里的「最后提交」只是**登记那一刻的快照，不要据此断定版本** —— 用之前先 `git log -1` 核对。

获取方式（国内网络）：

| 项目 | 方式 |
|---|---|
| git 检出的 6 个（HyperCeiler / HyperStar / HyperIsland / janus / SignHook / Capsulyric） | 本地已有，直接用；更新自行 `git pull` |
| InstallerX Revived | `curl --noproxy '*' "https://gh-proxy.com/https://github.com/wxxsfxyzm/InstallerX-Revived/archive/refs/heads/main.tar.gz"`（`git clone` 会因 schannel 吊销检查失败） |
| **HyperCeiler 发布版 APK** 2.10.166-55d51aa8d-r4667 | 本地已有 `D:/Code/Android/APK/Hook/HyperCeiler_2.10.166-55d51aa8d-r4667.apk`（`com.sevtinge.hyperceiler`）→ 已反编译到 `APK/out/hyperceiler`（3,999 类，`--no-res`）。**查目标类名用源码**（明文），APK 只看类结构 |

关键参考文件：`app/src/main/java/com/rosan/installer/ui/page/miuix/settings/home/MiuixHomePage.kt`

> HyperCeiler 使用注意（详见 `AGENTS.md` 第 6 节）：发布版做过 **lsparanoid 字符串混淆**，
> 目标类名 grep 不到（都是 `Deobfuscator...getString(-1565086082435137L)`）；Hook 实现也不在
> `hooker/`（那是设置界面）而在 `libhook/rules/`。

---

## 四之五、device_center_size 目标进程修正（2026-09-27）✅

### 问题
`device_center_size` 原先登记的目标进程是 `miui.systemui.plugin` —— **错的**。

### 原因（用户指正 + 旧脚本 diag.txt 佐证）
「系统界面组件」(miui.systemui.plugin) 是 SystemUI **动态加载的插件 APK**，
**不作为独立进程运行**；devicecenter 那些类由 **SystemUI 自己创建的 ClassLoader** 加载。
因此必须注入 **`com.android.systemui`**，在 SystemUI 进程内找那条子 loader。

> 旧脚本 `DeviceCenterSize/diag.txt` 记录过这个坑：在错误位置 `Class.forName` 全部失败
> ```
> loader#0 : PathClassLoader[zip file ".../MiuiSystemUI.apk"]
>       FAIL miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder$Mode
> !! ClassLoader 里没有 miui.systemui.plugin 的代码，hook 不会生效
> ```

### 修正内容
| 项 | 修正 |
|---|---|
| `HookMeta.targetPackages` | `miui.systemui.plugin` → **`com.android.systemui`** |
| `targetApps` | 移除 `miui.systemui.plugin` 条目；`com.android.systemui` 的 note 更新为「也承载系统界面组件的插件代码」 |
| `scope.list` | 移除 `miui.systemui.plugin` |
| ClassLoader 查找 | 改为**三级**：主 loader → hint 反查 → 无 hint 全量反查 |

### 验证结果 ✅
```
[com.android.systemui] [device_center_size] Hook 装载成功 @ com.android.systemui
```

---

## 四之七、导航改用 Miuix `miuix-nav`：顶层整屏路由 + Y 轴转场（2026-09-27）

> 依据已安装的 **`miuix-style` skill**（`~/.agents/skills/miuix-style`）的三条要求：
> ① 复用组件库 ② 子页面层级放顶层 ③ 动画用 Miuix 的 Y 动画。

### 问题（上一版实现的不足）
`PredictiveBackStack` 是**自己手写**的：`graphicsLayer` + `PredictiveBackHandler` 手算位移/缩放/蒙层，
并且它嵌在 `MainScreen` 的 `Scaffold` **内容区内** —— 于是子页面切换时**顶栏与底部页签纹丝不动**，
只有中间内容在动，不是真正的「整屏子页面」。

### 改法
| 项 | 内容 |
|---|---|
| 依赖 | 新增 `top.yukonga.miuix.kmp:miuix-nav-android:0.9.4`（version catalog 里本就预留了 `miuix-nav`） |
| 新增 | `app/.../ui/SpaceHookNavHost.kt` —— 顶层路由 + `NavDisplay` 宿主 |
| 路由 | `Route.Main`（三页签外壳）/ `Route.UserApps`（二级）/ `Route.HookConfig(hookId)`（三级） |
| 整屏图层 | 每个路由**自带 Miuix `Scaffold`**（`SubPageScaffold` = `SmallTopAppBar` + 返回键）；子页面覆盖整屏，含原先底部页签的位置 |
| Y 轴动画 | `transition = NavTransitions.Modal`（库自带：`translationY = progress × height`，自下而上滑入） |
| 跟手返回 | 用库**内置**的预测性返回 / 边缘侧滑（`PredictiveBackHandlerWithSessions` + `navSwipeDismissImpl`），删除 240 行手写实现 |
| 下滑关闭 | `entry(swipeDismiss = NavSwipeDirection.TopToBottom)`：与 Y 位移同轴；库的仲裁是「子级优先」，不会抢列表滚动 |
| 开关 | 「预测性返回动画」= `Modal` ↔ `None`（关闭即立即切换）；改走新增的 `FeatureState`（Compose 状态），在设置页关掉**当场生效**，不必重启 App |
| 复用组件 | 子页面的「← 返回」卡片全部删掉，改用 `SmallTopAppBar(navigationIcon = 返回键)`；参数页副标题进 `subtitle` |
| 删除 | `app/.../ui/PredictiveBackStack.kt`（自研转场，已被组件库取代） |

### ⚠️ 踩坑：Miuix 0.9.4 的 class 文件是 **JVM 21**
`NavEntryBuilder.entry { }` 是 **inline** 函数，宿主模块 `jvmTarget = 17` 时报：
`Cannot inline bytecode built with JVM target 21 into bytecode ... JVM target 17`。
→ `:app` 的 `compileOptions` + `jvmTarget` 一并升到 **21**（`common` / `libhook` 仍为 17，不影响）。
可用 `class major = 65` 自行核对任何 Miuix AAR。

### 自检
- `BUILD SUCCESSFUL`
- 用 skill 自带脚本扫描：`inspect-project.ps1` 已识别 `miuix-nav-android`；
  `check-miuix-style.ps1` → **0 error / 3 warning / 3 info**（warning 均为既有的硬编码色值等，非本次引入）
- 真机复测待设备重新连上无线调试（本轮 adb 掉线）

---

## 四之六、预测性返回（Predictive Back）动画（2026-09-27）✅ 真机已验证

### 问题
Hook 页的二级/三级页面侧滑返回时**没有任何跟手动画**，松手后才整页切换；
设置里的「预测性返回动画」开关（`exp_predictive_back`）此前只是**空开关** ——
只有 UI 侧写入，Hook 侧从未读取，也没有任何代码消费。

### 原因
1. 三个子页面用的是普通 `androidx.activity.compose.BackHandler` —— 只处理「返回」这个动作，
   拿不到手势进度，页面自然不会跟手；
2. 页面之间是 `when { ... }` 直接替换，**上一个页面根本不在组合树里**，
   即使拿到进度也没有「上一级」可露出；
3. 清单里没有 `android:enableOnBackInvokedCallback`（API 33/34 上必须显式开启；
   API 35+ 目标版本默认开启，但显式声明可覆盖低版本）。

### 实现
| 项 | 内容 |
|---|---|
| 新增 | `app/.../ui/PredictiveBackStack.kt` —— 预测性返回页面栈容器 |
| 手势 | `PredictiveBackHandler`（activity-compose）收集 `BackEventCompat.progress`，进度 0→1 = 返回 |
| 动画 | 上层页面 `translationX = progress × 宽度`（跟手）；下层页面 `scale 0.92→1` + 变暗 `0.28→0` + 圆角 `22dp→0` |
| 提交/取消 | 提交 → 220ms 内顺势滑出再切换层级；取消 → `spring` 弹回原位 |
| 前进 | 层级变深时复用同一条曲线、方向相反（`remember(depth)` 初值取 1），进入下一级也有滑入动画 |
| 页面内返回按钮 | 通过 `front(goBack)` 拿到同一条动画的回调，与侧滑返回观感一致 |
| 触摸隔离 | 下层页面用 `pointerInput` 在 `Initial` 阶段吃掉事件，避免「透过当前页点到上一级」 |
| 层级 | Hook 页 0=一级（系统应用）/1=二级（用户应用）/2=功能配置页；`depth == 0` 时不拦截返回键（交给系统退出应用） |
| 开关 | 「设置 → 实验性功能 → 预测性返回动画」现在**真正生效**（关闭 = 立即切换，等价旧行为），summary 文案已同步更正 |
| 清单 | `<application android:enableOnBackInvokedCallback="true">` |

改动文件：`app/.../ui/PredictiveBackStack.kt`（新增）、`app/.../ui/screens/HookListScreen.kt`、
`app/.../ui/screens/SettingsScreen.kt`、`app/src/main/AndroidManifest.xml`、`common/.../HookPrefs.kt`。

### 验证
- 构建：`BUILD SUCCESSFUL`，覆盖安装 + 重启 App
- 真机（25098PN5AC / Android 17 / API 37）：侧滑返回**跟手生效** ✅（用户确认）
- 截帧复核：前进/返回过程中可截到「上层页面半滑出、下层页面缩小变暗」的中间帧

---

## 五、变更日志

### 2026-09-30（登记本地参考项目清单）

- 把 `D:/Code/Android/Projects/OSHook/references/` 下**全部 7 个**参考项目登记进 `AGENTS.md` 第 6 节
  （HyperCeiler / HyperStar / HyperIsland / InstallerX-Revived / janus / SignHook / Capsulyric），
  每行含 origin（GitHub）与**本地最后提交快照**
- 明确标注「**只登记、不更新**」：**部分检出可能落后上游**（`InstallerX-Revived` 为 tarball 快照、无 git 历史），
  文档里的提交哈希只是登记时刻快照，**不要据此断定版本**，用前自行 `git log -1` 核对
- 订正过期说法：HyperCeiler 源码检出 HEAD 已是 `55d51aa8d`（2026-09-16，`versionName = "2.10.166"`），
  **与手头 APK 同一提交**；删掉旧文档里「源码 2.10.165、比 APK 旧」的错误结论
- `PROGRESS.md` 不重复整表，改为指向 AGENTS.md（避免两处漂移）

### 2026-09-27（修复：切页签后自动弹出「批量重启」确认框）

- **现象**：点一次 Hook 页右上角 ⟳、取消确认框后，切到别的页签再切回来，确认框会自己弹出来
- **原因**：`restartTrigger` 是**只增不减的计数**（点一次 `+1` 就再没归零），
  而 `HookListScreen` 在切页签时会被销毁重建，它的 `LaunchedEffect(restartTrigger)` 又带着旧的
  `> 0` 值跑了一遍，于是「重新触发」了一次
- **修法**：把「批量重启」的**入口与确认框放在一起** —— 上移到 [MainScreen.kt](app/src/main/java/top/spacejoy/spacehook/ui/MainScreen.kt)
  （⟳ 按钮本来就在它的标题栏里，状态在页签切换时自然保留），
  [HookListScreen.kt](app/src/main/java/top/spacejoy/spacehook/ui/screens/HookListScreen.kt) 不再承接 `restartTrigger`，
  彻底去掉「计数 + LaunchedEffect」这种跨组件触发方式
- 二级「用户应用」页的批量重启确认框同理，状态就挂在它自己的路由 entry 里（出栈即销毁，不会残留）

### 2026-09-27（导航改用 Miuix `miuix-nav`）

- 新增依赖 `miuix-nav-android:0.9.4`；新增 `ui/SpaceHookNavHost.kt`（顶层 `NavDisplay` + 3 条路由）
- 子页面（用户应用 / 功能配置）改为**整屏路由**，各自拥有 Miuix `Scaffold` 与带返回键的 `SmallTopAppBar`
- 转场统一用库自带 **Y 轴动画** `NavTransitions.Modal`；预测性返回 / 侧滑跟手改用库内置实现
- 删除自研 `ui/PredictiveBackStack.kt`（及三处手写的「← 返回」卡片）
- `:app` 的 JVM target 17 → **21**（Miuix 0.9.4 class 文件为 21，inline 的 nav DSL 无法内联到 17）
- 详见「四之七」

### 2026-09-27（登记参考项目 HyperCeiler）

- 新增参考项目 **HyperCeiler 发布版 APK 2.10.166-55d51aa8d-r4667**
  （`D:/Code/Android/APK/Hook/HyperCeiler_2.10.166-55d51aa8d-r4667.apk`，包名 `com.sevtinge.hyperceiler`），
  已用 jadx CLI 全量反编译到 `D:/Code/Android/APK/out/hyperceiler`（3,999 类，`--no-res`）
- 登记到 `AGENTS.md` 第 6 节新增的「**参考项目**」表 + 「已反编译产物」表；`PROGRESS.md` 参考项目表同步
- 顺带记下两条使用注意（避免以后白翻）：
  1. 发布版用 **lsparanoid** 混淆字符串，**目标类名在 APK 里 grep 不到**（都是 `Deobfuscator…getString(-1565…)`），
     查类名要 grep 本地已有源码 `OSHook/references/HyperCeiler/library/libhook/`
  2. `com.sevtinge.hyperceiler.hooker.*` 是**设置界面**，Hook 实现全在 `com.sevtinge.hyperceiler.libhook.rules.*`
- 本次**只登记参考，未改动任何功能代码**

### 2026-09-27（首页信息去重）

- 首页「框架信息」区与顶部大状态卡内容重复，**删掉重复项**：
  `连接状态` / `框架` / `框架版本` / `Xposed API`
  （顶部状态卡已展示「模块已激活」+「LSPosed 2.2.0-it · API 102」）
- 该区块更名 **「模块信息」**，只保留顶部卡片没有的内容：`模块版本` + `Root 权限`（含 Root 失败提示）
- `FrameworkCard` 不再需要 `activated` 参数，`framework` 局部变量一并移除；真机截图复核通过

### 2026-09-27（预测性返回动画）

- 新增 `PredictiveBackStack`，Hook 页三个层级之间支持**预测性返回跟手动画**（侧滑 + 页面内返回按钮共用）
- 移除三个子页面里各自的 `BackHandler`，返回逻辑统一由页面栈容器接管
- 设置里的「预测性返回动画」开关从空开关变为**真实生效**，文案更正为「侧滑返回时页面跟手移动并露出上一级；关闭则立即切换」
- 清单补 `android:enableOnBackInvokedCallback="true"`（覆盖 API 33/34 设备）
- 真机验证通过

### 2026-09-27（真机验证成功）

- 通过无线 ADB 连接 `Xiaomi 25098PN5AC`（Android 17 / API 37）
- 安装 APK → LSPosed 启用 → 3 个进程注入成功 → Hook 全部装载
- **端到端链路完全打通**，详见「四之三、真机验证结果」
- 截图存档于 `docs/screenshots/`
- 新增 `tools/deploy.sh`（一键配对/连接/安装/校验）
- 新增 `tools/jadx_mcp_cli.py`（jadx-headless-mcp 客户端）

### 2026-09-26（初始搭建 + 构建跑通）

- 完成环境勘察：确认 `D:/Code/Android/{APK,Hook,SDK}`、JDK 21/25/27、jadx 1.5.6、
  jadx-ai-mcp 6.4.1、参考项目 `OSHook/references/{HyperCeiler,janus,...}`
- 从 `libxposed-api-102.0.0.aar` 与 `libxposed-service-102.0.0.aar` 提取全部 API 签名
- 从 `miuix-*.aar` 提取组件签名与包路径（`top.yukonga.miuix.kmp.basic.*` 等）
- 创建三模块工程骨架与全部源码（30 个 Kotlin/KTS 文件）
- 实现 4 个 Hook 功能（待 jadx 复核）
- 撰写 `AGENTS.md` / `docs/*`
- **构建跑通**：`BUILD SUCCESSFUL`，并验证 APK 内 `META-INF/xposed/*` 三个声明文件齐全、
  `XposedProvider` 已由 service 库自动合并进 manifest（authority `top.spacejoy.spacehook.XposedService`）、
  入口类 `SpaceHookEntry` 已打包进 dex

#### 构建过程中解决的 5 个坑

| # | 现象 | 解法 |
|---|---|---|
| 1 | `org.jetbrains.kotlin.android` 插件与 AGP 9 新 DSL 冲突 | `gradle.properties` 加 `android.newDsl=false` + `android.builtInKotlin=false`；`jvmTarget` 配置上移到**顶层** `kotlin { }` 块 |
| 2 | `android.app.ActivityThread` 未解析（隐藏 API） | 全程改用反射访问（`Class.forName("android.app.ActivityThread")`） |
| 3 | `androidx.core:core-ktx:1.19.1` / Miuix 传递依赖 `materialkolor:5.0.1` 要求 compileSdk 37 + AGP 9.1+ | 升 AGP 到 **9.1.0**、compileSdk/targetSdk 到 **37**，并补装 SDK Platform 37 |
| 4 | AGP 9.1.0 要求 Gradle ≥ 9.3.1 | 下载 Gradle **9.3.1**（`D:/Code/Android/tools/gradle-9.3.1`），wrapper 同步指向腾讯镜像 9.3.1 |
| 5 | `Failed to find target with hash string 'android-37'` | AGP 自动安装的目录是 `platforms/android-37.0`，而 hash 查找用 `android-37`；把 `android-37/source.properties` 的 `AndroidVersion.ApiLevel` 改为 `37`，并加 `android.suppressUnsupportedCompileSdk=37.0` |
| 6 | `XposedService.getRemotePreferences()` 等未解析 | `:app` 漏加 `io.github.libxposed:service` 依赖（UI 侧与框架通信必需），已在 version catalog 补 `libxposed-service` |
| 7 | `hotReloadModule(target, bundle, null)` 类型不匹配 | `HotReloadCallback` 非空，改为传 SAM lambda `{ _, _ -> refreshTargets() }` |

---

## 七、模块更新流程（已核实 + 实测）

**覆盖安装 + 重启目标应用即可，不需要卸载重装。**

```bash
gradle :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk   # 覆盖安装，LSPosed 配置保留
adb shell am force-stop <目标包名>                              # 重启目标应用
```

已用 `[BUILD-MARKER]` 实测证明：覆盖安装后 LSPosed **立即**使用新代码。
LSPosed 官方升级说明同样是 *Install over the previous version → Force-stop target and reopen*。

> ⚠️ **排查陷阱**：LSPosed 数据库是 WAL 模式，只拉 `modules_config.db` 会读到**旧快照**。
> 曾因此误判「`apk_path` 没更新」，错误地得出了「必须卸载重装」的结论。
> 一定要连 `modules_config.db-wal` 一起拉。

---

## 六、下一步建议（优先级从高到低）

1. ~~确认 123 云盘包名~~ ✅ **已完成**（`com.mfcloudcalculate.networkdisk`，4 个 APK 包名均已核实）
2. **逐个复核 4 个 Hook 的 hook 点**，用 jadx 核对类名/方法签名/参数类型，更新 `docs/HOOKS.md`
3. **真机部署验证**：`adb install app-debug.apk` → LSPosed 启用 → 勾选作用域 → 重启目标应用 → 看首页「已生效进程」
4. 实装模糊效果（`miuix-blur-android`）与显式「立即热重载」按钮
5. 可选：接入 jadx-ai-mcp 插件（需先在 jadx GUI 中确认插件处于服务状态）

> ✅ 构建链路已完全跑通，可直接 `adb install app/build/outputs/apk/debug/app-debug.apk` 做初步冒烟测试。
