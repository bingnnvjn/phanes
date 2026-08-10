# 25 — fable-session 生产实现（SessionManager + 事件流 + JNI 边界）

**What to build:** 把验证切片升级为生产级 Rust 会话层 crate：SessionManager（创建/关闭/列表/生命周期，2–8 并发设计余量）、Session（PTY 读写、resize、进程退出捕获与回收）、环境注入（环境快照由 Kotlin 计算传入，Rust 不重实现 AndroidShellEnvironment）、事件流第一版最小集六事件（command_started / output_chunk / command_finished / exit_code / session_created / session_closed，schema 含 session_id/时间戳/扩展 metadata）、JNI 边界（SessionHandle 创建/读写/resize/close + 事件回调 + 诊断日志订阅）、Rust 单测（PTY 行为/事件序列/并发）。

**Blocked by:** fable-v1/24
Status: 待开工

## 验收清单

- [ ] SessionManager API：create/close/list/get（行为驱动，TDD 红绿一片）
- [ ] 事件流：六事件序列断言（session_created → command_started → output_chunk* → command_finished → exit_code → session_closed）；schema 含扩展点（metadata）
- [ ] 并发：8 会话本机/offscreen 压测不崩、输出独立（2–4 硬指标、8 余量数据记录）
- [ ] 进程生命周期：退出码捕获、僵尸回收、close/kill 语义
- [ ] JNI：SessionHandle 创建/读写/resize/close/事件回调可从 Kotlin 调用；诊断日志订阅生效
- [ ] 全量单测 + 类型检查通过
- [ ] 结论写回 Comments：事件流 schema 定稿、JNI 边界、坑、对 fable-v1/26 的输入

## Comments

2026-08-10 建单（决策窗口 8：会话层搬 Rust，ADR-0008）。
