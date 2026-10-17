# 状态栏功耗与网速分离显示 —— 方案分析

> 调研日期：2026-10-18
> 目标 APK：**手机端** `MiuiSystemUI.apk`（`17.03.260226.r`，md5 `a4a91602454d2921c61965c3bbcab79b`）
> 反编译产物：`D:/Code/Android/APK/out/systemui_phone`（30,159 个类）

---

## 0. 结论先说

**当前问题的根因不是「排版挤」，而是「共用一个 View」。**

`status_bar_meter` 与 `status_bar_speed` 现在都往**同一个原生 `NetworkSpeedView`** 里写字
（见 `StatusBarMeterHookBase` 的接管逻辑）。而超级岛遮挡是 **按 View 逐个判定** 的：

```java
// MiuiStatusIconContainer.onLayout()，逐个 child 判定
newStatusIconState4.visibleState = 2;
newStatusIconState4.hiddenBySpace = true;
```

所以只要这一个 View 被岛挤掉，**功耗和网速会一起消失** —— 这正是用户观察到的现象。

**方案：给功耗/温度仪表申请一个独立的、真正的状态栏图标槽位**，让两个指标成为两个独立 View，
各自参与遮挡计算。这与 Flux Decor 3.9.2 的做法一致（它用 `StatusBarTempPowerSlotHook` 造独立槽位）。

---

## 1. 手机端 APK 复核结果（与本地素材的差异）

| 项 | 手机端 | 本地旧素材 | 结论 |
|---|---|---|---|
| 版本名 | `17.03.260226.r` | `17.03.260226.r` | 相同 |
| versionCode | `202602260` | `202602260` | 相同 |
| **md5** | `a4a9160245…` | `4ddaa4a0df…` | **不同，必须用手机端这份** |
| 大小 | 52,333,704 | 52,321,431 | 差 12KB |

> 版本号相同但构建不同（本地那份少了 12KB）。**本方案所有类名/方法名均以手机端反编译产物为准。**

---

## 2. 当前实现为什么会「一起被遮挡」

### 2.1 现在的显示链路

```
NetworkSpeedController.updateText(String[])   ← 原生唯一写文本入口
        ↓
StatusBarIconControllerImpl.setNetworkSpeedIcon(NetworkSpeedState)
        ↓
IconManager.addHolder()  type == 9  →  inflate R.layout.network_speed
        ↓
NetworkSpeedView（状态栏里就这一个 View）
        ↓
【我们的 Hook】把 mNetworkSpeedNumberText / mNetworkSpeedUnitText 换成
   「网速 + 功耗」两列文本（StatusBarMeterState.compose()）
```

关键代码（手机端 `NetworkSpeedController.java:477`）：

```java
public final void updateText(String[] strArr) {
    NetworkSpeedState networkSpeedState = this.mNetworkSpeedState;
    ...
    networkSpeedState.networkSpeedNumber = strArr[0];
    networkSpeedState.networkSpeedUnit = strArr[1];
    ...
    ((StatusBarIconControllerImpl) this.mStatusBarIconController).setNetworkSpeedIcon(networkSpeedState);
}
```

**整个系统只有 `network_speed` 这一个槽位承载网速视图**，没有第二个位置。

### 2.2 遮挡判定的粒度：逐个 View

`MiuiStatusIconContainer.onLayout()`（手机端 `MiuiStatusIconContainer.java:640-690`）：

```java
for (int i19 = size - 1; i19 >= 0; i19--) {
    NewStatusIconState s = layoutStates.get(i19);
    float f4 = s.translationX;
    if ((f4 < paddingStart && !s.signalView) || (limitIcon && i20 >= iMin)) break;
    ...
}
if (i19 != -1) {
    for (int i21 = -1; i21 < i19; i21 = -1) {
        NewStatusIconState s = layoutStates.get(i19);
        s.visibleState = 2;        // ← 2 = 隐藏
        s.hiddenBySpace = true;
        i19--;
    }
}
```

