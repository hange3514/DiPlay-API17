# DiPlay 0.44.61 有线 CarPlay 回铃音测试版

这次主要改善外拨电话等待接通时，车机扬声器上的回铃音间歇卡顿。

## 本次更新

- 外拨等待接通时，给通话和提示音增加短预缓冲。首次开始或播放队列确认耗尽时，目标积累约 48 毫秒音频，最长等待 60 毫秒。限制队列长度和音频年龄，尽量保留回铃之间原有的静音间隔。
- 在开始或恢复通话音频时核对比亚迪车机的 USB 音源状态。完成一次完整的恢复操作并回读确认后，减少重复发送音源、音量和解除静音命令。
- 按拨号、来电响铃、接通和结束阶段记录必要的诊断状态，便于区分接通前的回铃和接通后的语音。保留上一版的 USB 换机、ALSA 麦克风和媒体流播放路径。

## 安装与实车验证

[下载 APK](https://github.com/hange3514/DiPlay-API17/releases/download/v0.44.61-call-ringback-test/DiPlay_API17_0.44.61-call-ringback-test.apk)。已有相同签名的对应版本可覆盖安装。使用 USB 数据线连接 iPhone，按系统提示允许 DiPlay 访问 USB。若图库另弹访问提示，可取消图库的请求。投屏由应用内 lwIP 协议栈承载，无需系统 VPN 授权。

项目实车参考为 **2018 款比亚迪宋 DM**，车机为 Freescale MX9A0，固件 `V4.2.2.30.170830`，Android 4.2.2 / API 17，分辨率 1024 × 600。参考手机为 iPhone 15 Pro Max / iOS 26.2.1。

本版通过 532 项本地回归测试和 API 17 APK 签名校验。回铃优化效果待实车确认。建议停车外拨一次，听几段接通前的回铃，再检查接通后双方语音和挂断后音乐是否正常。也可用来电确认原有铃声。

## APK 与源码

- 包名 `local.headunit.diplay`，versionCode `196`，最低 Android API 17，ARMv7。
- `DiPlay_API17_0.44.61-call-ringback-test.apk`，1,365,184 字节。
- SHA-256 `43a7da2a9c157c611560a26de15abaa16cd69e53c0a71209306c29f276111e82`。
- [对应源码](https://github.com/hange3514/DiPlay-API17/tree/v0.44.61-call-ringback-test/legacy) · [构建说明](https://github.com/hange3514/DiPlay-API17/blob/v0.44.61-call-ringback-test/docs/BUILD.md) · [附件身份来源](https://github.com/hange3514/DiPlay-API17/blob/v0.44.61-call-ringback-test/docs/AUTH_PROVENANCE.md)。

项目基于 GPL-3.0 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)。兼容性报告由用户确认后提交。[反馈与隐私](https://github.com/hange3514/DiPlay-API17/blob/main/docs/COMPATIBILITY_TESTING.md) · [车型适配交流](https://github.com/hange3514/DiPlay-API17#适配交流)。
