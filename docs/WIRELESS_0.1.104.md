# DiPlay 0.1.104 无线 CarPlay 正式版

支持 Wi-Fi Direct 的 MX9A0 车机现在默认使用 Wi-Fi Direct。DiPlay 通过外置 USB 蓝牙适配器和 iAP2 引导 iPhone 自动加入车机建立的局域网。画面、声音和控制在这条 Wi-Fi 链路上传输，整个过程无需建立 Android 系统 VPN。

普通系统热点曾在车载移动网络恢复时因 NAT 转发失败被系统关闭。Wi-Fi Direct 使用本地局域网和独立的组网流程，避开这一故障链路。车机移动数据可以继续开启。主界面移除了「车载热点」按钮，手动连接和开机自动连接均无需勾选测试选项。

## 实车验证范围

Wi-Fi Direct 已在 0.1.103 上用以下组合实车投屏。0.1.104 将同一组网流程设为默认，并精简主界面。

| 项目 | 实车配置 |
| --- | --- |
| 车型 | 2018 款比亚迪宋 DM |
| 车机 | Freescale MX9A0，固件 `V4.2.2.30.170830`，Android 4.2.2 / API 17，ARMv7 |
| 投屏 | 1024 × 600 |
| 手机 | iPhone 15 Pro Max，iOS 26.2.1 |
| USB 蓝牙 | **绿联 CM591**，实测适配器 USB ID 为 `10D7:B012` |

实车验证仅覆盖这台车和表中组合。CM390 路径参考上游项目，尚未在本车实测。 [实测车辆照片及交流群](https://github.com/hange3514/DiPlay-API17#实测车型)

## 安装与连接

1. 在车机安装发布附件中的 APK。此前同签名的无线版可直接覆盖升级。
2. 关闭车机系统热点，插上 CM591，并保持 iPhone 的蓝牙和 Wi-Fi 开启。
3. 在 DiPlay 点「连接」。首次连接时，按提示允许应用访问 USB 适配器，并在 iPhone 上完成配对和 CarPlay 确认。
4. 首次成功投屏后保持适配器插着。车机下次启动会使用已保存的配对记录自动尝试连接。

## 源码与在线功能

项目基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)，外置 USB 蓝牙栈源自 [DiPlay-Legacy-Nissan](https://github.com/mp1314/DiPlay-Legacy-Nissan/tree/90a9a09f62292aab6fffa0896f83749c70f47d9f)。对应源码和构建说明位于本标签的 [`legacy-wireless/`](https://github.com/hange3514/DiPlay-API17/tree/wireless-v0.1.104/legacy-wireless)。 [公开 APK 认证材料说明](https://github.com/hange3514/DiPlay-API17/blob/wireless-v0.1.104/docs/AUTH_PROVENANCE.md)

在线更新和主动提交的诊断报告使用维护者的私人服务器。报告可包含车机信息、用户填写的问题描述和应用日志。手动选择诊断附件或麦克风采样时，相应内容也会随报告提交。介意上传的用户请勿使用提交功能。

| APK 项目 | 值 |
| --- | --- |
| 文件名 | `DiPlay_API17_0.1.104-wifi-direct-default.apk` |
| 包名 | `local.headunit.diplay.hybrid` |
| versionCode | `105` |
| 文件大小 | `1,464,013` 字节 |
| SHA-256 | `635aa3a0a0977328386b305c338a11be3c892a82055cee3b6e5d27cdf57b384c` |

适配交流可加入 QQ 群「就是耍」，群号 **1078069411**。 [项目首页二维码](https://github.com/hange3514/DiPlay-API17#适配交流)
