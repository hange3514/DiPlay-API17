# DiPlay 0.44.51 有线 CarPlay

面向 Android 4.2.2（API 17）车机的有线 CarPlay，移植自 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)。应用内的 lwIP 协议栈继续处理 USB 投屏通信，开机自动连接无需系统 VPN 授权。

## 本次更新

- 移植无线版的画面优化。适配 Freescale H.264 硬件解码器，改进输入排队、画面输出节奏和积压时的恢复，记录收帧与解码数据供画面对比测试使用。
- 投屏分辨率为 **1024 × 600**，默认使用 **60 FPS**。也可选择 15、20、30、40 或 50 FPS，设置在下一次连接时生效。
- 投屏时可按返回键打开控制面板，在「画面帧率」中切换档位或启动自动对比。测试按实际输出和卡顿数据给出配置建议。
- 支持在车机屏幕上向 iPhone 转发双指触控。高德地图等支持该手势的 CarPlay 应用可双指捏合缩放。

## 安装与验证

下载本页 APK 覆盖安装 0.44.50，用 USB 数据线连接 iPhone。若系统提示 DiPlay 访问 USB 设备，请允许。如果另弹出图库访问 USB 的提示，请点取消。停车后打开 CarPlay 高德地图即可测试双指缩放。

60 FPS、动画流畅度和高德地图双指缩放已在无线 **0.1.109** 实车验证。有线 **0.44.51** 通过 API 17 构建与签名校验、493 项 JVM 测试和 3 个原生测试程序。新功能的有线实车表现待验证。

| 参考实测设备 | 配置 |
| --- | --- |
| 车型 | 2018 款比亚迪宋 DM |
| 车机 | Freescale MX9A0，Android 4.2.2 / API 17 |
| 显示 | 1024 × 600 |
| 手机 | iPhone 15 Pro Max，iOS 26.2.1 |

其他车型或系统版本的兼容情况可在 QQ 群「就是耍」`1078069411` 交流。主动提交兼容性报告会将设备环境、问题描述和相关日志上传到维护者的私人服务器。

## APK 与源码

- APK `DiPlay_API17_0.44.51.apk`，1,340,608 字节，包名 `local.headunit.diplay`，版本编号 `186`，Android API 最低版本 `17`。
- 源码在 `v0.44.51` 标签的 `legacy/`。APK 使用与 0.44.50 相同的安装签名，附件身份材料沿用此前版本。 [附件身份来源](https://github.com/hange3514/DiPlay-API17/blob/v0.44.51/docs/AUTH_PROVENANCE.md)
- 文件校验见发布附件 `SHA256SUMS-v0.44.51.txt`。

```text
ceb6a09b1e717b514e15b4874efd52b8dfc50e2fe5cc2f6c9c7c80d6007ad6eb  DiPlay_API17_0.44.51.apk
```
