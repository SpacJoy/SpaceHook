# 混淆名失效的解决方案（第三方 App Hook 通用）

> 适用：Hook 目标 App 的类名 / 方法名是 R8 混淆名，每次发版可能整批变化。
> 首次触发案例：网易云 9.6.08 → 9.6.13（2026-10-24）。

---

## 一、失效的典型症状

**"静默失效"**：模块装上去了、不崩溃、不报错、日志里也没异常，但功能就是不生效。

根因在本项目的 Hook 装载链路：

```
replace(clazz, "方法", 参数)   // BaseHook.kt:184
  → findMethod() 返回 null
  → logw("方法不存在，跳过：xxx#方法")   // 只是 WARNING
  → 返回 null
loadHook()  → 依然 markActive() + 记「装载成功」   // SpaceHookEntry.kt:132
```

**因此判断 Hook 是否真的生效，不能只看「装载成功」这行日志**，要看有没有
`解析到承载类：xxx` 这类**定位成功**的日志。

更隐蔽的一种情况：**旧类名可能还在，但已经被别的类占用**。
9.6.13 里 `activity.ha` 这个名字仍然存在，但变成了一个无关的 lambda 类
（`<init>(activity.ma, int)` + `invoke()`），里面根本没有 `showDialog`。
所以「`Class.forName` 成功」**不等于**「目标方法还在」。

---

## 二、R8 能改什么、不能改什么

| 项 | 会被改名吗 | 说明 |
|---|---|---|
| 普通类的类名 | ✅ 会 | 每次发版重新排字母表 |
| 方法名、字段名 | ✅ 会 | 同上 |
| **manifest 里注册的组件名** | ❌ **不会** | 改了系统找不到 Activity/Service，App 直接崩 |
| **被 `-keep` 保护的接口/服务类** | ❌ 不会 | 开发者自己保留，例如高德的 `PlanHomeService` |
| **类之间的继承关系** | ❌ 不会 | 名字变了，但"谁继承谁"不变 |
| **方法签名 / 字段类型** | ❌ 不会 | 除非开发者真改了代码 |
| **字符串常量** | ⚠️ 一般不会 | 但可以被 `-adaptresource` 或手写加密，也可能被删 |

**结论：不要记名字，要记关系和结构。**

---

## 三、三层锚点（按稳健程度排序）

### 第 1 层：manifest 锚点（最稳）

拿 `AndroidManifest.xml` 里注册过的类当起点，例如：

```kotlin
const val CLS_PLAYER_ACTIVITY = "com.netease.cloudmusic.activity.PlayerActivity"
val player = findClass(CLS_PLAYER_ACTIVITY)
```

查看某 APK 里注册了哪些组件：

```bash
aapt2 dump xmltree app.apk --file AndroidManifest.xml | grep -oE '"com\.example\.[A-Za-z0-9_.]+"'
```

⚠️ 注意：manifest 里的 Activity 名通常是**未混淆的语义名**（`PlayerActivity`），
而它的**父类**才是混淆名。这个反差正是锚点的价值所在。

### 第 2 层：继承链（本项目主用）

从 manifest 锚点沿父类向上爬，找第一个**自己声明**了目标方法的祖先：

```kotlin
AnchorFinder.findAncestor(player) { clazz ->
    AnchorFinder.declaresMethod(clazz, "showDialog", arrayOf(Boolean::class.javaPrimitiveType!!, String::class.java))
}
```

实测网易云两版的链路（**名字全变了，关系没变**）：

| | 9.6.08 | 9.6.13 |
|---|---|---|
| 播放页 | `PlayerActivity` | `PlayerActivity`（未变） |
| 祖先链 | → `o8` → `p4` → **`ha`** | → `t8` → `u4` → **`ma`** |

**必须用 `getDeclaredMethod` 而不是 `getMethod`**：后者会往父类找，
导致每一层祖先都"命中"，定位结果落到错误的最顶层类上。

### 第 3 层：已知名单（兜底 / 加速）

把历次适配确认过的类名记成列表，命中即用。**但必须逐项验证**它自己声明了目标方法，
否则会重演「名字还在、方法没了」的误判：

