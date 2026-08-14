//! 工单 26 合成 emoji 输入诊断（一次覆盖全部候选因素）：
//! 真实 PTY + bash（readline）+ 核心（libghostty-vt）端到端。
//!
//! 候选因素一次全测：
//! A) readline 宽度模型：逐 emoji 键入后抓 readline 回显字节流（是否出现 \r /
//!    光标归位序列）→ 若核心光标被拉回第 0 列，即 readline 按每码位 2 列
//!    算宽（家庭=8 列）触发"行满重绘"。
//! B) 核心聚合：逐格 RAW→ghostty_cell_get 打 WIDE 标记，确认合成 emoji 是
//!    1 个 WIDE+TAIL（2 列，2027 生效）还是 4 个 WIDE+TAIL（8 列，未聚合）。
//! C) 渲染几何：build_row_vertices 在快照上的字形/光标矩形落点。
//! E) 光标位置：核心 CURSOR_VIEWPORT_X 在键入过程中的变化（是否回第 0 列）。
//!
//! 验收命令：cargo run --release --example diag_zwj_typing

use fable_render::ffi::*;
use std::ffi::CString;
use std::ffi::{c_char, c_int, c_void};
use std::ptr;
use std::thread;
use std::time::Duration;

extern "C" {
    fn fable_pty_spawn(shell: *const c_char, cols: c_int, rows: c_int) -> i64;
    fn fable_pty_read(handle: i64, buf: *mut u8, len: usize) -> c_int;
    fn fable_pty_read_ready(handle: i64) -> c_int;
    fn fable_pty_write(handle: i64, data: *const u8, len: usize) -> c_int;
    fn fable_pty_close(handle: i64);
}

fn check(result: GhosttyResult, what: &str) {
    assert_eq!(result, GHOSTTY_SUCCESS, "{what} 失败: {result}");
}

unsafe fn cell_text(cells: GhosttyRenderStateRowCells) -> String {
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
        let _ = ghostty_render_state_row_cells_get(
            cells,
            CELL_DATA_GRAPHEMES_UTF8,
            &mut buf2 as *mut GhosttyBuffer as *mut c_void,
        );
        bytes.truncate(buf2.len);
        String::from_utf8_lossy(&bytes).into_owned()
    } else {
        String::new()
    }
}

unsafe fn cell_raw_wide(cells: GhosttyRenderStateRowCells) -> i32 {
    let mut raw: u64 = 0;
    let r = ghostty_render_state_row_cells_get(
        cells,
        CELL_DATA_RAW,
        &mut raw as *mut u64 as *mut c_void,
    );
    if r != GHOSTTY_SUCCESS || raw == 0 {
        return -1;
    }
    let mut wide: i32 = -1;
    ghostty_cell_get(
        raw,
        GHOSTTY_CELL_DATA_WIDE,
        &mut wide as *mut i32 as *mut c_void,
    );
    wide
}

unsafe fn drain_pty(pty: i64) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buf = [0u8; 4096];
    while fable_pty_read_ready(pty) != 0 {
        let n = fable_pty_read(pty, buf.as_mut_ptr(), buf.len());
        if n <= 0 {
            break;
        }
        out.extend_from_slice(&buf[..n as usize]);
    }
    out
}

/// 字节流摘要：可打印字符 + 转义缩写 → 判断 readline 是否发 \r。
fn summarize(bytes: &[u8]) -> String {
    let mut s = String::new();
    for &b in bytes {
        match b {
            0x0d => s.push_str("<CR>"),
            0x0a => s.push_str("<LF>"),
            0x1b => s.push_str("<ESC>"),
            0x09 => s.push_str("<TAB>"),
            0x20..=0x7e => s.push(b as char),
            _ => s.push_str(&format!("<{:02X}>", b)),
        }
    }
    s
}

