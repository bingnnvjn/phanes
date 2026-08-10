//! 只读诊断：工单 22 修复前的布局根因验证。
//!
//! 输出 AppleColorEmoji 各样本 cluster 的：
//! - 每个 glyph 的 sbix 位图尺寸 + originOffsetX/Y + hmtx advance（font units）
//! - 字体 upem / hhea / OS/2 垂直 metrics
//! - 两种显示模型的对比：
//!   A. 当前实现：内容 bbox fit 进「2 格宽 × 行高」盒
//!   B. em 盒模型：位图按 origin 摆进 160ppem em 盒，em 盒映射到目标 em 边长
//!
//! 用法：cargo run --bin diag_sbix --release -- <AppleColorEmoji.ttf>

use fable_render::sbix::{self, AppleSbixFont};
use std::path::Path;

fn u16be(data: &[u8], off: usize) -> Option<u16> {
    if off + 2 > data.len() {
        return None;
    }
    Some(u16::from_be_bytes([data[off], data[off + 1]]))
}

fn i16be(data: &[u8], off: usize) -> Option<i16> {
    if off + 2 > data.len() {
        return None;
    }
    Some(i16::from_be_bytes([data[off], data[off + 1]]))
}

fn u32be(data: &[u8], off: usize) -> Option<u32> {
    if off + 4 > data.len() {
        return None;
    }
    Some(u32::from_be_bytes([
        data[off],
        data[off + 1],
        data[off + 2],
        data[off + 3],
    ]))
}

fn table<'a>(data: &'a [u8], tag: &[u8; 4]) -> Option<(usize, usize)> {
    let (face_base, tables) = sbix::table_directory(data)?;
    for t in &tables {
        if &t.tag == tag {
            return Some((face_base + t.offset, t.length));
        }
    }
    None
}

fn hmtx_advance(data: &[u8], gid: u32) -> Option<i32> {
    let (_, maxp) = table(data, b"maxp")?;
    let num_glyphs = u16be(data, maxp + 4)? as u32;
    let (hmtx_off, _) = table(data, b"hmtx")?;
    let (_, hhea) = table(data, b"hhea")?;
    let num_h_metrics = u16be(data, hhea + 34)? as u32;
    if num_h_metrics == 0 {
        return None;
    }
    if gid < num_h_metrics {
        Some(u16be(data, hmtx_off + gid as usize * 4)? as i32)
    } else {
        let last = u16be(data, hmtx_off + (num_h_metrics as usize - 1) * 4)? as i32;
        Some(last)
    }
}

