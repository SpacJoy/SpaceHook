# Hook 功能清单与 hook 点定位表

> **复核状态**：13 个 Hook，hook 点已全部实测验证（2026-09-26 ~ 2026-10-17）。
> 工具链路：`jadx-headless-mcp-v2`（32 个 MCP 工具）+ jadx CLI 全量反编译
> （jadx-ai-mcp 插件已弃用；jadx-GUI 不再打开，all.jar 仅 CLI 模式）；
> 抖音（55 个 dex）改用 `dexdump`（build-tools 36.1.0）按 class_defs 逐类验证。

---

## 复核总览

| Hook id | 目标 APK | 反编译规模 | 结论 |
|---|---|---|---|
| `lock_colon` | 系统界面 17.03.260226.r | 30,160 类 | ✅ **9/9 hook 点全部正确** |
| `device_center_size` | 系统界面组件 18.3.2.22.0 | 4,525 类 | ✅ **4/4 hook 点全部正确** |
| `disable_list_autoplay` | 录音机 8.1.5-779121d13 | 5,385 类 | ⚠️→✅ **1 处已失效并修正**（`o1` → `q1`） |
| `installer_redirect` | Android Framework API | 无需反编译 | ✅ 均为稳定公开 API |
| `douyin_nocrop` | 抖音 40.6.0（400601） | 55 个 dex | ✅ **5/5 hook 点全部正确** |
| `adb_stay_awake` | AOSP Framework（system_server） | 无需反编译 | ✅ 移植自 AdbStayAwake（libxposed API 102 同源） |
| `cc_card_order` | 系统界面组件 18.3.2.22.0 | 4,525 类 | ✅ 参考 HyperStar `QSControlCenterList`，8 个控制器 `getPriority` |
| `cc_card_tiles` | 系统界面组件 18.3.2.22.0 | 4,525 类 | ✅ 参考 HyperStar `QSCardTileList`，`QSController#getCardStyleTileSpecs` |
| `status_bar_meter` | 系统界面 17.03.260226.r | 30,160 类 | ✅ 接管 `NetworkSpeedView`，真机已验证（功耗 / 电流 / CPU 温度 / 电池温度） |
| `status_bar_speed` | 系统界面 17.03.260226.r | 30,160 类 | ✅ 同显示位独立网速开关，真机已验证（与仪表合并显示） |
| `cloudmusic_no_smartplay_loading` | 网易云音乐 9.6.08 | 20 个 dex / 约 18 万类 | ✅ 抓 `Dialog#show` 调用栈定位到 `activity.ha#showDialog`；真机已验证弹窗消失 |
| `alarm_swap_snooze_dismiss` | 时钟 18.28.0 | 3,909 类 | ✅ 真机验证：上滑 → 延迟提醒，按钮 → 关闭闹钟，文案同步 |
| `alarm_swipe_dismiss_distance` | 时钟 18.28.0 | 3,909 类 | ✅ 真机验证：10% 上滑回弹、64% 上滑放行（阈值 60% 可调） |

---

## 1. lock_colon —— 锁屏大时钟显示冒号 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/systemui/LockColonHook.kt` |
| 目标 APK | `D:/Code/Android/APK/Hook/系统界面_17.03.260226.r.apk` |
| 目标进程 | `com.android.systemui`、`com.miui.aod` |
| 复核结论 | **全部正确，无需修改** |

### 复核明细

| # | 目标 | 实测位置 | 结论 |
|---|---|---|---|
| A1 | `AllInOneBase#refreshFullTimeStrings()` | `AllInOneBase.java:1234` | ✅ `public final void` |
| A2 | `AllInOneBase#setClockStyleInfo(ClockStyleInfo)` | `:1485` | ✅ |
| A3 | `AllInOneBase#setEditorColonShow(boolean)` | `:2489` | ✅ 内部 `if (this.mColonShow != z)` |
| A4 | `AllInOneBase#setEditorColonViewVisible(boolean)` | `:2504` | ✅ |
| A5 | 字段 `AllInOneBase.mColonShow` | `:102` | ✅ `public boolean`（非 final，可反射改写） |
| A6 | `ClockStyleInfo#convertInfoFromClockBean(int, Context, ClockBean)` | `ClockStyleInfo.java:169` | ✅ **static**，arg2 正是 `ClockBean` |
| A7 | `ClockBean#setColonShow(boolean)` | `ClockBean.java:426` | ✅ |
| A8 | `KeyguardOTAInteractor#generateEditorInfo(String)` | `:45` | ✅ **static** |
| A9 | `KeyguardOTAInteractor#writeEditorInfoIntoSettings(String)` | `:184` | ✅ 内部 L185 直写 `Settings.Secure.putStringForUser(..., "constant_template_editor_info", ...)` |

### 数据链（已验证）

```
Settings.Secure["constant_template_editor_info"]  (JSON)
  -> KeyguardOTAInteractor#generateEditorInfo(String)         [写入层, static]
  -> KeyguardOTAInteractor#writeEditorInfoIntoSettings(String)
       \- Settings.Secure.putStringForUser(..., "constant_template_editor_info", str, userId)
  -> ClockStyleInfo#convertInfoFromClockBean(int, Context, ClockBean)   [渲染层, static]
       \- L165: this.isColonShow = clockBean.isColonShow()
  -> AllInOneBase#setClockStyleInfo(ClockStyleInfo)
       \- L1505: this.mColonShow = allInOneBaseInfo.isColonShow
  -> AllInOneBase#refreshFullTimeStrings()      [L1246 / L1740 / L1847 判冒号]
```

### 实现要点（与代码一致）

- **SystemUI 主进程不点亮独立 `colon_view`**，避免双冒号
  （`setEditorColonViewVisible` 仅在 `com.miui.aod` 挂载）
- `ClockBean.isColonShow` 是 **private 非 final**（`ClockBean.java:59`）→ 反射可改 ✅
- ⚠️ `ClockStyleInfo.isColonShow` 是 **`public final`**（`:108`），**不要直接改它**；
  本实现只改 `ClockBean`，且时机在 `convertInfoFromClockBean` 读取之前，因此无需触碰 final 字段

---

## 2. device_center_size —— 融合设备中心卡片尺寸 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/systemui/DeviceCenterSizeHook.kt` |
| 目标 APK | `D:/Code/Android/APK/Hook/系统界面组件_18.3.2.22.0.apk`（MIUISystemUIPlugin.apk） |
| **目标进程** | **`com.android.systemui`** ⚠️ 不是 `miui.systemui.plugin` |
| 复核结论 | hook 点全部正确；**目标进程已修正**（见下） |

### ⚠️⚠️ 最重要的前提：目标进程必须是 SystemUI

「系统界面组件」(miui.systemui.plugin) 是 SystemUI **动态加载的插件 APK**，
**不作为独立进程运行**。devicecenter 那些类由 **SystemUI 自己创建的 ClassLoader** 加载。

| | |
|---|---|
| ✅ 正确 | 注入 `com.android.systemui`，用 SystemUI 的（子）ClassLoader 找类并 hook |
| ❌ 错误 | 把 `miui.systemui.plugin` 当独立目标进程 |

> 旧 BeanShell 脚本的 `diag.txt` 记录过这个坑 —— 在错误位置 `Class.forName` **全部失败**
> （`ClassNotFoundException`），因为那条 ClassLoader 里没有 MIUISystemUIPlugin 的代码：
> ```
> loader#0 : PathClassLoader[zip file "/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"]
>       FAIL miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder$Mode
> ```

### ClassLoader 三级查找策略

