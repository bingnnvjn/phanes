//! 诊断日志订阅（JNI 边界项）：全局有界通道，Rust 侧 publish，
//! Kotlin 经 `sessionSetLogCallback` 订阅（第一版只接诊断/日志，ADR-0008 决策 7）。

use crate::event::SessionId;
use crate::event::now_ms;
use crossbeam_channel::{Receiver, Sender, bounded};
use std::sync::LazyLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Info,
    Warn,
    Error,
}

impl LogLevel {
    pub fn as_i32(&self) -> i32 {
        match self {
            Self::Info => 0,
            Self::Warn => 1,
            Self::Error => 2,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Info => "info",
            Self::Warn => "warn",
            Self::Error => "error",
        }
    }
}

#[derive(Debug, Clone)]
pub struct LogEntry {
    pub session_id: Option<SessionId>,
    pub level: LogLevel,
    pub message: String,
    pub timestamp_ms: u64,
}

static LOG_CHANNEL: LazyLock<(Sender<LogEntry>, Receiver<LogEntry>)> =
    LazyLock::new(|| bounded(4096));

pub fn subscribe() -> Receiver<LogEntry> {
    LOG_CHANNEL.1.clone()
}

pub fn publish(entry: LogEntry) {
    // 非阻塞：日志满即丢（不影响会话数据路径）。
    let _ = LOG_CHANNEL.0.try_send(entry);
}

pub fn log_info(session_id: Option<SessionId>, message: impl Into<String>) {
    publish(LogEntry {
        session_id,
        level: LogLevel::Info,
        message: message.into(),
        timestamp_ms: now_ms(),
    });
}

pub fn log_warn(session_id: Option<SessionId>, message: impl Into<String>) {
    publish(LogEntry {
        session_id,
        level: LogLevel::Warn,
        message: message.into(),
        timestamp_ms: now_ms(),
    });
}

pub fn log_error(session_id: Option<SessionId>, message: impl Into<String>) {
    publish(LogEntry {
        session_id,
        level: LogLevel::Error,
        message: message.into(),
        timestamp_ms: now_ms(),
    });
}
