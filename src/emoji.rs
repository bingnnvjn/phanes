//! 彩色 emoji 字形（工单 22：Apple sbix 160 单档主通路 + Noto COLRv1 兜底）。
//!
//! 通路：emoji run（含 ZWJ 序列）先经 rustybuzz 用 Apple 字体整形，逐 glyph
//! 查 sbix PNG（`sbix.rs`，恒 160 解码 + LRU），合成后按目标字号双线性 +
//! 线性空间缩放；任一 glyph 无图 -> 整段 cluster 回退 Noto COLRv1
//! （`colr.rs`/FreeType 保留，决策 5）；都无 -> None（上层画主题方框）。
//! 灰度正文仍 fontdue 不动；字体改为 APK assets 运行时 mmap 加载
//! （不再 include_bytes 内嵌，决策 4）。

use crate::colr;
use crate::freetype_ffi::*;
use crate::sbix;
use std::path::Path;

pub struct RgbaBitmap {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
    /// 非透明包围盒 (x, y, w, h)，用于视觉居中。
    pub bbox: Option<(u32, u32, u32, u32)>,
}

/// 是否为彩色 emoji 字符（宽 2 格）。
pub fn is_emoji(ch: char) -> bool {
    let c = ch as u32;
    matches!(
        c,
        0x1F000..=0x1FAFF
            | 0x2600..=0x27BF
            | 0x2300..=0x23FF
            | 0x2B00..=0x2BFF
    )
}

/// 整段文本是否为 emoji run：单字符 emoji 或含 ZWJ / VS16 / 肤色修饰 /
/// 区域指示符等组合（家庭、肤色、旗帜、键帽等序列都命中）。
pub fn is_emoji_run(text: &str) -> bool {
    if text.is_empty() {
        return false;
    }
    text.chars().any(|ch| {
        if is_emoji(ch) {
            return true;
        }
        matches!(ch as u32, 0x200D | 0xFE0F | 0x1F3FB..=0x1F3FF | 0x1F1E6..=0x1F1FF)
    })
}

/// 字体加载状态（诊断输出：正常/降级/缺失）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FontStatus {
    Normal,
    Degraded,
    Missing,
}

impl FontStatus {
    pub fn label(&self) -> &'static str {
        match self {
            FontStatus::Normal => "正常",
            FontStatus::Degraded => "降级",
            FontStatus::Missing => "缺失",
        }
    }
}

/// 期望 sha256（与 scripts/fetch-emoji-fonts.sh 强制校验值一致；
/// 换字体时按 docs/fonts/换字体步骤.md 同步更新）。
pub const APPLE_EXPECTED_SHA256: &str =
    "6f6ad8b9751356c5707ab9e2645cddc3521d116a6b387d7b4b1437456d3784a3";
pub const NOTO_EXPECTED_SHA256: &str =
    "0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2";

/// 启动预热前 50 个热门 emoji（启动后 2s 内后台解码，指标见 ADR-0007）。
pub const POPULAR_EMOJI_50: &[&str] = &[
    "😀", "😂", "😍", "😢", "😡", "🥺", "😱", "😭", "😅", "😉", "😊", "😎", "🤔",
    "🤣", "😇", "🙃", "😴", "🤯", "😬", "😐", "🤗", "🤭", "🫡", "🥰", "😘", "🤩",
    "🥳", "😜", "🤪", "😝", "🧐", "🤓", "😏", "😒", "😞", "😔", "😟", "😕", "🙁",
    "😣", "😖", "😫", "😩", "🥱", "😤", "😠", "😈", "👿", "💀", "❤️",
];

