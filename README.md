# DiPlay API 17 兼容性移植

本仓库是基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 的 Android API 17 有线 CarPlay 移植与兼容性实验分支，遵循 GPL-3.0。公开源码和版本 APK 发布在 GitHub Releases。

## 更正与致歉

我此前把自己的车辆年款误写为 2017 款。经重新确认，实际测试车辆是 **2018 款比亚迪宋 DM**。此前错误说明可能误导读者，我为此郑重致歉，相关车型信息现已同步更正。

目前仅有该车机（MX9A0 / Android 4.2.2 / API 17）与 iPhone 15 Pro Max（iOS 26.2.1）的有线实车测试记录；其他车型和车机没有测试。

## 当前版本

- 当前正式版：**DiPlay 0.44.49**（APK 内部版本名为 `0.44.49-usb-reattach-focus-beta`），适用于 Android API 17。对应源码在 `legacy/`，APK 与 SHA-256 在 [GitHub Releases](https://github.com/hange3514/DiPlay-API17/releases)。
- 实车测试组合：比亚迪宋 DM 2018 款、Freescale MX9A0、Android 4.2.2 / API 17、1024×600、iPhone 15 Pro Max / iOS 26.2.1。0.44.49 已在该组合实车测试，用户反馈未发现问题；没有在其他车型或车机上测试。
- 0.40.0 是此前有明确稳定有线使用记录的版本。历史版本可在 Releases 查看。

## 0.44.49 更新摘要

- 沿用应用内用户态 IPv6 网络处理，正常连接不再创建 Android VPN，因而不再出现 VPN 授权弹窗。
- 保留 0.44.48 的电话麦克风低延迟滤波：抑制低频风噪/轰鸣，并在语音间隙柔和压低底噪；没有更换已验证的音频通路。
- 保留启动过渡动画、连接进度界面和控制面板的界面优化。
- 继续使用此前连接预热；本版修复 USB 重枚举后授权对象过期和默认 Activity 抢占 USB 系统弹窗导致的等待问题。本次实车测试未发现连接异常；不同车机和手机的 CarKit/MFi 握手耗时仍可能不同。

逐项说明、安装步骤和 SHA-256 见 [`docs/RELEASE_0.44.49.md`](docs/RELEASE_0.44.49.md)。

## 隐私与发布内容

兼容性报告会上传到维护者的私人服务器。报告可能包含车机型号及 Android 版本、用户描述、USB 接口特征、按键码、连接阶段日志和用户手动确认的测试结果。介意上传到私人服务器的用户请勿提交报告。详见 [`docs/COMPATIBILITY_TESTING.md`](docs/COMPATIBILITY_TESTING.md)。

公开源码不包含私人服务器令牌、签名密钥或离线 MFi 身份文件。公开 APK 按维护者决定包含运行所需的上游来源附件身份；来源说明见 [`docs/AUTH_PROVENANCE.md`](docs/AUTH_PROVENANCE.md)。这不代表 Apple 认证或额外分发授权，也不保证未来 iOS 版本或其他车型可用。

## 构建

源码树位于 `legacy/`。API 17 编译所需工具链与本地 Android SDK 组件没有打包到仓库；`docs/BUILD.md` 说明已知构建环境。公开 APK 是独立签名的发布产物，不能使用其他签名覆盖已安装的不同签名版本。
