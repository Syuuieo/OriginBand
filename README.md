# OriginBand

## 📖 介绍

**OriginBand** 是一款专为 **OriginOS** 打造的穿戴设备原生桥接工具。

它能够将原本绑定在「小米运动健康」中的小米手环与手表，直接接入 **OriginOS 系统** 与 **vivo 健康（`com.vivo.health`）**：无需后台常驻小米运动健康，即可在手机本地完成蓝牙直连、状态卡片展示、健康数据回传，以及通知、来电、免打扰、音乐、闹钟和天气等系统级联动。

---

## ✨ 项目亮点

* **Material Design 3 界面：** 采用 Jetpack Compose Material 3 设计，支持 Edge-to-Edge 与明暗色青绿主题。
* **原生蓝牙接管：** 导入绑定密钥后直接由桥接服务接管连接，无需后台保留小米运动健康。
* **vivo 健康深度整合：** 在 vivo 健康「设备」页直接呈现设备连接状态、实时电量与管理入口，健康数据自动写入当前登录账号。
* **低开销长连接：** 轻量后台保活设计，支持在控制中心添加「手环」快捷磁贴一键连接或断开。

---

## 🛠️ 核心功能

1. **蓝牙会话接管：** 从本地读取加密绑定密钥，通过 SPP V2 / BLE 建立设备连接并同步实时电量与状态。
2. **健康数据回传：** 自动收集并回传步数、心率、静息/步行心率、血氧、压力、睡眠区间/分期及运动 GPS 轨迹至 vivo 健康，并在本地保存历史记录与日汇总核对。
3. **系统通知与来电：** 支持手机应用通知及图标转发，支持多卡来电状态提醒与手环端拒接/挂断联动。
4. **日常生态联动：** 支持手机与手环免打扰状态双向同步、系统时钟闹钟联动、音乐播放控制及「入睡后暂停音乐」、查找手机。
5. **天气与诊断工具：** 支持通过 vivo 健康天气快照向手环推送天气；内置设备详情、完整设备日志拉取解压、协议测试与独立连接诊断页面。

---

## 🚀 快速开始

### 环境要求

* 已获取 **Root** 权限（推荐使用 **KernelSU**）
* 已安装 **LSPosed**（支持 libxposed API 102）
* 已安装 **小米运动健康**（用于初次配对与读取绑定密钥）与 **vivo 健康**（已适配 `7.1.3.72`）

### 使用步骤

1. 安装 **OriginBand**（包名：`com.originband.link`）。
2. 在 **KernelSU** 中为 OriginBand 授予超级用户权限。
3. 在 **LSPosed** 中启用 OriginBand 模块并勾选作用域：
   * `com.vivo.health`
   * `com.mi.health`
   * `com.android.BBKClock`
   完成后重启一次 vivo 健康进程（或重启手机）。
4. 确认手环已在小米运动健康中完成配对，打开 OriginBand 首页按引导完成 **授权 Root → 导入绑定 → 添加到健康**，随后授予「附近设备」与「通知」权限即可使用。

---

## 🔧 从源码构建

使用 **JDK 17** 与 Android SDK（`compileSdk 37` / `minSdk 35`），在项目根目录执行：

```powershell
# 运行全部单元测试
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain

# 构建 Debug APK
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain

# 构建 Release APK
.\gradlew.bat :app:assembleRelease --no-daemon --console=plain
```

> Release 签名需配置环境变量：`ORIGINBAND_KEYSTORE`、`ORIGINBAND_STORE_PASSWORD`、`ORIGINBAND_KEY_ALIAS`、`ORIGINBAND_KEY_PASSWORD`。

---

## ❤️ 致谢

感谢以下优秀开源项目提供的核心基础与技术参考：

* [MiaM1ku/Miband-OPlusBridge](https://github.com/MiaM1ku/Miband-OPlusBridge)（上游原始桥接实现）
* [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)（小米穿戴协议 framing、会话加密与 Protobuf 结构参考）
* [libxposed/api](https://github.com/libxposed/api)（现代 Xposed API 102 模块接口）

详细信息与上游声明见 [NOTICE](NOTICE)。

本项目基于 **[GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)](LICENSE)** 开源。
