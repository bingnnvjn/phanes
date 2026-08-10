//! COLRv1 paint graph 遍历 + 合成（FreeType 解析/光栅；工单 13）。
//!
//! FreeType 2.13+ 只自动合成 COLRv0 层列表；COLRv1 paint graph 需调用方
//! 自行遍历（Ghostty 同款做法，见 ADR-0006 调研）。本模块实现：
//! ColrLayers / Glyph / ColrGlyph / Solid / Linear·Radial·Sweep 渐变 /
//! Transform·Translate·Scale·Rotate·Skew / Composite 27 种混合模式
//! （HSL 系降级为 MULTIPLY，字体普查未用到）。
//!
//! 坐标约定（与 FreeType 一致）：paint 变换值 16.16（font 单位），根变换
//! 把 font 单位映射到 device px；NO_SCALE outline 是 26.6 font 单位，
//! 因此对 outline 应用矩阵时乘 64 修正（A × 64 × 65536，见实现）。

use crate::freetype_ffi::*;
use std::os::raw::{c_int, c_long};

const MAX_DEPTH: u32 = 16;

/// 仿射变换（f64）：x' = a*x + c*y + e; y' = b*x + d*y + f。
/// 语义：font 单位 → device px（y-up，与 FreeType 一致）。
#[derive(Clone, Copy)]
pub struct Affine {
    pub a: f64,
    pub b: f64,
    pub c: f64,
    pub d: f64,
    pub e: f64,
    pub f: f64,
}

impl Affine {
    pub fn identity() -> Self {
        Self { a: 1.0, b: 0.0, c: 0.0, d: 1.0, e: 0.0, f: 0.0 }
    }

    fn from_23(af: &FT_Affine23) -> Self {
        Self {
            a: af.xx as f64 / 65536.0,
            b: af.yx as f64 / 65536.0,
            c: af.xy as f64 / 65536.0,
            d: af.yy as f64 / 65536.0,
            e: af.dx as f64 / 65536.0,
            f: af.dy as f64 / 65536.0,
        }
    }

    fn translate(tx: f64, ty: f64) -> Self {
        Self { a: 1.0, b: 0.0, c: 0.0, d: 1.0, e: tx, f: ty }
    }

    fn scale(sx: f64, sy: f64) -> Self {
        Self { a: sx, b: 0.0, c: 0.0, d: sy, e: 0.0, f: 0.0 }
    }

    fn rotate(rad: f64) -> Self {
        let (s, c) = rad.sin_cos();
        Self { a: c, b: s, c: -s, d: c, e: 0.0, f: 0.0 }
    }

    fn skew(x_rad: f64, y_rad: f64) -> Self {
        Self { a: 1.0, b: y_rad.tan(), c: x_rad.tan(), d: 1.0, e: 0.0, f: 0.0 }
    }

    /// self = self ∘ m（先应用 m，再应用 self 原有的变换）。
    fn compose(&mut self, m: &Affine) {
        let (a, b, c, d, e, f) = (self.a, self.b, self.c, self.d, self.e, self.f);
        self.a = a * m.a + c * m.b;
        self.b = b * m.a + d * m.b;
        self.c = a * m.c + c * m.d;
        self.d = b * m.c + d * m.d;
        self.e = a * m.e + c * m.f + e;
        self.f = b * m.e + d * m.f + f;
    }

    fn apply(&self, x: f64, y: f64) -> (f64, f64) {
        (self.a * x + self.c * y + self.e, self.b * x + self.d * y + self.f)
    }

    fn invert(&self) -> Option<Affine> {
        let det = self.a * self.d - self.b * self.c;
        if det.abs() < 1e-12 {
            return None;
        }
        let ia = self.d / det;
        let ib = -self.b / det;
        let ic = -self.c / det;
        let id = self.a / det;
        let ie = -(ia * self.e + ic * self.f);
        let if_ = -(ib * self.e + id * self.f);
        Some(Affine { a: ia, b: ib, c: ic, d: id, e: ie, f: if_ })
    }
}

/// 合成用 RGBA 图像（straight alpha；offset = 左上角 device px，y-down）。
#[derive(Clone)]
pub struct RgbaImage {
    pub offset_x: i32,
    pub offset_y: i32,
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

impl RgbaImage {
    fn new(offset_x: i32, offset_y: i32, width: u32, height: u32) -> Self {
        Self {
            offset_x,
            offset_y,
            width,
            height,
            pixels: vec![0u8; (width * height * 4) as usize],
        }
    }

