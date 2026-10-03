# SoundMan 移植分析报告

> **状态**：分析完成（2026-10-03），**尚未开始移植**（用户决策：先只出分析报告）。
> **来源项目**：`D:\Code\Android\Projects\Referenzprojekt\SoundMan`（killerprojecte/SoundMan，YukiHookAPI 模块）。
> **规模**：hook 目录合计约 14,000 行（systemui scope ≈ 12,000 行 + 音频核心 ≈ 2,000 行有效代码）。
> **分析方式**：两个并行子代理分别全量核实「SystemUI 音量面板入口」与「系统音频核心 + Zygote 设备亲和性」。

---

## 1. 功能语义总览

| 功能 | 语义 | 生效位置 |
|---|---|---|
| **按应用音量** | 对每个应用做 0-100% 音量衰减（`multiplier = percent/100`，**不能 >100%**），实时作用于 STARTED 播放器 | system_server（`IPlayer.setVolume`） |
| **按应用固定输出设备** | 把应用钉到指定音频设备（最多 **3 条独立链路**，超 3 台直接抛错） | **目标 App 进程**（`AudioTrack/MediaPlayer.setPreferredDevice`） |
| **HyperOS 音量侧栏入口** | 在音量侧栏折叠态圆钮列插入 SoundMan 入口圆钮，点击打开音量面板 | com.android.systemui 进程 |

补充语义：

- **设备身份匹配**用 AudioSystem internal `type + address`（展示名不参与匹配；`addressesMatch` 对匿名 MAC 兼容末 4 位比较）。
- **断开回退**：设备断开→`fallbackToSystem`（hint 置空=FollowSystem），保留原目标；重连后 `publishAll` 重算，原规则继续生效。
- **拆 Mix 机制**（README 未写清）：本质是 **usage 伪装** —— 第 2/3 台设备的播放器把 `AudioAttributes.usage` 改写为 RINGTONE/ALARM（legacy `streamType` 改 STREAM_RING/ALARM，contentType 不改），使播放器落入不同策略流，再各自 `setPreferredDevice`。全部秘密在 `PreferredDeviceHooker` + `PreferredDeviceUsage`。
- **面板只列正在播放应用**（`MediaPlaybackProbe`）：uid≥10000、state==STARTED、usage∈{MEDIA,RINGTONE,ALARM} 或 volumeControlStream==STREAM_MUSIC、剔除 `com.miui.miwallpaper`，同 uid 留一条。

**与原 README 不一致（以源码为准）**：

1. 规则持久化**以 UID 为主键**（非包名）；包名仅展示 + UID 复用校验，不匹配即回退默认。
2. 音量是 0..1 **衰减**，不是增益，不能超过 100%。
3. 「拆 Mix」= usage 伪装（见上）。
4. `HiddenAudioSystem` 保留 `setUidDeviceAffinities/removeUidDeviceAffinities`，但源码注明**仅诊断/单测用**，不是改道主路径（旧 Settings.Global 机制已废弃）。

---

## 2. 架构总览

三个装载点（`HookEntry.kt`）：

| 装载点 | Hooker | 行数 | 职责 |
|---|---|---|---|
| `loadZygote` | PreferredDeviceHooker | 646 | 各 App 进程内钉输出设备 + usage 伪装拆 Mix |
| `loadSystem`（system_server） | SystemAudioHooker + SystemAudioRuntime | 158 + 703 | uid↔播放器映射、按应用音量、规则宿主（IPC host） |
| `loadApp("com.android.systemui")` | SystemUiVolumeEntryHooker + Runtime | 576 + 2287 | HyperOS 音量侧栏入口 + 面板 |

规则链路：`SharedPreferencesRuleStore`（prefs "soundman_rules"，UID 主键，revision 递增）→ AIDL（`HostOfferPublisher`/`HostOfferMailbox`/`ISoundManHostOffer`）+ ContentProvider 桥（`RuleStoreBridgeProvider`，AUTHORITY=`hk.uwu.soundman.rule-store`）→ system_server 生效。

**两套机制解耦**：

