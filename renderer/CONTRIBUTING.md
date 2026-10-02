# Contributing

Changes are reviewed in the private engineering repository. The five parts live
in one repository (ADR-0011); publication is a separate decision that has not
been taken (ADR-0012).

For each change:

1. Keep this crate independent; do not turn it into a Cargo workspace.
2. Preserve `Cargo.lock` and run the root quality and supply-chain gates.
3. For JNI, FreeType, wgpu, native-window, or other `unsafe` changes, include
   the safety invariant, ABI assumptions, ownership/Drop behavior, and
   focused regression coverage.
4. Keep third-party sources, font assets, and their license/provenance records
   synchronized.
5. Do not add credentials, signing files, generated artifacts, or internal
   `.scratch`/device material.

Public pull-request and security-contact details will be added if the repository
is ever made public and branch rules are approved.
