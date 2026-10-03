# Phanes 许可分区

本仓库不是单一许可证；按目录分区授权，各目录的完整文本为准。

| 目录 | 许可 | 完整文本 |
| --- | --- | --- |
| `android/` | **GPLv3-only**（`termux/termux-app` 的修改版 fork） | [`android/GPL-3.0.txt`](android/GPL-3.0.txt)；例外见 [`android/LICENSE.md`](android/LICENSE.md) |
| `renderer/` | **MIT** | [`renderer/LICENSE`](renderer/LICENSE) |
| `session/` | **MIT** | [`session/LICENSE`](session/LICENSE) |
| `boo/` | **MIT** | [`boo/LICENSE`](boo/LICENSE) |
| `libghostty/` | 只跟踪 README；实际资产属构建输入，不入库（ADR-0012） | [`libghostty/README.md`](libghostty/README.md) |

本仓库自行编写的其余内容（根文档、`docs/`、`scripts/`、配置）与 `renderer/`、
`session/`、`boo/` 相同，按 **MIT** 授权，除非该文件或目录另有声明。

第三方来源与许可证台账：[`docs/release/third-party-sources.md`](docs/release/third-party-sources.md)。
应用身份（`com.gph.fable`）与签名身份按 ADR-0013 冻结，不随项目改名变化。
