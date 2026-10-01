//! 事件流第一版最小集六事件（ADR-0008 决策 7，工单 25）。
//!
//! schema：`Event { kind, session_id, timestamp_ms, meta }`；
//! `EventMeta` 是扩展点（metadata），第一版含 bytes/exit_code/signal/message/extra
//! 五个字段，`extra` 为自由键值对，供未来 AI 功能扩展。

use std::collections::BTreeMap;
use std::time::{SystemTime, UNIX_EPOCH};

pub type SessionId = u64;

/// 六事件最小集。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum EventKind {
    SessionCreated,
    CommandStarted,
    OutputChunk,
    CommandFinished,
    ExitCode,
    SessionClosed,
}

impl EventKind {
    /// schema 定稿名称（JNI 回调 / 日志用）。
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::SessionCreated => "session_created",
            Self::CommandStarted => "command_started",
            Self::OutputChunk => "output_chunk",
            Self::CommandFinished => "command_finished",
            Self::ExitCode => "exit_code",
            Self::SessionClosed => "session_closed",
        }
    }
}

/// 事件 metadata：扩展点。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct EventMeta {
    /// output_chunk 的负载字节。
    pub bytes: Option<Vec<u8>>,
    /// exit_code 事件的退出码（信号终止时为 portable-pty 折算码）。
    pub exit_code: Option<i32>,
    /// 信号终止时的信号名（如 "Killed"）。
    pub signal: Option<String>,
    /// 诊断/说明信息（如 command_finished 的原因）。
    pub message: Option<String>,
    /// 自由扩展键值（session_created/command_started 可携带 shell/args 等）。
    pub extra: BTreeMap<String, String>,
}

/// 事件流条目。
#[derive(Debug, Clone, PartialEq)]
pub struct Event {
    pub kind: EventKind,
    pub session_id: SessionId,
    pub timestamp_ms: u64,
    pub meta: EventMeta,
}

pub type EventSender = crossbeam_channel::Sender<Event>;
pub type EventReceiver = crossbeam_channel::Receiver<Event>;

/// 每会话事件队列默认容量（有界，4096 条 ≈ 16MB 输出背压上限）。
pub const DEFAULT_EVENT_CAPACITY: usize = 4096;

pub fn event_channel(capacity: usize) -> (EventSender, EventReceiver) {
    crossbeam_channel::bounded(capacity.max(16))
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
