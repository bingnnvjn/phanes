# Third-party sources

| Component | Location | Source / revision | License evidence |
| --- | --- | --- | --- |
| Ghostty `+boo` animation frames | `data/frames/` | `ghostty-org/ghostty`, commit `05221c11c9db0715666fc6e038915128fc6a563e` (2026-08-09) | Ghostty MIT license; only the website frame payload and format are reproduced |

`boo` does not vendor the Ghostty executable or its build system. The
Rust implementation and its build script are Phanes code under the crate MIT
license. Keep this inventory synchronized with `README.md` and the frame
regeneration procedure before changing the embedded payload.
