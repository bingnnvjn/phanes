# `android/` 上游来源与改动说明

- **上游仓库**：`termux/termux-app`
- **基线版本**：`v0.119.0-beta.3`（上游 tag；对应提交 `816a4bf`，2025-05-23）
- **本目录是修改版（fork）**：应用身份前缀、界面层、Kotlin 化、JNI 边界、
  构建链与打包内容都做了改动，见本仓库提交历史
- **修改日期**：fork 起点 2026-08-06，持续到本仓库 `master` 当前提交
- **改动内容**：逐条见本仓库 `master` 的提交历史（`git log -- android/`）
- **许可**：整体仍受 **GPLv3-only** 约束，全文见 [`GPL-3.0.txt`](GPL-3.0.txt)；
  例外见 [`LICENSE.md`](LICENSE.md) 与 [`termux-shared/LICENSE.md`](termux-shared/LICENSE.md)
- **第三方来源台账**：[`../docs/release/third-party-sources.md`](../docs/release/third-party-sources.md)
- **签名材料**：应用签名材料（keystore、密码、私钥）永不进入仓库或构建输入
  （ADR-0012、ADR-0013）
