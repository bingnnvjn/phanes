# 01 — 是否启动隔离 `libghostty-rs` spike，以及 spike 的禁止事项

Type: wayfinder:ticket
Label: wayfinder:grilling
Mode: HITL
Parent: [Wayfinder Map — `libghostty-rs` 与 `awesome-libghostty` 采用决策路线](../libghostty-rs-awesome-map.md)
Blocked by: None
Status: 待开工
Assignee: None

## Question

基于调研结论，Fable 是否授权一个**隔离、固定 SHA、只输出自有 owned snapshot/mailbox 数据**的 `libghostty-rs` spike？

需要同时锁定：

- spike 的目标是验证替换手写 C FFI 的可行性，还是只做 API/线程模型侦察；
- 是否明确“不改生产路径、不改 Kotlin API、不改 `portable-pty`、不上游类型穿过 JNI”；
- 失败时的回退边界，以及什么结果足以进入 `/to-spec`。

推荐答案：授权一个独立 spike，但把生产依赖切换、Kotlin API 变化、会话层重写和上游类型暴露明确列为禁止事项。

## Comments

