# Hook 功能清单与 hook 点定位表

> **复核状态**：9 个 Hook，hook 点已全部实测验证（2026-09-26 ~ 2026-10-04）。
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
| `status_bar_meter` | 系统界面 17.03.260226.r | 30,160 类 | ✅ 接管 `NetworkSpeedView`；⚠️ 待真机验证（当前无设备） |

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
| 实现类 | `libhook/.../rules/systemui/StatusBarMeterHook.kt` + `.../rules/systemui/meter/MeterReader.kt` |
| 目标进程 | `com.android.systemui`（主 APK，非插件） |
| 参数 | 4 个槽位 `Select` + 排布 `Select` + 小数位 / 单位 / 粗细 / 字号 / 刷新间隔 |
| 参数 UI | 走**独立整屏页**（`HookParam.FullPage` → `HookParamsScreen`），形态同「控制中心卡片排序」 |
| 默认 | **关闭**（`defaultEnabled = false`）；开启后默认 = 功耗 + 电池温度、双行排布 |

### 功能语义

接管状态栏「实时网速」的显示位置，改为显示所选指标：功耗（W）、电流（mA）、
CPU 温度、电池温度、实时网速。最多 4 项，可单行 / 双行排布。

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
| `NetworkSpeedView#onFinishInflate()` | 缓存视图引用、起定时刷新 |
| `NetworkSpeedView#onDensityOrFontScaleChanged()` / `onMiuiThemeChanged(boolean)` | 系统重套文字外观后，重新套用字号 / 粗细 |

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

### 数据源（全部只读，无 MIUI 私有 API）

| 指标 | 来源 | 换算 |
|---|---|---|
| 功耗 | `/sys/class/power_supply/{battery,bms,Battery,…}/current_now` × `voltage_now` | `P = V×A`（µV × µA ÷ 1e12），取绝对值 |
| 电流 | 同 `current_now` | `|µA| / 1000` → mA |
| 电压兜底 | 电池广播 `"voltage"` | mV ÷ 1000 |
| CPU 温度 | `/sys/class/thermal/thermal_zone*/type` 含 `cpu`（次选 `soc`/`ap`/`gpu`）→ 同目录 `temp` | 原始值 > 1000 视为毫摄氏度 |
| 电池温度 | 电池广播 `"temperature"` | 十分之一摄氏度 ÷ 10 |
| 网速 | `TrafficStats.getTotalRxBytes() + getTotalTxBytes()` 差分 | ÷ 间隔 → B/s，≥1024 KB/s 转 MB/s |

节点不存在 → 该项本次不显示（不是显示 `0`），避免把「读不到」误报成「功耗为零」。

---

## 10. 新增 Hook 的模板

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
