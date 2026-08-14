//! 工单 25 程序化验收（离屏自检，PASS/FAIL 断言）：
//! 生产级会话层核心（SessionManager / Session / 事件流）行为验证。
//! 与本机 bionic（Termux 宿主）跑 portable-pty，与 JNI 桥走同一套
//! native_pty_system + CommandBuilder 路径。
//!
//! 覆盖验收项：SessionManager API（create/close/list/get）、六事件序列断言、
//! 8 会话并发余量（2–4 硬指标内含）、进程生命周期（退出码/回收/close/kill）。

use fable_session::{EventKind, SessionConfig, SessionManager};
use std::time::{Duration, Instant};

const BASH: &str = "/data/data/com.termux/files/usr/bin/bash";

fn base_cfg() -> SessionConfig {
    SessionConfig {
        shell: BASH.to_string(),
        // args[0] 是 argv0 名（与 Java createSubprocess 同语义）；用非登录
        // argv0 "bash" + --noprofile/--norc，保证测试进程 hermetic（不读 profile）。
        args: vec!["bash".into(), "--noprofile".into(), "--norc".into()],
        env: vec![
            ("TERM".into(), "xterm-256color".into()),
            ("HOME".into(), "$HOME".into()),
            ("PREFIX".into(), "/data/data/com.termux/files/usr".into()),
            (
                "PATH".into(),
                "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin".into(),
            ),
        ],
        cwd: Some("$HOME".into()),
        cols: 80,
        rows: 24,
        event_capacity: 4096,
        ..SessionConfig::default()
    }
}

