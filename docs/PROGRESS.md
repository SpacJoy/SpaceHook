# SpaceHook 开发进度

> **交接必读**：本文件记录项目的实时状态。每次实质性改动后请更新「变更日志」一节。
> 最后更新：2026-10-29（移除 `adb_stay_awake` hook 及其全部残留引用）

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
| 11 | 抖音不裁剪 Hook（移植 douyinnocrop v1.1.0） | ✅ 完成 | 5/5 hook 点经抖音 40.6.0（400601）dexdump 复核；修正原实现 2 个 bug；构建通过，待真机验证 |
| 12 | ADB 调试常亮 Hook（移植 AdbStayAwake） | ❌ 已移除 | 2026-10-29 按需求完整删除（`adb_stay_awake`）：hook 实现 / `HookRegistry` 映射 / `HookCatalog` 元数据与「系统框架」TargetApp / `scope.list` 的 `system` 行全部移除；`HookCategory.SYSTEM_FRAMEWORK` 保留待后续 system_server 功能挂入 |
| 13 | 控制中心卡片排序 / 磁贴卡片自定义（参考 HyperStar） | ✅ 完成 | `cc_card_order`（8 个控制器 `getPriority`）+ `cc_card_tiles`（`QSController#getCardStyleTileSpecs`）；hook 点定位见 HOOKS.md 第 7、8 节 |
| 14 | SoundMan 移植分析 | ✅ 完成 | 两路子代理全量分析（音频核心 + SystemUI 入口）；报告见 `docs/ANALYSIS-SoundMan.md`；**移植未开始**（用户决策：先只出分析报告） |
| 15 | 三合一状态图标 Hook（移植 com.aiden.combinedstatus 1.4.3） | ❌ 已撤销 | 2026-10-03 按需求取消移植：hook 实现 / 绘制层 / 配置 UI / 分析文档全部移除；`HookCategory.STATUS_BAR` 与「状态栏」折叠子菜单**保留**，待后续功能挂入 |
| 16 | 状态栏功耗/温度仪表 `status_bar_meter` | ✅ 完成 | 接管原生 `NetworkSpeedView` 显示功耗 / 电流 / CPU 温度 / 电池温度（最多 4 槽，单行或双行）。真机已验证装载与写入（`Hook 装载成功`、仪表文本正常刷新）；CPU 温度恒 95℃ 的根因（命中 `cpu-hw-trip-*` 阈值节点）已修。参数页为独立整屏页，逐行 −/＋ 步进 + 点数字手动输入（仿 HyperCeiler 的形态重构已于 2026-10-04 回退） |
| 17 | 整屏参数页 `HookParamsScreen` | ✅ 完成 | 新增 `HookParam.FullPage` 标记 + 通用整屏参数页（仿「控制中心卡片排序」形态）；`status_bar_meter` 改用该页，列表内联弹窗不再承载它的 10 个参数 |
| 18 | 状态栏实时网速 `status_bar_speed` | ✅ 完成 | 从仪表里拆出的独立开关（用户需求：网速与功耗分开设置）。与 `status_bar_meter` 共用原生显示位：显示引擎抽到 `StatusBarMeterHookBase` + `StatusBarMeterState`，两个 Hook 各只提供一个文本贡献者，同时开启时按序合并。参数页与仪表共用同一个独立整屏页（仿 HyperCeiler 的形态重构已于 2026-10-04 回退） |
| 19 | 参数页形态重构（仿 HyperCeiler） | ❌ 已回退 | `HookParam` 曾新增 `Toggle`（开关）/ `group`（分组标题）/ `dependsOn`（依赖某开关，关时置灰）/ `IntRange.unit`（显示单位），`HookParamsScreen` 重写为「总开关卡 + 分组参数卡 + 底部按钮」，行按类型渲染成开关行 / 下拉行 / 滑条行。真机验证曾通过（`分发完成：成功 6 / 共 6`），但**用户要求回退**（2026-10-04，显示不正常）：4 个 UI 文件退回重构前的逐行形态，`HookParam` 的 4 个新成员一并移除；**hook 层未受影响** |
| 20 | 网易云音乐 `cloudmusic_no_smartplay_loading` | ⚠️ 修复待复测 | 去掉心动模式加载弹窗（黑底 + 红心动画 +「正在打开心动模式...」）。**9.6.13 上失效两次**：①类名漂移 `activity.ha`→`ma`（已用锚点解决）②同类功能被编译出**两份实现**（`activity.ma` + `i1.c` 桥接），只挂一份会漏。当前改挂上游唯一入口 `showAIModeLoadingDialog`，并遍历所有声明该方法的类逐个挂；离线 dex 已核对，待真机复测 |
| 21 | 时钟闹钟操作 `alarm_swap_snooze_dismiss` / `alarm_swipe_dismiss_distance` | ✅ 完成 | 两个独立开关：①交换「上滑关闭」与「点击延迟提醒」（文案同步：按钮→「关闭闹钟」、底部→「滑动延迟提醒」）②加长上滑距离（阈值 30~90% 可调，默认 60%，并禁用快速一甩通道）。真机验证：按钮关闭、上滑延迟（排到 1 分钟后）、10% 回弹、64% 放行 |

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
| ✅ 已解决 | ~~4 个 Hook 的类名/方法名来自**旧 BeanShell 脚本**，未用 jadx 复核~~ → **10 个 Hook 的 hook 点已全部实测验证**（2026-09-26 ~ 2026-10-04） | 复核明细见 `docs/HOOKS.md` 各节 |
| ✅ 已解决 | ~~123云盘真实包名未知~~ → 确认为 **`com.mfcloudcalculate.networkdisk`** | 已回填 `HookCatalog.targetApps` / `installer_redirect.targetPackages` / `scope.list`；命令：`aapt2 dump badging` |
| ✅ 已废弃 | ~~jadx-ai-mcp 插件（`127.0.0.1:8650`）请求无响应~~ | 2026-10-03 起不再使用 jadx-ai-mcp 插件（8650 作废）；jadx-GUI 不再打开，`jadx-gui-1.5.6-all.jar` 恢复保留但仅以 CLI 模式调用 |
| 🟡 中 | `BaseHook.replace()` 的返回值语义依赖「try 块最后表达式」 | 已实现并加注释，改动时注意不要破坏 |
| 🟡 中 | `DeviceCenterSizeHook` 依赖 MIUI plugin 子 ClassLoader，机型差异大 | 需要真机验证；失败时看 `ClassLoaderUtils` 的日志 |
| 🟢 低 | 未做 `miuix-blur` 模糊效果接入（`blurEnabled` 开关已预留但未实装） | 后续可加 `miuix-blur-android` 依赖 |
| 🟢 低 | 「热重载」开关只写配置，UI 未接 `XposedService.hotReloadModule` | 可在设置页加显式「立即重载」按钮 |
| 🔴 高 | **短时间内反复重启系统界面会让 LSPosed 进安全模式** | 安全模式**按进程生效，且作用于该进程下的全部模块** —— `com.android.systemui` 崩一次，**所有**模块在它里面都被跳过，不只是肇事那个。KeiMi 有自己的安全模式并会打日志（`Safety mode triggered by com.android.systemui; all KeiMi feature behavior is paused`），**本项目会被静默跳过、一行日志都没有** —— 「模块日志完全不见」本身就是安全模式的特征，不要误判成「代码没生效」。解除方式：**重启手机**。**验证前先自检**：在 LSPosed 持久日志 `/data/adb/lspd/log/modules_*.log`（root 读）里搜当前会话 id 的 `SpaceHook 已注入`；**搜不到就说明模块没加载，此时不要改代码，先重启**。验证时一次 `killall com.android.systemui` 就够，不要反复试错；不要依赖只有 15~30 秒的 logcat 缓冲 |
| ✅ 已解决 | ~~承载视图 `layoutParams.width` 被改成 `MATCH_PARENT`，导致状态栏「靠右侧闪烁」+「右侧空隙太大」~~ | `MiuiStatusIconContainer.onLayout` 用 `x = 右内容边 - NetworkSpeedView.getNetworkSpeedWidth()` 摆子视图，而有内容时 `getNetworkSpeedWidth()` 直接返回 `getWidth()` ⇒ 满宽视图被算到容器左边界、下一轮又摆回右侧（闪烁），余量全落在右边（空隙）。**承载视图必须保持 `WRAP_CONTENT`**（原生 `IconManager.onCreateLayoutParams` 给的就是 `-2`）；间隙用视图自己的 `paddingStart/paddingEnd` 表达。见 `StatusBarMeterHookBase.applyStructure()` KDoc |

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

