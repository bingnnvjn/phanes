# Contributing

Before a Fable-owned public remote is established, changes are reviewed in
the private engineering repository.

For each change:

1. Keep the crate independent; do not turn it into a Cargo workspace.
2. Preserve `Cargo.lock` and run `cargo fmt --all -- --check`.
3. Run the strict quality and supply-chain gates from the Fable root.
4. Explain behavior changes and add focused tests or examples.
5. Do not add credentials, signing files, generated artifacts, or internal
   `.scratch`/device material.

Public pull-request and security-contact details will be added when the
Fable-owned remote and branch rules are approved.
