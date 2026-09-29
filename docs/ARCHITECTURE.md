# SpaceHook 架构说明

## 1. 整体结构

```
┌──────────────────────────────────────────────────────────────────┐
│                        同一个 APK 文件                            │
│                                                                  │
│  ┌────────────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │  :app  (UI 进程)    │  │   :common      │  │   :libhook     │  │
│  │  Compose + Miuix    │  │  纯数据 / 常量  │  │  Hook 实现      │  │
│  │                     │  │                │  │                │  │
│  │  MainActivity       │  │  HookCatalog   │  │ SpaceHookEntry │  │
│  │  HomeScreen         │──▶ HookMeta      ◀──│ BaseHook       │  │
│  │  HookListScreen     │  │  HookPrefs     │  │ HookRegistry   │  │
│  │  SettingsScreen     │  │  Constants     │  │ rules/*        │  │
│  │  XposedServiceManager│ │                │  │                │  │
│  └─────────┬───────────┘  └────────────────┘  └───────┬────────┘  │
│            │                                          │           │
└────────────┼──────────────────────────────────────────┼───────────┘
             │                                          │
             │ 写入                                      │ 读取
             ▼                                          ▲
   ┌──────────────────────────────────────────────────────────┐
   │         LSPosed 数据库 (RemotePreferences group)          │
   │                 group = "spacehook_config"                │
   └──────────────────────────────────────────────────────────┘
             ▲                                          │
             │ XposedService (Binder)                   │ 框架注入
             │                                          ▼
   ┌──────────────────────┐              ┌──────────────────────────┐
   │  LSPosed 框架管理器   │              │  被 Hook 的目标 App 进程   │
   └──────────────────────┘              │  (SystemUI / 录音机 / …)  │
                                         └──────────────────────────┘
```

## 2. 为什么必须拆三个模块

`:app` 是**普通 Android 应用**，运行在自己的进程里。
`:libhook` 里的类引用 `io.github.libxposed.api.*`，而该依赖是 `compileOnly`（运行时由框架提供）。

如果 UI 代码直接引用 `:libhook` 的 Hook 实现类（例如把 `Class` 对象放进列表），
在 UI 进程里就会 `NoClassDefFoundError`。

因此：

- **`:common`** —— 只放**纯数据**（`HookMeta` / `TargetApp` / 常量 / 偏好键），
  同时被 UI 进程与 Hook 进程加载，绝不引用 libxposed 或 Compose。
- **`:libhook`** —— 全部 Hook 实现 + 框架，依赖 `:common`，`compileOnly` libxposed。
- **`:app`** —— UI，依赖两者。UI 只读 `:common` 的数据来渲染，通过 `hookId` 字符串与实现关联。

`:libhook` 的 `HookRegistry` 用 **工厂 lambda** 而非 `Class` 引用列表，
以保证 Hook 实现类的加载时机完全可控（只有真正要分发时才加载）。

## 3. 模块启动时序

```
LSPosed 框架加载模块 APK
        │
        ▼
  实例化 SpaceHookEntry（构造时不做任何初始化）
        │
        ▼
  onModuleLoaded(param)
   ├─ processName = param.processName
   ├─ XLog.init(this, processName)
   └─ PrefsBridge.init(getRemotePreferences("spacehook_config"))
        │
        ├──────────────► 普通 App 进程
        │                onPackageReady(param)  [仅 isFirstPackage]
        │                  └─ dispatch(packageName, classLoader, isSystemServer=false)
        │
        └──────────────► system_server
                         onSystemServerStarting(param)
                           └─ dispatch("system", classLoader, isSystemServer=true)

  dispatch() 内部：
   ① 幂等检查（同包只分发一次）
   ② 总开关 / 安全模式闸门
   ③ HookCatalog.hooksFor(packageName) 拿到该包的 Hook 元数据
   ④ 逐个：PrefsBridge.isHookEnabled() → HookRegistry.create(id) → attach(ctx) → onHook()
   ⑤ PrefsBridge.markActive(id, processName) 回写生效状态
```

## 4. libxposed API 102 关键签名（已从 AAR 用 javap 核实）

### 4.1 入口

```java
public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface
```

### 4.2 XposedInterfaceWrapper / XposedInterface

```java
HookBuilder hook(Executable);
HookBuilder hookClassInitializer(Class<?>);
boolean     deoptimize(Executable);
Invoker<?, Method> getInvoker(Method);
<T> CtorInvoker<T> getInvoker(Constructor<T>);
void        log(int priority, String tag, String message);
void        log(int priority, String tag, String message, Throwable t);
SharedPreferences getRemotePreferences(String group);
ApplicationInfo   getModuleApplicationInfo();
String[]          listRemoteFiles();
ParcelFileDescriptor openRemoteFile(String name);
int     getApiVersion();
String  getFrameworkName();
String  getFrameworkVersion();
long    getFrameworkVersionCode();
long    getFrameworkProperties();
```

常量：`API_101=101`、`API_102=102`、`PRIORITY_DEFAULT/LOWEST/HIGHEST`、
`PROP_CAP_SYSTEM/PROP_CAP_REMOTE/PROP_RT_API_PROTECTION`

### 4.3 Chain（拦截器链）

```java
Executable     getExecutable();
Object         getThisObject();
List<Object>   getArgs();              // 可修改
Object         getArg(int index);
Object         proceed() throws Throwable;
Object         proceed(Object[] args) throws Throwable;
Object         proceedWith(Object result) throws Throwable;
Object         proceedWith(Object result, Object[] args) throws Throwable;
```

> **注意**：没有 `setResult()`。拦截器「返回什么就是方法的返回值」。