```kotlin
for (name in KNOWN_HOSTS) {
    val clazz = findClass(name) ?: continue
    if (AnchorFinder.declaresMethod(clazz, METHOD, PARAMS)) return clazz   // ← 必须验证
}
```

---

## 四、其他可选锚点（按需用）

| 锚点 | 适用 | 本项目现状 |
|---|---|---|
| **未混淆服务类**（如高德 `PlanHomeService#getInitRouteType`） | 开发者保留了公开接口 | 高德已用，效果最好 |
| **方法签名组合**（多个方法同时存在） | 找不到稳定起点时 | 网易云可作第 4 层：`showDialog` + `dismissAIModeLoadingDialog` + `showAIModeLoadingDialog` 三者齐全的类全 APK 只有 2 个 |
| **DexKit 扫 dex** | Hook 数量多、维护不过来 | **暂不引入**，见第五节 |

---

## 五、DexKit：什么时候才值得引入

参考项目的主武器（HyperCeiler `dexkit 2.2.0`、AmapRouteDefault `dexkit 2.3.0`），
能在运行时扫 dex 按特征定位，配合缓存做到「版本变了就重扫」。

### 它的工作方式

```
目标 App 进程启动
  → 读缓存指纹（规则版本 + APK 路径 + 大小 + mtime），成本 <1ms
      ├─ 指纹对得上 → 反射取 Method → 结构校验 → 挂上（≈0 耗时）
      └─ 指纹不对   → System.loadLibrary("dexkit") → 扫 dex → 校验 → 写缓存（100~150ms）
```

- 缓存命中也要**重新校验结构**，避免旧缓存指向已不存在的方法
- 多进程写同一缓存文件要加锁（HyperCeiler 用 `FileLock`，AmapRouteDefault 用 `AtomicFile`）

### 它的代价

| 项 | 数值 |
|---|---|
| 依赖体积 | AAR 1.5 MB，arm64 so 约 395 KB（本项目已限定 `abiFilters += "arm64-v8a"`，ABI 对得上） |
| 首次扫描 | 120–155 ms（AmapRouteDefault 实测高德） |
| 改造面 | 每个 Hook 从"写死名字"改成"写查询 DSL"，查询写错不报错、只是扫不到，排查更难 |

### 本项目的落地方案（2026-10-24 已引入）

**DexKit 只做「第三层兜底」，不当主力。** 顺序是：

```
① 已知名单      纯反射，≈0 耗时    ← KNOWN_HOSTS
② manifest 锚点 + 继承链   纯反射，≈0 耗时    ← AnchorFinder
③ DexKit 扫 dex  首次 100~150ms，之后走缓存    ← DexAnchorScanner
```

这样**绝大多数版本的启动完全不碰原生库** —— 只有当 ①② 全落空（网易换架构、名字整体位移）时才会加载
`libdexkit.so` 去扫。这与参考项目「DexKit 为主力」的做法不同，是因为本项目大部分 Hook
目标是系统 App（不混淆），真正需要兜底的只有少数几个。

相关实现：

| 文件 | 职责 |
|---|---|
| `libhook/.../base/AnchorFinder.kt` | ①② 的通用工具（父类链、只认自己声明的方法） |
| `libhook/.../utils/DexAnchorScanner.kt` | ③ DexKit 扫描 + 缓存 + 原生库懒加载 |
| `libhook/.../rules/cloudmusic/...Hook.kt` | 三层串联的实例：①名单 → ②继承链 → ③DexKit |

**缓存指纹** = 规则版本(`RULE_REVISION`) + APK 路径 + 大小 + 修改时间 + 目标方法签名，
存在目标 App 的 `cacheDir`。App 更新或规则改动都会自动失效重扫；缓存命中仍会**反射复核**一次。

**原生库**：`System.loadLibrary("dexkit")` 是懒加载的（只在真要扫时执行），
加载失败（ABI 不支持）只会记日志并退回纯反射，不影响其他 Hook。
本项目 `:app` 已限定 `abiFilters += "arm64-v8a"`，APK 内 `libdexkit.so` 约 395 KB。

### 什么时候该推广到其他 Hook