/// 宿主/自检默认字体路径：优先环境变量，否则 spike-render 相对 fable-app
/// assets 目录（与 APK 内同一份文件）。
pub fn default_font_paths() -> (String, String) {
    let manifest = env!("CARGO_MANIFEST_DIR");
    let dir = std::path::Path::new(manifest).join("../fable-app/app/src/main/assets/fonts");
    let apple = std::env::var("FABLE_APPLE_EMOJI")
        .unwrap_or_else(|_| dir.join("AppleColorEmoji.ttf").display().to_string());
    let noto = std::env::var("FABLE_NOTO_EMOJI")
        .unwrap_or_else(|_| dir.join("NotoColorEmoji.ttf").display().to_string());
    (apple, noto)
}

// ================================================================ Noto 兜底

pub struct EmojiFont {
    data: Vec<u8>,
    library: FT_Library,
    face: FT_Face,
    units_per_em: u16,
    palette: *const FT_Color,
    palette_len: usize,
}

/// FT_FACE_FLAG_COLOR（字体是否带彩色字形表）。
const FT_FACE_FLAG_COLOR: FT_Long = 1 << 14;

impl EmojiFont {
    /// 从任意字体字节构造（失败返回 None）。
    pub fn from_bytes(data: Vec<u8>) -> Option<Self> {
        Self::open(data)
    }

    fn open(data: Vec<u8>) -> Option<Self> {
        unsafe {
            let mut library = std::ptr::null_mut();
            if FT_Init_FreeType(&mut library) != 0 {
                return None;
            }
            let mut face: FT_Face = std::ptr::null_mut();
            let err = FT_New_Memory_Face(
                library,
                data.as_ptr(),
                data.len() as FT_Long,
                0,
                &mut face,
            );
            if err != 0 || face.is_null() {
                FT_Done_FreeType(library);
                return None;
            }
            let units_per_em = (*face).units_per_em;
            let mut palette: *const FT_Color = std::ptr::null();
            let mut palette_len = 0usize;
            let mut pd = FT_Palette_Data::default();
            if FT_Palette_Data_Get(face, &mut pd) == 0 {
                palette_len = pd.num_palette_entries as usize;
            }
            FT_Palette_Select(face, 0, &mut palette);
            Some(Self {
                data,
                library,
                face,
                units_per_em,
                palette,
                palette_len,
            })
        }
    }

    pub fn from_path(path: &Path, expected_sha256: &str) -> Option<Self> {
        let data = std::fs::read(path).ok()?;
        if !expected_sha256.is_empty() {
            let digest = crate::sha256::hex(&crate::sha256::sha256(&data));
            if digest != expected_sha256 {
                return None;
            }
        }
        Self::open(data)
    }
}

impl Drop for EmojiFont {
    fn drop(&mut self) {
        unsafe {
            if !self.face.is_null() {
                FT_Done_Face(self.face);
            }
            if !self.library.is_null() {
                FT_Done_FreeType(self.library);
            }
        }
    }
}

impl EmojiFont {
    /// 诊断用：字体是否带彩色字形能力（COLR/CBDT/sbix）。
    pub fn has_color(&self) -> bool {
        unsafe { (*self.face).face_flags & FT_FACE_FLAG_COLOR != 0 }
    }

    pub fn byte_len(&self) -> usize {
        self.data.len()
    }

    /// 单字符光栅（兼容旧调用方：委托给整段整形光栅）。
    pub fn rasterize(&self, ch: char, pixels_per_em: u16) -> Option<RgbaBitmap> {
        let mut s = String::new();
        s.push(ch);
        self.rasterize_cluster(&s, pixels_per_em)
    }

