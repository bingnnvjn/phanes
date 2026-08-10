//! SessionManager：创建/关闭/列表/获取（2–8 并发设计余量，ADR-0008 决策 6）。

use crate::event::SessionId;
use crate::log::log_info;
use crate::session::{Session, SessionConfig, SessionInfo, spawn_session};
use anyhow::Result;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

pub struct SessionManager {
    sessions: Mutex<HashMap<SessionId, Arc<Session>>>,
    next_id: AtomicU64,
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            next_id: AtomicU64::new(1),
        }
    }

    /// 创建会话：spawn → 注册 → 发 session_created/command_started → 返回句柄。
    pub fn create(&self, cfg: SessionConfig) -> Result<SessionId> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let session = spawn_session(id, &cfg)?;
        if let Ok(mut map) = self.sessions.lock() {
            map.insert(id, session.clone());
        } else {
            session.close();
            anyhow::bail!("SESSIONS 锁中毒");
        }
        session.emit_start_events(&cfg);
        log_info(Some(id), "SessionManager::create 完成");
        Ok(id)
    }

    pub fn get(&self, id: SessionId) -> Option<Arc<Session>> {
        self.sessions
            .lock()
            .ok()
            .and_then(|map| map.get(&id).cloned())
    }

    pub fn list(&self) -> Vec<SessionInfo> {
        self.sessions
            .lock()
            .map(|map| {
                let mut v: Vec<SessionInfo> = map.values().map(|s| s.info()).collect();
                v.sort_by_key(|i| i.id);
                v
            })
            .unwrap_or_default()
    }

    pub fn close(&self, id: SessionId) -> Result<()> {
        let removed = self
            .sessions
            .lock()
            .map(|mut map| map.remove(&id))
            .unwrap_or(None);
        match removed {
            Some(s) => {
                s.close();
                log_info(Some(id), "SessionManager::close 完成");
                Ok(())
            }
            None => anyhow::bail!("会话 {id} 不存在或已关闭"),
        }
    }

    pub fn close_all(&self) {
        let ids: Vec<SessionId> = self
            .sessions
            .lock()
            .map(|map| map.keys().copied().collect())
            .unwrap_or_default();
        for id in ids {
            let _ = self.close(id);
        }
    }
}

impl Drop for SessionManager {
    fn drop(&mut self) {
        self.close_all();
    }
}