- **音量** = system_server 全局一处生效（仅 `android` 作用域即可）。
- **路由** = 目标 App 必须入作用域（App 进程内 `setPreferredDevice`），system_server 的 `applySetRoute` **只写规则、不改道**；规则保存后 `PreferredDeviceSync.publish`（写 prefs "soundman_route_hints" + 广播 `hk.uwu.soundman.action.PREFERRED_DEVICE`，权限 CONTROL_HOST）→ `PreferredDeviceHooker` 收到且 uid 匹配才 `applyHint`。冷启动由 `loadStoredRoute` 从 prefs 恢复。

---

## 3. Hook 点定位表

### 3.1 系统音频核心（system_server + Zygote/App 进程）

| 目标类#方法(签名) | 进程 | 方式 | 做了什么 | 目的 |
|---|---|---|---|---|
| `AudioService#trackPlayer(PlayerBase$PlayerIdCard)→int` | system_server | before+after | before 存 `Binder.getCallingUid()`；after 取 piid + 反射 `PlayerIdCard.mIPlayer` 得 IPlayer，dispatch Track | uid↔播放器映射 |
| `AudioService#playerEvent`（全部重载） | system_server | hookAll+after | dispatch Event(piid,state)；STARTED 时应用音量 | 播放态跟踪 |
| `AudioService#releasePlayer(int)` | system_server | after | dispatch Release 清理 | 生命周期 |
| `AudioService#systemReady()` | system_server | after | 初始化 SystemAudioRuntime（幂等防重入，失败 rethrow）；ready 前事件缓存重放 | 宿主启动时机 |
| `Instrumentation#callApplicationOnCreate(Application)` | Zygote→各 App | before | loadStoredRoute + installTrackHooks + ensureReceiver + ensureDeviceCallback | App 侧注入总入口 |
| AudioTrack 构造器（≥4 参全部） | App | before+after | before `rewriteUsageArgs`（改 usage / 首参 streamType）；after 注册 + `applyToTrack` 钉设备 | 拆 Mix + 钉路由 |
| `AudioTrack#play()` | App | after | 注册 + applyToTrack | 延迟创建的也钉 |
| `AudioTrack#setPreferredDevice(AudioDeviceInfo)` | App | before | 强制替换 args(0) 为目标设备（applying ThreadLocal 防递归） | 抢占应用自选设备 |
| `AudioTrack$Builder#build()` | App | before+after | before 反射 `mAttributes` 改 usage；after 注册钉 | 覆盖 Builder 路径 |
| `PlayerBase#<init>(AudioAttributes,int)` | App | before | 改 usage | 继承链统一 |
| `PlayerBase#baseUpdateAudioAttributes(AudioAttributes)` | App | before | 改 usage（源码注释：此 ROM 无 setAudioAttributes，HyperOS 特化） | 运行中改属性拦截 |
| `MediaPlayer#start()` | App | after | 注册 + applyToPlayer | 钉设备 |
| `MediaPlayer#setAudioAttributes(AudioAttributes)` | App | before | 改 usage | 拆 Mix |
| `MediaPlayer#setPreferredDevice(AudioDeviceInfo)` | App | before | 强制替换为目标设备 | 抢占 |

### 3.2 SystemUI 音量面板入口（com.android.systemui 进程）

| 目标类#方法 | 方式 | 做了什么 | 目的 |
|---|---|---|---|
| `MiuiRingerModeLayout#onFinishInflate/onAttachedToWindow` | after | scheduleInsertion（锚点未就绪重试） | 插入入口圆钮（48dp，克隆官方 ringer 样式） |
| `MiuiRingerModeLayout#updateExpandedH(Boolean)` | after | applyExpanded：展开时入口 GONE | 跟随展开态 |
| `VolumePanelViewController#showH` | after | 捕获 controller 实例 | 官方面板生命周期 |
| `VolumePanelViewController#dismissH(int)` | after + MethodHandles.unreflect 存 dismissHandle | captureOfficialDismissController（reason=8） | 面板关闭清理 |
| `android.view.View#setBackground(…)` 全重载 | after | runtime.noteBackground(drawable) | 抓 HyperLight 热 LspModuleClassLoader 的唯一途径（drawable.classLoader.toString() 含模块包名） |
| `PluginInstance#loadPlugin/getPlugin` | hook | attach() gate：包名==miui.systemui.plugin 且 ClassLoader 变化才 installVolumeHooks | 插件 reload 换 CL 后重装 |

