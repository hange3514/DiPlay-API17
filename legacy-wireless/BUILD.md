# DiPlay 无线 API 17 构建说明

此目录对应 `0.1.117-auto-evidence`，包名为 `local.headunit.diplay.hybrid`，versionCode 为 `118`。发布 APK 直接取自维护者在线更新包。公开构建脚本用环境变量读取本地工具链和签名配置。

## 构建输入

- Windows、Python 3.12 或以上、JDK 21。
- 在 `DIPLAY_TOOLCHAIN` 指向的目录中准备 `jdk21/<JDK 目录>/bin/java.exe`、`zig/ziglang/zig.exe`、`android17.jar`、`r8.jar`、Kotlin 2.2.10 编译器及其运行依赖、`kotlin-stdlib.jar`、`annotations.jar`、Bouncy Castle 1.79 的 `bcprov.jar`、`bctls.jar` 和 `bcutil.jar`、`junit.jar`、`hamcrest.jar`。`android17.jar` 使用 Android API 17 平台。安装对应版本的 Android NDK 后，可使用 API 17 的 ARMv7 系统 C 链接桩作为 `libc-api17-arm.so`；这个文件只在链接时读取，APK 运行时使用车机系统的 `libc.so`。
- 将 [Xiph 发布的 libopus 1.5.2 源码包](https://opus-codec.org/release/stable/2024/04/12/libopus-1_5_2.html) 命名为 `opus-1.5.2.tar.gz` 放在同一个工具链目录。构建脚本会校验官方发布的 SHA-256。lwIP、Monocypher 和 Concentus 的源码已包含在本目录。
- `DIPLAY_BUILD_TOOLS` 指向包含 `aapt.exe`、`zipalign.exe`、`lib/apksigner.jar` 的 Android 构建工具目录。`DIPLAY_RESOURCE_ANDROID_JAR` 指向 API 19 的 Android 平台 `android.jar`，仅用于资源打包；Java、Kotlin 和 DEX 仍按 API 17 构建。
- iPhone 握手需要 `assets/offline-mfi/certificate.p7b` 和 `assets/offline-mfi/identity.pk8`。构建者可在本机提供自己有权使用的材料，路径已加入忽略规则。发行包的材料来源见 [认证材料说明](../docs/AUTH_PROVENANCE.md)。
- 使用自己的 Android keystore，在当前 PowerShell 进程设置 `DIPLAY_SIGNING_KEYSTORE`、`DIPLAY_SIGNING_ALIAS`、`DIPLAY_SIGNING_STORE_PASSWORD` 和 `DIPLAY_SIGNING_KEY_PASSWORD`。口令直接通过环境变量交给 `apksigner`。自签构建需要与已有安装的 Android 签名一致才能覆盖安装。

在此目录运行 `python build.py`。APK 位于 `dist/`，中间产物和本地编译输出位于 `build/`。实际发行的原始 APK 和哈希见 [无线发布说明](../docs/WIRELESS_0.1.117.md)。
