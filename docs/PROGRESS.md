# SpaceHook 开发进度

> **交接必读**：本文件记录项目的实时状态。每次实质性改动后请更新「变更日志」一节。
> 最后更新：2026-10-17（新增时钟 Hook：闹钟响铃界面「上滑改为延迟提醒」+「加长上滑距离」）

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
| 12 | ADB 调试常亮 Hook（移植 AdbStayAwake） | ✅ 完成 | **首个 system_server Hook**；hook `PowerManagerService#getScreenOffTimeoutLocked`（ADB 开启时返回极大超时值）；构建通过，待真机验证 |
| 13 | 控制中心卡片排序 / 磁贴卡片自定义（参考 HyperStar） | ✅ 完成 | `cc_card_order`（8 个控制器 `getPriority`）+ `cc_card_tiles`（`QSController#getCardStyleTileSpecs`）；hook 点定位见 HOOKS.md 第 7、8 节 |
| 14 | SoundMan 移植分析 | ✅ 完成 | 两路子代理全量分析（音频核心 + SystemUI 入口）；报告见 `docs/ANALYSIS-SoundMan.md`；**移植未开始**（用户决策：先只出分析报告） |
| 15 | 三合一状态图标 Hook（移植 com.aiden.combinedstatus 1.4.3） | ❌ 已撤销 | 2026-10-03 按需求取消移植：hook 实现 / 绘制层 / 配置 UI / 分析文档全部移除；`HookCategory.STATUS_BAR` 与「状态栏」折叠子菜单**保留**，待后续功能挂入 |
| 16 | 状态栏功耗/温度仪表 `status_bar_meter` | ✅ 完成 | 接管原生 `NetworkSpeedView` 显示功耗 / 电流 / CPU 温度 / 电池温度（最多 4 槽，单行或双行）。真机已验证装载与写入（`Hook 装载成功`、仪表文本正常刷新）；CPU 温度恒 95℃ 的根因（命中 `cpu-hw-trip-*` 阈值节点）已修。参数页为独立整屏页，逐行 −/＋ 步进 + 点数字手动输入（仿 HyperCeiler 的形态重构已于 2026-10-04 回退） |
| 17 | 整屏参数页 `HookParamsScreen` | ✅ 完成 | 新增 `HookParam.FullPage` 标记 + 通用整屏参数页（仿「控制中心卡片排序」形态）；`status_bar_meter` 改用该页，列表内联弹窗不再承载它的 10 个参数 |
| 18 | 状态栏实时网速 `status_bar_speed` | ✅ 完成 | 从仪表里拆出的独立开关（用户需求：网速与功耗分开设置）。与 `status_bar_meter` 共用原生显示位：显示引擎抽到 `StatusBarMeterHookBase` + `StatusBarMeterState`，两个 Hook 各只提供一个文本贡献者，同时开启时按序合并。参数页与仪表共用同一个独立整屏页（仿 HyperCeiler 的形态重构已于 2026-10-04 回退） |
| 19 | 参数页形态重构（仿 HyperCeiler） | ❌ 已回退 | `HookParam` 曾新增 `Toggle`（开关）/ `group`（分组标题）/ `dependsOn`（依赖某开关，关时置灰）/ `IntRange.unit`（显示单位），`HookParamsScreen` 重写为「总开关卡 + 分组参数卡 + 底部按钮」，行按类型渲染成开关行 / 下拉行 / 滑条行。真机验证曾通过（`分发完成：成功 6 / 共 6`），但**用户要求回退**（2026-10-04，显示不正常）：4 个 UI 文件退回重构前的逐行形态，`HookParam` 的 4 个新成员一并移除；**hook 层未受影响** |
| 20 | 网易云音乐 `cloudmusic_no_smartplay_loading` | ✅ 完成 | 去掉心动模式加载弹窗（黑底 + 红心动画 +「正在打开心动模式...」）。抓 `Dialog#show` 调用栈定位到 `activity.ha#showDialog`，replace 跳过即可；心动模式功能保留。真机验证弹窗消失、无闪退 |
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
> HyperCeiler / HyperStar / HyperIsland / InstallerX-Revived / janus / SignHook / Capsulyric / SoundMan / AdbStayAwake。
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
