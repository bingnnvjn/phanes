# Contributing

Changes are reviewed in the private engineering repository. The five parts live
in one repository (ADR-0011); publication is a separate decision that has not
been taken (ADR-0012).

For each change:

1. Keep the crate independent; do not turn it into a Cargo workspace.
2. Preserve `Cargo.lock` and run `cargo fmt --all -- --check`.
3. Run the strict quality and supply-chain gates from the repository root.
4. Explain behavior changes and add focused tests or examples.
5. Do not add credentials, signing files, generated artifacts, or internal
   `.scratch`/device material.

Public pull-request and security-contact details will be added if the repository
is ever made public and branch rules are approved.
