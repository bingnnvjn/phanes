//! 工单 10：安卓 GPU 渲染器（快照 → 顶点 → wgpu 单 pipeline 一次 draw call）。
//!
//! 借 Shellow（Apache-2.0）的 GPU 整段思路：ANativeWindow + wgpu Vulkan、
//! 单 shader（solid/glyph 双 mode）、字形图集、内容签名触发渲染。
//! 本切片不实现脏行增量顶点 / 彩色 emoji 图集 / sprite face（ADR-0004 缺口后置）。

use crate::ffi::*;
use std::collections::HashMap;
use std::collections::hash_map::DefaultHasher;
use std::ffi::{CString, c_char, c_void};
use std::hash::{Hash, Hasher};
use std::ptr::NonNull;
use std::sync::Mutex;
use std::sync::Once;

static LOG_BUFFER: Mutex<Vec<String>> = Mutex::new(Vec::new());
static LOGGER_SET: Once = Once::new();

struct CaptureLogger;

impl log::Log for CaptureLogger {
    fn enabled(&self, _metadata: &log::Metadata) -> bool {
        true
    }

    fn log(&self, record: &log::Record) {
        let line = format!("[{}] {}", record.level(), record.args());
        if let Ok(mut buffer) = LOG_BUFFER.lock() {
            buffer.push(line);
            if buffer.len() > 200 {
                buffer.remove(0);
            }
        }
    }

    fn flush(&self) {}
}

static CAPTURE_LOGGER: CaptureLogger = CaptureLogger;

fn init_wgpu_logger() {
    LOGGER_SET.call_once(|| {
        let _ = log::set_logger(&CAPTURE_LOGGER);
        log::set_max_level(log::LevelFilter::Debug);
    });
}

fn recent_wgpu_log() -> String {
    if let Ok(buffer) = LOG_BUFFER.lock() {
        buffer
            .iter()
            .rev()
            .take(5)
            .cloned()
            .collect::<Vec<_>>()
            .join(" | ")
    } else {
        String::new()
    }
}

pub const LOG_TAG: &str = "FableRender";
const ANDROID_LOG_INFO: i32 = 4;
const ANDROID_LOG_ERROR: i32 = 6;

extern "C" {
    fn fable_android_log(prio: i32, tag: *const c_char, msg: *const c_char) -> i32;
    fn ANativeWindow_acquire(window: *mut c_void) -> i32;
    fn ANativeWindow_release(window: *mut c_void) -> i32;
    fn fable_anw_get_width(window: *mut c_void) -> i32;
    fn fable_anw_get_height(window: *mut c_void) -> i32;
}

pub fn log_info(msg: &str) {
    let tag = CString::new(LOG_TAG).unwrap_or_default();
    let text = CString::new(msg).unwrap_or_default();
    unsafe {
        fable_android_log(ANDROID_LOG_INFO, tag.as_ptr(), text.as_ptr());
    }
}

pub fn log_error(msg: &str) {
    let tag = CString::new(LOG_TAG).unwrap_or_default();
    let text = CString::new(msg).unwrap_or_default();
    unsafe {
        fable_android_log(ANDROID_LOG_ERROR, tag.as_ptr(), text.as_ptr());
    }
}

// ---------- 快照 ----------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Rgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

/// 主终端可 push 的配色板（工单 14）。未 push 时用核心解析配色。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Palette {
    pub fg: Rgb,
    pub bg: Rgb,
    pub selection: Rgb,
    pub cursor: Rgb,
    /// ANSI 16 色覆盖（colors.properties color0-15 / 内置明暗）；indexed 单元格用它。
    pub ansi: [Rgb; 16],
}

pub const DEFAULT_FONT_SIZE_PX: f32 = 24.0;
pub const MIN_FONT_SIZE_PX: f32 = 4.0;
pub const MAX_FONT_SIZE_PX: f32 = 128.0;
const DEFAULT_SELECTION_COLOR: Rgb = Rgb {
    r: 64,
    g: 115,
    b: 242,
};

/// 内置 ANSI 16 色（与 TerminalColorScheme 默认一致；未 push 时用核心配色）。
pub const DEFAULT_ANSI_16: [Rgb; 16] = [
    Rgb { r: 0, g: 0, b: 0 },
    Rgb { r: 0xcd, g: 0, b: 0 },
    Rgb { r: 0, g: 0xcd, b: 0 },
    Rgb { r: 0xcd, g: 0xcd, b: 0 },
    Rgb { r: 0x64, g: 0x95, b: 0xed },
    Rgb { r: 0xcd, g: 0, b: 0xcd },
    Rgb { r: 0, g: 0xcd, b: 0xcd },
    Rgb { r: 0xe5, g: 0xe5, b: 0xe5 },
    Rgb { r: 0x7f, g: 0x7f, b: 0x7f },
    Rgb { r: 0xff, g: 0, b: 0 },
    Rgb { r: 0, g: 0xff, b: 0 },
    Rgb { r: 0xff, g: 0xff, b: 0 },
    Rgb { r: 0x5c, g: 0x5c, b: 0xff },
    Rgb { r: 0xff, g: 0, b: 0xff },
    Rgb { r: 0, g: 0xff, b: 0xff },
    Rgb { r: 0xff, g: 0xff, b: 0xff },
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cell {
    pub text: String,
    pub fg: Option<Rgb>,
    pub bg: Option<Rgb>,
    pub selected: bool,
    pub underline: bool,
    /// SGR 4 下划线颜色（样式显式指定；None = 用前景色，工单 13 更高对比）。
    pub underline_color: Option<Rgb>,
    pub strikethrough: bool,
    pub overline: bool,
    /// 占几列（工单 13 布局：emoji cluster 一律 2 格；核心拆分/1 格宽由
    /// `merge_emoji_runs` 归一）。
    pub col_span: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub cols: u16,
    pub rows: u16,
    pub lines: Vec<Vec<Cell>>,
    pub cursor: Option<(u16, u16)>,
    pub cursor_style: i32,
    pub default_fg: Rgb,
    pub default_bg: Rgb,
    pub cursor_color: Rgb,
    pub dirty: i32,
    pub dirty_rows: Vec<usize>,
    pub selection_color: Rgb,
    pub palette: Option<Palette>,
    /// 已 push 的 ANSI 16 色；collect 时覆盖 core palette[0..16] 解析的单元格色。
    pub ansi_override: Option<[Rgb; 16]>,
}

/// 把 push 的配色板应用到快照（渲染循环与离屏自检共用，避免两处手写漂移）。
pub fn apply_palette(snapshot: &mut Snapshot, palette: Palette) {
    snapshot.default_fg = palette.fg;
    snapshot.default_bg = palette.bg;
    snapshot.cursor_color = palette.cursor;
    snapshot.selection_color = palette.selection;
    snapshot.ansi_override = Some(palette.ansi);
    snapshot.palette = Some(palette);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct OverlayRange {
    pub row: u32,
    pub start_col: u32,
    pub end_col: u32,
}

fn check(result: GhosttyResult, what: &str) -> bool {
    if result != GHOSTTY_SUCCESS {
        log_error(&format!("{what} failed: {result}"));
        false
    } else {
        true
    }
}

unsafe fn get_u16(state: GhosttyRenderState, data: i32) -> u16 {
    let mut v: u16 = 0;
    let _ = ghostty_render_state_get(state, data, &mut v as *mut u16 as *mut c_void);
    v
}

unsafe fn get_bool(state: GhosttyRenderState, data: i32) -> bool {
    let mut v: bool = false;
    let _ = ghostty_render_state_get(state, data, &mut v as *mut bool as *mut c_void);
    v
}

unsafe fn get_i32(state: GhosttyRenderState, data: i32) -> i32 {
    let mut v: i32 = 0;
    let _ = ghostty_render_state_get(state, data, &mut v as *mut i32 as *mut c_void);
    v
}

unsafe fn cell_text(cells: GhosttyRenderStateRowCells) -> String {
    let mut buf = GhosttyBuffer {
        ptr: std::ptr::null_mut(),
        cap: 0,
        len: 0,
    };
    let r = ghostty_render_state_row_cells_get(
        cells,
        CELL_DATA_GRAPHEMES_UTF8,
        &mut buf as *mut GhosttyBuffer as *mut c_void,
    );
    if r == GHOSTTY_OUT_OF_SPACE && buf.len > 0 {
        let mut bytes = vec![0u8; buf.len];
        let mut buf2 = GhosttyBuffer {
            ptr: bytes.as_mut_ptr(),
            cap: bytes.len(),
            len: 0,
        };
        let _ = ghostty_render_state_row_cells_get(
            cells,
            CELL_DATA_GRAPHEMES_UTF8,
            &mut buf2 as *mut GhosttyBuffer as *mut c_void,
        );
        bytes.truncate(buf2.len);
        String::from_utf8_lossy(&bytes).into_owned()
    } else {
        String::new()
    }
}

unsafe fn cell_color(cells: GhosttyRenderStateRowCells, data: i32) -> Option<Rgb> {
    let mut c = GhosttyColorRgb { r: 0, g: 0, b: 0 };
    let r = ghostty_render_state_row_cells_get(
        cells,
        data,
        &mut c as *mut GhosttyColorRgb as *mut c_void,
    );
    if r == GHOSTTY_SUCCESS {
        Some(Rgb {
            r: c.r,
            g: c.g,
            b: c.b,
        })
    } else {
        None
    }
}

pub unsafe fn collect_snapshot(
    state: GhosttyRenderState,
    colors: &GhosttyRenderStateColors,
    ansi_override: Option<[Rgb; 16]>,
) -> Snapshot {
    let cols = get_u16(state, DATA_COLS);
    let rows = get_u16(state, DATA_ROWS);
    let dirty = get_i32(state, DATA_DIRTY);
    let cursor_style = get_i32(state, DATA_CURSOR_VISUAL_STYLE);
    let cursor_visible = get_bool(state, DATA_CURSOR_VISIBLE);
    let cursor = if cursor_visible {
        Some((
            get_u16(state, DATA_CURSOR_VIEWPORT_X),
            get_u16(state, DATA_CURSOR_VIEWPORT_Y),
        ))
    } else {
        None
    };

    let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
    if !check(
        ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it),
        "row_iterator_new",
    ) {
        return Snapshot {
            cols,
            rows,
            lines: Vec::new(),
            cursor,
            cursor_style,
            default_fg: Rgb {
                r: colors.foreground.r,
                g: colors.foreground.g,
                b: colors.foreground.b,
            },
            default_bg: Rgb {
                r: colors.background.r,
                g: colors.background.g,
                b: colors.background.b,
            },
            cursor_color: Rgb {
                r: colors.cursor.r,
                g: colors.cursor.g,
                b: colors.cursor.b,
            },
            dirty,
            dirty_rows: Vec::new(),
            selection_color: DEFAULT_SELECTION_COLOR,
            palette: None,
            ansi_override,
        };
    }
    check(
        ghostty_render_state_get(state, DATA_ROW_ITERATOR, &mut row_it as *mut _ as *mut c_void),
        "get row_iterator",
    );

    let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
    if !check(
        ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells),
        "row_cells_new",
    ) {
        ghostty_render_state_row_iterator_free(row_it);
        return Snapshot {
            cols,
            rows,
            lines: Vec::new(),
            cursor,
            cursor_style,
            default_fg: Rgb {
                r: colors.foreground.r,
                g: colors.foreground.g,
                b: colors.foreground.b,
            },
            default_bg: Rgb {
                r: colors.background.r,
                g: colors.background.g,
                b: colors.background.b,
            },
            cursor_color: Rgb {
                r: colors.cursor.r,
                g: colors.cursor.g,
                b: colors.cursor.b,
            },
            dirty,
            dirty_rows: Vec::new(),
            selection_color: DEFAULT_SELECTION_COLOR,
            palette: None,
            ansi_override,
        };
    }

    let mut lines = Vec::new();
    let mut dirty_rows = Vec::new();
    while ghostty_render_state_row_iterator_next(row_it) {
        let mut row_dirty: bool = false;
        let _ = ghostty_render_state_row_get(
            row_it,
            ROW_DATA_DIRTY,
            &mut row_dirty as *mut bool as *mut c_void,
        );
        if row_dirty {
            dirty_rows.push(lines.len());
        }
        let _ = ghostty_render_state_row_get(row_it, ROW_DATA_CELLS, &mut cells as *mut _ as *mut c_void);
        let mut row_cells = Vec::new();
        while ghostty_render_state_row_cells_next(cells) {
            let text = cell_text(cells);
            let mut fg = cell_color(cells, CELL_DATA_FG_COLOR);
            let mut bg = cell_color(cells, CELL_DATA_BG_COLOR);
            let mut selected: bool = false;
            let _ = ghostty_render_state_row_cells_get(
                cells,
                CELL_DATA_SELECTED,
                &mut selected as *mut bool as *mut c_void,
            );
            let mut style = GhosttyStyle {
                size: std::mem::size_of::<GhosttyStyle>(),
                fg_color: GhosttyStyleColor {
                    tag: 0,
                    value: GhosttyStyleColorValue { palette: 0 },
                },
                bg_color: GhosttyStyleColor {
                    tag: 0,
                    value: GhosttyStyleColorValue { palette: 0 },
                },
                underline_color: GhosttyStyleColor {
                    tag: 0,
                    value: GhosttyStyleColorValue { palette: 0 },
                },
                bold: false,
                italic: false,
                faint: false,
                blink: false,
                inverse: false,
                invisible: false,
                strikethrough: false,
                overline: false,
                underline: 0,
            };
            let _ = ghostty_render_state_row_cells_get(
                cells,
                CELL_DATA_STYLE,
                &mut style as *mut GhosttyStyle as *mut c_void,
            );
            fg = apply_ansi_override(
                style.fg_color.tag,
                style.fg_color.value.palette,
                fg,
                ansi_override,
            );
            bg = apply_ansi_override(
                style.bg_color.tag,
                style.bg_color.value.palette,
                bg,
                ansi_override,
            );
            row_cells.push(Cell {
                text,
                fg,
                bg,
                selected,
                underline: style.underline != SGR_UNDERLINE_NONE,
                underline_color: match style.underline_color.tag {
                    1 => apply_ansi_override(
                        style.underline_color.tag,
                        style.underline_color.value.palette,
                        fg,
                        ansi_override,
                    ),
                    2 => Some(Rgb {
                        r: style.underline_color.value.rgb.r,
                        g: style.underline_color.value.rgb.g,
                        b: style.underline_color.value.rgb.b,
                    }),
                    _ => None,
                },
                strikethrough: style.strikethrough,
                overline: style.overline,
                col_span: 1,
            });
        }
        // 工单 13：核心把 ZWJ/肤色/旗帜拆成片段、⌨️ 等按 1 格宽；
        // 渲染前重组完整 cluster 并归一 emoji 为 2 格（否则相邻 emoji
        // 位图重叠、旗帜被拆成 C N）。
        merge_emoji_runs(&mut row_cells);
        lines.push(row_cells);
    }

    ghostty_render_state_row_cells_free(cells);
    ghostty_render_state_row_iterator_free(row_it);

    Snapshot {
        cols,
        rows,
        lines,
        cursor,
        cursor_style,
        default_fg: Rgb {
            r: colors.foreground.r,
            g: colors.foreground.g,
            b: colors.foreground.b,
        },
        default_bg: Rgb {
            r: colors.background.r,
            g: colors.background.g,
            b: colors.background.b,
        },
        cursor_color: Rgb {
            r: colors.cursor.r,
            g: colors.cursor.g,
            b: colors.cursor.b,
        },
        dirty,
        dirty_rows,
        selection_color: DEFAULT_SELECTION_COLOR,
        palette: None,
        ansi_override,
    }
}

