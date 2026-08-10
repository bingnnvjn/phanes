//! 验证：2027 模式下旗帜（RI 对）/英格兰 tag 旗/keycap 的核心 cell 拆分。
use fable_render::ffi::*;
use std::ffi::c_void;
unsafe fn cell_text(cells: GhosttyRenderStateRowCells) -> String {
    let mut buf = GhosttyBuffer { ptr: std::ptr::null_mut(), cap: 0, len: 0 };
    let r = ghostty_render_state_row_cells_get(cells, CELL_DATA_GRAPHEMES_UTF8, &mut buf as *mut GhosttyBuffer as *mut c_void);
    if r == GHOSTTY_OUT_OF_SPACE && buf.len > 0 {
        let mut bytes = vec![0u8; buf.len];
        let mut buf2 = GhosttyBuffer { ptr: bytes.as_mut_ptr(), cap: bytes.len(), len: 0 };
        ghostty_render_state_row_cells_get(cells, CELL_DATA_GRAPHEMES_UTF8, &mut buf2 as *mut GhosttyBuffer as *mut c_void);
        return String::from_utf8_lossy(&bytes[..buf2.len]).into_owned();
    }
    String::new()
}
fn esc(s: &str) -> String {
    if s.is_empty() { "␣".into() } else { s.chars().map(|c| if c == '\u{200d}' { "ZWJ".into() } else { c.to_string() }).collect::<Vec<_>>().join("|") }
}
fn main() {
    force_tls_pad();
    unsafe {
        let opts = GhosttyTerminalOptions { cols: 40, rows: 10, max_scrollback: 1000 };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        ghostty_terminal_new(std::ptr::null(), &mut terminal, opts);
        let enable = b"\x1b[?2027h";
        ghostty_terminal_vt_write(terminal, enable.as_ptr(), enable.len());
        let cases: [&str; 3] = [
            "🇨🇳🇺🇸🇯🇵🇬🇧\r\n",
            "🏴󠁧󠁢󠁥󠁮󠁧󠁿\r\n",
            "1️⃣9️⃣#️⃣*️⃣\r\n",
        ];
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
            ghostty_render_state_get(state, DATA_ROW_ITERATOR, &mut row_it as *mut _ as *mut c_void);
            let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
            ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells);
            let mut row_index = 0usize;
            while ghostty_render_state_row_iterator_next(row_it) {
                ghostty_render_state_row_get(row_it, ROW_DATA_CELLS, &mut cells as *mut _ as *mut c_void);
                let mut texts: Vec<String> = Vec::new();
                while ghostty_render_state_row_cells_next(cells) {
                    texts.push(esc(&cell_text(cells)));
                }
                if !texts.iter().all(|t| t == "␣") {
                    println!("case={:?} row{row_index}: {}", case.trim(), texts.join(" / "));
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
