# Third-party sources and assets

This inventory is part of the public-release review. Every entry must remain
available in the published tree with its applicable notice.

| Component | Location | Source / revision | License evidence |
| --- | --- | --- | --- |
| FreeType 2.14.3 | `third_party/freetype/` | FreeType source bundle; see `README` | `LICENSE.TXT`, `docs/FTL.TXT`, `docs/GPLv2.TXT`, and component notices |
| Noto Color Emoji | `assets/NotoColorEmoji.ttf` | googlefonts/noto-emoji `Noto-COLRv1.ttf`, SHA-256 recorded in `assets/NOTO-EMOJI-LICENSE.txt` | SIL Open Font License 1.1; see `assets/NOTO-EMOJI-LICENSE.txt` |
| JetBrains Mono Regular 2.304 | `assets/JetBrainsMono-Regular.ttf` | JetBrains Mono release `v2.304`, tag commit `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9`; asset SHA-256 is recorded beside the font | SIL Open Font License 1.1; see `assets/JETBRAINS-MONO-LICENSE.txt` |

Cargo dependencies are governed by the root `deny.toml` and
`supply-chain-exceptions.toml`; the root gate must run against this crate's
independent lockfile.

The asset is kept byte-for-byte with the recorded release and is not modified
by the build. Any replacement must update the release tag, asset digest, and
license notice together.