/// 工单 04：push 的 ANSI 16 色覆盖 core palette[0..16] 解析出的单元格色；
/// 直接 RGB（tag=2）与默认色（tag=0）、越界索引、未 push 时原样回退。
fn apply_ansi_override(
    tag: i32,
    index: u8,
    fallback: Option<Rgb>,
    ansi: Option<[Rgb; 16]>,
) -> Option<Rgb> {
    if tag == 1 {
        if let Some(ansi) = ansi {
            let idx = index as usize;
            if idx < 16 {
                return Some(ansi[idx]);
            }
        }
    }
    fallback
}

/// 工单 13：核心（libghostty-vt 预编译 b0947378）把 ZWJ 家庭/肤色/旗帜
/// 拆成多个 cell，且 ⌨️ 等带 VS16 字符按 1 格宽。渲染前把片段重组成完整
/// emoji cluster，并把宽度归一为 2 格（累计列布局由 build_row_vertices 用
/// `col_span` 实现），否则相邻 emoji 位图重叠、旗帜被画成 C N 两个字符。
pub fn merge_emoji_runs(cells: &mut Vec<Cell>) {
    if cells.is_empty() {
        return;
    }
    let mut out: Vec<Cell> = Vec::with_capacity(cells.len());
    let mut i = 0usize;
    while i < cells.len() {
        let mut cell = cells[i].clone();
        if cell.text.is_empty() {
            i += 1;
            continue;
        }
        let mut span = 1u32;
        if crate::emoji::is_emoji_run(&cell.text) {
            span = 2;
        }
        let mut j = i + 1;
        loop {
            // 跳过 emoji 的伴随空格 cell（核心按 2 格拆出的第二格）。
            while j < cells.len() && cells[j].text.is_empty() {
                j += 1;
            }
            if j >= cells.len() {
                break;
            }
            let next = &cells[j].text;
            let next_is_skin = next.chars().any(|c| (0x1F3FB..=0x1F3FF).contains(&(c as u32)));
            let cell_all_ri =
                cell.text.chars().all(|c| (0x1F1E6..=0x1F1FF).contains(&(c as u32)));
            let next_all_ri = next.chars().all(|c| (0x1F1E6..=0x1F1FF).contains(&(c as u32)));
            let need_merge =
                cell.text.ends_with('\u{200d}') || next_is_skin || (cell_all_ri && next_all_ri);
            if !need_merge {
                break;
            }
            cell.text.push_str(next);
            if crate::emoji::is_emoji_run(&cell.text) {
                span = 2;
            }
            j += 1;
        }
        cell.col_span = span;
        out.push(cell);
        i = j;
    }
    *cells = out;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ansi_override_maps_palette_indexed_cells() {
        let mut ansi = DEFAULT_ANSI_16;
        ansi[1] = Rgb { r: 1, g: 2, b: 3 };
        let fallback = Some(Rgb { r: 9, g: 9, b: 9 });

        // palette index 1 → push 的 ANSI[1]。
        assert_eq!(apply_ansi_override(1, 1, fallback, Some(ansi)), Some(Rgb { r: 1, g: 2, b: 3 }));
        // 直接 RGB（tag=2）不动。
        assert_eq!(apply_ansi_override(2, 1, fallback, Some(ansi)), fallback);
        // 默认色（tag=0）不动。
        assert_eq!(apply_ansi_override(0, 1, fallback, Some(ansi)), fallback);
        // 越界索引回退。
        assert_eq!(apply_ansi_override(1, 17, fallback, Some(ansi)), fallback);
        // 未 push 配色板时回退。
        assert_eq!(apply_ansi_override(1, 0, fallback, None), fallback);
    }

    #[test]
    fn light_theme_darkens_bright_foreground() {
        let light = Snapshot {
            cols: 1,
            rows: 1,
            lines: Vec::new(),
            cursor: None,
            cursor_style: 0,
            default_fg: Rgb { r: 0, g: 0, b: 0 },
            default_bg: Rgb { r: 255, g: 255, b: 255 },
            cursor_color: Rgb { r: 0, g: 0, b: 0 },
            dirty: 0,
            dirty_rows: Vec::new(),
            selection_color: DEFAULT_SELECTION_COLOR,
            palette: Some(Palette {
                fg: Rgb { r: 0, g: 0, b: 0 },
                bg: Rgb { r: 255, g: 255, b: 255 },
                selection: DEFAULT_SELECTION_COLOR,
                cursor: Rgb { r: 0, g: 0, b: 0 },
                ansi: DEFAULT_ANSI_16,
            }),
            ansi_override: None,
        };
        // 亮黄（256 色 PS1 常见）压暗后可读。
        let yellow = Rgb { r: 0xff, g: 0xd7, b: 0x5f };
        let adapted = adapt_light_fg(yellow, &light);
        assert!(color_luminance(adapted) < 140.0);
        assert!(adapted.r > adapted.b, "色相保留（黄）");
        // 深色不变。
        let dark_red = Rgb { r: 0xcd, g: 0, b: 0 };
        assert_eq!(adapt_light_fg(dark_red, &light), dark_red);

        // 深色主题（黑底）任何色原样。
        let dark = Snapshot {
            palette: Some(Palette {
                fg: Rgb { r: 255, g: 255, b: 255 },
                bg: Rgb { r: 0, g: 0, b: 0 },
                selection: DEFAULT_SELECTION_COLOR,
                cursor: Rgb { r: 255, g: 255, b: 255 },
                ansi: DEFAULT_ANSI_16,
            }),
            ..light
        };
        assert_eq!(adapt_light_fg(yellow, &dark), yellow);
    }
}

fn hash_row(row: &[Cell]) -> u64 {
    let mut hasher = DefaultHasher::new();
    for cell in row {
        cell.text.hash(&mut hasher);
        cell.fg.hash(&mut hasher);
        cell.bg.hash(&mut hasher);
        cell.selected.hash(&mut hasher);
        cell.underline.hash(&mut hasher);
        cell.underline_color.hash(&mut hasher);
        cell.strikethrough.hash(&mut hasher);
        cell.overline.hash(&mut hasher);
        cell.col_span.hash(&mut hasher);
    }
    hasher.finish()
}

// ---------- 字形图集 ----------

const ATLAS_SIZE: u32 = 2048;
// 彩色图集用更大的格子容纳 emoji 位图（NotoColorEmoji PNG 常见 24px 级）。
const COLOR_ATLAS_CELL: u32 = 64;
const COLOR_ATLAS_COLUMNS: u32 = ATLAS_SIZE / COLOR_ATLAS_CELL;
const COLOR_ATLAS_CAPACITY: u32 = COLOR_ATLAS_COLUMNS * COLOR_ATLAS_COLUMNS;
const EMBEDDED_MONO_FONT: &[u8] = include_bytes!("../assets/JetBrainsMono-Regular.ttf");

struct FontFace {
    font: fontdue::Font,
    collection_index: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct GlyphEntry {
    cell: u32,
    pub metrics: fontdue::Metrics,
    pub ascent: f32,
    pub descent: f32,
    pub line_gap: f32,
    pub pixels_per_em: f32,
    pub bitmap_w: u32,
    pub bitmap_h: u32,
    pub pad_x: u32,
    pub pad_y: u32,
    pub is_sprite: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct ColorGlyphEntry {
    pub cell: u32,
    pub bitmap_w: u32,
    pub bitmap_h: u32,
    pub pad_x: u32,
    pub pad_y: u32,
}

pub struct GlyphAtlas {
    faces: Vec<FontFace>,
    entries: HashMap<char, GlyphEntry>,
    pixels: Vec<u8>,
    next_cell: u32,
    revision: u64,
    pixels_per_em: f32,
    emoji_font: Option<crate::emoji::EmojiFont>,
    color_entries: HashMap<String, ColorGlyphEntry>,
    color_pixels: Vec<u8>,
    color_next_cell: u32,
    color_revision: u64,
}

fn load_font_face(bytes: &[u8], collection_index: u32) -> Option<fontdue::Font> {
    let settings = fontdue::FontSettings {
        collection_index,
        ..Default::default()
    };
    fontdue::Font::from_bytes(bytes, settings).ok()
}

fn first_cjk_face(bytes: &[u8]) -> Option<(u32, fontdue::Font)> {
    for index in 0..16u32 {
        if let Some(font) = load_font_face(bytes, index) {
            if font.lookup_glyph_index('中') != 0 || font.lookup_glyph_index('漢') != 0 {
                return Some((index, font));
            }
        }
    }
    None
}

fn system_font_candidates() -> Vec<&'static str> {
    vec![
        "/system/fonts/MiSansVF.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSansMono-Regular.ttf",
        "/system/fonts/DroidSansMono.ttf",
        "/system/fonts/RobotoMono-Regular.ttf",
        "/data/data/com.termux/files/usr/share/fonts/TTF/DejaVuSansMono.ttf",
    ]
}

impl GlyphAtlas {
    pub fn new() -> Option<Self> {
        let mut faces = Vec::new();
        if let Some(font) = load_font_face(EMBEDDED_MONO_FONT, 0) {
            faces.push(FontFace {
                font,
                collection_index: 0,
            });
        }
        for path in system_font_candidates() {
            let Ok(bytes) = std::fs::read(path) else {
                continue;
            };
            if let Some((collection_index, font)) = first_cjk_face(&bytes) {
                faces.push(FontFace {
                    font,
                    collection_index,
                });
                break;
            }
        }
        if faces.is_empty() {
            return None;
        }
        Some(Self {
            faces,
            entries: HashMap::new(),
            pixels: vec![0u8; (ATLAS_SIZE * ATLAS_SIZE * 4) as usize],
            next_cell: 1,
            revision: 0,
            pixels_per_em: DEFAULT_FONT_SIZE_PX,
            emoji_font: crate::emoji::load_emoji_font(),
            color_entries: HashMap::new(),
            color_pixels: vec![0u8; (ATLAS_SIZE * ATLAS_SIZE * 4) as usize],
            color_next_cell: 1,
            color_revision: 0,
        })
    }

    pub fn revision(&self) -> u64 {
        self.revision
    }

    pub fn pixels_per_em(&self) -> f32 {
        self.pixels_per_em
    }

    /// 切换字号：清空灰度/彩色图集，下一帧按新字号重光栅化（工单 14）。
    pub fn set_pixels_per_em(&mut self, px: f32) {
        let px = px.clamp(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX);
        if (px - self.pixels_per_em).abs() < f32::EPSILON {
            return;
        }
        self.pixels_per_em = px;
        self.entries.clear();
        self.pixels.iter_mut().for_each(|p| *p = 0);
        self.next_cell = 1;
        self.revision = self.revision.wrapping_add(1);
        self.color_entries.clear();
        self.color_pixels.iter_mut().for_each(|p| *p = 0);
        self.color_next_cell = 1;
        self.color_revision = self.color_revision.wrapping_add(1);
    }

    /// 灰度图集格子像素尺寸：随字号动态放大（至少 32px，1.05 倍余量覆盖
    /// 带升部/降部的字形，如 j、|、/、\）。修复工单 15 真机 >32px 缺字：
    /// 固定 32px 格子会把位图超格的字形整格丢弃（ensure_glyph 返回 None）。
    pub fn atlas_cell_px(&self) -> u32 {
        ((self.pixels_per_em * 1.05).ceil() as u32).max(32)
    }

    pub fn atlas_columns(&self) -> u32 {
        ATLAS_SIZE / self.atlas_cell_px()
    }

    pub fn atlas_capacity(&self) -> u32 {
        let columns = self.atlas_columns();
        columns * columns
    }

    /// 灰度字形 UV 矩形 (left, right, top, bottom)：集中"entry.cell + 当前格子
    /// 尺寸 → UV"的换算，避免调用方三处重复。entry 与图集字号不一致时断言
    /// （正常流程 set_pixels_per_em 会清空图集，不可能不一致）。
    pub fn uv_for(
        &self,
        entry: &GlyphEntry,
        atlas_w: f32,
        atlas_h: f32,
    ) -> (f32, f32, f32, f32) {
        debug_assert!(
            (entry.pixels_per_em - self.pixels_per_em).abs() < f32::EPSILON,
            "glyph entry font size mismatch: entry={} atlas={}",
            entry.pixels_per_em,
            self.pixels_per_em
        );
        let columns = self.atlas_columns();
        let cell_px = self.atlas_cell_px();
        let glyph_col = entry.cell % columns;
        let glyph_row = entry.cell / columns;
        let uv_left = (glyph_col * cell_px + entry.pad_x) as f32 / atlas_w;
        let uv_right = (glyph_col * cell_px + entry.pad_x + entry.bitmap_w) as f32 / atlas_w;
        let uv_top = (glyph_row * cell_px + entry.pad_y) as f32 / atlas_h;
        let uv_bottom = (glyph_row * cell_px + entry.pad_y + entry.bitmap_h) as f32 / atlas_h;
        (uv_left, uv_right, uv_top, uv_bottom)
    }

