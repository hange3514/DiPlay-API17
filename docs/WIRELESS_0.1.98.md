# DiPlay 0.1.98 无线 CarPlay 首次公开版

这是面向老款 Android 车机的无线 CarPlay 移植。无线版通过外置 USB 蓝牙适配器与 iPhone 配对，再由 iPhone 加入车机热点传输画面、音频和控制。APK 与车主使用的 `0.1.98-wireless-hotspot-order` 在线更新包完全一致。

同仓库提供 [有线 0.44.50](https://github.com/hange3514/DiPlay-API17/releases/tag/v0.44.50)。无线版使用独立包名，两个版本可同时安装。连接时建议只运行一个版本。

## 实车验证和适配器

| 项目 | 实车配置 |
| --- | --- |
| 车型 | 比亚迪宋 DM，2018 款 |
| 车机 | Freescale MX9A0，固件 `V4.2.2.30.170830`，Android 4.2.2 / API 17，ARMv7 |
| 投屏 | 1024 × 600 |
| 手机 | iPhone 15 Pro Max，iOS 26.2.1 |
| 蓝牙 | 绿联 **CM591**，测试设备的 USB ID 为 `10D7:B012` |
| Wi-Fi | iPhone 连接车机自身的热点；实车使用 2.4 GHz |

**请优先按 CM591 的实车组合选用。** CM390 的初始化路径来自上游代码，本项目未实车验证 CM390。其他车型、车机固件、适配器硬件批次、iPhone 和 iOS 版本也未验证。

## 安装和首次连接

1. 下载发布附件中的 APK，在车机上安装。原无线 `0.1.87` 或同签名的后续版本可覆盖升级。
2. 插入一只 CM591，打开车机端 DiPlay。系统弹出 DiPlay 的 USB 访问授权时，选择允许。
3. iPhone 保持蓝牙和 Wi-Fi 开启。在车机点「连接」，按界面提示完成蓝牙配对和 iPhone 的 CarPlay 确认。应用使用车机热点，系统限制自动开启时可按提示在车机设置中手动打开。
4. 首次配对完成后，应用可使用保存的配对记录重连。车机热点与 Wi-Fi 客户端共用同一块无线网卡，应用在自动启动热点前先等待客户端关闭。

## 这一版包含的主要改动

- 将蓝牙收发、配对和 iAP2 引导移植到 Android USB Host。主线采用 DiPlay-Legacy-Nissan 的 GPL-3.0 蓝牙协议栈，适配 CM591 的控制器行为。
- 整理蓝牙到 Wi-Fi 的交接顺序。正常连接时，在 Wi-Fi 上的 iAP2 隧道完成认证之后再释放蓝牙引导通道。支持旧版配对记录迁移和断线后自动恢复。
- 优化车机热点启动。在车机仍处于 Wi-Fi 客户端模式时先关闭客户端，等热点和地址准备好后再建立会话。应用会在符合条件时重建异常热点，并在准备失败时恢复此次关闭的 Wi-Fi。
- 延续媒体、导航和通话音频路径，优化缓冲音乐、麦克风采集和断流恢复。方向盘上一曲、下一曲增加媒体键接收权回读和断开后的注销。

## 在线功能、源码和校验

本次按原样分发 APK。在线更新和用户主动提交的诊断报告仍使用维护者的私人服务器。报告可包含车机环境、问题描述和应用日志。界面另有麦克风采样和车机诊断附件的提交选项。介意上传的用户可以不使用这些提交功能。

主项目源自 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，蓝牙代码参考 [DiPlay-Legacy-Nissan](https://github.com/mp1314/DiPlay-Legacy-Nissan/tree/90a9a09f62292aab6fffa0896f83749c70f47d9f)。对应无线源码在本标签的 `legacy-wireless/`，并随 GitHub 生成的源码压缩包提供。编译方法与组件归属见 [无线构建说明](../legacy-wireless/BUILD.md) 和 [第三方声明](../legacy-wireless/THIRD_PARTY_NOTICES.md)。

APK 保留上游预览包已有的实验附件身份，与有线 0.44.50 内置材料一致。身份和 CarPlay 图标的权利不随项目源代码许可转授，后续 iOS 的接受情况也不确定。此项目与 Apple、比亚迪和绿联无隶属关系。 [认证材料来源](AUTH_PROVENANCE.md) · [上游第三方声明](https://github.com/shihabal3amri/DiPlay/blob/main/docs/THIRD_PARTY_NOTICES.md#experimental-authentication-data)

| APK 项目 | 值 |
| --- | --- |
| 文件名 | `DiPlay_API17_0.1.98-wireless-hotspot-order.apk` |
| 包名 | `local.headunit.diplay.hybrid` |
| versionCode | `99` |
| 文件大小 | `1,443,533` 字节 |
| SHA-256 | `fb1dd903916406a8a767cbc3794b2a73da084b89d9e34bbe066484b55442f0a8` |
