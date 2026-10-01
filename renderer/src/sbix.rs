//! 工单 22：Apple Color Emoji sbix 通路。
//!
//! Rust 自解析 sfnt/sbix/cmap/name/maxp/head（不重开 FreeType PNG、不新增
//! C 依赖），恒 160px 档解码 + 双线性 + 线性空间缩放；512 张 LRU 缓存
//! 160px 原图；文件经 mmap 读取（mmap_shim.c）。Noto COLRv1 兜底仍走
//! colr.rs/FreeType（保留，见 ADR-0007 决策 5）。

use crate::sha256;
use std::collections::HashMap;
use std::collections::VecDeque;
use std::path::Path;

pub const SBIX_STRIKE_PPEM: u16 = 160;
pub const LRU_CAPACITY: usize = 512;

// ---------------------------------------------------------------- mmap shim

extern "C" {
    fn fable_mmap_file(path: *const std::ffi::c_char, out_len: *mut usize) -> *const u8;
    fn fable_munmap(ptr: *const u8, len: usize) -> i32;
}

/// mmap 只读文件映射（不拷贝进堆，63MB 级字体不翻倍内存）。
pub struct MappedFont {
    ptr: *const u8,
    len: usize,
}

// SAFETY: mmap 区域只读；指针/长度来自成功的 fable_mmap_file，Drop 只在最后一个
// 所有者释放映射。并发访问仅产生不可变切片，不修改映射内容。
unsafe impl Send for MappedFont {}
// SAFETY: 同上；只读映射可在多个线程共享，释放由唯一 Drop 所有者完成。
unsafe impl Sync for MappedFont {}

impl MappedFont {
    pub fn open(path: &Path) -> Option<Self> {
        let c_path = std::ffi::CString::new(path.as_os_str().to_str()?).ok()?;
        let mut len: usize = 0;
        // SAFETY: `c_path` is NUL-terminated and `out_len` points to a live
        // local. The shim returns a read-only mapping or null.
        let ptr = unsafe { fable_mmap_file(c_path.as_ptr(), &mut len) };
        if ptr.is_null() || len == 0 {
            if !ptr.is_null() {
                // SAFETY: this branch owns the mapping returned above and has
                // not exposed it, so this is its single matching unmap.
                unsafe { fable_munmap(ptr, len) };
            }
            return None;
        }
        Some(Self { ptr, len })
    }

    pub fn as_slice(&self) -> &[u8] {
        // SAFETY: `open` stores only a non-null, non-empty live mapping and
        // Drop cannot unmap it while this shared borrow is alive.
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }
}

impl Drop for MappedFont {
    fn drop(&mut self) {
        if !self.ptr.is_null() {
            // SAFETY: `MappedFont` owns this mapping and Drop runs once, so
            // this matches the successful `fable_mmap_file` call exactly once.
            unsafe { fable_munmap(self.ptr, self.len) };
        }
    }
}

// ---------------------------------------------------------------- sfnt 读取