**ClassLoader 定位**（SystemUiPluginClassLoader）：主链 `PluginInstance` 字段 `mPluginFactory`→`mClassLoaderFactory`→`get()` 取插件 CL；fallback `pluginInstance."pluginData"`→`"context"`→`getClassLoader()`；成员按 runtime class 查找 + ConcurrentHashMap 缓存。所有解析走 `resolve().optional()` + `safeResolve{}` 软失败，不打穿 SystemUI。

入口类全名：`com.android.systemui.miui.volume.MiuiRingerModeLayout` / `VolumePanelViewController`。

---

## 4. 隐藏 API / 反射依赖（按脆弱度分级）

### 高脆弱（HyperOS 升级即失效）

- `PlayerBase$PlayerIdCard.mIPlayer` 字段、`AudioTrack$Builder.mAttributes` 私有字段。
- `PluginInstance` 私有字段 `mPluginFactory`/`mClassLoaderFactory`。
- HyperLight 玻璃桥混淆名 `zf0.l`(9 参静态)、`ht.a`、PrefsBridge(`enable_real_liquid_glass`)；⚠️ `SIZE_TIER=0`（别把刷新档位当尺寸传）。
- `com.android.server.audio.AudioSystemAdapter`（MIUI/AOSP 内部类）。

### 中稳（AOSP 常量多年稳定，但属 hidden / 版本敏感）

- `android.media.AudioSystem` 静态字段/方法：DEVICE_STATE_AVAILABLE、DEVICE_OUT_*、getDeviceConnectionState、getDevicesForStream（setUidDeviceAffinities/removeUidDeviceAffinities 仅诊断）。
- `IPlayer`（编译期不可见）：setVolume(float) 必需，pause/start/trackPause/trackStart 可选。
- AudioPlaybackConfiguration：getClientUid/isActive 必需、getPlayerInterfaceId/getPlayerProxy 可选。
- MIUI 音量面板类：`MiuiRingerModeLayout$RingerButtonHelper`(4 参 ctor)、`…volume.Util`/`MiuiVolumeDialogRes`/`RingerButtonRes`/`VolumeColumn` 等；o3_* 资源名（volume_dialog_columns、dnd_layout…）。
- MiBlur 栈（OS3/OS4 两代判定树）、MIUIX 材质（`HyperMaterialUtils.applyViewMaterial` 3..5 形参软匹配）。
- 截屏 `IWindowManager.captureDisplay` + `setExcludeLayers`。

### 稳定（公开 API / hook 点）

- AudioService 4 个 hook 点（trackPlayer/releasePlayer/systemReady Android 11+ 稳定；playerEvent 用 hookAll 防签名漂移）。
- `AudioTrack/MediaPlayer.setPreferredDevice`、`Instrumentation#callApplicationOnCreate`。

---

## 5. 规则数据流（UI → 生效）

