# 02 — `libghostty-rs` 核心对象的 owner thread 与 Fable snapshot 边界

Type: wayfinder:ticket
Label: wayfinder:grilling
Mode: HITL
Parent: [Wayfinder Map — `libghostty-rs` 与 `awesome-libghostty` 采用决策路线](../libghostty-rs-awesome-map.md)
Blocked by: [01 — 是否启动隔离 `libghostty-rs` spike，以及 spike 的禁止事项](01-是否启动隔离-libghostty-rs-spike.md)
Status: 待开工
Assignee: None

## Question

若隔离 spike 获准，如何把上游 `!Send + !Sync` 的 `Terminal`/`RenderState` 安全地接入 Fable？

需要决定：

- 核心对象是否固定在单一 session/core owner thread；
- PTY 字节、effects/write-back、resize、`RenderState` 两阶段 update 的顺序；
- mailbox 跨线程只传哪些 owned 值，是否允许增量脏行与完整快照并存；
- Kotlin/JNI 是否只暴露 Fable 自有 handle/DTO，如何禁止上游 wrapper 类型泄漏。

推荐答案：专用 owner thread 持有上游对象；更新结束后只发布 Fable 自有 owned snapshot，跨线程不传 Ghostty 指针、不共享 wrapper 类型。

## Comments