- `layoutStates` 是**每个子 View 一项**（`MiuiStatusIconContainer.java:324` 每帧 `clear()` 后重建）
- 隐藏方式是 `visibleState = 2` + 动画 alpha 到 0（`MiuiStatusBarIconAnimatorController.hideIslandProperties` 用 `ViewProperty.ALPHA`）
- `NetworkSpeedView` 也带 `hiddenBySpace` 状态（`NetworkSpeedView.java:216` 的 `toString()` 就在打印它）

**结论：一个 View = 一个遮挡单位。合并写进同一个 View，就必然同生共死。**

---

## 3. 关键技术发现（决定方案可行性）

### 3.1 容器会自动给新子 View 建遮挡状态 —— 不需要手动造

`MiuiStatusIconContainer.onViewAdded()`（手机端 `:1068-1082`）：

```java
@Override
public final void onViewAdded(View view) {
    super.onViewAdded(view);
    ...
    if (view != null) {
        NewStatusIconState newStatusIconState = new NewStatusIconState();
        newStatusIconState.view = view;
        newStatusIconState.slot = "";
        newStatusIconState.inIslandState = 20;
        newStatusIconState.justAdded = true;
        view.setTag(R.id.status_bar_view_state_tag, newStatusIconState);
    }
}
```

**任何** `addView` 进 `MiuiStatusIconContainer` 的子 View 都会自动获得 `NewStatusIconState`，
从而自动参与 `onMeasure` / `onLayout` 的排布与超级岛遮挡计算。**这是本方案能成立的核心。**

### 3.2 容器 onMeasure 强制要求子 View 实现 StatusIconDisplayable

`MiuiStatusIconContainer.onMeasure()`（手机端 `:998-1010`）：

```java
for (int i4 = 0; i4 < childCount; i4++) {
    StatusIconDisplayable statusIconDisplayable = (StatusIconDisplayable) getChildAt(i4);   // ← 强转，无判空
    if (statusIconDisplayable.isIconVisible() && !statusIconDisplayable.isIconBlocked()
            && !this.ignoredSlots.contains(statusIconDisplayable.getSlot())) {
        this.measureViews.add((View) statusIconDisplayable);
    }
}
```

⚠️ **插入的 View 必须实现 `com.android.systemui.statusbar.StatusIconDisplayable`，否则直接 ClassCastException，状态栏会崩。**

接口方法（手机端 `StatusIconDisplayable.java`）：

```java
public interface StatusIconDisplayable extends DarkIconDispatcher.DarkReceiver, IBlurEffect {
    boolean getDeemHide();
    boolean getRemoveFlag();
    String  getSlot();
    int     getVisibleState();
    boolean isIconVisible();
    void    setAnimationEnable(boolean z);
    void    setBlocked(boolean z);
    void    setDecorColor(int i);
    void    setDeemHide(boolean z);
    void    setDripEnd(boolean z);
    void    setStaticDrawableColor(int i);
    void    setStaticDrawableColor(int i, int i2);
    void    setVisibleState(int i);
    void    setVisibleState(int i, boolean z);
    // default 方法：isIconBlocked / isSignalView / onDensityOrFontScaleChanged /
    //              onMiuiLocaleListChanged / onMiuiThemeChanged / onUiModeChanged / performRemove
}
```

### 3.3 容器是 LinearLayout，布局参数用 LinearLayout.LayoutParams

`public final class MiuiStatusIconContainer extends AlphaOptimizedLinearLayout implements IAnimatable`

`IconManager` 给所有图标用的都是 `onCreateLayoutParams(StatusBarIcon.Shape.WRAP_CONTENT)`，
即 `LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)` + 垂直居中 gravity。

### 3.4 容器会周期性重建子 View —— 插入的 View 需要「守卫重插」

`IconManager.removeIcon()` 用 `mGroup.removeViewAt(i)`，`destroy()` 用 `mGroup.removeAllViews()`，
`StatusBarIconControllerImpl` 每次刷新都会按 slot 顺序重排。
→ **我们插入的 View 会被摘掉**，必须用定时器检查「是否还在容器里」，不在就重新插入。

