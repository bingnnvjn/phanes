# Third-party sources

| Component | Location | Source / revision | License evidence |
| --- | --- | --- | --- |
| portable-pty 0.9.0 | `vendor/portable-pty/` | WezTerm revision recorded in root `supply-chain-exceptions.toml` | `vendor/portable-pty/LICENSE.md` (MIT) plus the root provenance and content digest |

Cargo registry dependencies are governed by the root `deny.toml` and
`supply-chain-exceptions.toml`; the root gate must run against this crate's
independent lockfile.

The vendor tree is a reviewed local patch, not an unreviewed dependency
exception. Changes require a new provenance/content-digest review.
