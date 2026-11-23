# DiPlay 0.44.50 有线正式版

本项目移植自 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，面向 Android 4.2.2（API 17）车机。

## 本次更新

- 修复方向盘上一曲、下一曲间歇失效。有线投屏显示首帧后，应用申请 Android 媒体键接收权。断开或停止投屏后，服务注销接收器并交还接收权。
- 增加媒体键接收闭环诊断。查询系统当前接收器，区分注册调用、系统回读、实际收键与 CarPlay 转发结果。
- 延续上一正式版的应用内网络处理、通话麦克风降噪、连接预热和投屏界面。正常有线连接无需创建 Android VPN。

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