fn u16be(data: &[u8], off: usize) -> Option<u16> {
    if off + 2 > data.len() {
        return None;
    }
    Some(u16::from_be_bytes([data[off], data[off + 1]]))
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

pub struct TableRecord {
    pub tag: [u8; 4],
    pub offset: usize,
    pub length: usize,
}

/// 解析 sfnt 表目录；ttc 自动取 face 0。返回（face 起始偏移, 表目录）。
pub fn table_directory(data: &[u8]) -> Option<(usize, Vec<TableRecord>)> {
    if data.len() < 12 {
        return None;
    }
    let sfnt = u32be(data, 0)?;
    let face_base = if sfnt == 0x74746366 {
        // 'ttcf'
        if data.len() < 16 {
            return None;
        }
        let num_fonts = u32be(data, 8)?;
        if num_fonts == 0 {
            return None;
        }
        u32be(data, 12)? as usize
    } else {
        0
    };
    if face_base + 6 > data.len() {
        return None;
    }
    let num_tables = u16be(data, face_base + 4)? as usize;
    let mut tables = Vec::with_capacity(num_tables);
    for i in 0..num_tables {
        let rec = face_base + 12 + i * 16;
        if rec + 16 > data.len() {
            return None;
        }
        tables.push(TableRecord {
            tag: [data[rec], data[rec + 1], data[rec + 2], data[rec + 3]],
            offset: u32be(data, rec + 8)? as usize,
            length: u32be(data, rec + 12)? as usize,
        });
    }
    Some((face_base, tables))
}

fn find_table<'a>(
    data: &'a [u8],
    face_base: usize,
    tables: &[TableRecord],
    tag: &[u8; 4],
) -> Option<&'a [u8]> {
    let rec = tables.iter().find(|r| &r.tag == tag)?;
    let _ = face_base;
    // TTC 与单 face sfnt 的表偏移均相对文件头。
    let start = rec.offset;
    let end = (start + rec.length).min(data.len());
    if start > end {
        return None;
    }
    Some(&data[start..end])
}

// ---------------------------------------------------------------- cmap

/// 从 cmap 提取 (codepoint, gid) 列表（format 4 + 12；覆盖报告/诊断用，
/// 整形仍走 rustybuzz）。
pub fn cmap_codepoints(data: &[u8]) -> Vec<(u32, u32)> {
    let mut out = Vec::new();
    let Some((_, tables)) = table_directory(data) else {
        return out;
    };
    let Some(cmap) = find_table(data, 0, &tables, b"cmap") else {
        return out;
    };
    if cmap.len() < 4 {
        return out;
    }
    let n = u16be(cmap, 2).unwrap_or(0);
    for i in 0..n {
        let rec = 4 + i as usize * 8;
        if rec + 8 > cmap.len() {
            break;
        }
        let Some(sub_off) = u32be(cmap, rec + 4) else {
            break;
        };
        let Some(sub) = cmap.get(sub_off as usize..) else {
            break;
        };
        match u16be(sub, 0).unwrap_or(0) {
            4 => parse_cmap4(sub, &mut out),
            12 => parse_cmap12(sub, &mut out),
            _ => {}
        }
    }
    out.sort_unstable();
    out.dedup_by_key(|(cp, _)| *cp);
    out
}

fn parse_cmap4(sub: &[u8], out: &mut Vec<(u32, u32)>) {
    let Some(seg_count_x2) = u16be(sub, 6) else {
        return;
    };
    let seg_count = seg_count_x2 as usize / 2;
    if seg_count == 0 || sub.len() < 14 + seg_count * 8 {
        return;
    }
    let end_off = 14;
    let start_off = end_off + seg_count * 2 + 2;
    let delta_off = start_off + seg_count * 2;
    let range_off = delta_off + seg_count * 2;
    for i in 0..seg_count {
        let end = u16be(sub, end_off + i * 2).unwrap_or(0) as u32;
        let start = u16be(sub, start_off + i * 2).unwrap_or(0) as u32;
        let delta = u16be(sub, delta_off + i * 2).unwrap_or(0) as i16;
        let range_offset = u16be(sub, range_off + i * 2).unwrap_or(0) as usize;
        if start > end {
            continue;
        }
        if range_offset == 0 {
            for c in start..=end {
                let gid = (c as i64 + delta as i64) & 0xFFFF;
                if gid != 0 {
                    out.push((c, gid as u32));
                }
            }
        } else {
            let base = range_off + i * 2;
            for c in start..=end {
                let gid_addr = base + range_offset + (c - start) as usize * 2;
                let Some(raw) = u16be(sub, gid_addr) else {
                    break;
                };
                if raw != 0 {
                    let gid = (raw as i64 + delta as i64) & 0xFFFF;
                    if gid != 0 {
                        out.push((c, gid as u32));
                    }
                }
            }
        }
    }
}