1. **写规则**：`SharedPreferencesRuleStore`（prefs "soundman_rules"，UID 主键，revision 递增；损坏抛 `CorruptedAudioRuleException` 禁止静默回退）。SystemUI 面板经 ContentProvider `content://hk.uwu.soundman.rule-store`（`call()` 方法集：readAll/readOrDefault/save/updateVolume/fallbackToSystem/revision/panelSnapshot/panelSetVolume/panelSetRoute）跨进程操作同一 store；调用方经 `SystemUiSecurityPolicy.isModuleOrSystemUi` 校验（模块 UID 或包名恰为 com.android.systemui，防共享 UID 误放行）。
2. **IPC 拓扑**：host = system_server（`ISoundManHostService.Stub`），client = 模块/SystemUI 进程。握手由 client 发起：`sendBroadcast(ACTION_REQUEST_BINDER，带 oneway 邮箱 HostOfferMailbox)` → bootstrapReceiver（RECEIVER_EXPORTED，worker 线程）→ `HostOfferPublisher` 把 host Binder oneway 投回邮箱（校验 SYSTEM_UID）→ `HostSession.connect`：linkToDeath + registerClient(protocolVersion==4) → 回推全量 snapshot。
3. **命令**（oneway AIDL）：`replaceRules(commandId,revision,List<Bundle>)` / `setVolume(uid,0..100)` / `setRoute(uid,target)` / `requestSnapshot`；结果走 `ISoundManClientCallback.onEvent`；`HostIpcRecovery` 把 "host is closed" 类失败当会话死亡重连。
4. **音量生效**：`applyReplaceRules` → 锁内对 STARTED 播放器 `HiddenPlayer.setVolume(multiplier)`（反射 IPlayer.setVolume，oneway 不阻塞）；无活跃播放器回 RESULT_UID_NOT_ACTIVE。
5. **路由生效**：`applySetRoute` 只写规则（RESULT_OK）→ `PreferredDeviceSync.publish`（prefs + 广播）→ `PreferredDeviceHooker.applyHint`：`PreferredDeviceUsage.allocate` 分三条链路（MAX_INDEPENDENT_DEVICES=3）+ usage 伪装拆 Mix（系统设备保持 MEDIA=1，其余按 alarmFirst 顺序伪装 ALARM=4/RINGTONE=6；单设备/FollowSystem 不伪装）→ `getDevices` 按 type+address 匹配 → `setPreferredDevice(device)`；FollowSystem → `setPreferredDevice(null)`。

---

## 6. 风险与脆弱点

1. **鉴权空洞（最严重，移植必须修复）**：REQUEST_BINDER 广播无权限保护；`HostOfferPublisher` 对 sendFromUid/sentFromPackage 只记日志；`hostService.authenticateBinderCaller` 仅打日志不校验 uid；`registerClient` 只验协议版本——**任何知道协议的 App 都能连 host 并 replaceRules/setVolume/setRoute 改全局规则**。SpaceHook 若保留 IPC，必须加签名级/UID 校验。
2. **system_server 稳定性**：4 个 hook 均轻量（ThreadLocal + post worker），安全；但 `systemReady` 初始化失败 rethrow 会放大开机失败。原实现**无显式 deoptimize**（grep 证实），依赖 YukiHookAPI/LSPlant 处理内联——移植时应按 adb_stay_awake 惯例对关键 hook 点做 deoptimize。
3. **SystemUI 崩溃面**：反射全走 optional + try/catch 软失败，任何漏出异常直接拖死 SystemUI。
4. **ROM 耦合**（原项目声明 Android 16/API 36+）：`mIPlayer`、`baseUpdateAudioAttributes`（HyperOS 特化，AOSP 应走 setAudioAttributes 分支）、AudioSystemAdapter、miwallpaper 剔除、SYSTEM_UI_SETTINGS prefs、HyperOS 侧栏、ignore-focus 多播假设。AOSP 下 AudioService 4 点/常量可用，但多 app 同播依赖小米焦点策略，价值降低。
5. **字符串失效面**：方法名（setBackground/showH/dismissH/updateExpandedH）与 o3_* 资源名随版本改名。
6. **移植别踩的坑**：
   - `RingerButtonHelper` 会把切静音 onClick 绑到 bg_blur，必须在其后 `blurView.setOnClickListener` 覆盖。
   - 入口间距禁止实测兜底（正反馈越撑越高）。
   - HyperLight `SIZE_TIER=0` 参数坑。
   - `GenerationGate` 防后台结果触碰已关闭代际 View；`panelSetRoute` 禁主线程 + CountDownLatch 超时。
   - 错配返回值类型 → system_server 开机崩溃（同 adb_stay_awake 的坑）：`chain.proceed()` 返回值必须与被 hook 方法真实返回类型一致（int/long 按原值运行时类型）。

---

## 7. 移植到 SpaceHook 的方案

### 7.1 YukiHookAPI → libxposed API 102 改写对照