### 4.4 Hooker / HookBuilder / HookHandle

```java
interface Hooker { Object intercept(Chain chain) throws Throwable; }

interface HookBuilder {
    HookBuilder setPriority(int);
    HookBuilder setExceptionMode(ExceptionMode);
    HookBuilder setId(String);
    HookHandle  intercept(Hooker);
}

interface HookHandle {
    Executable getExecutable();
    void       unhook();
    String     getId();
    HookHandle replaceHook(Hooker);
}

enum ExceptionMode { DEFAULT, PROTECTIVE, PASSTHROUGH }
```

### 4.5 生命周期

```java
default void onModuleLoaded(ModuleLoadedParam);
default void onPackageLoaded(PackageLoadedParam);
default void onPackageReady(PackageReadyParam);
default void onSystemServerStarting(SystemServerStartingParam);
default boolean onHotReloading(HotReloadingParam);
default void onHotReloaded(HotReloadedParam);
```

参数对象方法：

| 类型 | 方法 |
|---|---|
| `ModuleLoadedParam` | `isSystemServer()`, `getProcessName()` |
| `PackageLoadedParam` | `getPackageName()`, `getApplicationInfo()`, `isFirstPackage()`, `getDefaultClassLoader()` |
| `PackageReadyParam` | 继承上面 + `getClassLoader()`, `getAppComponentFactory()` |
| `SystemServerStartingParam` | `getClassLoader()` |

### 4.6 libxposed:service（模块 App 侧与框架通信）

```java
XposedServiceHelper.registerListener(OnServiceListener);   // static
interface OnServiceListener {
    void onServiceBind(XposedService);
    void onServiceDied(XposedService);
}

// XposedService
int    getApiVersion();
String getFrameworkName() / getFrameworkVersion();
long   getFrameworkVersionCode() / getFrameworkProperties();
List<String>       getScope();
void               requestScope(List<String>, OnScopeEventListener);
void               removeScope(List<String>);
List<HookedTarget> getRunningTargets();
void               hotReloadModule(HookedTarget, Bundle, HotReloadCallback);
SharedPreferences  getRemotePreferences(String group);
void               deleteRemotePreferences(String group);
String[]           listRemoteFiles();
ParcelFileDescriptor openRemoteFile(String);
boolean            deleteRemoteFile(String);

// HookedTarget
int getUid(); int getPid(); String getProcessName();
State getState();            // UP_TO_DATE / STALE / RELOADING / FAILED
long getLoadedVersionCode();
```

> `libxposed:service` 的 AAR 已自带 `XposedProvider` 的 manifest 声明
> （`authorities="${applicationId}.XposedService"`），无需手动添加。

## 5. Miuix 关键信息（已核实）

| 项 | 值 |
|---|---|
| 坐标 | `top.yukonga.miuix.kmp:miuix-ui-android:0.9.4`（+ `-preference-` / `-icons-` / `-nav-`） |
| 底层 | JetBrains Compose Multiplatform **1.12.0**，Kotlin **2.4.20** |
| 基础组件包 | `top.yukonga.miuix.kmp.basic` |
| 偏好组件包 | `top.yukonga.miuix.kmp.preference` |
| 弹层组件包 | `top.yukonga.miuix.kmp.overlay` |
| 主题包 | `top.yukonga.miuix.kmp.theme` |
| 图标包 | `top.yukonga.miuix.kmp.icon`（+ `.basic` / `.extended`） |

常用 API：

```kotlin
MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) { content }
// 颜色： MiuixTheme.colorScheme.primary / onSurfaceVariantSummary / surface / error ...
// 文本： MiuixTheme.textStyles.title1 / title2 / title3 / body1 / body2 / footnote1 / footnote2 / headline1 / headline2 / subtitle / main / paragraph / button

Scaffold(topBar = {...}, bottomBar = {...}) { paddingValues -> }
NavigationBar { NavigationBarItem(selected, onClick, icon/*ImageVector*/, label) }
Card(modifier, cornerRadius, insideMargin, colors, onClick) { /* ColumnScope */ }
Switch(checked, onCheckedChange, enabled, colors)
SwitchPreference(title, summary, checked, onCheckedChange, insideMargin, enabled, startAction, endActions)
ArrowPreference(title, summary, onClick, insideMargin)
ColorPicker(color, onColorChanged, showPreview, hapticEffect, colorSpace)
Slider(value, onValueChange, modifier, enabled, valueRange, steps)
OverlayDialog(title, show, onDismissRequest) { /* 必须被 Scaffold 包裹 */ }
TextButton(text, onClick, modifier, colors)
```

> ⚠️ `Overlay*` 系列（含 `OverlayDialog`）**必须位于 `Scaffold` 内**才能弹出。
> 本项目的 `MainScreen` 已用 `Scaffold` 包裹全部页面。

## 6. ProGuard / R8

模块入口必须保留，且 `java_init.list` 需随混淆重写：

```proguard
-keep class top.spacejoy.spacehook.libhook.SpaceHookEntry { public <init>(); }
-keep class top.spacejoy.spacehook.libhook.** { *; }
-keep class top.spacejoy.spacehook.common.** { *; }
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list
```

## 7. 调试技巧

```bash
# 1. 看模块是否被注入
adb logcat -s SpaceHook:V

# 2. 看 LSPosed 日志
adb shell "su -c 'cat /data/adb/lspd/log/verbose.log'"

# 3. 查看 RemotePreferences 是否写入（LSPosed 数据库）
#    最直观的还是 App 首页的「已生效进程」列表

# 4. 编译产物
ls app/build/outputs/apk/debug/app-debug.apk
```