**DexKit 只解决"名字变了"，解决不了"实现变了"。** 参考项目自己也写明：
「自动定位不能保证所有版本兼容，若初始化架构 / 枚举含义 / 特征字符串同时变化，仍需更新匹配规则。」

所以别一上来就给所有 Hook 加第三层：

- 系统 App（SystemUI / 录音机 / 时钟）**不混淆**，①②就够，加了只是白跑一趟；
- 第三方 App 的 Hook，先看有没有②可用的 manifest 锚点；有就不必急着加③；
- 只有「**没有任何稳定锚点、只能靠已知名单硬撑**」的 Hook，才值得接③。

---

## 六、⚠️ 新增 Hook 强制检查清单

**目标 App 是第三方 App（非系统 App）时，必须逐条确认：**

- [ ] **确认目标 App 是否做了 R8 混淆**
      → 反编译后看类名是不是 `a` / `ha` / `ma` 这种短名；是就说明混淆了
- [ ] **不要写死混淆类名**，改用 `AnchorFinder` 的锚点定位（manifest 锚点 → 继承链）
- [ ] **如果实在找不到稳定锚点**（没有 manifest 可锚的组件、没有未混淆服务类、没有可靠的继承关系）
      → 在 `DexAnchorScanner` 上加一层 DexKit 兜底（参考网易云 Hook 的三层写法），
        并在文档里记录「这个 Hook 依赖 DexKit 兜底」
- [ ] **定位失败必须报 ERROR 日志**，不能安静 `return`
      （否则失效和正常在日志与 UI 上无法区分，见第一节）
- [ ] **失败要能被统计到**：用 `markAnchorFailed(...)` 或让 `requireClass/replace`
      返回 null（它们会自增失败计数），这样启动 Toast 才会提示「失败 N 个」
- [ ] **把历次适配的名字记进 `KNOWN_HOSTS`**，下次更新可以省一次爬链，也留了排查线索

> 系统 App（SystemUI / 录音机 / 时钟等）一般不混淆，可以按类名直接写，
> 但仍建议先反编译确认一遍再下结论。

---

## 六点五、启动提示（Hook 状态 Toast）

Hook 失效最难受的地方是**静默**：不崩、不报错，功能就是没反应，和"没装模块"看起来一模一样。

因此加了「启动时提示 Hook 状态」开关（设置页 → 实验性功能，默认**关闭**）：
目标 App 启动那一刻，用 Toast 告诉本次装载结果。

总开关下面是「提示哪些应用」入口，列出**全部用户应用**（系统应用本来就不弹，不列），
每行一个开关，可以只让某几个 App 提示。两级闸门的关系：

```
exp_toast_on_start（总开关，关闭 → 一律不弹）
  └─ exp_toast_app::<包名>（单个用户应用，默认 true）
```

即：总开关关着时子页面怎么选都不弹；总开关打开后，被单独关掉的那几个才安静。

```
SpaceHook：成功 1 / 失败 1（共 2）
需要适配：去掉心动模式加载弹窗
```

### 怎么判定成功 / 失败 / 跳过

每个 `BaseHook` 自带计数器，由基类方法自动维护：

| 结果 | 含义 | 触发 |
|---|---|---|
| `OK` | 至少装上一个拦截器且无失败 | `hookExecutable` 成功 |
| `FAILED` | 有关键锚点解析不到 | `requireClass` 返回 null、`replace/before` 找不到方法、`markAnchorFailed()` |
| `SKIPPED` | 主动跳过，本进程不适用 | 例如「非主进程，直接 return」 |

**`SKIPPED` 不算失败**：像高德那个 Hook 明说只在主进程工作，子进程里什么都不做是正常的。

### 为什么失败判定要放在基类

因为"静默失效"的根因就在基类：`replace()` 找不到方法时只打一条 `logw` 就 `return null`，
调用方看不出区别。把计数放进基类，**所有 Hook 自动获得正确的失败检测**，
不用每个 Hook 自己记得上报。

### 时机处理

`onPackageReady` 时目标 App 还没有 Activity，直接 `Toast.show()` 看不到。
`HookToast` 的做法是：先算好结果 → 每 500ms 轮询一次「是否已有 Activity」→ 有了才弹。
超过 15 秒放弃，绝不拖累目标 App。

