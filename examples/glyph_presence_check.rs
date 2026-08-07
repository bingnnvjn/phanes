//! 工单 15 真机回归回路：默认字号（约 34-36px）下小写 `j`、`/`、`\`、`|`、
//! `线` 及部分 CJK 缺字（空白格）。
//!
//! 断言：32px 是"缩小两次恢复正常"的阈值基线；34/36/48/60/90/128px 全部
//! 必须产出非空字形位图（修复前 34px 起 j、/、\、|、线 MISSING → 空白格）。
//! 验收命令：`cargo run --release --example glyph_presence_check`。

use fable_render::render_android::GlyphAtlas;

const FONT_SIZES: [f32; 7] = [32.0, 34.0, 36.0, 48.0, 60.0, 90.0, 128.0];
const WATCH: &[char] = &['j', '/', '\\', '|', '线', '不', '日', '水', '，', '。'];

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
    println!("结果: {}", if all_ok { "ALL PASS" } else { "FAIL（存在缺字）" });
    if !all_ok {
        std::process::exit(1);
    }
}
