# Wayfinder Map — `libghostty-rs` 与 `awesome-libghostty` 采用决策路线

Type: wayfinder:map
Label: wayfinder:map
Status: 进行中
Assignee: None
Created: 2026-08-17
Source: [调研：`libghostty-rs` 与 `awesome-libghostty` 对 Fable 的价值](../research-libghostty-rs-awesome.md)

## Destination

形成一份可交给 `/to-spec` 与 `/to-tickets` 的决策包：明确 Fable 是否、在什么门槛下以固定版本隔离引入 `libghostty-rs` 作为核心适配层，并明确 `awesome-libghostty` 的参考与持续扫描制度。地图完成前不改变当前生产 C FFI、Rust `portable-pty`、Rust/wgpu 画法层或 Kotlin 壳。

## Notes

- 领域：Fable 核心接线、渲染状态/快照层、Android 构建与原生安全、生态参考。
- 事实源：调研文件（2026-08-17 核实）、`CONTEXT.md`、ADR-0003、ADR-0004、ADR-0008，以及相关已完成工单。
- 词汇遵循 `CONTEXT.md`：核心、渲染状态、快照层、画法层、会话层、CoreAdapter。
- 本地 Markdown tracker 没有原生 issue dependency/assignment API；本地图用 `Parent`、`Blocked by`、`Assignee` 和 `Status` 元数据表达父子、阻塞、认领与开闭。frontier = 本目录下 `Status: 待开工`、`Assignee: None` 且所有 `Blocked by` 票为 `Status: 已完成` 的子票。
- HITL 决策票解析时必须使用 `/grilling` 与 `/domain-modeling`；只有出现新的外部事实缺口时才新增/使用 `/research` 票。
- 这是规划地图，不是实施清单。任何 spike 只在决策闭合后另行进入 `/to-spec` 与 `/to-tickets`。
- 研究中的上游版本、Issue 状态、Android 构建能力等均带核实日期；进入实施前仍需按信息新鲜度规则复核。

## Decisions so far

<!-- 关闭票据后只在这里追加一行摘要；详细答案留在票据的 resolution comment。 -->

## Not yet specified

- 隔离 spike 确认后，最终的 Fable owned snapshot 字段、脏行编码和 mailbox 背压形态应由 API 实测结果再定。
- 除已知 `aarch64-linux-android` 主路径外，是否纳入 x86_64、模拟器、不同 NDK/AGP 组合，取决于构建闸门票的目标定义。
- `libghostty-rs` 上游修复、Fable 本地补丁与长期维护责任的分界，需在安全票和行为票完成后才可精确描述。
- `awesome-libghostty` 初始重点项目之外的长期 watchlist 范围，随扫描制度决策再毕业。

## Out of scope

- 在本地图内把 `libghostty-rs` 加入生产依赖，替换已经验收的手写 C FFI，或改动生产 ABI。
- 替换 Fable 会话层（`portable-pty`）、Kotlin `SessionHandle/CoreAdapter` API、Rust/wgpu 画法层或 Android 壳。
- 把 `awesome-libghostty` 当作依赖、供应链来源、许可证背书或成熟度认证。
- 直接搬入清单项目的桌面/Web/Flutter/Compose 壳、渲染器或未核验代码。
- 处理完整 `libghostty` App/renderer 层、WebView 路线或与本地图目的无关的核心替换。
