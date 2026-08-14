//! 工单 15 真机回归回路（第二轮）：连续放大（字体增大 → 列数减少）到临界点后，
//! 一行中文变成两行相同内容；缩小回去仍保持两行。
//!
//! 回路：向 libghostty-vt 核心写入一行 10 个 CJK 字符（20 列），沿"放大"方向
//! 逐档缩小列数（60→10），再沿"缩小"方向逐档放大列数（10→60），每档读取
//! 视口行文本；断言最终回到 60 列时内容仍是单行、不重复、无残留折行片段。
//! 验收命令：`cargo run --release --example resize_reflow_check`。

use fable_render::ffi::*;
use std::ffi::c_void;

fn check(result: GhosttyResult, what: &str) {
    assert_eq!(result, GHOSTTY_SUCCESS, "{what} 失败: {result}");
}

#[expect(dead_code, reason = "工单 50：诊断例程保留供手工探针调用")]
unsafe fn get_u16(state: GhosttyRenderState, data: i32) -> u16 {
    let mut v: u16 = 0;
    check(
        ghostty_render_state_get(state, data, &mut v as *mut u16 as *mut c_void),
        "get_u16",
    );
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

/// 读全部视口行文本（新迭代器，不依赖旧状态）。
unsafe fn collect_rows_text(state: GhosttyRenderState) -> Vec<String> {
    let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
    check(
        ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it),
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
    let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
    check(
        ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells),
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

fn main() {
    const LINE: &str = "白日依山尽，黄河入海流";
    force_tls_pad();
    unsafe {
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        check(
            ghostty_terminal_new(
                std::ptr::null(),
                &mut terminal,
                GhosttyTerminalOptions {
                    cols: 60,
                    rows: 24,
                    max_scrollback: 10000,
                },
            ),
            "terminal_new",
        );
        let mut state: GhosttyRenderState = std::ptr::null_mut();
        check(
            ghostty_render_state_new(std::ptr::null(), &mut state),
            "render_state_new",
        );

        ghostty_terminal_vt_write(terminal, LINE.as_ptr(), LINE.len());
        ghostty_terminal_vt_write(terminal, b"\r\n".as_ptr(), 2);
        let second = "第二行测试文字\r\n";
        ghostty_terminal_vt_write(terminal, second.as_ptr(), second.len());

        let cols_seq: [u16; 15] = [55, 50, 45, 40, 35, 30, 26, 22, 18, 14, 10, 14, 18, 26, 60];
        let mut first_failure: Option<(u16, String)> = None;

        for &cols in &cols_seq {
            check(ghostty_terminal_resize(terminal, cols, 24, 0, 0), "resize");
            check(
                ghostty_render_state_update(state, terminal),
                "render_state_update",
            );
            let rows = collect_rows_text(state);
            let full: Vec<&String> = rows.iter().filter(|r| r.contains(LINE)).collect();
            let wrapped_fragments = rows
                .iter()
                .filter(|r| !r.is_empty() && r.contains("白日依山尽") || r.contains("黄河入海流"))
                .count();
            if full.len() > 1 || wrapped_fragments > 2 {
                let snapshot: Vec<String> = rows.iter().take(6).cloned().collect();
                first_failure.get_or_insert((cols, format!("{snapshot:?}")));
            }
        }

        // 回到 60 列后：整行应恰好出现一次，且没有残留折行片段。
        let rows = collect_rows_text(state);
        let full: Vec<&String> = rows.iter().filter(|r| r.contains(LINE)).collect();
        let fragments: Vec<&String> = rows
            .iter()
            .filter(|r| !r.is_empty() && (r.contains("白日依山尽") || r.contains("黄河入海流")))
            .collect();
        println!("final rows (前 8 行): {:?}", &rows[..rows.len().min(8)]);
        println!(
            "final full_line_count={} fragment_rows={}",
            full.len(),
            fragments.len()
        );

        let ok = full.len() == 1 && fragments.len() == 1;
        if let Some((cols, detail)) = first_failure {
            println!("首次异常出现在 cols={cols}: {detail}");
        }
        println!(
            "结果: {}",
            if ok {
                "ALL PASS"
            } else {
                "FAIL（resize 重排出现重复/残留）"
            }
        );
        if !ok {
            std::process::exit(1);
        }

        ghostty_render_state_free(state);
        ghostty_terminal_free(terminal);
    }
}