fn parse_cmap12(sub: &[u8], out: &mut Vec<(u32, u32)>) {
    let Some(n_groups) = u32be(sub, 12) else {
        return;
    };
    for i in 0..n_groups as usize {
        let off = 16 + i * 12;
        let Some(start) = u32be(sub, off) else { break };
        let Some(end) = u32be(sub, off + 4) else {
            break;
        };
        let Some(gid0) = u32be(sub, off + 8) else {
            break;
        };
        if start > end {
            continue;
        }
        for c in start..=end {
            let gid = gid0 + (c - start);
            if gid != 0 {
                out.push((c, gid));
            }
        }
    }
}

// ---------------------------------------------------------------- name

/// nameID 5（版本串）。Apple 字体版本串形如 Version 21.4d3e1。
pub fn name_version(data: &[u8]) -> Option<String> {
    let (_, tables) = table_directory(data)?;
    let name = find_table(data, 0, &tables, b"name")?;
    if name.len() < 6 {
        return None;
    }
    let count = u16be(name, 2)? as usize;
    let string_off = u16be(name, 4)? as usize;
    let mut best: Option<String> = None;
    for i in 0..count {
        let rec = 6 + i * 12;
        if rec + 12 > name.len() {
            break;
        }
        let platform = u16be(name, rec)?;
        let name_id = u16be(name, rec + 6)?;
        let len = u16be(name, rec + 8)? as usize;
        let off = u16be(name, rec + 10)? as usize + string_off;
        if name_id != 5 {
            continue;
        }
        let Some(bytes) = name.get(off..off + len) else {
            continue;
        };
        let text = if platform == 0 || platform == 3 {
            decode_utf16be(bytes)
        } else {
            // platform 1 = Mac Roman；ASCII 子集直接按 latin1 取
            bytes.iter().map(|b| *b as char).collect()
        };
        if !text.is_empty() {
            best = Some(text);
            break;
        }
    }
    best
}

fn decode_utf16be(bytes: &[u8]) -> String {
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|c| u16::from_be_bytes([c[0], c[1]]))
        .collect();
    String::from_utf16_lossy(&units)
}

// ---------------------------------------------------------------- head/maxp

pub struct FontMeta {
    pub units_per_em: u16,
    pub glyph_count: u32,
    pub font_revision: u32,
}

pub fn font_meta(data: &[u8]) -> Option<FontMeta> {
    let (face_base, tables) = table_directory(data)?;
    let head = find_table(data, face_base, &tables, b"head")?;
    let maxp = find_table(data, face_base, &tables, b"maxp")?;
    let units_per_em = u16be(head, 18)?;
    let font_revision = u32be(head, 4)?;
    let glyph_count = u16be(maxp, 4)? as u32;
    Some(FontMeta {
        units_per_em,
        glyph_count,
        font_revision,
    })
}

// ---------------------------------------------------------------- sbix

pub struct SbixStrike {
    pub ppem: u16,
    pub ppi: u16,
    glyph_offsets: Vec<u32>,
    strike_abs: usize,
}

impl SbixStrike {
    /// `glyphDataOffsets[i] == glyphDataOffsets[i+1]` 表示无图（dupe/缺失）。
    /// 边界保护：损坏/过短的 glyph 记录返回 None（决策 7：单字形失败回退，
    /// 不崩溃）。
    pub fn glyph_data<'a>(&self, data: &'a [u8], gid: u32) -> Option<SbixGlyph<'a>> {
        let gid = gid as usize;
        if gid + 1 >= self.glyph_offsets.len() {
            return None;
        }
        let o0 = self.glyph_offsets[gid] as usize;
        let o1 = self.glyph_offsets[gid + 1] as usize;
        if o1 <= o0 {
            return None;
        }
        let start = self.strike_abs + o0;
        let end = self.strike_abs + o1;
        let raw = data.get(start..end)?;
        if raw.len() < 8 {
            return None;
        }
        let origin_x = i16::from_be_bytes([raw[0], raw[1]]);
        let origin_y = i16::from_be_bytes([raw[2], raw[3]]);
        let graphic_type = [raw[4], raw[5], raw[6], raw[7]];
        if &graphic_type != b"png " {
            return None;
        }
        let png = &raw[8..];
        if png.is_empty() {
            return None;
        }
        Some(SbixGlyph {
            origin_x: origin_x as i32,
            origin_y: origin_y as i32,
            png,
        })
    }
}

