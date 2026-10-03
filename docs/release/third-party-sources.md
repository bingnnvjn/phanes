# Phanes 第三方来源与许可证台账

这份台账只记录本仓库实际携带或直接复用的第三方来源；`android/` 源码来自
termux-app fork，仍受其上游许可证边界约束。每次
更换版本、打补丁或重新生成资产时，必须同步更新来源 revision、许可证证据
和摘要，并重新运行公开发布闸门。

| 组件 | 发布输入 | 固定来源 | 许可证/证据 |
| --- | --- | --- | --- |
| Ghostty `+boo` 帧 | `boo/data/frames/` | `ghostty-org/ghostty` commit `05221c11c9db0715666fc6e038915128fc6a563e` | MIT；crate 内 `boo/THIRD_PARTY.md` |
| JetBrains Mono Regular | `renderer/assets/JetBrainsMono-Regular.ttf` | JetBrains Mono `v2.304`, tag commit `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9` | SIL OFL 1.1；`renderer/assets/JETBRAINS-MONO-LICENSE.txt`；资产 SHA-256 已固定 |
| Noto Color Emoji | `renderer/assets/NotoColorEmoji.ttf` | `googlefonts/noto-emoji` `Noto-COLRv1.ttf`, revision `e92753bfa55fd449e427d4d325f9c8c40408c74e` | SIL OFL 1.1；`renderer/assets/NOTO-EMOJI-LICENSE.txt`；资产 SHA-256 已固定 |
| FreeType | `renderer/third_party/freetype/` | FreeType `VER-2-14-3`, dereferenced commit `0a0221a1347e2f1e07c395263540026e9a0aa7c7` | FreeType Project License；`LICENSE.TXT`、`docs/FTL.TXT`、`docs/GPLv2.TXT` |
| portable-pty | `session/vendor/portable-pty/` | WezTerm revision `f8921727a11b9f8b073e8c24821d72fd41283500` | MIT；`vendor/portable-pty/LICENSE.md` 与 `supply-chain-exceptions.toml` |
| Termux app host | 进入仓库的 `android/`（termux-app fork） | 上游 `termux/termux-app` `v0.119.0-beta.3`（tag commit `816a4bf`）；合并后不再有独立 remote | GPLv3-only；`android/GPL-3.0.txt` 全文 + `android/LICENSE.md` 及其例外清单；上游来源、基线版本与"这是修改版"见 `android/NOTICE.md`；应用签名材料永不进入仓库 |
| libghostty-vt / Phanes 核心边界 | `libghostty/README.md` 记录来源与 sha256；`.a`/头文件/字体属构建输入，不入库 | `libghostty-vt` expo-libghostty 预编译资产，由 `docs/build-inputs.md` 固定来源与摘要 | 构建输入按 ADR-0012 不入库；不得把内部源码、构建产物或诊断资料带入仓库 |

`android/app/<上游测试签名材料>`、旧 `<旧签名材料>`、发布日志、APK/AAB、
环境文件、私钥均不在仓库内容中。应用身份与签名身份按 ADR-0013 冻结；旧
旧 keystore 已退役并轮换为仓库外的新 release key（工单 57），
签名材料台账属操作记录，按 ADR-0012 不再保留在仓库内。