    fn empty() -> Self {
        Self { offset_x: 0, offset_y: 0, width: 0, height: 0, pixels: Vec::new() }
    }

    pub fn to_bitmap(&self) -> crate::emoji::RgbaBitmap {
        crate::emoji::RgbaBitmap {
            width: self.width,
            height: self.height,
            pixels: self.pixels.clone(),
            bbox: None,
            canvas_origin_x: 0,
            canvas_origin_y: 0,
        }
    }
}

/// COLRv1 合成上下文（face + CPAL 调色板）。
pub struct ColrCtx {
    pub face: FT_Face,
    pub palette: *const FT_Color,
    pub palette_len: usize,
}

enum Fill {
    Solid([u8; 4]),
    Linear(FT_PaintLinearGradient),
    Radial(FT_PaintRadialGradient),
    Sweep(FT_PaintSweepGradient),
}

fn zeroed_paint() -> FT_COLR_Paint {
    // union 无法 Default，用零初始化（所有成员可拷贝）。
    unsafe { std::mem::zeroed() }
}

fn palette_color(ctx: &ColrCtx, idx: FT_ColorIndex) -> [u8; 4] {
    let a = if idx.alpha == 0 {
        1.0
    } else {
        (idx.alpha as f64 / 16384.0).clamp(0.0, 1.0)
    };
    if ctx.palette.is_null() || idx.palette_index as usize >= ctx.palette_len {
        return [255, 255, 255, (a * 255.0).round() as u8];
    }
    let c = unsafe { *ctx.palette.add(idx.palette_index as usize) };
    let ca = (c.alpha as f64 / 255.0 * a).clamp(0.0, 1.0);
    [c.red, c.green, c.blue, (ca * 255.0).round() as u8]
}

/// 根入口：取 COLRv1 paint（含根变换），遍历合成出 RGBA 位图。
pub fn render_color_glyph(ctx: &ColrCtx, glyph_id: u32, pixels_per_em: u16) -> Option<RgbaImage> {
    if pixels_per_em == 0 || ctx.face.is_null() {
        return None;
    }
    let mut root = FT_OpaquePaint::default();
    if unsafe { FT_Get_Color_Glyph_Paint(ctx.face, glyph_id, FT_COLOR_INCLUDE_ROOT_TRANSFORM, &mut root) } == 0 {
        return None;
    }
    render_paint(ctx, root, &Affine::identity(), 0)
}


fn render_paint(ctx: &ColrCtx, op: FT_OpaquePaint, affine: &Affine, depth: u32) -> Option<RgbaImage> {
    if depth > MAX_DEPTH {
        return None;
    }
    let mut paint = zeroed_paint();
    if unsafe { FT_Get_Paint(ctx.face, op, &mut paint) } == 0 {
        return None;
    }
    match paint.format {
        FT_COLR_PAINTFORMAT_COLR_LAYERS => {
            let mut acc: Option<RgbaImage> = None;
            let mut it = unsafe { paint.u.colr_layers.layer_iterator };
            let mut layer = FT_OpaquePaint::default();
            while unsafe { FT_Get_Paint_Layers(ctx.face, &mut it, &mut layer) } != 0 {
                if let Some(img) = render_paint(ctx, layer, affine, depth + 1) {
                    acc = Some(match acc {
                        None => img,
                        Some(mut dst) => {
                            composite(&mut dst, &img, FT_COLR_COMPOSITE_SRC_OVER);
                            dst
                        }
                    });
                }
                layer = FT_OpaquePaint::default();
            }
            acc
        }
        FT_COLR_PAINTFORMAT_GLYPH => {
            let glyph = unsafe { paint.u.glyph };
            let fill = read_fill(ctx, glyph.paint, depth + 1)?;
            render_glyph(ctx, glyph.glyph_id, affine, &fill)
        }
        FT_COLR_PAINTFORMAT_COLR_GLYPH => {
            let gid = unsafe { paint.u.colr_glyph.glyph_id };
            let mut sub = FT_OpaquePaint::default();
            if unsafe { FT_Get_Color_Glyph_Paint(ctx.face, gid, FT_COLOR_NO_ROOT_TRANSFORM, &mut sub) } != 0 {
                render_paint(ctx, sub, affine, depth + 1)
            } else {
                None
            }
        }
        FT_COLR_PAINTFORMAT_TRANSFORM => {
            let mut m = *affine;
            m.compose(&Affine::from_23(&unsafe { paint.u.transform.affine }));
            render_paint(ctx, unsafe { paint.u.transform.paint }, &m, depth + 1)
        }
        FT_COLR_PAINTFORMAT_TRANSLATE => {
            let t = unsafe { paint.u.translate };
            let mut m = *affine;
            m.compose(&Affine::translate(t.dx as f64 / 65536.0, t.dy as f64 / 65536.0));
            render_paint(ctx, t.paint, &m, depth + 1)
        }
        FT_COLR_PAINTFORMAT_SCALE => {
            let s = unsafe { paint.u.scale };
            let cx = s.center_x as f64 / 65536.0;
            let cy = s.center_y as f64 / 65536.0;
            let mut m = *affine;
            m.compose(&Affine::translate(cx, cy));
            m.compose(&Affine::scale(s.scale_x as f64 / 65536.0, s.scale_y as f64 / 65536.0));
            m.compose(&Affine::translate(-cx, -cy));
            render_paint(ctx, s.paint, &m, depth + 1)
        }
        FT_COLR_PAINTFORMAT_ROTATE => {
            let r = unsafe { paint.u.rotate };
            let cx = r.center_x as f64 / 65536.0;
            let cy = r.center_y as f64 / 65536.0;
            let mut m = *affine;
            m.compose(&Affine::translate(cx, cy));
            m.compose(&Affine::rotate(r.angle as f64 / 65536.0));
            m.compose(&Affine::translate(-cx, -cy));
            render_paint(ctx, r.paint, &m, depth + 1)
        }
        FT_COLR_PAINTFORMAT_SKEW => {
            let k = unsafe { paint.u.skew };
            let cx = k.center_x as f64 / 65536.0;
            let cy = k.center_y as f64 / 65536.0;
            let mut m = *affine;
            m.compose(&Affine::translate(cx, cy));
            m.compose(&Affine::skew(
                k.x_skew_angle as f64 / 65536.0,
                k.y_skew_angle as f64 / 65536.0,
            ));
            m.compose(&Affine::translate(-cx, -cy));
            render_paint(ctx, k.paint, &m, depth + 1)
        }
        FT_COLR_PAINTFORMAT_COMPOSITE => {
            let c = unsafe { paint.u.composite };
            let backdrop = render_paint(ctx, c.backdrop_paint, affine, depth + 1);
            let mut source = render_paint(ctx, c.source_paint, affine, depth + 1);
            let mut dst = match backdrop {
                Some(b) => b,
                None => match source {
                    Some(s) => return Some(s),
                    None => return None,
                },
            };
            // 裸渐变/纯色 paint 作为 composite 操作数时，铺满 backdrop 区域
            //（COLRv1 允许；旗帜 emoji 的 SRC_IN 渐变源即此用法）。
            if source.is_none() {
                source = fill_paint_bounds(ctx, c.source_paint, affine, &dst);
            }
            if let Some(src) = source {
                composite(&mut dst, &src, c.composite_mode);
            }
            Some(dst)
        }
        _ => None,
    }
}

fn read_fill(ctx: &ColrCtx, op: FT_OpaquePaint, depth: u32) -> Option<Fill> {
    if depth > MAX_DEPTH {
        return None;
    }
    let mut paint = zeroed_paint();
    if unsafe { FT_Get_Paint(ctx.face, op, &mut paint) } == 0 {
        return None;
    }
    match paint.format {
        FT_COLR_PAINTFORMAT_SOLID => Some(Fill::Solid(palette_color(ctx, unsafe { paint.u.solid.color }))),
        FT_COLR_PAINTFORMAT_LINEAR_GRADIENT => Some(Fill::Linear(unsafe { paint.u.linear_gradient })),
        FT_COLR_PAINTFORMAT_RADIAL_GRADIENT => Some(Fill::Radial(unsafe { paint.u.radial_gradient })),
        FT_COLR_PAINTFORMAT_SWEEP_GRADIENT => Some(Fill::Sweep(unsafe { paint.u.sweep_gradient })),
        _ => None,
    }
}

/// 渲染单个 GLYPH 层：NO_SCALE outline 按当前仿射变换后光栅为覆盖率，
/// 再用 fill 着色（solid 或渐变）。
fn render_glyph(ctx: &ColrCtx, gid: u32, affine: &Affine, fill: &Fill) -> Option<RgbaImage> {
    unsafe {
        if FT_Load_Glyph(ctx.face, gid, FT_LOAD_NO_SCALE) != 0 {
            return None;
        }
        let slot = (*ctx.face).glyph;
        if slot.is_null() || (*slot).format != 0x6F75746C /* FT_GLYPH_FORMAT_OUTLINE = 'outl' */ {
            return None;
        }
        if (*slot).outline.n_points <= 0 {
            return None;
        }
        // NO_SCALE outline 是 26.6 font 单位；根变换 A 是 font 单位 → device px。
        // 对 26.6 outline 应用矩阵 M = A×64×65536，再平移 A.e×64（26.6）。
        let m = FT_Matrix {
            xx: (affine.a * 64.0 * 65536.0).round() as c_long,
            xy: (affine.c * 64.0 * 65536.0).round() as c_long,
            yx: (affine.b * 64.0 * 65536.0).round() as c_long,
            yy: (affine.d * 64.0 * 65536.0).round() as c_long,
        };
        FT_Outline_Transform(&mut (*slot).outline, &m);
        FT_Outline_Translate(
            &mut (*slot).outline,
            (affine.e * 64.0).round() as c_long,
            (affine.f * 64.0).round() as c_long,
        );
        if FT_Render_Glyph(slot, FT_RENDER_MODE_NORMAL) != 0 {
            return None;
        }
        let bmp = &(*slot).bitmap;
        if bmp.pixel_mode != FT_PIXEL_MODE_GRAY || bmp.width == 0 || bmp.rows == 0 || bmp.buffer.is_null()
        {
            return None;
        }
        let w = bmp.width as usize;
        let h = bmp.rows as usize;
        let pitch = if bmp.pitch < 0 {
            bmp.pitch.unsigned_abs() as usize
        } else {
            bmp.pitch as usize
        };
        let src = std::slice::from_raw_parts(bmp.buffer, pitch * h);
        let offset_x = (*slot).bitmap_left;
        // 工单 13 真机修复：FT 位图行 0 = 顶边（y-up bitmap_top）。
        // 图像左上角（y-down）= -bitmap_top；旧代码 bitmap_top - rows 把
        // "底边"当顶边，整层垂直翻转 + 错位（火箭解体/头颠倒/勾变竖条）。
        let offset_y = -(*slot).bitmap_top;
        let mut img = RgbaImage::new(offset_x, offset_y, w as u32, h as u32);
        let inv = affine.invert();
        for y in 0..h {
            for x in 0..w {
                let cov = src[y * pitch + x] as f64 / 255.0;
                if cov <= 0.0 {
                    continue;
                }
                let left_f = offset_x as f64;
                let top_f = (*slot).bitmap_top as f64; // y-up 顶（渐变反算用）
                let color = match fill {
                    Fill::Solid(c) => *c,
                    Fill::Linear(g) => gradient_color(
                        ctx,
                        inv.as_ref(),
                        &g.p0,
                        &g.p1,
                        &g.p2,
                        &g.colorline,
                        x,
                        y,
                        left_f,
                        top_f,
                    )?,
                    Fill::Radial(g) => gradient_color_radial(
                        ctx,
                        inv.as_ref(),
                        &g.p0,
                        &g.p1,
                        &g.p2,
                        &g.colorline,
                        x,
                        y,
                        left_f,
                        top_f,
                    )?,
                    Fill::Sweep(g) => gradient_color_sweep(
                        ctx,
                        inv.as_ref(),
                        &g.center,
                        g.start_angle,
                        g.end_angle,
                        &g.colorline,
                        x,
                        y,
                        left_f,
                        top_f,
                    )?,
                };
                let di = (y * w + x) * 4;
                img.pixels[di] = color[0];
                img.pixels[di + 1] = color[1];
                img.pixels[di + 2] = color[2];
                img.pixels[di + 3] = (color[3] as f64 * cov).round() as u8;
            }
        }
        Some(img)
    }
}

/// 裸渐变/纯色 paint 生成铺满 bounds 的图像（composite 操作数用）。
fn fill_paint_bounds(
    ctx: &ColrCtx,
    op: FT_OpaquePaint,
    affine: &Affine,
    bounds: &RgbaImage,
) -> Option<RgbaImage> {
    let mut paint = zeroed_paint();
    if unsafe { FT_Get_Paint(ctx.face, op, &mut paint) } == 0 {
        return None;
    }
    let mut img = RgbaImage::new(bounds.offset_x, bounds.offset_y, bounds.width, bounds.height);
    let inv = affine.invert();
    let left = bounds.offset_x as f64;
    let top = bounds.offset_y as f64 + bounds.height as f64; // y-up 顶
    for y in 0..img.height as usize {
        for x in 0..img.width as usize {
            let di = (y * img.width as usize + x) * 4;
            let c = match paint.format {
                FT_COLR_PAINTFORMAT_SOLID => palette_color(ctx, unsafe { paint.u.solid.color }),
                FT_COLR_PAINTFORMAT_LINEAR_GRADIENT => gradient_color(
                    ctx,
                    inv.as_ref(),
                    &unsafe { paint.u.linear_gradient }.p0,
                    &unsafe { paint.u.linear_gradient }.p1,
                    &unsafe { paint.u.linear_gradient }.p2,
                    &unsafe { paint.u.linear_gradient }.colorline,
                    x,
                    y,
                    left,
                    top,
                )?,
                FT_COLR_PAINTFORMAT_RADIAL_GRADIENT => gradient_color_radial(
                    ctx,
                    inv.as_ref(),
                    &unsafe { paint.u.radial_gradient }.p0,
                    &unsafe { paint.u.radial_gradient }.p1,
                    &unsafe { paint.u.radial_gradient }.p2,
                    &unsafe { paint.u.radial_gradient }.colorline,
                    x,
                    y,
                    left,
                    top,
                )?,
                FT_COLR_PAINTFORMAT_SWEEP_GRADIENT => gradient_color_sweep(
                    ctx,
                    inv.as_ref(),
                    &unsafe { paint.u.sweep_gradient }.center,
                    unsafe { paint.u.sweep_gradient }.start_angle,
                    unsafe { paint.u.sweep_gradient }.end_angle,
                    &unsafe { paint.u.sweep_gradient }.colorline,
                    x,
                    y,
                    left,
                    top,
                )?,
                _ => return None,
            };
            img.pixels[di..di + 4].copy_from_slice(&c);
        }
    }
    Some(img)
}

/// 线性渐变：p0→p1 为方向，p2 定义平面；t = dot(p-p0, p1-p0)/|p1-p0|²。
fn gradient_color(
    ctx: &ColrCtx,
    inv: Option<&Affine>,
    p0: &FT_Vector,
    p1: &FT_Vector,
    _p2: &FT_Vector,
    colorline: &FT_ColorLine,
    x: usize,
    y: usize,
    left: f64,
    top: f64,
) -> Option<[u8; 4]> {
    let fp = pixel_to_font(inv, x, y, left, top)?;
    let v = ft_vec_sub(p1, p0);
    let len2 = v.0 * v.0 + v.1 * v.1;
    if len2 <= 0.0 {
        return Some(colorline_color(ctx, colorline, 0.0));
    }
    let t = ((fp.0 - p0.x as f64) * v.0 + (fp.1 - p0.y as f64) * v.1) / len2;
    Some(colorline_color(ctx, colorline, t))
}

/// 径向渐变：r0=0（p0 圆心），r1=|p2-p1|；t = |p-p0|/r1。
fn gradient_color_radial(
    ctx: &ColrCtx,
    inv: Option<&Affine>,
    p0: &FT_Vector,
    p1: &FT_Vector,
    p2: &FT_Vector,
    colorline: &FT_ColorLine,
    x: usize,
    y: usize,
    left: f64,
    top: f64,
) -> Option<[u8; 4]> {
    let fp = pixel_to_font(inv, x, y, left, top)?;
    let r1 = ((p2.x - p1.x) as f64).hypot((p2.y - p1.y) as f64);
    let d = ((fp.0 - p0.x as f64).powi(2) + (fp.1 - p0.y as f64).powi(2)).sqrt();
    if r1 <= 0.0 {
        return Some(colorline_color(ctx, colorline, 0.0));
    }
    Some(colorline_color(ctx, colorline, d / r1))
}

/// 扫描渐变：绕 center 从 start_angle 到 end_angle（弧度，16.16）。
fn gradient_color_sweep(
    ctx: &ColrCtx,
    inv: Option<&Affine>,
    center: &FT_Vector,
    start_angle: FT_Fixed,
    end_angle: FT_Fixed,
    colorline: &FT_ColorLine,
    x: usize,
    y: usize,
    left: f64,
    top: f64,
) -> Option<[u8; 4]> {
    let fp = pixel_to_font(inv, x, y, left, top)?;
    let dx = fp.0 - center.x as f64;
    let dy = fp.1 - center.y as f64;
    let start = start_angle as f64 / 65536.0;
    let end = end_angle as f64 / 65536.0;
    let base = (fp.0 - center.x as f64).atan2(-(fp.1 - center.y as f64));
    let angle = base - start;
    let span = end - start;
    if span <= 0.0 {
        return Some(colorline_color(ctx, colorline, 0.0));
    }
    // 归一化到 [0, 1)（顺时针正方向与 COLR 约定一致）。
    let mut t = angle / span;
    while t < 0.0 {
        t += 1.0;
    }
    while t >= 1.0 {
        t -= 1.0;
    }
    let _ = (dx, dy);
    Some(colorline_color(ctx, colorline, t))
}

/// 像素 (x,y)（y-down）→ 反变换回 font 单位（y-up）。
fn pixel_to_font(inv: Option<&Affine>, x: usize, y: usize, left: f64, top: f64) -> Option<(f64, f64)> {
    let inv = inv?;
    let dx = left + x as f64;
    let dy = top - y as f64;
    Some(inv.apply(dx, dy))
}

fn ft_vec_sub(a: &FT_Vector, b: &FT_Vector) -> (f64, f64) {
    ((a.x - b.x) as f64, (a.y - b.y) as f64)
}

/// colorline 采样（PAD/REPEAT/REFLECT + 相邻 stop 插值）。
fn colorline_color(ctx: &ColrCtx, colorline: &FT_ColorLine, t: f64) -> [u8; 4] {
    let mut stops: Vec<(f64, [u8; 4])> = Vec::new();
    let mut it = colorline.color_stop_iterator;
    let mut st = FT_ColorStop::default();
    while unsafe { FT_Get_Colorline_Stops(ctx.face, &mut st, &mut it) } != 0 {
        stops.push((st.stop_offset as f64 / 65536.0, palette_color(ctx, st.color)));
        st = FT_ColorStop::default();
    }
    if stops.is_empty() {
        return [0, 0, 0, 0];
    }
    stops.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    let t = match colorline.extend {
        FT_PAINT_EXTEND_REPEAT => t - t.floor(),
        FT_PAINT_EXTEND_REFLECT => {
            let m = t - (2.0 * t).floor() * 0.5;
            1.0 - (m - m.floor() - 0.5).abs() * 2.0
        }
        _ => t.clamp(0.0, 1.0),
    };
    if stops.len() == 1 {
        return stops[0].1;
    }
    for pair in stops.windows(2) {
        let (t0, c0) = pair[0];
        let (t1, c1) = pair[1];
        if t <= t1 {
            if t1 == t0 {
                return c1;
            }
            let f = ((t - t0) / (t1 - t0)).clamp(0.0, 1.0);
            return lerp_color(c0, c1, f);
        }
    }
    stops.last().map(|s| s.1).unwrap_or([0, 0, 0, 0])
}

fn lerp_color(a: [u8; 4], b: [u8; 4], f: f64) -> [u8; 4] {
    let g = |x: u8, y: u8| (x as f64 + (y as f64 - x as f64) * f).round() as u8;
    [g(a[0], b[0]), g(a[1], b[1]), g(a[2], b[2]), g(a[3], b[3])]
}

/// 把 src 按混合模式合成到 dst（bounds 取并集；straight alpha）。
pub fn composite(dst: &mut RgbaImage, src: &RgbaImage, mode: c_int) {
    if src.width == 0 || src.height == 0 {
        return;
    }
    let x0 = dst.offset_x.min(src.offset_x);
    let y0 = dst.offset_y.min(src.offset_y);
    let x1 = (dst.offset_x + dst.width as i32).max(src.offset_x + src.width as i32);
    let y1 = (dst.offset_y + dst.height as i32).max(src.offset_y + src.height as i32);
    let nw = (x1 - x0) as u32;
    let nh = (y1 - y0) as u32;
    let mut out = vec![0u8; (nw * nh * 4) as usize];
    for y in 0..dst.height {
        for x in 0..dst.width {
            let ox = dst.offset_x - x0 + x as i32;
            let oy = dst.offset_y - y0 + y as i32;
            if ox >= 0 && oy >= 0 && (ox as u32) < nw && (oy as u32) < nh {
                let si = ((y * dst.width + x) * 4) as usize;
                let di = ((oy as u32 * nw + ox as u32) * 4) as usize;
                out[di..di + 4].copy_from_slice(&dst.pixels[si..si + 4]);
            }
        }
    }
    for y in 0..src.height {
        for x in 0..src.width {
            let ox = src.offset_x - x0 + x as i32;
            let oy = src.offset_y - y0 + y as i32;
            if ox < 0 || oy < 0 || (ox as u32) >= nw || (oy as u32) >= nh {
                continue;
            }
            let si = ((y * src.width + x) * 4) as usize;
            let di = ((oy as u32 * nw + ox as u32) * 4) as usize;
            let s = [src.pixels[si], src.pixels[si + 1], src.pixels[si + 2], src.pixels[si + 3]];
            let mut d = [out[di], out[di + 1], out[di + 2], out[di + 3]];
            blend_pixel(&mut d, s, mode);
            out[di..di + 4].copy_from_slice(&d);
        }
    }
    *dst = RgbaImage { offset_x: x0, offset_y: y0, width: nw, height: nh, pixels: out };
}

fn blend_pixel(dst: &mut [u8; 4], src: [u8; 4], mode: c_int) {
    let cs = [src[0] as f64 / 255.0, src[1] as f64 / 255.0, src[2] as f64 / 255.0, src[3] as f64 / 255.0];
    let cb = [dst[0] as f64 / 255.0, dst[1] as f64 / 255.0, dst[2] as f64 / 255.0, dst[3] as f64 / 255.0];
    let (as_, ab) = (cs[3], cb[3]);
    let put = |d: &mut [u8; 4], r: f64, g: f64, b: f64, a: f64| {
        d[0] = (r.clamp(0.0, 1.0) * 255.0).round() as u8;
        d[1] = (g.clamp(0.0, 1.0) * 255.0).round() as u8;
        d[2] = (b.clamp(0.0, 1.0) * 255.0).round() as u8;
        d[3] = (a.clamp(0.0, 1.0) * 255.0).round() as u8;
    };
    match mode {
        FT_COLR_COMPOSITE_CLEAR => put(dst, 0.0, 0.0, 0.0, 0.0),
        FT_COLR_COMPOSITE_SRC => *dst = src,
        FT_COLR_COMPOSITE_DEST => {}
        FT_COLR_COMPOSITE_SRC_OVER => {
            let ao = as_ + ab * (1.0 - as_);
            if ao <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(
                    dst,
                    (cs[0] * as_ + cb[0] * ab * (1.0 - as_)) / ao,
                    (cs[1] * as_ + cb[1] * ab * (1.0 - as_)) / ao,
                    (cs[2] * as_ + cb[2] * ab * (1.0 - as_)) / ao,
                    ao,
                );
            }
        }
        FT_COLR_COMPOSITE_DEST_OVER => {
            let ao = as_ + ab * (1.0 - as_);
            if ao <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(
                    dst,
                    (cs[0] * as_ * (1.0 - ab) + cb[0] * ab) / ao,
                    (cs[1] * as_ * (1.0 - ab) + cb[1] * ab) / ao,
                    (cs[2] * as_ * (1.0 - ab) + cb[2] * ab) / ao,
                    ao,
                );
            }
        }
        FT_COLR_COMPOSITE_SRC_IN => {
            let a = as_ * ab;
            if a <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(dst, cs[0], cs[1], cs[2], a);
            }
        }
        FT_COLR_COMPOSITE_DEST_IN => {
            let a = as_ * ab;
            if a <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(dst, cb[0], cb[1], cb[2], a);
            }
        }
        FT_COLR_COMPOSITE_SRC_OUT => {
            let a = as_ * (1.0 - ab);
            if a <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(dst, cs[0], cs[1], cs[2], a);
            }
        }
        FT_COLR_COMPOSITE_DEST_OUT => {
            let a = ab * (1.0 - as_);
            if a <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(dst, cb[0], cb[1], cb[2], a);
            }
        }
        FT_COLR_COMPOSITE_SRC_ATOP => {
            let a = ab;
            if a <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(dst, cs[0] * as_ + cb[0] * (1.0 - as_), cs[1] * as_ + cb[1] * (1.0 - as_), cs[2] * as_ + cb[2] * (1.0 - as_), a);
            }
        }
        FT_COLR_COMPOSITE_DEST_ATOP => {
            let a = as_;
            if a <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(dst, cb[0] * ab + cs[0] * (1.0 - ab), cb[1] * ab + cs[1] * (1.0 - ab), cb[2] * ab + cs[2] * (1.0 - ab), a);
            }
        }
        FT_COLR_COMPOSITE_XOR => {
            let ao = as_ + ab - 2.0 * as_ * ab;
            if ao <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(
                    dst,
                    (cs[0] * as_ * (1.0 - ab) + cb[0] * ab * (1.0 - as_)) / ao,
                    (cs[1] * as_ * (1.0 - ab) + cb[1] * ab * (1.0 - as_)) / ao,
                    (cs[2] * as_ * (1.0 - ab) + cb[2] * ab * (1.0 - as_)) / ao,
                    ao,
                );
            }
        }
        FT_COLR_COMPOSITE_PLUS => {
            let ao = (as_ + ab).min(1.0);
            if ao <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
            } else {
                put(
                    dst,
                    ((cs[0] * as_ + cb[0] * ab) / ao).min(1.0),
                    ((cs[1] * as_ + cb[1] * ab) / ao).min(1.0),
                    ((cs[2] * as_ + cb[2] * ab) / ao).min(1.0),
                    ao,
                );
            }
        }
        _ => {
            // 其余可分离混合模式（含 HSL 降级为 MULTIPLY）。
            let ao = as_ + ab - as_ * ab;
            if ao <= 0.0 {
                put(dst, 0.0, 0.0, 0.0, 0.0);
                return;
            }
            let mut out = [0.0f64; 3];
            for k in 0..3 {
                let b = blend_channel(cb[k], cs[k], mode);
                out[k] = (cs[k] * as_ * (1.0 - ab) + cb[k] * ab * (1.0 - as_) + b * as_ * ab) / ao;
            }
            put(dst, out[0], out[1], out[2], ao);
        }
    }
}

