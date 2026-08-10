//! fable-session：会话层生产实现（工单 25）。
//!
//! 产出 `libfable-session.so`：
//! - 核心（纯 Rust，可离屏测试）：`SessionManager`（create/close/list/get）、
//!   `Session`（PTY 读写、resize、进程退出捕获与回收）、事件流六事件最小集、
//!   诊断日志订阅；
//! - JNI 边界：SessionHandle 创建/读写/resize/close + 事件回调 + 日志订阅。
//!
//! 环境快照由 Kotlin 侧构造传入（`"KEY=VALUE"` 数组 + cwd），本 crate 不重建
//! termux-shared 环境逻辑（ADR-0008 决策 8）。

pub mod event;
pub mod log;
pub mod manager;
pub mod session;
mod jni;
mod jni_handle;

pub use event::{Event, EventKind, EventMeta, SessionId, now_ms};
pub use manager::SessionManager;
pub use session::{Session, SessionConfig, SessionInfo, SessionState};
