//! 工单 22 真机反馈诊断：emoji22 序列的 shaping / 合成画布 / merge 重组。
use fable_render::emoji::{self, EmojiFonts};
use std::path::PathBuf;

fn main() {
    let (apple, noto) = emoji::default_font_paths();
    let mut fonts = EmojiFonts::load(Some(&PathBuf::from(&apple)), Some(&PathBuf::from(&noto)));
    let samples = [
        "🇨🇳",
        "🇺🇸",
        "🇯🇵",
        "🇬🇧",
        "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
        "👨‍👩‍👧‍👦",
        "👩‍👩‍👧‍👦",
        "👨‍👨‍👦",
        "👨‍👩‍👦",
        "👍🏻",
        "👍🏽",
        "👋🏾",
        "🧑🏿",
        "🧑‍🚀",
        "🧑‍💻",
        "👩‍🎓",
        "👨‍🍳",
        "1️⃣",
        "9️⃣",
        "#️⃣",
        "*️⃣",
        "🫖",
        "🫶",
        "⌨️",
    ];
    for s in samples {
        let face = match rustybuzz::Face::from_slice(fonts.apple.as_ref().unwrap().data(), 0) {
            Some(f) => f,
            None => {
                println!("{s}: no face");
                continue;
            }
        };
        let mut buffer = rustybuzz::UnicodeBuffer::new();
        buffer.push_str(s);
        let glyphs = rustybuzz::shape(&face, &[], buffer);
        let infos = glyphs.glyph_infos();
        let positions = glyphs.glyph_positions();
        let mut has_all = true;
        for info in infos.iter() {
            if !fonts.apple.as_ref().unwrap().has_glyph(info.glyph_id) {
                has_all = false;
            }
        }
        let bmp = fonts.rasterize_cluster(s, 24);
        let bmp_desc = match &bmp {
            Some(b) => format!(
                "{}x{} bbox={:?} origin=({},{})",
                b.width, b.height, b.bbox, b.canvas_origin_x, b.canvas_origin_y
            ),
            None => "None".to_string(),
        };
        println!(
            "{s}: gids={} glyphs={:?} all_apple={} -> {bmp_desc}",
            infos.len(),
            infos.iter().map(|g| g.glyph_id).collect::<Vec<_>>(),
            has_all
        );
        for (info, pos) in infos.iter().zip(positions.iter()) {
            println!(
                "    gid={} x_adv={} x_off={} y_off={}",
                info.glyph_id, pos.x_advance, pos.x_offset, pos.y_offset
            );
        }
    }
}