> **完整清单见 `AGENTS.md` 第 6 节「参考项目」**（9 个项目 + origin + 本地最后提交快照）。
> 这里不重复表格，避免两处漂移。
>
> ⚠️ 全部在 `D:/Code/Android/Projects/Referenzprojekt/` 下（2026-10-02 从 `OSHook/references` 迁来）：
> HyperCeiler / HyperStar / HyperIsland / InstallerX-Revived / janus / SignHook / Capsulyric / SoundMan / AdbStayAwake
> （AdbStayAwake 对应的 `adb_stay_awake` 功能已于 2026-10-29 移除，参考目录本身保留）。
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

## 四之六、导航改用 Miuix `miuix-nav`：顶层整屏路由 + Y 轴转场（2026-09-27）

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

## 四之七、预测性返回（Predictive Back）动画（2026-09-27）✅ 真机已验证

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

### 2026-10-29 移除 `adb_stay_awake`（ADB 调试常亮）Hook

**需求**：完整删除该 hook，并清理因删除而失效的引用、导入、资源与注释，不残留任何引用；
若存在与它关联但被其他功能共用的代码，保留共用部分并说明处理方式。

**删除清单（代码层）**：

| 位置 | 处理 |
|---|---|
| `libhook/.../rules/system/AdbStayAwakeHook.kt`（322 行） | 删除整个文件；`rules/system/` 目录随之清空后一并删除 |
| `libhook/.../base/HookRegistry.kt` | 删除 `AdbStayAwakeHook` 的 import 与 `"adb_stay_awake" to { … }` 映射，以及「系统框架（system_server）」分组注释 |
| `common/.../HookCatalog.kt` | 删除 `adb_stay_awake` 的 `HookMeta`（含 6 条 hookPoints）；删除 `Constants.TARGET_SYSTEM` 的 `TargetApp`（「系统框架」卡片） |
| `libhook/src/main/resources/META-INF/xposed/scope.list` | 删除 `system` 行（该行仅为此 hook 而存在） |

**保留的共用部分（未删除，仅说明）**：

