# 04 — 行为、性能、内存对照与 go/no-go 规则

Type: wayfinder:ticket
Label: wayfinder:grilling
Mode: HITL
Parent: [Wayfinder Map — `libghostty-rs` 与 `awesome-libghostty` 采用决策路线](../libghostty-rs-awesome-map.md)
Blocked by:
- [02 — `libghostty-rs` 核心对象的 owner thread 与 Fable snapshot 边界](02-owner-thread与snapshot边界.md)
- [03 — Android 构建、版本冻结与安全 API 闸门](03-Android构建版本冻结与安全API闸门.md)
Status: 待开工
Assignee: None

## Question

如何证明 wrapper 适配层相对当前手写 C FFI 是“可接受的候选”，而不是只证明它能编译？

需要锁定对照协议与失败阈值，至少覆盖：

- `vt_write`、effects/write-back、resize、scrollback、选择/输入编码；
- `RenderState` begin/end、global/row dirty、RowIterator/CellIterator；
- owned snapshot 的分配量、JNI 调用次数、帧构建耗时、输出吞吐与多会话余量；
- Android 真机崩溃/ANR/UB/内存错误，以及与现有 C FFI 的行为差异；
- 任一门槛失败时，是回退到 C FFI、缩小 API 子集，还是暂停 spike。

推荐答案：行为必须与现有 C FFI 等价；性能/分配只允许在预先记录的预算内变化；任何 sanitizer/真机安全失败都直接 no-go，不以“功能能跑”抵销。

## Comments

