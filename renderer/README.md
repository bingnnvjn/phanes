# renderer

`renderer` is the Rust renderer for Phanes. It exposes a
`cdylib`/`rlib` with JNI and native-FFI boundaries, software/GPU rendering
paths, FreeType-backed font handling, and color-emoji support.

This crate is intentionally not a Cargo workspace. Build and test it with its
own `Cargo.lock`:

```bash
cargo check --all-targets --all-features --locked
cargo test --all-targets --all-features --locked
```

The renderer embeds font assets and a FreeType source tree. Read
[`THIRD_PARTY.md`](THIRD_PARTY.md) and the asset notices before redistributing
anything. JNI, `unsafe`, native-window, and GPU changes require the safety
review described in [`CONTRIBUTING.md`](CONTRIBUTING.md).

The crate is a Phanes integration component, not a standalone Android
application. All five parts live in one repository (ADR-0011); publication is
a separate decision that has not been taken (ADR-0012).