    /// emoji run（含 ZWJ 序列）整形 + FreeType 光栅，输出合成为一张 RGBA。
    pub fn rasterize_cluster(&self, text: &str, pixels_per_em: u16) -> Option<RgbaBitmap> {
        if text.is_empty() || pixels_per_em == 0 {
            return None;
        }
        let face = rustybuzz::Face::from_slice(&self.data, 0)?;
        let mut buffer = rustybuzz::UnicodeBuffer::new();
        buffer.push_str(text);
        let glyphs = rustybuzz::shape(&face, &[], buffer);
        let infos = glyphs.glyph_infos();
        let positions = glyphs.glyph_positions();
        if infos.is_empty() {
            return None;
        }

        let upem = self.units_per_em.max(1) as f32;
        let scale = pixels_per_em as f32 / upem;
        unsafe {
            if FT_Set_Pixel_Sizes(self.face, 0, pixels_per_em as FT_UInt) != 0 {
                return None;
            }
            let colr_ctx = colr::ColrCtx {
                face: self.face,
                palette: self.palette,
                palette_len: self.palette_len,
            };
            let mut placed: Vec<(i32, i32, RgbaBitmap)> = Vec::new();
            let mut pen_x: f32 = 0.0;
            let baseline_y = pixels_per_em as f32;
            for (info, pos) in infos.iter().zip(positions.iter()) {
                let gid = info.glyph_id as FT_UInt;
                if let Some(img) = colr::render_color_glyph(&colr_ctx, gid, pixels_per_em) {
                    if img.width == 0 || img.height == 0 {
                        pen_x += pos.x_advance as f32 * scale;
                        continue;
                    }
                    let x = pen_x + pos.x_offset as f32 * scale + img.offset_x as f32;
                    let y = baseline_y + pos.y_offset as f32 * scale
                        - img.offset_y as f32
                        - img.height as f32;
                    placed.push((
                        x.round() as i32,
                        y.round() as i32,
                        img.to_bitmap(),
                    ));
                    pen_x += pos.x_advance as f32 * scale;
                    continue;
                }
                if FT_Load_Glyph(self.face, gid, FT_LOAD_COLOR | FT_LOAD_RENDER) != 0 {
                    pen_x += pos.x_advance as f32 * scale;
                    continue;
                }
                let slot = (*self.face).glyph;
                if slot.is_null() {
                    pen_x += pos.x_advance as f32 * scale;
                    continue;
                }
                let bitmap = &(*slot).bitmap;
                let w = bitmap.width as usize;
                let h = bitmap.rows as usize;
                if w == 0 || h == 0 || bitmap.buffer.is_null() {
                    pen_x += pos.x_advance as f32 * scale;
                    continue;
                }
                let pixels = match bitmap.pixel_mode {
                    FT_PIXEL_MODE_BGRA => match decode_bgra(bitmap, w, h) {
                        Some(p) => p,
                        None => {
                            pen_x += pos.x_advance as f32 * scale;
                            continue;
                        }
                    },
                    FT_PIXEL_MODE_GRAY => decode_gray(bitmap, w, h),
                    _ => {
                        pen_x += pos.x_advance as f32 * scale;
                        continue;
                    }
                };
                let x = pen_x + pos.x_offset as f32 * scale + (*slot).bitmap_left as f32;
                let y = baseline_y + pos.y_offset as f32 * scale - (*slot).bitmap_top as f32;
                placed.push((
                    x.round() as i32,
                    y.round() as i32,
                    RgbaBitmap {
                        width: w as u32,
                        height: h as u32,
                        pixels,
                        bbox: None,
                    },
                ));
                pen_x += pos.x_advance as f32 * scale;
            }
            if placed.is_empty() {
                return None;
            }
            composite(&placed)
        }
    }
}

/// straight-alpha src-over 单像素 blit（工单 13 真机修复后的正确公式；
/// 软/GPU/Apple 通路共用，避免重复实现）。
fn blit_src_over(
    canvas: &mut [u8],
    cw: usize,
    chh: usize,
    src: &[u8],
    sw: usize,
    sh: usize,
    ox: usize,
    oy: usize,
) {
    for gy in 0..sh {
        for gx in 0..sw {
            let si = (gy * sw + gx) * 4;
            let a = src[si + 3];
            if a == 0 {
                continue;
            }
            let dx = ox + gx;
            let dy = oy + gy;
            if dx >= cw || dy >= chh {
                continue;
            }
            let di = (dy * cw + dx) * 4;
            let sa = a as f32 / 255.0;
            let da = canvas[di + 3] as f32 / 255.0;
            let oa = sa + da * (1.0 - sa);
            if oa <= 0.0 {
                continue;
            }
            for k in 0..3 {
                canvas[di + k] = ((src[si + k] as f32 * sa
                    + canvas[di + k] as f32 * da * (1.0 - sa))
                    / oa)
                    as u8;
            }
            canvas[di + 3] = (oa * 255.0) as u8;
        }
    }
}