即便在 SystemUI 进程内，这些类也**不在主 ClassLoader**（主 loader 的 DexPathList 指向 `MiuiSystemUI.apk`），
而由一条**子 ClassLoader**（parent 指向 MiuiSystemUI.apk）加载。因此实现里做了三级尝试：

1. 宿主主 ClassLoader（少数机型 plugin 被合并进主 dex）
2. 按 `miui.systemui` 前缀过滤 `ActivityThread.mPackages` 反查
3. **不带过滤全量反查**（plugin 可能以别的 key 注册，兜底）

### 复核明细

| # | 目标 | 实测位置 | 结论 |
|---|---|---|---|
| 1 | `DeviceCenterEntryViewHolder` | `entry/DeviceCenterEntryViewHolder.java:22` | ✅ `public final class ... extends ScaleItemViewHolder` |
| 2 | `DeviceCenterEntryViewHolder$Mode` | `:76` **嵌套枚举** `public enum Mode` | ✅ JVM 名 `...$Mode` 正确 |
| 3 | `Mode.MODE_COLLAPSED / MODE_1_ROW / MODE_2_ROWS` | `:77-79` | ✅ 三个常量齐全 |
| 4 | `DeviceCenterCardController#getMode()` | `devices/DeviceCenterCardController.java:146` | ✅ `private final`；逻辑 size==1→COLLAPSED、>4→2_ROWS、否则 1_ROW |
| 5 | `DeviceCenterCardController#handleUpdateList(boolean)` | `:174` | ✅ `private final void`，L176 读 `pendingUpdateList` |
| 6 | 字段 `DeviceCenterCardController.pendingUpdateList` | `:55` | ✅ `private List<DeviceInfoWrapper>` |
| 7 | `DeviceCenterEntryViewHolder#changeMode(Mode, boolean, boolean, boolean)` | `:162` | ✅ **4 个参数**，改 `args[0]` 正确 |
| 8 | `DeviceCenterEntryViewHolder$Companion` | `:30` 内联嵌套类 + `:23` 静态实例 | ✅ |
| 9 | `Companion#getDeviceCenterEntryHeight(Context, Mode)` | `:57` | ✅ 返回 int，内部按 mode 算高度 |

### 关键坑（已在实现中处理）

- 这些类**不在** `com.android.systemui` 主 APK，而在 `miui.systemui.plugin`。
  必须用 `ClassLoaderUtils` 反查 `ActivityThread.mPackages` 拿到 SystemUI 创建的子 ClassLoader，
  否则 `Class.forName` 会抛 `ClassNotFoundException` / `NoClassDefFoundError`。
- `Companion` 与 `Mode` 都是**内联嵌套类型**，jadx 不会生成独立的 `$Companion.java` / `$Mode.java`
  —— 验证时必须看外层文件，**不要因为找不到独立文件就判定"类不存在"**。
- `getMode()` 是 `private final` → 反射 hook 无碍 ✅

---

## 3. disable_list_autoplay —— 录音列表点击不自动播放 ⚠️→✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/soundrecorder/DisableListAutoPlayHook.kt` |
| 目标 APK | `D:/Code/Android/APK/Hook/录音机_8.1.5-779121d13.apk` |
| 目标进程 | `com.android.soundrecorder` |
| 复核结论 | **1 处失效已修正**，其余正确 |

### ⚠️ 本次复核的核心产出：旧脚本的方法名已失效

| 旧 BeanShell 脚本写的 | jadx 实测 | 结论 |
|---|---|---|
| `fragment.N#z(View, int)` | `N.java:1798 public final void z(View view, int i2)` | ✅ 仍然正确 |
| `ContentFragment#o1(Bundle)` | **`ContentFragment#q1(Bundle)`**（`ContentFragment.java:2028`） | ❌ **已失效 → 已修正** |
| `PlaybackFragment#J1(Bundle)` | 读取点在 `PlaybackFragment.java:1496`（`f5825R = bundle.getBoolean("playback_auto", false)`） | ⚠️ 只读，无需 hook |

`q1` 是播放入口的**铁证**：方法体首行日志即
`p053j1.c.a("SoundRecorder:ContentFragment", "startPlayback")`。

### 数据链（完整，已验证）

```
N.java:1798   public final void z(View view, int i2)          <- 列表项点击
N.java:1880   bundle.putBoolean("playback_auto", true)
N.java:1886   this.f6464o.q1(bundle)                          <- 汇聚点

另有 4 处 putBoolean("playback_auto", true)：
ContentFragment.java:475   普通入口
ContentFragment.java:819   intent.getBooleanExtra("playback_auto", ...) 透传
ContentFragment.java:859   OSBot 卡片入口
ContentFragment.java:902   其他入口

          ↓ 全部汇聚到
ContentFragment.java:2028  public final void q1(Bundle bundle)   [日志 "startPlayback"]
ContentFragment.java:2040  public final void r1(Bundle bundle)   [switchToPlaybackFragment]

其他设置点（不需要改）：
C0357g.java:119                    通知栏播放入口
SoundRecorderToolService.java:714  工具服务入口

          ↓ 最终读取
PlaybackFragment.java:1496  this.f5825R = bundle.getBoolean("playback_auto", false)
```

### 修正内容

实现改为「**候选名列表 + 自动探测**」：

```kotlin
val PLAYBACK_ENTRY_CANDIDATES = listOf("q1", "o1", "p1", "startPlayback")
```

按序探测第一个存在的方法并 hook。下次录音机更新后即使 `q1` 改名，也只需往候选表补一个名字，
不必重读代码。候选表已含旧脚本记录的 `o1`，因此对历史版本同样兼容。

### 为什么用 ThreadLocal 标记

`playback_auto = true` 也出现在通知栏（`C0357g`）与工具服务（`SoundRecorderToolService`）路径，
那是**正常的自动播放**，不应拦截。因此只在 `N#z`（列表点击）打标记，
在播放入口消费标记 —— 只影响列表点击这一条来源。

---

## 4. installer_redirect —— 安装请求回归系统默认安装器 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/generic/InstallerRedirectHook.kt` |
| 目标 APK | `D:/Code/Android/APK/Hook/123云盘_3.3.0.apk` |
| 目标进程 | **`com.mfcloudcalculate.networkdisk`**（已用 `aapt2 dump badging` 确认） |

### ⚠️ 功能语义（容易理解错）

**本 Hook 不指定任何安装器。** 它解决的问题是：

> 部分国产应用会把安装请求**硬绑定**到系统原装安装器
> （如 `com.miui.packageinstaller`），通过 `setPackage()` / `setComponent()` 写死，
> 从而**绕过用户在系统设置里选的「默认安装器」**。

本 Hook 只做一件事 —— **解开这个硬绑定**，让 Intent 重新走系统 resolver，
按**用户当前设置的默认安装器**解析。

| | |
|---|---|
| ✅ 做什么 | 清掉 `setPackage()` / `setComponent()`，规范化成 `ACTION_VIEW + application/vnd.android.package-archive` |
| ❌ 不做什么 | 不指定 InstallerX，也不指定任何具体安装器 |
| 最终由谁接管 | **取决于系统「默认安装器」设置**，与模块无关 |

> 常量 `FORCE_COMPONENT` 默认留空正是为了保持这个语义，**正常使用不要填** ——
> 它只是留给特殊场景的逃生口。

### 实测状态（2026-09-27）

✅ **功能已验证正常**（123云盘 实测）。

> ⚠️ 排查提示：LSPosed 日志里 `com.mfcloudcalculate.networkdisk` 的注入记录
> **可能看不到「匹配到 N 个 Hook」这一行**（日志停在 `PrefsBridge 已接入`），
> 但**功能本身是正常的** —— 这属于 LSPosed 日志侧的缓冲 / 分文件现象，
> **排查时请以实际行为为准，不要仅凭日志缺失判定未生效**。

