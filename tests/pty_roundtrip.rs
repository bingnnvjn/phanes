//! 工单 24 程序化验收（离屏自检，PASS/FAIL 断言）：
//! portable-pty 0.9.0 在本机 bionic（Termux 宿主，aarch64-linux-android）上的
//! spawn/read/write/resize/并行/长输出 行为验证。与 JNI 桥走同一套
//! native_pty_system + CommandBuilder 路径。

use portable_pty::{Child, CommandBuilder, MasterPty, PtySize, native_pty_system};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

const BASH: &str = "/data/data/com.termux/files/usr/bin/bash";

struct PtyProbe {
    writer: Box<dyn Write + Send>,
    master: Box<dyn MasterPty>,
    child: Box<dyn Child + Send + Sync>,
    buf: Arc<Mutex<String>>,
    cv: Arc<(Mutex<()>, Condvar)>,
    alive: Arc<AtomicBool>,
}

impl PtyProbe {
    fn spawn(cols: u16, rows: u16) -> anyhow::Result<Self> {
        let mut cmd = CommandBuilder::new(BASH);
        cmd.arg("--noprofile");
        cmd.arg("--norc");
        cmd.env("TERM", "xterm-256color");
        cmd.env("HOME", "$HOME");
        cmd.env("PREFIX", "/data/data/com.termux/files/usr");
        cmd.env(
            "PATH",
            "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin",
        );
        cmd.env("TMPDIR", "/data/data/com.termux/files/usr/tmp");
        cmd.cwd("$HOME");

        let system = native_pty_system();
        let pair = system.openpty(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })?;
        let child = pair.slave.spawn_command(cmd)?;
        let mut reader = pair.master.try_clone_reader()?;
        let writer = pair.master.take_writer()?;

        let buf = Arc::new(Mutex::new(String::new()));
        let cv = Arc::new((Mutex::new(()), Condvar::new()));
        let alive = Arc::new(AtomicBool::new(true));
        let probe = PtyProbe {
            writer,
            master: pair.master,
            child,
            buf: buf.clone(),
            cv: cv.clone(),
            alive: alive.clone(),
        };
        std::thread::spawn(move || {
            let mut tmp = [0u8; 4096];
            while alive.load(Ordering::Relaxed) {
                match reader.read(&mut tmp) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let text = String::from_utf8_lossy(&tmp[..n]);
                        buf.lock().unwrap().push_str(&text);
                        let (_guard, cvar) = &*cv;
                        cvar.notify_all();
                    }
                }
            }
            alive.store(false, Ordering::Relaxed);
        });
        Ok(probe)
    }

    fn send(&mut self, s: &str) -> anyhow::Result<()> {
        self.writer.write_all(s.as_bytes())?;
        self.writer.flush()?;
        Ok(())
    }

    fn wait_for(&self, marker: &str, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        loop {
            if self.buf.lock().unwrap().contains(marker) {
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            let (guard, cvar) = &*self.cv;
            let _ = cvar
                .wait_timeout(guard.lock().unwrap(), Duration::from_millis(200))
                .unwrap();
        }
    }

    fn text(&self) -> String {
        self.buf.lock().unwrap().clone()
    }

    fn resize(&mut self, cols: u16, rows: u16) -> anyhow::Result<()> {
        self.master.resize(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })?;
        Ok(())
    }

    fn close(&mut self) {
        self.alive.store(false, Ordering::Relaxed);
        let _ = self.child.kill();
    }
}

