//! 工单 22 真机反馈诊断：libghostty-vt 核心对 emoji22 序列的 cell 拆分形态。
use fable_render::ffi::*;
use std::ffi::c_void;

unsafe fn cell_text(cells: GhosttyRenderStateRowCells) -> String {
    // SAFETY: callers position `cells` on a live cell; all output slots and
    // byte buffers below remain valid for the synchronous C calls.
    unsafe {
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
}

fn esc(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            ' ' => '␣'.to_string(),
            '\u{200d}' => "ZWJ".to_string(),
            '\u{fe0f}' => "VS16".to_string(),
            '\u{20e3}' => "KEYCAP".to_string(),
            '\u{e007f}' => "TAG-END".to_string(),
            c if (0xe0020..=0xe007e).contains(&(c as u32)) => "TAG".to_string(),
            c => c.to_string(),
        })
        .collect::<Vec<_>>()
        .join("|")
}

fn main() {
    force_tls_pad();
    // SAFETY: this diagnostic owns the terminal, render state, iterator, and
    // cell handles it creates, then releases each matching resource.
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: 80,
            rows: 24,
            max_scrollback: 1000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        ghostty_terminal_new(std::ptr::null(), &mut terminal, opts);

        // 读真实 bash PTY 字节流（.font-check/bash_echo.bin），整段喂给核心，
        // 复现探针「命令行回显 + echo 输出」两行的核心 cell 拆分。
        let bytes = std::fs::read("../.font-check/bash_echo2.bin")
            .or_else(|_| std::fs::read(".font-check/bash_echo2.bin"))
            .expect("bash_echo2.bin (bracketed paste 真实 bash 捕获)");
        println!(
            "bytes={} first={:02x?}",
            bytes.len(),
            &bytes[..16.min(bytes.len())]
        );
        ghostty_terminal_vt_write(terminal, bytes.as_ptr(), bytes.len());

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
            println!("row {row_index}: {}", texts.join(" / "));
            row_index += 1;
            if row_index > 4 {
                break;
            }
        }
        ghostty_render_state_row_cells_free(cells);
        ghostty_render_state_row_iterator_free(row_it);
        ghostty_render_state_free(state);
        ghostty_terminal_free(terminal);
    }
}