### hook 点（Android Framework 公开 API，名称稳定）

| # | 类 | 方法 | 说明 |
|---|---|---|---|
| 1 | `android.app.Instrumentation` | `execStartActivity`（**所有重载**） | 所有 startActivity 的汇聚点 |
| 1b | `android.app.PendingIntent` | `getActivity`（所有重载） | 通知栏点击安装 |
| 2 | `android.content.pm.PackageInstaller` | `createSession` | 检测 session 安装路径 |
| 2b | `android.content.pm.PackageInstaller$Session` | `openWrite` | 开启 APK 源文件监听 |
| 2c | `android.content.pm.PackageInstaller$Session` | `commit` | 拦下 session，转发给默认安装器 |

### 两条路径

```
【Intent 路径】
  Instrumentation.execStartActivity / PendingIntent.getActivity
    -> 识别「安装类 Intent」：
         action == INSTALL_PACKAGE
         || type 含 "package-archive"
         || data 以 content:/file: 开头且 .apk 结尾
         || component/package 含 "packageinstaller" / "installer"
    -> 解绑：清 setPackage()/setComponent()，action 改 ACTION_VIEW，
             setDataAndType(data, APK_MIME)，
             加 NEW_TASK + GRANT_READ_URI_PERMISSION，去掉 EXTRA_RETURN_RESULT
    -> 交回系统 resolver -> 按「默认安装器」解析

【Session 路径】（不经过 resolver，任何默认安装器都拦不到）
  监听 FileInputStream 构造捕获刚被读取的 APK
    -> Session#commit 前拦下
    -> abandon 原 session，用 FileProvider 取 URI 转发给默认安装器
```

### 可调参数

| 常量 | 默认 | 说明 |
|---|---|---|
| `FORCE_COMPONENT` | `""` | **保持为空**；填写会强制指定组件，违背本功能初衷 |
| `SESSION_TAKEOVER` | `true` | 是否接管 PackageInstaller session 路径 |
| `STRIP_RETURN_RESULT` | `true` | 是否剥掉 `EXTRA_RETURN_RESULT` |
| `FRESH_APK_WINDOW_MS` | `60000` | 捕获到的 APK 文件有效期 |

---

## 5. douyin_nocrop —— 抖音不裁剪 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/douyin/DouYinNoCropHook.kt`（+ `NoCropReflect` / `NoCropVideoAspect` / `NoCropFitApplier`） |
| 移植来源 | `D:/Code/Android/APK/Hook/douyinnocrop-v1.1.0.apk`（io.github.dunxuan.douyinnocrop，libxposed API 102） |
| 目标 APK | `D:/Code/Android/APK/Hook/抖音.apk`（40.6.0 / versionCode 400601，55 个 dex） |
| 目标进程 | `com.ss.android.ugc.aweme` |
| 复核结论 | **5/5 hook 点全部正确**，无需改名 |

### 功能语义

Feed 流视频 / 图集按**原始宽高比完整显示**（等比贴合全屏容器），不再裁剪填充 9:16。
播放器回报真实尺寸后会以真实宽高比重算；净屏模式、详情页切换后自动 refit。

### hook 点（抖音 40.6.0 dexdump 实测）

| # | 目标 | 实测位置 | 结论 |
|---|---|---|---|
| 1 | `VideoItemParams#getAweme()` | `classes29.dex` | ✅ 未混淆；字段 `mAweme`、`mBaseFeedPlayerView` 均在 |
| 2 | `KeepSurfaceTextureView#onAttachedToWindow()` | `classes55.dex` | ✅ |
| 3 | `FeedImageViewHolder#bind(Aweme)` / `bind(Aweme,int)`（含 Full/Detail 子类） | `classes29.dex`（Full 同 dex，Detail 在 `classes19.dex`） | ✅ bind 在父类，`DetailFeedImageViewHolder extends FeedImageViewHolder` |
| 4 | `CleanModeViewModel#sA(CleanModeCommand,String,boolean)` | `classes29.dex` | ✅ 混淆名 `sA` 未变；实现按**参数签名**查找，抗改名 |
| 5 | `VideoPatchLayout#onVideoSizeChanged(VideoStateInquirer,PlayEntity,int,int)` | `classes47.dex` | ✅ 4 参签名不变 |

### 相关数据类（已验证）

| 类 | 位置 | 结论 |
|---|---|---|
| `Aweme` | `classes16.dex` | `getAid()` / `getVideo()` 未混淆；字段 `imageInfos`、`images : List` ✅ |
| `Video`（`getVideo()` 返回类型） | — | 尺寸字段 `width`/`height`（混淆别名 `t`/`s`）+ `aspectRatio`（宽松反射读取） |
| `RemoteImageView` | `classes23.dex` | 自身无实例字段，`mDraweeHolder` 在父类（Fresco `GenericDraweeView`） |
| `ScalingUtils$ScaleType` | `classes20.dex` | `FIT_CENTER` 常量 ✅ |
| `PlayEntity` / `VideoStateInquirer` | `classes47.dex` / `classes11.dex` | 仅作签名参数类型 |

### 与原实现的差异（修正 2 个 bug）

| # | 原 douyinnocrop | 问题 | 本实现 |
|---|---|---|---|
| 1 | `final float f = i / i2;` | **int 除法**：竖屏视频得 0 被 sanity 区间拦掉、横屏精度截断 | `w.toFloat() / h.toFloat()` 浮点除法 |
| 2 | 宽高对换 `i = i2; i2 = i;` | 顺序写错，**两个都变成 height** | 不做对换，直接使用回报的宽高 |

### 跳过条件（保护体验，任一命中即恢复默认布局）

- **评论区上下文**：`VideoItemParams.commentFeedPageId` 非空 / fragment 系字段类名含 `comment`·`cmt` / Activity 名含 `comment`·`cmt`
- **非主·详情页**：净屏模式激活时放行，否则仅 `MainActivity` / `DetailActivity`
- **图集详情面板展开**：`VideoItemParams.isDetailPagePanelShow`（实测为**字段**）或详情页非净屏
- **容器非全屏级**：父容器宽 < root 宽 85% 或高 < root 高 40%（半屏卡片不适配）

### 复核方法备忘（教训）

- 抖音有 55 个 dex，**不能**用「dex 字符串池扫描」定位类定义 —— 描述符被引用 ≠ 类定义在该 dex
  （实测 `KeepSurfaceTextureView` 的字符串出现在别的 dex）。必须按 **class_defs**（`dexdump` 的
  `Class descriptor` 行）定位，本项目的定位结果见 `build/analysis/dex/dump-p*.txt`。
- 旧版模块（douyinnocrop 1.1.0，面向旧抖音）的全部 hook 点在 40.6.0 依然有效，
  连混淆名 `sA` 都未改；但 `width`/`height` 的混淆别名 `t`/`s` 需要宽松反射兜底。

---

## 6. adb_stay_awake —— ADB 调试常亮 ✅

| 项 | 值 |
|---|---|
| 目标进程 | **system_server**（`scope.list` 的 `system`） |
| 目标 App | `Constants.TARGET_SYSTEM`（"system"），UI 归入「系统应用 → 系统框架」 |
| 来源 | 参考项目 **AdbStayAwake**（`GeneralPeople1970/AdbStayAwake`，`2eb2e74` 2026-09-21），单文件 `MainModule.java`（libxposed API 102，与本项目同 API）；**触发条件已按需求收紧**（见「与原实现的差异」） |
| 实现 | `libhook/.../rules/system/AdbStayAwakeHook.kt` |
| 默认 | 开启；**需重启设备**生效（system_server 无法单独重启） |