    /// 主终端布局用：按当前字号计算单元格像素尺寸。
    /// 宽 = 主字体 'M' advance，高 = 行度量（ascent - descent + line_gap）。
    pub fn cell_size(&self) -> (u32, u32) {
        let px = self.pixels_per_em;
        let font = &self.faces[0].font;
        let line = font.horizontal_line_metrics(px).unwrap_or(fontdue::LineMetrics {
            ascent: px * 0.8,
            descent: -px * 0.2,
            line_gap: 0.0,
            new_line_size: px,
        });
        let line_height = (line.ascent - line.descent + line.line_gap).max(px);
        let advance = font
            .rasterize_indexed(font.lookup_glyph_index('M'), px)
            .0
            .advance_width;
        let cell_w = advance.max(1.0).ceil() as u32;
        let cell_h = line_height.max(1.0).ceil() as u32;
        (cell_w.max(1), cell_h.max(1))
    }

    pub fn extent(&self) -> (u32, u32) {
        (ATLAS_SIZE, ATLAS_SIZE)
    }

    pub fn pixels(&self) -> &[u8] {
        &self.pixels
    }

    pub fn color_revision(&self) -> u64 {
        self.color_revision
    }

    pub fn color_pixels(&self) -> &[u8] {
        &self.color_pixels
    }

    pub fn color_extent(&self) -> (u32, u32) {
        (ATLAS_SIZE, ATLAS_SIZE)
    }

    pub fn color_glyph_count(&self) -> usize {
        self.color_entries.len()
    }

    pub fn glyph_count(&self) -> usize {
        self.entries.len()
    }

    pub fn emoji_ready(&self) -> bool {
        self.emoji_font.is_some()
    }

    /// 取灰度字形（字体字形或程序化 sprite）；空格/控制符/缺字返回 None。
    pub fn ensure_glyph(&mut self, ch: char) -> Option<GlyphEntry> {
        if let Some(entry) = self.entries.get(&ch) {
            return Some(*entry);
        }
        if ch == ' ' || ch.is_control() {
            return None;
        }
        if crate::symbols::is_sprite(ch) {
            return self.ensure_sprite_glyph(ch);
        }

        let font_index = self
            .faces
            .iter()
            .position(|face| face.font.lookup_glyph_index(ch) != 0)
            .unwrap_or(0);
        let glyph_index = self.faces[font_index].font.lookup_glyph_index(ch);
        let (metrics, bitmap) = self.faces[font_index]
            .font
            .rasterize_indexed(glyph_index, self.pixels_per_em);
        let line_metrics = self.faces[font_index]
            .font
            .horizontal_line_metrics(self.pixels_per_em)
            .unwrap_or(fontdue::LineMetrics {
                ascent: self.pixels_per_em * 0.8,
                descent: -self.pixels_per_em * 0.2,
                line_gap: 0.0,
                new_line_size: self.pixels_per_em,
            });
        if metrics.width == 0 || bitmap.is_empty() {
            return None;
        }

        let cell_px = self.atlas_cell_px();
        let mut bitmap_w = metrics.width as u32;
        let mut bitmap_h = metrics.height as u32;
        let mut bitmap_ref = bitmap.as_slice();
        let mut entry_metrics = metrics;
        let mut scaled: Vec<u8> = Vec::new();
        if bitmap_w > cell_px || bitmap_h > cell_px {
            // 罕见超高字形（个别 CJK/装饰符号位图超过 1.05 倍字号）：最近邻
            // 缩放到格内并等比修正度量，避免整格丢弃导致空白。
            let scale = (cell_px as f32 / bitmap_w.max(bitmap_h) as f32).min(1.0);
            let new_w = ((bitmap_w as f32 * scale).round() as u32).max(1);
            let new_h = ((bitmap_h as f32 * scale).round() as u32).max(1);
            scaled = vec![0u8; (new_w * new_h) as usize];
            for y in 0..new_h {
                let sy = (((y as f32 + 0.5) / scale) as usize).min(bitmap_h as usize - 1);
                for x in 0..new_w {
                    let sx = (((x as f32 + 0.5) / scale) as usize).min(bitmap_w as usize - 1);
                    scaled[(y * new_w + x) as usize] =
                        bitmap[(sy * bitmap_w as usize + sx) as usize];
                }
            }
            bitmap_w = new_w;
            bitmap_h = new_h;
            bitmap_ref = &scaled;
            entry_metrics = fontdue::Metrics {
                width: bitmap_w as usize,
                height: bitmap_h as usize,
                xmin: (metrics.xmin as f32 * scale).round() as i32,
                ymin: (metrics.ymin as f32 * scale).round() as i32,
                advance_width: metrics.advance_width * scale,
                advance_height: metrics.advance_height * scale,
                // 等比缩放轮廓包围盒（当前无读取方，保持与位图一致以防未来使用）。
                bounds: fontdue::OutlineBounds {
                    xmin: metrics.bounds.xmin * scale,
                    ymin: metrics.bounds.ymin * scale,
                    width: metrics.bounds.width * scale,
                    height: metrics.bounds.height * scale,
                },
            };
        }
        if self.next_cell >= self.atlas_capacity() {
            log_error("glyph atlas full");
            return None;
        }

        let cell = self.next_cell;
        self.next_cell += 1;
        let pad_x = (cell_px - bitmap_w) / 2;
        let pad_y = (cell_px - bitmap_h) / 2;
        let columns = self.atlas_columns();
        blit_glyph_alpha(
            &mut self.pixels,
            cell_px,
            columns,
            cell,
            pad_x,
            pad_y,
            bitmap_w,
            bitmap_h,
            bitmap_ref,
        );

        let entry = GlyphEntry {
            cell,
            metrics: entry_metrics,
            ascent: line_metrics.ascent,
            descent: line_metrics.descent,
            line_gap: line_metrics.line_gap,
            pixels_per_em: self.pixels_per_em,
            bitmap_w,
            bitmap_h,
            pad_x,
            pad_y,
            is_sprite: false,
        };
        self.revision = self.revision.wrapping_add(1);
        self.entries.insert(ch, entry);
        Some(entry)
    }

    /// 取彩色 emoji 字形（cluster 键：ZWJ 序列整段进图集）；
    /// 非 emoji run / 无彩色字体 / 光栅失败返回 None。
    pub fn ensure_color_glyph(&mut self, cluster: &str) -> Option<ColorGlyphEntry> {
        if let Some(entry) = self.color_entries.get(cluster) {
            return Some(*entry);
        }
        if !crate::emoji::is_emoji_run(cluster) {
            return None;
        }
        let font = self.emoji_font.as_ref()?;
        let bitmap = font.rasterize_cluster(cluster, self.pixels_per_em as u16)?;
        if bitmap.width == 0 || bitmap.height == 0 || bitmap.pixels.is_empty() {
            return None;
        }
        if self.color_next_cell >= COLOR_ATLAS_CAPACITY {
            log_error("color glyph atlas full");
            return None;
        }

        // 位图超过格子时最近邻缩小。
        let scale = (COLOR_ATLAS_CELL as f32 / bitmap.width.max(bitmap.height) as f32).min(1.0);
        let bitmap_w = (bitmap.width as f32 * scale).round().max(1.0) as u32;
        let bitmap_h = (bitmap.height as f32 * scale).round().max(1.0) as u32;
        let mut scaled = Vec::with_capacity((bitmap_w * bitmap_h * 4) as usize);
        for y in 0..bitmap_h {
            let sy = ((y as f32 + 0.5) / scale).min(bitmap.height as f32 - 1.0) as usize;
            for x in 0..bitmap_w {
                let sx = ((x as f32 + 0.5) / scale).min(bitmap.width as f32 - 1.0) as usize;
                let si = ((sy * bitmap.width as usize + sx) * 4) as usize;
                scaled.extend_from_slice(&bitmap.pixels[si..si + 4]);
            }
        }

        let cell = self.color_next_cell;
        self.color_next_cell += 1;
        let pad_x = (COLOR_ATLAS_CELL - bitmap_w) / 2;
        let pad_y = (COLOR_ATLAS_CELL - bitmap_h) / 2;
        let col = cell % COLOR_ATLAS_COLUMNS;
        let row = cell / COLOR_ATLAS_COLUMNS;
        for gy in 0..bitmap_h {
            for gx in 0..bitmap_w {
                let alpha = scaled[((gy * bitmap_w + gx) * 4 + 3) as usize];
                if alpha == 0 {
                    continue;
                }
                let px = col * COLOR_ATLAS_CELL + pad_x + gx;
                let py = row * COLOR_ATLAS_CELL + pad_y + gy;
                let i = ((py * ATLAS_SIZE + px) * 4) as usize;
                let si = ((gy * bitmap_w + gx) * 4) as usize;
                self.color_pixels[i] = scaled[si];
                self.color_pixels[i + 1] = scaled[si + 1];
                self.color_pixels[i + 2] = scaled[si + 2];
                self.color_pixels[i + 3] = scaled[si + 3];
            }
        }

        let entry = ColorGlyphEntry {
            cell,
            bitmap_w,
            bitmap_h,
            pad_x,
            pad_y,
        };
        self.color_revision = self.color_revision.wrapping_add(1);
        self.color_entries.insert(cluster.to_string(), entry);
        Some(entry)
    }

    /// 程序化 sprite 字形写进灰度图集（工单 12 ③）。
    fn ensure_sprite_glyph(&mut self, ch: char) -> Option<GlyphEntry> {
        let cell_px = self.atlas_cell_px();
        let bitmap = crate::symbols::sprite_bitmap(ch, cell_px)?;
        if self.next_cell >= self.atlas_capacity() {
            log_error("glyph atlas full");
            return None;
        }
        let bitmap_w = bitmap.width;
        let bitmap_h = bitmap.height;
        let cell = self.next_cell;
        self.next_cell += 1;
        let pad_x = (cell_px - bitmap_w) / 2;
        let pad_y = (cell_px - bitmap_h) / 2;
        let columns = self.atlas_columns();
        blit_glyph_alpha(
            &mut self.pixels,
            cell_px,
            columns,
            cell,
            pad_x,
            pad_y,
            bitmap_w,
            bitmap_h,
            &bitmap.alpha,
        );
        let entry = GlyphEntry {
            cell,
            metrics: fontdue::Metrics {
                width: bitmap_w as usize,
                height: bitmap_h as usize,
                xmin: 0,
                ymin: 0,
                advance_width: self.pixels_per_em,
                advance_height: 0.0,
                bounds: fontdue::OutlineBounds::default(),
            },
            ascent: self.pixels_per_em,
            descent: 0.0,
            line_gap: 0.0,
            pixels_per_em: self.pixels_per_em,
            bitmap_w,
            bitmap_h,
            pad_x,
            pad_y,
            is_sprite: true,
        };
        self.revision = self.revision.wrapping_add(1);
        self.entries.insert(ch, entry);
        Some(entry)
    }
}

/// 把单通道灰度位图按居中 padding 写入灰度图集格（RGB=255 + alpha）。
/// ensure_glyph 与 ensure_sprite_glyph 共用，避免重复的逐像素写入。
fn blit_glyph_alpha(
    pixels: &mut [u8],
    cell_px: u32,
    columns: u32,
    cell: u32,
    pad_x: u32,
    pad_y: u32,
    bitmap_w: u32,
    bitmap_h: u32,
    src: &[u8],
) {
    let col = cell % columns;
    let row = cell / columns;
    for gy in 0..bitmap_h {
        for gx in 0..bitmap_w {
            let alpha = src[(gy * bitmap_w + gx) as usize];
            if alpha == 0 {
                continue;
            }
            let px = col * cell_px + pad_x + gx;
            let py = row * cell_px + pad_y + gy;
            let i = ((py * ATLAS_SIZE + px) * 4) as usize;
            pixels[i] = 255;
            pixels[i + 1] = 255;
            pixels[i + 2] = 255;
            pixels[i + 3] = alpha;
        }
    }
}

// ---------- 顶点 ----------

#[derive(Debug, Clone, Copy)]
pub struct Vertex {
    pub position: [f32; 2],
    pub tex_coord: [f32; 2],
    pub color: [f32; 4],
    pub mode: f32,
}

const VERTEX_STRIDE: u64 = 36;
const MODE_SOLID: f32 = 0.0;
const MODE_GLYPH: f32 = 1.0;
const MODE_COLOR_GLYPH: f32 = 2.0;

impl Vertex {
    fn new(position: [f32; 2], tex_coord: [f32; 2], color: [f32; 4], mode: f32) -> Self {
        Self {
            position,
            tex_coord,
            color,
            mode,
        }
    }