| YukiHookAPI（原） | libxposed 102（SpaceHook） |
|---|---|
| `loadZygote` | 无直接对应；改 hook `Instrumentation#callApplicationOnCreate`（SoundMan 本就用它做 App 侧入口，天然兼容） |
| `loadSystem` | `onSystemServerStarting` → dispatch(TARGET_SYSTEM) |
| `loadApp("com.android.systemui")` | `onPackageReady` + scope.list 含 `com.android.systemui` |
| `method { name = ... }.hook { before/after }` | `BaseHook.hookExecutable(executable, block: (Chain) -> Any?)` + `chain.proceed()` |
| `toClass()` / KavaRef `resolve()` | `requireClass` / 反射查找；**缺成员必须软失败**（SystemUI/system_server 目标） |
| `prefs` 跨进程配置 | `PrefsBridge` + `getRemotePreferences(Constants.PREFS_GROUP)` |
| 无 | `ctx.module.deoptimize(m)`（原实现缺失，移植时补上） |

⚠️ `chain.proceed()` 返回值类型必须匹配被 hook 方法真实返回类型（adb_stay_awake 的 bootloop 坑）。

### 7.2 范围分级（用户决策：先只出分析报告，本节供实施时参考）

**Phase 1 —— 音频核心（价值最高，建议先做）**：

| 内容 | 原始行数 | 处理 |
|---|---|---|
| SystemAudioHooker + SystemAudioRuntime | 158 + 703 | 必搬，精简 IPC 编排 |
| PreferredDeviceHooker + PreferredDeviceUsage + PreferredDeviceSync | 646 + 159 + 475 | 必搬（拆 Mix 全部秘密） |
| hidden 层（HiddenAudioSystem/HiddenPlayer/MediaPlaybackProbe 等 11 件） | 853 | 必搬 |
| RuleStore | 161 | 必搬（UID 主键） |
| IPC 编排 5 件（BridgeClient/HostSession/Mailbox/Publisher/Recovery） | 543 | **可压成单 binder 直连**，顺带修复鉴权空洞 |
| AIDL ×3 | — | 精简（或用 Messenger/ContentProvider 替代） |
| UI 数据层（RuleStoreBridge 542 + AppsSource/AppSettingsStore 617） | 1159 | **丢弃**，换 SpaceHook UI 内嵌简化配置（已定方案） |
| systemui scope 全部 | ≈12,000 | **丢弃**（Phase 2 再议） |
| miuix/ui 纯 UI（SSlider/SColorPicker/SoundPanel 等） | ≈20,000 | **丢弃** |

**Phase 2 —— SystemUI 入口（可选）**：简化版 Hooker + PluginClassLoader 三件 + EntryLayout/Placement + 精简 Runtime（openPanel 换成拉起自家 UI）≈ 5–6 文件 ~1,200 行；LiquidGlass（1,096）/ 材质五件套（1,332）/ 截屏 / 内置面板（4,483）全部可丢。

**Phase 3 —— HyperOS 侧栏入口**（如需要）：仅在 Phase 1 稳定后考虑。

### 7.3 配置 UI（已决策）

采用 **SpaceHook UI 内嵌简化配置**：在 HookListScreen 的 hook 详情参数区（现有 `HookParam.CardOrder`/`HookParam.MultiSelect` 机制）内嵌 per-app 音量/路由规则配置，规则经 RemotePreferences（PrefsBridge）下发；不搬 SoundMan 自绘面板与 ContentProvider 桥。

### 7.4 作用域影响

- 音量功能：仅需 `android`（system_server）。
- 路由功能：**目标 App 必须逐个入 scope**（usage 伪装 + setPreferredDevice 都在 App 进程）——需在 scope.list/UI 中提示用户按需勾选。

---

## 8. 参考资料

- 原项目 README：按应用音量 / 按应用固定输出设备（最多 3 条链路：MEDIA/铃声/闹钟）/ HyperOS 音量侧栏入口。
- 本项目相关：`docs/HOOKS.md` 第 6 节（adb_stay_awake，system_server hook 的先例与 bootloop 坑）、`docs/ARCHITECTURE.md`（libxposed API 102 签名）。