pub struct SbixGlyph<'a> {
    pub origin_x: i32,
    pub origin_y: i32,
    pub png: &'a [u8],
}

/// 从字体字节解析 sbix 表并定位 160px strike（无借用的自包含结构，
/// glyph 数据按需从字体字节读取）。
pub fn find_strike(data: &[u8], glyph_count: u32) -> Option<SbixStrike> {
    let (face_base, tables) = table_directory(data)?;
    let sbix = find_table(data, face_base, &tables, b"sbix")?;
    if sbix.len() < 8 {
        return None;
    }
    let num_strikes = u32be(sbix, 4)? as usize;
    if num_strikes == 0 {
        return None;
    }
    for i in 0..num_strikes {
        let off = u32be(sbix, 8 + i * 4)? as usize;
        let strike = sbix.get(off..)?;
        let ppem = u16be(strike, 0)?;
        if ppem != SBIX_STRIKE_PPEM {
            continue;
        }
        let ppi = u16be(strike, 2)?;
        let count = glyph_count as usize + 1;
        if strike.len() < 4 + count * 4 {
            return None;
        }
        let mut glyph_offsets = Vec::with_capacity(count);
        for g in 0..count {
            glyph_offsets.push(u32be(strike, 4 + g * 4)?);
        }
        let sbix_abs = tables
            .iter()
            .find(|r| &r.tag == b"sbix")
            .map(|r| r.offset)
            .unwrap_or(0);
        return Some(SbixStrike {
            ppem,
            ppi,
            glyph_offsets,
            strike_abs: sbix_abs + off,
        });
    }
    None
}

/// 160 档 PNG 数量（dupe 记录跳过；验收项 3761）。
pub fn count_pngs(data: &[u8], glyph_count: u32) -> usize {
    let Some(strike) = find_strike(data, glyph_count) else {
        return 0;
    };
    (0..glyph_count)
        .filter(|&gid| strike.glyph_data(data, gid).is_some())
        .count()
}

// ---------------------------------------------------------------- png 解码

pub struct DecodedGlyph {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
    pub origin_x: i32,
    pub origin_y: i32,
    /// 非透明包围盒 (x, y, w, h)，用于视觉居中。
    pub bbox: (u32, u32, u32, u32),
}

