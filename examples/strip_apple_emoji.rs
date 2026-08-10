//! 工单 22 字体剥离工具：AppleColorEmoji ttc -> 仅 face 0 + sbix 160px 单档。
//!
//! 用法：cargo run --release --example strip_apple_emoji -- <输入.ttc> <输出.ttf>
//! 输出前打印验证摘要（版本串 / strike / PNG 数 / sha256），失败非零退出。

use fable_render::sbix;
use fable_render::sha256;
use std::path::Path;

fn main() {
    let mut args = std::env::args().skip(1);
    let input = args.next().expect("usage: strip_apple_emoji <input.ttc> <output.ttf>");
    let output = args.next().expect("usage: strip_apple_emoji <input.ttc> <output.ttf>");
    let data = std::fs::read(&input).expect("read input");

    // face 0 表目录；表数据偏移相对整个 ttc 文件（TTC 规范）。
    let (face_base, tables) = sbix::table_directory(&data).expect("parse table directory");
    let meta = sbix::font_meta(&data).expect("font meta");
    println!(
        "input: {} bytes, face_base={}, units_per_em={}, glyphs={}",
        data.len(),
        face_base,
        meta.units_per_em,
        meta.glyph_count
    );

    let mut out_tables: Vec<(Vec<u8>, [u8; 4])> = Vec::new();
    for rec in &tables {
        let start = rec.offset; // TTC 表偏移从文件头计
        let end = (start + rec.length).min(data.len());
        if start > end {
            eprintln!("bad table range {:?}", rec.tag);
            std::process::exit(1);
        }
        let mut blob = data[start..end].to_vec();
        if &rec.tag == b"sbix" {
            blob = prune_sbix_to_160(&blob, meta.glyph_count);
        }
        out_tables.push((blob, rec.tag));
    }

    let font = build_sfnt(&out_tables, meta.glyph_count);
    std::fs::write(&output, &font).expect("write output");

    // 校验输出
    verify(&font, meta.glyph_count, &output);
    let digest = sha256::hex(&sha256::sha256(&font));
    println!("output sha256: {digest}");
    println!("strip OK");
}

/// sbix 表：只保留 ppem==160 的 strike；strike blob 原样拷贝（内部
/// glyphDataOffsets 相对 strike 起点，拷贝后依然有效）。
fn prune_sbix_to_160(sbix: &[u8], glyph_count: u32) -> Vec<u8> {
    let num_strikes = u32::from_be_bytes([sbix[4], sbix[5], sbix[6], sbix[7]]) as usize;
    let mut found: Option<(usize, usize)> = None; // (strike_off, strike_len)
    for i in 0..num_strikes {
        let off = u32::from_be_bytes([
            sbix[8 + i * 4],
            sbix[9 + i * 4],
            sbix[10 + i * 4],
            sbix[11 + i * 4],
        ]) as usize;
        let next_off = if i + 1 < num_strikes {
            u32::from_be_bytes([
                sbix[8 + (i + 1) * 4],
                sbix[9 + (i + 1) * 4],
                sbix[10 + (i + 1) * 4],
                sbix[11 + (i + 1) * 4],
            ]) as usize
        } else {
            sbix.len()
        };
        if sbix.get(off..off + 2) == Some(&[0, 160]) {
            found = Some((off, next_off - off));
        }
        let _ = glyph_count;
    }
    let (off, len) = found.expect("160px strike not found");
    let strike = &sbix[off..off + len];
    let mut out = Vec::with_capacity(12 + strike.len());
    out.extend_from_slice(&[0, 1, 0, 0]); // version=1, flags=0
    out.extend_from_slice(&[0, 0, 0, 1]); // numStrikes=1
    out.extend_from_slice(&[0, 0, 0, 12]); // strikeOffset=12
    out.extend_from_slice(strike);
    out
}

