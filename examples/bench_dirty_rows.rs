//! 工单 12 ① 基准：seq200 流式下"全量顶点重建" vs "脏行增量重建" CPU 耗时对比。
//! 复刻渲染器的实际数据路径（RenderState → 快照 → 顶点），不接 GPU。

use fable_render::ffi::*;
use fable_render::render_android::{GlyphAtlas, RowVertexStore, Snapshot};
use std::ffi::c_void;
use std::time::Instant;

unsafe fn collect(state: GhosttyRenderState) -> Snapshot {
    let mut colors = GhosttyRenderStateColors {
        size: std::mem::size_of::<GhosttyRenderStateColors>(),
        background: GhosttyColorRgb { r: 0, g: 0, b: 0 },
        foreground: GhosttyColorRgb { r: 0, g: 0, b: 0 },
        cursor: GhosttyColorRgb { r: 0, g: 0, b: 0 },
        cursor_has_value: false,
        palette: [GhosttyColorRgb { r: 0, g: 0, b: 0 }; 256],
    };
    let _ = ghostty_render_state_colors_get(state, &mut colors);
    fable_render::render_android::collect_snapshot(state, &colors, None)
}

fn main() {
    force_tls_pad();
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: 80,
            rows: 24,
            max_scrollback: 10000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        check(
            ghostty_terminal_new(std::ptr::null(), &mut terminal, opts),
            "terminal_new",
        );
        let mut state: GhosttyRenderState = std::ptr::null_mut();
        check(
            ghostty_render_state_new(std::ptr::null(), &mut state),
            "render_state_new",
        );

        let mut atlas = GlyphAtlas::new().expect("atlas");
        let mut store = RowVertexStore::new(80, 24);
        let mut full_us: u128 = 0;
        let mut incr_us: u128 = 0;
        let mut full_bytes: usize = 0;
        let mut incr_bytes: usize = 0;
        let mut frames: u64 = 0;

        // seq200 流式：100 个 chunk，每 chunk 2 行（带 \r\n）。
        for chunk in 0..100usize {
            let mut data = String::new();
            for line in chunk * 2..chunk * 2 + 2 {
                data.push_str(&format!("seq-{line:03}\r\n"));
            }
            ghostty_terminal_vt_write(terminal, data.as_ptr(), data.len());
            check(
                ghostty_render_state_update(state, terminal),
                "render_state_update",
            );
            let snapshot = collect(state);
            reset_dirty(state);

            // 全量：所有行重建 + overlay（旧行为）。
            let start = Instant::now();
            let mut full_payload = Vec::new();
            for row in 0..snapshot.rows as usize {
                let (offset, len) = store.rebuild_row(row, &snapshot, &mut atlas, 800, 480);
                let start_byte = offset as usize;
                full_payload.extend_from_slice(&store.payload()[start_byte..start_byte + len]);
            }
            let (offset, len) = store.rebuild_overlays(&snapshot, &[], 800, 480);
            let start_byte = offset as usize;
            full_payload.extend_from_slice(&store.payload()[start_byte..start_byte + len]);
            full_us = full_us.saturating_add(start.elapsed().as_micros());
            full_bytes = full_bytes.saturating_add(full_payload.len());

            // 增量：只重建脏行 + overlay（新行为）。
            let start = Instant::now();
            let mut ranges = Vec::new();
            let all: Vec<usize> = (0..snapshot.rows as usize).collect();
            let dirty: Vec<usize> =
                if snapshot.dirty == DIRTY_FULL || snapshot.dirty_rows.is_empty() {
                    all
                } else {
                    snapshot.dirty_rows.clone()
                };
            for row in dirty {
                ranges.push(store.rebuild_row(row, &snapshot, &mut atlas, 800, 480));
            }
            ranges.push(store.rebuild_overlays(&snapshot, &[], 800, 480));
            incr_us = incr_us.saturating_add(start.elapsed().as_micros());
            incr_bytes =
                incr_bytes.saturating_add(ranges.iter().map(|(_, len)| len).sum::<usize>());
            frames += 1;
        }

        let full_avg = full_us as f64 / frames as f64;
        let incr_avg = incr_us as f64 / frames as f64;
        println!("== 工单 12 ① 基准 A：seq200 流式（滚屏，核心全行脏）==");
        println!("frames={frames} 80x24 800x480");
        println!("full_build   avg={full_avg:.1}us/frame total={full_us}us bytes={full_bytes}");
        println!("incr_build   avg={incr_avg:.1}us/frame total={incr_us}us bytes={incr_bytes}");
        println!("speedup      {:.2}x", full_avg / incr_avg.max(0.001));
        println!(
            "upload_bytes {:.1}%",
            incr_bytes as f64 * 100.0 / full_bytes.max(1) as f64
        );

        // 基准 B：单行原地重写（\r 不回行），核心只标 1 行脏。
        let mut full_us2: u128 = 0;
        let mut incr_us2: u128 = 0;
        let mut full_bytes2: usize = 0;
        let mut incr_bytes2: usize = 0;
        let mut frames2: u64 = 0;
        let mut store2 = RowVertexStore::new(80, 24);
        for i in 0..100usize {
            let data = format!("seq-{i:03}\r");
            ghostty_terminal_vt_write(terminal, data.as_ptr(), data.len());
            check(
                ghostty_render_state_update(state, terminal),
                "render_state_update",
            );
            let snapshot = collect(state);
            reset_dirty(state);
            let start = Instant::now();
            let mut full_payload = Vec::new();
            for row in 0..snapshot.rows as usize {
                let (offset, len) = store2.rebuild_row(row, &snapshot, &mut atlas, 800, 480);
                let sb = offset as usize;
                full_payload.extend_from_slice(&store2.payload()[sb..sb + len]);
            }
            let (offset, len) = store2.rebuild_overlays(&snapshot, &[], 800, 480);
            let sb = offset as usize;
            full_payload.extend_from_slice(&store2.payload()[sb..sb + len]);
            full_us2 = full_us2.saturating_add(start.elapsed().as_micros());
            full_bytes2 = full_bytes2.saturating_add(full_payload.len());

            let start = Instant::now();
            let mut ranges = Vec::new();
            for row in 0..snapshot.rows as usize {
                if snapshot.dirty == DIRTY_FULL || snapshot.dirty_rows.contains(&row) {
                    ranges.push(store2.rebuild_row(row, &snapshot, &mut atlas, 800, 480));
                }
            }
            ranges.push(store2.rebuild_overlays(&snapshot, &[], 800, 480));
            incr_us2 = incr_us2.saturating_add(start.elapsed().as_micros());
            incr_bytes2 =
                incr_bytes2.saturating_add(ranges.iter().map(|(_, len)| len).sum::<usize>());
            frames2 += 1;
        }
        let full_avg2 = full_us2 as f64 / frames2 as f64;
        let incr_avg2 = incr_us2 as f64 / frames2 as f64;
        println!("\n== 基准 B：单行原地重写（\r，核心只标 1 行脏）==");
        println!("frames={frames2} 80x24 800x480");
        println!("full_build   avg={full_avg2:.1}us/frame total={full_us2}us bytes={full_bytes2}");
        println!("incr_build   avg={incr_avg2:.1}us/frame total={incr_us2}us bytes={incr_bytes2}");
        println!("speedup      {:.2}x", full_avg2 / incr_avg2.max(0.001));
        println!(
            "upload_bytes {:.1}%",
            incr_bytes2 as f64 * 100.0 / full_bytes2.max(1) as f64
        );

        ghostty_render_state_free(state);
        ghostty_terminal_free(terminal);
    }
}

fn check(result: GhosttyResult, what: &str) {
    assert_eq!(result, GHOSTTY_SUCCESS, "{what}: {result}");
}

unsafe fn reset_dirty(state: GhosttyRenderState) {
    let f = false;
    let _ = ghostty_render_state_set(
        state,
        RENDER_STATE_OPTION_DIRTY,
        &f as *const bool as *const c_void,
    );
    let mut it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
    let _ = ghostty_render_state_row_iterator_new(std::ptr::null(), &mut it);
    let _ = ghostty_render_state_get(state, DATA_ROW_ITERATOR, &mut it as *mut _ as *mut c_void);
    while ghostty_render_state_row_iterator_next(it) {
        let _ =
            ghostty_render_state_row_set(it, ROW_OPTION_DIRTY, &f as *const bool as *const c_void);
    }
    ghostty_render_state_row_iterator_free(it);
}
