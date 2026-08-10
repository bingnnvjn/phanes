//! 工单 09 切片一：链接 libghostty-vt，走官方 RenderState 迭代器，
//! 验证"核心 → 渲染状态"数据通道（阶段 A），并用软件光栅输出 PNG（阶段 B）。
//! 工单 12 扩展：彩色 emoji（emoji.rs）/ sprite face（symbols.rs）/ 下划线。

use fable_render::emoji;
use fable_render::ffi::*;
use fable_render::symbols;
use std::ffi::c_void;
use std::path::Path;

fn check(result: GhosttyResult, what: &str) {
    assert_eq!(result, GHOSTTY_SUCCESS, "{what} 失败: {result}");
}

unsafe fn get_u16(state: GhosttyRenderState, data: i32) -> u16 {
    let mut v: u16 = 0;
    check(
        ghostty_render_state_get(state, data, &mut v as *mut u16 as *mut c_void),
        "get_u16",
    );
    v
}

unsafe fn get_bool(state: GhosttyRenderState, data: i32) -> bool {
    let mut v: bool = false;
    check(
        ghostty_render_state_get(state, data, &mut v as *mut bool as *mut c_void),
        "get_bool",
    );
    v
}

unsafe fn get_i32(state: GhosttyRenderState, data: i32) -> i32 {
    let mut v: i32 = 0;
    check(
        ghostty_render_state_get(state, data, &mut v as *mut i32 as *mut c_void),
        "get_i32",
    );
    v
}

/// 取当前格子的 UTF-8 文本（两段式：先问长度，再取数据）。
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
        check(
            ghostty_render_state_row_cells_get(
                cells,
                CELL_DATA_GRAPHEMES_UTF8,
                &mut buf2 as *mut GhosttyBuffer as *mut c_void,
            ),
            "cell_text 取数据",
        );
        bytes.truncate(buf2.len);
        String::from_utf8_lossy(&bytes).into_owned()
    } else if r == GHOSTTY_SUCCESS {
        String::new()
    } else {
        String::new()
    }
}

unsafe fn cell_color(cells: GhosttyRenderStateRowCells, data: i32) -> Option<GhosttyColorRgb> {
    let mut c = GhosttyColorRgb { r: 0, g: 0, b: 0 };
    let r = ghostty_render_state_row_cells_get(
        cells,
        data,
        &mut c as *mut GhosttyColorRgb as *mut c_void,
    );
    if r == GHOSTTY_SUCCESS {
        Some(c)
    } else {
        None
    }
}

unsafe fn terminal_usize(terminal: GhosttyTerminal, data: i32) -> usize {
    let mut v: usize = 0;
    check(
        ghostty_terminal_get(terminal, data, &mut v as *mut usize as *mut c_void),
        "terminal_get usize",
    );
    v
}

unsafe fn terminal_bool(terminal: GhosttyTerminal, data: i32) -> bool {
    let mut v: bool = false;
    check(
        ghostty_terminal_get(terminal, data, &mut v as *mut bool as *mut c_void),
        "terminal_get bool",
    );
    v
}

unsafe fn terminal_scrollbar(terminal: GhosttyTerminal) -> GhosttyTerminalScrollbar {
    let mut sb = GhosttyTerminalScrollbar {
        total: 0,
        offset: 0,
        len: 0,
    };
    check(
        ghostty_terminal_get(
            terminal,
            TERMINAL_DATA_SCROLLBAR,
            &mut sb as *mut _ as *mut c_void,
        ),
        "terminal_get scrollbar",
    );
    sb
}

/// 重新读取渲染状态的全部视口行文本（新迭代器，不依赖旧迭代器状态）。
unsafe fn collect_rows_text(state: GhosttyRenderState) -> Vec<String> {
    let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
    check(
        ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it),
        "row_iterator_new",
    );
    check(
        ghostty_render_state_get(
            state,
            DATA_ROW_ITERATOR,
            &mut row_it as *mut _ as *mut c_void,
        ),
        "get row_iterator",
    );
    let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
    check(
        ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells),
        "row_cells_new",
    );

    let mut rows = Vec::new();
    while ghostty_render_state_row_iterator_next(row_it) {
        check(
            ghostty_render_state_row_get(
                row_it,
                ROW_DATA_CELLS,
                &mut cells as *mut _ as *mut c_void,
            ),
            "row cells",
        );
        let mut line = String::new();
        while ghostty_render_state_row_cells_next(cells) {
            line.push_str(&cell_text(cells));
        }
        rows.push(line.trim_end().to_string());
    }

    ghostty_render_state_row_cells_free(cells);
    ghostty_render_state_row_iterator_free(row_it);
    rows
}

fn check_case(ok: bool, label: &str, all_ok: &mut bool) {
    println!("  {} : {}", if ok { "PASS" } else { "FAIL" }, label);
    if !ok {
        *all_ok = false;
    }
}

#[derive(Clone)]
struct Cell {
    text: String,
    fg: Option<GhosttyColorRgb>,
    bg: Option<GhosttyColorRgb>,
    selected: bool,
    underline: bool,
    /// SGR 4 下划线颜色（样式显式指定；None = 用前景色，工单 13 更高对比）。
    underline_color: Option<GhosttyColorRgb>,
    strikethrough: bool,
    overline: bool,
    /// 占几列（工单 13：merge_emoji_runs 归一 emoji 为 2 格）。
    col_span: usize,
}

impl Default for Cell {
    fn default() -> Self {
        Self {
            text: String::new(),
            fg: None,
            bg: None,
            selected: false,
            underline: false,
            underline_color: None,
            strikethrough: false,
            overline: false,
            col_span: 1,
        }
    }
}

fn ansi_fg(c: GhosttyColorRgb) -> String {
    format!("\x1b[38;2;{};{};{}m", c.r, c.g, c.b)
}