---

## 4. 两个参考项目的做法对比

### 4.1 Flux Decor 3.9.2（`flux.decor.hook.StatusBarTempPowerSlotHook`）

**做法：造一个独立的槽位 + 自定义 Displayable，走完整图标链路**

```
1. ensureIconSet()            → 往 StatusBarIconList 的 slot 列表里插入自定义 slot 名
2. ensureSlotInLiveList()     → 往图标列表的 live list 里插入一个 holder 实例
3. createDisplayable()        → 用 ByteBuddy 动态生成
                                class X extends FrameLayout implements StatusIconDisplayable
                                并实现 InvocationHandler 转发
4. ensureFallbackIconViews()  → 兜底：找不到容器时直接往 MiuiStatusIconContainer 塞 TextView
5. refreshTick (1000ms)       → 定时刷新文本 + 重插守卫
```

关键点（`StatusBarTempPowerSlotHook.java`）：

```java
private static final int HOLDER_TYPE_CUSTOM = 7;   // 自定义 holder 类型
private static final boolean USE_BYTEBUDDY_SLOT = false;  // ← 注意：ByteBuddy 槽位是关闭的
```

> ⚠️ **`USE_BYTEBUDDY_SLOT = false`** —— Flux Decor 发布版**实际走的是 fallback 路径**：
> 不动态造类，而是直接在 `MiuiStatusIconContainer` 里 `addView` 一个 `TextView`。
> 也就是说 **它自己承认「造槽位」那条路在真机上不稳，最终选了「直接插 View」。**

fallback 插入代码（`ensureFallbackOnContainer`）：

```java
TextView textView = (TextView) viewGroup.findViewWithTag(TAG_FALLBACK_ICON);
if (textView == null) {
    textView = new TextView(viewGroup.getContext());
    textView.setTag(TAG_FALLBACK_ICON);
    textView.setIncludeFontPadding(false);
    textView.setGravity(8388629);      // END | CENTER_VERTICAL
    int index = fallbackInsertIndex(viewGroup);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.gravity = 16;                    // CENTER_VERTICAL
    viewGroup.addView(textView, index, lp);
}
```

插入位置策略（`fallbackInsertIndex`）：优先插在网速/信号之后，否则插在倒数第一个之前。

### 4.2 HyperCeiler（`NewNetworkSpeed.kt`）

**做法：完全不造槽位，只接管 `updateText` 的文本**

```kotlin
nscCls!!.findMethod { name("updateText"); paramCount(1) }.createBeforeHook {
    ...
    it.args[0] = strArr   // 只改文本内容
}
```

- 它做的是「网速样式增强」（上下行分排、箭头、隐藏慢速、总速率等），**本质上还是一个 View**
- 它的「上下两行」用的是 `"$txStr\n$rxStr"`，也就是我们现在的 `LAYOUT_DUAL` 那套
- **它没有解决「功耗与网速分离」的问题**，因为 HyperCeiler 根本没有功耗/温度功能

> 结论：**HyperCeiler 只能作为「网速文本格式化」的参考，解决不了本次需求。**
> 真正的解法参考必须是 Flux Decor 的**独立槽位/独立 View** 思路。

---

## 5. 方案设计

### 5.1 方案 A（推荐）：给仪表插独立 View 到 MiuiStatusIconContainer

**思路**：保留现有「网速接管原生 `NetworkSpeedView`」的链路不动，
另造一个 `TextView`（实现 `StatusIconDisplayable`），插进 `MiuiStatusIconContainer` 作为独立图标。

```
状态栏容器 MiuiStatusIconContainer
 ├─ [通知图标] ...
 ├─ NetworkSpeedView        ← 网速（原生槽位，现有逻辑不变）
 ├─ MeterIconView           ← 功耗/温度（新增，独立 View，独立参与遮挡）
 └─ [电池/信号] ...
```