- **`Constants.TARGET_SYSTEM`（"system"）常量**：保留。它不只是这个 hook 的包名，还是
  `SpaceHookEntry.onSystemServerStarting()` → `dispatch(TARGET_SYSTEM, …)` 的入口标识，
  以及 `HookMeta.TargetApp.isSystemServer`、`HookCatalog.installedPackages()`、
  `HookListScreen`（系统框架卡片不显示「单独重启」）、`HookParamsScreen`（跳过 system_server 的重启目标）
  的判定依据。这些是**框架级能力**，删除常量会让 system_server 分发链路编译不过，
  未来任何 system_server Hook 都要重新加回来。
- **`HookCategory.SYSTEM_FRAMEWORK("系统框架")`**：保留枚举值，当前无 Hook 使用。
  处理方式与当初撤销「三合一状态图标」时保留 `STATUS_BAR` 一致 —— 分类是 UI 的稳定词表，
  后续 system_server 功能（如 `docs/ANALYSIS-SoundMan.md` 的音频方案）可直接挂入，无需改 UI。
  已补注释说明「当前暂无 Hook 使用」。
- **`SpaceHookEntry.onSystemServerStarting()` 与 `dispatch(isSystemServer=…)`**：
  这是 libxposed API 102 的生命周期回调，属于框架接线，不是该 hook 私有代码，保留。
  当前 `system` 不在 `scope.list` 中，也不会再被 LSPosed 注入；回调保留无害且是后续 system_server
  Hook 的必需入口。
- **`docs/screenshots/12_hooklist_system.png`**：历史截图，保留（记录当时的 UI 状态，
  不是被引用的资源文件，不影响构建与运行）。

**验证**：`gradle --no-daemon :app:assembleDebug` → `BUILD SUCCESSFUL in 33s`
（`:common:compileDebugKotlin` / `:libhook:compileDebugKotlin` / `:app:compileDebugKotlin` 全部重编通过）。
全仓 grep `adb_stay_awake` / `AdbStayAwake` / 「ADB 调试常亮」在 `*.kt` / `*.list` / `*.prop` / `*.xml` 中零残留。

**文档同步**：`docs/HOOKS.md`（删第 6 节、总览表删行、后续小节顺延编号、内部 § 引用同步）、
`docs/PROGRESS.md`（本条目 + 进度表第 12 行标记已移除）、`AGENTS.md`（目录结构删该文件、
参考项目表补注已移除）、`README.md`、`docs/新手代码地图.md`、`docs/ANALYSIS-SoundMan.md`。

### 2026-10-26 ✅ 网易云 `cloudmusic_no_smartplay_loading` 真机验证通过（第四次适配）→ **v1.2.1 (10201)**

**版本号**：`1.2.0` → `1.2.1`（补丁号 +1）。按 SemVer 语义，本次是「修 bug + 适配新版目标 App」，
`app/build.gradle.kts` 的 `versionPatch` 由 0 改为 1（`versionCode` 自动换算为 10201）。

**结果**：弹窗消失，日志 `已拦截 14 次`。真正生效的 hook 点是：

```
已挂载：com.netease.cloudmusic.activity.ma@Tinker补丁#showAIModeLoadingDialog
```

**完整成功链路（日志实证）**：

```
15:44:44  已挂载：materialdialogs.g#show（show 兜底）            ← 兜底挂上
15:44:44  已挂载：ma@base.apk#showAIModeLoadingDialog             ← 这份无效
15:44:44  已挂载：i1.c@base.apk#showAIModeLoadingDialog
15:44:44  检测到 2 个 Tinker 补丁 dex，开始等待补丁装载…
15:44:46  第 1 次轮询：当前加载器=DelegateLastClassLoader          ← 补丁装完
15:44:46  已知名单命中 → ma@Tinker补丁                            ← 换加载器后是新 Class
15:44:46  补挂：发现 1 个新承载类 [ma@Tinker补丁]
15:44:46  已挂载：ma@Tinker补丁#showAIModeLoadingDialog             ← 真正生效
15:44:49 起  已拦截心动模式加载弹窗（ma@Tinker补丁）× 14 次
```

**结论**：前三次失效的根因就是 **Tinker**（`base.apk` 那份类没被调用，
实际执行的是补丁 dex 里那份）。`show()` 兜底一次都没触发，说明精准入口就够，
兜底保留作为网易云改架构时的最后防线。

**本轮附带优化**：

| 项 | 改动 |
|---|---|
| 补丁路径去重 | `/data/user/0` 与 `/data/data` 是同一目录的两种写法，原来扫两遍；改按 `canonicalPath` 去重 |
| 轮询提前结束 | 补挂成功后立即停止，不再空转 10 次 |
| 复用补丁列表 | 补挂轮询复用已读到的 `patchDexes`，不再重复列目录 |
| 日志降噪 | `已拦截` 首次打全文（含文案），之后只计数，避免一次切歌刷 14 行 |

### 2026-10-26（`PrefsBridge` 只读 Preferences 降噪）

**现象**：每个 Hook 每次启动都刷一串 ERROR，把真正的故障淹掉：

```
E 配置写入异常：active::cloudmusic_no_smartplay_loading::com.netease.cloudmusic
java.lang.UnsupportedOperationException: Read only implementation
  at PrefsBridge.putBoolean(PrefsBridge.kt:69)
  at PrefsBridge.markActive(PrefsBridge.kt:140)
```