### 功能语义（2026-10-03 语义收紧：连接调试工具才常亮）

**常亮判定**（改动核心）：

```
常亮 = adb_enabled == 1  &&  ( adb_connected == 1  ||  USB 线上启用 adb function )
```

1. **监听 ADB 连接状态**：`ContentObserver` 同时跟踪 `Settings.Global.ADB_ENABLED`（前置开关）与
   `Settings.Global.ADB_CONNECTED`（"adb_connected"，adbd 连接状态，USB / 无线连接均会翻转），
   另注册 `BroadcastReceiver` 跟踪 `android.hardware.usb.action.USB_STATE`（sticky 系统广播，
   `connected=true` 且 `functions` 含 "adb"）作 USB 兜底；三者缓存于 `@Volatile` 字段。
2. **强制保持常亮**：**已连接调试工具**时让 `getScreenOffTimeoutLocked` 返回 `Integer.MAX_VALUE`
   （约 24.8 天），系统永不因超时自动熄屏；仅开着调试开关但未连接时不再强制常亮。
3. **允许手动锁屏**：不 Hook 任何电源键路径 —— `goToSleep` 不经过屏幕超时计算，按电源键立即锁屏。
4. **断开后恢复**：不永久修改任何系统字段；调试工具断开时透传 `chain.proceed()` 原值，
   原有屏幕超时设置立即自动生效。

### hook 点（AOSP Framework，名称稳定，无需反编译）

| hook 点 | 作用 |
|---|---|
| `com.android.server.power.PowerManagerService#getScreenOffTimeoutLocked`（所有重载） | 返回极大超时值 |
| `…#updateUserActivitySummaryLocked` / `…#updatePowerStateLocked` | 仅 `deoptimize()`，规避方法内联导致 Hook 不触发 |
| `android.provider.Settings$Global.ADB_ENABLED` + `ContentObserver` | 前置开关跟踪（非 hook，是监听） |
| `android.provider.Settings$Global.ADB_CONNECTED` + `ContentObserver` | adbd 连接状态跟踪（非 hook，是监听） |
| `android.hardware.usb.action.USB_STATE` + `BroadcastReceiver` | USB adb 连接兜底信号（sticky 广播，注册即得当前值） |

### 两个关键坑（原实现实测踩过，Kotlin 移植已保留处理）

1. **返回值类型必须与被 Hook 方法的真实返回类型一致**：不同 ROM 上 `getScreenOffTimeoutLocked`
   返回 `int` 或 `long`（Xiaomi HyperOS / Android 15 为 `long`）。类型不匹配时框架 PROTECTIVE 模式
   回填抛 `ClassCastException` → system_server 开机崩溃 bootloop。处理：以 `chain.proceed()`
   原返回值的运行时类型为准（`is Long` → 返回 `Integer.MAX_VALUE.toLong()`），从根本上避免错配。
2. **懒注册 ADB 监听的时机在 `mLock` 内**：`ensureAdbObserver` 由 `getScreenOffTimeoutLocked`
   在持有 `PowerManagerService.mLock` 时调用，**绝不能**在其中做 Binder IPC（读 Settings /
   注册 ContentObserver），否则锁序颠倒 / Watchdog 超时。处理：只做廉价反射取 `mContext`，
   真正的注册工作 `Handler(mainLooper).post { … }` 投递到主线程，彻底脱离 mLock；
   注册失败复位调度标志允许重试。

### 与原实现的差异

- **触发条件收紧（2026-10-03，用户需求）**：原实现「开启 USB / 无线调试（`adb_enabled=1`）即常亮」；
  本实现改为「**已连接调试工具才常亮**」= `adb_enabled && (adb_connected || USB adb)`。
  信号源：`Settings.Global.ADB_CONNECTED`（AOSP 由 AdbService / AdbDebuggingManager 在
  连接建立 / 断开时更新，USB 与无线均翻转）+ `USB_STATE` sticky 广播兜底。
  **⚠️ 真机实测结论（2026-10-03，HyperOS / Android 16，25098PN5AC）**：
  - 判定链路全部正常：`settings put global adb_connected 1` → 超时变 `2147483647`；
    置 `0` → 恢复原值 `30000`（ContentObserver 监听、判定、常亮、恢复均按预期工作）。
  - **但 HyperOS 不维护 `adb_connected`**（`settings get global adb_connected` 返回 `null`，
    该 key 从不写入）→ **无线调试连接场景下主信号缺失**：真实连着 adb 也不常亮。
  - 待办（方案 B）：改走 hook `AdbDebuggingManager` / adbd 连接回调取得真实连接状态
    （需反编译 HyperOS framework 确认类名 / 方法名）；USB 场景暂靠 `USB_STATE` 广播兜底（待插线实测）。
- 原实现额外 `deoptimize()` 被 Hook 方法本身（best-effort）；移植版沿用，并由 `hookExecutable`
  的 try-catch 兜底，失败不影响主流程。
- 原实现为无界面独立模块；本实现进 `HookCatalog` 体系，受四级开关闸门控制
  （总开关 / 安全模式 / `app_master::system` / `hook::adb_stay_awake`）。
- UI：系统框架卡片隐藏「单独重启」按钮（system_server 不能 force-stop），提示重启设备生效。

---

## 7. cc_card_order —— 控制中心卡片排序 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/systemui/ControlCenterOrderHook.kt`（`MiuiPluginHook`） |
| 目标进程 | `com.android.systemui`（类在 `miui.systemui.plugin` 插件内） |
| 参考 | HyperStar `QSControlCenterList` |
| 参数 | `HookParam.CardOrder()`（模块内仿控制中心的长按拖拽排序页，`CardOrderScreen`） |

### 功能语义

在模块内拖拽调整控制中心各卡片位置（磁贴卡片 / 媒体 / 亮度 / 音量 / 融合设备中心 /
设备控制 / 列表 / 编辑按钮），通过改写各控制器的 `getPriority()` 生效。
数值越小越靠上；某张卡未配置（-1）时回退原生值，行为与系统完全一致。

### hook 点（系统界面组件 18.3.2.22.0）

| hook 点 | 原生优先级 |
|---|---|
| `PluginInstance$PluginFactory#createPluginContext`（插件 ClassLoader 捕获入口） | — |
| `…main.qs.QSCardsController#getPriority` | 20（磁贴卡片） |
| `…main.media.MediaPlayerController#getPriority` | 30（媒体） |
| `…main.brightness.BrightnessSliderController#getPriority` | 31（亮度） |
| `…main.volume.VolumeSliderController#getPriority` | 32（音量） |
| `…devicecenter.entry.DeviceCenterEntryController#getPriority` | 50（融合设备中心） |
| `…devicecontrol.DeviceControlsEntryController#getPriority` | 51（设备控制） |
| `…main.qs.QSListController#getPriority` | 100（列表） |
| `…main.qs.EditButtonController#getPriority` | 200（编辑按钮） |

### 实现要点

- `onPluginLoaded(loader)` 里按 `CC_ORDER_CARDS` 逐个 `Class.forName(…, false, loader)`，
  不存在的控制器跳过并 logw（版本漂移防御）；全部挂不上时 logw 提示。
- 读取配置：`PrefsBridge.getInt(HookPrefs.configKey(id, cardKey), -1)`，`>=0` 才生效。
- ⚠️ **消费方是 `MainPanelContentDistributor` 构造时按 `getPriority()` 升序排列 childControllers，
  修改配置后需重启系统界面才会重排**。

---

