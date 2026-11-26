# API 17 源码构建说明

`legacy/` 是有线版 0.44.51 对应的应用源码快照。APK 在 Windows 下以 API 17 工具链构建，并使用维护者本地签名。公开仓库不包含 Android SDK、Kotlin/Zig 编译工具链、签名密钥或离线 MFi 身份文件，因此从干净 GitHub 克隆不能直接复现公开 APK 的签名或全部附件认证配置。

当前 `legacy/build.py` 沿用维护者工作区的相对目录布局：工具链位于 `research/DiPlay/toolchain`，Android 构建工具和 API 平台位于 `headunit-probe/tools/`。移植到其他目录前，需要按本机 SDK 与工具链位置调整脚本中的目录引用；签名时使用自己的 keystore 和口令。不要把签名密钥、口令、服务令牌或离线 MFi 身份材料提交到仓库。

构建后可在 `legacy/` 运行 `python test_protocols.py`。把含实际 `org.json` 实现的 JVM 测试 JAR 放到工具链目录，命名为 `android-json-test.jar`，或通过 `DIPLAY_TEST_JSON_JAR` 指定路径。APK 运行时使用车机系统提供的 `org.json`。

公开 APK 的文件名、大小和 SHA-256 见 [0.44.51 发布说明](RELEASE_0.44.51.md)。附件身份材料见 [认证材料来源](AUTH_PROVENANCE.md)。自行构建请使用自己的签名。覆盖安装要求新旧 APK 使用同一签名。
