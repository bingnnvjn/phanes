//! 工单 15 真机回归回路（第二轮，生产路径）：Renderer（mailbox + 渲染线程）+
//! 真实 PTY/bash 下连续 resize，输入行是否被复制。
//!
//! 与 resize_pty_reflow_check 的差别：所有 write/resize 走 Renderer 的 mailbox
//! 与独立渲染线程（生产同构），行内容经 rendererSetSelection + selection_text
//! 逐行读出。验收命令：`cargo run --release --example renderer_resize_reflow_check`。

use fable_render::render_android::Renderer;
use std::ffi::{c_char, c_int};
use std::thread;
use std::time::Duration;

extern "C" {
    fn fable_pty_spawn(shell: *const c_char, cols: c_int, rows: c_int) -> i64;
    fn fable_pty_read(handle: i64, buf: *mut u8, len: usize) -> c_int;
    fn fable_pty_read_ready(handle: i64) -> c_int;
    fn fable_pty_write(handle: i64, data: *const u8, len: usize) -> c_int;
    fn fable_pty_resize(handle: i64, cols: c_int, rows: c_int);
    fn fable_pty_close(handle: i64);
}

fn drain_pty(pty: i64) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buf = [0u8; 4096];
    while unsafe { fable_pty_read_ready(pty) } != 0 {
        let n = unsafe { fable_pty_read(pty, buf.as_mut_ptr(), buf.len()) };
        if n <= 0 {
            break;
        }
        out.extend_from_slice(&buf[..n as usize]);
    }
    out
}

fn dump_rows(renderer: &Renderer, cols: u16, rows: u16) -> Vec<String> {
    // 全选逐行 → selection_text 按行序拼接（跨行 \n），再切回每行文本。
    for r in 0..rows {
        renderer.set_selection(r as u32, 0, cols as u32);
    }
    let text = renderer.selection_text();
    for r in 0..rows {
        renderer.set_selection(r as u32, 0, 0);
    }
    if text.is_empty() {
        return Vec::new();
    }
    let mut lines: Vec<String> = text.split('\n').map(|s| s.trim_end().to_string()).collect();
    while lines.len() > rows as usize {
        lines.pop();
    }
    lines
}

fn main() {
    const LINE: &str = "白日依山尽，黄河入海流";
    const SHELL: &str = "/data/data/com.termux/files/usr/bin/bash";
    let renderer = Renderer::new(60, 24).expect("renderer");

    let shell_c = std::ffi::CString::new(SHELL).unwrap();
    let pty = unsafe { fable_pty_spawn(shell_c.as_ptr(), 60, 24) };
    assert!(pty != 0, "pty spawn 失败");

    thread::sleep(Duration::from_millis(600));
    let banner = drain_pty(pty);
    if !banner.is_empty() {
        renderer.write(&banner);
    }
    thread::sleep(Duration::from_millis(200));
    let extra = drain_pty(pty);
    if !extra.is_empty() {
        renderer.write(&extra);
    }

    let ps1 = "PS1='$ '\r";
    unsafe { fable_pty_write(pty, ps1.as_ptr(), ps1.len()) };
    thread::sleep(Duration::from_millis(300));
    let out = drain_pty(pty);
    if !out.is_empty() {
        renderer.write(&out);
    }
    thread::sleep(Duration::from_millis(150));
    let out2 = drain_pty(pty);
    if !out2.is_empty() {
        renderer.write(&out2);
    }

    // 模拟输入法逐词提交：分块写入，并在输入中途开始连续放大（快速 resize），
    // 贴近真机"边打字边放大"的时序。
    let chunks = ["白日依山尽，", "黄河入海流", "。欲穷千里目，", "更上一层楼。"];
    let mut cols: u16 = 60;
    for (i, chunk) in chunks.iter().enumerate() {
        unsafe { fable_pty_write(pty, chunk.as_ptr(), chunk.len()) };
        thread::sleep(Duration::from_millis(120));
        let out = drain_pty(pty);
        if !out.is_empty() {
            renderer.write(&out);
        }
        // 每个词块之后放大几档（列数减少），观察输入行是否被复制/错乱。
        for _ in 0..3 {
            cols = (cols - 4).max(10);
            let rows = (24u16 * cols / 60).max(8);
            unsafe { fable_pty_resize(pty, cols as c_int, rows as c_int) };
            renderer.resize(cols, rows);
            thread::sleep(Duration::from_millis(30));
            let out2 = drain_pty(pty);
            if !out2.is_empty() {
                renderer.write(&out2);
            }
        }
        thread::sleep(Duration::from_millis(30));
        let out3 = drain_pty(pty);
        if !out3.is_empty() {
            renderer.write(&out3);
        }
        let rows_now = dump_rows(&renderer, cols, (24u16 * cols / 60).max(8));
        let full: Vec<&String> = rows_now.iter().filter(|r| r.contains("黄河入海流")).collect();
        if full.len() > 1 {
            println!("chunk={i} cols={cols} 出现重复: {rows_now:?}");
        }
    }

    // 输入完成后再放大回 60 列。
    for &target in &[40u16, 26, 18, 26, 40, 60] {
        let rows = (24u16 * target / 60).max(8);
        unsafe { fable_pty_resize(pty, target as c_int, rows as c_int) };
        renderer.resize(target, rows);
        thread::sleep(Duration::from_millis(80));
        let out = drain_pty(pty);
        if !out.is_empty() {
            renderer.write(&out);
        }
    }

    thread::sleep(Duration::from_millis(200));
    let tail = drain_pty(pty);
    if !tail.is_empty() {
        renderer.write(&tail);
    }
    let final_rows = dump_rows(&renderer, 60, 24);
    let full: Vec<&String> = final_rows.iter().filter(|r| r.contains(LINE)).collect();
    let fragments: Vec<&String> = final_rows
        .iter()
        .filter(|r| !r.is_empty() && (r.contains("白日依山尽") || r.contains("黄河入海流")))
        .collect();
    println!("最终渲染器行（前 8 行）: {:?}", &final_rows[..final_rows.len().min(8)]);
    println!(
        "最终 full_line_count={} fragment_rows={}",
        full.len(),
        fragments.len()
    );

    let ok = full.len() == 1 && fragments.len() == 1;
    println!(
        "结果: {}",
        if ok {
            "ALL PASS"
        } else {
            "FAIL（生产路径 resize 后输入行被复制/残留）"
        }
    );
    if !ok {
        std::process::exit(1);
    }

    unsafe { fable_pty_close(pty) };
}
