# Contributing

Before a Fable-owned public remote is established, changes are reviewed in
the private engineering repository.

For each change:

1. Keep this crate independent; do not turn it into a Cargo workspace.
2. Preserve `Cargo.lock` and run the root quality and supply-chain gates.
3. For JNI, PTY, process, `unsafe`, or cross-thread shutdown changes, document
   ownership, descriptor lifetime, thread affinity, panic behavior, and
   deterministic failure paths.
4. Keep the vendored `portable-pty` provenance, license, and content digest in
   `supply-chain-exceptions.toml` synchronized with the source tree.
5. Do not add credentials, signing files, generated artifacts, or internal
   `.scratch`/device material.

Public pull-request and security-contact details will be added when the
Fable-owned remote and branch rules are approved.
