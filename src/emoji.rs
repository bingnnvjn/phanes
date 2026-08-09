//! 彩色 emoji 字形（工单 13：FreeType 光栅 COLRv1 + rustybuzz ZWJ 整形）。
//!
//! 灰度正文仍走 fontdue（不动）；彩色字形统一走 FreeType：
//! emoji run（含 ZWJ 序列）先经 rustybuzz 整形，取 glyph id 后走
//! COLRv1 paint graph 合成（`colr.rs`，FreeType 解析+光栅）；无 paint 的
//! 字形回退 `FT_LOAD_COLOR | FT_LOAD_RENDER`（CBDT 内嵌 PNG 需 libpng +
//! `FT_CONFIG_OPTION_USE_PNG`，本构建关闭，见工单 Comments 回退说明）。
//! 输出直通 RGBA 进双图集彩色层。字体 = 内嵌 `NotoColorEmoji.ttf`
//! （COLRv1 完整版 `Noto-COLRv1.ttf`，含旗帜；旧 CBDT 回退见工单 Comments）。

use crate::freetype_ffi::*;
use crate::colr;

pub struct RgbaBitmap {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

/// 是否为彩色 emoji 字符（宽 2 格）。
pub fn is_emoji(ch: char) -> bool {
    let c = ch as u32;
    matches!(
        c,
        0x1F000..=0x1FAFF // 补充符号与 pictograph（🚀 等，含区域指示符/肤色修饰）
        | 0x2600..=0x27BF // 杂项符号与 dingbats（✅ 等）
        | 0x2300..=0x23FF // 杂项技术符号（⌨️ 等）
        | 0x2B00..=0x2BFF // 杂项符号和箭头
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

pub struct EmojiFont {
    data: Vec<u8>,
    library: FT_Library,
    face: FT_Face,
    units_per_em: u16,
    palette: *const FT_Color,
    palette_len: usize,
}

const EMBEDDED_COLOR_EMOJI: &[u8] = include_bytes!("../assets/NotoColorEmoji.ttf");

/// FT_FACE_FLAG_COLOR（字体是否带彩色字形表）。
const FT_FACE_FLAG_COLOR: FT_Long = 1 << 14;

impl EmojiFont {
    /// 诊断/测试用：从任意字体字节构造（失败返回 None，不再是 infallible）。
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

pub fn load_emoji_font() -> Option<EmojiFont> {
    // 内嵌 COLRv1 完整版 NotoColorEmoji（googlefonts/noto-emoji main
    // `fonts/Noto-COLRv1.ttf`，OFL-1.1）。Android 15 已移除系统位图 emoji
    // 字体，系统字体只剩 COLRv1 矢量版，以内嵌为权威来源。
    if let Some(font) = EmojiFont::open(EMBEDDED_COLOR_EMOJI.to_vec()) {
        return Some(font);
    }
    const CANDIDATES: &[&str] = &[
        "/system/fonts/NotoColorEmoji.ttf",
        "/system/fonts/NotoColorEmoji-Regular.ttf",
        "/system/fonts/NotoColorEmojiFlags.ttf",
        "/system/fonts/NotoColorEmojiStatic.ttf",
        "/system/fonts/AndroidEmoji.ttf",
    ];
    for path in CANDIDATES {
        if let Ok(data) = std::fs::read(path) {
            if let Some(font) = EmojiFont::open(data) {
                return Some(font);
            }
        }
    }
    None
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
    ///
    /// 流程：rustybuzz 用字体 GSUB 整形整段文本（家庭/肤色/旗帜等序列会
    /// ligature 成单 glyph），逐个 glyph id 以 `FT_LOAD_COLOR | FT_LOAD_RENDER`
    /// 光栅（BGRA 预乘 → 直通 RGBA；纯灰度字形转白字），按位点合成。
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
            // em 盒基线：字形 bitmap_top 以基线为原点，先占满一 em 高。
            let baseline_y = pixels_per_em as f32;
            for (info, pos) in infos.iter().zip(positions.iter()) {
                let gid = info.glyph_id as FT_UInt;
                // 工单 13：COLRv1 paint graph 合成优先（🚀/✅/ZWJ 等）。
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
                // 简单路径兜底：CBDT/sbix/普通灰度字形。
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
                    },
                ));
                pen_x += pos.x_advance as f32 * scale;
            }
            if placed.is_empty() {
                return None;
            }

            // 画布取所有位图并集，按位点合成（straight alpha src-over）。
            let mut min_x = i32::MAX;
            let mut min_y = i32::MAX;
            let mut max_x = i32::MIN;
            let mut max_y = i32::MIN;
            for (x, y, bmp) in &placed {
                min_x = min_x.min(*x);
                min_y = min_y.min(*y);
                max_x = max_x.max(x + bmp.width as i32 - 1);
                max_y = max_y.max(y + bmp.height as i32 - 1);
            }
            let cw = (max_x - min_x + 1) as usize;
            let chh = (max_y - min_y + 1) as usize;
            let mut canvas = vec![0u8; cw * chh * 4];
            for (x, y, bmp) in &placed {
                let ox = (x - min_x) as usize;
                let oy = (y - min_y) as usize;
                for gy in 0..bmp.height as usize {
                    for gx in 0..bmp.width as usize {
                        let si = (gy * bmp.width as usize + gx) * 4;
                        let a = bmp.pixels[si + 3];
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
                        for k in 0..3 {
                            canvas[di + k] = (bmp.pixels[si + k] as f32 * sa
                                + canvas[di + k] as f32 * (1.0 - sa))
                                as u8;
                        }
                        canvas[di + 3] = 255;
                    }
                }
            }
            Some(RgbaBitmap {
                width: cw as u32,
                height: chh as u32,
                pixels: canvas,
            })
        }
    }
}

/// FreeType BGRA（预乘 alpha）→ 直通 RGBA（un-premultiply，与旧位图通路一致）。
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

/// FreeType GRAY（8bit 覆盖率）→ RGBA 白字（彩色字体里纯灰度字形极少见）。
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
        assert!(!is_emoji_run("A"));
    }

    #[test]
    fn embedded_font_is_color_capable() {
        let font = load_emoji_font().expect("内嵌彩色字体可打开");
        assert!(font.has_color(), "字体应带彩色字形能力（COLR/CBDT）");
        assert_eq!(font.byte_len(), EMBEDDED_COLOR_EMOJI.len());
    }
}
