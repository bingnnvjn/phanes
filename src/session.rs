//! 生产级 Session：PTY 生命周期（spawn/close/resize）、进程管理（退出码捕获、
//! 僵尸回收、close/kill 语义）、字节 I/O 与事件流。
//!
//! 线程模型：每会话一个 reader 线程独占 master fd 的 dup 副本阻塞读；
//! 输出字节同时进事件流（output_chunk，主通道）和只读侧缓冲（`read_output`，
//! 供 JNI `sessionRead` 等非事件消费方）。EOF/EIO 后轮询 `try_wait` 回收子进程
//! （不持锁阻塞 wait，可被 close 打断），发 command_finished/exit_code，
//! 随后**自然退出闭环**：发 session_closed → 置 Closed → 关读 fd → 线程退出
//! （旧实现等 close 信号，子进程自然退出后会话永不收尾、Kotlin onExit 不触发——
//! 工单 26 真机输入失效的根因之一）；close() 先置标志、close 读 fd、kill 子进程、
//! join，reader 已发 session_closed 则跳过重复——保证六事件序列顺序。

use crate::event::{
    Event, EventKind, EventMeta, EventReceiver, EventSender, SessionId, event_channel, now_ms,
};
use crate::log::{log_error, log_info, log_warn};
use anyhow::{anyhow, bail, Context, Result};
use crossbeam_channel::SendTimeoutError;
use portable_pty::{Child, CommandBuilder, MasterPty, PtySize, native_pty_system};
use std::collections::VecDeque;
use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// 只读侧缓冲上限（1 MiB），超出丢最旧并记 warn 日志。
pub const DEFAULT_OUTPUT_BUF_CAP: usize = 1 << 20;

