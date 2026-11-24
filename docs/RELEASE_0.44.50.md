# DiPlay 0.44.50 有线正式版

本项目移植自 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，面向 Android 4.2.2（API 17）车机。

## 应用内协议栈，免 VPN 插线即连

有线版在 APK 内运行 lwIP 协议栈，直接处理 iPhone 与车机之间 USB NCM 链路的 IPv6、TCP 和 UDP，投屏会话使用应用内套接字。车机无需建立 Android 系统 VPN，重启后也无需再次确认 VPN 授权。

开启自动连接并完成首次授权后，车机开机即可检测 iPhone，插上 USB 数据线就自动进入投屏，无需打开应用或点「连接」。若车机提示 USB 访问授权，请按系统提示允许。

## 本次更新

- 修复方向盘上一曲、下一曲间歇失效。有线投屏显示首帧后，应用申请 Android 媒体键接收权。断开或停止投屏后，服务注销接收器并交还接收权。
- 增加媒体键接收闭环诊断。查询系统当前接收器，区分注册调用、系统回读、实际收键与 CarPlay 转发结果。
- 延续 0.44.49 引入的应用内网络协议栈，以及通话麦克风降噪、连接预热和投屏界面的改进。

## 实车验证

车主确认下列组合的实车测试通过，方向盘上一曲和下一曲恢复正常。

| 项目 | 实测设备 |
| --- | --- |
| 车型 | 比亚迪宋 DM，2018 款 |
| 车机 | Freescale MX9A0，Android 4.2.2 / API 17 |
| 投屏分辨率 | 1024 × 600 |
| 手机 | iPhone 15 Pro Max，iOS 26.2.1 |

## 安装与校验

下载发布附件中的 APK，在车机安装后用 USB 数据线连接 iPhone。若系统弹出 DiPlay 的 USB 访问授权，请允许。

发布附件保留实车使用的原始签名，包名为 `local.headunit.diplay`，versionCode 为 185，APK 内部版本名为 `0.44.50-wheel-media-confirm-beta`。

```text
fa38f1b521f3e6ea4e95c24763a81b442655f69eb4e8a6b5b60a797cadbb448e  DiPlay_API17_0.44.50-wheel-media-confirm-beta.apk
```

对应源码快照在 `legacy/`，附件身份材料来源见 [认证材料来源](https://github.com/hange3514/DiPlay-API17/blob/v0.44.50/docs/AUTH_PROVENANCE.md)。主动提交兼容性报告时，车机环境、问题描述和日志会上传到维护者的私人服务器。介意上传可跳过提交。