/// png crate 解码到直通 RGBA8（支持 8/16bit、灰度/灰度 alpha/RGB/RGBA）。
pub fn decode_png_rgba(png: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
    let decoder = png::Decoder::new(std::io::Cursor::new(png));
    let mut reader = decoder.read_info().ok()?;
    let out_len = reader.output_buffer_size().unwrap_or(0);
    if out_len == 0 {
        return None;
    }
    let mut buf = vec![0u8; out_len];
    let info = reader.next_frame(&mut buf).ok()?;
    let w = info.width;
    let h = info.height;
    let bit_depth = info.bit_depth as usize;
    let color_type = info.color_type;
    let mut rgba = Vec::with_capacity((w as usize * h as usize * 4) as usize);
    use png::ColorType as Ct;
    match (color_type, bit_depth) {
        (Ct::Rgba, 8) => rgba.extend_from_slice(&buf[..(w as usize * h as usize * 4)]),
        (Ct::Rgba, 16) => {
            for px in buf.chunks_exact(8) {
                for c in 0..4 {
                    let v = u16::from_be_bytes([px[c * 2], px[c * 2 + 1]]);
                    rgba.push((v >> 8) as u8);
                }
            }
        }
        (Ct::Rgb, 8) => {
            for px in buf.chunks_exact(3) {
                rgba.extend_from_slice(&[px[0], px[1], px[2], 255]);
            }
        }
        (Ct::Rgb, 16) => {
            for px in buf.chunks_exact(6) {
                for c in 0..3 {
                    let v = u16::from_be_bytes([px[c * 2], px[c * 2 + 1]]);
                    rgba.push((v >> 8) as u8);
                }
                rgba.push(255);
            }
        }
        (Ct::Grayscale, 8) => {
            for &g in buf.iter().take(w as usize * h as usize) {
                rgba.extend_from_slice(&[g, g, g, 255]);
            }
        }
        (Ct::GrayscaleAlpha, 8) => {
            for px in buf.chunks_exact(2).take(w as usize * h as usize) {
                rgba.extend_from_slice(&[px[0], px[0], px[0], px[1]]);
            }
        }
        (Ct::GrayscaleAlpha, 16) => {
            for px in buf.chunks_exact(4).take(w as usize * h as usize) {
                let g = u16::from_be_bytes([px[0], px[1]]) >> 8;
                let a = u16::from_be_bytes([px[2], px[3]]) >> 8;
                rgba.extend_from_slice(&[g as u8, g as u8, g as u8, a as u8]);
            }
        }
        // Apple sbix PNG 为 Indexed（调色板）：png crate 不展开，输出是每像素
        // 1 字节调色板索引；这里自行应用 palette + tRNS（透明度）。
        (Ct::Indexed, 8) => {
            let palette = reader.info().palette.clone().unwrap_or_default();
            let trns = reader.info().trns.clone();
            if palette.len() < 3 {
                return None;
            }
            for &idx in buf.iter().take(w as usize * h as usize) {
                let pi = idx as usize * 3;
                if pi + 2 < palette.len() {
                    let a = trns
                        .as_ref()
                        .and_then(|t| t.get(idx as usize))
                        .copied()
                        .unwrap_or(255);
                    rgba.extend_from_slice(&[palette[pi], palette[pi + 1], palette[pi + 2], a]);
                } else {
                    rgba.extend_from_slice(&[0, 0, 0, 255]);
                }
            }
        }
        _ => return None,
    }
    Some((w, h, rgba))
}

/// 计算非透明包围盒；全透明返回 (0,0,0,0)。
pub fn opaque_bbox(w: u32, h: u32, rgba: &[u8]) -> (u32, u32, u32, u32) {
    let mut min_x = w;
    let mut min_y = h;
    let mut max_x = 0u32;
    let mut max_y = 0u32;
    for y in 0..h {
        for x in 0..w {
            if rgba[((y * w + x) * 4 + 3) as usize] != 0 {
                min_x = min_x.min(x);
                min_y = min_y.min(y);
                max_x = max_x.max(x);
                max_y = max_y.max(y);
            }
        }
    }
    if max_x < min_x || max_y < min_y {
        (0, 0, 0, 0)
    } else {
        (min_x, min_y, max_x - min_x + 1, max_y - min_y + 1)
    }
}

// ---------------------------------------------------------------- 缩放

fn srgb_to_linear(v: u8) -> f32 {
    let c = v as f32 / 255.0;
    if c <= 0.04045 {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).powf(2.4)
    }
}

fn linear_to_srgb(v: f32) -> u8 {
    let c = v.clamp(0.0, 1.0);
    let out = if c <= 0.0031308 {
        c * 12.92
    } else {
        1.055 * c.powf(1.0 / 2.4) - 0.055
    };
    (out * 255.0 + 0.5).min(255.0) as u8
}

