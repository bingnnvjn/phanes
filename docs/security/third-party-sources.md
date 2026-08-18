# Fable 第三方来源与许可证台账

这份台账只记录公开 Rust crate 发布边界内实际携带或直接复用的第三方
来源；Android 应用源码仍受 `fable-app` 上游仓库及其许可证边界约束。每次
更换版本、打补丁或重新生成资产时，必须同步更新来源 revision、许可证证据
和摘要，并重新运行公开发布闸门。

| 组件 | 发布输入 | 固定来源 | 许可证/证据 |
| --- | --- | --- | --- |
| Ghostty `+boo` 帧 | `fable-boo/data/frames/` | `ghostty-org/ghostty` commit `05221c11c9db0715666fc6e038915128fc6a563e` | MIT；crate 内 `fable-boo/THIRD_PARTY.md` |
| JetBrains Mono Regular | `spike-render/assets/JetBrainsMono-Regular.ttf` | JetBrains Mono `v2.304`, tag commit `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9` | SIL OFL 1.1；`spike-render/assets/JETBRAINS-MONO-LICENSE.txt`；资产 SHA-256 已固定 |
| Noto Color Emoji | `spike-render/assets/NotoColorEmoji.ttf` | `googlefonts/noto-emoji` `Noto-COLRv1.ttf`, revision `e92753bfa55fd449e427d4d325f9c8c40408c74e` | SIL OFL 1.1；`spike-render/assets/NOTO-EMOJI-LICENSE.txt`；资产 SHA-256 已固定 |
| FreeType | `spike-render/third_party/freetype/` | FreeType `VER-2-14-3`, dereferenced commit `0a0221a1347e2f1e07c395263540026e9a0aa7c7` | FreeType Project License；`LICENSE.TXT`、`docs/FTL.TXT`、`docs/GPLv2.TXT` |
| portable-pty | `spike-session/vendor/portable-pty/` | WezTerm revision `f8921727a11b9f8b073e8c24821d72fd41283500` | MIT；`vendor/portable-pty/LICENSE.md` 与 `supply-chain-exceptions.toml` |
| Termux app host | 不进入三个 Rust crate 发布输入 | 受保护的上游 `termux/termux-app`；本地 `fable-app` 保持原 `origin` | GPLv3-only；`fable-app/LICENSE.md` 及其例外清单；应用签名材料永不进入 Rust 发布树 |
| libghostty-vt / Fable 核心边界 | 仅以接口/构建输入存在于私有应用集成 | Fable 本地 `spike-libghostty` 与 `fable-app` 集成资料，不属于三个 Rust public crate 的 allow-list | 该输入仍受 Fable 私有仓库边界约束；不得把内部源码、构建产物或诊断资料带入公开 crate |

`fable-app/app/<上游测试签名材料>`、`keystore/<旧签名材料>`、发布日志、APK/AAB、
环境文件、私钥和内部 `.scratch`/`.crew` 资料均不在本台账的发布输入中。
签名材料的分类、隔离和历史边界见
`docs/security/signing-material-inventory.md`；在人工确认完成前保持发布阻断。