/// 位图并集合成（straight alpha src-over），返回裁剪后的 RGBA + 非透明包围盒。
fn composite(placed: &[(i32, i32, RgbaBitmap)]) -> Option<RgbaBitmap> {
    let mut min_x = i32::MAX;
    let mut min_y = i32::MAX;
    let mut max_x = i32::MIN;
    let mut max_y = i32::MIN;
    for (x, y, bmp) in placed {
        min_x = min_x.min(*x);
        min_y = min_y.min(*y);
        max_x = max_x.max(x + bmp.width as i32 - 1);
        max_y = max_y.max(y + bmp.height as i32 - 1);
    }
    let cw = (max_x - min_x + 1) as usize;
    let chh = (max_y - min_y + 1) as usize;
    let mut canvas = vec![0u8; cw * chh * 4];
    for (x, y, bmp) in placed {
        let ox = (x - min_x) as usize;
        let oy = (y - min_y) as usize;
        blit_src_over(
            &mut canvas,
            cw,
            chh,
            &bmp.pixels,
            bmp.width as usize,
            bmp.height as usize,
            ox,
            oy,
        );
    }
    let bbox = sbix::opaque_bbox(cw as u32, chh as u32, &canvas);
    Some(RgbaBitmap {
        width: cw as u32,
        height: chh as u32,
        pixels: canvas,
        bbox: Some(bbox),
    })
}

/// FreeType BGRA（预乘 alpha）-> 直通 RGBA（un-premultiply，与旧位图通路一致）。
fn decode_bgra(bitmap: &FT_Bitmap, w: usize, h: usize) -> Option<Vec<u8>> {
    if bitmap.pitch < 0 {
        return None;
    }
    let pitch = bitmap.pitch as usize;
    let src = unsafe { std::slice::from_raw_parts(bitmap.buffer, pitch * h) };
    let mut rgba = Vec::with_capacity(w * h * 4);
    for row in 0..h {
        let base = row * pitch;
        for x in 0..w {
            let i = base + x * 4;
            let b = src[i];
            let g = src[i + 1];
            let r = src[i + 2];
            let a = src[i + 3];
            if a == 0 {
                rgba.extend_from_slice(&[0, 0, 0, 0]);
            } else {
                let un = |v: u16| (v * 255 / a as u16).min(255) as u8;
                rgba.extend_from_slice(&[un(r as u16), un(g as u16), un(b as u16), a]);
            }
        }
    }
    Some(rgba)
}

/// FreeType GRAY（8bit 覆盖率）-> RGBA 白字（彩色字体里纯灰度字形极少见）。
fn decode_gray(bitmap: &FT_Bitmap, w: usize, h: usize) -> Vec<u8> {
    let pitch = if bitmap.pitch < 0 {
        bitmap.pitch.unsigned_abs() as usize
    } else {
        bitmap.pitch as usize
    };
    let src = unsafe { std::slice::from_raw_parts(bitmap.buffer, pitch * h) };
    let mut rgba = Vec::with_capacity(w * h * 4);
    for row in 0..h {
        let base = row * pitch;
        for x in 0..w {
            let a = src[base + x];
            rgba.extend_from_slice(&[255, 255, 255, a]);
        }
    }
    rgba
}

// ================================================================ 双字体容器