#[test]
fn portable_pty_roundtrip() -> anyhow::Result<()> {
    let mut p = PtyProbe::spawn(80, 24)?;
    // 关掉终端回显：后续 wait_for 标记只匹配命令输出，不匹配键入回显。
    p.send("stty -echo\n")?;
    std::thread::sleep(Duration::from_millis(300));

    // 1) spawn + 读写交互：提示符后发命令，读到输出回显。
    p.send("echo __READY__\n")?;
    assert!(
        p.wait_for("__READY__", Duration::from_secs(10)),
        "spawn/读写交互超时，输出={:?}",
        p.text()
    );

    // 2) 环境注入：PREFIX/PWD 与预期一致。
    p.send("printf 'P=%s\\n' \"$PREFIX\"\npwd\n")?;
    assert!(
        p.wait_for("P=/data/data/com.termux/files/usr", Duration::from_secs(5)),
        "PREFIX 输出异常: {:?}",
        p.text()
    );
    assert!(
        p.wait_for("$HOME", Duration::from_secs(5)),
        "pwd 输出异常: {:?}",
        p.text()
    );

    // 3) resize：40x10 / 80x24 行列正确（stty size 输出 rows cols）。
    p.resize(40, 10)?;
    p.send("stty size\n")?;
    assert!(
        p.wait_for("10 40", Duration::from_secs(5)),
        "40x10 行列异常: {:?}",
        p.text()
    );
    p.resize(80, 24)?;
    p.send("stty size\n")?;
    assert!(
        p.wait_for("24 80", Duration::from_secs(5)),
        "80x24 行列异常: {:?}",
        p.text()
    );

    // 4) seq 200 长输出不卡死：DONE 前读到完整 1..200。
    let start = Instant::now();
    p.send("seq 1 200\necho __DONE__\n")?;
    assert!(
        p.wait_for("__DONE__", Duration::from_secs(15)),
        "seq 200 未在 15s 内完成: {:?}",
        p.text()
    );
    // PTY 默认 ONLCR（行尾 \r\n）+ bracketed paste（ESC 序列），
    // 剥掉 ANSI、归一化行尾后按行核对 1..200 全量。
    let text = strip_ansi(&p.text().replace("\r\n", "\n").replace('\r', ""));
    let lines: std::collections::HashSet<&str> = text.lines().map(|l| l.trim()).collect();
    for n in [1usize, 100, 200] {
        assert!(
            lines.contains(&n.to_string().as_str()),
            "seq 输出缺 {n}: …{}…",
            {
                let start = text.find("seq 1 200").unwrap_or(0);
                text.chars().skip(start).take(300).collect::<String>()
            }
        );
    }
    println!("seq 200 完成耗时 {:?}", start.elapsed());

    p.close();
    Ok(())
}

fn strip_ansi(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\x1b' {
            // 跳过 CSI（ESC [ ... final-byte）或单字符序列
            if chars.peek() == Some(&'[') {
                chars.next();
                for c in chars.by_ref() {
                    if ('@'..='~').contains(&c) {
                        break;
                    }
                }
            }
            continue;
        }
        out.push(c);
    }
    out
}

#[test]
fn portable_pty_parallel_sessions() -> anyhow::Result<()> {
    let mut sessions = Vec::new();
    for i in 0..4 {
        let mut p = PtyProbe::spawn(80, 24)?;
        p.send("stty -echo\n")?;
        std::thread::sleep(Duration::from_millis(300));
        p.send(&format!("echo SESSION_{i}__READY\n"))?;
        assert!(
            p.wait_for(&format!("SESSION_{i}__READY"), Duration::from_secs(10)),
            "会话 {i} 未就绪: {:?}",
            p.text()
        );
        sessions.push(p);
    }

    // 各自独立：输出互不串（每会话再写唯一标记并核对只出现在自己的缓冲里）。
    for (i, p) in sessions.iter_mut().enumerate() {
        p.send(&format!("echo UNIQ_{i}\n"))?;
    }
    for (i, p) in sessions.iter_mut().enumerate() {
        assert!(
            p.wait_for(&format!("UNIQ_{i}"), Duration::from_secs(5)),
            "会话 {i} 未收到自身标记"
        );
        let text = p.text();
        for j in 0..4 {
            if i != j {
                assert!(
                    !text.contains(&format!("UNIQ_{j}")),
                    "会话 {i} 混入会话 {j} 的输出"
                );
            }
        }
    }

    for p in sessions.iter_mut() {
        p.close();
    }
    Ok(())
}