---

## 七、失效后的排查流程（按顺序）

1. **看日志里有没有「解析到承载类」** —— 没有说明定位失败（不是代码逻辑问题）
2. **解 dex + 跑脚本**（离线，几十秒出结果）：

   ```bash
   mkdir -p /tmp/appdex && cd /tmp/appdex
   unzip -o -q /path/to/app.apk 'classes*.dex'

   PY="C:/Users/SpaceJoy/.workbuddy/binaries/python/versions/3.13.12/python.exe"
   cd D:/Code/Android/Projects/LSPosed/Xposed.spacejoy.top

   # ① 目标方法还在不在、在哪个类（参数写 dex 描述符：Z=boolean，Ljava/lang/String;=String）
   "$PY" tools/dex_anchor_scan.py --dex-dir /tmp/appdex \
       --method showDialog --params 'Z,Ljava/lang/String;' --ret V

   # ② manifest 锚点类的继承链，看目标类在第几层
   "$PY" tools/dex_anchor_scan.py --dex-dir /tmp/appdex \
       --super-chain 'Lcom/netease/cloudmusic/activity/PlayerActivity;'
   ```

   输出示例（9.6.13 实测）：

   ```
   classes14.dex  Lcom/netease/cloudmusic/activity/ma;  showDialog(Z,String)V
   ⚠️ 命中 2 个候选，需要再加判据（继承链 / 字符串特征 / 多方法组合）
   ```

   → 把命中结果和 ②的继承链对照，落在链上的那个就是目标。
3. **确认方法体没变**：对比新旧版本的常量字符串（如 `showDialog##_1 isFromPlayModeChange=`）
   —— 字符串还在、方法体结构一样，说明只是改名，改锚点即可
4. **临时抓调用栈定位**（锚点全部落空时的终极手段）：
   在 Hook 里挂 `android.app.Dialog#show()`，打印 `thisObject` 类名 + 业务栈帧

> 脚本 `tools/dex_anchor_scan.py` 是**离线**工具，只读 APK 不碰设备；
> 运行时定位仍由 `AnchorFinder` 负责，两者判据一致。

---

## 八、版本号方案（待定，需用户选）

项目此前一直是 `versionCode = 1` / `versionName = "1.0.0"`，装新版前后无法区分。
建议改成下面几种之一（`app/build.gradle.kts` 的 `defaultConfig`）：

**已选定：A. 语义化 SemVer**（2026-10-24 用户确认），已落地到 `app/build.gradle.kts`：

```kotlin
val versionMajor = 1   // 不兼容的大改 / 架构调整
val versionMinor = 1   // 新增功能
val versionPatch = 0   // 修 bug / 适配新版目标 App
versionName = "$versionMajor.$versionMinor.$versionPatch"
versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
```

- **只改上面三个变量**，`versionCode` 自动换算，不用手改
- 当前版本：`versionName = 1.1.0` / `versionCode = 10100`
- 约定：**每次交付 APK 前必 bump**，并在 `docs/PROGRESS.md` 的变更日志里注明新版号

> ⚠️ 之前一直是 `1.0.0(1)`，本次起跳到 `1.1.0`：因为新增了 DexKit 兜底、装载结果统计、
> 启动 Toast 三个功能，按 SemVer 属于 minor 位。

---

## 九、参考资料

| 项目 | 方案 | 位置 |
|---|---|---|
| AmapRouteDefault | DexKit 2.3.0 + 缓存指纹 + 结构校验 + 未混淆服务锚点 | `Referenzprojekt/AmapRouteDefault` |
| HyperCeiler | DexKit 2.2.0 + `DexKitCacheManager` + `JsonFileCache`（版本 + 系统版本校验） | `Referenzprojekt/HyperCeiler/library/libhook/.../dexkit/` |
| janus | 规则写进 JSON（含写死类名），靠"规则可替换"缓解 | `Referenzprojekt/janus` |
| SignHook | Hook 系统 API，本身不是混淆名，无需处理 | `Referenzprojekt/SignHook` |
| **本项目** | `AnchorFinder`（manifest 锚点 + 继承链 + 已知名单） | `libhook/.../base/AnchorFinder.kt` |