/// Apple 主 + Noto 兜底（决策 4/5）。
pub struct EmojiFonts {
    pub apple: Option<sbix::AppleSbixFont>,
    pub noto: Option<EmojiFont>,
    pub status: FontStatus,
    pub apple_path: Option<String>,
    pub noto_path: Option<String>,
    pub load_ms: f32,
}

impl EmojiFonts {
    pub fn load(apple_path: Option<&Path>, noto_path: Option<&Path>) -> Self {
        let t0 = std::time::Instant::now();
        let apple = apple_path.and_then(|p| sbix::AppleSbixFont::open(p, APPLE_EXPECTED_SHA256));
        let noto = noto_path.and_then(|p| EmojiFont::from_path(p, NOTO_EXPECTED_SHA256));
        let status = match (&apple, &noto) {
            (Some(_), Some(_)) => FontStatus::Normal,
            (None, Some(_)) => FontStatus::Degraded,
            _ => FontStatus::Missing,
        };
        Self {
            apple_path: apple_path.map(|p| p.display().to_string()),
            noto_path: noto_path.map(|p| p.display().to_string()),
            apple,
            noto,
            status,
            load_ms: t0.elapsed().as_secs_f32() * 1000.0,
        }
    }

    pub fn is_available(&self) -> bool {
        self.apple.is_some() || self.noto.is_some()
    }

    /// 主通路：Apple sbix；整段任一 glyph 无图 -> Noto COLRv1 兜底。
    pub fn rasterize_cluster(&mut self, text: &str, pixels_per_em: u16) -> Option<RgbaBitmap> {
        if let Some(apple) = self.apple.as_mut() {
            if let Some(bmp) = apple_rasterize_cluster(apple, text, pixels_per_em) {
                return Some(bmp);
            }
        }
        self.noto.as_ref()?.rasterize_cluster(text, pixels_per_em)
    }

    /// 启动预热：后台解码前 N 个热门 emoji（2s 内达标）。
    /// 返回（成功解码数, 参与 glyph 数, 耗时）。
    pub fn prewarm(&mut self, samples: &[&str]) -> (usize, usize, std::time::Duration) {
        let t0 = std::time::Instant::now();
        let mut decoded = 0usize;
        let mut total = 0usize;
        if let Some(apple) = self.apple.as_mut() {
            for s in samples {
                let face = match rustybuzz::Face::from_slice(apple.data(), 0) {
                    Some(f) => f,
                    None => continue,
                };
                let mut buffer = rustybuzz::UnicodeBuffer::new();
                buffer.push_str(s);
                let glyphs = rustybuzz::shape(&face, &[], buffer);
                for info in glyphs.glyph_infos() {
                    let gid = info.glyph_id as u32;
                    if apple.has_glyph(gid) {
                        total += 1;
                        if apple.decode(gid).is_some() {
                            decoded += 1;
                        }
                    }
                }
            }
        }
        (decoded, total, t0.elapsed())
    }

    pub fn diagnostics(&self) -> String {
        let mut parts = Vec::new();
        parts.push(format!("emoji_status={}", self.status.label()));
        parts.push(format!("apple={}", self.apple_path.as_deref().unwrap_or("none")));
        parts.push(format!("noto={}", self.noto_path.as_deref().unwrap_or("none")));
        if let Some(apple) = &self.apple {
            parts.push(format!(
                "apple_version={} sha256={:.12} strike={} pngs={} cache={} upem={}",
                apple.version,
                apple.sha256_hex,
                apple.strike_ppem,
                apple.png_count(),
                apple.cache_len(),
                apple.units_per_em
            ));
        }
        if let Some(noto) = &self.noto {
            parts.push(format!(
                "noto_bytes={} color={}",
                noto.byte_len(),
                noto.has_color()
            ));
        }
        parts.push(format!("load_ms={:.1}", self.load_ms));
        parts.join(" ")
    }