**收益**
- 两个指标**彻底分离**，超级岛挤掉其中一个不会影响另一个 ✅
- 不占用额外原生槽位，不碰 `StatusBarIconList`，改动面小 ✅
- 容器 `onViewAdded` 自动建遮挡状态，无需手动造 `NewStatusIconState` ✅
- 与 Flux Decor 实机验证过的 fallback 路径一致 ✅

**成本 / 风险**
- 必须实现 `StatusIconDisplayable` 全部方法（Kotlin 里约 60 行样板）⚠️
- 需要「重插守卫」：容器重建子 View 后会丢，定时器要补 ⚠️
- 需要找到容器实例（遍历 `PhoneStatusBarView` → 找 `MiuiStatusIconContainer`）⚠️
- 字号/颜色要跟随系统主题（`onMiuiThemeChanged` / `onDarkChanged`）⚠️

### 5.2 方案 B（更彻底，但风险高）：注册真正的原生槽位

**思路**：仿 Flux Decor 的 `ensureIconSet` + `ensureSlotInLiveList`，
在 `StatusBarIconList` 里注册一个 `meter` 槽位，让 `IconManager` 原生地为它建 View。

**收益**
- 完全走原生图标生命周期，不需要重插守卫 ✅

**成本 / 风险**
- 要 hook `StatusBarIconList.findOrInsertSlot` / `IconManager.addHolder`，改动深 ⚠️
- 要伪造 `StatusBarIconHolder`（type 需为自定义值）⚠️
- Flux Decor 自己都把这条路的开关关掉了（`USE_BYTEBUDDY_SLOT = false`），**说明真机不稳** ❌
- 版本升级极易失效 ❌

> **不建议**。除非方案 A 在实测中遇到无法绕过的障碍。

### 5.3 方案 C（最小改动）：只在「单行」时拆开

**思路**：仅当用户开了单行排布时，把网速与仪表分成两个 View；双行时保持现状。

- 收益：改动小
- 问题：**没有解决根本问题**，双行时仍然一起被遮挡 ❌

> 不推荐，除非用户明确只想解决「空间占用」而非「遮挡联动」。

---

## 6. 方案 A 的实现细节

### 6.1 新增文件

```
libhook/.../rules/systemui/meter/
├── MeterIconView.kt        ← 新增：TextView 子类，实现 StatusIconDisplayable
├── MeterIconSlot.kt        ← 新增：容器查找 + 插入 + 重插守卫
├── StatusBarMeterHookBase.kt  ← 修改：网速仍写原生 View，仪表改走独立 View
├── StatusBarMeterState.kt     ← 修改：Contributor 增加「是否独立槽位」语义
└── MeterReader.kt             ← 不变
```

### 6.2 MeterIconView 关键实现

```kotlin
internal class MeterIconView(context: Context) : TextView(context), StatusIconDisplayable {

    var iconVisible = true
    private var blocked = false
    private var removeFlag = false
    private var visibleState = 0

    override fun getSlot(): String = SLOT_NAME          // "spacehook_meter"
    override fun isIconVisible(): Boolean = iconVisible && !blocked
    override fun isIconBlocked(): Boolean = blocked
    override fun getRemoveFlag(): Boolean = removeFlag
    override fun getVisibleState(): Int = visibleState
    override fun getDeemHide(): Boolean = false

    override fun setVisibleState(state: Int) = setVisibleState(state, false)
    override fun setVisibleState(state: Int, animate: Boolean) {
        visibleState = state
        visibility = if (state == 2) View.INVISIBLE else View.VISIBLE
    }
    override fun setBlocked(z: Boolean) { blocked = z }
    override fun setAnimationEnable(z: Boolean) = Unit
    override fun setDecorColor(color: Int) = Unit
    override fun setDeemHide(z: Boolean) = Unit
    override fun setDripEnd(z: Boolean) = Unit
    override fun setStaticDrawableColor(color: Int) = Unit
    override fun setStaticDrawableColor(a: Int, b: Int) = Unit

    // DarkReceiver
    override fun onDarkChanged(areas: ArrayList<Rect>, darkIntensity: Float, tint: Int) {
        setTextColor(/* 按 darkIntensity 在深浅色之间取 */)
    }
    override fun onLightDarkTintChanged(light: Int, dark: Int, useTint: Boolean) { ... }

    // IBlurEffect
    override fun getBlurRadius(): Int = 0
    override fun setBlurRadius(r: Int) = Unit

    override fun onDensityOrFontScaleChanged() { applyStyle() }
    override fun onMiuiThemeChanged(z: Boolean) { applyStyle() }
}
```

