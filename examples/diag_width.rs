//! 工单 22 真机反馈：宽字符（中文/emoji/ZWJ 序列）的核心列布局 + 光标位置诊断。
use fable_render::ffi::*;
use std::ffi::c_void;

unsafe fn get_u16(state: GhosttyRenderState, data: i32) -> u16 {
    let mut v: u16 = 0;
    ghostty_render_state_get(state, data, &mut v as *mut u16 as *mut c_void);
    v
}
unsafe fn get_bool(state: GhosttyRenderState, data: i32) -> bool {
    let mut v: bool = false;
    ghostty_render_state_get(state, data, &mut v as *mut bool as *mut c_void);
    v
}
unsafe fn cell_text(cells: GhosttyRenderStateRowCells) -> String {
    let mut buf = GhosttyBuffer {
        ptr: std::ptr::null_mut(),
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
        ghostty_render_state_row_cells_get(
            cells,
            CELL_DATA_GRAPHEMES_UTF8,
            &mut buf2 as *mut GhosttyBuffer as *mut c_void,
        );
        return String::from_utf8_lossy(&bytes[..buf2.len]).into_owned();
    }
    String::new()
}
fn esc(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            ' ' => "·".to_string(),
            '\u{200d}' => "ZWJ".to_string(),
            '\u{fe0f}' => "VS16".to_string(),
            c => c.to_string(),
        })
        .collect::<Vec<_>>()
        .join("|")
}

fn main() {
    force_tls_pad();
    mode2027_cells();
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: 40,
            rows: 10,
            max_scrollback: 1000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        ghostty_terminal_new(std::ptr::null(), &mut terminal, opts);

        let cases: [&str; 4] = [
            "中\r\n",
            "🧑\u{200d}🎓\r\n",
            "🧑\u{200d}🎓🧑\u{200d}🎓🧑\u{200d}🎓\r\n",
            "🚀\r\n",
        ];
        for case in cases {
            // bracketed paste 包裹（保留 ZWJ，与探针按钮一致）
            let mut payload = Vec::new();
            payload.extend_from_slice(b"\x1b[200~");
            payload.extend_from_slice(case.as_bytes());
            payload.extend_from_slice(b"\x1b[201~");
            ghostty_terminal_vt_write(terminal, payload.as_ptr(), payload.len());

            let mut state: GhosttyRenderState = std::ptr::null_mut();
            ghostty_render_state_new(std::ptr::null(), &mut state);
            ghostty_render_state_update(state, terminal);

            let cursor_x = get_u16(state, DATA_CURSOR_VIEWPORT_X);
            let cursor_y = get_u16(state, DATA_CURSOR_VIEWPORT_Y);
            let cursor_visible = get_bool(state, DATA_CURSOR_VISIBLE);

            let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
            ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it);
            ghostty_render_state_get(
                state,
                DATA_ROW_ITERATOR,
                &mut row_it as *mut _ as *mut c_void,
            );
            let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
            ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells);
            let mut row_index = 0usize;
            while ghostty_render_state_row_iterator_next(row_it) {
                ghostty_render_state_row_get(
                    row_it,
                    ROW_DATA_CELLS,
                    &mut cells as *mut _ as *mut c_void,
                );
                let mut texts: Vec<String> = Vec::new();
                while ghostty_render_state_row_cells_next(cells) {
                    texts.push(esc(&cell_text(cells)));
                }
                if !texts.iter().all(|t| t.is_empty()) {
                    println!(
                        "case={:?} row{row_index} cols={} cursor=({cursor_x},{cursor_y}) visible={cursor_visible}\n  cells[{}]: {}",
                        case.trim(),
                        texts.len(),
                        texts.len(),
                        texts.join(" / ")
                    );
                }
                row_index += 1;
            }
            ghostty_render_state_row_cells_free(cells);
            ghostty_render_state_row_iterator_free(row_it);
            ghostty_render_state_free(state);
            println!();
        }
        ghostty_terminal_free(terminal);
    }
}