/// 双线性 + 线性空间 gamma 校正（预乘通道插值，避免透明边缘灰边）。
/// src 为直通 RGBA8；输出 tw x th RGBA8。
pub fn scale_rgba(src: &[u8], sw: u32, sh: u32, tw: u32, th: u32) -> Option<Vec<u8>> {
    if sw == 0 || sh == 0 || tw == 0 || th == 0 {
        return None;
    }
    if tw == sw && th == sh {
        return Some(src.to_vec());
    }
    let mut out = vec![0u8; (tw * th * 4) as usize];
    let sx = sw as f32 / tw as f32;
    let sy = sh as f32 / th as f32;
    for dy in 0..th {
        let yf = (dy as f32 + 0.5) * sy - 0.5;
        let y0 = yf.floor().max(0.0) as u32;
        let y1 = (y0 + 1).min(sh - 1);
        let fy = yf - y0 as f32;
        for dx in 0..tw {
            let xf = (dx as f32 + 0.5) * sx - 0.5;
            let x0 = xf.floor().max(0.0) as u32;
            let x1 = (x0 + 1).min(sw - 1);
            let fx = xf - x0 as f32;
            // 预乘 + 线性空间双线性
            let mut acc = [0.0f32; 4];
            for (yy, wy) in [(y0, 1.0 - fy), (y1, fy)] {
                for (xx, wx) in [(x0, 1.0 - fx), (x1, fx)] {
                    let i = ((yy * sw + xx) * 4) as usize;
                    let a = src[i + 3] as f32 / 255.0;
                    let wgt = wx * wy;
                    for c in 0..3 {
                        acc[c] += srgb_to_linear(src[i + c]) * a * wgt;
                    }
                    acc[3] += a * wgt;
                }
            }
            let o = ((dy * tw + dx) * 4) as usize;
            let a = acc[3].clamp(0.0, 1.0);
            if a <= 0.0 {
                out[o..o + 4].copy_from_slice(&[0, 0, 0, 0]);
            } else {
                for c in 0..3 {
                    let lin = (acc[c] / a).clamp(0.0, 1.0);
                    out[o + c] = linear_to_srgb(lin);
                }
                out[o + 3] = (a * 255.0 + 0.5).min(255.0) as u8;
            }
        }
    }
    Some(out)
}

// ---------------------------------------------------------------- LRU

pub struct LruCache<T> {
    cap: usize,
    map: HashMap<u32, T>,
    clock: VecDeque<u32>,
}

impl<T> LruCache<T> {
    pub fn new(cap: usize) -> Self {
        Self {
            cap: cap.max(1),
            map: HashMap::new(),
            clock: VecDeque::new(),
        }
    }

    pub fn get(&mut self, key: u32) -> Option<&T> {
        if self.map.contains_key(&key) {
            self.clock.retain(|&k| k != key);
            self.clock.push_back(key);
            self.map.get(&key)
        } else {
            None
        }
    }

    pub fn insert(&mut self, key: u32, value: T) {
        if self.map.contains_key(&key) {
            self.clock.retain(|&k| k != key);
            self.map.insert(key, value);
            self.clock.push_back(key);
            return;
        }
        while self.map.len() >= self.cap {
            let Some(k) = self.clock.pop_front() else {
                break;
            };
            if self.map.remove(&k).is_some() {
                break;
            }
        }
        self.map.insert(key, value);
        self.clock.push_back(key);
    }