**结论**：这不是故障。LSPosed 下发给模块的 RemotePreferences 是**只读实现**，
`edit()` 必然抛 `UnsupportedOperationException`。UI 侧的「已生效」展示本来就不依赖它 ——
`SettingsStore.rememberActiveProcesses()` 用本地 `local_seen_processes` 闭环记录，
代码注释里 2026-10 就已写明「Hook 侧回写实测不稳定（`module_configs` 表始终为空），
故改用本地记录」。所以**功能与展示均不受影响**，只是日志噪声。

**改动**（`libhook/.../utils/PrefsBridge.kt`）：

| 位置 | 改动 |
|---|---|
| `putBoolean()` | 新增 `isReadOnly(t)` 判定：只读 → `XLog.w` 一句话；其他异常（IO/权限/序列化）仍 `XLog.e` + 堆栈。判据以**异常类型**为主、消息文本为辅，消息为空也判只读，避免版本措辞差异导致漏判 |
| `markActive()` | 诊断日志从 `XLog.e` 降为 `XLog.d`，且只在写入成功时回读校验；失败情形由 `putBoolean` 分级记录，不再重复刷 |

**设计取舍**：没有选择「静默吞掉所有异常」，因为那样会把真正的写入故障也藏起来。
只读是**预期内状态**（降级为 WARN），其余仍报 ERROR。

**验证**：`BUILD SUCCESSFUL`。下次真机日志里应只剩一行 `W 配置为只读实现，跳过写入`。

### 2026-10-26（网易云 `cloudmusic_no_smartplay_loading` 二次失效修复）

**现象**：9.6.13 上弹窗照旧，但日志说挂载成功（Toast 也提示成功）：

```
D 已知名单命中：com.netease.cloudmusic.activity.ma
I 已挂载：...ma#showDialog（拦截加载弹窗）
I Hook 装载成功（拦截 1 个）
```

**排查结论**：类名没漂、签名没变，问题是**全日志没有任何一条「已拦截」** ——
`showDialog` 从未被调用。扫描 9.6.13 全部 20 个 dex 发现，声明
`showAIModeLoadingDialog(Z,String)V` 的类有**两个**：播放页基类 `activity.ma`，
以及 `com.cmbridge` 桥接实现 `i1.c`（经 `ServiceLoader.put(kj2.b.class, ...)` 注册，
9.6.08 无此注册）。只挂 `ma` 那一份，桥接路径照旧弹窗。

**改动**：

| 文件 | 改动 |
|---|---|
| `libhook/.../rules/cloudmusic/CloudMusicNoSmartPlayLoadingHook.kt` | hook 点从 `showDialog` 换成上游唯一入口 `showAIModeLoadingDialog`；`resolveHosts()` 返回**全部**承载类并逐个挂；已知名单只保留播放页基类（桥接实现是纯混淆名，交给 DexKit 覆盖） |
| `libhook/.../utils/DexAnchorScanner.kt` | 新增 `findDeclaringClasses()` 返回所有命中类（旧 `findDeclaringClass()` 只返回唯一命中，遇两份实现会直接放弃）；`RULE_REVISION` 1→2 让旧缓存失效；缓存改存逗号分隔的多个类名 |
| `docs/HOOKS.md` §11、`docs/混淆名Hook方案.md` §一 | 记录「一份功能两份实现」这种失效形态与判据（只认「已拦截」不能只认「已挂载」） |

**为什么挂上游更安全**（相比继续加挂第二个 `showDialog`）：入口直接返回后，
`isPrepareShow` 保持 false（旧方案会留下「已置 true 但永不弹」的脏状态）、
红心 webp 连下载都不发起（旧方案动画已 start 却因 Dialog 未创建而无人 stop）、
`dismissAIModeLoadingDialog()` 首句判 null 故所有调用点安全空转。
**明确不碰** `handleMessage` / `dispatchPlayCommand` —— 那两个覆盖 12+ 个播放相关分支。

**验证**：`BUILD SUCCESSFUL`；产物 dex 核对确认方法名常量与类结构正确、
`ma` / `i1.c` 两处目标方法均为真实 `invoke-direct` 调用（未被 R8 内联）。
**真机复测待做**（本次未连 adb）。

### 2026-10-24（启动提示支持按应用单独开关）→ **v1.2.0 (10200)**

**需求**：启动提示（Hook 状态 Toast）原先只有一个总开关，开了就所有目标 App 全弹。
需要能逐个指定「哪些 App 弹」，且**只列用户应用**（系统应用本来就不弹）。

**改动**：

| 文件 | 改动 |
|---|---|
| `common/.../HookPrefs.kt` | 新增动态键 `toastAppKey(packageName)` = `exp_toast_app::<包名>` |
| `libhook/.../utils/PrefsBridge.kt` | 新增 `isToastEnabled(packageName)`：总开关 `exp_toast_on_start` 关闭时一律 false；打开时再看该 App 的键（默认 true） |
| `libhook/.../utils/HookToast.kt` | `showResult()` 增加参数 `packageName`，闸门改走 `PrefsBridge.isToastEnabled()` |
| `libhook/.../SpaceHookEntry.kt` | 调用处传入当前 `packageName` |
| `app/.../SettingsStore.kt` | 新增 `isToastAppEnabled` / `setToastAppEnabled` |
| `app/.../FeatureState.kt` | 新增 `toastAppsClosedCount` + `refreshToastAppsClosedCount()`（设置页副标题用，改完返回即时刷新） |
| `app/.../ui/screens/ToastAppsScreen.kt` | **新增** 整屏子页面：列出全部**用户应用**，每行一个开关（已安装置顶，未安装标「未安装」） |
| `app/.../ui/screens/SettingsScreen.kt` | 总开关下方加「提示哪些应用」入口（`ArrowPreference`），副标题显示「提示 N 个 / 共 M 个用户应用」 |
| `app/.../ui/SpaceHookNavHost.kt` / `MainScreen.kt` | 新增路由 `Route.ToastApps` + `onOpenToastApps` 回调链 |
| `app/build.gradle.kts` | 版本 `1.1.0` → `1.2.0`（10200） |