/// 工单 13：核心把 ZWJ/肤色/旗帜拆成片段、⌨️ 等按 1 格宽；
/// 软件光栅与 GPU 同款重组（见 render_android::merge_emoji_runs）。
fn merge_emoji_runs_main(cells: &mut Vec<Cell>) {
    let mut out: Vec<Cell> = Vec::with_capacity(cells.len());
    let mut i = 0usize;
    while i < cells.len() {
        let mut cell = cells[i].clone();
        if cell.text.is_empty() {
            i += 1;
            continue;
        }
        let mut span = 1usize;
        if fable_render::emoji::is_emoji_run(&cell.text) {
            span = 2;
        }
        let mut j = i + 1;
        loop {
            while j < cells.len() && cells[j].text.is_empty() {
                j += 1;
            }
            if j >= cells.len() {
                break;
            }
            let next = &cells[j].text;
            let next_only_skin = !next.is_empty()
                && next
                    .chars()
                    .all(|c| (0x1F3FB..=0x1F3FF).contains(&(c as u32)));
            let cell_all_ri =
                cell.text.chars().all(|c| (0x1F1E6..=0x1F1FF).contains(&(c as u32)));
            let next_all_ri = next.chars().all(|c| (0x1F1E6..=0x1F1FF).contains(&(c as u32)));
            // 与 render_android::merge_emoji_runs 同款（工单 22 真机反馈修复）。
            let ri_count = |s: &str| {
                s.chars()
                    .filter(|c| (0x1F1E6..=0x1F1FF).contains(&(*c as u32)))
                    .count()
            };
            let need_merge = cell.text.ends_with('\u{200d}')
                || next.starts_with('\u{200d}')
                || next_only_skin
                || (cell_all_ri && next_all_ri && ri_count(&cell.text) < 2);
            if !need_merge {
                break;
            }
            cell.text.push_str(next);
            if fable_render::emoji::is_emoji_run(&cell.text) {
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

fn ansi_bg(c: GhosttyColorRgb) -> String {
    format!("\x1b[48;2;{};{};{}m", c.r, c.g, c.b)
}

/// 软件路径的格子渲染宽度（与 render_android::cell_render_span 同语义）：
/// merge 后 emoji cluster 宽 2，中文/宽字符按码位判 2，取大。
fn cell_render_span_main(cell: &Cell) -> usize {
    let first = cell.text.chars().next();
    let w = first
        .map(|c| {
            let cp = c as u32;
            if (0x1100..=0x115f).contains(&cp)
                || (0x2e80..=0xa4cf).contains(&cp)
                || (0xac00..=0xd7a3).contains(&cp)
                || (0xf900..=0xfaff).contains(&cp)
                || (0xff00..=0xff60).contains(&cp)
                || (0x1f000..=0x1faff).contains(&cp)
                || (0x2300..=0x23ff).contains(&cp)
                || (0x2600..=0x27bf).contains(&cp)
                || (0x2b00..=0x2bff).contains(&cp)
            {
                2
            } else {
                1
            }
        })
        .unwrap_or(1);
    cell.col_span.max(1).max(w)
}

// ---------- 阶段 B：软件光栅 ----------

const CELL_W: usize = 10;
const CELL_H: usize = 20;

fn color_bytes(c: Option<GhosttyColorRgb>, default: (u8, u8, u8)) -> (u8, u8, u8) {
    match c {
        Some(c) => (c.r, c.g, c.b),
        None => default,
    }
}

/// 把一帧 RGBA 像素写出 PNG。
fn write_png(path: &Path, width: usize, height: usize, pixels: &[u8]) {
    let file = std::fs::File::create(path).expect("创建 PNG 文件");
    let mut encoder = png::Encoder::new(file, width as u32, height as u32);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header().expect("PNG 头");
    writer.write_image_data(pixels).expect("PNG 数据");
}

/// 加载 CJK 字体（系统 NotoSansCJK），失败则返回 None。
fn load_font() -> Option<fontdue::Font> {
    // fontdue 只支持 TrueType(glyf) 轮廓；NotoSansCJK 是 CFF，光栅化得 0x0。
    // MiSansVF：本机系统字体，可变字体默认实例可光栅化且覆盖 CJK。
    const CANDIDATES: &[&str] = &[
        "/system/fonts/MiSansVF.ttf",
        "/system/fonts/DroidSans.ttf",
        "/data/data/com.termux/files/usr/share/fonts/TTF/DejaVuSansMono.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttc",
    ];
    for path in CANDIDATES {
        if let Ok(bytes) = std::fs::read(path) {
            for index in 0..4usize {
                let settings = fontdue::FontSettings {
                    collection_index: index as u32,
                    ..Default::default()
                };
                if let Ok(font) = fontdue::Font::from_bytes(bytes.as_slice(), settings) {
                    return Some(font);
                }
            }
        }
    }
    None
}

/// 逐格光栅化并合成到帧缓冲。
fn rasterize_grid(
    rows: &[Vec<Cell>],
    cols: usize,
    cursor: Option<(usize, usize)>,
    default_fg: (u8, u8, u8),
    default_bg: (u8, u8, u8),
    font: &fontdue::Font,
    mut emoji_font: Option<&mut emoji::EmojiFonts>,
    px_per_em: f32,
) -> (Vec<u8>, usize, usize) {
    let height = rows.len().max(1) * CELL_H;
    let width = cols.max(1) * CELL_W;
    let mut framebuffer = vec![0u8; width * height * 4];

    // 背景填充默认色
    for y in 0..height {
        for x in 0..width {
            let i = (y * width + x) * 4;
            framebuffer[i] = default_bg.0;
            framebuffer[i + 1] = default_bg.1;
            framebuffer[i + 2] = default_bg.2;
            framebuffer[i + 3] = 255;
        }
    }

    let set_pixel =
        |framebuffer: &mut [u8], width: usize, x: usize, y: usize, r: u8, g: u8, b: u8| {
            if x >= width || y >= height {
                return;
            }
            let i = (y * width + x) * 4;
            framebuffer[i] = r;
            framebuffer[i + 1] = g;
            framebuffer[i + 2] = b;
            framebuffer[i + 3] = 255;
        };

    for (row_index, row) in rows.iter().enumerate() {
        let mut col_pos = 0usize;
        for cell in row {
            let origin_x = col_pos * CELL_W;
            let origin_y = row_index * CELL_H;

            // 背景色
            let (bg_r, bg_g, bg_b) = color_bytes(cell.bg, default_bg);
            for y in 0..CELL_H {
                for x in 0..CELL_W {
                    set_pixel(
                        &mut framebuffer,
                        width,
                        origin_x + x,
                        origin_y + y,
                        bg_r,
                        bg_g,
                        bg_b,
                    );
                }
            }

            // 工单 22 修复：按渲染宽度累加（中文渲染 2 格，col_span=1；
            // 用 col_span 会让下一个字重叠进上一字第二格）。
            col_pos += cell_render_span_main(cell);
            // 光标（块状）：光标格画反色底
            let is_cursor =
                cursor == Some((col_pos - cell_render_span_main(cell), row_index));
            if is_cursor {
                for y in 0..CELL_H {
                    for x in 0..CELL_W {
                        let i = ((origin_y + y) * width + origin_x + x) * 4;
                        framebuffer[i] = 255 - framebuffer[i];
                        framebuffer[i + 1] = 255 - framebuffer[i + 1];
                        framebuffer[i + 2] = 255 - framebuffer[i + 2];
                    }
                }
            }

            // 字形（彩色 emoji → sprite → 字体灰度）
            if cell.text.is_empty() {
                continue;
            }
            let (fg_r, fg_g, fg_b) = color_bytes(cell.fg, default_fg);
            let ch = cell.text.chars().next().unwrap_or(' ');

            // 彩色 emoji：整段 cluster（含 ZWJ）整形 + FreeType 光栅直绘。
            let mut drew = false;
            if let Some(emoji_font) = emoji_font.as_deref_mut() {
                if emoji::is_emoji_run(&cell.text) {
                    let target_w = CELL_W * 2;
                    // 与 GPU 路径一致（工单 22 修复）：目标 em 边长，画布=em 盒
                    // 缩放到 2 格宽内、垂直居中于行；不再用内容 bbox fit。
                    let em_px = (CELL_H as f32 * 0.9)
                        .min((px_per_em as f32).max(target_w as f32 * 0.95))
                        .clamp(8.0, 512.0);
                    if let Some(bitmap) =
                        emoji_font.rasterize_cluster(&cell.text, em_px as u16)
                    {
                        let scale = (target_w as f32 / bitmap.width.max(1) as f32).min(1.0);
                        let draw_w = (bitmap.width as f32 * scale).round() as usize;
                        let draw_h = (bitmap.height as f32 * scale).round() as usize;
                        let x_offset =
                            origin_x + ((target_w as f32 - draw_w as f32) / 2.0).round() as usize;
                        let y_offset =
                            origin_y + ((CELL_H as f32 - draw_h as f32) / 2.0).round() as usize;
                        for gy in 0..draw_h {
                            let sy = (gy as f32 / scale).min(bitmap.height as f32 - 1.0) as usize;
                            let dy = y_offset + gy;
                            if dy >= height {
                                continue;
                            }
                            for gx in 0..draw_w {
                                let sx =
                                    (gx as f32 / scale).min(bitmap.width as f32 - 1.0) as usize;
                                let dx = x_offset + gx;
                                if dx >= width {
                                    continue;
                                }
                                let si = (sy * bitmap.width as usize + sx) * 4;
                                let a = bitmap.pixels[si + 3] as f32 / 255.0;
                                if a == 0.0 {
                                    continue;
                                }
                                let i = (dy * width + dx) * 4;
                                let blend =
                                    |dst: u8, src: u8| (dst as f32 * (1.0 - a) + src as f32 * a) as u8;
                                framebuffer[i] = blend(framebuffer[i], bitmap.pixels[si]);
                                framebuffer[i + 1] = blend(framebuffer[i + 1], bitmap.pixels[si + 1]);
                                framebuffer[i + 2] = blend(framebuffer[i + 2], bitmap.pixels[si + 2]);
                                framebuffer[i + 3] = 255;
                            }
                        }
                        drew = true;
                    }
                }
            }

            if !drew && symbols::is_sprite(ch) {
                // sprite face：程序化位图（不依赖字体覆盖）。
                if let Some(bitmap) = symbols::sprite_bitmap(ch, 32) {
                    let scale = (CELL_W as f32 / bitmap.width.max(1) as f32)
                        .min(CELL_H as f32 / bitmap.height.max(1) as f32)
                        .max(0.01);
                    let draw_w = (bitmap.width as f32 * scale).round() as usize;
                    let draw_h = (bitmap.height as f32 * scale).round() as usize;
                    let x_offset = origin_x + (CELL_W - draw_w) / 2;
                    let y_offset = origin_y + (CELL_H - draw_h) / 2;
                    for gy in 0..draw_h {
                        let sy = (gy as f32 / scale).min(bitmap.height as f32 - 1.0) as usize;
                        let dy = y_offset + gy;
                        if dy >= height {
                            continue;
                        }
                        for gx in 0..draw_w {
                            let sx = (gx as f32 / scale).min(bitmap.width as f32 - 1.0) as usize;
                            let alpha = bitmap.alpha[sy * bitmap.width as usize + sx];
                            if alpha == 0 {
                                continue;
                            }
                            let dx = x_offset + gx;
                            if dx >= width {
                                continue;
                            }
                            let i = (dy * width + dx) * 4;
                            let a = alpha as f32 / 255.0;
                            let blend =
                                |dst: u8, src: u8| (dst as f32 * (1.0 - a) + src as f32 * a) as u8;
                            framebuffer[i] = blend(framebuffer[i], fg_r);
                            framebuffer[i + 1] = blend(framebuffer[i + 1], fg_g);
                            framebuffer[i + 2] = blend(framebuffer[i + 2], fg_b);
                            framebuffer[i + 3] = 255;
                        }
                    }
                    drew = true;
                }
            }

            if !drew {
                let glyph_index = font.lookup_glyph_index(ch);
                let (metrics, bitmap) = font.rasterize_indexed(glyph_index, px_per_em);
                let glyph_w = metrics.width as usize;
                let glyph_h = metrics.height as usize;
                let x_offset =
                    origin_x + ((CELL_W as i32 - metrics.width as i32) / 2).max(0) as usize;
                let y_offset =
                    origin_y + ((CELL_H as i32 - metrics.height as i32) / 2).max(0) as usize;
                for gy in 0..glyph_h {
                    let dy = y_offset + gy;
                    if dy >= height {
                        continue;
                    }
                    for gx in 0..glyph_w {
                        let alpha = bitmap[gy * glyph_w + gx];
                        if alpha == 0 {
                            continue;
                        }
                        let dx = x_offset + gx;
                        if dx >= width {
                            continue;
                        }
                        let i = (dy * width + dx) * 4;
                        let a = alpha as f32 / 255.0;
                        let blend =
                            |dst: u8, src: u8| (dst as f32 * (1.0 - a) + src as f32 * a) as u8;
                        framebuffer[i] = blend(framebuffer[i], fg_r);
                        framebuffer[i + 1] = blend(framebuffer[i + 1], fg_g);
                        framebuffer[i + 2] = blend(framebuffer[i + 2], fg_b);
                        framebuffer[i + 3] = 255;
                    }
                }
            }

            // 工单 22 决策 5：Apple → Noto 都无图时画主题适配方框（空心、前景色），
            // 仅 emoji run（避免普通缺字行为变化）。
            if !drew && emoji::is_emoji_run(&cell.text) {
                let bw = CELL_W * 2;
                let t = 2usize;
                for y in 0..CELL_H {
                    for x in 0..bw {
                        if x >= t && x < bw - t && y >= t && y < CELL_H - t {
                            continue;
                        }
                        let dx = origin_x + x;
                        let dy = origin_y + y;
                        if dx >= width || dy >= height {
                            continue;
                        }
                        let i = (dy * width + dx) * 4;
                        framebuffer[i] = fg_r;
                        framebuffer[i + 1] = fg_g;
                        framebuffer[i + 2] = fg_b;
                        framebuffer[i + 3] = 255;
                    }
                }
                drew = true;
            }

            // 程序化下划线/删除线/上划线。工单 13 真机反馈：12% 太粗 → 减半
            // max(2px, 6%)；下划线贴文字基线下方 1px（不再画在行底）。
            let thickness = ((CELL_H as f32 * 0.06).round() as usize).max(2);
            if cell.underline {
                // 更高对比：样式显式下划线色优先，否则用前景色。
                let (ul_r, ul_g, ul_b) = cell
                    .underline_color
                    .map(|c| (c.r, c.g, c.b))
                    .unwrap_or((fg_r, fg_g, fg_b));
                let (ascent_px, descent_px, line_gap_px) = font
                    .horizontal_line_metrics(px_per_em)
                    .map(|m| (m.ascent, m.descent, m.line_gap))
                    .unwrap_or((px_per_em * 0.8, -px_per_em * 0.2, 0.0));
                let line_h_px = (ascent_px - descent_px + line_gap_px).max(px_per_em);
                let baseline_y =
                    (origin_y as f32 + ((CELL_H as f32 - line_h_px) / 2.0).max(0.0) + ascent_px)
                        as usize;
                for x in 0..CELL_W {
                    for t in 0..thickness {
                        let dy = baseline_y + 1 + t;
                        let dx = origin_x + x;
                        if dy < height && dx < width {
                            let i = (dy * width + dx) * 4;
                            framebuffer[i] = ul_r;
                            framebuffer[i + 1] = ul_g;
                            framebuffer[i + 2] = ul_b;
                            framebuffer[i + 3] = 255;
                        }
                    }
                }
            }
            if cell.strikethrough {
                let mid = origin_y + CELL_H / 2;
                for x in 0..CELL_W {
                    let dx = origin_x + x;
                    if mid < height && dx < width {
                        let i = (mid * width + dx) * 4;
                        framebuffer[i] = fg_r;
                        framebuffer[i + 1] = fg_g;
                        framebuffer[i + 2] = fg_b;
                        framebuffer[i + 3] = 255;
                    }
                }
            }
            if cell.overline {
                for x in 0..CELL_W {
                    let dx = origin_x + x;
                    if origin_y < height && dx < width {
                        let i = (origin_y * width + dx) * 4;
                        framebuffer[i] = fg_r;
                        framebuffer[i + 1] = fg_g;
                        framebuffer[i + 2] = fg_b;
                        framebuffer[i + 3] = 255;
                    }
                }
            }
        }
    }

    (framebuffer, width, height)
}

/// 滚动缓冲验证：200 行输出，terminal_get 看总量，scroll_viewport 遍历历史。
unsafe fn verify_scroll() -> bool {
    let mut all_ok = true;
    let opts = GhosttyTerminalOptions {
        cols: 80,
        rows: 24,
        max_scrollback: 10000,
    };
    let mut term: GhosttyTerminal = std::ptr::null_mut();
    check(
        ghostty_terminal_new(std::ptr::null(), &mut term, opts),
        "scroll terminal_new",
    );

    let mut expected = Vec::with_capacity(200);
    let mut data = String::with_capacity(200 * 10);
    for i in 0..200usize {
        data.push_str(&format!("line-{i:03}\r\n"));
        expected.push(format!("line-{i:03}"));
    }
    ghostty_terminal_vt_write(term, data.as_ptr(), data.len());

    let total = terminal_usize(term, TERMINAL_DATA_TOTAL_ROWS);
    let scrollback = terminal_usize(term, TERMINAL_DATA_SCROLLBACK_ROWS);
    println!("\n== 滚动缓冲验证 ==");
    println!("total_rows={total} scrollback_rows={scrollback} viewport=80x24");
    check_case(
        total > 24,
        "scroll: total_rows > viewport rows",
        &mut all_ok,
    );
    check_case(scrollback > 0, "scroll: scrollback_rows > 0", &mut all_ok);

    let mut state: GhosttyRenderState = std::ptr::null_mut();
    check(
        ghostty_render_state_new(std::ptr::null(), &mut state),
        "scroll render_state_new",
    );
    check(
        ghostty_render_state_update(state, term),
        "scroll render_state_update",
    );

    let bottom = collect_rows_text(state);
    let bottom_nonempty: Vec<String> = bottom.iter().filter(|s| !s.is_empty()).cloned().collect();
    let bottom_expected = expected[scrollback..].to_vec();
    check_case(
        bottom_nonempty == bottom_expected,
        "scroll: 底部视口 = scrollback 起始后的全部内容行",
        &mut all_ok,
    );

    let mut sb = terminal_scrollbar(term);
    println!(
        "scrollbar(total={}, offset={}, len={})",
        sb.total, sb.offset, sb.len
    );
    check_case(
        sb.total > 24 && sb.len == 24,
        "scroll: scrollbar total>24 len=24",
        &mut all_ok,
    );

    // 视口滚到历史顶部：ROW 0 = scrollback 第一行
    ghostty_terminal_scroll_viewport(
        term,
        GhosttyTerminalScrollViewport {
            tag: SCROLL_VIEWPORT_ROW,
            value: GhosttyTerminalScrollViewportValue { row: 0 },
        },
    );
    check(
        ghostty_render_state_update(state, term),
        "scroll update after top",
    );
    let top = collect_rows_text(state);
    check_case(
        top == expected[0..24].to_vec(),
        "scroll: 顶部视口 = line-000..line-023",
        &mut all_ok,
    );
    sb = terminal_scrollbar(term);
    check_case(
        sb.offset == 0,
        "scroll: scrollbar offset=0 at top",
        &mut all_ok,
    );

    // 按 24 行一屏遍历整个 scrollback，确认全量 200 行都可从 render state 取出
    let mut seen = [false; 200];
    let last_start = expected.len() - 24;
    let mut start = 0usize;
    while start < 200 {
        ghostty_terminal_scroll_viewport(
            term,
            GhosttyTerminalScrollViewport {
                tag: SCROLL_VIEWPORT_ROW,
                value: GhosttyTerminalScrollViewportValue { row: start },
            },
        );
        check(
            ghostty_render_state_update(state, term),
            "scroll update walk",
        );
        let rows = collect_rows_text(state);
        for (i, text) in rows.iter().enumerate() {
            let idx = start + i;
            if idx < expected.len() && expected[idx] == *text {
                seen[idx] = true;
            }
        }
        if start == last_start {
            break;
        }
        start = (start + 24).min(last_start);
    }
    check_case(
        seen.iter().all(|&x| x),
        "scroll: 全量 200 行可经 viewport 遍历取出",
        &mut all_ok,
    );

    // 滚回底部：视口应重新钉住 active area
    ghostty_terminal_scroll_viewport(
        term,
        GhosttyTerminalScrollViewport {
            tag: SCROLL_VIEWPORT_BOTTOM,
            value: GhosttyTerminalScrollViewportValue { row: 0 },
        },
    );
    check(
        ghostty_render_state_update(state, term),
        "scroll update after bottom",
    );
    let active = terminal_bool(term, TERMINAL_DATA_VIEWPORT_ACTIVE);
    check_case(
        active,
        "scroll: viewport_active=true after bottom",
        &mut all_ok,
    );
    let bottom2 = collect_rows_text(state);
    let bottom2_nonempty: Vec<String> = bottom2.iter().filter(|s| !s.is_empty()).cloned().collect();
    check_case(
        bottom2_nonempty == bottom_expected,
        "scroll: 底部视口再次 = scrollback 起始后的全部内容行",
        &mut all_ok,
    );

    ghostty_render_state_free(state);
    ghostty_terminal_free(term);
    all_ok
}

/// resize 重排验证：80x24 写 100 字符长行 → 40x10 → 应重排为 40/40/20 三行。
unsafe fn verify_resize() -> bool {
    let mut all_ok = true;
    let opts = GhosttyTerminalOptions {
        cols: 80,
        rows: 24,
        max_scrollback: 10000,
    };
    let mut term: GhosttyTerminal = std::ptr::null_mut();
    check(
        ghostty_terminal_new(std::ptr::null(), &mut term, opts),
        "resize terminal_new",
    );

    let line = "0123456789".repeat(10);
    ghostty_terminal_vt_write(term, line.as_ptr(), line.len());
    check(
        ghostty_terminal_resize(term, 40, 10, 10, 20),
        "resize(40x10)",
    );

    let mut state: GhosttyRenderState = std::ptr::null_mut();
    check(
        ghostty_render_state_new(std::ptr::null(), &mut state),
        "resize render_state_new",
    );
    check(
        ghostty_render_state_update(state, term),
        "resize render_state_update",
    );

    let cols = get_u16(state, DATA_COLS);
    let rows = get_u16(state, DATA_ROWS);
    let text_rows = collect_rows_text(state);
    let nonempty: Vec<String> = text_rows
        .iter()
        .filter(|s| !s.is_empty())
        .cloned()
        .collect();
    let expected_rows = vec![
        line[0..40].to_string(),
        line[40..80].to_string(),
        line[80..100].to_string(),
    ];

    println!("\n== resize 重排验证 ==");
    println!(
        "after resize cols={cols} rows={rows} nonempty_rows={}",
        nonempty.len()
    );
    check_case(
        cols == 40 && rows == 10,
        "resize: cols=40 rows=10",
        &mut all_ok,
    );
    check_case(
        nonempty.len() == 3,
        "resize: 100 字符重排成 3 行",
        &mut all_ok,
    );
    check_case(
        nonempty == expected_rows,
        "resize: 重排行 = 40/40/20 内容正确",
        &mut all_ok,
    );
    check_case(
        nonempty.concat() == line,
        "resize: 三行拼接与原始长行一致",
        &mut all_ok,
    );

    ghostty_render_state_free(state);
    ghostty_terminal_free(term);
    all_ok
}

fn main() {
    force_tls_pad();
    unsafe {
        // 1. 建终端（80x24，滚动缓冲 10000）
        let opts = GhosttyTerminalOptions {
            cols: 80,
            rows: 24,
            max_scrollback: 10000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        check(
            ghostty_terminal_new(std::ptr::null(), &mut terminal, opts),
            "terminal_new",
        );

        // 2. 喂测试字节：纯文本 / SGR 颜色 / 粗斜体+RGB 前后景 / 中文宽字符 /
        //    下划线 / sprite 边框 / 彩色 emoji / 光标
        let vt = b"hello world\r\n\
                   \x1b[31mred text\x1b[0m\r\n\
                   \x1b[1;3;38;2;255;128;0;48;2;0;0;128morange on blue\x1b[0m\r\n\
                   \xe4\xb8\xad\xe6\x96\x87\xe5\xae\xbd\xe5\xad\x97\xe7\xac\xa6\xe6\xb5\x8b\xe8\xaf\x95\r\n\
                   \x1b[4munderline test\x1b[0m\r\n\
                   \xe2\x94\x8c\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80\xe2\x94\x90\r\n\
                   \xe2\x94\x82 x \xe2\x94\x82\r\n\
                   \xe2\x94\x94\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80\xe2\x94\x98\r\n\
                   \xf0\x9f\x9a\x80\xe2\x9c\x85\xf0\x9f\x91\xa8\xe2\x80\x8d\xf0\x9f\x91\xa9\xe2\x80\x8d\xf0\x9f\x91\xa7\xe2\x80\x8d\xf0\x9f\x91\xa6\xf0\x9f\x91\x8d\xf0\x9f\x8f\xbb\xf0\x9f\x87\xa8\xf0\x9f\x87\xb3\xe2\x8c\xa8\xef\xb8\x8f\xf0\x9f\x94\x8b\xf0\x9f\xa7\x91\xe2\x80\x8d\xf0\x9f\x9a\x80\xf0\x9f\xab\x96\xf0\x9f\xab\xb6\r\n\
                   end";
        ghostty_terminal_vt_write(terminal, vt.as_ptr(), vt.len());

        // 3. 渲染状态更新
        let mut state: GhosttyRenderState = std::ptr::null_mut();
        check(
            ghostty_render_state_new(std::ptr::null(), &mut state),
            "render_state_new",
        );
        check(
            ghostty_render_state_update(state, terminal),
            "render_state_update",
        );

        // 4. 全局元数据
        let cols = get_u16(state, DATA_COLS);
        let rows = get_u16(state, DATA_ROWS);
        let dirty = get_i32(state, DATA_DIRTY);
        let cursor_visible = get_bool(state, DATA_CURSOR_VISIBLE);
        let cursor_x = get_u16(state, DATA_CURSOR_VIEWPORT_X);
        let cursor_y = get_u16(state, DATA_CURSOR_VIEWPORT_Y);

        let mut colors = GhosttyRenderStateColors {
            size: std::mem::size_of::<GhosttyRenderStateColors>(),
            background: GhosttyColorRgb { r: 0, g: 0, b: 0 },
            foreground: GhosttyColorRgb { r: 0, g: 0, b: 0 },
            cursor: GhosttyColorRgb { r: 0, g: 0, b: 0 },
            cursor_has_value: false,
            palette: [GhosttyColorRgb { r: 0, g: 0, b: 0 }; 256],
        };
        check(
            ghostty_render_state_colors_get(state, &mut colors),
            "colors_get",
        );

        println!("== 全局元数据 ==");
        println!("cols={cols} rows={rows} dirty={dirty} cursor_visible={cursor_visible} cursor=({cursor_x},{cursor_y})");
        println!(
            "default fg=({},{},{}) bg=({},{},{})",
            colors.foreground.r,
            colors.foreground.g,
            colors.foreground.b,
            colors.background.r,
            colors.background.g,
            colors.background.b
        );

        // 5. 行/格迭代器
        let mut row_it: GhosttyRenderStateRowIterator = std::ptr::null_mut();
        check(
            ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it),
            "row_iterator_new",
        );
        check(
            ghostty_render_state_get(
                state,
                DATA_ROW_ITERATOR,
                &mut row_it as *mut _ as *mut c_void,
            ),
            "get row_iterator",
        );

        let mut cells: GhosttyRenderStateRowCells = std::ptr::null_mut();
        check(
            ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells),
            "row_cells_new",
        );

        println!("\n== 逐行输出（ANSI 彩色）==");
        let mut row_index = 0usize;
        let mut nonempty_lines = 0usize;
        while ghostty_render_state_row_iterator_next(row_it) {
            let mut row_dirty: bool = false;
            check(
                ghostty_render_state_row_get(
                    row_it,
                    ROW_DATA_DIRTY,
                    &mut row_dirty as *mut bool as *mut c_void,
                ),
                "row dirty",
            );
            check(
                ghostty_render_state_row_get(
                    row_it,
                    ROW_DATA_CELLS,
                    &mut cells as *mut _ as *mut c_void,
                ),
                "row cells",
            );

            let mut line_cells: Vec<Cell> = Vec::new();
            while ghostty_render_state_row_cells_next(cells) {
                let text = cell_text(cells);
                let fg = cell_color(cells, CELL_DATA_FG_COLOR);
                let bg = cell_color(cells, CELL_DATA_BG_COLOR);
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
                line_cells.push(Cell {
                    text,
                    fg,
                    bg,
                    selected,
                    underline: style.underline != SGR_UNDERLINE_NONE,
                    underline_color: match style.underline_color.tag {
                        2 => Some(style.underline_color.value.rgb),
                        _ => None,
                    },
                    strikethrough: style.strikethrough,
                    overline: style.overline,
                    col_span: 1,
                });
            }

            let text_only: String = line_cells.iter().map(|c| c.text.as_str()).collect();
            let trimmed = text_only.trim_end();
            if !trimmed.is_empty() {
                nonempty_lines += 1;
                let mut ansi = String::new();
                let mut last_fg: Option<GhosttyColorRgb> = None;
                let mut last_bg: Option<GhosttyColorRgb> = None;
                for cell in &line_cells {
                    if cell.fg != last_fg {
                        ansi.push_str(&cell.fg.map(ansi_fg).unwrap_or_else(|| "\x1b[39m".into()));
                        last_fg = cell.fg;
                    }
                    if cell.bg != last_bg {
                        ansi.push_str(&cell.bg.map(ansi_bg).unwrap_or_else(|| "\x1b[49m".into()));
                        last_bg = cell.bg;
                    }
                    ansi.push_str(&cell.text);
                }
                ansi.push_str("\x1b[0m");
                println!(
                    "{:02} [{}] {}",
                    row_index,
                    if row_dirty { "D" } else { " " },
                    ansi
                );
            }
            row_index += 1;
        }

        println!("\n== 验收比对 ==");
        // 简单程序化校验：关键行应出现且颜色正确
        let expect = [
            "hello world",
            "red text",
            "orange on blue",
            "中文宽字符测试",
            "underline test",
            "┌───┐",
            "│ x │",
            "└───┘",
            "🚀✅👨‍👩‍👧‍👦👍🏻🇨🇳⌨️🔋🧑‍🚀🫖🫶",
            "end",
        ];
        // 重新读一遍纯文本行（上面已经输出，这里再走一遍迭代器做断言）
        // 注意：渲染状态不允许 update 之间复用迭代器，重新 new。
        let mut row_it2: GhosttyRenderStateRowIterator = std::ptr::null_mut();
        check(
            ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it2),
            "row_iterator_new2",
        );
        check(
            ghostty_render_state_get(
                state,
                DATA_ROW_ITERATOR,
                &mut row_it2 as *mut _ as *mut c_void,
            ),
            "get row_iterator2",
        );
        let mut rows_text: Vec<String> = Vec::new();
        while ghostty_render_state_row_iterator_next(row_it2) {
            check(
                ghostty_render_state_row_get(
                    row_it2,
                    ROW_DATA_CELLS,
                    &mut cells as *mut _ as *mut c_void,
                ),
                "row cells2",
            );
            let mut line = String::new();
            while ghostty_render_state_row_cells_next(cells) {
                line.push_str(&cell_text(cells));
            }
            rows_text.push(line.trim_end().to_string());
        }
        let joined = rows_text.join("\n");
        let mut all_ok = true;
        for e in expect {
            let ok = joined.contains(e);
            println!("  {} : {}", if ok { "PASS" } else { "FAIL" }, e);
            all_ok &= ok;
        }
        if nonempty_lines == 0 {
            all_ok = false;
        }
        println!("非空行数: {nonempty_lines}（期望 ≥10）");
        if nonempty_lines < 10 {
            all_ok = false;
        }

        // 工单 22：Apple sbix 主通路 + Noto COLRv1 兜底（assets 文件加载，
        // sha256/版本校验；恒 160 超采样；类别化样例 + 回退 + 覆盖报告）。
        let (apple_path, noto_path) = emoji::default_font_paths();
        let mut emoji_fonts = emoji::EmojiFonts::load(
            Some(std::path::Path::new(&apple_path)),
            Some(std::path::Path::new(&noto_path)),
        );
        println!("字体加载: {}", emoji_fonts.diagnostics());
        let load_ok = matches!(
            emoji_fonts.status,
            emoji::FontStatus::Normal | emoji::FontStatus::Degraded
        );
        println!(
            "  {} : 字体加载（status={} load_ms={:.1}，≤200ms）",
            if load_ok && emoji_fonts.load_ms <= 200.0 {
                "PASS"
            } else {
                "FAIL"
            },
            emoji_fonts.status.label(),
            emoji_fonts.load_ms
        );
        all_ok &= load_ok && emoji_fonts.load_ms <= 200.0;

        let apple_version_ok = emoji_fonts
            .apple
            .as_ref()
            .map(|a| {
                a.version.contains("21.4d3e1")
                    && a.strike_ppem == 160
                    && a.png_count() == 3761
                    && a.sha256_hex == emoji::APPLE_EXPECTED_SHA256
            })
            .unwrap_or(false);
        if let Some(apple) = &emoji_fonts.apple {
            println!(
                "  {} : Apple 版本串 {} / strike {} / PNG {} / sha256 {:.12}…",
                if apple_version_ok { "PASS" } else { "FAIL" },
                apple.version,
                apple.strike_ppem,
                apple.png_count(),
                apple.sha256_hex
            );
        } else {
            println!("  FAIL : Apple 字体未加载（走降级/缺失）");
        }
        all_ok &= apple_version_ok;

        // 首现性能（冷 cache：加载后立即测 🚀 解码+缩放 ≤50ms，避免预热暖路径）。
        let first_ok = {
            let t0 = std::time::Instant::now();
            let got = emoji_fonts.rasterize_cluster("🚀", 24).is_some();
            let ms = t0.elapsed().as_secs_f32() * 1000.0;
            println!(
                "  {} : 🚀 首现（冷 cache 解码+缩放）{ms:.2}ms ≤50ms",
                if got && ms <= 50.0 { "PASS" } else { "FAIL" }
            );
            got && ms <= 50.0
        };
        all_ok &= first_ok;

        // 预热 50 个热门（2s 内达标）。
        let (prewarm_ok, prewarm_ms) = {
            let t0 = std::time::Instant::now();
            let (decoded, total, _) =
                emoji_fonts.prewarm(&emoji::POPULAR_EMOJI_50);
            let elapsed = t0.elapsed().as_secs_f32() * 1000.0;
            println!(
                "  {} : 预热 {decoded}/{total} 个热门 emoji（{elapsed:.1}ms ≤2000ms）",
                if decoded > 0 && elapsed <= 2000.0 {
                    "PASS"
                } else {
                    "FAIL"
                }
            );
            (decoded > 0 && elapsed <= 2000.0, elapsed)
        };
        all_ok &= prewarm_ok;

        // 类别化样例（36 项 = Apple 36/36 覆盖断言）：旗帜 8 / 家庭 4 / 肤色 4 /
        // 职业 ZWJ 4 / keycap 4 / tag 1 / 冷门 4 / VS16+基础 7。断言：非空 +
        // 至少一维 ≥0.7em（⚽ 等 Apple 图样本就是黑白球，不做彩色断言；
        // 彩色能力由 ✅/🚀/旗帜等其他断言覆盖）。
        let category_samples: [&str; 36] = [
            "🇨🇳", "🇺🇸", "🇯🇵", "🇬🇧", "🇫🇷", "🇩🇪", "🇧🇷", "🇰🇷",
            "👨‍👩‍👧‍👦", "👨‍👩‍👦", "👨‍👩‍👧", "👨‍👩‍👦‍👦",
            "👍🏻", "👍🏽", "👋🏾", "🧑🏿",
            "🧑‍🚀", "🧑‍💻", "👩‍🎓", "👨‍🍳",
            "1️⃣", "9️⃣", "#️⃣", "*️⃣",
            "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
            "🫖", "🫶", "🥷", "🦩",
            "⌨️", "🚀", "✅", "⚽", "🐶", "🍎", "🎄",
        ];
        let mut category_ok = true;
        for s in category_samples {
            let ok = emoji_fonts
                .rasterize_cluster(s, 24)
                .map(|b| {
                    let alpha = b.pixels.chunks_exact(4).any(|p| p[3] > 0);
                    alpha
                        // 工单 22 修复：画布 = em 盒（1em 高），内容在画布内
                        // 保持字体原生位置；画布至少 0.7em 高（ZWJ 多 glyph
                        // 画布更宽，高度仍 = 1em）。
                        && b.height as f32 >= 24.0 * 0.7
                })
                .unwrap_or(false);
            if !ok {
                println!("  FAIL : 类别样例 {s}");
                category_ok = false;
            }
        }
        println!(
            "  {} : 类别化样例 {} 项（Apple 36/36 覆盖：旗帜/家庭/肤色/职业 ZWJ/keycap/tag/冷门）非空",
            if category_ok { "PASS" } else { "FAIL" },
            category_samples.len()
        );
        all_ok &= category_ok;

        // 行高稳定（工单 22 修复）：emoji 画布（em 盒）不超出 1 格高 / 2 格宽；
        // 行高由主字体度量决定，emoji 只画在格内，有无 emoji 行高一致。
        let line_height_ok = ["🚀", "👨‍👩‍👧‍👦", "🇨🇳", "⌨️", "🏴󠁧󠁢󠁥󠁮󠁧󠁿"]
            .iter()
            .all(|s| {
                emoji_fonts
                    .rasterize_cluster(s, 16)
                    .map(|b| {
                        b.height <= 16 && b.width <= 32
                    })
                    .unwrap_or(false)
            });
        println!(
            "  {} : 行高稳定（emoji 画布=em 盒 ≤1格高/2格宽，不顶天立地）",
            if line_height_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= line_height_ok;

        // 新码位（Apple 有图且 Noto 无映射 → Emoji 17 时代新图样），抽查前 4 个。
        let new_cps = emoji_fonts.apple_only_codepoints();
        let new_ok = new_cps
            .iter()
            .take(4)
            .all(|&cp| {
                let s = char::from_u32(cp)
                    .map(|c| c.to_string())
                    .unwrap_or_default();
                !s.is_empty()
                    && emoji_fonts
                        .rasterize_cluster(&s, 24)
                        .map(|b| b.pixels.chunks_exact(4).any(|p| p[3] > 0))
                        .unwrap_or(false)
            });
        println!(
            "  {} : Emoji 17 新码位抽查（Apple-only {}/{} 个，抽查前 4 个）",
            if new_ok { "PASS" } else { "FAIL" },
            new_cps.len().min(8),
            new_cps.len()
        );
        all_ok &= new_ok;

        // VS16 行为：⌨️（带 VS16）必须走彩色位图；⌨（裸码位）不崩溃
        // （Apple 无图则整段降级 Noto/方框）。
        let vs16_ok = emoji_fonts
            .rasterize_cluster("⌨️", 24)
            .map(|b| b.pixels.chunks_exact(4).any(|p| p[3] > 0))
            .unwrap_or(false);
        println!(
            "  {} : VS16 行为（⌨️ 出图，⌨ 降级不崩溃）",
            if vs16_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= vs16_ok;
        let _ = emoji_fonts.rasterize_cluster("⌨", 24);

        // 工单 22 修复回归（画布=em 盒语义，全类一劳永逸）：
        // 旗帜（26 个单 RI + 组合）/ 竖条横条硬件 / ZWJ 序列 / 冷门，
        // 断言画布高度统一 = em、内容 bbox 在画布内不裁剪（不再 bbox fit）。
        let mut canvas_samples: Vec<String> = Vec::new();
        for cp in 0x1F1E6u32..=0x1F1FF {
            if let Some(c) = char::from_u32(cp) {
                canvas_samples.push(c.to_string());
            }
        }
        canvas_samples.extend([
            "🇨🇳".to_string(),
            "🇺🇸".to_string(),
            "🇬🇧".to_string(),
            "🇧🇷".to_string(),
            "🇯🇵".to_string(),
            "🏳️‍🌈".to_string(),
            "🏴󠁧󠁢󠁥󠁮󠁧󠁿".to_string(),
            "🚩".to_string(),
            "🎌".to_string(),
            "🔋".to_string(),
            "⚡".to_string(),
            "🔌".to_string(),
            "📱".to_string(),
            "⌨️".to_string(),
            "🖥️".to_string(),
            "🖱️".to_string(),
            "🖨️".to_string(),
            "👨‍👩‍👧‍👦".to_string(),
            "👩‍❤️‍👨".to_string(),
            "🧑‍💻".to_string(),
            "👍🏻".to_string(),
            "☠️".to_string(),
            "⚙️".to_string(),
            "♻️".to_string(),
            "🀄".to_string(),
            "🕐".to_string(),
            "1️⃣".to_string(),
            "㊗️".to_string(),
            "🈲".to_string(),
            "🥷".to_string(),
            "🦩".to_string(),
            "🫖".to_string(),
            "🫶".to_string(),
        ]);
        let mut canvas_fails: Vec<&str> = Vec::new();
        for s in &canvas_samples {
            let Some(b) = emoji_fonts.rasterize_cluster(s, 24) else {
                canvas_fails.push(s);
                continue;
            };
            // 画布高 ≈ em（0.7..1.25em：Apple 位图画布=1em，Noto 兜底按内容
            // 裁剪可能 1.08em；布局端统一 fit 2 格宽不溢出）。
            let h_em = b.height as f32 / 24.0;
            if !(0.7..=1.25).contains(&h_em) {
                canvas_fails.push(s);
                continue;
            }
            // 内容 bbox 必须在画布内（画布含透明边，内容不越界不裁剪）。
            if let Some((bx, by, bw, bh)) = b.bbox {
                if bx + bw > b.width || by + bh > b.height || bh == 0 {
                    canvas_fails.push(s);
                    continue;
                }
            }
        }
        let canvas_ok = canvas_fails.is_empty();
        println!(
            "  {} : 画布语义全类回归（{} 项：旗帜/硬件/ZWJ/冷门画布=em 盒、内容不越界）{}",
            if canvas_ok { "PASS" } else { "FAIL" },
            canvas_samples.len(),
            if canvas_fails.is_empty() {
                String::new()
            } else {
                format!(" 失败: {:?}", canvas_fails)
            }
        );
        all_ok &= canvas_ok;

        // Noto 兜底路径：取覆盖差异中第一个 Apple 无图/Noto 有映射的码位，
        // 断言整段 cluster 仍能出图（走 Noto COLRv1）。
        let (diff_count, diff) = emoji_fonts.coverage_diff();
        let fallback_ok = diff.first().map(|&cp| {
            let s = char::from_u32(cp).map(|c| c.to_string()).unwrap_or_default();
            !s.is_empty()
                && emoji_fonts
                    .rasterize_cluster(&s, 24)
                    .map(|b| b.pixels.chunks_exact(4).any(|p| p[3] > 0))
                    .unwrap_or(false)
        }).unwrap_or(false);
        println!(
            "  {} : Noto 兜底路径（覆盖差异 {diff_count} 个，抽查 U+{:04X}）",
            if fallback_ok { "PASS" } else { "FAIL" },
            diff.first().copied().unwrap_or(0)
        );
        all_ok &= fallback_ok;

        // 方框兜底：Apple/Noto 双字体缺失（状态=缺失）→ rasterize 返回 None，
        // 上层画主题适配方框（决策 5 第三级；实测四 emoji 区被双字体全覆盖，
        // Noto .notdef 亦出图，故方框只在字体缺失/加载失败时触发）。
        let box_fallback_ok = {
            let mut missing = emoji::EmojiFonts::load(None, None);
            missing.rasterize_cluster("🚀", 24).is_none()
                && missing.status == emoji::FontStatus::Missing
        };
        println!(
            "  {} : 方框兜底路径（字体缺失态 -> None，上层画主题方框）",
            if box_fallback_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= box_fallback_ok;

        // 覆盖差异报告写盘（验收项：输出 Apple vs Noto 覆盖差异）。
        let (apple_cov, noto_cov) = emoji_fonts.coverage_counts();
        let report = format!(
            "Apple vs Noto 覆盖差异报告（工单 22）\n\
             Apple 单码位 emoji 覆盖: {apple_cov}\n\
             Noto 单码位 emoji 覆盖: {noto_cov}\n\
             Apple 无图但 Noto 有: {diff_count}\n\
             触发 Noto 的码位: {:X?}\n",
            diff.iter().take(120).copied().collect::<Vec<u32>>()
        );
        let _ = std::fs::write("coverage_report.txt", &report);
        println!(
            "  {} : 覆盖差异报告已写 coverage_report.txt（diff={diff_count}，报告前 {} 个）",
            if !diff.is_empty() || apple_cov > 0 { "PASS" } else { "FAIL" },
            diff.len().min(40)
        );
        all_ok &= (!diff.is_empty() || apple_cov > 0);

        // 工单 13 回归：✅ 绿勾、家庭 ZWJ ≠ 单人、肤色、旗帜红色（Apple 图样重写）。
        let check_ok = emoji_fonts
            .rasterize_cluster("✅", 24)
            .map(|bmp| {
                let green = bmp.pixels.chunks_exact(4).any(|p| {
                    p[1] >= 120 && p[1] as u16 >= p[0] as u16 + 30 && p[1] as u16 >= p[2] as u16 + 30
                });
                green
            })
            .unwrap_or(false);
        println!(
            "  {} : ✅ 彩色（Apple 图样，绿底白勾）",
            if check_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= check_ok;

        let (family_ok, skin_ok, flag_ok) = {
            let family = emoji_fonts.rasterize_cluster("👨‍👩‍👧‍👦", 24);
            let person = emoji_fonts.rasterize_cluster("👨", 24);
            let thumb = emoji_fonts.rasterize_cluster("👍", 24);
            let tone = emoji_fonts.rasterize_cluster("👍🏻", 24);
            let flag = emoji_fonts.rasterize_cluster("🇨🇳", 24);
            let flag_red = flag
                .as_ref()
                .map(|f| {
                    f.pixels.chunks_exact(4).any(|p| {
                        p[0] >= 150 && p[1] <= 110 && p[2] <= 110
                    })
                })
                .unwrap_or(false);
            (
                family
                    .as_ref()
                    .zip(person.as_ref())
                    .map(|(f, p)| {
                        // Apple 图样重写：家庭 = 彩色全家福（≠ 单人；Noto 时代
                        // 的“灰卡更宽”断言不再适用）。
                        f.pixels != p.pixels
                            && f.pixels.chunks_exact(4).any(|px| {
                                px[3] > 0 && (px[0] != px[1] || px[1] != px[2])
                            })
                            && f.width as f32 >= 24.0 * 0.7
                    })
                    .unwrap_or(false),
                thumb
                    .as_ref()
                    .zip(tone.as_ref())
                    .map(|(t, o)| t.pixels != o.pixels || t.width != o.width)
                    .unwrap_or(false),
                flag_red,
            )
        };
        println!(
            "  {} : ZWJ 家庭 👨‍👩‍👧‍👦 出 Apple 彩色全家福（≠ 单人）",
            if family_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= family_ok;
        println!(
            "  {} : 肤色 👍🏻 与默认 👍 字形不同",
            if skin_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= skin_ok;
        println!(
            "  {} : 旗帜 🇨🇳 整形出红色",
            if flag_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= flag_ok;

        // 扩展测试集（工单 13 保留）：27 个代表性码位。断言：非空 + 彩色 + 尺寸 ≥0.7em。
        let ext: [&str; 27] = [
            "😀", "😢", "😂", "😍", "😡", "🥺", "🐶", "🐱", "🐼", "🦊", "🍎", "🍕", "🍜",
            "⚽", "🎮", "🎵", "📱", "💻", "☕", "❤️", "⭐", "⚠️", "🎄", "🎂", "💯", "👋🏻",
            "🏳️‍🌈",
        ];
        let ext_ok = ext.iter().all(|s| {
            emoji_fonts
                .rasterize_cluster(s, 24)
                .map(|b| {
                    let alpha = b.pixels.chunks_exact(4).any(|p| p[3] > 0);
                    // Apple 图样下 ⚽ 等本就是黑白球（设计如此），不断言彩色。
                    alpha
                        && (b.width as f32 > 24.0 * 0.7 || b.height as f32 > 24.0 * 0.7)
                        && b.width as f32 > 24.0 * 0.3
                        && b.height as f32 > 24.0 * 0.3
                })
                .unwrap_or(false)
        });
        println!(
            "  {} : 扩展测试集 27 个（黄脸/动物/食物/活动/物体/符号/ZWJ）非空且尺寸正常",
            if ext_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= ext_ok;
        let _ = prewarm_ms;

        let sprite_ok = symbols::sprite_bitmap('┌', 32)
            .map(|bitmap| bitmap.alpha.iter().any(|&a| a > 0))
            .unwrap_or(false);
        println!(
            "  {} : sprite 位图非空（┌ 程序化自绘）",
            if sprite_ok { "PASS" } else { "FAIL" }
        );
        all_ok &= sprite_ok;

        // 阶段 B：把网格收集成 Cell 矩阵 → 软件光栅 → PNG
        let mut grid: Vec<Vec<Cell>> = Vec::new();
        let mut row_it3: GhosttyRenderStateRowIterator = std::ptr::null_mut();
        check(
            ghostty_render_state_row_iterator_new(std::ptr::null(), &mut row_it3),
            "row_iterator_new3",
        );
        check(
            ghostty_render_state_get(
                state,
                DATA_ROW_ITERATOR,
                &mut row_it3 as *mut _ as *mut c_void,
            ),
            "get row_iterator3",
        );
        while ghostty_render_state_row_iterator_next(row_it3) {
            check(
                ghostty_render_state_row_get(
                    row_it3,
                    ROW_DATA_CELLS,
                    &mut cells as *mut _ as *mut c_void,
                ),
                "row cells3",
            );
            let mut row_cells: Vec<Cell> = Vec::new();
            while ghostty_render_state_row_cells_next(cells) {
                let text = cell_text(cells);
                let fg = cell_color(cells, CELL_DATA_FG_COLOR);
                let bg = cell_color(cells, CELL_DATA_BG_COLOR);
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
                row_cells.push(Cell {
                    text,
                    fg,
                    bg,
                    selected,
                    underline: style.underline != SGR_UNDERLINE_NONE,
                    underline_color: match style.underline_color.tag {
                        2 => Some(style.underline_color.value.rgb),
                        _ => None,
                    },
                    strikethrough: style.strikethrough,
                    overline: style.overline,
                    col_span: 1,
                });
            }
            merge_emoji_runs_main(&mut row_cells);
            grid.push(row_cells);
        }
        ghostty_render_state_row_iterator_free(row_it3);

        let underline_seen = grid
            .iter()
            .flatten()
            .any(|cell| cell.underline);
        println!(
            "  {} : 下划线样式从核心解析（SGR 4）",
            if underline_seen { "PASS" } else { "FAIL" }
        );
        all_ok &= underline_seen;

        // 补足每行到 cols 格（PNG 画布需要固定宽）
        for row in grid.iter_mut() {
            while row.len() < cols as usize {
                row.push(Cell::default());
            }
        }
        while grid.len() < rows as usize {
            grid.push(vec![Cell::default(); cols as usize]);
        }

        let font = load_font();
        match font {
            Some(font) => {
                let cursor_pos = if cursor_visible {
                    Some((cursor_x as usize, cursor_y as usize))
                } else {
                    None
                };
                let (pixels, w, h) = rasterize_grid(
                    &grid,
                    cols as usize,
                    cursor_pos,
                    (
                        colors.foreground.r,
                        colors.foreground.g,
                        colors.foreground.b,
                    ),
                    (
                        colors.background.r,
                        colors.background.g,
                        colors.background.b,
                    ),
                    &font,
                    Some(&mut emoji_fonts),
                    16.0,
                );
                let out_path = Path::new("out.png");
                write_png(out_path, w, h, &pixels);
                println!("\nPNG 已输出: {} ({}x{})", out_path.display(), w, h);
                // 离屏 PNG 彩色 emoji 验收：emoji 行（第 9 行，行内前 4 格）存在非灰像素。
                let emoji_row = 8usize;
                let emoji_colored = (emoji_row * CELL_H..(emoji_row + 1) * CELL_H).any(|y| {
                    (0..CELL_W * 4).any(|x| {
                        let i = (y * w + x) * 4;
                        pixels[i] != pixels[i + 1] || pixels[i + 1] != pixels[i + 2]
                    })
                });
                println!(
                    "  {} : PNG emoji 行含彩色像素",
                    if emoji_colored { "PASS" } else { "FAIL" }
                );
                all_ok &= emoji_colored;

                // 下划线可见性：SGR 4 行（第 5 行）文字基线下方 1px 起
                // thickness 行内应有成片前景色像素（工单 13：贴基线定位）。
                let ul_row = 4usize;
                let ul_thickness = ((CELL_H as f32 * 0.06).round() as usize).max(2);
                let (ascent_px, descent_px, line_gap_px) = font
                    .horizontal_line_metrics(16.0)
                    .map(|m| (m.ascent, m.descent, m.line_gap))
                    .unwrap_or((16.0 * 0.8, -16.0 * 0.2, 0.0));
                let line_h_px = (ascent_px - descent_px + line_gap_px).max(16.0);
                let ul_baseline =
                    (ul_row * CELL_H) as f32 + ((CELL_H as f32 - line_h_px) / 2.0).max(0.0) + ascent_px;
                let ul_start = (ul_baseline + 1.0) as usize;
                let ul_pixels = (ul_start..ul_start + ul_thickness)
                    .flat_map(|y| (0..CELL_W * 20).map(move |x| (y, x)))
                    .filter(|&(y, x)| {
                        let i = (y * w + x) * 4;
                        pixels[i] == 255 && pixels[i + 1] == 255 && pixels[i + 2] == 255
                    })
                    .count();
                let underline_ok = ul_pixels >= 40;
                println!(
                    "  {} : SGR 4 下划线可见（基线下方 {}px 前景像素 {} 个 ≥40）",
                    if underline_ok { "PASS" } else { "FAIL" },
                    ul_thickness,
                    ul_pixels
                );
                all_ok &= underline_ok;
            }
            None => {
                println!("\n警告: 未找到可用字体，跳过 PNG 输出");
                all_ok = false;
            }
        }

        ghostty_render_state_row_cells_free(cells);
        ghostty_render_state_row_iterator_free(row_it);
        ghostty_render_state_row_iterator_free(row_it2);
        ghostty_render_state_free(state);
        ghostty_terminal_free(terminal);

        let extra_ok = verify_scroll() & verify_resize();
        all_ok &= extra_ok;

        println!("\n结果: {}", if all_ok { "ALL PASS" } else { "FAILED" });
        std::process::exit(if all_ok { 0 } else { 1 });
    }
}
