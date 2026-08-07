//! 彩色 emoji 字形（工单 12 ②：灰度/彩色双图集）。
//!
//! fontdue 只做灰度光栅；彩色字形从系统彩色字体（NotoColorEmoji，
//! CBDT/CBLC 内嵌 PNG）经 ttf-parser 提取 + png 解码为 RGBA，
//! 写进独立彩色图集，shader 按 mode 分层采样。

use std::io::Cursor;

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
        0x1F000..=0x1FAFF // 补充符号与 pictograph（🚀 等）
        | 0x2600..=0x27BF // 杂项符号与 dingbats（✅ 等）
        | 0x2300..=0x23FF // 杂项技术符号（⏰ 等）
        | 0x2B00..=0x2BFF // 杂项符号和箭头
    )
}

pub struct EmojiFont {
    data: Vec<u8>,
}

const EMBEDDED_COLOR_EMOJI: &[u8] = include_bytes!("../assets/NotoColorEmoji.ttf");

impl EmojiFont {
    /// 诊断/测试用：从任意字体字节构造。
    pub fn from_bytes(data: Vec<u8>) -> Self {
        Self { data }
    }
}

pub fn load_emoji_font() -> Option<EmojiFont> {
    // 内嵌 CBDT/CBLC 位图版 NotoColorEmoji（googlefonts/noto-emoji
    // v2017-03-10-color-emoji-binary，OFL-1.1）。系统 NotoColorEmoji 是
    // COLRv1 矢量，ttf-parser 无法直接取位图，故以内嵌为权威来源。
    if ttf_parser::Face::parse(EMBEDDED_COLOR_EMOJI, 0).is_ok() {
        return Some(EmojiFont {
            data: EMBEDDED_COLOR_EMOJI.to_vec(),
        });
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
            if ttf_parser::Face::parse(&data, 0).is_ok() {
                return Some(EmojiFont { data });
            }
        }
    }
    None
}

impl EmojiFont {
    /// 诊断用：数据是否为 CBDT/CBLC 位图字体。
    pub fn is_bitmap(&self) -> bool {
        ttf_parser::Face::parse(&self.data, 0)
            .map(|face| face.tables().cbdt.is_some() || face.tables().sbix.is_some())
            .unwrap_or(false)
    }

    pub fn byte_len(&self) -> usize {
        self.data.len()
    }

    /// 提取字形位图（优先 PNG 位图；兼容预乘 BGRA32 位图）。
    pub fn rasterize(&self, ch: char, pixels_per_em: u16) -> Option<RgbaBitmap> {
        let face = ttf_parser::Face::parse(&self.data, 0).ok()?;
        let glyph_id = face.glyph_index(ch)?;
        let image = face.glyph_raster_image(glyph_id, pixels_per_em)?;
        let width = image.width as usize;
        let height = image.height as usize;
        if width == 0 || height == 0 {
            return None;
        }
        match image.format {
            ttf_parser::RasterImageFormat::PNG => decode_png(image.data).map(|(w, h, pixels)| {
                RgbaBitmap {
                    width: w,
                    height: h,
                    pixels,
                }
            }),
            ttf_parser::RasterImageFormat::BitmapPremulBgra32 => {
                if image.data.len() < width * height * 4 {
                    return None;
                }
                let mut pixels = Vec::with_capacity(width * height * 4);
                for chunk in image.data.chunks_exact(4) {
                    let b = chunk[0] as u16;
                    let g = chunk[1] as u16;
                    let r = chunk[2] as u16;
                    let a = chunk[3] as u16;
                    let un = |v: u16| {
                        if a == 0 {
                            0
                        } else {
                            (v * 255 / a).min(255) as u8
                        }
                    };
                    pixels.extend_from_slice(&[un(r), un(g), un(b), a as u8]);
                }
                Some(RgbaBitmap {
                    width: width as u32,
                    height: height as u32,
                    pixels,
                })
            }
            _ => None,
        }
    }
}

fn decode_png(data: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
    let mut decoder = png::Decoder::new(Cursor::new(data));
    // CBDT 内嵌 PNG 常见 Indexed + tRNS：强制展开为 8bit RGBA。
    decoder.set_transformations(
        png::Transformations::normalize_to_color8() | png::Transformations::ALPHA,
    );
    let mut reader = decoder.read_info().ok()?;
    let buffer_size = reader.output_buffer_size()?;
    let mut buffer = vec![0u8; buffer_size];
    let info = reader.next_frame(&mut buffer).ok()?;
    let width = info.width;
    let height = info.height;
    let (color, bits) = reader.output_color_type();
    let bytes_per_sample = match bits {
        png::BitDepth::Sixteen => 2usize,
        _ => 1usize,
    };
    let channels = match color {
        png::ColorType::Rgba => 4usize,
        png::ColorType::Rgb => 3usize,
        png::ColorType::GrayscaleAlpha => 2usize,
        png::ColorType::Grayscale => 1usize,
        png::ColorType::Indexed => return None,
    };
    let row_bytes = width as usize * channels * bytes_per_sample;
    let expected = row_bytes * height as usize;
    if buffer.len() < expected {
        return None;
    }
    buffer.truncate(expected);

    let mut rgba = Vec::with_capacity(width as usize * height as usize * 4);
    for row in buffer.chunks_exact(row_bytes) {
        let mut i = 0;
        for _ in 0..width as usize {
            match channels {
                4 => {
                    rgba.push(row[i]);
                    rgba.push(row[i + 1]);
                    rgba.push(row[i + 2]);
                    rgba.push(row[i + 3]);
                    i += 4;
                }
                3 => {
                    rgba.extend_from_slice(&row[i..i + 3]);
                    rgba.push(255);
                    i += 3;
                }
                2 => {
                    let gray = row[i];
                    rgba.extend_from_slice(&[gray, gray, gray, row[i + 1]]);
                    i += 2;
                }
                1 => {
                    let gray = row[i];
                    rgba.extend_from_slice(&[gray, gray, gray, 255]);
                    i += 1;
                }
                _ => unreachable!(),
            }
        }
    }
    Some((width, height, rgba))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emoji_ranges_cover_acceptance_chars() {
        assert!(is_emoji('🚀'));
        assert!(is_emoji('✅'));
        assert!(!is_emoji('A'));
    }
}