**语义**：总开关是上位闸门 —— 关掉就全都不弹，子页面怎么选都没用；
打开后默认全部用户应用都弹，只有被单独关掉的那些才安静。列表取自
`HookCatalog.userApps`（已配置 Hook 的用户应用），不列设备上全部已安装应用。

### 2026-10-24（新增：DexKit 兜底 + Hook 装载结果统计 + 启动 Toast 提示）→ **v1.1.0 (10100)**

**动机**：网易云失效事件暴露出两个问题 —— ① 混淆名变化后没有兜底；② 失效完全静默，用户看不出。

**配套：版本号方案落地**（用户 2026-10-24 选定方案 A 语义化 SemVer）：
`app/build.gradle.kts` 改为 `major/minor/patch` 三个变量 + 自动换算 `versionCode`，
本次版本 `1.1.0 / 10100`（此前一直停在 `1.0.0 / 1`）。约定：**每次交付 APK 前必 bump**。

**改动**：

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` / `libhook/build.gradle.kts` | 引入 `org.luckypray:dexkit:2.3.0`（含 arm64 `libdexkit.so` 约 395 KB） |
| `libhook/.../utils/DexAnchorScanner.kt` | **新增** DexKit 扫描器：按方法签名定位混淆类 + 缓存指纹（规则版本/APK 路径/大小/mtime）+ 原生库懒加载 + 缓存命中仍反射复核 |
| `libhook/.../base/BaseHook.kt` | 新增装载结果统计：`LoadResult`（OK/FAILED/SKIPPED）+ `installedHooks`/`failedAnchors`；`requireClass`/`replace`/`before`/`hookAllMethods`/`hookConstructor`/`hookExecutable` 自动计数 |
| `libhook/.../base/HookContext.kt` | 增加 `apkPath`（来自 `PackageReadyParam.applicationInfo.sourceDir`） |
| `libhook/.../utils/HookToast.kt` | **新增** 启动提示：等到目标 App 有 Activity 再弹（轮询，最长 15s），显示成功/失败/跳过与失败功能名 |
| `libhook/.../SpaceHookEntry.kt` | 分发时按 `LoadResult` 分别统计，汇总日志改为「成功/跳过/失败/已开启/共」，并调用 `HookToast` |
| `libhook/.../rules/cloudmusic/...Hook.kt` | 定位改为三层：已知名单 → manifest 锚点+继承链 → **DexKit 兜底** |
| `common/.../HookPrefs.kt` + `app/.../SettingsScreen.kt` + `SettingsStore.kt` | 新增开关 `exp_toast_on_start`（设置页 → 实验性功能，默认关闭） |
| `docs/混淆名Hook方案.md` | 补充 DexKit 落地方式、启动提示章节、版本号方案候选 |

**关键设计**：DexKit 只做**第三层兜底**。前两层纯反射（≈0 耗时），只有全落空才加载原生库扫描 ——
绝大多数版本的启动完全不碰 `libdexkit.so`。这与参考项目「DexKit 当主力」不同，
因为本项目多数 Hook 目标是系统 App（不混淆）。

**验证**：`BUILD SUCCESSFUL`；APK 13 MB，内含 `lib/arm64-v8a/libdexkit.so`。真机未测。

---

### 2026-10-24（修复：网易云 9.6.13 失效 → 通用混淆名锚点方案）

**现象**：用户升级网易云到 9.6.13 后，`cloudmusic_no_smartplay_loading` 完全不生效，
但无闪退、日志里也只有一条 `方法不存在，跳过` 的 WARNING。

**根因**：`activity.ha` 是 R8 混淆名，9.6.13 里真正的播放页基类变成了 `activity.ma`；
而 `activity.ha` 这个**名字还在**，被一个无关的 lambda 类占用。
`BaseHook.replace()` 找不到方法只打 WARN、Hook 仍记为「装载成功」→ 静默失效。
（弹窗逻辑本身一字未改，连红心 WebP 的 URL 都一样。）

**改动**：

| 文件 | 改动 |
|---|---|
| `libhook/.../base/AnchorFinder.kt` | **新增**通用锚点工具：`findAncestor`（沿父类链找）/ `declaresMethod`（只认自己声明的）/ `findDeclaredMethod` |
| `libhook/.../rules/cloudmusic/CloudMusicNoSmartPlayLoadingHook.kt` | 重写定位为三层：已知名单（`ma`/`ha`，逐个验证声明）→ manifest 锚点 `PlayerActivity` → 继承链解析。定位失败改报 **ERROR** |
| `common/.../HookCatalog.kt` | 修正该 Hook 过时的 `hookPoints`（原来还写着已回滚的 903 消息方案） |
| `docs/混淆名Hook方案.md` | **新增**：混淆名通用方案 + 新增 Hook 强制检查清单 + DexKit 引入时机 |
| `docs/HOOKS.md` §11 | 补充 9.6.08 → 9.6.13 的失效分析与新定位说明 |

**为什么不引入 DexKit**：参考项目（HyperCeiler / AmapRouteDefault）用它做主力，
但代价是原生库（arm64 so 约 395 KB）+ 首次扫描 100–150 ms + 每个 Hook 改写成查询 DSL。
目前全项目只有网易云 1 个 Hook 真被混淆名坑过，投入产出比不划算 ——
判断标准与引入时机记在 `docs/混淆名Hook方案.md` 第五节（**等第 3 个 Hook 失效再上**）。

**验证**：`BUILD SUCCESSFUL`（`:app:assembleDebug`）。真机未测（按用户要求不自动测试）。

---

### 2026-10-20（新增：高德地图默认出行方式）

**需求**：参考 `Referenzprojekt/AmapRouteDefault`，移植「修改高德默认导航/出行方式」功能。

**关键差异**：参考项目只适配了高德 **15.10.1.2063**（初始化方法在 `p91#a(s91, cb)`），
而真机实际是 **17.01.0.2012**（`versionCode=170100`，从 `/data/app/…/base.apk` 提取，194 MB / 8 个 dex）。
新版的初始化方法已变成 `h15#a(g15, cb)`，参考项目的 DexKit 字符串特征
（`initRouteType() routeTypeSource`、`tab_recommend_filter`）**在本版已全部消失**，
所以不能直接搬 DexKit 方案，改为按结构特征定位。

