# DiPlay 0.1.127 无线 CarPlay Wi-Fi Direct 兼容性测试版

这次处理比亚迪宋 DM SAH / MX9 在蓝牙认证后停在第 9 步的问题。此前该车机因型号限制走普通车载热点，即使热点可以访问测试页，也可能等不到 iPhone 建立 AirPlay 会话。

## 本次更新

- 根据系统声明的 Wi-Fi Direct 能力和 Wi-Fi 服务自动选路。MX9、MX9A0 及其他满足条件的车机均可尝试由车机建立 Wi-Fi Direct 群组。
- 读取固件实际分配的群组接口、IPv4 地址和子网掩码，确认接口就绪后再通知 iPhone。现有蓝牙适配器、音频和多 iPhone 选机逻辑继续沿用。
- 新纳入的车机若建组失败，在确认群组已清理且系统没有其他共享冲突时可回到普通热点路径。进度提示与日志按实际使用的网络显示。

## 安装与验证

1. [下载 APK](https://github.com/hange3514/DiPlay-API17/releases/download/wireless-v0.1.127-wifi-direct-compat/DiPlay_API17_0.1.127-wifi-direct-compat.apk)，或在无线版里点「在线更新」。已有同签名无线版可覆盖安装。
2. 保持外置 USB 蓝牙适配器连接，打开 iPhone 的蓝牙和 Wi-Fi。如果车机系统热点已开，先按提示关闭它。点击「连接」，由车机建组并引导 iPhone 自动加入。
3. 确认能否从第 9 步进入投屏。如果仍停在第 9 步，请在车机点「提交日志」，描述是否看到 iPhone 自动加入车机网络。兼容性报告由用户确认后提交。[反馈与隐私](https://github.com/hange3514/DiPlay-API17/blob/main/docs/COMPATIBILITY_TESTING.md)

项目实车参考为 2018 款比亚迪宋 DM、Freescale MX9A0、固件 `V4.2.2.30.170830`、Android 4.2.2 / API 17、绿联 CM591 和 iPhone 15 Pro Max / iOS 26.2.1。报告第 9 步故障的 MX9 固件为 `V4.2.2.30.180428`。本版通过 356 项本地回归测试和 API 17 APK 签名校验。MX9 及其他新车机需实测群组创建、iPhone 自动入网和投屏。CM390 保留上游适配代码，尚未实车测试。

## 安装包与源码

- 包名 `local.headunit.diplay.hybrid`，versionCode `128`，最低 Android API 17，ARMv7。
- 安装包 `DiPlay_API17_0.1.127-wifi-direct-compat.apk`，1,558,221 字节，SHA-256 `6a822583c4948f5c4a67253aa3cd90cece8cace2332d3881acc3722f417b24a0`。
- [对应源码](https://github.com/hange3514/DiPlay-API17/tree/wireless-v0.1.127-wifi-direct-compat/legacy-wireless) · [构建说明](https://github.com/hange3514/DiPlay-API17/blob/wireless-v0.1.127-wifi-direct-compat/legacy-wireless/BUILD.md) · [附件身份来源](https://github.com/hange3514/DiPlay-API17/blob/wireless-v0.1.127-wifi-direct-compat/docs/AUTH_PROVENANCE.md)。

项目基于 GPL-3.0 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)。[适配交流](https://github.com/hange3514/DiPlay-API17#适配交流)。
