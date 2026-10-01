//! 工单 15 真机回归回路（第二轮，端到端）：真实 PTY + bash（readline）下，
//! 连续放大（列数减少）后输入行被复制成两份。
//!
//! 回路：spawn bash → 输入一行 10 个 CJK 字符（不回车，留在 readline 输入缓冲）
//! → 逐档缩小/放大 PTY 与核心（模拟双指缩放）→ 每档把 PTY 输出喂给核心 →
//! 断言最终回到 60 列时输入行仍只有一份、无残留折行片段。
//! 验收命令：`cargo run --release --example resize_pty_reflow_check`。

use fable_render::ffi::*;
use std::ffi::{c_char, c_int, c_void};
use std::ptr;
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

fn check(result: GhosttyResult, what: &str) {
    assert_eq!(result, GHOSTTY_SUCCESS, "{what} 失败: {result}");
}

unsafe fn cell_text(cells: GhosttyRenderStateRowCells) -> String {
    // SAFETY: callers position `cells` on a live cell; all output slots and
    // byte buffers below remain valid for the synchronous C calls.
    unsafe {
        let mut buf = GhosttyBuffer {
            ptr: ptr::null_mut(),
            cap: 0,
            len: 0,
        };
        let r = ghostty_render_state_row_cells_get(
            cells,
            CELL_DATA_GRAPHEMES_UTF8,
            &mut buf as *mut GhosttyBuffer as *mut c_void,
        );
        if r == GHOSTTY_OUT_OF_SPACE && buf.len > 0 {
            let mut bytes = vec![0u8; buf.len];
            let mut buf2 = GhosttyBuffer {
                ptr: bytes.as_mut_ptr(),
                cap: bytes.len(),
                len: 0,
            };
            check(
                ghostty_render_state_row_cells_get(
                    cells,
                    CELL_DATA_GRAPHEMES_UTF8,
                    &mut buf2 as *mut GhosttyBuffer as *mut c_void,
                ),
                "cell_text 取数据",
            );
            bytes.truncate(buf2.len);
            String::from_utf8_lossy(&bytes).into_owned()
        } else {
            String::new()
        }
    }
}

unsafe fn collect_rows_text(state: GhosttyRenderState) -> Vec<String> {
    // SAFETY: caller supplies a live state; this diagnostic owns the iterator
    // and cells handles and frees them after copying all borrowed text.
    unsafe {
        let mut row_it: GhosttyRenderStateRowIterator = ptr::null_mut();
        check(
            ghostty_render_state_row_iterator_new(ptr::null(), &mut row_it),
            "row_iterator_new",
        );
        check(
            ghostty_render_state_get(
                state,
                DATA_ROW_ITERATOR,
                &mut row_it as *mut _ as *mut c_void,
            ),
            "get row_iterator",
        );
        let mut cells: GhosttyRenderStateRowCells = ptr::null_mut();
        check(
            ghostty_render_state_row_cells_new(ptr::null(), &mut cells),
            "row_cells_new",
        );

        let mut rows = Vec::new();
        while ghostty_render_state_row_iterator_next(row_it) {
            check(
                ghostty_render_state_row_get(
                    row_it,
                    ROW_DATA_CELLS,
                    &mut cells as *mut _ as *mut c_void,
                ),
                "row cells",
            );
            let mut line = String::new();
            while ghostty_render_state_row_cells_next(cells) {
                line.push_str(&cell_text(cells));
            }
            rows.push(line.trim_end().to_string());
        }

        ghostty_render_state_row_cells_free(cells);
        ghostty_render_state_row_iterator_free(row_it);
        rows
    }
}

/// 排空 PTY 当前可读输出并返回字节。
fn drain_pty(pty: i64) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buf = [0u8; 4096];
    // SAFETY: caller owns a live PTY handle; `buf` is writable for the exact
    // length supplied to the shim, which returns at most that many bytes.
    unsafe {
        while fable_pty_read_ready(pty) != 0 {
            let n = fable_pty_read(pty, buf.as_mut_ptr(), buf.len());
            if n <= 0 {
                break;
            }
            out.extend_from_slice(&buf[..n as usize]);
        }
    }
    out
}