**新定位方案**：

1. 候选类：`h15`（17.01）/ `p91`（15.10），dex 里是默认包名（`Lh15;`），jadx 显示为 `defpackage.h15`
2. 未混淆锚点：`com.amap.bundle.planhome.PlanHomeService#getInitRouteType` 直接转发给初始化方法，
   拿它的第 1 个参数类型交叉校验候选请求类
3. 结构校验：静态 void 双参 + 单方法回调接口 + 请求类无父类有无参构造 +
   恰好一个枚举字段（DEFAULT/CAR/BUS/ONFOOT/RIDE = -1/0/1/2/3）+ 至少 2 个 POI 形态字段
4. 匹配不唯一 / 校验不过 → 不挂载，保持高德原样

**替换语义**（与参考项目一致，刻意保留的边界）：

- 请求已明确指定方式 → 不覆盖（专门驾车入口 / 带方式的第三方 scheme 不受影响）
- 请求为 `null` → 不覆盖（不硬造空请求，避免挤掉高德自己的推荐兜底）
- 页内手动切换 → 不拦；复制请求副本修改，高德持有的原始请求不变
- 改设置无需重启高德

**冗余入口**：除 `h15#a` 外同时挂 `PlanHomeService#getInitRouteType`，
重复拦截安全（副本已带明确方式，第二层走「已指定」分支原样放行）。

**验证状态**：真机验证通过（高德 17.01.0.2012，Android 16 / LSPosed 2.1.1-it）。
定位链路全部命中，输入目的地后点导航的结果随设置变化。

**已知差异（已修）**：首页推荐卡片的导航按钮恒为驾车。日志证据是卡片链路
传入的请求里已经是 `CAR` —— 高德 `routePlan` 动作的 `optInt("routeType")`
在字段缺失时兜底返回 0 = `CAR`，被「保留明确指定」规则放行。
为此新增 `scope` 选项：0=仅未指定时（默认）/ 1=全部入口都覆盖。

**改动文件**：

- 新增 `libhook/.../rules/amap/AmapDefaultRouteHook.kt`、`AmapRouteAdapter.kt`
- `HookCatalog`（高德 TargetApp + `amap_default_route`）、`HookMeta`（新增 `HookCategory.AMAP`）、
  `HookRegistry`、`scope.list`
- `docs/HOOKS.md` §14

**⚠️ 事故记录（教训）**：为省事直接改 LSPosed 的 `modules_config.db` 并 `killall lspd`，
导致 lspd 无法再拉起（启动即 SIGSEGV，`fault addr 0x314`），需重启设备恢复。
**今后一律不碰 `/data/adb/lspd/` 下的任何文件与进程**，开关与作用域只能由用户在管理器里操作。

### 2026-10-18（修复：网速「排布方式=双行」始终显示单行）

**问题**：网速参数页把「排布方式」设成「双行」，实际仍是单行。

**根因**：两个设置项的职责搞混了 ——

1. `StatusBarMeterState.composeNative()` 按**段数**切分（`parts.size >= 2`），
   而不是按**排布**切分。单行样式恒只产出一段，于是双行分支永远走不到。
2. `ensureRow()` 把承载容器固定改成**横排** `LinearLayout`。它的用途原本是
   「网速占左列、仪表占右列」，但仪表改用独立槽位后这个前提已不存在；
   而「双行」要的是**上下两行**，横排容器根本无法表达。

**修法**

- `composeNative()` 改为按 `layout` 收敛：单行 → 全段用空格连成一行；双行 → 首段进数字框、
  其余进单位框。段数由「排布方式」决定，不再由「显示样式」决定。
- `ensureRow()` → `ensureContainer()`，容器固定为**竖排** `LinearLayout`：
  数字框在上、单位框在下，单行时单位框 GONE。