fn blend_channel(cb: f64, cs: f64, mode: c_int) -> f64 {
    match mode {
        FT_COLR_COMPOSITE_SCREEN => cs + cb - cs * cb,
        FT_COLR_COMPOSITE_OVERLAY => {
            if cb <= 0.5 {
                2.0 * cb * cs
            } else {
                1.0 - 2.0 * (1.0 - cb) * (1.0 - cs)
            }
        }
        FT_COLR_COMPOSITE_DARKEN => cb.min(cs),
        FT_COLR_COMPOSITE_LIGHTEN => cb.max(cs),
        FT_COLR_COMPOSITE_COLOR_DODGE => {
            if cb <= 0.0 {
                0.0
            } else if cs >= 1.0 {
                1.0
            } else {
                (cb / (1.0 - cs)).min(1.0)
            }
        }
        FT_COLR_COMPOSITE_COLOR_BURN => {
            if cb >= 1.0 {
                1.0
            } else if cs <= 0.0 {
                0.0
            } else {
                (1.0 - (1.0 - cb) / cs).max(0.0)
            }
        }
        FT_COLR_COMPOSITE_HARD_LIGHT => {
            if cs <= 0.5 {
                2.0 * cb * cs
            } else {
                1.0 - 2.0 * (1.0 - cb) * (1.0 - cs)
            }
        }
        FT_COLR_COMPOSITE_SOFT_LIGHT => {
            let d = if cb <= 0.25 {
                ((16.0 * cb - 12.0) * cb + 4.0) * cb
            } else {
                cb.sqrt()
            };
            if cs <= 0.5 {
                cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb)
            } else {
                cb + (2.0 * cs - 1.0) * (d - cb)
            }
        }
        FT_COLR_COMPOSITE_DIFFERENCE => (cb - cs).abs(),
        FT_COLR_COMPOSITE_EXCLUSION => cb + cs - 2.0 * cb * cs,
        _ => cb * cs, // MULTIPLY + HSL 降级
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn affine_invert_roundtrip() {
        let mut a = Affine::identity();
        a.compose(&Affine::scale(2.0, 3.0));
        a.compose(&Affine::translate(4.0, 5.0));
        let inv = a.invert().unwrap();
        let (x, y) = a.apply(7.0, 11.0);
        let (ix, iy) = inv.apply(x, y);
        assert!((ix - 7.0).abs() < 1e-9 && (iy - 11.0).abs() < 1e-9);
    }

    #[test]
    fn src_over_blend_matches_expectation() {
        // 不透明红 src-over 不透明蓝 → 红
        let mut d = [0u8; 4];
        let s = [255u8, 0, 0, 255];
        let b = [0u8, 0, 255, 255];
        d.copy_from_slice(&b);
        blend_pixel(&mut d, s, FT_COLR_COMPOSITE_SRC_OVER);
        assert_eq!(d, [255, 0, 0, 255]);
        // 半透明红 src-over 不透明蓝 → 粉
        d.copy_from_slice(&b);
        blend_pixel(&mut d, [255, 0, 0, 128], FT_COLR_COMPOSITE_SRC_OVER);
        assert_eq!(d[0], 128);
        assert_eq!(d[1], 0);
        assert_eq!(d[2], 127);
        assert_eq!(d[3], 255);
    }
}