    fn write(&self, out: &mut Vec<u8>) {
        for v in self.position {
            out.extend_from_slice(&v.to_le_bytes());
        }
        for v in self.tex_coord {
            out.extend_from_slice(&v.to_le_bytes());
        }
        for v in self.color {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.extend_from_slice(&self.mode.to_le_bytes());
    }

    fn write_to(&self, out: &mut [u8], offset: usize) {
        let mut i = offset;
        for v in self.position {
            out[i..i + 4].copy_from_slice(&v.to_le_bytes());
            i += 4;
        }
        for v in self.tex_coord {
            out[i..i + 4].copy_from_slice(&v.to_le_bytes());
            i += 4;
        }
        for v in self.color {
            out[i..i + 4].copy_from_slice(&v.to_le_bytes());
            i += 4;
        }
        out[i..i + 4].copy_from_slice(&self.mode.to_le_bytes());
    }
}

fn rgb_components(color: Rgb) -> [f32; 4] {
    [
        color.r as f32 / 255.0,
        color.g as f32 / 255.0,
        color.b as f32 / 255.0,
        1.0,
    ]
}

fn push_rect(
    vertices: &mut Vec<Vertex>,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    color: [f32; 4],
    uv: [[f32; 2]; 4],
    mode: f32,
    surface_w: f32,
    surface_h: f32,
) {
    if width <= 0.0 || height <= 0.0 {
        return;
    }
    let x0 = (x / surface_w) * 2.0 - 1.0;
    let x1 = ((x + width) / surface_w) * 2.0 - 1.0;
    let y0 = 1.0 - (y / surface_h) * 2.0;
    let y1 = 1.0 - ((y + height) / surface_h) * 2.0;
    vertices.extend_from_slice(&[
        Vertex::new([x0, y1], uv[0], color, mode),
        Vertex::new([x1, y1], uv[1], color, mode),
        Vertex::new([x0, y0], uv[2], color, mode),
        Vertex::new([x1, y1], uv[1], color, mode),
        Vertex::new([x1, y0], uv[3], color, mode),
        Vertex::new([x0, y0], uv[2], color, mode),
    ]);
}

fn terminal_cell_width(ch: char) -> u32 {
    if ch == '\t' {
        return 4;
    }
    if ch.is_control() {
        return 0;
    }
    let codepoint = ch as u32;
    if (0x0300..=0x036f).contains(&codepoint)
        || (0x1ab0..=0x1aff).contains(&codepoint)
        || (0x1dc0..=0x1dff).contains(&codepoint)
        || (0x20d0..=0x20ff).contains(&codepoint)
        || (0xfe20..=0xfe2f).contains(&codepoint)
    {
        return 0;
    }
    if (0x1100..=0x115f).contains(&codepoint)
        || (0x2329..=0x232a).contains(&codepoint)
        || (0x2e80..=0xa4cf).contains(&codepoint)
        || (0xac00..=0xd7a3).contains(&codepoint)
        || (0xf900..=0xfaff).contains(&codepoint)
        || (0xfe10..=0xfe19).contains(&codepoint)
        || (0xfe30..=0xfe6f).contains(&codepoint)
        || (0xff00..=0xff60).contains(&codepoint)
        || (0xffe0..=0xffe6).contains(&codepoint)
        || (0x1f300..=0x1faff).contains(&codepoint)
        || (0x2300..=0x23ff).contains(&codepoint)
        || (0x2600..=0x27bf).contains(&codepoint)
        || (0x2b00..=0x2bff).contains(&codepoint)
    {
        return 2;
    }
    1
}

fn push_glyph_rect(
    vertices: &mut Vec<Vertex>,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    color: [f32; 4],
    uv_left: f32,
    uv_right: f32,
    uv_top: f32,
    uv_bottom: f32,
    surface_w: f32,
    surface_h: f32,
) {
    push_rect(
        vertices,
        x,
        y,
        width,
        height,
        color,
        [
            [uv_left, uv_bottom],
            [uv_right, uv_bottom],
            [uv_left, uv_top],
            [uv_right, uv_top],
        ],
        MODE_GLYPH,
        surface_w,
        surface_h,
    );
}

fn push_color_glyph_rect(
    vertices: &mut Vec<Vertex>,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    uv_left: f32,
    uv_right: f32,
    uv_top: f32,
    uv_bottom: f32,
    surface_w: f32,
    surface_h: f32,
) {
    push_rect(
        vertices,
        x,
        y,
        width,
        height,
        [1.0, 1.0, 1.0, 1.0],
        [
            [uv_left, uv_bottom],
            [uv_right, uv_bottom],
            [uv_left, uv_top],
            [uv_right, uv_top],
        ],
        MODE_COLOR_GLYPH,
        surface_w,
        surface_h,
    );
}

fn push_solid_rect(
    vertices: &mut Vec<Vertex>,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    color: [f32; 4],
    surface_w: f32,
    surface_h: f32,
) {
    push_rect(
        vertices,
        x,
        y,
        width,
        height,
        color,
        [[0.0, 1.0], [1.0, 1.0], [0.0, 0.0], [1.0, 0.0]],
        MODE_SOLID,
        surface_w,
        surface_h,
    );
}

fn selection_components(color: Rgb) -> [f32; 4] {
    [
        color.r as f32 / 255.0,
        color.g as f32 / 255.0,
        color.b as f32 / 255.0,
        0.45,
    ]
}

fn fg_for_cell(cell: &Cell, snapshot: &Snapshot) -> Rgb {
    // 坑 H 的近黑转白启发只在"核心解析配色"下生效；
    // 主终端 push 配色板后按 push 值原样渲染。
    let core_heuristic = snapshot.palette.is_none();
    let mut fg_color = match cell.fg {
        Some(color) => color,
        None => {
            let default = snapshot.default_fg;
            if core_heuristic && default.r < 40 && default.g < 40 && default.b < 40 {
                Rgb {
                    r: 229,
                    g: 229,
                    b: 229,
                }
            } else {
                default
            }
        }
    };
    if core_heuristic
        && fg_color.r < 40
        && fg_color.g < 40
        && fg_color.b < 40
        && cell.bg.is_none()
    {
        fg_color = Rgb {
            r: 229,
            g: 229,
            b: 229,
        };
    }
    adapt_light_fg(fg_color, snapshot)
}

fn color_luminance(color: Rgb) -> f32 {
    0.241 * color.r as f32 + 0.691 * color.g as f32 + 0.068 * color.b as f32
}

/// 工单 04（用户拍板）：浅色主题（白底）下，任何亮色前景（含 256 色/真彩 PS1 提示符
/// 如 `$`）压暗到白底可读范围；深色主题原样。色相保留、只降亮度。
fn adapt_light_fg(color: Rgb, snapshot: &Snapshot) -> Rgb {
    let light_bg = snapshot
        .palette
        .is_some_and(|palette| color_luminance(palette.bg) >= 128.0);
    if !light_bg {
        return color;
    }
    let lum = color_luminance(color);
    if lum <= 160.0 {
        return color;
    }
    let scale = (140.0 / lum).clamp(0.42, 0.75);
    Rgb {
        r: ((color.r as f32) * scale).round().min(255.0) as u8,
        g: ((color.g as f32) * scale).round().min(255.0) as u8,
        b: ((color.b as f32) * scale).round().min(255.0) as u8,
    }
}

/// 重建单行顶点（工单 12 ①：脏行增量重建）。
pub fn build_row_vertices(
    row_index: usize,
    row: &[Cell],
    snapshot: &Snapshot,
    atlas: &mut GlyphAtlas,
    width_px: u32,
    height_px: u32,
) -> Vec<Vertex> {
    let surface_w = width_px.max(1) as f32;
    let surface_h = height_px.max(1) as f32;
    let cols = snapshot.cols.max(1) as f32;
    let rows = snapshot.rows.max(1) as f32;
    let cell_w = surface_w / cols;
    let row_h = surface_h / rows;
    let mut vertices = Vec::<Vertex>::new();
    let (atlas_w, atlas_h) = atlas.extent();
    let (color_w, color_h) = atlas.color_extent();
    let row_y = row_index as f32 * row_h;

    // 工单 13：按累计列定位（col_span 由 merge_emoji_runs 归一；核心拆分的
    // ZWJ/旗帜/⌨️ 片段不再各自占位，避免位图重叠/被拆成两字）。
    let mut col_pos = 0u32;
    for cell in row {
        let x = col_pos as f32 * cell_w;
        let y = row_y;

        // 清屏色 = push 配色板背景（否则维持现状深灰）；
        // 显式背景只在异于"实际底"时才画矩形，避免 push 后默认格被清屏色盖错。
        let bg_compare = snapshot
            .palette
            .map(|palette| palette.bg)
            .unwrap_or(snapshot.default_bg);
        if let Some(bg) = cell.bg.filter(|bg| *bg != bg_compare) {
            push_solid_rect(
                &mut vertices,
                x,
                y,
                cell_w,
                row_h,
                rgb_components(bg),
                surface_w,
                surface_h,
            );
        }
        if cell.selected {
            push_solid_rect(
                &mut vertices,
                x,
                y,
                cell_w,
                row_h,
                selection_components(snapshot.selection_color),
                surface_w,
                surface_h,
            );
        }

        let Some(ch) = cell.text.chars().next() else {
            continue;
        };
        let width_cells = terminal_cell_width(ch);
        if width_cells == 0 {
            continue;
        }
        let target_cell_w = cell_w * width_cells as f32;
        let fg = rgb_components(fg_for_cell(cell, snapshot));

        // 彩色 emoji 优先走彩色图集（工单 13：整段 cluster 整形后入图集）。
        if let Some(color_entry) = atlas.ensure_color_glyph(&cell.text) {
            let scale = ((target_cell_w * 0.92) / color_entry.bitmap_w.max(1) as f32)
                .min((row_h * 0.92) / color_entry.bitmap_h.max(1) as f32)
                .max(0.01);
            let draw_w = color_entry.bitmap_w as f32 * scale;
            let draw_h = color_entry.bitmap_h as f32 * scale;
            let draw_x = x + (target_cell_w - draw_w) / 2.0;
            let draw_y = y + (row_h - draw_h) / 2.0;
            let col = color_entry.cell % COLOR_ATLAS_COLUMNS;
            let row = color_entry.cell / COLOR_ATLAS_COLUMNS;
            let uv_left = (col * COLOR_ATLAS_CELL + color_entry.pad_x) as f32 / color_w as f32;
            let uv_right =
                (col * COLOR_ATLAS_CELL + color_entry.pad_x + color_entry.bitmap_w) as f32
                    / color_w as f32;
            let uv_top = (row * COLOR_ATLAS_CELL + color_entry.pad_y) as f32 / color_h as f32;
            let uv_bottom =
                (row * COLOR_ATLAS_CELL + color_entry.pad_y + color_entry.bitmap_h) as f32
                    / color_h as f32;
            push_color_glyph_rect(
                &mut vertices,
                draw_x,
                draw_y,
                draw_w,
                draw_h,
                uv_left,
                uv_right,
                uv_top,
                uv_bottom,
                surface_w,
                surface_h,
            );
            continue;
        }

        let Some(entry) = atlas.ensure_glyph(ch) else {
            continue;
        };
        let (uv_left, uv_right, uv_top, uv_bottom) =
            atlas.uv_for(&entry, atlas_w as f32, atlas_h as f32);

        if entry.is_sprite {
            // sprite face：整格绘制（工单 12 ③）。
            push_glyph_rect(
                &mut vertices,
                x,
                y,
                target_cell_w,
                row_h,
                fg,
                uv_left,
                uv_right,
                uv_top,
                uv_bottom,
                surface_w,
                surface_h,
            );
        } else {
            // 宽字符从双格盒左缘绘制（半格偏移会把字形右推并溢出到下一列）；
            // 半角宽度为 1，该偏移量本身为 0，两分支共用此式。
            let glyph_x = x + if width_cells >= 2 {
                0.0
            } else {
                ((target_cell_w - cell_w) / 2.0).max(0.0)
            };
            let metrics = entry.metrics;
            let line_height_px =
                (entry.ascent - entry.descent + entry.line_gap).max(entry.pixels_per_em);
            let scale = if width_cells >= 2 {
                // 宽字符（CJK/全角标点）按位图尺寸约束适度放大填双格（上限 1.15，
                // 标点这类小位图不会被撑爆），收窄与 ASCII 并排时的观感间距
                // （fable-v1/15 真机"中文间距特别宽"）。
                1.15f32
                    .min((target_cell_w * 0.85) / entry.bitmap_w.max(1) as f32)
                    .min((row_h * 0.92) / entry.bitmap_h.max(1) as f32)
                    .max(0.5)
            } else {
                let advance_scale = cell_w / metrics.advance_width.max(1.0);
                let height_scale = (row_h * 0.92) / line_height_px.max(1.0);
                advance_scale.min(height_scale).max(0.01)
            };
            let advance_width = metrics.advance_width * scale;
            let x_padding = ((target_cell_w - advance_width) / 2.0).max(0.0);
            let draw_x = glyph_x + x_padding + metrics.xmin as f32 * scale;
            let baseline = y
                + ((row_h - line_height_px * scale) / 2.0).max(0.0)
                + entry.ascent * scale;
            let draw_y = baseline - (metrics.ymin + metrics.height as i32) as f32 * scale;
            let draw_width = entry.bitmap_w as f32 * scale;
            let draw_height = entry.bitmap_h as f32 * scale;
            push_glyph_rect(
                &mut vertices,
                draw_x,
                draw_y,
                draw_width,
                draw_height,
                fg,
                uv_left,
                uv_right,
                uv_top,
                uv_bottom,
                surface_w,
                surface_h,
            );
        }

        // 程序化文字装饰（下划线/删除线/上划线，不依赖字体）。
        // 工单 13：25px 行高下 2px 真机仍不可见 → max(3px, 12% 行高)。
        let thickness = (row_h * 0.12).max(3.0);
        if cell.underline {
            // 更高对比：样式显式下划线色优先，否则用前景色。
            let ul_color = cell.underline_color.unwrap_or(fg_for_cell(cell, snapshot));
            push_solid_rect(
                &mut vertices,
                x,
                y + row_h - thickness,
                target_cell_w,
                thickness,
                rgb_components(ul_color),
                surface_w,
                surface_h,
            );
        }
        if cell.strikethrough {
            push_solid_rect(
                &mut vertices,
                x,
                y + row_h * 0.48,
                target_cell_w,
                thickness,
                fg,
                surface_w,
                surface_h,
            );
        }
        if cell.overline {
            push_solid_rect(
                &mut vertices,
                x,
                y,
                target_cell_w,
                thickness,
                fg,
                surface_w,
                surface_h,
            );
        }
        col_pos += cell.col_span.max(1);
    }
    vertices
}

/// 重建 overlay（选择叠加 + 光标，光标按 visual style 程序化绘制）。
pub fn build_overlay_vertices(
    snapshot: &Snapshot,
    overlays: &[OverlayRange],
    width_px: u32,
    height_px: u32,
) -> Vec<Vertex> {
    let surface_w = width_px.max(1) as f32;
    let surface_h = height_px.max(1) as f32;
    let cols = snapshot.cols.max(1) as f32;
    let rows = snapshot.rows.max(1) as f32;
    let cell_w = surface_w / cols;
    let row_h = surface_h / rows;
    let mut vertices = Vec::<Vertex>::new();

    for overlay in overlays {
        if overlay.row as u16 >= snapshot.rows {
            continue;
        }
        let start = overlay.start_col.min(snapshot.cols as u32);
        let end = overlay.end_col.min(snapshot.cols as u32);
        if end <= start {
            continue;
        }
        push_solid_rect(
            &mut vertices,
            start as f32 * cell_w,
            overlay.row as f32 * row_h,
            (end - start) as f32 * cell_w,
            row_h,
            selection_components(snapshot.selection_color),
            surface_w,
            surface_h,
        );
    }

    if let Some((cursor_x, cursor_y)) = snapshot.cursor {
        if (cursor_y as usize) < snapshot.lines.len() {
            let mut cursor_rgb = snapshot.cursor_color;
            // 坑 H 近黑转亮蓝只在核心解析配色下生效；push 配色板后按原样渲染。
            if snapshot.palette.is_none()
                && cursor_rgb.r < 40
                && cursor_rgb.g < 40
                && cursor_rgb.b < 40
            {
                cursor_rgb = Rgb {
                    r: 80,
                    g: 200,
                    b: 255,
                };
            }
            let mut color = rgb_components(cursor_rgb);
            color[3] = 0.9;
            let x = cursor_x as f32 * cell_w;
            let y = cursor_y as f32 * row_h;
            match snapshot.cursor_style {
                0 => {
                    // BAR：左侧竖条
                    push_solid_rect(
                        &mut vertices,
                        x,
                        y,
                        (cell_w * 0.1).max(2.0),
                        row_h,
                        color,
                        surface_w,
                        surface_h,
                    );
                }
                1 => {
                    // BLOCK：整格
                    push_solid_rect(
                        &mut vertices,
                        x,
                        y + 2.0,
                        cell_w,
                        (row_h - 4.0).max(1.0),
                        color,
                        surface_w,
                        surface_h,
                    );
                }
                2 => {
                    // UNDERLINE：底部线（工单 13：更粗 max(3px, 12%)）
                    let thickness = (row_h * 0.12).max(3.0);
                    push_solid_rect(
                        &mut vertices,
                        x,
                        y + row_h - thickness,
                        cell_w,
                        thickness,
                        color,
                        surface_w,
                        surface_h,
                    );
                }
                _ => {
                    // BLOCK_HOLLOW / 未知：描边矩形
                    let t = (row_h * 0.08).max(2.0);
                    push_solid_rect(&mut vertices, x, y, cell_w, t, color, surface_w, surface_h);
                    push_solid_rect(
                        &mut vertices,
                        x,
                        y + row_h - t,
                        cell_w,
                        t,
                        color,
                        surface_w,
                        surface_h,
                    );
                    push_solid_rect(
                        &mut vertices,
                        x,
                        y,
                        t,
                        row_h,
                        color,
                        surface_w,
                        surface_h,
                    );
                    push_solid_rect(
                        &mut vertices,
                        x + cell_w - t,
                        y,
                        t,
                        row_h,
                        color,
                        surface_w,
                        surface_h,
                    );
                }
            }
        }
    }
    vertices
}

/// 脏行增量顶点存储（工单 12 ①）：每行固定容量槽位，行内顶点数可变，
/// 只重写脏行槽位并增量上传；行间互不影响。
// 最坏情况：背景 + 选择 + 字形 + 下划线/删除线/上划线 = 6 个矩形 = 36 顶点。
pub const MAX_VERTS_PER_CELL: usize = 36;
const OVERLAY_VERT_CAPACITY: usize = 1024;

pub struct RowVertexStore {
    cols: u16,
    rows: u16,
    slot_verts: usize,
    slot_bytes: u64,
    overlay_byte_offset: u64,
    capacity_bytes: u64,
    payload: Vec<u8>,
    row_counts: Vec<usize>,
    overlay_count: usize,
}

/// 槽位字节对齐：需同时整除顶点步长 36 与 wgpu write_buffer 的 256 对齐，
/// 使"字节偏移 / 36 = 顶点索引"恒成立。lcm(36,256)=2304。
fn align_slot(v: u64) -> u64 {
    let rem = v % 2304;
    if rem == 0 {
        v
    } else {
        v + 2304 - rem
    }
}

impl RowVertexStore {
    pub fn new(cols: u16, rows: u16) -> Self {
        let mut store = Self {
            cols: 0,
            rows: 0,
            slot_verts: 0,
            slot_bytes: 0,
            overlay_byte_offset: 0,
            capacity_bytes: 0,
            payload: Vec::new(),
            row_counts: Vec::new(),
            overlay_count: 0,
        };
        store.reset(cols, rows);
        store
    }