> ⚠️ `setVisibleState(2)` 里**必须真的隐藏**（`INVISIBLE` 或 `alpha=0`），
> 否则容器判定它隐藏了、我们却还画着 —— 会与岛重叠。这一点要在实测中确认：
> 系统给 `visibleState=2` 后，是否还会走动画把 alpha 打到 0。
> 若系统不处理，我们要自己在 `setVisibleState` 里做。

### 6.3 容器查找与插入

```kotlin
// 1. 拿容器：从 PhoneStatusBarView 递归找 MiuiStatusIconContainer
private fun findIconContainers(): List<ViewGroup> =
    collectStatusBars().mapNotNull { findIconContainerUnder(it) }

private fun findIconContainerUnder(root: View?): ViewGroup? {
    if (root == null) return null
    if (root is ViewGroup && root.javaClass.simpleName == "MiuiStatusIconContainer") return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findIconContainerUnder(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

// 2. 插入（幂等）
private fun ensureInserted(container: ViewGroup, view: MeterIconView) {
    if (view.parent === container) return
    val index = insertIndex(container)      // 网速之后 / 信号之前
    val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    lp.gravity = Gravity.CENTER_VERTICAL
    container.addView(view, index, lp)
}
```

**插入位置**：找到 `NetworkSpeedView` 的下标 `+1`（网速右侧），找不到则插在倒数第一个之前。

### 6.4 重插守卫

容器会重建子 View，所以定时器里每次都要：

```kotlin
if (meterView.parent !== container) ensureInserted(container, meterView)
```

注意 `container` 本身也可能被重建（状态栏销毁重建），要每次重新查找，或用 `WeakReference` 缓存 + 校验 `isAttachedToWindow`。

### 6.5 与现有 StatusBarMeterState 的衔接

现在的 `compose()` 把两个 Contributor 拼成一个 `Pair<String,String>` 写进同一个 View。
改造后：

- **网速 Contributor** → 仍然写原生 `NetworkSpeedView`（现有 `writeText` 链路不变）
- **仪表 Contributor** → 写新的 `MeterIconView`
- `StatusBarMeterState` 增加 `composeSpeed()` / `composeMeter()` 两个方法，各自只取自己那一列

同时「左右双列」这个排布模式**语义变了**：
- 以前：双列 = 同一个 View 里两列文字
- 以后：双列 = 两个独立 View 并排（由容器排布）
→ `LAYOUT_DUAL` 应改为「仪表是否独立槽位」的开关，或者干脆移除、固定为独立。

### 6.6 设置项调整

`HookCatalog` 里 `status_bar_meter` 的描述要改，且新增/调整配置项：

| 键 | 含义 | 变更 |
|---|---|---|
| `separate_slot` | 是否独立显示（默认 true） | **新增** |
| `layout` | 单行/双列 | 语义改为「仪表内部排布」 |
| `gap_left` / `gap_right` | 与相邻图标的间距 | 保留 |

---

## 7. 验证计划

1. **编译期**：`gradle :app:assembleDebug` 通过
2. **不崩溃**：开机 + 下拉 + 锁屏 + 息屏，各切一次，看 logcat 有无 `ClassCastException`
3. **分离生效**：让超级岛出现（播放音乐 / 计时器 / 通话），确认：
   - 网速被挤掉时，功耗仍显示 ✅
   - 功耗被挤掉时，网速仍显示 ✅
4. **主题跟随**：切换深色/浅色、换主题，两个 View 颜色都正确
5. **重建恢复**：横竖屏切换、DPI 变更后，仪表 View 能自动重插

---