fn main() {
    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "fable-app/app/src/main/assets/fonts/AppleColorEmoji.ttf".to_string());
    let path = Path::new(&path);
    let mut font = AppleSbixFont::open(path, "").expect("open font");
    let data = font.data().to_vec();
    let upem = font.units_per_em as f32;

    // 垂直 metrics。
    let (_, hhea) = table(&data, b"hhea").expect("hhea");
    let h_ascent = i16be(&data, hhea + 4).unwrap_or(0);
    let h_descent = i16be(&data, hhea + 6).unwrap_or(0);
    let h_line_gap = i16be(&data, hhea + 8).unwrap_or(0);
    let (_, os2) = table(&data, b"OS/2").expect("OS/2");
    let typo_ascent = i16be(&data, os2 + 68).unwrap_or(0);
    let typo_descent = i16be(&data, os2 + 70).unwrap_or(0);
    let typo_line_gap = i16be(&data, os2 + 72).unwrap_or(0);
    let use_typo = (u16be(&data, os2 + 62).unwrap_or(0) & 0x80) != 0;

    println!(
        "upem={} strike_ppem={} hhea: ascent={} descent={} line_gap={} | OS/2 typo: ascent={} descent={} line_gap={} use_typo={}",
        upem,
        font.strike_ppem,
        h_ascent,
        h_descent,
        h_line_gap,
        typo_ascent,
        typo_descent,
        typo_line_gap,
        use_typo
    );
    println!();

    let samples: &[&str] = &[
        "🇨🇳", "🇺🇸", "🇧🇷", "🇯🇵", "🏳️‍🌈", "🚩", "🎌", // 旗帜族
        "🔋", "⚡", "🔌", "📱", "⌨️", "🖥️", "🖱️", "🖨️", // 竖条/横条硬件族
        "🚀", "😀", "😂", "❤️", "👍🏻", "👨‍👩‍👧‍👦", "👩‍❤️‍👨", "🧑‍💻", // 通用/ZWJ
        "☠️", "⚙️", "♻️", "🀄", "🕐", "1️⃣", "㊗️", "🈲", // 冷门/组合
    ];

    for text in samples {
        let face = rustybuzz::Face::from_slice(&data, 0).expect("face");
        let mut buffer = rustybuzz::UnicodeBuffer::new();
        buffer.push_str(text);
        let glyphs = rustybuzz::shape(&face, &[], buffer);
        let infos = glyphs.glyph_infos();
        let positions = glyphs.glyph_positions();
        println!("  shaped: gids={} glyphs={:?}", infos.len(), infos.iter().map(|g| g.glyph_id).collect::<Vec<_>>());
        let scale = 160.0 / upem;
        let mut pen_x = 0.0f32;
        let mut rows: Vec<String> = Vec::new();
        let mut min_x = i32::MAX;
        let mut min_y = i32::MAX;
        let mut max_x = i32::MIN;
        let mut max_y = i32::MIN;
        let mut advance_units = 0i32;
        for (info, pos) in infos.iter().zip(positions.iter()) {
            let gid = info.glyph_id as u32;
            let adv = hmtx_advance(&data, gid).unwrap_or(0);
            advance_units += adv;
            let d = font.decode(gid);
            println!(
                "    gid={} pos.x_advance={} pos.x_offset={} (fixed 26.6)",
                gid,
                pos.x_advance,
                pos.x_offset
            );
            match d {
                Some(d) => {
                    let x = pen_x + pos.x_offset as f32 * scale + d.origin_x as f32;
                    let y = 160.0 + pos.y_offset as f32 * scale - d.origin_y as f32 - d.height as f32;
                    rows.push(format!(
                        "gid={:<4} canvas={}x{} bbox={:?} origin=({},{}) adv={}units place=({},{})",
                        gid,
                        d.width,
                        d.height,
                        d.bbox,
                        d.origin_x,
                        d.origin_y,
                        adv,
                        x.round() as i32,
                        y.round() as i32
                    ));
                    min_x = min_x.min(x.round() as i32);
                    min_y = min_y.min(y.round() as i32);
                    max_x = max_x.max(x.round() as i32 + d.width as i32 - 1);
                    max_y = max_y.max(y.round() as i32 + d.height as i32 - 1);
                }
                None => {
                    rows.push(format!("gid={:<4} <no sbix> adv={}units", gid, adv));
                }
            }
            pen_x += pos.x_advance as f32 * scale;
        }
        let bbox_w = (max_x - min_x + 1).max(0);
        let bbox_h = (max_y - min_y + 1).max(0);
        let em_advance = advance_units as f32 * scale; // 160ppem 下 cluster advance
        println!("== {} (gids={})", text, infos.len());
        for r in &rows {
            println!("   {}", r);
        }
        println!(
            "   content bbox @160ppem: {}x{} at ({},{}) | cluster advance={}px",
            bbox_w, bbox_h, min_x, min_y, em_advance
        );
        // 模型 A：bbox fit 2格×行高（示例 40 列 10 行 1080x1920）
        let cell_w = 1080.0f32 / 40.0;
        let row_h = 1920.0f32 / 10.0;
        let target_w = cell_w * 2.0;
        let sa = ((target_w * 0.92) / bbox_w as f32)
            .min((row_h * 0.92) / bbox_h as f32)
            .max(0.01);
        println!(
            "   [A current] fit 2格x行高({}x{}): scale={:.3} -> {}x{}px (行高占比 {:.0}%)",
            target_w,
            row_h,
            sa,
            (bbox_w as f32 * sa).round(),
            (bbox_h as f32 * sa).round(),
            (bbox_h as f32 * sa / row_h * 100.0)
        );
        // 模型 B：em 盒（边长=160px 画布）→ 目标 em 边长 = min(2格宽, 行高)
        let em_target = target_w.min(row_h);
        let eb = em_target / 160.0;
        println!(
            "   [B em-box] 内容占 em 盒 {:.1}% x {:.1}% -> em边长={}px 时 {}x{}px (行高占比 {:.0}%)",
            bbox_w as f32 / 160.0 * 100.0,
            bbox_h as f32 / 160.0 * 100.0,
            em_target,
            (bbox_w as f32 * eb).round(),
            (bbox_h as f32 * eb).round(),
            (bbox_h as f32 * eb / row_h * 100.0)
        );
        println!();
    }
}