    pub fn reset(&mut self, cols: u16, rows: u16) {
        self.cols = cols.max(1);
        self.rows = rows.max(1);
        self.slot_verts = self.cols as usize * MAX_VERTS_PER_CELL;
        self.slot_bytes = align_slot(self.slot_verts as u64 * VERTEX_STRIDE);
        self.overlay_byte_offset = align_slot(self.rows as u64 * self.slot_bytes);
        self.capacity_bytes = self.overlay_byte_offset + OVERLAY_VERT_CAPACITY as u64 * VERTEX_STRIDE;
        self.payload = vec![0u8; self.capacity_bytes as usize];
        self.row_counts = vec![0usize; self.rows as usize];
        self.overlay_count = 0;
    }

    pub fn dims(&self) -> (u16, u16) {
        (self.cols, self.rows)
    }

    pub fn row_byte_range(&self, row: usize) -> (u64, usize) {
        let offset = row as u64 * self.slot_bytes;
        let len = self.row_counts[row] * VERTEX_STRIDE as usize;
        (offset, len)
    }

    pub fn overlay_byte_range(&self) -> (u64, usize) {
        (self.overlay_byte_offset, self.overlay_count * VERTEX_STRIDE as usize)
    }

    pub fn rebuild_row(
        &mut self,
        row: usize,
        snapshot: &Snapshot,
        atlas: &mut GlyphAtlas,
        width_px: u32,
        height_px: u32,
    ) -> (u64, usize) {
        if row >= snapshot.lines.len() {
            // 快照行缺失（瞬态 reflow/缩放），本行不重建，避免越界 panic。
            self.row_counts[row] = 0;
            let (offset, _) = self.row_byte_range(row);
            return (offset, 0);
        }
        let vertices = build_row_vertices(row, &snapshot.lines[row], snapshot, atlas, width_px, height_px);
        self.row_counts[row] = vertices.len();
        let (offset, len) = self.row_byte_range(row);
        for (i, vertex) in vertices.iter().enumerate() {
            let base = offset as usize + i * VERTEX_STRIDE as usize;
            vertex.write_to(&mut self.payload, base);
        }
        (offset, len)
    }

    pub fn rebuild_overlays(
        &mut self,
        snapshot: &Snapshot,
        overlays: &[OverlayRange],
        width_px: u32,
        height_px: u32,
    ) -> (u64, usize) {
        let vertices = build_overlay_vertices(snapshot, overlays, width_px, height_px);
        self.overlay_count = vertices.len();
        let (offset, len) = self.overlay_byte_range();
        for (i, vertex) in vertices.iter().enumerate() {
            let base = offset as usize + i * VERTEX_STRIDE as usize;
            vertex.write_to(&mut self.payload, base);
        }
        (offset, len)
    }

    /// 本帧所有非空行的绘制区间 + overlay 区间。
    pub fn draw_ranges(&self) -> Vec<(u32, u32)> {
        let mut ranges = Vec::new();
        for (row, count) in self.row_counts.iter().enumerate() {
            if *count > 0 {
                let start = (row as u64 * self.slot_bytes / VERTEX_STRIDE) as u32;
                ranges.push((start, *count as u32));
            }
        }
        if self.overlay_count > 0 {
            let start = (self.overlay_byte_offset / VERTEX_STRIDE) as u32;
            ranges.push((start, self.overlay_count as u32));
        }
        ranges
    }