## 8. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| `StatusIconDisplayable` 方法签名随版本变 | 编译期用反射，运行期失败 | 反射实现 + 逐方法 try-catch |
| 容器 `onMeasure` 强转崩溃 | **状态栏崩** | 视图必须完整实现接口；先 try-catch 包裹插入 |
| 重插守卫不及时 | 仪表短暂消失 | 定时器 1s 检查；另在容器 `onViewAdded` 后立即补 |
| 插入位置与信号图标冲突 | 排版错乱 | 优先插在 `NetworkSpeedView` 之后 |
| 与原生网速开关联动 | 关网速时仪表也没了 | 现有 `-$$Nest$mupdateVisibility` 钉 true 的逻辑要保留 |

---

## 9. 实测结论（2026-10-18 真机验证完成）

> 探针：`MeterProbeHook`（临时，已从 HookRegistry / HookCatalog 移除）
> 设备：Xiaomi 25098PN5AC，系统界面 `17.03.2260260.r`，LSPosed 2.2.1 (API 102)
> 方法：hook `MiuiStatusIconContainer.onLayout` / `setIgnoredSlots` / `addIgnoredSlots`
> 与 `IconManager.onRemoveIcon` / `destroy`，布局后 dump 全部子 View 状态

### 9.1 实测点 1：`visibleState = 2` 如何隐藏 → **直接设 `visibility`，不是只做动画**

关键样本（超级岛出现时，`network_speed` 被挤掉）：

```
[3:network_speed vs=2 hb=true  is=20 stA=1.0 vA=1.0 vis=0]   ← 被岛遮挡
[3:network_speed vs=0 hb=false is=20 stA=1.0 vA=1.0 vis=0]   ← 正常显示
```

**结论**：
- `vs=2` 的 View 最终 `visibility = 8`（GONE）；`vs=0` 时 `visibility = 0`
- `stA`（state.alpha）恒为 1.0，`vA`（view.alpha）也是 1.0 —— **隐藏不走 alpha 通道**
- `hiddenBySpace=true` 只在真正被岛挤掉时出现，且与 `vs=2` 同时发生

→ **`MeterIconView` 必须在 `setVisibleState(2)` 里自己把 `visibility` 设成 `GONE`**，
   不能指望系统帮忙隐藏。系统会调用 `setVisibleState(visibleState, false)`（见
   `MiuiStatusIconContainer` 的 `applyToView` 分支），所以只需在这个回调里处理。

### 9.2 实测点 2：`ignoredSlots` 内容 → **只有固定黑名单，不含自定义 slot**

```
setIgnoredSlots: [mute, micphone, headset, mikey, privacy_mode, gps, missed_call, ime,
                  cast, bluetooth_handsfree_battery, ethernet, nfc, tty, stealth,
                  second_space, handle, handle_battery, bluetooth]
addIgnoredSlots: [car, tv, pc, pad, phone, sound_box_group, stereo, sound_box_screen,
                  sound_box, wireless_headset, glasses, camera]
```

**结论**：来源就是 `MiuiIconManagerUtils` 的 `RIGHT_BLOCK_LIST`（状态栏）与
`CONTROL_CENTER_BLOCK_LIST`（控制中心），**全是固定常量**。
自定义 slot 名（如 `spacehook_meter`）**永远不会**被加进去 → **不需要 hook 过滤**。

### 9.3 实测点 3：`IconManager` 会不会摘掉我们插入的 View → **有两个真实风险**

#### 风险 A：`destroy()` 会清空整个容器（已实测到）

```
IconManager.destroy() → 容器 removeAllViews，插入的 View 会丢失
```

启动时必然触发一次（`04:41:07.464`），之后状态栏重建也会触发。
→ **重插守卫是必须的**，不是可选项。

#### 风险 B：`onRemoveIcon(i)` 按**索引**删除 —— 插入 View 会让索引错位 ⚠️

```java
public void onRemoveIcon(int i) {
    View childAt = this.mGroup.getChildAt(i);   // ← 按位置取，不按 slot 匹配
    if (childAt instanceof StatusIconDisplayable) {
        childAt.setTag(R.id.view_remove_tag, Boolean.TRUE);
    }
    this.mGroup.removeViewAt(i);                // ← 删掉的可能是别人的图标
}
```

