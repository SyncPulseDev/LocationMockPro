# 🛰️ LocationMockPro - 极客级 Android 定位伪装与高拟真动力学轨迹仿真系统

[![Android](https://img.shields.io/badge/Platform-Android%208.0%20~%2015%20(API%2026--35)-brightgreen.svg)](https://developer.android.com)
[![Architecture Rating](https://img.shields.io/badge/Architecture%20Review-S%2B%20(Production--Ready)-success.svg)](https://github.com/SyncPulseDev/LocationMockPro)
[![Version](https://img.shields.io/badge/Version-v1.3.2-blue.svg)](https://github.com/SyncPulseDev/LocationMockPro/releases)
[![LSPosed](https://img.shields.io/badge/Module-LSPosed%20%2F%20Xposed%20Supported-purple.svg)](https://github.com/LSPosed/LSPosed)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.0%2B-blue.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-purple.svg)](https://developer.android.com/jetpack/compose)
[![Release Package](https://img.shields.io/badge/Release%20APK-1.31%20MB%20(R8%20Optimized)-informational.svg)](https://github.com/SyncPulseDev/LocationMockPro/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

`LocationMockPro` 是一套面向移动安全研究、高精度自动化测试与地理位置算法验证的 **工业级 Android 深度仿真框架**。项目不仅提供全套高拟真传感器动力学算法与跨 Tab 零重载高德地图交互，更深度集成了原生 **LSPosed / Xposed 系统底层免杀 Hook 引擎**，具备全闭环的物理与无线电环境指纹伪装能力，正式获评 **S+ 级（Release 产线封版状态）** 工业级交付标准。

---

## 🌟 核心功能特性矩阵

### 1. 🛡️ LSPosed 系统级免杀 Hook 引擎 (`XposedEntry.kt`)
- **系统标志位彻底抹除**：底层拦截 `Location.isFromMockProvider()`、Android 12+ (API 31) 的 `Location.isMock()` 并永真返回 `false`；清除 `Location.getExtras()` 中 `mockLocation` / `isMock` 标记。
- **全星历卫星 Extras 深度补齐**：注入 Location 时自动附带 `satellites=16`、`maxSatellites=28`、`meanCn0=33.5f`、`numSatellitesInView=16` 等全套卫星状态参数，彻底杜绝大厂安全 SDK 依赖“0 卫星/空 Extras”判定作弊。
- **开发者选项与权限沙箱遮蔽**：拦截 `Settings.Secure.getString/getInt("mock_location")` 强制返回 `0`；Hook `AppOpsManager.checkOp(OP_MOCK_LOCATION, 58)` 返回 `MODE_IGNORED`，彻底瓦解银行与大厂 App 的权限交叉比对。
- **Android 11+ 包可见性 (Package Visibility) 终极穿透**：宿主侧 Hook `ApplicationPackageManager.canPackageQuery` 与 `resolveContentProvider` 动态补齐 `ProviderInfo`，即使宿主未配置 `<queries>` 标签亦可秒级穿透。
- **LSPosed Manager 原生适配**：清单配置 `xposedmodule`，并在 `arrays.xml` 预置包含运动世界校园、闪动校园、钉钉、企业微信、Keep、Pokémon GO、滴滴、美团等主流高频目标 App 的推荐勾选作用域。

### 2. ⚡ 纳秒时钟、动态精度与物理状态机自治
- **双时钟物理同步**：每次位置更新同步刷新 `location.time = System.currentTimeMillis()` 与纳秒级单调时钟 `location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()`，穿透 `FusedLocationProvider` 的时间戳交叉防作弊校验。
- **动态微幅精度抖动**：摒弃固定整数精度破绽，依据时间函数注入 `3.5m ~ 8.0m` 正弦连续自然精度波动，并装配方位角、垂直高度及速度精度因子。
- **GNSS 3D 定位快速冷启动**：Hook `LocationManager.registerGnssStatusCallback`，在 800ms 内瞬间触发 `onStarted()` 与 `onFirstFix(800)` 3D 卫星锁定，彻底杜绝目标应用因“获取不到 GNSS 卫星锁”判定定位无效。

### 3. 📡 全星座 NMEA-0183 卫星视场与无线电指纹
- **多星视场完整模拟**：生成标准 `$GPRMC`、`$GPGGA` 及 **`$GPGSV`** 多报文序列，真实计算各卫星仰角 (20°~75°)、方位角 (0°~360°) 与高保真载噪比（CNR 32~44 dB-Hz），流式输出至主界面终端。
- **基站 `CellInfoLte` 完备装配**：反射构造合规中国移动 LTE 基站信息，写入合法 MCC (`460`)、MNC (`00`)、TAC、CI 以及非空 `CellSignalStrengthLte`（`-75 ~ -84 dBm`，Level 4），根除国内主流定位 SDK（高德/百度/腾讯）因信号对象为空导致的 `NullPointerException` 崩溃。
- **企业 Wi-Fi 扫描与连接指纹注入**：拦截 `WifiManager.getConnectionInfo()` 与 `getScanResults()`，按需注入目标企业打卡路由器的 SSID、BSSID 及 `-52 dBm` 拟真 RSSI。

### 4. 🏃 模拟器主动计步器仿真与开机基准增量叠加
- **无硬件计步设备/模拟器主动事件生成 (Active Sensor Dispatcher)**：
  - 拦截 `SensorManager.getDefaultSensor`，在**安卓模拟器（如 MuMu）**或无计步硬件设备上伪装注入标准的 **Bosch 硬件计步传感器**；
  - 维持 `activeStepListeners` 调度队列，在后台由独立调度线程按照真实跑步速度与步频主动合成合规的 `SensorEvent` 投递给宿主应用，彻底攻克模拟器“有位移但步数为零”被判定为骑车/作弊的风控痛点！
- **真机开机物理基数锚定**：真实设备上首次回调捕获底层硬件步数 `baseHardwareSteps`（无硬件回调时以 `12580f` 垫底），向目标 App 注入 `baseHardwareSteps + simulatedSteps` 的连续增量，符合 Android 物理步数单调递增契约。
- **生理学步频阻尼**：速度与步频实时联动，拐角与红绿灯驻留时自动平滑衰减，步频传感器与 GPS 速度完全同步。

### 5. 🏟️ 400m 标准田径场跑道与精细校准对齐引擎
- **IAAF 国际田联标准跑道数学几何重构**：严格按照国际田径联合会标准跑道参数构建（标准直道长 84.39m，第 1 道内突沿 36.50m / 计算跑线半径 36.80m，闭环周长精确为 400.0 米），彻底消除传统近似算法导致的椭圆畸变与位置偏移。
- **1~8 分道道次与顺/逆时针自适应**：支持 1~8 跑道半径自适应扩展（道宽 1.22m 增量计算），默认符合田联规范左转逆时针奔跑，亦可一键反转为顺时针。
- **跑道长轴航向旋转与四向米级对齐罗盘**：独家研发可视化跑道校准器（`Track400mDialog`），支持 0~360° 航向角平滑滑块微调，结合上/下/左/右四向米级步进罗盘（1m/5m/10m/20m），在卫星地图底图上实现像素级精准贴合。
- **地图视野中心感知与车标火星坐标纠偏**：地图视野移动时实时同步视野中心点，未选点时智能锚定当前屏幕中心；路线模拟巡航时自动对车标位置进行 GCJ-02 动态纠偏，保证车标与高德/卫星航线绝对对齐贴合。
- **道路吸附 WGS-84 / GCJ-02 双向纠偏引擎**：OpenStreetMap OSRM 拓扑引擎在请求前先将 GCJ-02 转为 WGS-84，吸附返回后再转回 GCJ-02，彻底消除两点吸附在地图上的 500m~1km 剧烈偏移问题。

### 6. 🛡️ 前台服务强保活与三级低延迟 IPC 容错防崩
- **前台服务强生命周期守护**：显式结合 `startForegroundService` 与 `bindService`，彻底解决切出后台/锁屏时 Activity 销毁导致模拟定位服务被系统回收的致命缺陷。
- **`WeakReference` 闭包解耦双保险**：`ListenerDispatcher` 采用弱引用持有宿主 `LocationListener`，在派发 Runnable 内部**二次动态解引** `weakRef.get()`，结合 `pruneStaleDispatchers()` 存活性清扫，根除长尾劣质 App 忘记注销引发的 Activity 内存泄漏。
- **三级无阻塞容错 IPC 通道与 HMA/SELinux 终极穿透**：
  - **Tier 1（Loopback UDP 微内核）**：绑定本机回环端口 `127.0.0.1:39527`（备用 `39528`）构建亚毫秒级二进制协议同步通道，后台守护线程轮询维护 `@Volatile cachedState` 无锁内存快照，`getSimState()` 实现 `O(1)` 零阻塞读取，彻底绕过 Android 15 SELinux 限制与 Hide My Applist (HMA) 的 ContentProvider 隐藏拦截；
  - **Tier 2（New `XSharedPreferences`）**：清单开启 `xposedsharedprefs` 并配合 `MODE_WORLD_READABLE` 自动持久化降级策略，即使后台保活服务未唤醒仍可离线读取最新坐标与无线电指纹；
  - **Tier 3（`ContentProvider` 熔断降级兜底）**：保留 `ContentResolver.call` / `MatrixCursor` 双模通道并内置 25 秒失败熔断断路器，杜绝 HMA 屏蔽环境下 `Failed to find provider info` 日志刷屏与 5 秒 binder 超时卡顿。

### 7. 🔄 智能在线自动更新与 Root 静默热升级 (`UpdateManager.kt`)
- **双通道云端版本检测**：基于 GitHub Releases API 与轻量 `update.json` 双协议检测，冷启动智能轻量探测，严格遵循 SemVer 语义化版本规范。
- **国内反代镜像极速下载 (gh-proxy)**：内置国内 CDN 镜像下载加速开关，具备镜像与官方源自动故障切换 (Failover) 与流式断点汇报。
- **安装包完整性校验与 Root 极速静默秒升**：执行安装前强制校验 APK 归档合法性与包名一致性，若设备具备 Root（KernelSU / Magisk / su），采用 `pm install -r` 实施无感热更新；非 Root 环境无缝降级调起系统 `PackageInstaller`。
- **现代暗黑仪表盘弹窗**：实时呈现发布日志、版本号、下载进度百分比、已下载/总计 MB，支持后台下载与异常重试。

---

## 🛠️ 项目工程结构

```text
LocationMockPro/
├── app/
│   ├── build.gradle.kts                         # 产线级 R8 代码混淆与资源收缩编译管线
│   ├── proguard-rules.pro                       # Xposed、Provider、WebView Bridge 精准保留规则
│   └── src/main/
│       ├── AndroidManifest.xml                  # queries 包可见性、多进程 Provider 声明
│       ├── assets/xposed_init                   # LSPosed 模块入口定义 (com.antigravity.geospoofer.hook.XposedEntry)
│       ├── res/
│       │   ├── values/arrays.xml                # LSPosed 建议作用域预置列表 (14 款核心与校园 App)
│       │   └── xml/file_paths.xml               # GPX 安全分享路径配置
│       └── java/com/antigravity/geospoofer/
│           ├── MainActivity.kt                  # Jetpack Compose M3 核心控制台 (跨 Tab 共享单实例 WebView)
│           ├── hook/
│           │   └── XposedEntry.kt               # LSPosed 终极免杀、主动计步仿真与物理状态机自治
│           ├── provider/
│           │   └── LocationStateProvider.kt     # 跨进程毫秒级 IPC 双模状态广播与异步节流落盘 Provider
│           ├── core/
│           │   ├── MockLocationEngine.kt        # 底层双 Provider 注入与 NMEA GSV/GGA 发生器
│           │   ├── RouteSimulator.kt            # 动力学步态、拐角减速、折返与目标守护
│           │   ├── CoordinateUtils.kt           # WGS-84 / GCJ-02 / BD-09 互转与标准400m操场生成
│           │   ├── RoadSnapHelper.kt            # 双向纠偏 OSRM 道路吸附规划器
│           │   ├── AntiDetectionAuditHelper.kt  # 六维防检测体检中心
│           │   ├── GpxExportHelper.kt           # 标准 GPX 1.1 (WGS-84 + 三频段微地形) 轨迹导出与分享
│           │   └── RouteStorageHelper.kt        # 路线持久化、GPX/KML 自动纠偏导入与收藏夹
│           ├── service/
│           │   └── MockLocationService.kt       # 前台常驻保活与防休眠定位服务
│           └── ui/
│               ├── Components.kt                # 极客暗黑风玻璃卡片、呼吸状态胶囊、遥测指标磁贴
│               ├── SingleMockTab.kt             # 单点定位控制中心 (地图选点、快速城市、打卡收藏)
│               ├── RouteMockTab.kt              # 路线巡航控制台 (4-Grid 航空级遥测HUD、折叠长路点与预设管理)
│               ├── EngineDefenseTab.kt          # 极客引擎与安全防护中心 (Root/LSPosed、4Hz防跳、高斯抖动、Wi-Fi基站伪装)
│               ├── Dialogs.kt                   # 统一暗黑弹窗系统 (体检报告、基站伪装、1~8全道次操场校准等)
│               ├── MapWebView.kt                # 响应式交互式路线标点地图
│               └── theme/                       # 极客座舱暗黑主题 (Dark Precision Design System)
```

---

## 🚀 编译与产线构建指南

### 1. 环境要求
- **JDK**：OpenJDK 17 或 21
- **Android SDK**：API 35 (Android 15) / Min SDK 26 (Android 8.0)
- **编译工具**：Gradle 8.7+

### 2. 构建产物说明
| 构建模式 | 执行命令 | 产物路径 | 体积 | 特性说明 |
| :--- | :--- | :--- | :---: | :--- |
| **Debug** | `./gradlew assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | 15.4 MB | 包含完整调试符号，开发联调首选 |
| **Release** | `./gradlew assembleRelease` | `app/build/outputs/apk/release/app-release.apk` | **1.31 MB** | **R8 生产级混淆**、压缩率超 90%、去除冗余依赖、入口类安全留存 |

---

## 📝 更新日志 (Changelog)

### v1.3.2 (Build 17) - 2026-11-08
- **🚀 Loopback UDP 微内核与无锁 IPC 重构（100% 穿透 Android 15 & HMA）**：
  - 在 `LocationStateProvider` 内建仅限本机回环的 `127.0.0.1:39527`（备选 `39528`）UDP 二进制微桥接服务端（`<0.2ms` RAM 内存直传），`XposedEntry` 端由后台 `StateSyncDaemon` 守护线程维持 `@Volatile cachedState` `O(1)` 无锁缓存，`getSimState()` 彻底免除主线程 Binder/I/O 阻塞；
  - 开启标准 `xposedsharedprefs` 声明与 8 秒状态新鲜度校验，并为 `ContentProvider` 通道配置 25 秒失败熔断器，彻底消除运动世界 / Keep 在 HMA 隔离下出现的 `Failed to find provider info` 日志刷屏。
- **🏃 App 感知型步态仿真与跨会话计步单调锁定**：
  - 引入持续角相位积分算法（消除变频变速时 `2π·f(t)·t` 造成的相位倒流跳变），配合 **50Hz 独立加速度计保底泵**，在 Keep 等仅依赖加速度计 DSP 算法且模拟器物理硬件静默的环境中输出完整 3 轴步态波（实测 5 km/h 巡航稳态输出 **平均步频 116 SPM / 平均步幅 0.71 米**）；
  - 对同时监听 `TYPE_STEP_COUNTER` 的校园跑多传感器应用智能仲裁为亚阈值手持微颤（`±0.05 m/s²`，方差 > 0 且不产生额外伪峰步数），严防三源叠加导致步频翻倍；
  - 引入全局单调基线锁 `computeMonotonicStepCounterValue()`，确保多次启停路线巡航时 `TYPE_STEP_COUNTER` 累计步数严格单调递增不倒退。
- **🛰️ GNSS 卫星状态与生命周期全闭环**：
  - `GnssCallbackInfo` 严格遵守宿主注册的 `Handler` / `Executor` 线程亲和派发，主动拦截并抑制低星数（`<10` 颗）真实系统回调，稳定合成 17 颗全绿卫星（12 GPS + 5 北斗）3D 锁定状态（Keep / 运动世界校园稳态显示 **绿色 3 格 `GPS |||`**）；
  - 修复 `MockLocationService.stopRoute()` 遗留后台心跳线程问题，与 `RouteSimulator.pauseSimulation()` 及 `startMock()` 统一联动归零速度与步频。
- **🛡️ 反扫描定向隐身、WebView 崩溃自愈与新图标升级（整合 v1.3.1 演进）**：
  - 扩充 `hookPackageVisibility`：新增 `getPackageInfo` / `getApplicationInfo` 定向探测抛出 `NameNotFoundException` 隐身拦截与 `queryIntentActivities` / `queryIntentServices` 过滤；
  - `MapWebView` 增加 `onRenderProcessGone` 渲染进程崩溃安全回收与自愈重连；
  - 全套升级蓝青极光渐变指南针自适应启动图标（含 `mdpi ~ xxxhdpi` 全密度切图）。

### v1.2.9 (Build 14) - 2026-11-06
- **✨ 现代流光玻璃拟态设计体系重构 (Luminous Midnight Glassmorphism)**：
  - **星夜极光光影画布**：底色升级为深邃星夜渐变（`#070B14` → `#050811`），搭载随模拟/巡航状态动态呼吸切换的环境极光散射光晕（Ambient Aurora Nebula）；
  - **斜向高光折射毛玻璃卡片 (`GlassCard`)**：配备顶部流光反射细线（Specular Shimmer Line）、斜向高光描边与多维语义主题色环境阴影；
  - **宝石微光磁贴与极光导航坞**：重构极光渐变分段导航栏、连续呼吸脉冲状态胶囊与 5 色独立宝石微光轨迹工具箱磁贴。
- **🚀 地图下方「悬浮流光速控舱」与交互提效**：
  - 在「单点定位」与「路线巡航」地图卡片正下方新增流光速控舱，实时呈现当前锁止坐标或航线总里程（km），无需滚到底部即可一键启停单点模拟与路线巡航；
  - 高德地图浮层控件、搜索框与提示条同步升级为深海毛玻璃与极光高亮风格，支持长轨迹中间密集路点智能折叠。
- **🧹 架构精简与性能调优**：
  - 移除冗余悬浮摇杆服务，R8 混淆压缩后 Release 安装包体积进一步精简至 **1.22 MB**。

### v1.2.8 (Build 13) - 2026-11-05
- **🛡️ 阻断伪传感器内核句柄下穿（Handle 0 冲突彻底根除）**：
  - **内核句柄隔离**：修复反射构造的 `TYPE_STEP_COUNTER` 与 `TYPE_STEP_DETECTOR` 伪传感器未设置 `mHandle`（默认 0），导致 Android 底层 `SystemSensorManager` 误将其作为硬件 3-axis Accelerometer（handle 0）直接绑入物理加速度计底层数据流的严重缺陷；
  - **分配虚拟负句柄**：为伪传感器显式赋予虚拟独立负句柄（`-101` / `-102`），并在 Java 层拦截下传内核，彻底杜绝多传感器事件绑定混淆。
- **🏃 多传感器计步融合防翻倍（独占单源仲裁体系）**：
  - **独占单源仲裁**：针对运动世界校园等同时注册 `TYPE_STEP_COUNTER`、`TYPE_STEP_DETECTOR` 与 `TYPE_ACCELEROMETER` 的应用，建立单源独占机制；
  - **消除叠加暴增**：当权威 `TYPE_STEP_COUNTER` 激活时，自动抑制 `TYPE_STEP_DETECTOR` 脉冲分发，并令加速度计保持平稳自然重力基准 `(0, 9.80665, 0)`，彻底消除 App 内部三源叠加引发的 471/428 SPM 鬼畜步频与 20cm 碎步异常；
  - **实测指标稳定收敛**：在 MuMu 模拟器实测步频精准稳定在 **108 ~ 115 SPM**，步幅自然锁定在 **74.7 厘米**，平均配速与步频图表呈现平滑自然的真实曲线。
- **⚡ 内存与分发路径深度优化**：
  - 去除高频冗余诊断日志，全面精简分发路径，提升运行稳定性与抗风控表现。

### v1.2.7 (Build 12) - 2026-11-05
- **🏃 彻底根治 428/231 鬼畜步频与步幅异常（跨步频率精准锁定）**：
  - **跨步与单步生理学相位对齐**：基于人体生物力学与 DSP 峰值滤波算法，单次完整步态周期（Stride）包含左脚与右脚两次单步触地（2 Steps）。Keep 等运动 App 的加速度计峰谷与过零检测算法对单轴正弦波每个半波均判定为一次触地计步（1 周期 = 2 步）。将加速度计振荡频率严格锁定为跨步频率（Stride Frequency = Cadence / 120.0 Hz），彻底根治误用单步频率导致的 231 SPM 翻倍异常！
  - **三轴同相微幅重构消除正交谐波**：Z 轴前后俯仰与 X 轴迈步侧摆彻底摒弃 90 度正交余弦波，消除三维空间向量导数峰值倍增诱发的 428 SPM 异常，静止/原地驻留自动平稳对齐自然重力 (0, 9.80665, 0)；
  - **硬件计步基准单调性永久锁止**：开机物理硬件步数基准值（baseHardwareSteps）首次锁定后禁止运行中突变，彻底杜绝自循环跳变引发的 533 SPM 风控极值与非单调回滚；
  - **真实微动物理层绝对阻断**：在位移模拟激活期间，全面阻断真实硬件物理微动 STEP_DETECTOR 回调，杜绝手持晃动与虚拟步数混淆串扰。

### v1.2.6 (Build 11) - 2026-11-05
- **🏃 彻底消除双源计步冲突与步频翻倍异常 (Single-source Step Arbiter)**：
  - **主次传感器互斥机制**：针对 Keep / 悦跑圈同时监听 `TYPE_STEP_COUNTER` 与 `TYPE_STEP_DETECTOR` 导致的双重累加冲突，引入主次互斥调度器。在检测到 `TYPE_STEP_COUNTER` 活跃时，调度线程严格抑制次级 `TYPE_STEP_DETECTOR` 模拟脉冲，由权威硬件计步器独占派发，彻底根治 Keep 步频飙升至 231 SPM（实测翻倍）及步幅异常减半至 35cm 的生理学异常；
  - **物理硬件真实微动阻断**：在位移模拟激活期间，若 `TYPE_STEP_COUNTER` 已接管步数，底层拦截物理硬件产生的微小真实 `STEP_DETECTOR` 回调，杜绝手持晃动导致的软硬件脉冲混淆累积；
  - **SensorType 命名空间哈希隔离**：解决宿主同一 Listener 实例先后注册两类计步传感器时的 Map Key 覆盖与注销误伤隐患。
- **🧬 人体运动学重力加速度平滑动态自适应**：
  - 垂直冲击波幅摒弃固定 3.2m/s²，重构为随航速非线性自适应拟合：慢步走（1.35m/s）垂直振荡收敛至自然微幅 0.9m/s²，跑步自适应扩展至 2.4m/s²；左右迈步侧摆精准锁定双步跨步半频，Z 轴自适应俯仰。
- **🎯 慢走生理学步频下限扩展**：
  - 扩展低速健走步频自然区间至 80~205 SPM，在 12'22" 慢步走下输出 114 SPM 与 71cm 真实成人健走步态。

### v1.2.5 (Build 10) - 2026-11-05
- **🏃 运动健康步态与加速度计三轴动力学重构 (`Sensor.TYPE_ACCELEROMETER`)**：
  - 针对 Keep/悦跑圈等运动 App 采用加速度计双源滤波测步频算法的特性，深度 Hook `Sensor.TYPE_ACCELEROMETER`；
  - 依据实时速度与步频注入奔跑垂直冲击波（`9.8 ± 3.2 m/s²`）与左右交替迈步侧摆加速度，彻底根治手机静止时 Keep 步频飙升至 471 SPM、步幅异常萎缩至 40cm 以及图表断续空白等致命破绽，步频平滑收敛在 165~180 SPM 黄金自然区间，步幅恢复为 105~120cm 标准成人跑姿。
- **🏔️ 地理自然微地形海拔模型重塑**：
  - 彻底废除 350m 周期振幅达 35m 的机械“过山车正弦波”；
  - 重塑为宏观地表坡道（波长 1800m，起伏 2.2m）+ 中观地形（波长 650m，起伏 0.7m）+ 微地表纹理（波长 160m，起伏 0.2m）的三频段平缓地理模型，2 公里长跑累计爬升收敛至 6~8m 逼真微坡。
- **⚡ Gauss-Markov 一阶自回归平滑微抖动**：
  - 摒弃离散帧间独立的白噪声高斯扰动，引入时间常数约 2 秒的一阶自回归（Ornstein-Uhlenbeck）平滑微漂移；
  - 相邻 350ms 帧间位移扰动限制在 0.03~0.05m，彻底消除因瞬时离散采样跳跃导致的 **2'16" 尖锐超速下刺毛刺**。
- **🔄 步态会话重置与离散事件均匀流式分发**：
  - 启动/停止巡航模拟时自动调用 `resetSimulationSession()` 清除步数与配速历史残留，杜绝跨会话跳步；
  - 针对 `TYPE_STEP_DETECTOR` 依据实际配速增量动态均匀派发，支持防自身合成数据二次污染捕获机制。

### v1.2.4 (Build 9) - 2026-11-05
- **📡 全星座 NMEA-0183 原始卫星流调度器**：
  - 全面 Hook `LocationManager.addNmeaListener` 与 `removeNmeaListener`（覆盖 legacy `GpsStatus.NmeaListener` 与 API 24+ `OnNmeaMessageListener` 及任意 Handler / Executor 上下文）；
  - 调度线程持续向目标应用与风控 SDK 投递标准合规的 `$GPRMC` 与 `$GPGGA` 报文流，彻底破解钉钉、Keep、高德等大厂防作弊 SDK 对 NMEA 报文流缺失的风控检测。
- **🏃 人体生物力学步态动力学模型 (`calculateHumanGait`)**：
  - 彻底摒弃传统固定步长（0.72m）方案，基于真实人体运动生理学构建配速-步长-步频非线性动力学模型；
  - 步频严格自适应收敛在 95~202 SPM 生理学区间，彻底杜绝高速跑步/骑行时步频飙升至 300+ SPM 违背人体极限引发的反作弊封号。
- **⚙️ 定位提供者状态与多源定位补齐**：
  - 底层 Hook `LocationManager.isProviderEnabled`、`getProviders`、`getAllProviders` 与 `getBestProvider`；
  - 在模拟激活期间确保 `gps`、`network`、`fused` 恒定可用，解决模拟器或系统 GPS 未开启时目标应用弹出开启提示或降级检测。
- **🧩 模拟器硬件特性无感补全**：
  - Hook `ApplicationPackageManager.hasSystemFeature`，强制对步数传感器特性（`stepcounter` / `stepdetector`）放行；
  - Hook `SensorManager.getSensorList`，在无传感器真机或 MuMu/夜神等模拟器中自动注入 Bosch 高精度虚拟计步传感器。
- **📐 IAAF 国际田联 400m 跑道几何间隙修复与 GPX/KML 解析拓宽**：
  - 修复离散化曲线采样端点漏步导致弧线与直道交界处的 18 度空间破隙，并为环线算法引入 3 点环形平滑闭包；
  - GPX 解析器拓展支持 `<rtept>` 航线点，并新增 Google Earth / KML `<coordinates>lon,lat,alt ...</coordinates>` 格式导入兼容。
- **🧹 内存生命周期深度优化与 Android 14+ MSL 海拔支持**：
  - 在 Leaflet `AndroidView` 引入 `onRelease` 生命周期回调，彻底销毁底层 Chromium 原生 WebView，消除长期巡航下的隐性内存泄漏；
  - 引入 Android 14+ (API 34) `mslAltitudeMeters` 与 `mslAltitudeAccuracyMeters` 大地水准面海拔装配。

### v1.2.3 (Build 8) - 2026-11-04
- **🛡️ LSPosed 终极免杀加固**：全面补齐 Android 11+ (API 30+) `getCurrentLocation` 单次精准定位拦截与现代 `AppOpsManager` 字符串鉴权体系 (`OPSTR_MOCK_LOCATION`)。
- **⚡ 稳定性全面提升**：重构 Root 执行管道彻底消除死锁隐患，切换异步协程彻底杜绝 UI 线程阻塞与 ANR。
- **🏃 动力学仿真守恒**：引入步长余量结转机制，攻克操场跑道弯道累积减速缺陷，闭环循环自动消除空间瞬移。
- **🛡️ Android 14+ 前台服务生命周期契约加固**，消除权限受限崩溃陷阱。
- **🗺️ 地图交互优化**：切 Tab 自动毫秒级还原单点标记与规划航线，优化更新检查接口响应。

### v1.2.1 (Build 6) - 2026-11-04
- **🏟️ 400m 标准操场跑道算法纠偏与精细校准引擎**：
  - **IAAF 田联标准数学几何重构**：依据国际田联真实标准重新建模（直道 84.39m，第 1 道周长精准 400.0m），修复航向角旋转算法正负逆转导致的位置偏移缺陷；
  - **跑道全方位校准弹窗 (`Track400mDialog`)**：支持 0°~360° 航向角旋转滑块、四向米级微调罗盘（1m/5m/10m/20m）、1~8 道次切换、顺/逆时针方向与目标圈数总里程动态计算；
  - **地图视图中心感知与底图实时联动**：未选点时自动感知地图当前视野中心生成跑道，调整时在卫星底图上实时呈现预览；
  - **模拟运行车标 GCJ-02 坐标贴合纠偏**：解决火星坐标系下模拟运行中车标与高德图层及轨迹线轻微错位的问题。

### v1.2.0 (Build 5) - 2026-11-03
- **🎨 交互设计重构：极客座舱暗黑视觉体系 (Dark Precision Design System)**：
  - **三维功能分区解耦**：彻底告别以往单页十几个卡片堆叠滚动的杂乱布局，采用现代化分段导航栏划分三大独立主视图：
    - `📍 单点定位`：专一聚焦地图选点、坐标微调校准、常用打卡收藏与秒级瞬移；
    - `🛣️ 路线巡航`：静态规划（操场/环线/吸附/路点）与动态巡航彻底解耦；
    - `🛡️ 引擎安全`：整合 Root 提权、后台保活、4Hz 高频防跳、动力学物理仿真、Wi-Fi/基站伪装与 NMEA 终端；
  - **🚀 航空级 4-Grid 实时运动遥测 HUD**：巡航时动态呼出实时时速、累计里程、计步步频、仿真海拔爬升 4 大高斯毛玻璃指标磁贴；
  - **⚡ 动态呼吸状态胶囊 (Status Pill)**：顶栏实时感知【待命中 / 单点模拟中 / 路线巡航中 / 挂起暂停】状态并呈现动态微光呼吸灯；
  - **💎 统一材质与配色系统**：去除所有杂乱刺眼的混杂色块，全套重构为深邃冷灰底色（`#0B0F17`）、精密卡片表面（`#131B29`）与极客电光蓝/青色（`#38BDF8` / `#00E5FF`）高亮色系；
  - **📱 模块化架构**：将原 2600+ 行庞大巨石类彻底拆解为 `SingleMockTab`、`RouteMockTab`、`EngineDefenseTab` 与 `Dialogs` 模块，大幅提升流畅度与渲染帧率。

### v1.1.2 (Build 4) - 2026-11-02
- **🛰️ 高精度 GNSS 3D 卫星合成引擎 (Synthetic GnssStatus)**：
  - 彻底解决高德/百度地图定位 SDK 在虚拟机或室内因无法收到卫星回调报 `#1401: no enough satellites` 判定为“GPS信号弱”的痛点；
  - 采用 `GnssStatus.Builder` 合成 16 颗全绿卫星（12 颗 GPS + 4 颗北斗，全部标记 `usedInFix=true`，平均信噪比 36 dB-Hz），以 1Hz 频率向高德 SDK 主动推流；
  - 拦截 `addGpsStatusListener` 与 `getGpsStatus`，全覆盖老旧地图 SDK 版本；
  - 原生模拟位置模式下收敛精度至 `2.8m ~ 4.6m` 黄金区间（符合高德优良信号 $\le 5.0\text{m}$ 判定标准），并补全 Extras 卫星字段；
  - LSPosed 白名单补全【运动世界校园】官方正式主包名 `com.zjwh.android_wh_physicalfitness`。

### v1.1.1 (Build 3) - 2026-11-02
- **⚡ 架构加固**：
  - 修复 `MockLocationService` 后台保活缺陷，显式结合 `startForegroundService` 与 `bindService`，切出后台不被系统杀死；
  - 重构悬浮窗摇杆触控监听体系，彻底根治相对坐标正反馈引发的手指抖动跳跃；
  - 调优 LSPosed 跨进程读取缓存至 `250ms`，消除 1Hz 阶梯状移动卡顿。
- **🛡️ 模拟器计步与防风控增强**：
  - 新增模拟器与无计步芯片设备专属的**主动虚拟计步事件分发器（Active Sensor Dispatcher）**，解决 MuMu 等模拟器中“零步数”作弊判定；
  - 补齐 Location 注入实例中的卫星星历 `extras` 数据包（`satellites=16`、`numSatellitesInView=16` 等）；
  - LSPosed 作用域白名单扩充校园跑主流应用（运动世界校园、闪动校园）。
- **🛣️ 算法与交互**：
  - 新增 `400m` 标准田径场操场跑道一键生成算法，完美闭环；
  - 彻底解决道路吸附中 `GCJ-02 <-> WGS-84` 混淆造成的 500m~1km 航向漂移。

---

## ⚖️ 免责声明 (Disclaimer)

本项目仅用于 Android 定位相关技术研究、移动端应用在弱信号及多样环境下的功能自动化测试。请勿将本项目用于任何违反法律法规或第三方服务使用协议之用途。使用者需自行承担因违规使用产生的一切风险与法律后果。