// 光标落点补充测试：写宽字符不换行，看光标 x。
pub fn cursor_on_wide() {
    force_tls_pad();
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: 40,
            rows: 10,
            max_scrollback: 1000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        ghostty_terminal_new(std::ptr::null(), &mut terminal, opts);
        let cases: [&str; 4] = ["中", "🚀", "🧑\u{200d}🎓", "A中🚀"];
        for case in cases {
            let mut payload = Vec::new();
            payload.extend_from_slice(b"\x1b[200~");
            payload.extend_from_slice(case.as_bytes());
            payload.extend_from_slice(b"\x1b[201~");
            ghostty_terminal_vt_write(terminal, payload.as_ptr(), payload.len());
            let mut state: GhosttyRenderState = std::ptr::null_mut();
            ghostty_render_state_new(std::ptr::null(), &mut state);
            ghostty_render_state_update(state, terminal);
            let x = get_u16(state, DATA_CURSOR_VIEWPORT_X);
            let y = get_u16(state, DATA_CURSOR_VIEWPORT_Y);
            println!("cursor case={case:?} -> ({x},{y})");
            ghostty_render_state_free(state);
        }
        ghostty_terminal_free(terminal);
    }
}

pub fn grapheme_mode_2027() {
    force_tls_pad();
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: 40,
            rows: 10,
            max_scrollback: 1000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        ghostty_terminal_new(std::ptr::null(), &mut terminal, opts);
        // 启用 DECSET 2027（grapheme clustering）
        let enable = b"\x1b[?2027h";
        ghostty_terminal_vt_write(terminal, enable.as_ptr(), enable.len());
        let cases: [&str; 2] = ["🧑\u{200d}🎓", "🧑\u{200d}🎓🧑\u{200d}🎓🧑\u{200d}🎓"];
        for case in cases {
            let mut payload = Vec::new();
            payload.extend_from_slice(b"\x1b[200~");
            payload.extend_from_slice(case.as_bytes());
            payload.extend_from_slice(b"\x1b[201~");
            ghostty_terminal_vt_write(terminal, payload.as_ptr(), payload.len());
            let mut state: GhosttyRenderState = std::ptr::null_mut();
            ghostty_render_state_new(std::ptr::null(), &mut state);
            ghostty_render_state_update(state, terminal);
            let x = get_u16(state, DATA_CURSOR_VIEWPORT_X);
            println!("mode2027 cursor={case:?} -> x={x}");
            ghostty_render_state_free(state);
        }
        ghostty_terminal_free(terminal);
    }
}

pub fn mode2027_cells() {
    force_tls_pad();
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: 40,
            rows: 10,
            max_scrollback: 1000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        ghostty_terminal_new(std::ptr::null(), &mut terminal, opts);
        let enable = b"\x1b[?2027h";
        ghostty_terminal_vt_write(terminal, enable.as_ptr(), enable.len());
        let cases: [&str; 4] = ["中\r\n", "🧑\u{200d}🎓\r\n", "👍🏻\r\n", "1️⃣\r\n"];
        for case in cases {
            let mut payload = Vec::new();
            payload.extend_from_slice(b"\x1b[200~");
            payload.extend_from_slice(case.as_bytes());
            payload.extend_from_slice(b"\x1b[201~");
            ghostty_terminal_vt_write(terminal, payload.as_ptr(), payload.len());
            let mut state: GhosttyRenderState = std::ptr::null_mut();
            ghostty_render_state_new(std::ptr::null(), &mut state);
            ghostty_render_state_update(state, terminal);
            let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
            ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it);
            ghostty_render_state_get(
                state,
                DATA_ROW_ITERATOR,
                &mut row_it as *mut _ as *mut c_void,
            );
            let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
            ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells);
            let mut row_index = 0usize;
            while ghostty_render_state_row_iterator_next(row_it) {
                ghostty_render_state_row_get(
                    row_it,
                    ROW_DATA_CELLS,
                    &mut cells as *mut _ as *mut c_void,
                );
                let mut texts: Vec<String> = Vec::new();
                while ghostty_render_state_row_cells_next(cells) {
                    texts.push(esc(&cell_text(cells)));
                }
                if !texts.iter().all(|t| t.is_empty()) {
                    println!(
                        "2027 case={:?} row{row_index}: {}",
                        case.trim(),
                        texts.join(" / ")
                    );
                }
                row_index += 1;
            }
            ghostty_render_state_row_cells_free(cells);
            ghostty_render_state_row_iterator_free(row_it);
            ghostty_render_state_free(state);
        }
        ghostty_terminal_free(terminal);
    }
}
