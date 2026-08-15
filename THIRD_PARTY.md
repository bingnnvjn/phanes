# Third-party sources and assets

This inventory is part of the public-release review. Every entry must remain
available in the published tree with its applicable notice.

| Component | Location | Source / revision | License evidence |
| --- | --- | --- | --- |
| FreeType 2.14.3 | `third_party/freetype/` | FreeType source bundle; see `README` | `LICENSE.TXT`, `docs/FTL.TXT`, `docs/GPLv2.TXT`, and component notices |
| Noto Color Emoji | `assets/NotoColorEmoji.ttf` | googlefonts/noto-emoji `Noto-COLRv1.ttf`, SHA-256 recorded in `assets/NOTO-EMOJI-LICENSE.txt` | SIL Open Font License 1.1; see `assets/NOTO-EMOJI-LICENSE.txt` |
| JetBrains Mono | `assets/JetBrainsMono-Regular.ttf` | Embedded font used by the renderer | Source revision and license artifact still require verification before publication |

Cargo dependencies are governed by the root `deny.toml` and
`supply-chain-exceptions.toml`; the root gate must run against this crate's
independent lockfile.

The JetBrains Mono row is intentionally a release blocker until the exact
download/source revision and license notice are recorded beside the asset.
