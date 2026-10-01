# 06 — 决策包到 `/to-spec` 与 `/to-tickets` 的收口边界

Type: wayfinder:ticket
Label: wayfinder:grilling
Mode: HITL
Parent: [Wayfinder Map — `libghostty-rs` 与 `awesome-libghostty` 采用决策路线](../libghostty-rs-awesome-map.md)
Blocked by:
- [04 — 行为、性能、内存对照与 go/no-go 规则](04-行为性能内存对照与go-no-go规则.md)
- [05 — `awesome-libghostty` 参考雷达与借鉴准入](05-awesome-libghostty参考雷达与借鉴准入.md)
Status: 待开工
Assignee: None

## Question

当 spike 入口、线程/快照边界、构建/安全闸门、对照判定和生态雷达都已决定后，哪些内容应正式写入 Fable 的架构/规格与实施工单，哪些仍留在观察记录？

需要锁定：

- 是否新增或重开 ADR，还是只更新现有 ADR/`spec.md`；
- 是否生成一张独立的隔离 spike 工单，以及它的验收边界；
- `awesome-libghostty` 扫描是否需要单独 backlog 工单；
- 哪些事项明确留在 map 的 out-of-scope，不得在实施阶段顺手扩大。

推荐答案：只把已闭合的决策转成一张隔离 spike 工单和一份生态扫描 backlog；不把上游 wrapper 类型、生产切换或无关项目移植写入实施范围。

## Comments