- `StatusBarSpeedHook.render()` 同步改为按 `layout` 收敛段数：
  双行恒给两段，单行把两段合并成一段。这样即使选了「值和单位分开」+「单行」，
  也会正确显示「12.3KB/s」而不是裂成两段。
- `applyLayout()` 里 `maxLines` 从 `if(dual)2 else1` 改为**每框恒 1 行** ——
  双行是两个框各一行（由容器竖排实现），不是同一个框内换行。

**顺带修的间距**：仪表槽位原来只在左侧留 6dp，右侧紧贴电池。现在两侧都用
**视图自身的 `padding`** 表达（容器 `onLayout` 会读 `getPaddingStart/End` 并计入 `translationX`，
而 `layoutParams` 的 margin 在它的坐标系里完全不被考虑）。`gap_left` / `gap_right` 默认均为 6dp。

**文案**：「显示样式」与「排布方式」的说明、选项名重写，明确「样式决定显示什么内容、
排布决定显示几行」；去掉「网速占左列、仪表占右列」等已失效的描述。

**另外修掉一个同类残留**：`applyStructure` 只作用于**网速的原生视图**，但它算右空隙时读的却是
`meterGapRightDp`（仪表槽位的配置）。仪表搬走后这属于张冠李戴 —— 网速的右空隙应当读自己的
`speedGapRightDp`，否则改网速的「右空隙」毫无反应、改仪表的反而会动到网速。

**验证**：真机在参数页来回切换「单行 ↔ 双行」，状态栏即时跟随；
双行显示为「上传/下载」两行 + 仪表两行，单行恢复为一行。

### 2026-10-18（新增面向项目所有者的《新手代码地图》）

**背景**：项目所有者自学出身、本职非开发岗，只有深夜才有时间碰项目，且手上两个在用项目都是
「描述需求 → AI 整段给代码」的方式产出的，因此对代码实现细节不熟。目标是让他在不看完整代码、
不动手改代码的前提下，也能判断「某个东西在哪个文件 / 该不该动 / 出问题先查什么」。

**新增**：`docs/新手代码地图.md`（365 行，非技术向），十节：

1. 三句话总览（界面不干活、开关是跨进程存储、改功能界面自动适配）
2. 项目定位（与普通 App 的差别、Hook 的含义）
3. 三个模块各自职责 + 逐个文件说明
4. 「点开关 → 真正生效」的完整九步链路 + 四级闸门表
5. 框架固定写法 vs 自己可改的部分（API 102 拦截器链，区别于网上旧教程）
6. **改动速查表**（想改什么 → 改哪个文件）+ 新增 Hook 的完整流程
7. 排障：日志关键词 → 结论对照表；安全模式坑；验证流程
8. 13 个功能一览
9. 易忘概念（包名 / 进程 / 作用域 / ClassLoader / 两类存储 / 热重载）
10. 禁止改动清单 + AI 改动后的汇报格式要求

**配套改动**：`AGENTS.md` 第 3 节目录结构补登该文件（标 ★），便于后续 Agent 知道它的定位。

**注**：本文件只讲「在哪、是什么、怎么查」，不写实现细节；实现仍以 `ARCHITECTURE.md` / `HOOKS.md` 为准。

### 2026-10-18（状态栏功耗与网速分离：仪表改用独立槽位）

**需求**：功耗/温度和网速挤在同一个显示位，占空间大；超级岛遮挡网速时功耗也被一起隐藏。
要求两者分开，被遮挡时互不影响。

**根因**：两个 Hook 原本都往**同一个**原生 `NetworkSpeedView` 里写字，而超级岛遮挡是
**按 View 逐个判定**的（`MiuiStatusIconContainer.onLayout` 里逐 child 设 `visibleState=2`），
所以一个被挤掉两个一起消失 —— 不是排版问题，是「共用一个 View」。

**方案**：仪表改走独立槽位（`meter/MeterSlot.kt`），网速继续写原生视图。两条链路彻底分开。

#### 真机实测（探针 `MeterProbeHook`，验证后已删除）

| 待确认 | 实测结论 |
|---|---|
| `visibleState=2` 怎么隐藏 | **直接设 `visibility=GONE`**，不走 alpha（`stA`/`vA` 恒 1.0） |
| `ignoredSlots` 是否含自定义 slot | **不含**，只有 `RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST` 固定常量 |
| `IconManager` 会不会摘掉插入的 View | `destroy()` 启动时必清空容器 → **重插守卫必需** |
| `onRemoveIcon(i)` 索引删除 | 按索引取子 View，插入会错位（`onSetIcon` 还会强转崩溃）→ **追加末尾规避** |

#### 实现要点（踩过的坑，按顺序）

1. **插入的 View 必须实现 `StatusIconDisplayable`** —— 容器 `onMeasure` 无判空强转，否则状态栏崩。
   解法：反射 `new` 一个原生 `NetworkSpeedView`（它本身就是 `LinearLayout + StatusIconDisplayable`），
   再把 `mNetworkSpeedNumberText` / `mNetworkSpeedUnitText` / `mContainer` 三个字段换成自己的控件。
2. **不能调 `setVisibilityByController()`** —— 会连锁 `updateVisibility()` → `updateNetworkSpeed$1()`，
   把原生格式（带尾空格、单行）覆盖掉仪表文本。直接设 `mVisibleByController=true`。