    /// Apple vs Noto 覆盖差异：Apple 无图但 Noto 有映射的 emoji 码位。
    pub fn coverage_diff(&self) -> (usize, Vec<u32>) {
        let (Some(apple), Some(noto)) = (&self.apple, &self.noto) else {
            return (0, Vec::new());
        };
        let apple_cmap = sbix::cmap_codepoints(apple.data());
        let noto_cmap = sbix::cmap_codepoints(&noto.data);
        let mut noto_set = std::collections::HashSet::new();
        for (cp, gid) in noto_cmap {
            if gid != 0 {
                noto_set.insert(cp);
            }
        }
        let mut apple_map = std::collections::HashMap::new();
        for (cp, gid) in apple_cmap {
            apple_map.insert(cp, gid);
        }
        let mut missing = Vec::new();
        for cp in noto_set {
            if !sbix::is_emoji_codepoint(cp) {
                continue;
            }
            let apple_has = match apple_map.get(&cp) {
                Some(&gid) => apple.has_glyph(gid),
                None => false,
            };
            if !apple_has {
                missing.push(cp);
            }
        }
        missing.sort_unstable();
        (missing.len(), missing)
    }

    /// 覆盖率：Apple/Noto 各自 emoji 码位映射数（含组合字形在内单码位覆盖）。
    pub fn coverage_counts(&self) -> (usize, usize) {
        let apple = self
            .apple
            .as_ref()
            .map(|a| {
                sbix::cmap_codepoints(a.data())
                    .into_iter()
                    .filter(|(cp, gid)| sbix::is_emoji_codepoint(*cp) && a.has_glyph(*gid))
                    .count()
            })
            .unwrap_or(0);
        let noto = self
            .noto
            .as_ref()
            .map(|n| {
                sbix::cmap_codepoints(&n.data)
                    .into_iter()
                    .filter(|(cp, _)| sbix::is_emoji_codepoint(*cp))
                    .count()
            })
            .unwrap_or(0);
        (apple, noto)
    }

    /// Apple 有图但 Noto 无映射的 emoji 码位（多为 Emoji 17 新码位；
    /// 供离屏"新码位"类别断言与覆盖报告）。
    pub fn apple_only_codepoints(&self) -> Vec<u32> {
        let (Some(apple), Some(noto)) = (&self.apple, &self.noto) else {
            return Vec::new();
        };
        let apple_cmap = sbix::cmap_codepoints(apple.data());
        let noto_cmap = sbix::cmap_codepoints(&noto.data);
        let noto_set: std::collections::HashSet<u32> =
            noto_cmap.iter().map(|(cp, _)| *cp).collect();
        let mut only = Vec::new();
        for (cp, gid) in apple_cmap {
            if sbix::is_emoji_codepoint(cp)
                && apple.has_glyph(gid)
                && !noto_set.contains(&cp)
            {
                only.push(cp);
            }
        }
        only.sort_unstable();
        only
    }
}

