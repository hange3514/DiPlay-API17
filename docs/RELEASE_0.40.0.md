# DiPlay API17 v0.40.0 有线 CarPlay 发布说明

发布日期：2026-10-30

## 下载

- 最新版：DiPlay_API17_Wired.apk（0.40.0，versionCode 131）
- 有线稳定回退包：DiPlay_API17_Stable034.apk（0.34.0-stable-131，versionCode 131）
- 源码快照：DiPlay_API17_0.40.0_Source_Snapshot.zip（不含本地签名密钥、服务器凭据、日志或 OEM APK）。
- SHA-256：见同一发布页的 SHA256SUMS-v0.40.0.txt

## 功能和变化

- 有线 CarPlay，1024×600，固定 15 fps。
- 本版本的通话音频路径使用 ALSA 直接采集调整。车主反馈通话声音问题已解决。
- 界面保留连接和停止操作；此公开版不包含在线更新、日志提交或环境检测入口。
- 同一发布页附带 0.34 有线稳定回退 APK。

## 实测范围

唯一的实车组合为：

- 比亚迪宋 DM 2017 款，MX9A0 车机
- Android 4.2.2，API 17；Linux 内核 3.0.35-svn54963
- iPhone 15 Pro Max，iOS 26.2.1
- USB 有线连接

通话声音修复由车主在上述组合上反馈已解决。没有在其他车型、车机、手机或 iOS 版本上验证，不代表通用兼容。378 项主机侧单元/协议测试通过；这些测试不能代替实车音频验证。

## 安装授权提示

连接 iPhone 时，车机若显示“允许应用‘图库’访问该 USB 设备吗？”，选择“取消”，不要勾选默认使用。DiPlay 显示 VPN 授权时，勾选“我信任此应用”并选择“确定”。

## 来源与安全说明

本项目基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，遵循 GPL-3.0。发布包保留上游项目中的实验性 MFi 认证材料；这不表示获得 Apple 的 MFi/CarPlay 官方认证。APK 未发现个人服务器令牌或 AI 服务 API Key；签名私钥不随 APK 发布。

程序和协议仅针对上面列出的实测设备组合做兼容适配。本项目不是 Apple 或比亚迪官方产品。
