# 有线 API 17 源码构建

有线换机测试版 **0.44.60** 的对应代码位于 [`v0.44.60-phone-switch-test` 标签的 `legacy/`](https://github.com/hange3514/DiPlay-API17/tree/v0.44.60-phone-switch-test/legacy)。可使用 Windows、Python 3.11 或以上及 JDK 21 构建。Android API 17 编译依赖、Zig、Kotlin、R8、Bouncy Castle 和 Android 构建工具由构建者准备。

- `DIPLAY_TOOLCHAIN` 指向包含 `jdk21/`、`android17.jar`、`zig/`、`r8.jar` 和编译依赖的工具链目录。
- `DIPLAY_BUILD_TOOLS` 指向包含 `aapt.exe`、`zipalign.exe`、`lib/apksigner.jar` 的 Android Build Tools 目录。
- `DIPLAY_RESOURCE_ANDROID_JAR` 指向用于资源打包的 API 19 `android.jar`。Java、Kotlin 和 DEX 目标为 API 17。
- `DIPLAY_SIGNING_KEYSTORE`、`DIPLAY_SIGNING_ALIAS`、`DIPLAY_SIGNING_STORE_PASSWORD` 和 `DIPLAY_SIGNING_KEY_PASSWORD` 用于本机签名。覆盖安装需要相同的 APK 签名证书。
- 如需复现 CarPlay 附件认证，在本机准备有权使用的附件身份文件，路径见[附件身份来源](AUTH_PROVENANCE.md)。

在 `legacy/` 运行 `python build.py`，APK 位于 `dist/`。JVM 测试可用 `python test_protocols.py`，通过 `DIPLAY_TEST_JSON_JAR` 指定含实际 `org.json` 实现的测试 JAR。公开 APK 及校验值见[发布说明](RELEASE_0.44.60_PHONE_SWITCH.md)。
