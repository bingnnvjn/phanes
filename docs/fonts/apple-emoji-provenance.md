# Apple Color Emoji 溯源（工单 22）

> 本文件是字体资产的单一溯源记录。所有 sha256 均为构建脚本
> `scripts/fetch-emoji-fonts.sh` 的强制校验值；不匹配即构建失败。

## 原始文件（ttc）

| 项 | 值 |
| --- | --- |
| 文件名 | `AppleColorEmoji-160px.ttc` |
| 大小 | 63,888,516 B |
| sha256 | `76c37f95960d82a37a2c93c929dce7eb830a641707786a9e6a8ee17d911714dc` |
| 版本串 | `21.4d3e1`（iOS 26.4 / macOS 26.4 时代，2026-03 发布，覆盖 Emoji 17.0） |
| 来源 | PoomSmart/EmojiFonts release `17.0.0-apple`（iOS 固件提取，苹果原版位图） |
| 下载 URL | `https://github.com/PoomSmart/EmojiFonts/releases/download/17.0.0-apple/AppleColorEmoji-160px.ttc`（构建走 `ghfast.top` 前缀） |
| 提取日期 | 2026-08-10（决策窗口首次下载，2026-08-10 构建脚本复验） |
| 结构 | 4367 glyphs；strikes 40/64/96/160；160 档 3761 张 PNG |

原版性交叉验证（决策窗口记录，见 ADR-0007）：iOS 提取版 vs macOS 独立提取版
逐像素比对，⚽ 0% 差异、其余颜色一致，仅抗锯齿边缘约 4% 像素不同 → 苹果原版位图。

## 剥离产物（assets 内文件）

| 项 | 值 |
| --- | --- |
| 文件名 | `AppleColorEmoji.ttf`（android `app/src/main/assets/fonts/`） |
| 大小 | 30,770,720 B |
| sha256 | `6f6ad8b9751356c5707ab9e2645cddc3521d116a6b387d7b4b1437456d3784a3` |
| 剥离规则 | face 0；sbix 仅保留 160px strike（40/64/96 删除）；图样零删减（3761 PNG） |
| 剥离工具 | `renderer/examples/strip_apple_emoji.rs`（Rust 自解析，确定性输出） |
| 校验 | 版本串 `21.4d3e1`、strike ppem=160、PNG 数 3761、sha256 全量比对 |

> 注：决策窗口的手工剥离产物（`e7605cad…`）与脚本剥离产物
> （`6f6ad8b9…`）表内容完全一致（逐表大小相同），仅表目录顺序不同；
> 本仓库以脚本可复现产物为准（sha256 `6f6ad8b9…`）。

## Noto 兜底字体

| 项 | 值 |
| --- | --- |
| 文件名 | `NotoColorEmoji.ttf`（COLRv1 完整版，原文件名 `Noto-COLRv1.ttf`） |
| 大小 | 4,991,984 B |
| sha256 | `0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2` |
| 版本 | `Version 2.051;GOOG;noto-emoji:20250818…` |
| 来源 | googlefonts/noto-emoji `fonts/Noto-COLRv1.ttf`（OFL-1.1，工单 13 已内嵌同文件） |

## 更新流

见 `docs/fonts/换字体步骤.md`；每周上游检查 `scripts/check-emoji-upstream.sh`。