索引由 `StatusBarIconList.getViewIndex(tag, slot)` 算出来，**只统计图标槽位**，
不知道我们在容器里额外塞了一个 View。所以我们插在中间，就会让它删错对象。

→ **缓解方案：把 `MeterIconView` 插到容器的「最后一个可见图标之后」不行（会被后续插入挤走），
   正确做法是插在 index 0 之后的位置并 hook `onRemoveIcon` 做索引补偿**，或者
   **改为在 `onLayout` 里用 `translationX` 定位而不改变子 View 顺序**（不推荐，容器自己会改 translationX）。

实测中 `onRemoveIcon` 在本次会话内**未被调用**（只有 `destroy()` 被调用），
说明它只在图标真正消失时触发。但插件的生命周期里必然会发生，**必须处理**。

### 9.4 额外实测发现（原方案未覆盖）

1. **容器子 View 数量会大幅波动**：实测从 `16` 个变到 `32` 个
   （`handle`、`wifi`、`demo_*` 等会动态加入），**不能缓存索引**，每次都要重新计算插入位置。
2. **`onLayout` 每帧都会跑**（一秒内打了 30 条日志），重插守卫放这里会过于频繁；
   应放在 1s 定时器里，或在 `onLayout` 里只做「不在容器就重插」的轻量判断。
3. **`NetworkSpeedView` 的 slot 名是 `network_speed`**，在 `layoutStates` 里能稳定识别，
   可以可靠地拿它做插入位置锚点。
4. **`StatusIconDisplayable` 接口共 21 个方法**（含 default），与文档第 3.2 节一致，
   `NewStatusIconState` 的字段也全部对得上（`slot` / `visibleState` / `hiddenBySpace` 等）。

### 9.5 对方案 A 的修订

| 原计划 | 实测后的修订 |
|---|---|
| `setVisibleState(2)` 可以不处理 | **必须自己 `visibility = GONE`** |
| 需要 hook `addIgnoredSlots` 过滤 | **不需要**，黑名单是固定常量 |
| 重插守卫「锦上添花」 | **必须**，`destroy()` 启动时就清空 |
| 插入位置可缓存 | **不可缓存**，子 View 数在 16~32 间波动 |
| 未考虑 `onRemoveIcon` 索引错位 | **新增风险**：需 hook 补偿或改为尾部插入策略 |

---

## 10. 参考索引

| 内容 | 路径 |
|---|---|
| 手机端系统界面反编译 | `D:/Code/Android/APK/out/systemui_phone` |
| 容器遮挡逻辑 | `.../statusbar/views/MiuiStatusIconContainer.java:640-690` |
| 容器 onViewAdded | `.../statusbar/views/MiuiStatusIconContainer.java:1068-1082` |
| 容器 onMeasure | `.../statusbar/views/MiuiStatusIconContainer.java:998-1010` |
| 容器 onLayout 隐藏分支 | `.../statusbar/views/MiuiStatusIconContainer.java:700-760` |
| 图标接口 | `.../statusbar/StatusIconDisplayable.java` |
| 网速视图 | `.../statusbar/views/NetworkSpeedView.java` |
| 网速控制器 | `.../statusbar/policy/NetworkSpeedController.java:477` |
| 图标管理器 | `.../statusbar/phone/ui/IconManager.java:104-114` |
| onRemoveIcon 索引删除 | `.../statusbar/phone/ui/IconManager.java:269-306` |
| ignoredSlots 来源 | `.../statusbar/phone/MiuiIconManagerUtils.java` |
| Flux Decor 槽位 Hook | `D:/Code/Android/APK/out/fluxdecor/sources/flux/decor/hook/StatusBarTempPowerSlotHook.java` |
| HyperCeiler 网速 | `Referenzprojekt/HyperCeiler/library/libhook/.../rules/systemui/statusbar/network/NewNetworkSpeed.kt` |
