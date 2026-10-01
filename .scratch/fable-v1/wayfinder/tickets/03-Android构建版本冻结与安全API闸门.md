# 03 — Android 构建、版本冻结与安全 API 闸门

Type: wayfinder:ticket
Label: wayfinder:grilling
Mode: HITL
Parent: [Wayfinder Map — `libghostty-rs` 与 `awesome-libghostty` 采用决策路线](../libghostty-rs-awesome-map.md)
Blocked by: [01 — 是否启动隔离 `libghostty-rs` spike，以及 spike 的禁止事项](01-是否启动隔离-libghostty-rs-spike.md)
Status: 待开工
Assignee: None

## Question

为 spike 锁定一套可复核的 Android 构建与安全门槛：

- 精确固定哪个 Git SHA、Ghostty source pin、Rust/Zig/NDK/Gradle 组合；
- `aarch64-linux-android` 是否为硬门槛，是否同时覆盖 `x86_64-linux-android` 或模拟器；
- 静态链接、TLS 对齐、Android 15+ 16KB page、JNI 打包和 loader 检查如何判定通过；
- 上游 soundness Issue #70（`graphemes_buf` 容量）与 #75（剪贴板空指针/二进制 UTF-8）采用禁用、局部封装、等待上游修复还是本地补丁；
- 许可证元数据声明 `MIT OR Apache-2.0` 与仓库可见 MIT 文件不一致时，Fable 采用哪条归档路径。

推荐答案：固定调研时 SHA `a28e4ad00d6ba79b98b8cac651ed8976d8500903`，以 arm64 真机构建/加载/16KB page 为硬门槛；在 #70/#75 闭环前禁用相关 API，不把“target 能映射”当作 Android 可用证明。

## Comments