fn checksum_table(blob: &[u8]) -> u32 {
    let mut sum: u32 = 0;
    for chunk in blob.chunks(4) {
        let mut word = [0u8; 4];
        word[..chunk.len()].copy_from_slice(chunk);
        sum = sum.wrapping_add(u32::from_be_bytes(word));
    }
    sum
}

fn build_sfnt(tables: &[(Vec<u8>, [u8; 4])], _glyph_count: u32) -> Vec<u8> {
    let num_tables = tables.len();
    let dir_len = 12 + num_tables * 16;
    let mut offset = dir_len;
    // 先 4 字节对齐每个表
    let mut entries: Vec<(Vec<u8>, [u8; 4], u32, u32)> = Vec::new();
    for (blob, tag) in tables {
        let mut padded = blob.clone();
        while padded.len() % 4 != 0 {
            padded.push(0);
        }
        entries.push((padded, *tag, offset as u32, blob.len() as u32));
        offset += entries.last().unwrap().0.len();
    }
    let mut out = vec![0u8; offset];
    out[0..4].copy_from_slice(&[0x00, 0x01, 0x00, 0x00]); // TrueType sfnt
    out[4..6].copy_from_slice(&(num_tables as u16).to_be_bytes());
    // searchRange/entrySelector/rangeShift（规范算法）
    let mut pow2 = 1u32;
    let mut entry_selector = 0u32;
    while pow2 * 2 <= num_tables as u32 {
        pow2 *= 2;
        entry_selector += 1;
    }
    out[6..8].copy_from_slice(&((pow2 * 16) as u16).to_be_bytes());
    out[8..10].copy_from_slice(&(entry_selector as u16).to_be_bytes());
    out[10..12].copy_from_slice(&((num_tables as u32 * 16 - pow2 * 16) as u16).to_be_bytes());
    for (i, (blob, tag, off, len)) in entries.iter().enumerate() {
        let rec = 12 + i * 16;
        out[rec..rec + 4].copy_from_slice(tag);
        let checksum = checksum_table(blob);
        out[rec + 4..rec + 8].copy_from_slice(&checksum.to_be_bytes());
        out[rec + 8..rec + 12].copy_from_slice(&off.to_be_bytes());
        out[rec + 12..rec + 16].copy_from_slice(&len.to_be_bytes());
        let start = *off as usize;
        out[start..start + blob.len()].copy_from_slice(blob);
    }
    // head.checkSumAdjustment：置 0 后整文件 checksum 应 ≡ 0xB1B0AFBA
    let head_rec = tables
        .iter()
        .position(|(_, t)| t == b"head")
        .expect("head table");
    let head_off = entries[head_rec].2 as usize;
    out[head_off + 8..head_off + 12].copy_from_slice(&[0, 0, 0, 0]);
    let whole = checksum_table(&out);
    let adjustment = 0xB1B0AFBAu32.wrapping_sub(whole);
    out[head_off + 8..head_off + 12].copy_from_slice(&adjustment.to_be_bytes());
    out
}

fn verify(font: &[u8], glyph_count: u32, path: &str) {
    let meta = sbix::font_meta(font).expect("output font meta");
    assert_eq!(meta.glyph_count, glyph_count, "glyph 数应保持");
    let version = sbix::name_version(font).unwrap_or_default();
    println!("output version: {version}");
    let strike = sbix::find_strike(font, glyph_count).expect("160 strike");
    println!(
        "output strike: ppem={} ppi={}",
        strike.ppem, strike.ppi
    );
    let pngs = sbix::count_pngs(font, glyph_count);
    println!("output pngs: {pngs}");
    if !version.contains("21.4d3e1") {
        eprintln!("warning: version 串不含 21.4d3e1（{version}）");
    }
    if strike.ppem != 160 || pngs != 3761 {
        eprintln!("unexpected strike/png count (ppem={} pngs={})", strike.ppem, pngs);
        std::process::exit(1);
    }
    let _ = Path::new(path);
}
