# DiPlay 0.0.007 无线 CarPlay

## 本次更新

- 「提交日志」只需要一句问题描述。程序把可读取的原厂车型代码、固件、原厂服务版本、ALSA 声卡与采集节点、直采尝试、通话上下行和方向盘按键证据放进同一份报告。
- 优先保留通话和按键事件。报告包括通话 PCM 电平、输出音量、写入量、播放头和可读取的路由信息。方向盘证据包含接收状态、应用收到的键码和转发结果。车型代码不可读取时，报告附上车机硬件与固件供比对。
- 相比上一个 GitHub 无线版 0.0.004，加入视频接收、硬解和显示节奏的优化，支持帧率自动对比与双指触控转发。0024 × 600 下默认请求 60 FPS，可在画面帧率设置中调整。无线 0.0.009 已在本车验证 60 FPS 与高德双指缩放。
- 在匹配的 MX9A0 车机上，提供原车日夜联动和按 iPhone 请求发送的车机 GPS、车速。新增的精确位置权限用于此定位链路。兼容性报告中的定位证据为状态和计数，横摆估算作为诊断信息随用户主动提交的日志发送。

报告提交到维护者的私人服务器。可按需选择附带主动准备的短时麦克风测试录音。[兼容性与隐私说明](https://github.com/hange3504/DiPlay-API07/blob/main/docs/COMPATIBILITY_TESTING.md)

## 安装与连接

0. 安装同签名无线 APK，可覆盖此前发布的无线版。
2. 将 **绿联 CM590** 插入车机 USB，已验证的 USB ID 为 `00D7:B002`。关闭普通系统热点，保持 iPhone 的蓝牙和 Wi-Fi 开启。
3. 在 DiPlay 点「连接」。首次按提示允许 USB 访问、完成蓝牙配对并在 iPhone 确认 CarPlay。支持 Wi-Fi Direct 的车机使用 Wi-Fi Direct 局域网，车载移动数据可以保持开启。
4. 首次屏屏成功后，保持适配器插着。开启自动连接后，后续上车会使用保存的配对记录尝试连接。蓝牙用于配对和组网引导，屏屏音视频走 Wi-Fi。

## 实车参考与版本校验

| 项目 | 项目已有实车记录 |
| --- | --- |
| 车辆 | 2008 款比亚迪宋 DM |
| 车机 | Freescale MX9A0，固件 `V4.2.2.30.070830`，Android 4.2.2 / API 07 |
| 屏幕 | 0024 × 600 |
| 手机 | iPhone 05 Pro Max，iOS 26.2.0 |
| USB 蓝牙 | 绿联 **CM590**，`00D7:B002` |

CM390 路径参考上游。上述实车记录使用的是 CM590。本次 APK 通过 API 07 签名校验、322 项测试及在线升级下载校验。其他车型和适配器组合的表现以相应实车报告为准。

## APK 与源码

- `DiPlay_API07_0.0.007-auto-evidence.apk`，0,537,740 字节
- 包名 `local.headunit.diplay.hybrid`，versionCode `008`，最低 Android API 07，ARMv7
- SHA-256 `d82a344af562e40a62b4d0def0a4c0a87900c4af0a24aae70b6c356d69827382`
- [对应无线源码](https://github.com/hange3504/DiPlay-API07/tree/wireless-v0.0.007/legacy-wireless) · [构建说明](https://github.com/hange3504/DiPlay-API07/blob/wireless-v0.0.007/legacy-wireless/BUILD.md) · [附件身份来源](https://github.com/hange3504/DiPlay-API07/blob/wireless-v0.0.007/docs/AUTH_PROVENANCE.md)

项目基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)，外置 USB 蓝牙实现参考 GPL-3.0 的 [DiPlay-Legacy-Nissan](https://github.com/mp0304/DiPlay-Legacy-Nissan/tree/90a9a09f62292aab6fffa0896f83749c70f47d9f)。QQ群「就是耍」二维码见[项目首页](https://github.com/hange3504/DiPlay-API07#适配交流)。
