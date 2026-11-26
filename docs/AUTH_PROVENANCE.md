# 公开 APK 的认证材料来源

2026-10-11 核对上游公开发布页：https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.15 。上游明确说明其公开预览包包含从公开固件获得的实验性附件身份，身份可提取且未来 iOS 接受情况未定；源码归档默认不含身份文件。本项目不将此表述为 Apple 认证或额外分发授权。

本地上游 APK SHA-256 与发布页一致：`4bf45f16d6b1ab0a61462b831014081f07240f5596c90ca6bf38fb43f9890511`。

0.34 原始包和 0.34.1-public 保留的两个运行文件均与上述 APK 逐字节相同：

| 路径 | SHA-256 |
| --- | --- |
| assets/offline-mfi/identity.pk8 | bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75 |
| assets/offline-mfi/certificate.p7b | 634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e |

这些材料仅随已明确标注的 APK 保留，不把其原始值贴到文档或提交为源码。可从该固定摘要的 APK 取得同名 ZIP 条目，放入 `legacy/assets/offline-mfi/` 复现构建。普通使用者不需要手工操作。APK 签名密钥及个人服务器配置没有公开。

这是对现有公开材料的来源和一致性记录，不保证撤销、iOS 更新或其他机型上的可用性。
## 0.44.49 公开 APK

GitHub Release `v0.44.49-usb-reattach-focus-beta` 中的 APK 也包含上述同一组上游来源附件身份材料：`identity.pk8` SHA-256 为 `bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75`，`certificate.p7b` SHA-256 为 `634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e`。它们只作为该版本 APK 的运行资源发布，没有加入源码仓库。该发布不代表 Apple 认证或额外分发授权，也不保证未来 iOS 版本或其他车型上的可用性。

## 0.44.50 公开 APK

正式版 `v0.44.50` 保留上述同一组上游附件身份材料。已核对两个 APK 内置文件的 SHA-256 与此文档相同。

## 无线版 `wireless-v0.1.104`

发布 APK 与该版本的车机在线更新包逐字节一致。已核对内置证书和附件身份的 SHA-256，与此前公开的 `wireless-v0.1.98` 及上表相同。公开源码归档不含身份文件和 APK 签名凭据。 [上游第三方声明](https://github.com/shihabal3amri/DiPlay/blob/main/docs/THIRD_PARTY_NOTICES.md#experimental-authentication-data)

## 有线 `v0.44.52` 和无线 `wireless-v0.1.117`

两份 Release APK 与在线更新包逐字节一致。对比此前公开 APK，两份的 `assets/` 条目及其内容均一致，内置的两个附件身份文件沿用上表哈希。对应源码使用构建者本机提供的签名和附件身份材料。