3. **容器 `onLayout` 不调 `super.onLayout`** —— 把所有子 View 都 `layout` 到 `x=0`，
   位置**完全由 `translationX` 决定**。所以**绝不能归零 `translationX`**（归零后停在容器左边界）。
4. **下标越大 → 越靠右**（容器倒序遍历累加）。实测：下标 32 → 屏X=977（正确）；下标 0 → 屏X=795（偏中间）。
   追加到末尾还有个好处：原生图标索引全不受影响，规避 `onRemoveIcon` 错位。
5. **必须 `clipChildren=false`** —— 这是最后一个坑。位移后视图右缘被容器裁剪区切掉，
   表现是「日志显示可见、宽 54、坐标也对，屏幕上什么都没有」，排查了很久。
6. **刷新循环不能因网速视图为空就 `return`** —— 仪表不依赖网速视图，以前这里会连带停掉仪表。
   另外原生 `NetworkSpeedView` 有多个实例，锚点要挑 `isShown()` 为 true 的那个。

#### 改动文件

| 文件 | 说明 |
|---|---|
| `meter/MeterSlot.kt` | **新增**：独立槽位控制器（创建 / 挂载 / 写入 / 卸载） |
| `meter/StatusBarMeterState.kt` | 拆成 `composeNative()`（网速）+ `composeSlot()`（仪表）两条链路 |
| `meter/StatusBarMeterHookBase.kt` | tick 同时驱动两条链路；新增 `refreshSlot()` 与重插守卫 |
| `StatusBarMeterHook.kt` | `separate = true`；`gap_left` 默认值改 6dp（与电池的间距） |
| `StatusBarSpeedHook.kt` | `separate = false` |
| `HookCatalog.kt` | 两个功能的描述改为「各占独立位置，被遮挡时互不影响」 |

**验证**：真机实测超级岛（计时器）出现时，网速 `0.1KB/0.2KB` 与仪表 `1.7w/46℃` 同时正常显示；
岛消失后两者都正常恢复。方案分析见 `docs/ANALYSIS-MeterSplit.md`。

### 2026-10-17（新增时钟 Hook：闹钟响铃操作重映射 + 上滑距离定制）

**需求**：闹钟响铃界面上滑一下就关闭（极易误触），而「1 分钟后提醒」按钮才是延迟提醒。
用户要求二选一，最终确认**两个都做成独立开关**，且「加长上滑距离」的阈值可自定义；
交换模式下界面文案要同步。

**目标**：`com.android.deskclock` 18.28.0（真机 `versionCode=1306002800`，与素材同版本）。
反编译：jadx CLI 全量 3,909 类。

**定位（`AlarmAlertFullScreenActivity` 是两个手势的汇聚点）**

```
DragCallback#onViewReleased → AlertScreenController#dismiss() → listener.onDismiss(false)   ← 上滑
mSnooze.setOnClickListener  → AlertScreenController#snooze()  → listener.onSnooze()        ← 点按钮
```

- **交换**：hook `onSnooze()` → 执行 `dismiss(false, false, true)`；hook `onDismiss(boolean)` → 执行 `snooze(true)`。
  计时器响铃（`mTimerViewRoot` 可见）原样放行。
- **文案**：hook `DefaultAlarmAlertScreenController#init()`，`mSnoozeHint` 换成 `string/close_alarm_new`（「关闭闹钟」），
  `mAlertSliderHide` 把「关闭闹钟」替换成「延迟提醒」→「滑动延迟提醒」。
- **距离**：原生除「滑过 50%」外还有「15% + 快速一甩」的关闭通道，误触根源就在这里。
  hook `DragCallback#onViewReleased`，未达阈值（默认 60%，参数可调 30~90%）一律强制回弹；
  达到才交回原生。拖动时的 `alpha` 也改成 `1 - pct/阈值`，淡出进度与所需距离一致。

**真机验证（21:19~21:28）**

| 时间 | 操作 | 结果 |
|---|---|---|
| 21:22:00 | 点中央按钮 | 日志「点击提醒按钮 → 改为关闭闹钟」，闹钟消失 |
| 21:26:00 | 上滑 350px（10%） | 「上滑 10% 未达到 60%，强制回弹」，界面保留 |
| 21:26:28 | 上滑 1700px（64%） | 「上滑 → 改为延迟提醒」，锁屏「1 分钟后再响」，闹钟排到 21:27:28 |
| 21:28:52 | 点中央按钮 | 闹钟消失 |

**踩坑记录**

- LSPosed 在模块更新时会**自动把 `scope.list` 里新增的包加入作用域**（本次 `com.android.deskclock`
  无需手动勾选，数据库 `scope` 表直接多了一行）。
- 真机测试时批量 `input swipe` 容易与锁屏手势（上滑解锁）打架，验证上滑距离要用
  `input swipe` 的慢速（≥250ms）并从屏幕中部起手。
- `markActive` 在时钟进程写 RemotePreferences 报异常（`配置写入异常 … active::…`），
  与既有的 systemui 路径一致，不影响 Hook 生效。

**改动文件**

- 新增 `libhook/.../rules/clock/AlarmSwapSnoozeDismissHook.kt`、`AlarmSwipeDismissDistanceHook.kt`
- `HookCatalog`（时钟 TargetApp + 两条 HookMeta）、`HookMeta`（新增 `HookCategory.CLOCK`）、
  `HookRegistry`、`scope.list`
- `docs/HOOKS.md` §12、§13