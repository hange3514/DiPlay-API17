# DiPlay API 17 兼容性移植

本仓库是基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 的 Android API 17 有线和无线 CarPlay 移植，项目代码遵循 GPL-3.0。公开源码和版本 APK 发布在 GitHub Releases。

## 下载 DiPlay

| 版本 | 下载 | 连接与安装 |
| --- | --- | --- |
| **有线 CarPlay 0.44.50** | [**下载有线 APK**](https://github.com/hange3514/DiPlay-API17/releases/download/v0.44.50/DiPlay_API17_0.44.50-wheel-media-confirm-beta.apk) | USB 数据线连接 iPhone。[发布说明、安装与校验](https://github.com/hange3514/DiPlay-API17/releases/tag/v0.44.50) |
| **无线 CarPlay 0.1.98** | [**下载无线 APK**](https://github.com/hange3514/DiPlay-API17/releases/download/wireless-v0.1.98/DiPlay_API17_0.1.98-wireless-hotspot-order.apk) | 绿联 CM591（`10D7:B012`）配对，iPhone 加入车机热点。[发布说明、配对与校验](https://github.com/hange3514/DiPlay-API17/releases/tag/wireless-v0.1.98) |

## 适配交流

**QQ群「就是耍」 · `1078069411`**。用 QQ 扫下方二维码，或搜索群号加入。

<a href="docs/images/qq-group-1078069411.jpg"><img src="docs/images/qq-group-1078069411.jpg" width="230" alt="QQ群「就是耍」1078069411 的加群二维码"></a>

有线和无线版目前都只在我自己的 **2018 款比亚迪宋 DM** 上做过实车验证。车机为 Freescale MX9A0、Android 4.2.2 / API 17、1024 × 600，手机为 iPhone 15 Pro Max / iOS 26.2.1。无线版的 CM390 代码参考上游项目，尚未实车测试。

欢迎其他车型用户在群里分享使用结果。反馈问题时可附上车型和年款、车机型号和 Android 版本、iPhone 和 iOS 版本、DiPlay 版本、连接方式及复现步骤。无线连接可以注明 USB 蓝牙适配器型号。我有时间会根据各车型的反馈排查和优化。

## 更正与致歉

我此前把自己的车辆年款误写为 2017 款。经重新确认，实际测试车辆是 **2018 款比亚迪宋 DM**。此前错误说明可能误导读者，我为此郑重致歉，相关车型信息现已同步更正。

## 0.44.50 更新摘要

- 修复方向盘上一曲、下一曲间歇失效。投屏显示首帧后申请媒体键接收权，断开后释放。
- 增加媒体键接收权回读、收键和转发的诊断记录。
- 延续 0.44.49 的免 VPN 有线连接、麦克风降噪、界面和连接预热。

安装步骤与文件校验见 [0.44.50 发布说明](docs/RELEASE_0.44.50.md)。

## 隐私与发布内容

兼容性报告会上传到维护者的私人服务器。报告可能包含车机型号及 Android 版本、用户描述、USB 接口特征、按键码、连接阶段日志和用户手动确认的测试结果。介意上传到私人服务器的用户请勿提交报告。详见 [`docs/COMPATIBILITY_TESTING.md`](docs/COMPATIBILITY_TESTING.md)。

公开源码不包含私人服务器令牌、签名密钥或离线 MFi 身份文件。公开 APK 按维护者决定包含运行所需的上游来源附件身份；来源说明见 [`docs/AUTH_PROVENANCE.md`](docs/AUTH_PROVENANCE.md)。这不代表 Apple 认证或额外分发授权，也不保证未来 iOS 版本或其他车型可用。

## 构建

- 有线源码在 `legacy/`，参见 [有线构建说明](docs/BUILD.md)。本次发布的内部版本名为 `0.44.50-wheel-media-confirm-beta`。
- 无线源码在 [`wireless-v0.1.98` 标签的 `legacy-wireless/`](https://github.com/hange3514/DiPlay-API17/tree/wireless-v0.1.98/legacy-wireless)，参见 [无线构建说明](https://github.com/hange3514/DiPlay-API17/blob/wireless-v0.1.98/legacy-wireless/BUILD.md)。
- API 17 编译所需工具链及 Android SDK 组件需自行准备。自签名 APK 要和车机上已有版本签名一致才能覆盖安装。 [历史版本](https://github.com/hange3514/DiPlay-API17/releases)