## 8. cc_card_tiles —— 磁贴卡片自定义内容 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/systemui/CardStyleTilesHook.kt`（`MiuiPluginHook`） |
| 目标进程 | `com.android.systemui`（类在 `miui.systemui.plugin` 插件内） |
| 参考 | HyperStar `QSCardTileList` |
| 参数 | `HookParam.MultiSelect("tiles")`（建议 2 或 4 个；留空 = 跟随系统） |

### 功能语义

自定义控制中心顶部大磁贴卡片的内容（WiFi / 移动数据 / 蓝牙 / 手电筒 / VoWiFi），
替换系统原生的磁贴组合。系统原生 `getCardStyleTileSpecs()` 只返回
`wifi+cell`（支持移动数据）或 `wifi+bt` 两组；本模块替换后可自由组合。

### hook 点（系统界面组件 18.3.2.22.0）

| hook 点 | 做了什么 |
|---|---|
| `PluginInstance$PluginFactory#createPluginContext` | 插件 ClassLoader 捕获入口 |
| `miui.systemui.controlcenter.qs.QSController#getCardStyleTileSpecs` | 返回模块配置的 tile spec 列表（`wifi` / `cell` / `bt` / `flashlight` / `vowifi1` / `vowifi2`） |

### 实现要点

- 配置值以 `|` 连接存储（`HookPrefs.configKey(id, "tiles")`）；空 / 未配置时
  `chain.proceed()` 原样返回，跟随系统。
- ⚠️ HyperStar 还额外 hook `QSCardItemView#updateBackground` 给非默认磁贴补大卡片背景图——
  这部分属视觉美化，依赖插件内 drawable 资源名，**暂未实现**；非默认磁贴用默认卡片样式渲染，
  功能不受影响。

---

## 9. status_bar_meter —— 状态栏功耗/温度仪表 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/systemui/StatusBarMeterHook.kt`（配置 + 贡献者）、`.../systemui/meter/StatusBarMeterHookBase.kt`（hook 点与视图，与 §10 共用）、`.../meter/StatusBarMeterState.kt`（合并渲染）、`.../meter/MeterReader.kt`（数据源） |
| 目标进程 | `com.android.systemui`（主 APK，非插件） |
| 参数 | 20 项（见「参数页形态」）：4 槽位 + 排布 / 数值格式 / 字体 / 对齐 / 宽度 / 边距 / 空隙 + 刷新间隔 |
| 参数 UI | 走**独立整屏页**（`HookParam.FullPage` → `HookParamsScreen`），形态同「控制中心卡片排序」 |
| 默认 | **关闭**（`defaultEnabled = false`）；开启后默认 = 功耗 + 电池温度、双行排布 |

### 功能语义

接管状态栏「实时网速」的显示位置，改为显示所选指标：功耗（W）、电流（mA）、
CPU 温度、电池温度。最多 4 项，可单行 / 双行排布。

> 网速已拆成独立的 `status_bar_speed`（见 §10）—— 两者共用同一个原生显示位，
> 同时开启时按「仪表在前、网速在后」合并成单行 / 双行文本。

### 参数页形态

**独立整屏页**（`HookParam.FullPage` → `HookParamsScreen`），每项参数占一整行：左侧标题 + 说明、
右侧当前取值；点按整行展开候选列表，数值用 −/＋ 步进、**也可以点数字手动输入**。
底部是「恢复默认」与「重启目标应用生效」。

> 2026-10-04 曾按 HyperCeiler「电池信息」页做过一次形态重构（总开关卡 + 分组参数卡 +
> 开关行 / 滑条行），真机显示不正常，已按用户要求**整体回退**到上面的逐行形态。

| 参数 | 类型 / 选项 | 默认 |
|---|---|---|
| `slot1` ~ `slot4` | `Select`：关闭 / 功耗 / 电流 / CPU 温度 / 电池温度 | 功耗 / 电池温度 / 关 / 关 |
| `layout` | `Select`：单行 / 双行 | 双行 |
| `decimals` | `IntRange` 0 ~ 2（功耗 / 电流的小数位） | 1 |
| `temp_decimal` | `Select`：取整 / 一位小数（温度的小数位） | 取整 |
| `hide_unit` | `Select`：显示 / 全部隐藏 / 仅隐藏温度 / 仅隐藏功耗与电流 | 显示 |
| `reverse_order` | `Select`：正序 / 反转 | 正序 |
| `in_charge` | `Select`：始终显示 / 仅充电时 | 始终显示 |
| `font_style` | `Select`：跟随系统 / 细体 / 加粗 | 跟随系统 |
| `font_size_sp` | `IntRange` 0 ~ 24 sp（0 = 跟随系统） | 0 |
| `align` | `Select`：保持原生 / 靠左 / 居中 / 靠右 | 保持原生 |
| `fixed_width` | `IntRange` 0 ~ 120 dp（0 = 不干预） | 0 |
| `left_margin` / `right_margin` | `IntRange` 0 ~ 16 dp（0 = 保持原生） | 0 |
| `vertical_offset` | `IntRange` −20 ~ 20 dp（正值下移） | 0 |
| `gap_left` / `gap_right` | `IntRange` 0 ~ 30 dp | 0 |
| `refresh_ms` | `IntRange` 500 ~ 5000 ms | 1000 |

**「外边距」和「空隙」是两回事，别混**：

- `left_margin` / `right_margin` → 视图**外面**的 `layoutParams` 边距，决定它与相邻图标、容器的距离；
- `gap_left` / `gap_right` → 视图**内面**的 `setPadding`，决定框边到文字的留白。

**排布语义（2026-10-04 定稿）**：`layout = 双行` 时**网速固定占左列、仪表固定占右列**，
列内各占两行 —— **与开了几个槽位无关**（早先版本让列分配跟着槽位数走，已废弃）。
两列间距 = 网速的 `gap_right` + 仪表的 `gap_left` + 内置 6dp。

**除字号外，样式类参数一律「0 或默认值 = 不干预」**——原生视图的宽度、边距由系统状态栏
布局决定，参数没设就不该覆盖它（否则默认值会把原生边距抹成 0）。

> **2026-10-04 补 UI**：`font_style` / `align` / `fixed_width` / `left_margin` / `right_margin` /
> `vertical_offset` / `reverse_order` / `in_charge` / `temp_decimal` 这 9 项此前**只在 hook 层被读取、
> `HookCatalog` 里没有声明** —— UI 改不了、只能吃默认值，等于一批看不见的旋钮。现已全部补上声明。
> 同时把与 `font_style = 加粗` 完全等效的 `bold` 开关**合并掉**（加粗只剩一个入口），
> `Contributor.bold` 字段已删除。

### 为什么要「接管」而不是自己插布局

原生网速本来就占着状态栏右侧一个槽位（`IconManager` 里 `type == 9` 直接
`inflate(R.layout.network_speed)`）。直接把它的文本换掉，就不用自己造图标槽、
不用 ByteBuddy 生成假图标类，也就不存在与原生布局打架的问题。

### hook 点（系统界面 17.03.260226.r）

