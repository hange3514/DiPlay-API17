# DiPlay 0.44.60 有线 CarPlay 换机测试版

这次更新着重处理两台 iPhone 交替插线时的 USB 授权、设备识别和旧连接清理。

## 本次更新

- 拔掉上一台 iPhone 后，程序依据当前 USB 设备列表识别新手机。迟到的 USB 插入、拔出或授权通知需要和当前设备及对应的授权请求匹配。
- iPhone 正在切换 USB 模式时，仍能识别并优先保留已选中的设备。重新识别设备后若暂时无法打开 USB 连接，程序会短暂重试。
- 每台 iPhone 保留自己的信任记录。只有当前有效连接的首帧可以进入投屏，避免上一台手机迟到的画面通知改变界面。
- 相比 GitHub 有线正式版 0.44.52，加入按设备保留的配对记录和 ALSA 麦克风采集路径适配。通话在默认路径下使用 Android 媒体输出。兼容性反馈可按提示附带原厂百度 CarLife 安装包及可读取的同名 ODEX，用于方向盘按键适配。

## 安装与验证

下载 APK 安装。已有同签名有线版时可以覆盖安装。使用 USB 数据线连接 iPhone，按系统提示授予 DiPlay 的 USB 访问权限。如果图库单独申请访问 USB，可以取消图库的请求。应用使用 lwIP 处理 USB 网络，投屏无需系统 VPN 授权。

项目已有实车参考为 2018 款比亚迪宋 DM，车机为 Freescale MX9A0，固件 `V4.2.2.30.170830`，Android 4.2.2 / API 17，屏幕 1024 × 600，参考手机为 iPhone 15 Pro Max / iOS 26.2.1。

本版通过 523 项本地回归测试及 API 17 签名校验。两台手机快速换机和车机 USB 授权弹窗的实际时序仍待实车验证。实车可重点检查授权弹窗尚未结束时拔下 A 再插入 B，确认最终显示 B 的 CarPlay。

## 兼容性报告

报告由用户确认后通过 HTTP 发送至维护者的服务器。报告可能包含设备信息，也可能包含一条失败的 AirPlay 请求及其异常详情、限长的请求头和请求体，以及相关 iPhone 标识。原厂 CarLife APK 和 ODEX 会在选择附带时另外上传。本地投屏可独立使用。[兼容性与隐私说明](https://github.com/hange3514/DiPlay-API17/blob/main/docs/COMPATIBILITY_TESTING.md)

## APK 与源码

- `DiPlay_API17_0.44.60-phone-switch-test.apk`，1,361,088 字节。
- 包名 `local.headunit.diplay`，versionCode `195`，最低 Android API 17，ARMv7。
- SHA-256 `b8e0af5937abf850aa38d3aafe4487722d44aa71f9c383fc960a595e748e13e0`。
- [对应有线源码](https://github.com/hange3514/DiPlay-API17/tree/v0.44.60-phone-switch-test/legacy) · [构建说明](https://github.com/hange3514/DiPlay-API17/blob/v0.44.60-phone-switch-test/docs/BUILD.md) · [附件身份来源](https://github.com/hange3514/DiPlay-API17/blob/v0.44.60-phone-switch-test/docs/AUTH_PROVENANCE.md)

本项目基于 GPL-3.0 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)。适配交流见[项目首页](https://github.com/hange3514/DiPlay-API17#适配交流)。