/// Apple 通路：160 档合成 -> 目标字号缩放。
fn apple_rasterize_cluster(
    apple: &mut sbix::AppleSbixFont,
    text: &str,
    pixels_per_em: u16,
) -> Option<RgbaBitmap> {
    if text.is_empty() || pixels_per_em == 0 {
        return None;
    }
    let face = rustybuzz::Face::from_slice(apple.data(), 0)?;
    let mut buffer = rustybuzz::UnicodeBuffer::new();
    buffer.push_str(text);
    let glyphs = rustybuzz::shape(&face, &[], buffer);
    let infos = glyphs.glyph_infos();
    let positions = glyphs.glyph_positions();
    if infos.is_empty() {
        return None;
    }
    // 决策 5：整段任一 glyph 无图 -> 整段走 Noto。
    for info in infos.iter() {
        if !apple.has_glyph(info.glyph_id as u32) {
            return None;
        }
    }
    let upem = apple.units_per_em.max(1) as f32;
    let scale = 160.0 / upem; // 先按 160 档合成，最后统一缩到目标字号
    let baseline_y = 160.0f32;
    // 第一遍：只取每 glyph 尺寸算放置矩形（避免长借 cache）。
    struct Place {
        gid: u32,
        x: i32,
        y: i32,
        w: u32,
        h: u32,
    }
    let mut places = Vec::with_capacity(infos.len());
    let mut pen_x = 0.0f32;
    for (info, pos) in infos.iter().zip(positions.iter()) {
        let gid = info.glyph_id as u32;
        let d = apple.decode(gid)?;
        let x = pen_x + pos.x_offset as f32 * scale + d.origin_x as f32;
        let y = baseline_y + pos.y_offset as f32 * scale - d.origin_y as f32 - d.height as f32;
        places.push(Place {
            gid,
            x: x.round() as i32,
            y: y.round() as i32,
            w: d.width,
            h: d.height,
        });
        pen_x += pos.x_advance as f32 * scale;
    }
    if places.is_empty() {
        return None;
    }
    let mut min_x = i32::MAX;
    let mut min_y = i32::MAX;
    let mut max_x = i32::MIN;
    let mut max_y = i32::MIN;
    for p in &places {
        min_x = min_x.min(p.x);
        min_y = min_y.min(p.y);
        max_x = max_x.max(p.x + p.w as i32 - 1);
        max_y = max_y.max(p.y + p.h as i32 - 1);
    }
    let cw = (max_x - min_x + 1) as usize;
    let chh = (max_y - min_y + 1) as usize;
    if cw == 0 || chh == 0 {
        return None;
    }
    let mut canvas = vec![0u8; cw * chh * 4];
    // 第二遍：直接按放置矩形 blit（LRU 命中，无克隆）。
    for p in &places {
        let d = apple.decode(p.gid)?;
        let ox = (p.x - min_x) as usize;
        let oy = (p.y - min_y) as usize;
        blit_src_over(
            &mut canvas,
            cw,
            chh,
            &d.pixels,
            d.width as usize,
            d.height as usize,
            ox,
            oy,
        );
    }
    let bbox160 = sbix::opaque_bbox(cw as u32, chh as u32, &canvas);
    if bbox160.2 == 0 || bbox160.3 == 0 {
        return None;
    }
    // 恒 160 超采样：缩到目标字号（>160px 用 160 原图放大）。
    let factor = pixels_per_em as f32 / 160.0;
    let tw = ((cw as f32 * factor).round()).max(1.0) as u32;
    let th = ((chh as f32 * factor).round()).max(1.0) as u32;
    let pixels = sbix::scale_rgba(&canvas, cw as u32, chh as u32, tw, th)?;
    let bbox = sbix::opaque_bbox(tw, th, &pixels);
    Some(RgbaBitmap {
        width: tw,
        height: th,
        pixels,
        bbox: Some(bbox),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emoji_ranges_cover_acceptance_chars() {
        assert!(is_emoji('🚀'));
        assert!(is_emoji('✅'));
        assert!(is_emoji_run("👨‍👩‍👧‍👦"));
        assert!(is_emoji_run("👍🏻"));
        assert!(is_emoji_run("🇨🇳"));
        assert!(is_emoji_run("⌨️"));
        assert!(!is_emoji_run("A"));
        assert!(!is_emoji_run(""));
    }

    #[test]
    fn default_fonts_load_and_decode() {
        let (apple, noto) = default_font_paths();
        let fonts = EmojiFonts::load(
            Some(std::path::Path::new(&apple)),
            Some(std::path::Path::new(&noto)),
        );
        assert_eq!(fonts.status, FontStatus::Normal, "默认字体应可加载");
        if let Some(a) = &fonts.apple {
            assert!(a.version.contains("21.4d3e1"));
            assert_eq!(a.png_count(), 3761);
        }
    }
}
