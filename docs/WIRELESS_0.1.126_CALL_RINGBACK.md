# DiPlay 0.1.126 无线 CarPlay 回铃音测试版

这次主要改善外拨电话等待接通时，车机扬声器上的回铃音间歇卡顿。

## 本次更新

- 无线通话和提示音播放使用约 48 毫秒的启动预缓冲，最长等待 55 毫秒。外拨等待接通和提示音长时间静音后可以重新预缓冲，减轻下一段回铃开头被拆碎的情况。
- 接通后的连续语音继续沿用现有无线音频队列。仅在需要时恢复车机音源，回读已确认 USB 音源后减少重复控制操作。
- 按拨号、来电响铃、接通和结束阶段记录必要的诊断状态。沿用上一版的多 iPhone 选机逻辑及媒体、导航音频设置。

## 安装与实车验证

[下载 APK](https://github.com/hange3514/DiPlay-API17/releases/download/wireless-v0.1.126-call-ringback-test/DiPlay_API17_0.1.126-call-ringback-test.apk)。已有相同签名的对应版本可覆盖安装。插入外置 USB 蓝牙适配器，首次使用按提示完成蓝牙配对和 CarPlay 授权。使用车机 Wi-Fi Direct 传输音视频，无需系统 VPN 授权。无线实车参考使用绿联 CM591（`10D7:B012`）。CM390 保留参考上游的适配代码，未做实车验证。

项目实车参考为 **2018 款比亚迪宋 DM**，车机为 Freescale MX9A0，固件 `V4.2.2.30.170830`，Android 4.2.2 / API 17，分辨率 1024 × 600。参考手机为 iPhone 15 Pro Max / iOS 26.2.1。

本版通过 353 项本地 JVM 回归测试和 API 17 APK 签名校验。回铃优化效果待实车确认。建议停车外拨一次，听几段接通前的回铃，再检查接通后双方语音和挂断后音乐是否正常。也可用来电确认原有铃声。

## APK 与源码

- 包名 `local.headunit.diplay.hybrid`，versionCode `127`，最低 Android API 17，ARMv7。
- `DiPlay_API17_0.1.126-call-ringback-test.apk`，1,554,125 字节。
- SHA-256 `acee7d325474517a651f19423389c1aee53eb5563fbab500caf4890e12286059`。
- [对应源码](https://github.com/hange3514/DiPlay-API17/tree/wireless-v0.1.126-call-ringback-test/legacy-wireless) · [构建说明](https://github.com/hange3514/DiPlay-API17/blob/wireless-v0.1.126-call-ringback-test/legacy-wireless/BUILD.md) · [附件身份来源](https://github.com/hange3514/DiPlay-API17/blob/wireless-v0.1.126-call-ringback-test/docs/AUTH_PROVENANCE.md)。

项目基于 GPL-3.0 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)。兼容性报告由用户确认后提交。[反馈与隐私](https://github.com/hange3514/DiPlay-API17/blob/main/docs/COMPATIBILITY_TESTING.md) · [车型适配交流](https://github.com/hange3514/DiPlay-API17#适配交流)。
