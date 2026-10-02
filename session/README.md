# session

`session` is the Rust session layer for Phanes. It
provides PTY-backed sessions, event delivery, lifecycle management, logging,
and JNI handles for the Android shell.

This crate is intentionally not a Cargo workspace. Build and test it with its
own `Cargo.lock`:

```bash
cargo check --all-targets --all-features --locked
cargo test --all-targets --all-features --locked
```

The crate carries a reviewed local `portable-pty` patch so login-shell
`argv[0]` semantics remain compatible with the existing Java layer. Read
[`THIRD_PARTY.md`](THIRD_PARTY.md) and the root supply-chain ledger before
changing that vendor tree.

JNI, PTY, process, `unsafe`, and cross-thread shutdown changes require the
ownership and lifetime review described in [`CONTRIBUTING.md`](CONTRIBUTING.md).
All five parts live in one repository (ADR-0011); publication is a separate
decision that has not been taken (ADR-0012).
