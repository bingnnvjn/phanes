# Android 签名

release 凭据不在仓库中保存。构建 release 时，Gradle 必须能取得以下四项环境变量、用户级 Gradle 属性（`~/.gradle/gradle.properties`），或仓库根目录不入库的 `signing.properties`：

```properties
FABLE_RELEASE_KEYSTORE=/absolute/path/to/fable-release.jks
FABLE_RELEASE_KEY_ALIAS=fable
FABLE_RELEASE_STORE_PASSWORD=...
FABLE_RELEASE_KEY_PASSWORD=...
```

CI 应把这四项作为 secret 环境变量注入；本机需要文件式覆盖时，将上述内容放入 `signing.properties`。环境变量优先于 Gradle 属性和该本地文件。release 缺少任意一项会在执行 `assembleRelease` 或 `bundleRelease` 前明确失败，绝不回退到 debug key。

debug 默认使用 Android Gradle Plugin 生成的独立 debug keystore，因此不能覆盖安装由 release 身份签名的正式包。仅在本机需要覆盖升级时，显式加上 `FABLE_DEBUG_USE_RELEASE_SIGNING=1`，并同时提供完整 release 凭据：

```bash
FABLE_DEBUG_USE_RELEASE_SIGNING=1 ./gradlew :app:assembleDebug
```

`signing.properties` 已被忽略，但不要将密码写回任何受版本控制的文件。用 `./test-signing-config.sh` 检查默认 debug 身份、release 缺凭据失败和显式覆盖路径。