    pub fn payload(&self) -> &[u8] {
        &self.payload
    }
}

/// 全量构建（基准/回归用）：与增量路径共用同一行构建器。
pub fn build_vertices(
    snapshot: &Snapshot,
    atlas: &mut GlyphAtlas,
    overlays: &[OverlayRange],
    width_px: u32,
    height_px: u32,
) -> (Vec<u8>, usize) {
    let mut store = RowVertexStore::new(snapshot.cols, snapshot.rows);
    let mut upload_ranges = Vec::new();
    for row in 0..snapshot.lines.len() {
        upload_ranges.push(store.rebuild_row(row, snapshot, atlas, width_px, height_px));
    }
    upload_ranges.push(store.rebuild_overlays(snapshot, overlays, width_px, height_px));
    let vertex_count = store
        .row_counts
        .iter()
        .sum::<usize>()
        .saturating_add(store.overlay_count);
    let _ = upload_ranges;
    (store.payload().to_vec(), vertex_count)
}

// ---------- wgpu runtime ----------

struct GpuSurface {
    surface: wgpu::Surface<'static>,
    config: wgpu::SurfaceConfiguration,
    window: *mut c_void,
    width: u32,
    height: u32,
}

struct GpuRuntime {
    instance: wgpu::Instance,
    adapter: wgpu::Adapter,
    device: wgpu::Device,
    queue: wgpu::Queue,
    backend: String,
    adapter_name: String,
    format: wgpu::TextureFormat,
    surface: Option<GpuSurface>,
    pipeline: Option<wgpu::RenderPipeline>,
    bind_group_layout: Option<wgpu::BindGroupLayout>,
    sampler: Option<wgpu::Sampler>,
    atlas_texture: Option<wgpu::Texture>,
    atlas_bind_group: Option<wgpu::BindGroup>,
    atlas_uploaded_revision: u64,
    atlas_bind_group_revision: u64,
    vertex_buffer: Option<wgpu::Buffer>,
    vertex_capacity: u64,
    attach_count: u64,
    present_count: u64,
    terminal_present_count: u64,
    acquire_success: u64,
    acquire_occluded: u64,
    acquire_timeout: u64,
    acquire_outdated: u64,
    acquire_validation: u64,
    draw_attempts: u64,
}

impl GpuRuntime {
    fn create() -> Result<Self, String> {
        let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
        descriptor.backends = wgpu::Backends::VULKAN;
        let instance = wgpu::Instance::new(descriptor);

        let adapters = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN));
        let adapter = adapters
            .first()
            .cloned()
            .ok_or_else(|| "no Vulkan adapter".to_string())?;
        let info = adapter.get_info();
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("fable-terminal-frame-device"),
            required_limits: adapter.limits(),
            ..Default::default()
        }))
        .map_err(|error| format!("request_device failed: {error}"))?;

        Ok(Self {
            instance,
            adapter,
            device,
            queue,
            backend: format!("{:?}", info.backend),
            adapter_name: info.name,
            format: wgpu::TextureFormat::Rgba8Unorm,
            surface: None,
            pipeline: None,
            bind_group_layout: None,
            sampler: None,
            atlas_texture: None,
            atlas_bind_group: None,
            atlas_uploaded_revision: 0,
            atlas_bind_group_revision: 0,
            vertex_buffer: None,
            vertex_capacity: 0,
            attach_count: 0,
            present_count: 0,
            terminal_present_count: 0,
            acquire_success: 0,
            acquire_occluded: 0,
            acquire_timeout: 0,
            acquire_outdated: 0,
            acquire_validation: 0,
            draw_attempts: 0,
        })
    }

    fn attach_surface(&mut self, window: *mut c_void, width: u32, height: u32) -> Result<(), String> {
        if let Some(surface) = &self.surface {
            if surface.window == window && surface.width == width && surface.height == height {
                return Ok(());
            }
            self.detach_surface();
        }
        if window.is_null() {
            return Err("null ANativeWindow".to_string());
        }

        let raw_window_handle =
            wgpu::rwh::AndroidNdkWindowHandle::new(NonNull::new(window).unwrap()).into();
        let raw_display_handle = wgpu::rwh::AndroidDisplayHandle::new().into();
        let surface = unsafe {
            self.instance
                .create_surface_unsafe(wgpu::SurfaceTargetUnsafe::RawHandle {
                    raw_display_handle: Some(raw_display_handle),
                    raw_window_handle,
                })
                .map_err(|error| format!("create_surface failed: {error}"))?
        };
        let config = surface
            .get_default_config(&self.adapter, width.max(1), height.max(1))
            .ok_or_else(|| "adapter cannot present to this surface".to_string())?;
        surface.configure(&self.device, &config);
        unsafe {
            ANativeWindow_acquire(window);
        }
        self.surface = Some(GpuSurface {
            surface,
            config,
            window,
            width: width.max(1),
            height: height.max(1),
        });
        self.attach_count = self.attach_count.saturating_add(1);
        Ok(())
    }

    fn detach_surface(&mut self) {
        if let Some(surface) = self.surface.take() {
            if !surface.window.is_null() {
                unsafe {
                    ANativeWindow_release(surface.window);
                }
            }
        }
    }

    fn present_clear(&mut self, background: [f32; 4]) -> Result<bool, String> {
        if self.surface.is_none() {
            return Ok(false);
        }
        let frame = {
            let surface_runtime = self
                .surface
                .as_mut()
                .ok_or_else(|| "surface disappeared".to_string())?;
            match surface_runtime.surface.get_current_texture() {
                wgpu::CurrentSurfaceTexture::Success(frame)
                | wgpu::CurrentSurfaceTexture::Suboptimal(frame) => {
                    self.acquire_success = self.acquire_success.saturating_add(1);
                    frame
                }
                wgpu::CurrentSurfaceTexture::Timeout => {
                    self.acquire_timeout = self.acquire_timeout.saturating_add(1);
                    return Ok(false);
                }
                wgpu::CurrentSurfaceTexture::Occluded => {
                    self.acquire_occluded = self.acquire_occluded.saturating_add(1);
                    return Ok(false);
                }
                wgpu::CurrentSurfaceTexture::Outdated | wgpu::CurrentSurfaceTexture::Lost => {
                    self.acquire_outdated = self.acquire_outdated.saturating_add(1);
                    surface_runtime
                        .surface
                        .configure(&self.device, &surface_runtime.config);
                    return Ok(false);
                }
                wgpu::CurrentSurfaceTexture::Validation => {
                    self.acquire_validation = self.acquire_validation.saturating_add(1);
                    return Err("surface validation failed".to_string());
                }
            }
        };
        let view = frame
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("fable-clear-encoder"),
            });
        {
            let color_attachments = [Some(wgpu::RenderPassColorAttachment {
                view: &view,
                depth_slice: None,
                resolve_target: None,
                ops: wgpu::Operations {
                    load: wgpu::LoadOp::Clear(wgpu::Color {
                        r: background[0] as f64,
                        g: background[1] as f64,
                        b: background[2] as f64,
                        a: background[3] as f64,
                    }),
                    store: wgpu::StoreOp::Store,
                },
            })];
            let _pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("fable-clear-pass"),
                color_attachments: &color_attachments,
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
        }
        self.queue.submit(Some(encoder.finish()));
        self.queue.present(frame);
        self.present_count = self.present_count.saturating_add(1);
        Ok(true)
    }

    fn upload_atlas(&mut self, atlas: &GlyphAtlas) -> Result<(), String> {
        let combined_revision = atlas.revision().wrapping_add(atlas.color_revision());
        if self.atlas_texture.is_some() && self.atlas_uploaded_revision == combined_revision {
            return Ok(());
        }
        let (width, height) = atlas.extent();
        let texture = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("fable-glyph-atlas"),
            size: wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 2,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: self.format,
            usage: wgpu::TextureUsages::COPY_DST | wgpu::TextureUsages::TEXTURE_BINDING,
            view_formats: &[],
        });
        let mut layers = Vec::with_capacity((width * height * 4 * 2) as usize);
        layers.extend_from_slice(atlas.pixels());
        layers.extend_from_slice(atlas.color_pixels());
        self.queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            &layers,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(width * 4),
                rows_per_image: Some(height),
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 2,
            },
        );
        self.atlas_texture = Some(texture);
        self.atlas_uploaded_revision = combined_revision;
        self.atlas_bind_group = None;
        self.atlas_bind_group_revision = 0;
        Ok(())
    }

    fn ensure_pipeline(&mut self, format: wgpu::TextureFormat) -> Result<(), String> {
        if self
            .pipeline
            .as_ref()
            .is_some_and(|_| self.surface.as_ref().is_some_and(|s| s.config.format == format))
        {
            return Ok(());
        }

        let shader = self
            .device
            .create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("fable-surface-shader"),
                source: wgpu::ShaderSource::Wgsl(SURFACE_SHADER.into()),
            });
        let bind_group_layout =
            self.device
                .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                    label: Some("fable-bind-group-layout"),
                    entries: &[
                        wgpu::BindGroupLayoutEntry {
                            binding: 0,
                            visibility: wgpu::ShaderStages::FRAGMENT,
                            ty: wgpu::BindingType::Texture {
                                sample_type: wgpu::TextureSampleType::Float { filterable: true },
                                view_dimension: wgpu::TextureViewDimension::D2Array,
                                multisampled: false,
                            },
                            count: None,
                        },
                        wgpu::BindGroupLayoutEntry {
                            binding: 1,
                            visibility: wgpu::ShaderStages::FRAGMENT,
                            ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                            count: None,
                        },
                    ],
                });
        let pipeline_layout = self
            .device
            .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("fable-pipeline-layout"),
                bind_group_layouts: &[Some(&bind_group_layout)],
                immediate_size: 0,
            });
        let pipeline = self
            .device
            .create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("fable-surface-pipeline"),
                layout: Some(&pipeline_layout),
                vertex: wgpu::VertexState {
                    module: &shader,
                    entry_point: Some("vs_main"),
                    compilation_options: wgpu::PipelineCompilationOptions::default(),
                    buffers: &[Some(wgpu::VertexBufferLayout {
                        array_stride: VERTEX_STRIDE,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &[
                            wgpu::VertexAttribute {
                                format: wgpu::VertexFormat::Float32x2,
                                offset: 0,
                                shader_location: 0,
                            },
                            wgpu::VertexAttribute {
                                format: wgpu::VertexFormat::Float32x2,
                                offset: 8,
                                shader_location: 1,
                            },
                            wgpu::VertexAttribute {
                                format: wgpu::VertexFormat::Float32x4,
                                offset: 16,
                                shader_location: 2,
                            },
                            wgpu::VertexAttribute {
                                format: wgpu::VertexFormat::Float32,
                                offset: 32,
                                shader_location: 3,
                            },
                        ],
                    })],
                },
                fragment: Some(wgpu::FragmentState {
                    module: &shader,
                    entry_point: Some("fs_main"),
                    compilation_options: wgpu::PipelineCompilationOptions::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format,
                        blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                primitive: wgpu::PrimitiveState {
                    topology: wgpu::PrimitiveTopology::TriangleList,
                    ..Default::default()
                },
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                multiview_mask: None,
                cache: None,
            });
        let sampler = self.device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("fable-atlas-sampler"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Nearest,
            ..Default::default()
        });

        self.bind_group_layout = Some(bind_group_layout);
        self.sampler = Some(sampler);
        self.pipeline = Some(pipeline);
        self.atlas_bind_group = None;
        self.atlas_bind_group_revision = 0;
        Ok(())
    }

    fn ensure_atlas_bind_group(&mut self) -> Result<(), String> {
        if self.atlas_bind_group.is_some()
            && self.atlas_bind_group_revision == self.atlas_uploaded_revision
        {
            return Ok(());
        }
        let layout = self
            .bind_group_layout
            .as_ref()
            .ok_or_else(|| "bind group layout missing".to_string())?;
        let sampler = self
            .sampler
            .as_ref()
            .ok_or_else(|| "sampler missing".to_string())?;
        let texture = self
            .atlas_texture
            .as_ref()
            .ok_or_else(|| "atlas texture missing".to_string())?;
        let texture_view = texture.create_view(&wgpu::TextureViewDescriptor {
            dimension: Some(wgpu::TextureViewDimension::D2Array),
            array_layer_count: Some(2),
            ..Default::default()
        });
        self.atlas_bind_group = Some(self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("fable-atlas-bind-group"),
            layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&texture_view),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(sampler),
                },
            ],
        }));
        self.atlas_bind_group_revision = self.atlas_uploaded_revision;
        Ok(())
    }

    fn write_vertex_ranges(
        &mut self,
        ranges: &[(u64, usize)],
        payload: &[u8],
    ) -> Result<(), String> {
        let required = ranges
            .iter()
            .map(|(offset, len)| offset + *len as u64)
            .max()
            .unwrap_or(0);
        if self.vertex_capacity < required {
            self.vertex_buffer =
                Some(self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("fable-vertex-buffer"),
                    size: required.max(1),
                    usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::VERTEX,
                    mapped_at_creation: false,
                }));
            self.vertex_capacity = required;
        }
        let buffer = self
            .vertex_buffer
            .as_ref()
            .ok_or_else(|| "vertex buffer missing".to_string())?;
        for (offset, len) in ranges {
            if *len == 0 {
                continue;
            }
            let start = *offset as usize;
            self.queue.write_buffer(buffer, *offset, &payload[start..start + *len]);
        }
        Ok(())
    }

    fn render_terminal(
        &mut self,
        draw_ranges: &[(u32, u32)],
        background: [f32; 4],
        atlas: &GlyphAtlas,
    ) -> Result<bool, String> {
        let Some(surface_runtime) = self.surface.as_ref() else {
            return Ok(false);
        };
        let surface_format = surface_runtime.config.format;
        let combined_revision = atlas.revision().wrapping_add(atlas.color_revision());
        let draw_terminal = !draw_ranges.is_empty() && self.atlas_uploaded_revision == combined_revision;
        if draw_terminal {
            self.ensure_pipeline(surface_format)?;
            self.ensure_atlas_bind_group()?;
        }
        if !draw_ranges.is_empty() {
            self.draw_attempts = self.draw_attempts.saturating_add(1);
        }

        let frame = {
            let surface_runtime = self
                .surface
                .as_mut()
                .ok_or_else(|| "surface disappeared".to_string())?;
            match surface_runtime.surface.get_current_texture() {
                wgpu::CurrentSurfaceTexture::Success(frame)
                | wgpu::CurrentSurfaceTexture::Suboptimal(frame) => {
                    self.acquire_success = self.acquire_success.saturating_add(1);
                    frame
                }
                wgpu::CurrentSurfaceTexture::Timeout => {
                    self.acquire_timeout = self.acquire_timeout.saturating_add(1);
                    return Ok(false);
                }
                wgpu::CurrentSurfaceTexture::Occluded => {
                    self.acquire_occluded = self.acquire_occluded.saturating_add(1);
                    return Ok(false);
                }
                wgpu::CurrentSurfaceTexture::Outdated | wgpu::CurrentSurfaceTexture::Lost => {
                    self.acquire_outdated = self.acquire_outdated.saturating_add(1);
                    let (actual_w, actual_h) = (
                        unsafe { fable_anw_get_width(surface_runtime.window) },
                        unsafe { fable_anw_get_height(surface_runtime.window) },
                    );
                    if actual_w > 0 && actual_h > 0 {
                        surface_runtime.width = actual_w as u32;
                        surface_runtime.height = actual_h as u32;
                        surface_runtime.config.width = actual_w as u32;
                        surface_runtime.config.height = actual_h as u32;
                    }
                    surface_runtime
                        .surface
                        .configure(&self.device, &surface_runtime.config);
                    return Ok(false);
                }
                wgpu::CurrentSurfaceTexture::Validation => {
                    self.acquire_validation = self.acquire_validation.saturating_add(1);
                    return Err("surface validation failed".to_string());
                }
            }
        };
        let view = frame
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("fable-frame-encoder"),
            });
        {
            let color_attachments = [Some(wgpu::RenderPassColorAttachment {
                view: &view,
                depth_slice: None,
                resolve_target: None,
                ops: wgpu::Operations {
                    load: wgpu::LoadOp::Clear(wgpu::Color {
                        r: background[0] as f64,
                        g: background[1] as f64,
                        b: background[2] as f64,
                        a: background[3] as f64,
                    }),
                    store: wgpu::StoreOp::Store,
                },
            })];
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("fable-frame-pass"),
                color_attachments: &color_attachments,
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            if draw_terminal {
                let pipeline = self
                    .pipeline
                    .as_ref()
                    .ok_or_else(|| "pipeline missing".to_string())?;
                let bind_group = self
                    .atlas_bind_group
                    .as_ref()
                    .ok_or_else(|| "bind group missing".to_string())?;
                let vertex_buffer = self
                    .vertex_buffer
                    .as_ref()
                    .ok_or_else(|| "vertex buffer missing".to_string())?;
                pass.set_pipeline(pipeline);
                pass.set_bind_group(0, bind_group, &[]);
                pass.set_vertex_buffer(0, vertex_buffer.slice(..));
                for (start, count) in draw_ranges {
                    if *count == 0 {
                        continue;
                    }
                    pass.draw(*start..start + count, 0..1);
                }
            }
        }
        self.queue.submit(Some(encoder.finish()));
        self.queue.present(frame);
        self.present_count = self.present_count.saturating_add(1);
        if draw_terminal {
            self.terminal_present_count = self.terminal_present_count.saturating_add(1);
        }
        Ok(true)
    }

}

impl Drop for GpuRuntime {
    fn drop(&mut self) {
        self.detach_surface();
    }
}

const SURFACE_SHADER: &str = r#"
struct VertexInput {
    @location(0) position: vec2<f32>,
    @location(1) tex_coord: vec2<f32>,
    @location(2) color: vec4<f32>,
    @location(3) mode: f32,
};

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) tex_coord: vec2<f32>,
    @location(1) color: vec4<f32>,
    @location(2) mode: f32,
};

@group(0) @binding(0) var glyph_atlas: texture_2d_array<f32>;
@group(0) @binding(1) var glyph_sampler: sampler;

@vertex
fn vs_main(input: VertexInput) -> VertexOutput {
    var output: VertexOutput;
    output.position = vec4<f32>(input.position, 0.0, 1.0);
    output.tex_coord = input.tex_coord;
    output.color = input.color;
    output.mode = input.mode;
    return output;
}

@fragment
fn fs_main(input: VertexOutput) -> @location(0) vec4<f32> {
    if (input.mode < 0.5) {
        return input.color;
    }
    var layer: u32;
    if (input.mode < 1.5) {
        layer = 0u;
    } else {
        layer = 1u;
    }
    let sample = textureSample(glyph_atlas, glyph_sampler, input.tex_coord, layer);
    if (input.mode >= 1.5) {
        // 彩色层：图集存 sRGB 值，先转线性，sRGB 帧缓冲再编码回去，
        // 避免中间色被提亮发灰（工单 12 真机 emoji 寡淡根因）。
        let linear = pow(sample.rgb, vec3<f32>(2.2));
        return vec4<f32>(linear, sample.a);
    }
    return vec4<f32>(input.color.rgb, input.color.a * sample.a);
}
"#;

// ---------- Renderer ----------

// ---------- 渲染线程（工单 12 ④） ----------

enum RenderCommand {
    Write(Vec<u8>),
    Resize(u16, u16),
    Scroll(isize),
    Selection(u32, u32, u32),
    SetFontSize(f32),
    SetPalette(Palette),
    ResetPalette,
    SelectionText(std::sync::mpsc::Sender<String>),
    CellSize(std::sync::mpsc::Sender<(u32, u32)>),
    Attach(*mut c_void, u32, u32),
    Detach,
    Render(u32, u32),
    ForceRender(u32, u32),
    TestPattern(u32, u32),
    Quit,
}

// 窗口指针只在渲染线程使用（attach 后 acquire、detach 时 release），跨线程仅传递。
unsafe impl Send for RenderCommand {}

#[derive(Default)]
pub struct RenderStats {
    pub backend: String,
    pub adapter: String,
    pub cols: u16,
    pub rows: u16,
    pub atlas_glyphs: usize,
    pub color_glyphs: usize,
    pub emoji_ready: bool,
    pub atlas_rev: u64,
    pub attaches: u64,
    pub presents: u64,
    pub terminal_frames: u64,
    pub acquire_success: u64,
    pub acquire_occluded: u64,
    pub acquire_timeout: u64,
    pub acquire_outdated: u64,
    pub acquire_validation: u64,
    pub draw_attempts: u64,
    pub calls: u64,
    pub builds: u64,
    pub full_builds: u64,
    pub incr_builds: u64,
    pub dirty_rows_last: usize,
    pub build_us_last: u128,
    pub row_uploads: u64,
    pub upload_bytes_last: usize,
    pub vertices_last: usize,
    pub mailbox_writes: u64,
    pub font_size_px: f32,
    pub palette_active: bool,
    pub last_error: String,
    pub alive: bool,
}

struct RendererCore {
    terminal: GhosttyTerminal,
    state: GhosttyRenderState,
    cols: u16,
    rows: u16,
    gpu: Option<GpuRuntime>,
    atlas: GlyphAtlas,
    last_signature: Option<u64>,
    last_meta: Option<(u16, u16, Option<(u16, u16)>, i32)>,
    overlays: Vec<OverlayRange>,
    row_hashes: Vec<u64>,
    row_store: Option<RowVertexStore>,
    force_full: bool,
    width_px: u32,
    height_px: u32,
    surface_window: *mut c_void,
    render_calls: u64,
    build_calls: u64,
    full_builds: u64,
    incr_builds: u64,
    last_dirty_rows: usize,
    last_build_us: u128,
    row_uploads: u64,
    last_upload_bytes: usize,
    last_vertex_count: usize,
    font_size_px: f32,
    palette: Option<Palette>,
    last_error: String,
}

unsafe impl Send for RendererCore {}

