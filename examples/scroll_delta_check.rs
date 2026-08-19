//! 工单 10 滚动排查：验证 SCROLL_VIEWPORT_DELTA 是否真的移动视口。

use fable_render::ffi::*;
use std::ffi::c_void;

unsafe fn collect_text(state: GhosttyRenderState) -> Vec<String> {
    // SAFETY: caller supplies a live state; this diagnostic owns the iterator
    // and cells handles and frees them after copying all borrowed text.
    unsafe {
        let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
        ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it);
        ghostty_render_state_get(
            state,
            DATA_ROW_ITERATOR,
            &mut row_it as *mut _ as *mut c_void,
        );
        let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
        ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells);
        let mut rows = Vec::new();
        while ghostty_render_state_row_iterator_next(row_it) {
            ghostty_render_state_row_get(
                row_it,
                ROW_DATA_CELLS,
                &mut cells as *mut _ as *mut c_void,
            );
            let mut line = String::new();
            while ghostty_render_state_row_cells_next(cells) {
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
                    bytes.truncate(buf2.len);
                    line.push_str(&String::from_utf8_lossy(&bytes));
                }
            }
            rows.push(line.trim_end().to_string());
        }
        ghostty_render_state_row_cells_free(cells);
        ghostty_render_state_row_iterator_free(row_it);
        rows
    }
}

fn main() {
    // SAFETY: this diagnostic owns the paired terminal and render state and
    // releases both after the viewport experiment.
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: 80,
            rows: 24,
            max_scrollback: 10000,
        };
        let mut term: GhosttyTerminal = std::ptr::null_mut();
        ghostty_terminal_new(std::ptr::null(), &mut term, opts);
        let mut data = String::new();
        for i in 0..200usize {
            data.push_str(&format!("line-{i:03}\r\n"));
        }
        ghostty_terminal_vt_write(term, data.as_ptr(), data.len());

        let mut state: GhosttyRenderState = std::ptr::null_mut();
        ghostty_render_state_new(std::ptr::null(), &mut state);
        ghostty_render_state_update(state, term);
        let bottom = collect_text(state);
        let bottom_nonempty: Vec<String> =
            bottom.iter().filter(|s| !s.is_empty()).cloned().collect();
        println!(
            "bottom_first={} bottom_last={}",
            bottom_nonempty.first().unwrap_or(&String::new()),
            bottom_nonempty.last().unwrap_or(&String::new())
        );

        ghostty_terminal_scroll_viewport(
            term,
            GhosttyTerminalScrollViewport {
                tag: SCROLL_VIEWPORT_DELTA,
                value: GhosttyTerminalScrollViewportValue { delta: -10 },
            },
        );
        ghostty_render_state_update(state, term);
        let after = collect_text(state);
        let after_nonempty: Vec<String> = after.iter().filter(|s| !s.is_empty()).cloned().collect();
        println!(
            "after_scroll_first={} after_scroll_last={}",
            after_nonempty.first().unwrap_or(&String::new()),
            after_nonempty.last().unwrap_or(&String::new())
        );
        let changed = bottom_nonempty != after_nonempty;
        println!(
            "RESULT: {}",
            if changed {
                "PASS viewport-moved"
            } else {
                "FAIL viewport-unchanged"
            }
        );
        ghostty_render_state_free(state);
        ghostty_terminal_free(term);
        std::process::exit(if changed { 0 } else { 1 });
    }
}