fn main() {
    const LINE: &str = "白日依山尽，黄河入海流";
    const SHELL: &str = "/data/data/com.termux/files/usr/bin/bash";
    force_tls_pad();
    // SAFETY: this diagnostic owns its PTY, terminal, render state, iterator,
    // and cells handles, releasing all of them on its normal completion path.
    unsafe {
        let mut terminal: GhosttyTerminal = ptr::null_mut();
        check(
            ghostty_terminal_new(
                ptr::null(),
                &mut terminal,
                GhosttyTerminalOptions {
                    cols: 60,
                    rows: 24,
                    max_scrollback: 10000,
                },
            ),
            "terminal_new",
        );
        let mut state: GhosttyRenderState = ptr::null_mut();
        check(
            ghostty_render_state_new(ptr::null(), &mut state),
            "render_state_new",
        );

        let shell_c = std::ffi::CString::new(SHELL).unwrap();
        let pty = fable_pty_spawn(shell_c.as_ptr(), 60, 24);
        assert!(pty != 0, "pty spawn 失败");

        // 启动横幅：排空并喂核心，等 bash 就绪。
        thread::sleep(Duration::from_millis(600));
        let banner = drain_pty(pty);
        if !banner.is_empty() {
            ghostty_terminal_vt_write(terminal, banner.as_ptr(), banner.len());
        }
        thread::sleep(Duration::from_millis(200));
        let extra = drain_pty(pty);
        if !extra.is_empty() {
            ghostty_terminal_vt_write(terminal, extra.as_ptr(), extra.len());
        }

        // 设置最小提示符并执行（回车），再输入目标行（不回车，留在输入缓冲）。
        let ps1 = "PS1='$ '\r";
        fable_pty_write(pty, ps1.as_ptr(), ps1.len());
        thread::sleep(Duration::from_millis(300));
        let out = drain_pty(pty);
        if !out.is_empty() {
            ghostty_terminal_vt_write(terminal, out.as_ptr(), out.len());
        }
        thread::sleep(Duration::from_millis(150));
        let out2 = drain_pty(pty);
        if !out2.is_empty() {
            ghostty_terminal_vt_write(terminal, out2.as_ptr(), out2.len());
        }

        fable_pty_write(pty, LINE.as_ptr(), LINE.len());
        thread::sleep(Duration::from_millis(400));
        let out3 = drain_pty(pty);
        if !out3.is_empty() {
            ghostty_terminal_vt_write(terminal, out3.as_ptr(), out3.len());
        }
        thread::sleep(Duration::from_millis(200));
        let out4 = drain_pty(pty);
        if !out4.is_empty() {
            ghostty_terminal_vt_write(terminal, out4.as_ptr(), out4.len());
        }

        check(
            ghostty_render_state_update(state, terminal),
            "render_state_update",
        );
        let before = collect_rows_text(state);
        println!(
            "输入后核心行（前 6 行）: {:?}",
            &before[..before.len().min(6)]
        );

        // 逐档放大（行列同缩）再缩小：模拟双指缩放的真实几何变化。
        // 先 PTY resize，再核心 resize（与应用 mailbox FIFO 顺序一致）。
        let cols_seq: [u16; 26] = [
            55, 50, 45, 40, 35, 30, 26, 22, 18, 14, 10, 14, 18, 22, 26, 30, 35, 40, 45, 50, 55, 60,
            60, 60, 60, 60,
        ];
        for (i, &cols) in cols_seq.iter().enumerate() {
            let rows = (24u16 * cols / 60).max(8);
            fable_pty_resize(pty, cols as c_int, rows as c_int);
            check(
                ghostty_terminal_resize(terminal, cols, rows, 0, 0),
                "resize",
            );
            // 前 12 档快节奏（40ms，模拟连续缩放），之后放慢让 shell 完成重绘。
            thread::sleep(Duration::from_millis(if i < 12 { 40 } else { 150 }));
            let out = drain_pty(pty);
            if !out.is_empty() {
                ghostty_terminal_vt_write(terminal, out.as_ptr(), out.len());
            }
            thread::sleep(Duration::from_millis(20));
            let out2 = drain_pty(pty);
            if !out2.is_empty() {
                ghostty_terminal_vt_write(terminal, out2.as_ptr(), out2.len());
            }
            check(
                ghostty_render_state_update(state, terminal),
                "render_state_update",
            );
            let rows = collect_rows_text(state);
            let full: Vec<&String> = rows.iter().filter(|r| r.contains(LINE)).collect();
            if full.len() > 1 {
                println!("cols={cols} 出现重复: {rows:?}");
            }
        }

        // 最终回到 60 列：整行应恰好出现一次，无残留片段。
        thread::sleep(Duration::from_millis(200));
        let tail = drain_pty(pty);
        if !tail.is_empty() {
            ghostty_terminal_vt_write(terminal, tail.as_ptr(), tail.len());
        }
        check(
            ghostty_render_state_update(state, terminal),
            "render_state_update",
        );
        let rows = collect_rows_text(state);
        let full: Vec<&String> = rows.iter().filter(|r| r.contains(LINE)).collect();
        let fragments: Vec<&String> = rows
            .iter()
            .filter(|r| !r.is_empty() && (r.contains("白日依山尽") || r.contains("黄河入海流")))
            .collect();
        println!("最终核心行（前 8 行）: {:?}", &rows[..rows.len().min(8)]);
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
                "FAIL（resize 后输入行被复制/残留）"
            }
        );
        if !ok {
            std::process::exit(1);
        }

        fable_pty_close(pty);
        ghostty_render_state_free(state);
        ghostty_terminal_free(terminal);
    }
}