impl RendererCore {
    fn new(cols: u16, rows: u16) -> Option<Self> {
        let opts = GhosttyTerminalOptions {
            cols,
            rows,
            max_scrollback: 10000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        if !check(
            unsafe { ghostty_terminal_new(std::ptr::null(), &mut terminal, opts) },
            "terminal_new",
        ) {
            return None;
        }
        let mut state: GhosttyRenderState = std::ptr::null_mut();
        if !check(
            unsafe { ghostty_render_state_new(std::ptr::null(), &mut state) },
            "render_state_new",
        ) {
            unsafe { ghostty_terminal_free(terminal) };
            return None;
        }
        let atlas = match GlyphAtlas::new() {
            Some(atlas) => atlas,
            None => {
                log_error("no font loaded");
                unsafe {
                    ghostty_render_state_free(state);
                    ghostty_terminal_free(terminal);
                }
                return None;
            }
        };
        Some(Self {
            terminal,
            state,
            cols,
            rows,
            gpu: None,
            atlas,
            last_signature: None,
            last_meta: None,
            overlays: Vec::new(),
            row_hashes: Vec::new(),
            row_store: None,
            force_full: true,
            width_px: 0,
            height_px: 0,
            surface_window: std::ptr::null_mut(),
            render_calls: 0,
            build_calls: 0,
            full_builds: 0,
            incr_builds: 0,
            last_dirty_rows: 0,
            last_build_us: 0,
            row_uploads: 0,
            last_upload_bytes: 0,
            last_vertex_count: 0,
            font_size_px: DEFAULT_FONT_SIZE_PX,
            palette: None,
            last_error: String::new(),
        })
    }

    fn write(&mut self, data: &[u8]) {
        unsafe {
            ghostty_terminal_vt_write(self.terminal, data.as_ptr(), data.len());
        }
    }

    fn resize(&mut self, cols: u16, rows: u16) {
        self.cols = cols;
        self.rows = rows;
        self.force_full = true;
        unsafe {
            let _ = ghostty_terminal_resize(self.terminal, cols, rows, 0, 0);
        }
    }

    fn scroll(&mut self, delta: isize) {
        self.force_full = true;
        unsafe {
            ghostty_terminal_scroll_viewport(
                self.terminal,
                GhosttyTerminalScrollViewport {
                    tag: SCROLL_VIEWPORT_DELTA,
                    value: GhosttyTerminalScrollViewportValue { delta },
                },
            );
        }
    }

    fn set_selection(&mut self, row: u32, start_col: u32, end_col: u32) {
        self.overlays.retain(|range| range.row != row);
        if end_col > start_col {
            self.overlays.push(OverlayRange {
                row,
                start_col,
                end_col,
            });
        }
        self.last_signature = None;
        self.last_meta = None;
    }

    fn set_font_size(&mut self, size_px: f32) {
        let px = size_px.clamp(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX);
        if (px - self.font_size_px).abs() < f32::EPSILON {
            return;
        }
        self.font_size_px = px;
        self.atlas.set_pixels_per_em(px);
        self.force_full = true;
        self.last_signature = None;
        self.last_meta = None;
    }

    fn cell_size(&self) -> (u32, u32) {
        self.atlas.cell_size()
    }

    fn set_palette(&mut self, palette: Option<Palette>) {
        self.palette = palette;
        self.force_full = true;
        self.last_signature = None;
        self.last_meta = None;
    }

    /// 选中区域文本：所有 overlay 按行序拼接（跨行 '\n'）。
    /// 宽字符/emoji 以"占用列区间与选区相交"判定，右半边选中也取整个字符，
    /// 与核心格数据一致。
    fn selection_text(&self) -> String {
        // 渲染状态需先 update 才能反映已写入字节（渲染循环里 update 在渲染前）。
        unsafe {
            let _ = ghostty_render_state_update(self.state, self.terminal);
        }
        let snapshot = self.current_snapshot();
        let mut overlays: Vec<&OverlayRange> = self
            .overlays
            .iter()
            .filter(|overlay| (overlay.row as usize) < snapshot.lines.len())
            .collect();
        overlays.sort_by_key(|overlay| overlay.row);
        let mut out = String::new();
        let mut first = true;
        for overlay in overlays {
            if !first {
                out.push('\n');
            }
            first = false;
            let line = &snapshot.lines[overlay.row as usize];
            for (col, cell) in line.iter().enumerate() {
                let width = terminal_cell_width(cell.text.chars().next().unwrap_or(' ')).max(1);
                let start = col as u32;
                let end = start + width;
                if end <= overlay.start_col || start >= overlay.end_col {
                    continue;
                }
                out.push_str(&cell.text);
            }
        }
        out
    }

    fn clear_color(&self) -> [f32; 4] {
        self.palette
            .map(|palette| {
                [
                    palette.bg.r as f32 / 255.0,
                    palette.bg.g as f32 / 255.0,
                    palette.bg.b as f32 / 255.0,
                    1.0,
                ]
            })
            .unwrap_or([0.12, 0.12, 0.12, 1.0])
    }

    fn attach(&mut self, window: *mut c_void, width_px: u32, height_px: u32) -> Result<(), String> {
        let background = self.clear_color();
        self.last_signature = None;
        self.last_meta = None;
        self.force_full = true;
        if self.gpu.is_none() {
            self.gpu = Some(GpuRuntime::create()?);
        }
        let gpu = self.gpu.as_mut().unwrap();
        gpu.attach_surface(window, width_px, height_px)?;
        let _ = gpu.present_clear(background);
        self.surface_window = window;
        self.width_px = width_px;
        self.height_px = height_px;
        Ok(())
    }

    fn detach(&mut self) {
        if let Some(gpu) = self.gpu.as_mut() {
            gpu.detach_surface();
        }
        self.surface_window = std::ptr::null_mut();
        self.last_signature = None;
        self.last_meta = None;
    }

    /// 调试用：不管终端内容，强制画一整块红色，验证 GPU 管线与 surface 可见性。
    fn test_pattern(&mut self, _width_px: u32, _height_px: u32) -> bool {
        if self.gpu.is_none() {
            match GpuRuntime::create() {
                Ok(gpu) => self.gpu = Some(gpu),
                Err(error) => {
                    self.last_error = format!("gpu init failed: {error}");
                    return false;
                }
            }
        }
        let gpu = self.gpu.as_mut().unwrap();
        if gpu.surface.is_none() {
            self.last_error = "no native surface attached".to_string();
            return false;
        }
        if let Err(error) = gpu.upload_atlas(&self.atlas) {
            self.last_error = format!("atlas upload failed: {error}");
            return false;
        }

        let mut payload = Vec::new();
        let red = [1.0f32, 0.0, 0.0, 1.0];
        // 左半屏红色（solid）
        let red_verts = [
            Vertex::new([-1.0, -1.0], [0.0, 1.0], red, MODE_SOLID),
            Vertex::new([0.0, -1.0], [1.0, 1.0], red, MODE_SOLID),
            Vertex::new([-1.0, 1.0], [0.0, 0.0], red, MODE_SOLID),
            Vertex::new([0.0, -1.0], [1.0, 1.0], red, MODE_SOLID),
            Vertex::new([0.0, 1.0], [1.0, 0.0], red, MODE_SOLID),
            Vertex::new([-1.0, 1.0], [0.0, 0.0], red, MODE_SOLID),
        ];
        for vertex in &red_verts {
            vertex.write(&mut payload);
        }
        // 右半屏白色字形 'A'（glyph mode，验证图集采样）
        let white = [1.0f32, 1.0, 1.0, 1.0];
        if let Some(entry) = self.atlas.ensure_glyph('A') {
            let (atlas_w, atlas_h) = self.atlas.extent();
            let (uv_left, uv_right, uv_top, uv_bottom) =
                self.atlas.uv_for(&entry, atlas_w as f32, atlas_h as f32);
            let glyph_verts = [
                Vertex::new([0.05, -0.7], [uv_left, uv_bottom], white, MODE_GLYPH),
                Vertex::new([0.95, -0.7], [uv_right, uv_bottom], white, MODE_GLYPH),
                Vertex::new([0.05, 0.7], [uv_left, uv_top], white, MODE_GLYPH),
                Vertex::new([0.95, -0.7], [uv_right, uv_bottom], white, MODE_GLYPH),
                Vertex::new([0.95, 0.7], [uv_right, uv_top], white, MODE_GLYPH),
                Vertex::new([0.05, 0.7], [uv_left, uv_top], white, MODE_GLYPH),
            ];
            for vertex in &glyph_verts {
                vertex.write(&mut payload);
            }
        }
        let background = [0.12, 0.12, 0.12, 1.0];
        let vertex_count = payload.len() / VERTEX_STRIDE as usize;
        if let Err(error) = gpu.write_vertex_ranges(&[(0, payload.len())], &payload) {
            self.last_error = format!("test pattern upload failed: {error}");
            return false;
        }
        match gpu.render_terminal(
            &[(0, vertex_count as u32)],
            background,
            &self.atlas,
        ) {
            Ok(true) => true,
            Ok(false) => {
                self.last_error = "test pattern acquire failed".to_string();
                false
            }
            Err(error) => {
                self.last_error = format!("test pattern failed: {error}");
                false
            }
        }
    }

    fn render(&mut self, width_px: u32, height_px: u32) -> bool {
        self.render_calls = self.render_calls.saturating_add(1);
        self.last_error.clear();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            self.render_inner(width_px, height_px)
        }));
        match result {
            Ok(value) => {
                if !value {
                    let message = self.last_error.clone();
                    if !message.is_empty() {
                        log_error(&format!("render failed: {message}"));
                    }
                }
                value
            }
            Err(_) => {
                self.last_error = "panic in render".to_string();
                log_error("panic in render");
                false
            }
        }
    }

    fn run(
        mut self,
        rx: std::sync::mpsc::Receiver<RenderCommand>,
        stats: std::sync::Arc<std::sync::Mutex<RenderStats>>,
    ) {
        self.sync_stats(&stats);
        while let Ok(command) = rx.recv() {
            match command {
                RenderCommand::Write(data) => self.write(&data),
                RenderCommand::Resize(cols, rows) => self.resize(cols, rows),
                RenderCommand::Scroll(delta) => self.scroll(delta),
                RenderCommand::Selection(row, start, end) => {
                    self.set_selection(row, start, end)
                }
                RenderCommand::SetFontSize(size_px) => self.set_font_size(size_px),
                RenderCommand::SetPalette(palette) => self.set_palette(Some(palette)),
                RenderCommand::ResetPalette => self.set_palette(None),
                RenderCommand::SelectionText(tx) => {
                    let _ = tx.send(self.selection_text());
                }
                RenderCommand::CellSize(tx) => {
                    let _ = tx.send(self.cell_size());
                }
                RenderCommand::Attach(window, width, height) => {
                    if let Err(error) = self.attach(window, width, height) {
                        self.last_error = error;
                    }
                }
                RenderCommand::Detach => self.detach(),
                RenderCommand::Render(width, height) => {
                    self.render(width, height);
                }
                RenderCommand::ForceRender(width, height) => {
                    // surface 重建后首帧可能 present 到未上屏缓冲且签名被去重：
                    // 清签名强制重画一帧（工单 04 选择空白恢复）。
                    self.last_signature = None;
                    self.last_meta = None;
                    self.force_full = true;
                    self.render(width, height);
                }
                RenderCommand::TestPattern(width, height) => {
                    self.test_pattern(width, height);
                }
                RenderCommand::Quit => break,
            }
            self.sync_stats(&stats);
        }
        self.sync_stats(&stats);
        if let Ok(mut stats) = stats.lock() {
            stats.alive = false;
        }
    }

    fn sync_stats(&self, stats: &std::sync::Mutex<RenderStats>) {
        let mut stats = match stats.lock() {
            Ok(stats) => stats,
            Err(_) => return,
        };
        match &self.gpu {
            Some(gpu) => {
                stats.backend = gpu.backend.clone();
                stats.adapter = gpu.adapter_name.clone();
                stats.attaches = gpu.attach_count;
                stats.presents = gpu.present_count;
                stats.terminal_frames = gpu.terminal_present_count;
                stats.acquire_success = gpu.acquire_success;
                stats.acquire_occluded = gpu.acquire_occluded;
                stats.acquire_timeout = gpu.acquire_timeout;
                stats.acquire_outdated = gpu.acquire_outdated;
                stats.acquire_validation = gpu.acquire_validation;
                stats.draw_attempts = gpu.draw_attempts;
            }
            None => {}
        }
        stats.cols = self.cols;
        stats.rows = self.rows;
        stats.atlas_glyphs = self.atlas.entries.len();
        stats.color_glyphs = self.atlas.color_glyph_count();
        stats.emoji_ready = self.atlas.emoji_ready();
        stats.atlas_rev = self.atlas.revision();
        stats.calls = self.render_calls;
        stats.builds = self.build_calls;
        stats.full_builds = self.full_builds;
        stats.incr_builds = self.incr_builds;
        stats.dirty_rows_last = self.last_dirty_rows;
        stats.build_us_last = self.last_build_us;
        stats.row_uploads = self.row_uploads;
        stats.upload_bytes_last = self.last_upload_bytes;
        stats.vertices_last = self.last_vertex_count;
        stats.font_size_px = self.font_size_px;
        stats.palette_active = self.palette.is_some();
        stats.last_error = self.last_error.clone();
        stats.alive = true;
    }

    fn render_inner(&mut self, width_px: u32, height_px: u32) -> bool {
        let (actual_w, actual_h) = if self.surface_window.is_null() {
            (0, 0)
        } else {
            (
                unsafe { fable_anw_get_width(self.surface_window) },
                unsafe { fable_anw_get_height(self.surface_window) },
            )
        };
        let width_px = if actual_w > 0 {
            actual_w as u32
        } else {
            width_px
        };
        let height_px = if actual_h > 0 {
            actual_h as u32
        } else {
            height_px
        };
        unsafe {
            if !check(
                ghostty_render_state_update(self.state, self.terminal),
                "render_state_update",
            ) {
                self.last_error = "render_state_update failed".to_string();
                return false;
            }
        }

        // 廉价全局元数据（不遍历行）。
        let cursor_visible = unsafe { get_bool(self.state, DATA_CURSOR_VISIBLE) };
        let cursor = if cursor_visible {
            Some((
                unsafe { get_u16(self.state, DATA_CURSOR_VIEWPORT_X) },
                unsafe { get_u16(self.state, DATA_CURSOR_VIEWPORT_Y) },
            ))
        } else {
            None
        };
        let cursor_style = unsafe { get_i32(self.state, DATA_CURSOR_VISUAL_STYLE) };
        let dirty = unsafe { get_i32(self.state, DATA_DIRTY) };
        let meta = (self.cols, self.rows, cursor, cursor_style);
        let overlays_signature = {
            let mut hasher = DefaultHasher::new();
            self.overlays.len().hash(&mut hasher);
            for overlay in &self.overlays {
                overlay.hash(&mut hasher);
            }
            hasher.finish()
        };

        // 内容签名不变零重绘：不收集快照、不碰顶点。
        if dirty == DIRTY_FALSE
            && self.last_meta == Some(meta)
            && self.last_signature.is_some()
            && self
                .gpu
                .as_ref()
                .is_some_and(|gpu| gpu.present_count > 0)
        {
            return false;
        }

        let mut snapshot = self.current_snapshot();
        // 快照行数不足 rows（瞬态 reflow/缩放/滚动）：保留上一帧，避免整屏空白或越界。
        if snapshot.lines.len() < snapshot.rows as usize {
            self.last_error = format!(
                "incomplete snapshot: {} lines < {} rows",
                snapshot.lines.len(),
                snapshot.rows
            );
            log_error(&self.last_error);
            return false;
        }
        if let Some(palette) = self.palette {
            apply_palette(&mut snapshot, palette);
        }
        let dims_changed = self
            .row_store
            .as_ref()
            .is_none_or(|store| store.dims() != (snapshot.cols, snapshot.rows));
        let full_rebuild = self.force_full || dims_changed || dirty == DIRTY_FULL;
        let mut rebuild_rows: Vec<usize> = if full_rebuild {
            (0..snapshot.rows as usize).collect()
        } else {
            snapshot.dirty_rows.clone()
        };
        if self.row_hashes.len() != snapshot.rows as usize {
            self.row_hashes = vec![0u64; snapshot.rows as usize];
            rebuild_rows = (0..snapshot.rows as usize).collect();
        }
        for row in &rebuild_rows {
            if let Some(line) = snapshot.lines.get(*row) {
                self.row_hashes[*row] = hash_row(line);
            }
        }

        let signature = {
            let mut hasher = DefaultHasher::new();
            width_px.hash(&mut hasher);
            height_px.hash(&mut hasher);
            snapshot.cols.hash(&mut hasher);
            snapshot.rows.hash(&mut hasher);
            snapshot.cursor.hash(&mut hasher);
            snapshot.cursor_style.hash(&mut hasher);
            snapshot.default_fg.hash(&mut hasher);
            snapshot.default_bg.hash(&mut hasher);
            snapshot.cursor_color.hash(&mut hasher);
            snapshot.selection_color.hash(&mut hasher);
            snapshot.palette.hash(&mut hasher);
            overlays_signature.hash(&mut hasher);
            for row_hash in &self.row_hashes {
                row_hash.hash(&mut hasher);
            }
            hasher.finish()
        };
        if self.last_signature == Some(signature)
            && self
                .gpu
                .as_ref()
                .is_some_and(|gpu| gpu.present_count > 0)
        {
            return false;
        }

        if self.gpu.is_none() {
            match GpuRuntime::create() {
                Ok(gpu) => self.gpu = Some(gpu),
                Err(error) => {
                    log_error(&format!("gpu init failed: {error}"));
                    self.last_error = format!("gpu init failed: {error}");
                    return false;
                }
            }
        }
        // 清屏：push 配色板用其背景；未 push 维持现状深灰（调试兜底）。
        let background = self.clear_color();
        let gpu = self.gpu.as_mut().unwrap();
        if gpu.surface.is_none() {
            log_error("no native surface attached");
            self.last_error = "no native surface attached".to_string();
            return false;
        }

        if self.row_store.is_none() || self.row_store.as_ref().unwrap().dims() != (snapshot.cols, snapshot.rows)
        {
            self.row_store = Some(RowVertexStore::new(snapshot.cols, snapshot.rows));
            rebuild_rows = (0..snapshot.rows as usize).collect();
        }
        let store = self.row_store.as_mut().unwrap();
        let build_start = std::time::Instant::now();
        let mut upload_ranges = Vec::with_capacity(rebuild_rows.len() + 1);
        for row in &rebuild_rows {
            upload_ranges.push(store.rebuild_row(*row, &snapshot, &mut self.atlas, width_px, height_px));
        }
        let overlay_range = store.rebuild_overlays(&snapshot, &self.overlays, width_px, height_px);
        upload_ranges.push(overlay_range);
        let build_us = build_start.elapsed().as_micros();
        self.build_calls = self.build_calls.saturating_add(1);
        self.last_build_us = build_us;
        self.last_dirty_rows = rebuild_rows.len();
        self.last_upload_bytes = upload_ranges.iter().map(|(_, len)| len).sum();
        self.row_uploads = self.row_uploads.saturating_add(rebuild_rows.len() as u64);
        if rebuild_rows.len() as u64 >= snapshot.rows as u64 {
            self.full_builds = self.full_builds.saturating_add(1);
        } else {
            self.incr_builds = self.incr_builds.saturating_add(1);
        }
        if let Err(error) = gpu.upload_atlas(&self.atlas) {
            log_error(&format!("atlas upload failed: {error}"));
            self.last_error = format!("atlas upload failed: {error}");
            return false;
        }
        if let Err(error) = gpu.write_vertex_ranges(&upload_ranges, store.payload()) {
            log_error(&format!("vertex upload failed: {error}"));
            self.last_error = format!("vertex upload failed: {error}");
            return false;
        }
        let draw_ranges = store.draw_ranges();
        if draw_ranges.iter().all(|(_, count)| *count == 0)
            && self.last_vertex_count > 0
            && dirty == DIRTY_FALSE
        {
            // 内容未变却全帧无顶点（选择/滚动触发的异常帧）：保留上一帧，
            // 不画纯背景；正常清屏（dirty=FULL）仍放行。
            self.last_error = "empty frame (no vertices)".to_string();
            log_error(&self.last_error);
            return false;
        }
        // 选择空白候选路径：有选择 overlay 但正文零字形。这类帧直接拦下保留上一帧
        //（既防空白，也让 Java 侧能读到 last_error），正常清屏无 overlay 不受影响。
        if store.row_counts.iter().all(|count| *count == 0) && !self.overlays.is_empty() {
            self.last_error = format!(
                "zero-glyph frame with overlays: dirty={dirty} rows={} overlays={}",
                snapshot.rows,
                self.overlays.len()
            );
            log_error(&self.last_error);
            return false;
        }
        self.last_vertex_count = draw_ranges.iter().map(|(_, count)| *count as usize).sum();
        match gpu.render_terminal(
            &draw_ranges,
            background,
            &self.atlas,
        ) {
            Ok(true) => {
                self.last_signature = Some(signature);
                self.last_meta = Some(meta);
                self.force_full = false;
                self.width_px = width_px;
                self.height_px = height_px;
                self.reset_dirty();
                true
            }
            Ok(false) => {
                self.last_error = "acquire failed (see counters)".to_string();
                false
            }
            Err(error) => {
                log_error(&format!("render failed: {error}"));
                self.last_error = format!("render failed: {error}");
                false
            }
        }
    }

    fn current_snapshot(&self) -> Snapshot {
        let mut colors = GhosttyRenderStateColors {
            size: std::mem::size_of::<GhosttyRenderStateColors>(),
            background: GhosttyColorRgb { r: 0, g: 0, b: 0 },
            foreground: GhosttyColorRgb { r: 0, g: 0, b: 0 },
            cursor: GhosttyColorRgb { r: 0, g: 0, b: 0 },
            cursor_has_value: false,
            palette: [GhosttyColorRgb { r: 0, g: 0, b: 0 }; 256],
        };
        unsafe {
            let _ = ghostty_render_state_colors_get(self.state, &mut colors);
            collect_snapshot(self.state, &colors, self.palette.map(|p| p.ansi))
        }
    }

    /// 官方约定：渲染完成后调用方自行清除全局与逐行 dirty，
    /// 否则 update 永远保持 FULL，脏行增量失效。
    fn reset_dirty(&mut self) {
        let false_value = false;
        unsafe {
            let _ = ghostty_render_state_set(
                self.state,
                RENDER_STATE_OPTION_DIRTY,
                &false_value as *const bool as *const c_void,
            );
            let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
            if check(
                ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it),
                "reset_dirty row_iterator_new",
            ) {
                let _ = ghostty_render_state_get(
                    self.state,
                    DATA_ROW_ITERATOR,
                    &mut row_it as *mut _ as *mut c_void,
                );
                while ghostty_render_state_row_iterator_next(row_it) {
                    let _ = ghostty_render_state_row_set(
                        row_it,
                        ROW_OPTION_DIRTY,
                        &false_value as *const bool as *const c_void,
                    );
                }
                ghostty_render_state_row_iterator_free(row_it);
            }
        }
    }
}