| hook 点 | 做了什么 |
|---|---|
| `NetworkSpeedController#updateText(String[])` | 原生推送路径：把 `args` 换成仪表文本后 `proceed` |
| `NetworkSpeedController#-$$Nest$mupdateVisibility(Controller, String)` | 计算 `visible` 前把 `mShowNetworkSpeed` / `mNetworkConnected` 钉成 `true`（强制可见） |
| `NetworkSpeedView#setNetworkSpeed(String, String)` | 视图写入路径：同样换成仪表文本 |
| `NetworkSpeedView#updateNetworkSpeed$1()` | **原生把 `mNetworkSpeedNumber` / `mNetworkSpeedUnit` 写进两个 TextView 的唯一出口** —— 它的 `TextUtils.equals` 守卫因原生值带尾空格而恒为假，所以每次都会重写，是「文本被原生盖回去」的根源。这里整方法替换，改为写我们的文本 |
| `NetworkSpeedView#updateResources$15()` | 系统重套文字外观（内部 `setTextAppearance` 会把字号打回原生 7dp / 6.4dp）→ after-hook 重新套用样式 |
| `NetworkSpeedView#onFinishInflate()` | 缓存视图引用、起定时刷新 |
| `NetworkSpeedView#onDensityOrFontScaleChanged()` / `onMiuiThemeChanged(boolean)` | 系统重套文字外观后，重新套用字号 / 字重 / 对齐 / 宽度 / 边距 / 垂直偏移 |

### 关键坑

1. **原生开关关闭时图标根本不会被创建**。`visible = mShowNetworkSpeed && mNetworkConnected && !mIsMinimalismModeOn`，
   开关关闭 → `visible` 恒 `false` → `setNetworkSpeedIcon` 从不调用 → `IconManager` 里
   连 `NetworkSpeedView` 都不会 inflate。所以必须拦 `-$$Nest$mupdateVisibility`
   把两个布尔字段钉死，否则「hook 挂上了但什么都没发生」。
   （`mIsMinimalismModeOn` 是省电精简模式，属用户主动选择，不强行覆盖。）
2. **`-$$Nest$mupdateVisibility` 是 D8 合成的静态访问桥**，名字里带 `-` 和 `$`，
   Java 里非法（jadx 会显示成 `m1604$$Nest$mupdateVisibility`）。dex 里的真实名字是
   `-$$Nest$mupdateVisibility`；代码里另留了一条「静态 + 两参 + 首参是控制器 +
   名字含 updateVisibility」的兜底匹配，防工具链改名。
3. **改参数必须 `chain.proceed(Object[])`**：libxposed API 102 的 `Chain.getArgs()`
   返回不可变 `List`，`chain.args[i] = ...` 会抛 `UnsupportedOperationException`
   （被 `BaseHook` 的回调 try 吞掉，表现为「静默不生效」）。
4. **视图不可见时 `setNetworkSpeed` 会提前 return**（`!mVisibleByController || mBlocked`），
   所以定时器里先 `setVisibilityByController(true)` + `setBlocked(false)` 再写文本。
5. **原生只在网速变化时推送**，功耗 / 温度会「冻住」，因此仪表自带主线程 Handler
   定时刷新（间隔可配，默认 1000ms）；状态栏重建后 `WeakReference` 失效即自动停机，
   等新视图的 `onFinishInflate` 再启动。
6. **thermal 节点里混着大量阈值节点，必须黑名单剔除**。本机 96 个 `thermal_zone` 中
   `cpu-hw-trip-0` / `cpu-hw-trip-1` 恒为 `95000`（95℃ 是**触发阈值**，不是温度）、
   `sdr0` / `sdr0_pa` 恒为 `-273000`、`*-bcl-lvl*` / `*-ibat-lvl*` 恒为 `0` 或负值。
   早先实现按「`type` 含 `cpu`」顺序取第一个命中节点并缓存路径 → 撞上 `cpu-hw-trip-0`
   就**永远卡在 95℃**（`File.listFiles()` 顺序不保证，属必然踩中）。现在改为
   「黑名单（`trip`/`lvl`/`limit`/`ibat`/`bcl`/`sdr`/`vsense`/`virtual`/`dummy`）
   + 按 zone 序号排序 + 组内取最高 + 1 ~ 150℃ 范围校验」，本机读到 44 ~ 61℃ 的真实核心温度。

### 数据源（全部只读，无 MIUI 私有 API）

| 指标 | 来源 | 换算 |
|---|---|---|
| 功耗 | `/sys/class/power_supply/{battery,bms,Battery,…}/current_now` × `voltage_now` | `P = V×A`（µV × µA ÷ 1e12），取绝对值 |
| 电流 | 同 `current_now` | `|µA| / 1000` → mA |
| 电压兜底 | 电池广播 `"voltage"` | mV ÷ 1000 |
| CPU 温度 | 扫 `/sys/class/thermal/thermal_zone*/type`，**先黑名单剔除阈值节点**，再按 `cpu` → `soc` → `ap` → `gpu` 分组，组内取最高值 | 原始值 > 1000 视为毫摄氏度；只接受 1 ~ 150℃ |
| 电池温度 | 电池广播 `"temperature"` | 十分之一摄氏度 ÷ 10 |
| 网速 | `TrafficStats.getTotalRxBytes() + getTotalTxBytes()` 差分 | ÷ 间隔 → B/s，≥1024 KB/s 转 MB/s |

节点不存在 → 该项本次不显示（不是显示 `0`），避免把「读不到」误报成「功耗为零」。

---

## 10. status_bar_speed —— 状态栏实时网速 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/systemui/StatusBarSpeedHook.kt`（配置 + 贡献者）+ 共用 `meter/StatusBarMeterHookBase.kt`、`meter/StatusBarMeterState.kt`、`meter/MeterReader.kt` |
| 目标进程 | `com.android.systemui`（主 APK，非插件） |
| 参数 | 20 项（见「参数页形态」）：样式 / 排布 / 数值格式 / 符号 / 低速隐藏 / 字体 / 对齐 / 宽度 / 边距 / 空隙 + 刷新间隔 |
| 参数 UI | 走**独立整屏页**（`HookParam.FullPage` → `HookParamsScreen`） |
| 默认 | **关闭**（`defaultEnabled = false`） |

### 功能语义

显示实时网速：`TrafficStats` 收发总量差分 ÷ 间隔 → B/s，按 KB/s 显示，
≥ 1024 KB/s 自动转 MB/s。收发**分开差分**，「上下行」样式才能给出两个独立的值。

### 参数页形态

结构同 §9：**独立整屏页**，逐行 −/＋ 步进 + 点数字手动输入。

| 参数 | 类型 / 选项 | 默认 |
|---|---|---|
| `style` | `Select`：值和单位单行 / 值和单位双排 / 上下行单行 / 上下行双排 | 值和单位单行 |
| `swap_places` | `Select`：上传在上 / 下载在上 | 上传在上 |
| `layout` | `Select`：跟随样式 / 单行 / 双行 | 跟随样式 |
| `decimals` | `IntRange` 0 ~ 2 | 1 |
| `hide_unit` | `Select`：显示 / 隐藏（`KB/s`、`MB/s` 整段一起隐藏） | 显示 |
| `hide_arrow` | `Select`：显示 / 隐藏（`↑` `↓` 箭头） | 显示 |
| `hide_per_s` | `Select`：显示 / 隐藏（隐藏 `/s`，只留 `KB`、`MB`） | 显示 |
| `hide` | `Select`：不隐藏 / 低于阈值时隐藏 | 不隐藏 |
| `hide_slow` | `IntRange` 1 ~ 2048 KB/s（收发合计） | 64 |
| `hide_all` | `Select`：正常显示 / 一律留空（配合「低速隐藏」，相当于临时关掉显示） | 正常显示 |
| `font_style` | `Select`：跟随系统 / 细体 / 加粗 | 跟随系统 |
| `font_size_sp` | `IntRange` 0 ~ 24 sp（0 = 跟随系统） | 0 |
| `align` | `Select`：保持原生 / 靠左 / 居中 / 靠右 | 保持原生 |
| `fixed_width` | `IntRange` 0 ~ 120 dp（0 = 不干预） | 0 |
| `left_margin` / `right_margin` | `IntRange` 0 ~ 16 dp（0 = 保持原生） | 0 |
| `vertical_offset` | `IntRange` −20 ~ 20 dp（正值下移） | 0 |
| `gap_left` / `gap_right` | `IntRange` 0 ~ 30 dp（框内留白，非外边距） | 0 |
| `refresh_ms` | `IntRange` 500 ~ 5000 ms | 1000 |

