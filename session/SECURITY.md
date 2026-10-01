# Security policy

`session` crosses Rust, JNI, PTY, process, and concurrent shutdown
boundaries. Treat unsafe, process-launch, descriptor, JNI-lifetime, and
dependency reports as security-sensitive.

Report suspected vulnerabilities privately to the repository maintainers.
Include the affected commit, target/platform, minimal reproduction, and
impact. Do not include tokens, private keys, keystores, device identifiers,
or confidential logs.

Until a Fable-owned public remote and security contact are recorded, this file
is a publication baseline; no public disclosure channel is implied.
