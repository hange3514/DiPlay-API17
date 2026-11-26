# DiPlay 0.44.52 有线 CarPlay

## 本次更新

- 「问题反馈 → 提交问题说明和诊断」现在只需要一句问题描述，例如「通话双方都听不到」。原厂车型代码、车机固件和原厂服务版本由程序随报告采集。固件未公开车型代码时，硬件和固件信息仍可用来比对车机环境。
- 同一份报告包括 ALSA 声卡和 PCM 节点、权限、实际尝试的采集路径、失败阶段及回退情况。通话下行记录 PCM 电平、写入量、播放头和可读取的输出路由，便于分别排查麦克风上行和车机扬声器下行。
- 自动保留 Android 按键事件和可读取的原厂方向盘广播，包括接收器注册状态、键码和转发结果。报告筛选优先保留音频和按键证据。
- 核对服务器回执后再清理本次已确认上传的本地记录。处理旧车机时钟调整后可能出现的事件时间偏差。

本版是兼容性取证更新。车友在停车时复现问题，再填写现象并主动提交即可。报告发送到维护者的私人服务器，数据范围见[兼容性与隐私说明](https://github.com/hange3514/DiPlay-API17/blob/main/docs/COMPATIBILITY_TESTING.md)。

## 安装与验证

下载 APK 安装。已安装同签名的有线版可直接覆盖，包括此前发布的 0.43.0 音频取证版。用 USB 数据线连接 iPhone，按提示允许 DiPlay 访问 USB。如果弹出图库访问 USB 的单独提示，请取消。应用内 lwIP 处理 USB 网络，正常连接无需 Android VPN 授权。

项目的实车参考组合为 **2018 款比亚迪宋 DM**，Freescale MX9A0 车机，固件 `V4.2.2.30.170830`，Android 4.2.2 / API 17，1024 × 600，iPhone 15 Pro Max / iOS 26.2.1。本次 APK 已通过 API 17 签名校验、496 项测试及在线升级下载校验。其他车型的声卡、按键和通话适配以各自的实车报告为准。

## APK 与源码

- `DiPlay_API17_0.44.52.apk`，1,340,608 字节
- 包名 `local.headunit.diplay`，versionCode `187`，最低 Android API 17，ARMv7
- SHA-256 `351fb180949eb54205b8f5afdc8404ea772ac6d42e9c4033d22a558c037c12af`
- [对应有线源码](https://github.com/hange3514/DiPlay-API17/tree/v0.44.52/legacy) · [构建说明](https://github.com/hange3514/DiPlay-API17/blob/v0.44.52/docs/BUILD.md) · [附件身份来源](https://github.com/hange3514/DiPlay-API17/blob/v0.44.52/docs/AUTH_PROVENANCE.md)

本项目基于 GPL-3.0 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)。更多适配反馈可通过[项目首页](https://github.com/hange3514/DiPlay-API17#适配交流)加入 QQ 群「就是耍」。