    pub fn contains(&self, key: u32) -> bool {
        self.map.contains_key(&key)
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    pub fn clear(&mut self) {
        self.map.clear();
        self.clock.clear();
    }
}

// ---------------------------------------------------------------- 字体封装

/// Apple Color Emoji（sbix 160 单档 + LRU）。
pub struct AppleSbixFont {
    pub mapped: MappedFont,
    pub units_per_em: u16,
    pub glyph_count: u32,
    pub version: String,
    pub sha256_hex: String,
    pub strike_ppem: u16,
    png_count: usize,
    strike: SbixStrike,
    cache: LruCache<DecodedGlyph>,
}

impl AppleSbixFont {
    pub fn open(path: &Path, expected_sha256: &str) -> Option<Self> {
        let mapped = MappedFont::open(path)?;
        let data = mapped.as_slice();
        let digest_hex = sha256::hex(&sha256::sha256(data));
        if !expected_sha256.is_empty() && digest_hex != expected_sha256 {
            return None;
        }
        let meta = font_meta(data)?;
        let glyph_count = meta.glyph_count;
        let strike = find_strike(data, glyph_count)?;
        let version = name_version(data).unwrap_or_else(|| {
            let rev = meta.font_revision;
            format!("{}.{:04x}", rev >> 16, rev & 0xffff)
        });
        let png_count = count_pngs(data, glyph_count);
        Some(Self {
            mapped,
            units_per_em: meta.units_per_em,
            glyph_count,
            version,
            sha256_hex: digest_hex,
            strike_ppem: strike.ppem,
            png_count,
            strike,
            cache: LruCache::new(LRU_CAPACITY),
        })
    }

    pub fn data(&self) -> &[u8] {
        self.mapped.as_slice()
    }

    pub fn has_glyph(&self, gid: u32) -> bool {
        self.strike.glyph_data(self.data(), gid).is_some()
    }

    /// 解码 160 档 PNG（LRU 缓存；失败返回 None -> 上层走 Noto 兜底）。
    pub fn decode(&mut self, gid: u32) -> Option<&DecodedGlyph> {
        if !self.cache.contains(gid) {
            let glyph = self.strike.glyph_data(self.data(), gid)?;
            let (w, h, rgba) = decode_png_rgba(glyph.png)?;
            let bbox = opaque_bbox(w, h, &rgba);
            if bbox.2 == 0 || bbox.3 == 0 {
                return None;
            }
            let decoded = DecodedGlyph {
                width: w,
                height: h,
                pixels: rgba,
                origin_x: glyph.origin_x,
                origin_y: glyph.origin_y,
                bbox,
            };
            self.cache.insert(gid, decoded);
        }
        self.cache.get(gid)
    }

    pub fn cache_len(&self) -> usize {
        self.cache.len()
    }

    pub fn png_count(&self) -> usize {
        self.png_count
    }
}

// ---------------------------------------------------------------- 工具函数

/// emoji 码位判定（覆盖报告用；与 emoji.rs is_emoji 同范围）。
pub fn is_emoji_codepoint(cp: u32) -> bool {
    matches!(
        cp,
        0x1F000..=0x1FAFF | 0x2600..=0x27BF | 0x2300..=0x23FF | 0x2B00..=0x2BFF
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lru_basics() {
        let mut c: LruCache<i32> = LruCache::new(2);
        c.insert(1, 10);
        c.insert(2, 20);
        assert!(c.get(1).is_some());
        c.insert(3, 30);
        assert!(c.get(2).is_none(), "LRU 应淘汰最久未用（2）");
        assert!(c.get(1).is_some());
        assert!(c.get(3).is_some());
    }

    #[test]
    fn scale_identity() {
        let src = vec![
            255u8, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255,
        ];
        let out = scale_rgba(&src, 2, 2, 2, 2).unwrap();
        assert_eq!(out, src);
    }

    #[test]
    fn scale_half_keeps_shape() {
        let src = [255u8, 0, 0, 255].repeat(16);
        let out = scale_rgba(&src, 4, 4, 2, 2).unwrap();
        assert_eq!(out.len(), 2 * 2 * 4);
        assert_eq!(out[3], 255);
        assert!(out[0] > 200);
    }

    #[test]
    fn sha256_roundtrip() {
        let h = sha256::hex(&sha256::sha256(b"fable-font-verify"));
        assert_eq!(h.len(), 64);
    }
}