unsafe fn dump_core(state: GhosttyRenderState, label: &str) {
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
    let mut cur_x: u16 = 0;
    let mut cur_y: u16 = 0;
    let mut has: bool = false;
    let _ = ghostty_render_state_get(
        state,
        DATA_CURSOR_VIEWPORT_HAS_VALUE,
        &mut has as *mut bool as *mut c_void,
    );
    if has {
        let _ = ghostty_render_state_get(
            state,
            DATA_CURSOR_VIEWPORT_X,
            &mut cur_x as *mut u16 as *mut c_void,
        );
        let _ = ghostty_render_state_get(
            state,
            DATA_CURSOR_VIEWPORT_Y,
            &mut cur_y as *mut u16 as *mut c_void,
        );
    }
    print!("{label} cursor=({cur_x},{cur_y}) has={has}");
    let mut row_idx = 0usize;
    while ghostty_render_state_row_iterator_next(row_it) {
        check(
            ghostty_render_state_row_get(
                row_it,
                ROW_DATA_CELLS,
                &mut cells as *mut _ as *mut c_void,
            ),
            "row cells",
        );
        let mut col = 0usize;
        let mut parts = Vec::new();
        while ghostty_render_state_row_cells_next(cells) {
            let text = cell_text(cells);
            let wide = cell_raw_wide(cells);
            let wname = match wide {
                CELL_WIDE_WIDE => "W",
                CELL_WIDE_SPACER_TAIL => "T",
                CELL_WIDE_SPACER_HEAD => "H",
                _ => ".",
            };
            if !text.is_empty() || wide == CELL_WIDE_WIDE || wide == CELL_WIDE_SPACER_TAIL {
                parts.push(format!(
                    "c{col}:{wname}{}",
                    if text.is_empty() { "" } else { &text }
                ));
            }
            col += 1;
        }
        if !parts.is_empty() {
            print!(" | r{row_idx} {}", parts.join(" "));
        }
        row_idx += 1;
    }
    println!();
    ghostty_render_state_row_cells_free(cells);
    ghostty_render_state_row_iterator_free(row_it);
}

fn main() {
    const SHELL: &str = "/data/data/com.termux/files/usr/bin/bash";
    const FAMILY: &str = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}\u{200D}\u{1F466}";
    force_tls_pad();

    for cols in [40usize, 80usize] {
        unsafe {
            println!("\n===== 场景: cols={cols} =====");
            let mut terminal: GhosttyTerminal = ptr::null_mut();
            check(
                ghostty_terminal_new(
                    ptr::null(),
                    &mut terminal,
                    GhosttyTerminalOptions {
                        cols: cols as u16,
                        rows: 6,
                        max_scrollback: 1000,
                    },
                ),
                "terminal_new",
            );
            let mut state: GhosttyRenderState = ptr::null_mut();
            check(
                ghostty_render_state_new(ptr::null(), &mut state),
                "render_state_new",
            );
            let shell_c = CString::new(SHELL).expect("shell path");
            let pty = fable_pty_spawn(shell_c.as_ptr(), cols as c_int, 6);
            // 注意：bionic MTE 会把堆指针打标签（0xB4 开头），有符号比较会误判负数；
            // 与 resize_pty_reflow_check 一致用 != 0 判断。
            assert!(pty != 0, "pty spawn 失败");

            // 初始输出（提示符）喂核心，直到静默。
            thread::sleep(Duration::from_millis(400));
            let init = drain_pty(pty);
            ghostty_terminal_vt_write(terminal, init.as_ptr(), init.len());
            // 开启 2027（与主终端一致）。
            let on = b"\x1b[?2027h";
            ghostty_terminal_vt_write(terminal, on.as_ptr(), on.len());

            // 记录 bash 版本与终端宽度（readline 感知）。
            for cmd in ["echo __BASHV__=$BASH_VERSION\n", "stty size\n"] {
                let b = cmd.as_bytes();
                fable_pty_write(pty, b.as_ptr(), b.len());
                thread::sleep(Duration::from_millis(250));
                let out = drain_pty(pty);
                ghostty_terminal_vt_write(terminal, out.as_ptr(), out.len());
                print!("  [{cmd:?}] -> {}", summarize(&out));
            }
            check(
                ghostty_render_state_update(state, terminal),
                "render_state_update",
            );
            dump_core(state, "  基线");

            // 逐 emoji 键入：每次抓 readline 回显字节 + 核心 cells/光标。
            for i in 1..=8usize {
                let b = FAMILY.as_bytes();
                fable_pty_write(pty, b.as_ptr(), b.len());
                thread::sleep(Duration::from_millis(350));
                let out = drain_pty(pty);
                ghostty_terminal_vt_write(terminal, out.as_ptr(), out.len());
                check(
                    ghostty_render_state_update(state, terminal),
                    "render_state_update",
                );
                let mut cur_x: u16 = 0;
                let mut has: bool = false;
                let _ = ghostty_render_state_get(
                    state,
                    DATA_CURSOR_VIEWPORT_HAS_VALUE,
                    &mut has as *mut bool as *mut c_void,
                );
                if has {
                    let _ = ghostty_render_state_get(
                        state,
                        DATA_CURSOR_VIEWPORT_X,
                        &mut cur_x as *mut u16 as *mut c_void,
                    );
                }
                let cr_count = out.iter().filter(|&&b| b == 0x0d).count();
                let esc_count = out.iter().filter(|&&b| b == 0x1b).count();
                println!(
                    "  [emoji#{i}] cr={cr_count} esc={esc_count} cursor_x={cur_x} echo={}",
                    summarize(&out)
                );
                dump_core(state, "     核心");
            }

            fable_pty_close(pty);
            ghostty_render_state_free(state);
            ghostty_terminal_free(terminal);
        }
    }
}