/// 从会话事件流收事件直到出现目标 kind（含），或超时。返回收到的全部事件。
fn collect_until(
    s: &fable_session::Session,
    target: EventKind,
    timeout: Duration,
) -> Vec<fable_session::Event> {
    let deadline = Instant::now() + timeout;
    let mut out = Vec::new();
    loop {
        if let Ok(ev) = s.try_recv_event() {
            let done = ev.kind == target;
            out.push(ev);
            if done {
                return out;
            }
        } else if Instant::now() >= deadline {
            panic!(
                "等待事件 {:?} 超时（{}ms），已收: {:?}",
                target,
                timeout.as_millis(),
                out.iter().map(|e| e.kind).collect::<Vec<_>>()
            );
        } else {
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}

fn all_output(events: &[fable_session::Event]) -> Vec<u8> {
    let mut out = Vec::new();
    for ev in events {
        if ev.kind == EventKind::OutputChunk {
            if let Some(bytes) = &ev.meta.bytes {
                out.extend_from_slice(bytes);
            }
        }
    }
    out
}

fn text_of(events: &[fable_session::Event]) -> String {
    String::from_utf8_lossy(&all_output(events)).into_owned()
}

/// 验收项 1：SessionManager API create/close/list/get（行为驱动）。
#[test]
fn manager_create_close_list_get() {
    let m = SessionManager::new();
    let id = m.create(base_cfg()).unwrap();

    let listed = m.list();
    assert_eq!(listed.len(), 1, "create 后 list 应有 1 条");
    assert_eq!(listed[0].id, id);
    assert!(matches!(
        listed[0].state,
        fable_session::SessionState::Running
    ));

    let s = m.get(id).expect("get(id) 应返回会话");
    assert_eq!(s.id(), id);

    // 首两个事件：session_created → command_started（create 同步发出）
    let e1 = s.try_recv_event().expect("应有 session_created");
    assert_eq!(e1.kind, EventKind::SessionCreated);
    let e2 = s.try_recv_event().expect("应有 command_started");
    assert_eq!(e2.kind, EventKind::CommandStarted);
    assert_eq!(e1.session_id, id);
    assert_eq!(e2.session_id, id);

    m.close(id).unwrap();
    assert!(m.list().is_empty(), "close 后 list 应为空");
    assert!(m.get(id).is_none(), "close 后 get 应无");

    // close 未收到也不 panic：直接结束（会话已由 close 回收）
}

/// 验收项 2：六事件序列断言 + schema（session_id/时间戳/metadata 扩展点）。
#[test]
fn six_event_sequence_with_exit_code() {
    let m = SessionManager::new();
    let mut cfg = base_cfg();
    cfg.args = vec![
        "bash".into(),
        "--noprofile".into(),
        "--norc".into(),
        "-c".into(),
        "echo __SEQ__; exit 7".into(),
    ];
    let id = m.create(cfg).unwrap();
    let s = m.get(id).unwrap();

    // 事件流是异步的：先等到 exit_code（其前必有 command_finished）。
    let events = collect_until(&s, EventKind::ExitCode, Duration::from_secs(10));
    let kinds: Vec<EventKind> = events.iter().map(|e| e.kind).collect();

    assert_eq!(
        &kinds[..2],
        &[EventKind::SessionCreated, EventKind::CommandStarted],
        "事件流必须以 session_created → command_started 开头"
    );
    assert!(
        kinds.contains(&EventKind::OutputChunk),
        "echo 输出应产生 output_chunk，实际: {kinds:?}"
    );
    let text = text_of(&events);
    assert!(
        text.contains("__SEQ__"),
        "output_chunk 字节应含命令输出，实际: {text:?}"
    );
    assert_eq!(
        &kinds[kinds.len() - 2..],
        &[EventKind::CommandFinished, EventKind::ExitCode],
        "command_finished 必须先于 exit_code，实际: {kinds:?}"
    );
    assert_eq!(
        events.last().unwrap().meta.exit_code,
        Some(7),
        "exit_code 事件应携带退出码 7，实际: {:?}",
        events.last().unwrap().meta
    );

    // 自然退出闭环：子进程退出后 session_closed 必须自行送达（无需 close()）。
    let closed = collect_until(&s, EventKind::SessionClosed, Duration::from_secs(5));
    assert_eq!(closed.last().unwrap().kind, EventKind::SessionClosed);
    assert!(
        m.get(id).is_some(),
        "自然退出后句柄仍应存在（由 Kotlin sessionClose 释放）"
    );

    // close() 幂等：状态已 Closed，不重复发 session_closed、不报错。
    m.close(id).unwrap();
    assert!(m.list().is_empty());

    // schema：session_id 一致、时间戳单调不减、metadata 有值
    let mut all = events.clone();
    all.extend(closed);
    for ev in &all {
        assert_eq!(ev.session_id, id, "所有事件 session_id 必须一致");
        assert!(ev.timestamp_ms > 0, "时间戳必须非零");
    }
    for w in all.windows(2) {
        assert!(
            w[0].timestamp_ms <= w[1].timestamp_ms,
            "时间戳必须单调不减: {:?}",
            all.iter().map(|e| e.timestamp_ms).collect::<Vec<_>>()
        );
    }
}

/// 验收项 2 补充：事件 kind 的 schema 名称定稿。
#[test]
fn event_kind_schema_names() {
    assert_eq!(EventKind::SessionCreated.as_str(), "session_created");
    assert_eq!(EventKind::CommandStarted.as_str(), "command_started");
    assert_eq!(EventKind::OutputChunk.as_str(), "output_chunk");
    assert_eq!(EventKind::CommandFinished.as_str(), "command_finished");
    assert_eq!(EventKind::ExitCode.as_str(), "exit_code");
    assert_eq!(EventKind::SessionClosed.as_str(), "session_closed");
}

/// 验收项 3：8 会话并发余量（2–4 硬指标内含）——不崩、输出独立、超时不卡。
#[test]
fn eight_sessions_concurrent_independent() {
    let m = SessionManager::new();
    let start = Instant::now();
    let mut sessions = Vec::new();
    for i in 0..8usize {
        let id = m.create(base_cfg()).unwrap();
        let s = m.get(id).unwrap();
        s.write(format!("stty -echo\necho UNIQ_{i}__READY\n").as_bytes())
            .unwrap();
        sessions.push((i, id, s));
    }

    for (i, _id, s) in &sessions {
        let events = collect_until(s, EventKind::OutputChunk, Duration::from_secs(15));
        // 标记可能跨 chunk：把 output_chunk 字节拼起来看
        let deadline = Instant::now() + Duration::from_secs(15);
        let mut all = events;
        while !text_of(&all).contains(&format!("UNIQ_{i}__READY")) {
            if Instant::now() >= deadline {
                panic!("会话 {i} 未收到自身标记，输出: {:?}", text_of(&all));
            }
            if let Ok(ev) = s.try_recv_event() {
                all.push(ev);
            } else {
                std::thread::sleep(Duration::from_millis(20));
            }
        }
        let text = text_of(&all);
        for (j, _id2, _s2) in &sessions {
            if *j != *i {
                assert!(
                    !text.contains(&format!("UNIQ_{j}__READY")),
                    "会话 {i} 混入会话 {j} 的输出"
                );
            }
        }
    }

    for (_i, id, _s) in &sessions {
        m.close(*id).unwrap();
    }
    println!("8 会话并发压测完成，耗时 {:?}", start.elapsed());
    assert!(m.list().is_empty());
}

/// 验收项 4：进程生命周期——退出码捕获、僵尸回收（wait 已消费）、状态流转。
#[test]
fn exit_code_captured_and_reaped() {
    let m = SessionManager::new();
    let mut cfg = base_cfg();
    cfg.args = vec![
        "bash".into(),
        "--noprofile".into(),
        "--norc".into(),
        "-c".into(),
        "exit 3".into(),
    ];
    let id = m.create(cfg).unwrap();
    let s = m.get(id).unwrap();

    let events = collect_until(&s, EventKind::ExitCode, Duration::from_secs(10));
    assert_eq!(
        events.last().unwrap().meta.exit_code,
        Some(3),
        "exit 3 应捕获退出码 3"
    );

    // 状态流转：Exited{code:3} 或已自然退出闭环的 Closed（reader 可能已推进）。
    let info = m.get(id).unwrap().info();
    assert!(
        matches!(
            info.state,
            fable_session::SessionState::Exited {
                exit_code: Some(3),
                ..
            } | fable_session::SessionState::Closed
        ),
        "状态应为 Exited{{exit_code:Some(3)}} 或 Closed，实际: {:?}",
        info.state
    );

    m.close(id).unwrap();
    assert!(m.list().is_empty());
}

/// 验收项 4：close/kill 语义——对运行中进程 close 应立即回收，不卡死。
#[test]
fn close_kills_running_process() {
    let m = SessionManager::new();
    let mut cfg = base_cfg();
    cfg.args = vec![
        "bash".into(),
        "--noprofile".into(),
        "--norc".into(),
        "-c".into(),
        "sleep 30".into(),
    ];
    let id = m.create(cfg).unwrap();
    let s = m.get(id).unwrap();

    let t0 = Instant::now();
    m.close(id).unwrap();
    let elapsed = t0.elapsed();
    assert!(
        elapsed < Duration::from_secs(5),
        "close 运行中的会话应在 5s 内回收，实际 {elapsed:?}"
    );

    let closed = collect_until(&s, EventKind::SessionClosed, Duration::from_secs(5));
    assert_eq!(closed.last().unwrap().kind, EventKind::SessionClosed);
    assert!(m.list().is_empty());
}

/// 验收项 2/4 补充：读写 + resize 行为（40x10/80x24 行列正确）。
#[test]
fn write_resize_roundtrip() {
    let m = SessionManager::new();
    let id = m.create(base_cfg()).unwrap();
    let s = m.get(id).unwrap();

    s.write(b"stty -echo\n").unwrap();
    std::thread::sleep(Duration::from_millis(300));
    s.resize(40, 10).unwrap();
    s.write(b"stty size\necho __DONE__\n").unwrap();

    let deadline = Instant::now() + Duration::from_secs(10);
    let mut events = Vec::new();
    while !text_of(&events).contains("__DONE__") {
        if Instant::now() >= deadline {
            panic!("resize 交互超时，输出: {:?}", text_of(&events));
        }
        if let Ok(ev) = s.try_recv_event() {
            events.push(ev);
        } else {
            std::thread::sleep(Duration::from_millis(20));
        }
    }
    let text = text_of(&events);
    assert!(
        text.contains("10 40"),
        "40x10 行列应输出 '10 40'，实际: {text:?}"
    );

    s.resize(80, 24).unwrap();
    s.write(b"stty size\necho __DONE2__\n").unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    while !text_of(&events).contains("__DONE2__") {
        if Instant::now() >= deadline {
            panic!("二次 resize 超时，输出: {:?}", text_of(&events));
        }
        if let Ok(ev) = s.try_recv_event() {
            events.push(ev);
        } else {
            std::thread::sleep(Duration::from_millis(20));
        }
    }
    assert!(
        text_of(&events).contains("24 80"),
        "80x24 行列应输出 '24 80'，实际: {:?}",
        text_of(&events)
    );

    m.close(id).unwrap();
}

/// 验收项 5 核心侧：只读输出缓冲（JNI sessionRead 的数据源）与事件流同源。
#[test]
fn read_output_buffer_matches_event_stream() {
    let m = SessionManager::new();
    let id = m.create(base_cfg()).unwrap();
    let s = m.get(id).unwrap();

    s.write(b"stty -echo\necho __BUF__\n").unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut events = Vec::new();
    while !text_of(&events).contains("__BUF__") {
        if Instant::now() >= deadline {
            panic!("等待 __BUF__ 超时: {:?}", text_of(&events));
        }
        if let Ok(ev) = s.try_recv_event() {
            events.push(ev);
        } else {
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    let mut buf = [0u8; 8192];
    let n = s.read_output(&mut buf).unwrap();
    let text = String::from_utf8_lossy(&buf[..n]).into_owned();
    assert!(
        text.contains("__BUF__"),
        "read_output 应能读到与事件流同源的输出，实际: {text:?}"
    );

    m.close(id).unwrap();
}

/// 工单 44：4 会话 × 450,000 行 = 1,800,000 行。
/// 直接验证 reader → 有界事件流在持续输出下保序、零丢失；Android 主线程 batch
/// 由 MainThreadEventDispatcher JVM 契约另行覆盖，真机 ANR/分配数据走 baseline 协议。
#[test]
fn four_sessions_deliver_1_8m_lines_without_loss() {
    const SESSIONS: usize = 4;
    const LINES_PER_SESSION: usize = 450_000;

    let m = SessionManager::new();
    let start = Instant::now();
    let mut sessions = Vec::new();
    for _ in 0..SESSIONS {
        let mut cfg = base_cfg();
        cfg.args = vec![
            "bash".into(),
            "--noprofile".into(),
            "--norc".into(),
            "-c".into(),
            format!("seq 1 {LINES_PER_SESSION}"),
        ];
        let id = m.create(cfg).unwrap();
        sessions.push((id, m.get(id).unwrap(), Vec::new(), false));
    }

    let deadline = Instant::now() + Duration::from_secs(60);
    while sessions.iter().any(|(_, _, _, complete)| !complete) {
        if Instant::now() >= deadline {
            panic!("4 会话 180 万行输出超时");
        }
        let mut progress = false;
        for (_id, session, events, complete) in &mut sessions {
            if *complete {
                continue;
            }
            if let Ok(event) = session.try_recv_event() {
                progress = true;
                *complete = event.kind == EventKind::SessionClosed;
                events.push(event);
            }
        }
        if !progress {
            std::thread::sleep(Duration::from_millis(1));
        }
    }

    for (id, _session, events, _complete) in &sessions {
        let text = text_of(events).replace("\r\n", "\n").replace('\r', "");
        let mut lines = text.lines();
        for expected in 1..=LINES_PER_SESSION {
            assert_eq!(
                lines.next(),
                Some(expected.to_string().as_str()),
                "会话 {id} 第 {expected} 行不完整或乱序"
            );
        }
        assert_eq!(lines.next(), None, "会话 {id} 有额外输出");
        m.close(*id).unwrap();
    }
    assert!(m.list().is_empty());
    println!(
        "4 会话 {} 行输出完整，耗时 {:?}",
        SESSIONS * LINES_PER_SESSION,
        start.elapsed()
    );
}
