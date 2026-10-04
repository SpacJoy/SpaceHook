# SpaceHook

基于 **libxposed API 102** 的 LSPosed 模块，把多个针对不同 App 的 Hook 功能聚合到一处，
并提供图形化开关管理。

UI 采用 [Miuix](https://compose-miuix-ui.github.io/miuix/)（小米 HyperOS 设计规范）构建。

---

## 特性

- **多合一**：一个模块承载多个 App 的 Hook 功能，无需装一堆模块
- **App 级 + 功能级双层开关**：既能一键禁用某个 App 的全部 Hook，也能单独开关每个功能
- **实时状态可见**：首页直接列出**当前已注入并运行**的进程，一眼确认是否真的生效
- **现代化 UI**：Miuix 风格，支持 6 种主题模式（含 Monet）、主题色自定义、字体缩放 80%~160%
- **安全模式**：出问题时一键暂停全部 Hook，避免开机循环崩溃
- **易扩展**：新增 Hook 只需改两处 + 加一个文件，UI 自动适配

---

## 三个页面

| 页面 | 内容 |
|---|---|
| **首页** | 激活状态、框架信息（名称/版本/API）、设备信息、**已生效进程列表**、功能概览 |
| **Hook 列表** | 按目标 App 分组的开关列表；App 头部是总开关，展开后是各功能开关，已生效的会亮圆点 |
| **设置** | 通用（总开关/安全模式/调试模式）、Hook 列表选项、外观（主题/主题色/字体大小）、实验性功能、关于 |

---

## 已实现的 Hook 功能

| 功能 | 目标 | 说明 |
|---|---|---|
| 锁屏大时钟显示冒号 | 系统界面 / 息屏显示 | 强制锁屏大时钟显示冒号；同时修正写入层，重启系统界面后依然生效，并覆盖编辑界面的预览 |
| 录音列表点击不自动播放 | 录音机 | 点录音列表只进详情，不自动外放 |
| 安装请求改道到默认安装器 | 发起安装请求的应用 | 把应用内安装请求从系统硬绑定安装器改道到 InstallerX 等默认安装器 |
| 融合设备中心卡片尺寸 | 系统界面组件 | 控制中心设备中心卡片强制单行/双行/自定义高度 |
| 控制中心卡片排序 | 系统界面组件 | 模块内仿控制中心的长按拖拽排序页，改写各卡片控制器优先级 |
| 磁贴卡片自定义内容 | 系统界面组件 | 自定义控制中心顶部大磁贴卡片的磁贴组合（WiFi / 移动数据 / 蓝牙 / 手电筒 / VoWiFi） |
| 抖音不裁剪 | 抖音 | 点开抖音视频详情保持原视频比例，不强制裁剪铺满 |
| ADB 调试常亮 | 系统框架（system_server） | **已连接调试工具时**（`adb connect` 在线 / USB 插着且启用 adb）屏幕保持常亮，断开后自动恢复原有屏幕超时 |
| 状态栏功耗/温度仪表 | 系统界面 | 接管状态栏「实时网速」的显示位置，显示电池功耗（W）、电流（mA）、CPU 温度、电池温度，最多 4 项；可单行或双行排布，字号 / 粗细 / 左右空隙可调 |
| 状态栏实时网速 | 系统界面 | 接管状态栏「实时网速」的显示位置，按自定义格式显示上下行速率；可隐藏 ↑↓ 符号与 `/s` 后缀。与仪表同时开启时网速占左列、仪表占右列 |


> ⚠️ 详细状态与 hook 点定位见 [`docs/HOOKS.md`](docs/HOOKS.md)。

---

## 构建

### 环境要求

- JDK **21**
- Android SDK（compileSdk 37 / minSdk 26 / targetSdk 37）
- Gradle **9.3.1**（或使用项目自带 wrapper）

### 命令

```bash
# 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Debug
./gradlew :app:assembleDebug

# Release（启用混淆与资源压缩）
./gradlew :app:assembleRelease

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

> 国内网络下建议给 Gradle 配代理，或依赖 `settings.gradle.kts` 中已配置的阿里云镜像。

---

## 安装与使用

1. 装好 **LSPosed**（需支持 Xposed API 102，LSPosed 1.9.3+）
2. `adb install app-debug.apk`
3. 在 LSPosed 管理器中**启用 SpaceHook 模块**
4. 勾选作用域（至少勾选你想 Hook 的应用；`system` 作用域对应 system_server 功能）
5. 重启目标应用（或重启系统界面；**system_server 相关 Hook 需重启设备**生效）
6. 打开 SpaceHook，首页应显示「**模块已激活**」并在「Hook 加载情况」中列出已注入进程

---

## 技术栈

| 组件 | 技术 |
|---|---|
| 语言 | Kotlin（`:app` JVM 21；`:common` / `:libhook` JVM 17） |
| UI | Jetpack Compose + **Miuix 0.9.4** |
| Hook 框架 | **libxposed API 102**（modern Xposed API） |
| 架构 | 多模块：`common`（纯数据） / `libhook`（Hook 实现） / `app`（UI） |
| 构建 | Gradle 9.3.1 + AGP 9.1.0 + Kotlin 2.4.20 |

---

## 文档

| 文件 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | **开发交接指南**（给 AI Agent 或新协作者的入口） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 架构设计、libxposed API 102 全部签名、Miuix API 速查 |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | 实时开发进度、已知问题、设计决策、下一步 |
| [`docs/HOOKS.md`](docs/HOOKS.md) | 每个 Hook 的 hook 点定位表与实现要点 |
| [`docs/ANALYSIS-SoundMan.md`](docs/ANALYSIS-SoundMan.md) | SoundMan 移植分析报告（hook 点定位、风险、移植方案） |

---

## 免责声明

本项目仅供**学习与研究**使用。使用本模块可能违反设备制造商的服务条款，请自行承担风险。
