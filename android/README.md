# android/ — 应用（Kotlin 壳 + JNI 桥）

本目录是 Phanes 的 Android 应用：Kotlin 壳、XML/经典 View 界面、四个 Gradle 模块
（`app`、`termux-shared`、`core`、`terminal-view`）。源码源自 termux-app
`v0.119.0-beta.3` 的 fork；界面、会话层与渲染通路已重写（ADR-0003/0004/0009）。

构建、设备配置与构建输入见仓库根 [README.md](../README.md)；应用身份与改名边界见
[`docs/rename-inventory.md`](../docs/rename-inventory.md) 与 ADR-0013。签名配置见
`docs/signing.md`。