pub struct Renderer {
    tx: Option<std::sync::mpsc::Sender<RenderCommand>>,
    join: Option<std::thread::JoinHandle<()>>,
    stats: std::sync::Arc<std::sync::Mutex<RenderStats>>,
}

impl Renderer {
    pub fn new(cols: u16, rows: u16) -> Option<Self> {
        init_wgpu_logger();
        crate::ffi::force_tls_pad();
        let core = RendererCore::new(cols, rows)?;
        let stats = std::sync::Arc::new(std::sync::Mutex::new(RenderStats::default()));
        let (tx, rx) = std::sync::mpsc::channel();
        let thread_stats = std::sync::Arc::clone(&stats);
        let join = std::thread::Builder::new()
            .name("fable-render-thread".to_string())
            .spawn(move || core.run(rx, thread_stats))
            .ok()?;
        Some(Self {
            tx: Some(tx),
            join: Some(join),
            stats,
        })
    }

    fn send(&self, command: RenderCommand) -> bool {
        let sent = self
            .tx
            .as_ref()
            .map(|tx| tx.send(command).is_ok())
            .unwrap_or(false);
        if sent {
            if let Ok(mut stats) = self.stats.lock() {
                stats.mailbox_writes = stats.mailbox_writes.saturating_add(1);
            }
        }
        sent
    }

    pub fn write(&self, data: &[u8]) {
        self.send(RenderCommand::Write(data.to_vec()));
    }

    pub fn resize(&self, cols: u16, rows: u16) {
        self.send(RenderCommand::Resize(cols, rows));
    }

    pub fn scroll(&self, delta: isize) {
        self.send(RenderCommand::Scroll(delta));
    }

    pub fn set_selection(&self, row: u32, start_col: u32, end_col: u32) {
        self.send(RenderCommand::Selection(row, start_col, end_col));
    }

    pub fn set_font_size(&self, size_px: f32) {
        self.send(RenderCommand::SetFontSize(size_px));
    }

    pub fn set_palette(&self, palette: Palette) {
        self.send(RenderCommand::SetPalette(palette));
    }

    pub fn reset_palette(&self) {
        self.send(RenderCommand::ResetPalette);
    }

    /// 同步查询：渲染线程返回当前选中区域文本（mailbox 保序，写先于查）。
    pub fn selection_text(&self) -> String {
        let (tx, rx) = std::sync::mpsc::channel();
        if self.send(RenderCommand::SelectionText(tx)) {
            rx.recv().unwrap_or_default()
        } else {
            String::new()
        }
    }

    /// 同步查询：当前字号的单元格像素尺寸 (width, height)。
    pub fn cell_size(&self) -> (u32, u32) {
        let (tx, rx) = std::sync::mpsc::channel();
        if self.send(RenderCommand::CellSize(tx)) {
            rx.recv().unwrap_or((0, 0))
        } else {
            (0, 0)
        }
    }

    pub fn attach(&self, window: *mut c_void, width_px: u32, height_px: u32) -> Result<(), String> {
        if self.send(RenderCommand::Attach(window, width_px, height_px)) {
            Ok(())
        } else {
            Err("render thread stopped".to_string())
        }
    }

    pub fn detach(&self) {
        self.send(RenderCommand::Detach);
    }

    pub fn test_pattern(&self, width_px: u32, height_px: u32) -> bool {
        self.send(RenderCommand::TestPattern(width_px, height_px))
    }

    pub fn render(&self, width_px: u32, height_px: u32) -> bool {
        self.send(RenderCommand::Render(width_px, height_px))
    }

    /// 强制重绘一帧（清除内容签名去重；surface 重建后首帧可能未上屏）。
    pub fn force_render(&self, width_px: u32, height_px: u32) -> bool {
        self.send(RenderCommand::ForceRender(width_px, height_px))
    }

    /// 最近一次渲染失败/跳帧原因（Java 侧经 JNI 读到后写诊断文件）。
    pub fn last_error(&self) -> String {
        let stats = match self.stats.lock() {
            Ok(stats) => stats,
            Err(_) => return "stats lock failed".to_string(),
        };
        stats.last_error.clone()
    }

    pub fn info(&self) -> String {
        let stats = match self.stats.lock() {
            Ok(stats) => stats,
            Err(_) => return "stats lock failed".to_string(),
        };
        format!(
            "backend={} adapter={} cols={} rows={} font_size={} palette={} atlas_glyphs={} color_glyphs={} emoji_font={} atlas_rev={} attaches={} presents={} terminal_frames={} acquire={}/{}/{}/{}/{} draw_attempts={} calls={} builds={} full={} incr={} dirty_rows={} build_us={} row_uploads={} upload_bytes={} vertices={} mailbox_writes={} thread_alive={} err={} wgpu_log={}",
            stats.backend,
            stats.adapter,
            stats.cols,
            stats.rows,
            stats.font_size_px,
            stats.palette_active,
            stats.atlas_glyphs,
            stats.color_glyphs,
            stats.emoji_ready,
            stats.atlas_rev,
            stats.attaches,
            stats.presents,
            stats.terminal_frames,
            stats.acquire_success,
            stats.acquire_occluded,
            stats.acquire_timeout,
            stats.acquire_outdated,
            stats.acquire_validation,
            stats.draw_attempts,
            stats.calls,
            stats.builds,
            stats.full_builds,
            stats.incr_builds,
            stats.dirty_rows_last,
            stats.build_us_last,
            stats.row_uploads,
            stats.upload_bytes_last,
            stats.vertices_last,
            stats.mailbox_writes,
            stats.alive,
            stats.last_error,
            recent_wgpu_log()
        )
    }
}

impl Drop for Renderer {
    fn drop(&mut self) {
        if let Some(tx) = self.tx.take() {
            let _ = tx.send(RenderCommand::Quit);
        }
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

impl Drop for RendererCore {
    fn drop(&mut self) {
        self.detach();
        unsafe {
            ghostty_render_state_free(self.state);
            ghostty_terminal_free(self.terminal);
        }
    }
}
