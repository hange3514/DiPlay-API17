# DiPlay API 17 兼容性移植

本仓库是基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 的 Android API 17 有线 CarPlay 移植与兼容性实验分支，遵循 GPL-3.0。公开源码和版本 APK 发布在 GitHub Releases。

## 更正与致歉

我此前把自己的车辆年款误写为 2017 款。经重新确认，实际测试车辆是 **2018 款比亚迪宋 DM**。此前错误说明可能误导读者，我为此郑重致歉，相关车型信息现已同步更正。

## 当前版本

- 当前有线正式版为 **DiPlay 0.44.50**。实车使用的 APK 内部版本名为 `0.44.50-wheel-media-confirm-beta`，对应源码在 `legacy/`。[下载 APK 和 SHA-256](https://github.com/hange3514/DiPlay-API17/releases/tag/v0.44.50)
- 实车验证通过的组合为比亚迪宋 DM 2018 款、Freescale MX9A0、Android 4.2.2 / API 17、1024 × 600、iPhone 15 Pro Max / iOS 26.2.1。
- 历史版本在 [GitHub Releases](https://github.com/hange3514/DiPlay-API17/releases) 查看。

## 0.44.50 更新摘要

- 修复方向盘上一曲、下一曲间歇失效。投屏显示首帧后申请媒体键接收权，断开后释放。
- 增加媒体键接收权回读、收键和转发的诊断记录。
- 延续 0.44.49 的免 VPN 有线连接、麦克风降噪、界面和连接预热。

安装步骤与文件校验见 [0.44.50 发布说明](docs/RELEASE_0.44.50.md)。

## 隐私与发布内容

兼容性报告会上传到维护者的私人服务器。报告可能包含车机型号及 Android 版本、用户描述、USB 接口特征、按键码、连接阶段日志和用户手动确认的测试结果。介意上传到私人服务器的用户请勿提交报告。详见 [`docs/COMPATIBILITY_TESTING.md`](docs/COMPATIBILITY_TESTING.md)。

公开源码不包含私人服务器令牌、签名密钥或离线 MFi 身份文件。公开 APK 按维护者决定包含运行所需的上游来源附件身份；来源说明见 [`docs/AUTH_PROVENANCE.md`](docs/AUTH_PROVENANCE.md)。这不代表 Apple 认证或额外分发授权，也不保证未来 iOS 版本或其他车型可用。

## 构建

源码树位于 `legacy/`。API 17 编译所需工具链与本地 Android SDK 组件没有打包到仓库；`docs/BUILD.md` 说明已知构建环境。公开 APK 是独立签名的发布产物，不能使用其他签名覆盖已安装的不同签名版本。
