# Hook 功能清单与 hook 点定位表

> **复核状态**：4 个 Hook 的 hook 点已全部用 **jadx 1.5.6 实机反编译**验证（2026-09-26）。
> 工具链路：`jadx-headless-mcp-v2`（32 个 MCP 工具）+ jadx CLI 全量反编译。

---

## 复核总览

| Hook id | 目标 APK | 反编译规模 | 结论 |
|---|---|---|---|
| `lock_colon` | 系统界面 17.03.260226.r | 30,160 类 | ✅ **9/9 hook 点全部正确** |
| `device_center_size` | 系统界面组件 18.3.2.22.0 | 4,525 类 | ✅ **4/4 hook 点全部正确** |
| `disable_list_autoplay` | 录音机 8.1.5-779121d13 | 5,385 类 | ⚠️→✅ **1 处已失效并修正**（`o1` → `q1`） |
| `installer_redirect` | Android Framework API | 无需反编译 | ✅ 均为稳定公开 API |

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

## 5. 新增 Hook 的模板

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