**`style` 与 `layout` 的分工**：`style` 决定这段文本**长什么样**（几个片段、怎么拼），
`layout` 决定**断不断行**。`layout = 跟随样式` 时按 `style` 推导 ——
`值和单位双排` / `上下行双排` 语义上就是双行。

**上下行顺序固定「上传在上、下载在下」**，`swap_places` 可换。`rxTx()` 里先给 tx。

### 为什么和 `status_bar_meter` 分成两个 Hook

原生**只有一个**网速显示位。两个功能想各自独立开关、独立调参数，就必须共用这个位，
所以显示引擎（hook 点安装、视图缓存、定时刷新、字体样式）抽到 `StatusBarMeterHookBase`，
两个 Hook 各自只提供一个「文本贡献者」（`StatusBarMeterState.Contributor`）：

| | `status_bar_meter` | `status_bar_speed` |
|---|---|---|
| `order` | 0（排在前） | 10（排在后） |
| 渲染 | 4 槽位任选指标 | 单个网速值 |
| 刷新间隔 | 自己配 | 自己配（实际取两者最小值） |
| 样式（字重 / 对齐 / 宽度 / 边距 / 垂直偏移） | 自己配 | 自己配（**实际以 `order` 最小者为准**） |

`Contributor` 接口除 `order` / `render` 外**全部有默认实现**，新加一个贡献者只需实现这两个成员。
样式类参数的默认值都是「0 = 不干预」，避免默认值把原生视图的宽度 / 边距抹成 0。

合并规则在 `StatusBarMeterState.compose()`：按 `order` 升序拼各贡献者的片段；
双行 = 第一段进「数字」TextView、其余进「单位」TextView；单行 = 全部拼进数字框、
单位框留空。**有贡献者但都没内容**（例如设了「仅充电时显示」而当前没插电、或网速低于
隐藏阈值）→ 写空串把原生内容顶掉；只有视图尚未就绪（`reader == null`）才返回 `null`，
交还原生行为。

### 关键坑

1. **hook 点只能装一次**：两个 Hook 都会走 `onHook()`，靠
   `StatusBarMeterState.claimInstaller()` 保证只装一遍（后到的只注册贡献者）。
2. 其余坑同 §9（`-$$Nest$mupdateVisibility` 钉字段、改参必须 `proceed(Object[])`、
   定时器里先 `setVisibilityByController(true)` 再写文本）。

---

## 11. cloudmusic_no_smartplay_loading —— 去掉心动模式加载弹窗 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/cloudmusic/CloudMusicNoSmartPlayLoadingHook.kt` |
| 目标 APK | `D:/Code/Android/APK/Hook/com.netease.cloudmusic_9.6.08.apk`（9.6.08，真机 `versionName=9.6.08 / versionCode=9006008`，与素材同版本） |
| 目标进程 | `com.netease.cloudmusic`（实测只有这一个进程） |
| 实测结论 | ✅ 真机验证：切歌 / 开心动模式不再弹窗，拦截日志连续命中 3 次，网易云无闪退 |

### 功能语义

开启心动模式、或在歌曲列表里切歌触发心动模式时，屏幕中央会弹出一个
黑底半透明圆角、红心 WebP 动画 +「正在打开心动模式...」的加载弹窗，
持续到推荐接口返回（约 2–5 秒），期间下方界面不可点。

本 Hook 只去掉这个弹窗，**心动模式本身照常工作**（推荐接口、切歌续播均不受影响）。

### hook 点

```kotlin
com.netease.cloudmusic.activity.ha#showDialog(boolean, String)
```

### 定位过程（两次弯路 + 一个可复用的抓栈手法）

1. **按资源 ID 搜常量 —— 走不通。** 文案来自 `R.string.gk_`（`0x7f1327a8`），
   但 `dexdump` 与自写 dex 扫描器在全部 20 个 dex 里都搜不到这个常量：
   代码实际取的是 `w21.f.U0` 字段（`sget`），不是 `const`。
2. **按「心动模式 = SmartPlay」追进程间消息 —— 走不通。** `qp0.z0#y4` 里确实有
   `sendMessageToClient(903 = SMARTPLAY_LOADING, ...)`，但**把它拦掉之后弹窗照旧出现**：
   真正的弹窗是播放页 Activity **本地**构建的 MaterialDialog，与那条消息无关。
   （该方案已回滚，勿再尝试。）

**真正一击命中的办法：抓 `Dialog#show` 的调用栈。** 临时在 Hook 里挂上
`android.app.Dialog#show()`，打印 `thisObject` 类名 + 业务栈帧，得到：

```
Dialog.show -> com.afollestad.materialdialogs.k
    at com.netease.cloudmusic.activity.ha.showDialog(180)
    at com.netease.cloudmusic.activity.ha.W7(1)
    at com.netease.cloudmusic.activity.ha$c.onLoadSuccess(45)
```

`showDialog` 的实现与现象逐条对应（`ha.java:378`）：

```java
inflate(w21.e.f292516c)                                      // 弹窗布局
findViewById(w21.d.f292419b).setImageDrawable(mWebpDrawable)  // 红心 WebP 动画
hk.a.a(this).b(false).r(view, false).e(-872415232)            // 背景 0xCC000000（黑底半透明）
    .j(false).s(new b()).g();
getWindow().setLayout(NeteaseMusicUtils.w(200.0f), -2);        // 宽 200dp、居中
str = getString(w21.f.U0);                                     // 文案「正在打开心动模式...」
mWebpDrawable.start();
aiModeLoadingDialog.show();
animHandler.postDelayed(..., 5000L);                           // 最长 5 秒后自动关闭
```

### 为什么跳过 `showDialog` 是安全的

`dismissAIModeLoadingDialog()`（`ha.java:423`）第一句就是
`if (aiModeLoadingDialog == null || !isShowing()) return;`，
所以「从未创建过弹窗」不会 NPE，也不会留下悬空定时器。

### 版本风险与重定位

`com.netease.cloudmusic.activity.ha` 与 `showDialog` 都是 **R8 混淆名**，大版本更新后可能失效。
重定位步骤：临时 hook `android.app.Dialog#show()` 打印类名 + 调用栈 → 找到新的
`showDialog` 所在类与方法名 → 改实现里的两个常量（`CLS_PLAYER_ACTIVITY` / `METHOD_SHOW_DIALOG`）。

### 反 hook 检测（本次未触发）

APK 自带 native 风控（`libsec-lib.so` / `libnetmobsec-4.4.7.so` / `libnetdev-1.0.0.so`
里含 `xposed` / `de.robv` / `TracerPid` 等特征）。本次真机实测全程未见闪退或异常，
故暂不处理检测面；若日后出现闪退，再单独针对检测点处理。

---

## 12. alarm_swap_snooze_dismiss —— 上滑改为延迟提醒 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/clock/AlarmSwapSnoozeDismissHook.kt` |
| 目标 APK | `D:/Code/Android/APK/Hook/com.android.deskclock_18.28.0.apk`（18.28.0，真机 `versionName=18.28.0 / versionCode=1306002800`，与素材同版本） |
| 目标进程 | `com.android.deskclock`（单进程，实测只有这一个） |
| 实测结论 | ✅ 真机验证：上滑 → 延迟提醒（闹钟排到 1 分钟后）、按钮 → 关闭闹钟、文案同步 |

