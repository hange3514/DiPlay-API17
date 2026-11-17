# API 17 源码构建说明

`legacy/` 是 0.44.49 对应的应用源码快照。APK 在 Windows 下以 API 17 工具链构建，并使用维护者本地签名。公开仓库不包含 Android SDK、Kotlin/Zig 编译工具链、签名密钥或离线 MFi 身份文件，因此从干净 GitHub 克隆不能直接复现公开 APK 的签名或全部附件认证配置。

当前 `legacy/build.py` 沿用维护者工作区的相对目录布局：工具链位于 `research/DiPlay/toolchain`，Android 构建工具和 API 平台位于 `headunit-probe/tools/`。移植到其他目录前，需要按本机 SDK 与工具链位置调整脚本中的目录引用；签名时使用自己的 keystore 和口令。不要把签名密钥、口令、服务令牌或离线 MFi 身份材料提交到仓库。

公开 APK 的文件名、大小、SHA-256 和认证材料来源记录在 `docs/RELEASE_0.44.49.md` 与 `docs/AUTH_PROVENANCE.md`。源码构建产物应使用自己的签名；替换签名后不能覆盖已安装的不同签名版本。