#[derive(Debug, Clone)]
pub struct SessionConfig {
    /// 可执行路径（如 /data/data/com.gph.fable/files/usr/bin/bash）。
    pub shell: String,
    /// argv[0] 名 + 真实参数：args[0] = argv0（登录 shell 为 "-bash"，与 Java
    /// createSubprocess / execvp 同语义），args[1..] = 真实参数（如 --noprofile）。
    pub args: Vec<String>,
    /// 环境快照（Kotlin 构造传入，本 crate 不重建 AndroidShellEnvironment）。
    pub env: Vec<(String, String)>,
    pub cwd: Option<String>,
    pub cols: u16,
    pub rows: u16,
    /// 事件队列容量（默认 4096）。
    pub event_capacity: usize,
    /// session_created/command_started 事件的扩展 metadata（自由键值）。
    pub extra: std::collections::BTreeMap<String, String>,
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self {
            shell: String::new(),
            args: Vec::new(),
            env: Vec::new(),
            cwd: None,
            cols: 80,
            rows: 24,
            event_capacity: crate::event::DEFAULT_EVENT_CAPACITY,
            extra: std::collections::BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum SessionState {
    Running,
    Exited {
        exit_code: Option<i32>,
        signal: Option<String>,
        at_ms: u64,
    },
    Closed,
}

#[derive(Debug, Clone)]
pub struct SessionInfo {
    pub id: SessionId,
    pub state: SessionState,
    pub created_at_ms: u64,
    pub cols: u16,
    pub rows: u16,
}

/// reader 线程与 close() 共享的状态（避免 Arc<Session> 循环引用）。
struct ReaderShared {
    id: SessionId,
    read_fd: Mutex<i32>,
    child: Mutex<Option<Box<dyn Child + Send + Sync>>>,
    events_tx: EventSender,
    output_buf: Mutex<VecDeque<u8>>,
    state: Mutex<SessionState>,
    close_flag: AtomicBool,
}

pub struct Session {
    id: SessionId,
    created_at_ms: u64,
    size: Mutex<(u16, u16)>,
    master: Mutex<Box<dyn MasterPty>>,
    writer: Mutex<Box<dyn Write + Send>>,
    reader: Arc<ReaderShared>,
    reader_thread: Mutex<Option<JoinHandle<()>>>,
    events_rx: EventReceiver,
}

impl Session {
    pub fn id(&self) -> SessionId {
        self.id
    }

    pub fn created_at_ms(&self) -> u64 {
        self.created_at_ms
    }

    pub fn info(&self) -> SessionInfo {
        let state = self
            .reader
            .state
            .lock()
            .map(|g| g.clone())
            .unwrap_or_else(|p| p.into_inner().clone());
        let (cols, rows) = *self.size.lock().unwrap_or_else(|p| p.into_inner());
        SessionInfo {
            id: self.id,
            state,
            created_at_ms: self.created_at_ms,
            cols,
            rows,
        }
    }

    pub fn write(&self, data: &[u8]) -> Result<()> {
        let mut writer = self.writer.lock().unwrap_or_else(|p| p.into_inner());
        writer
            .write_all(data)
            .and_then(|_| writer.flush())
            .context("PTY 写失败")
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<()> {
        let size = PtySize {
            rows: rows.max(1),
            cols: cols.max(1),
            pixel_width: 0,
            pixel_height: 0,
        };
        let master = self.master.lock().unwrap_or_else(|p| p.into_inner());
        master.resize(size).context("PTY resize 失败")?;
        *self.size.lock().unwrap_or_else(|p| p.into_inner()) = (size.cols, size.rows);
        Ok(())
    }

    /// 非阻塞取下一个事件（事件流主通道）。
    pub fn try_recv_event(&self) -> std::result::Result<Event, crossbeam_channel::TryRecvError> {
        self.events_rx.try_recv()
    }

    /// 订阅本会话事件流（JNI 事件回调分发线程用）。
    pub fn subscribe(&self) -> EventReceiver {
        self.events_rx.clone()
    }

    /// 非阻塞读取只读侧输出缓冲（JNI `sessionRead` 数据源；与事件流同源）。
    /// 返回写入 buf 的字节数；0 = 暂无输出。
    pub fn read_output(&self, buf: &mut [u8]) -> Result<usize> {
        let mut out = self.reader.output_buf.lock().unwrap_or_else(|p| p.into_inner());
        let take = out.len().min(buf.len());
        for b in buf.iter_mut().take(take) {
            *b = out.pop_front().unwrap_or(0);
        }
        Ok(take)
    }

    /// 关闭会话：置 close 标志 → close 读 fd（解除阻塞读）→ kill 子进程 →
    /// join reader → 状态 Closed → 发 session_closed。幂等。
    pub fn close(&self) {
        {
            let st = self.reader.state.lock().unwrap_or_else(|p| p.into_inner());
            if *st == SessionState::Closed {
                return;
            }
        }
        self.reader.close_flag.store(true, Ordering::SeqCst);

        {
            let mut fd = self.reader.read_fd.lock().unwrap_or_else(|p| p.into_inner());
            if *fd >= 0 {
                unsafe {
                    libc::close(*fd);
                }
                *fd = -1;
            }
        }
        {
            let mut child = self.reader.child.lock().unwrap_or_else(|p| p.into_inner());
            if let Some(c) = child.as_mut() {
                let _ = c.kill();
            }
        }
        if let Some(handle) = self
            .reader_thread
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .take()
        {
            let _ = handle.join();
        }
        // reader 自然退出时已置 Closed 并发过 session_closed，此处跳过重复。
        let already_closed = {
            let mut st = self.reader.state.lock().unwrap_or_else(|p| p.into_inner());
            let was = *st == SessionState::Closed;
            *st = SessionState::Closed;
            was
        };
        if !already_closed {
            send_terminal_event(&self.reader, self.id);
            log_info(Some(self.id), "session_closed 已发出（close 路径），句柄回收完成");
        } else {
            log_info(Some(self.id), "session_closed 已由 reader 自然退出发出，跳过重复");
        }
    }

    /// create 用：内部构造（spawn 成功后）。
    fn new(
        id: SessionId,
        created_at_ms: u64,
        cfg: &SessionConfig,
        pair: portable_pty::PtyPair,
        mut child: Box<dyn Child + Send + Sync>,
        writer: Box<dyn Write + Send>,
    ) -> Result<Self> {
        let master_fd = pair
            .master
            .as_raw_fd()
            .ok_or_else(|| anyhow!("master pty 无 raw fd"))?;
        let read_fd = unsafe { libc::dup(master_fd) };
        if read_fd < 0 {
            let _ = child.kill();
            bail!(
                "dup(master_fd) 失败: {}",
                std::io::Error::last_os_error()
            );
        }
        let (tx, rx) = event_channel(cfg.event_capacity);
        let reader = Arc::new(ReaderShared {
            id,
            read_fd: Mutex::new(read_fd),
            child: Mutex::new(Some(child)),
            events_tx: tx,
            output_buf: Mutex::new(VecDeque::new()),
            state: Mutex::new(SessionState::Running),
            close_flag: AtomicBool::new(false),
        });
        Ok(Self {
            id,
            created_at_ms,
            size: Mutex::new((cfg.cols.max(1), cfg.rows.max(1))),
            master: Mutex::new(pair.master),
            writer: Mutex::new(writer),
            reader,
            reader_thread: Mutex::new(None),
            events_rx: rx,
        })
    }

    fn start_reader(&self) -> Result<()> {
        let shared = self.reader.clone();
        let handle = std::thread::Builder::new()
            .name(format!("fable-session-reader-{}", self.id))
            .spawn(move || reader_loop(shared))
            .context("spawn reader 线程失败")?;
        *self
            .reader_thread
            .lock()
            .unwrap_or_else(|p| p.into_inner()) = Some(handle);
        Ok(())
    }

    /// create 用：发 session_created / command_started（同步，先于 create 返回）。
    pub(crate) fn emit_start_events(&self, cfg: &SessionConfig) {
        let mut created = EventMeta::default();
        created.message = Some(format!("shell={}", cfg.shell));
        created.extra = cfg.extra.clone();
        let _ = self.reader.events_tx.send(Event {
            kind: EventKind::SessionCreated,
            session_id: self.id,
            timestamp_ms: now_ms(),
            meta: created,
        });

        let mut started = EventMeta::default();
        let args = cfg.args.join(" ");
        started.message = Some(if args.is_empty() {
            cfg.shell.clone()
        } else {
            format!("{} {}", cfg.shell, args)
        });
        started.extra = cfg.extra.clone();
        let _ = self.reader.events_tx.send(Event {
            kind: EventKind::CommandStarted,
            session_id: self.id,
            timestamp_ms: now_ms(),
            meta: started,
        });
        log_info(Some(self.id), "session_created / command_started 已发出");
    }
}

/// 构造并启动会话（由 SessionManager::create 调用）。
pub(crate) fn spawn_session(
    id: SessionId,
    cfg: &SessionConfig,
) -> Result<Arc<Session>> {
    if cfg.shell.trim().is_empty() {
        bail!("shell 为空");
    }
    // args[0] 是 argv0 名（Java createSubprocess / execvp 同语义：
    // 登录 shell 为 "-bash"；program 仍是 cfg.shell）。argv0 与真实参数分离，
    // 由本地补丁的 CommandBuilder::argv0 支持（上游 portable-pty 0.9.0 无此 API）。
    let mut cmd = CommandBuilder::new(&cfg.shell);
    if let Some(argv0) = cfg.args.first() {
        cmd.argv0(std::ffi::OsString::from(argv0));
        for a in &cfg.args[1..] {
            cmd.arg(a);
        }
    }
    cmd.set_controlling_tty(true);
    for (k, v) in &cfg.env {
        cmd.env(k, v);
    }
    if let Some(cwd) = &cfg.cwd {
        cmd.cwd(cwd);
    }

    let system = native_pty_system();
    let size = PtySize {
        rows: cfg.rows.max(1),
        cols: cfg.cols.max(1),
        pixel_width: 0,
        pixel_height: 0,
    };
    let pair = system.openpty(size).context("openpty 失败")?;
    // 与 Java create_subprocess（termux.c）一致：IUTF8 + 清 IXON/IXOFF，
    // 否则 Ctrl+S 冻结显示、UTF-8 行规程与 Java 模式不一致（工单 26 真机审查）。
    if let Some(master_fd) = pair.master.as_raw_fd() {
        unsafe {
        let mut tios: libc::termios = std::mem::zeroed();
        if libc::tcgetattr(master_fd, &mut tios) == 0 {
            tios.c_iflag |= libc::IUTF8;
            tios.c_iflag &= !(libc::IXON | libc::IXOFF);
            libc::tcsetattr(master_fd, libc::TCSANOW, &tios);
        }
        }
    }
    let child = pair
        .slave
        .spawn_command(cmd)
        .context("spawn_command 失败")?;
    let writer = pair
        .master
        .take_writer()
        .context("take_writer 失败")?;

    let session = Session::new(id, now_ms(), cfg, pair, child, writer)?;
    if let Err(e) = session.start_reader() {
        // 失败清理：kill 子进程、关读 fd（Session 无 Drop，close 幂等安全）。
        session.close();
        return Err(e);
    }
    Ok(Arc::new(session))
}

fn reader_loop(shared: Arc<ReaderShared>) {
    let id = shared.id;
    log_info(Some(id), "reader 线程启动");
    // 1) 阻塞读 master dup fd；close() close 该 fd 时 read 返回 EIO/0 退出。
    loop {
        let read_fd = *shared.read_fd.lock().unwrap_or_else(|p| p.into_inner());
        if read_fd < 0 || shared.close_flag.load(Ordering::SeqCst) {
            break;
        }
        let mut data = [0u8; 4096];
        let n = unsafe {
            libc::read(
                read_fd,
                data.as_mut_ptr() as *mut libc::c_void,
                data.len(),
            )
        };
        if n > 0 {
            let bytes = data[..n as usize].to_vec();
            push_output(&shared, &bytes);
            let event = Event {
                kind: EventKind::OutputChunk,
                session_id: id,
                timestamp_ms: now_ms(),
                meta: EventMeta {
                    bytes: Some(bytes),
                    ..Default::default()
                },
            };
            if !send_event(&shared, event) {
                break; // 关闭中：停止产出
            }
            continue;
        }
        if n == 0 {
            log_info(Some(id), "PTY EOF（子进程关闭 slave 端）");
            break;
        }
        let err = std::io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        if err.raw_os_error() == Some(libc::EIO) {
            log_info(Some(id), "PTY 读返回 EIO（会话结束）");
            break;
        }
        log_error(Some(id), format!("PTY 读失败: {err}"));
        break;
    }

    // 2) 回收子进程（try_wait 轮询，可被 close 打断），取退出状态。
    let status = reap_child(&shared);
    let exit_code = status.as_ref().map(|s| s.exit_code() as i32);
    let signal = status
        .as_ref()
        .and_then(|s| s.signal().map(|x| x.to_string()));
    {
        let mut st = shared.state.lock().unwrap_or_else(|p| p.into_inner());
        if *st != SessionState::Closed {
            *st = SessionState::Exited {
                exit_code,
                signal: signal.clone(),
                at_ms: now_ms(),
            };
        }
    }
    send_event(
        &shared,
        Event {
            kind: EventKind::CommandFinished,
            session_id: id,
            timestamp_ms: now_ms(),
            meta: EventMeta {
                exit_code,
                signal: signal.clone(),
                message: Some(
                    status
                        .as_ref()
                        .map(|s| s.to_string())
                        .unwrap_or_else(|| "进程已结束（状态未知）".to_string()),
                ),
                ..Default::default()
            },
        },
    );
    send_event(
        &shared,
        Event {
            kind: EventKind::ExitCode,
            session_id: id,
            timestamp_ms: now_ms(),
            meta: EventMeta {
                exit_code,
                signal,
                ..Default::default()
            },
        },
    );
    log_info(Some(id), "command_finished / exit_code 已发出");

    // 3) 自然退出闭环：发 session_closed → 置 Closed → 关读 fd → 线程退出。
    //    （旧实现等 close() 信号：子进程自然退出后会话永不收尾、Kotlin onExit
    //    永不触发、isRunning 卡 true、输入被静默丢弃——工单 26 真机输入失效。）
    send_terminal_event(&shared, id);
    {
        let mut st = shared.state.lock().unwrap_or_else(|p| p.into_inner());
        *st = SessionState::Closed;
    }
    {
        let mut fd = shared.read_fd.lock().unwrap_or_else(|p| p.into_inner());
        if *fd >= 0 {
            unsafe {
                libc::close(*fd);
            }
            *fd = -1;
        }
    }
    shared.close_flag.store(true, Ordering::SeqCst);
    {
        let mut child = shared.child.lock().unwrap_or_else(|p| p.into_inner());
        *child = None;
    }
    log_info(Some(id), "session_closed 已发出（自然退出），reader 线程退出");
}

/// 有界事件发送：关闭中（或通道断开）即停止产出，避免 reader 永久阻塞。
fn send_event(shared: &Arc<ReaderShared>, event: Event) -> bool {
    loop {
        if shared.close_flag.load(Ordering::SeqCst) {
            return false;
        }
        match shared
            .events_tx
            .send_timeout(event.clone(), Duration::from_millis(100))
        {
            Ok(()) => return true,
            Err(SendTimeoutError::Timeout(_)) => continue,
            Err(SendTimeoutError::Disconnected(_)) => return false,
        }
    }
}

/// session_closed 专用送达：不受 close_flag 影响（自然退出闭环必须最终送达）；
/// 通道断开（订阅者全退）则丢弃。
fn send_terminal_event(shared: &Arc<ReaderShared>, id: SessionId) {
    let event = Event {
        kind: EventKind::SessionClosed,
        session_id: id,
        timestamp_ms: now_ms(),
        meta: EventMeta::default(),
    };
    if shared
        .events_tx
        .send_timeout(event, Duration::from_millis(500))
        .is_err()
    {
        log_warn(Some(id), "session_closed 投递失败（通道断开或背压）");
    }
}

fn push_output(shared: &Arc<ReaderShared>, bytes: &[u8]) {
    let mut buf = shared.output_buf.lock().unwrap_or_else(|p| p.into_inner());
    if buf.len() + bytes.len() > DEFAULT_OUTPUT_BUF_CAP {
        let drop = (buf.len() + bytes.len() - DEFAULT_OUTPUT_BUF_CAP).min(buf.len());
        buf.drain(..drop);
        log_warn(
            Some(shared.id),
            format!("read_output 缓冲溢出，丢弃最旧 {drop} 字节"),
        );
    }
    buf.extend(bytes);
}

/// 回收子进程：轮询 try_wait（不持锁阻塞 wait，可被 close 打断）。
fn reap_child(shared: &Arc<ReaderShared>) -> Option<portable_pty::ExitStatus> {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if shared.close_flag.load(Ordering::SeqCst) {
            // 被 close 打断：kill 并给 1s 余量取状态（SIGKILL 生效后 try_wait 立即可见）。
            {
                let mut child = shared.child.lock().unwrap_or_else(|p| p.into_inner());
                if let Some(c) = child.as_mut() {
                    let _ = c.kill();
                }
            }
            let close_deadline = Instant::now() + Duration::from_secs(1);
            loop {
                let mut child = shared.child.lock().unwrap_or_else(|p| p.into_inner());
                if let Some(c) = child.as_mut() {
                    if let Ok(Some(st)) = c.try_wait() {
                        *child = None;
                        return Some(st);
                    }
                }
                drop(child);
                if Instant::now() >= close_deadline {
                    return None;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
        }
        {
            let mut child = shared.child.lock().unwrap_or_else(|p| p.into_inner());
            if let Some(c) = child.as_mut() {
                if let Ok(Some(st)) = c.try_wait() {
                    *child = None;
                    return Some(st);
                }
            }
        }
        if Instant::now() >= deadline {
            log_warn(Some(shared.id), "reap_child 5s 未等到子进程退出");
            return None;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}