### 功能语义

闹钟响铃时，原本「上滑一下就关闭闹钟」，而屏幕中央的「1 分钟后提醒」按钮才是延迟提醒 ——
前者极易误触，直接关掉闹钟。本 Hook 交换两个操作：

| 操作 | 原生 | 本 Hook |
|---|---|---|
| 上滑 | 关闭闹钟（`EVENT_ALARM_DISMISS_BY_SWIPE`） | **延迟提醒** |
| 点中央按钮 | 延迟提醒（`EVENT_ALARM_SNOOZED_BY_CLICK`） | **关闭闹钟** |

界面文案同步交换：按钮显示「关闭闹钟」，底部提示由「滑动关闭闹钟」改成「滑动延迟提醒」。

### 定位（jadx 18.28.0）

```
DefaultAlarmAlertScreenController$DragCallback#onViewReleased
  → AlertScreenController#dismiss()          → listener.onDismiss(false)   ← 上滑
DefaultAlarmAlertScreenController#init 里 mSnooze.setOnClickListener
  → AlertScreenController#snooze()           → listener.onSnooze()        ← 点按钮
AlarmAlertFullScreenActivity 实现 AlertScreenController.AlertScreenListener
  → onDismiss(boolean) / onSnooze()
```

两条手势最终都汇聚到 Activity 的两个回调，因此 hook 放在这里可同时覆盖
默认界面与「生活早报（`AlarmLifePostScreenController`）」界面。

### 策略

| Hook 点 | 处理 |
|---|---|
| `AlarmAlertFullScreenActivity#onSnooze()` | 改为执行原 `onDismiss(false)` 的动作：`dismiss(false, false, true)` |
| `AlarmAlertFullScreenActivity#onDismiss(boolean)` | 改为执行原 `onSnooze()` 的动作：`snooze(true)` |
| `DefaultAlarmAlertScreenController#init()` | 文案替换（`mSnoozeHint` / `mAlertSliderHide`） |

```kotlin
// 计时器响铃不参与交换（mTimerViewRoot 可见时原样放行）
private fun isTimerScreen(activity: Any?): Boolean =
    (getField(activity, "mTimerViewRoot") as? View)?.visibility == View.VISIBLE
```

### 文案替换

| 位置 | 字段 | 处理 |
|---|---|---|
| 中央按钮 | `mSnoozeHint` | 换成应用自带 `string/close_alarm_new`（「关闭闹钟」，多语言自动适配） |
| 按钮无障碍描述 | `mSnooze.contentDescription` | 同上 |
| 底部提示 | `mAlertSliderHide` | 「滑动关闭闹钟」→「滑动延迟提醒」（字符串替换，非中文语种匹配不到时保持原文案） |

资源名已用 `aapt2 dump resources` 复核：`string/slider_text_new` = 「滑动关闭闹钟」，
`string/close_alarm_new` = 「关闭闹钟」。

### 真机验证记录（2026-10-17 21:19~21:28）

| 时间 | 操作 | 结果 |
|---|---|---|
| 21:22:00 | 手动点中央按钮 | 日志「点击提醒按钮 → 改为关闭闹钟」，闹钟消失 ✅ |
| 21:26:00 | 上滑 350px（约 10%） | 日志「上滑 10% 未达到 60%，强制回弹」，界面保留 ✅ |
| 21:26:28 | 上滑 1700px（约 64%） | 日志「上滑 → 改为延迟提醒」，锁屏提示「1 分钟后再响」，闹钟排到 21:27:28 ✅ |
| 21:28:52 | 手动点中央按钮 | 日志「点击提醒按钮 → 改为关闭闹钟」，闹钟消失 ✅ |

### 版本风险

`onSnooze` / `onDismiss` 是公开方法、`mSnoozeHint` 等字段名未混淆，随版本变化概率低；
字段名若变化，`getField` 返回 null 会静默跳过文案替换（行为交换仍然生效）。

---

## 13. alarm_swipe_dismiss_distance —— 加长上滑关闭距离 ✅

| 项 | 值 |
|---|---|
| 实现类 | `libhook/.../rules/clock/AlarmSwipeDismissDistanceHook.kt` |
| 目标 APK | `D:/Code/Android/APK/Hook/com.android.deskclock_18.28.0.apk`（18.28.0） |
| 目标进程 | `com.android.deskclock` |
| 实测结论 | ✅ 真机验证：阈值 60% 时 10% 滑动回弹、64% 滑动放行 |
| 参数 | `threshold`：关闭所需滑动比例（30~90%，默认 60%，步进 5；拖动/松手时实时读取） |

### 原生判定（jadx 18.28.0，`DefaultAlarmAlertScreenController`）

```java
private static final float SCREEN_SLIDE_THRESHOLD = 0.15f;   // 快速甩动的最小距离
// DragCallback#onViewReleased：
if ((yvel >= -mFlingVelocity || mScrollPercent <= 0.15f) && mScrollPercent <= 0.5f) {
    startBounceAnimation(...);                    // 回弹
} else {
    settleCapturedViewAt(0, -view.getHeight());   // 关闭
}
```

两条关闭通道：

1. 滑过屏幕高度 **50%**（慢速）；
2. 只滑 **15%** 以上 + 向上快速一甩（`mFlingVelocity`）—— 这才是误触根源。

### 策略

| Hook 点 | 处理 |
|---|---|
| `DragCallback#onViewReleased` | 读 `mScrollPercent`；未达阈值 → 强制回弹（`startBounceAnimation` + `startAlphaRestore`），不走原生逻辑；达到 → 交回原生（此时原生必然关闭） |
| `DragCallback#onViewPositionChanged` | 原生 `alpha = 1 - pct` 在 50% 就淡完，改为 `alpha = 1 - pct/阈值`，让淡出进度与所需比例一致 |

强制回弹前显式 `setField(callback, "mDetermineDismiss", false)`，避免
`onViewDragStateChanged` 误触发 `dismiss()`。

---

## 14. 新增 Hook 的模板

```kotlin
package top.spacejoy.spacehook.libhook.rules.xxx

import top.spacejoy.spacehook.libhook.base.BaseHook

class XxxHook : BaseHook("xxx_hook_id") {
    override fun onHook() {
        val clazz = requireClass("com.target.Class") ?: return

        // 改参数后执行原方法
        before(clazz, "method", arrayOf(String::class.java)) { chain ->
            chain.args[0] = "new value"
        }

        // 替换返回值
        replace(clazz, "another", arrayOf(Int::class.javaPrimitiveType!!)) { chain ->
            val origin = chain.proceed() as? Int ?: return@replace null
            origin * 2
        }

        // 同名方法的所有重载
        hookAllMethods(clazz, "overloaded") { chain -> chain.proceed() }
    }
}
```

### 复核新 Hook 的标准动作

1. **拿包名**（秒出，无需反编译）：
   `aapt2 dump badging target.apk | grep "^package:"`
2. **搜字符串常量定位**（比按类名猜更可靠，混淆名也能找到）：
   用 MCP 的 `search_string_constants`，或在 CLI 反编译产物里 `grep -rn "你的字符串"`
3. **验证类与方法真实存在**：注意**内联嵌套类型**（`$Companion` / `$Mode` 可能没有独立文件）
4. **确认参数个数与位置**：`changeMode` 实际有 4 个参数这类情况很常见
5. **留意 `final` 字段**：Android 10+ 对 final 字段的反射改写可能失效，优先改非 final 字段或换 hook 点

别忘了同步四处：`HookCatalog`、`HookRegistry`、`scope.list`、本文件。
