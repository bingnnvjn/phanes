//! 工单 15 真机回归回路：默认字号（约 34-36px）下小写 `j`、`/`、`\`、`|`、
//! `线` 及部分 CJK 缺字（空白格）。
//!
//! 断言：32px 是"缩小两次恢复正常"的阈值基线；34/36/48/60/90/128px 全部
//! 必须产出非空字形位图（修复前 34px 起 j、/、\、|、线 MISSING → 空白格）。
//! 验收命令：`cargo run --release --example glyph_presence_check`。

use fable_render::render_android::{build_row_vertices, Cell, GlyphAtlas, Rgb, Snapshot, Vertex};

const FONT_SIZES: [f32; 7] = [32.0, 34.0, 36.0, 48.0, 60.0, 90.0, 128.0];
const WATCH: &[char] = &['j', '/', '\\', '|', '中', '线', '不', '日', '水', '，', '。'];
const MODE_GLYPH: f32 = 1.0;
const VERTS_PER_RECT: usize = 6;

fn empty_cell() -> Cell {
    Cell {
        text: String::new(),
        fg: None,
        bg: None,
        selected: false,
        underline: false,
        strikethrough: false,
        overline: false,
    }
}

/// 取顶点流中第 n 个 glyph 矩形在表面上的像素范围 (left, right, width)
/// （顶点坐标为归一化 [-1,1]，px = (pos + 1) / 2 * surface_w）。
fn glyph_rect_px(verts: &[Vertex], nth: usize, surface_w: f32) -> Option<(f32, f32, f32)> {
    let mut seen = 0usize;
    for group in verts.chunks_exact(VERTS_PER_RECT) {
        if group.iter().any(|v| (v.mode - MODE_GLYPH).abs() > f32::EPSILON) {
            continue;
        }
        if seen != nth {
            seen += 1;
            continue;
        }
        let x0 = group[0].position[0];
        let x1 = group[1].position[0];
        let left = (x0 + 1.0) / 2.0 * surface_w;
        let right = (x1 + 1.0) / 2.0 * surface_w;
        return Some((left, right, right - left));
    }
    None
}

/// 宽字符（CJK）必须按 2 格目标宽绘制：36px 时中文字形宽应 ≥ 0.65 × 双格宽，
/// 修复前（按单格宽 0.6em 缩放）约 0.44，红；修复后约 0.71，绿。
fn check_wide_char_scale(atlas: &mut GlyphAtlas) -> bool {
    const W: u32 = 88; // 4 列 × 22px（36px 字号的格宽）
    const H: u32 = 48; // 1 行 × 48px（36px 字号的行高）
    let snapshot = Snapshot {
        cols: 4,
        rows: 1,
        lines: vec![vec![
            Cell {
                text: "中".to_string(),
                ..empty_cell()
            },
            empty_cell(),
            Cell {
                text: "A".to_string(),
                ..empty_cell()
            },
            empty_cell(),
        ]],
        cursor: None,
        cursor_style: 0,
        default_fg: Rgb { r: 229, g: 229, b: 229 },
        default_bg: Rgb { r: 31, g: 31, b: 31 },
        cursor_color: Rgb { r: 0, g: 0, b: 255 },
        dirty: 0,
        dirty_rows: Vec::new(),
        selection_color: Rgb { r: 0, g: 0, b: 255 },
        palette: None,
        ansi_override: None,
    };
    let verts = build_row_vertices(0, &snapshot.lines[0], &snapshot, atlas, W, H);
    let cjk = glyph_rect_px(&verts, 0, W as f32).unwrap_or((0.0, 0.0, 0.0));
    let ascii = glyph_rect_px(&verts, 1, W as f32).unwrap_or((0.0, 0.0, 0.0));
    let target_wide = 2.0 * W as f32 / snapshot.cols as f32;
    let ratio = cjk.2 / target_wide;
    println!(
        "  wide-char 中 left={:.1} right={:.1} draw_w={:.1}px target={:.1}px ratio={:.2}",
        cjk.0, cjk.1, cjk.2, target_wide, ratio
    );
    println!("  ascii A draw_w={:.1}px", ascii.2);
    // 字形必须完整落在双格盒内（右缘不得溢出到下一列），且占比达标。
    let contained = cjk.0 >= -1.0 && cjk.1 <= target_wide + 1.0;
    let ok = ratio > 0.65 && contained && ascii.2 > 0.0;
    println!(
        "  => {} wide-char scale/containment (CJK 按双格宽绘制且不溢出)",
        if ok { "PASS" } else { "FAIL" }
    );
    ok
}

fn main() {
    let mut atlas = GlyphAtlas::new().expect("atlas");
    let mut all_ok = true;
    for px in FONT_SIZES {
        atlas.set_pixels_per_em(px);
        let cell = atlas.cell_size();
        println!("font_size={px} cell_size={}x{}", cell.0, cell.1);
        let mut missing = Vec::new();
        for &ch in WATCH {
            match atlas.ensure_glyph(ch) {
                Some(entry) if entry.bitmap_w > 0 && entry.bitmap_h > 0 => {
                    println!("  {ch:?} OK bitmap={}x{}", entry.bitmap_w, entry.bitmap_h);
                }
                Some(entry) => missing.push(format!(
                    "{ch:?} empty bitmap={}x{}",
                    entry.bitmap_w, entry.bitmap_h
                )),
                None => missing.push(format!("{ch:?} MISSING")),
            }
        }
        if missing.is_empty() {
            println!("  => PASS at {px}px");
        } else {
            println!("  => FAIL at {px}px: {}", missing.join(", "));
            all_ok = false;
        }
    }
    atlas.set_pixels_per_em(36.0);
    all_ok &= check_wide_char_scale(&mut atlas);
    println!("结果: {}", if all_ok { "ALL PASS" } else { "FAIL（存在缺字）" });
    if !all_ok {
        std::process::exit(1);
    }
}
