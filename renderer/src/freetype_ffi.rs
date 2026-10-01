//! 最小 FreeType FFI（手工绑定，C ABI；只用工单 13 彩色字形需要的 API）。
//!
//! FreeType 2.14.3（vendored：`third_party/freetype`，构建见 `build.rs`）
//! 静态链接进 `libfable-render.so`。结构体只保留访问到的字段前缀
//! （repr(C) 前缀布局与 C 一致；不分配实例，故可安全截断尾部字段）。

#![expect(
    non_camel_case_types,
    reason = "工单 50：FreeType C ABI 名称和保留布局字段必须原样保留"
)]

use std::os::raw::{c_char, c_int, c_long, c_short, c_uchar, c_uint, c_ulong, c_ushort, c_void};

pub type FT_Long = c_long;
pub type FT_ULong = c_ulong;
pub type FT_Int = c_int;
pub type FT_UInt = c_uint;
pub type FT_UShort = c_ushort;
pub type FT_Short = c_short;
pub type FT_Byte = c_uchar;
pub type FT_Pos = c_long;
pub type FT_Fixed = c_long;
pub type FT_Error = c_int;

// FT_Load_Glyph flags（只列用到的位）。
pub const FT_LOAD_DEFAULT: c_int = 0;
pub const FT_LOAD_NO_SCALE: c_int = 1 << 0;
pub const FT_LOAD_RENDER: c_int = 1 << 2;
pub const FT_LOAD_COLOR: c_int = 1 << 20;

pub const FT_RENDER_MODE_NORMAL: c_int = 0;

// FT_Pixel_Mode
pub const FT_PIXEL_MODE_NONE: c_uchar = 0;
pub const FT_PIXEL_MODE_MONO: c_uchar = 1;
pub const FT_PIXEL_MODE_GRAY: c_uchar = 2;
pub const FT_PIXEL_MODE_BGRA: c_uchar = 7;

// FT_PaintFormat（ftcolor.h 2.13+）
pub const FT_COLR_PAINTFORMAT_COLR_LAYERS: c_int = 1;
pub const FT_COLR_PAINTFORMAT_SOLID: c_int = 2;
pub const FT_COLR_PAINTFORMAT_LINEAR_GRADIENT: c_int = 4;
pub const FT_COLR_PAINTFORMAT_RADIAL_GRADIENT: c_int = 6;
pub const FT_COLR_PAINTFORMAT_SWEEP_GRADIENT: c_int = 8;
pub const FT_COLR_PAINTFORMAT_GLYPH: c_int = 10;
pub const FT_COLR_PAINTFORMAT_COLR_GLYPH: c_int = 11;
pub const FT_COLR_PAINTFORMAT_TRANSFORM: c_int = 12;
pub const FT_COLR_PAINTFORMAT_TRANSLATE: c_int = 14;
pub const FT_COLR_PAINTFORMAT_SCALE: c_int = 16;
pub const FT_COLR_PAINTFORMAT_ROTATE: c_int = 24;
pub const FT_COLR_PAINTFORMAT_SKEW: c_int = 28;
pub const FT_COLR_PAINTFORMAT_COMPOSITE: c_int = 32;

pub const FT_COLOR_INCLUDE_ROOT_TRANSFORM: c_int = 0;
pub const FT_COLOR_NO_ROOT_TRANSFORM: c_int = 1;

// FT_Composite_Mode
pub const FT_COLR_COMPOSITE_CLEAR: c_int = 0;
pub const FT_COLR_COMPOSITE_SRC: c_int = 1;
pub const FT_COLR_COMPOSITE_DEST: c_int = 2;
pub const FT_COLR_COMPOSITE_SRC_OVER: c_int = 3;
pub const FT_COLR_COMPOSITE_DEST_OVER: c_int = 4;
pub const FT_COLR_COMPOSITE_SRC_IN: c_int = 5;
pub const FT_COLR_COMPOSITE_DEST_IN: c_int = 6;
pub const FT_COLR_COMPOSITE_SRC_OUT: c_int = 7;
pub const FT_COLR_COMPOSITE_DEST_OUT: c_int = 8;
pub const FT_COLR_COMPOSITE_SRC_ATOP: c_int = 9;
pub const FT_COLR_COMPOSITE_DEST_ATOP: c_int = 10;
pub const FT_COLR_COMPOSITE_XOR: c_int = 11;
pub const FT_COLR_COMPOSITE_PLUS: c_int = 12;
pub const FT_COLR_COMPOSITE_SCREEN: c_int = 13;
pub const FT_COLR_COMPOSITE_OVERLAY: c_int = 14;
pub const FT_COLR_COMPOSITE_DARKEN: c_int = 15;
pub const FT_COLR_COMPOSITE_LIGHTEN: c_int = 16;
pub const FT_COLR_COMPOSITE_COLOR_DODGE: c_int = 17;
pub const FT_COLR_COMPOSITE_COLOR_BURN: c_int = 18;
pub const FT_COLR_COMPOSITE_HARD_LIGHT: c_int = 19;
pub const FT_COLR_COMPOSITE_SOFT_LIGHT: c_int = 20;
pub const FT_COLR_COMPOSITE_DIFFERENCE: c_int = 21;
pub const FT_COLR_COMPOSITE_EXCLUSION: c_int = 22;
pub const FT_COLR_COMPOSITE_MULTIPLY: c_int = 23;
pub const FT_COLR_COMPOSITE_HSL_HUE: c_int = 24;
pub const FT_COLR_COMPOSITE_HSL_SATURATION: c_int = 25;
pub const FT_COLR_COMPOSITE_HSL_COLOR: c_int = 26;
pub const FT_COLR_COMPOSITE_HSL_LUMINOSITY: c_int = 27;

// FT_PaintExtend
pub const FT_PAINT_EXTEND_PAD: c_int = 0;
pub const FT_PAINT_EXTEND_REPEAT: c_int = 1;
pub const FT_PAINT_EXTEND_REFLECT: c_int = 2;

pub type FT_Library = *mut c_void;
pub type FT_GlyphSlot = *mut FT_GlyphSlotRec;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FT_Generic {
    pub data: *mut c_void,
    pub finalizer: *mut c_void,
}

#[repr(C)]
#[derive(Clone, Copy)]
#[expect(
    non_snake_case,
    reason = "工单 50：字段名必须匹配 FreeType FT_Glyph_Metrics ABI"
)]
pub struct FT_Glyph_Metrics {
    pub width: FT_Pos,
    pub height: FT_Pos,
    pub horiBearingX: FT_Pos,
    pub horiBearingY: FT_Pos,
    pub horiAdvance: FT_Pos,
    pub vertBearingX: FT_Pos,
    pub vertBearingY: FT_Pos,
    pub vertAdvance: FT_Pos,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_Vector {
    pub x: FT_Pos,
    pub y: FT_Pos,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FT_Matrix {
    pub xx: FT_Fixed,
    pub xy: FT_Fixed,
    pub yx: FT_Fixed,
    pub yy: FT_Fixed,
}

#[repr(C)]
#[derive(Clone, Copy)]
#[expect(
    non_snake_case,
    reason = "工单 50：字段名必须匹配 FreeType FT_BBox ABI"
)]
pub struct FT_BBox {
    pub xMin: FT_Pos,
    pub yMin: FT_Pos,
    pub xMax: FT_Pos,
    pub yMax: FT_Pos,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FT_Bitmap {
    pub rows: c_uint,
    pub width: c_uint,
    pub pitch: c_int,
    pub buffer: *mut c_uchar,
    pub num_grays: c_ushort,
    pub pixel_mode: c_uchar,
    pub palette_mode: c_uchar,
    pub palette: *mut c_void,
}

// ---------- COLRv1（ftcolor.h）结构体 ----------

/// FT_OpaquePaint：对 Paint 的偏移句柄。调用前必须清零 p/insert_root_transform。
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_OpaquePaint {
    pub p: *mut FT_Byte,
    pub insert_root_transform: u8,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_LayerIterator {
    pub num_layers: FT_UInt,
    pub layer: FT_UInt,
    pub p: *mut FT_Byte,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_ColorStopIterator {
    pub num_color_stops: FT_UInt,
    pub current_color_stop: FT_UInt,
    pub p: *mut FT_Byte,
    pub read_variable: u8,
}

/// FT_Color：BGRA 字节序（blue, green, red, alpha）。
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_Color {
    pub blue: FT_Byte,
    pub green: FT_Byte,
    pub red: FT_Byte,
    pub alpha: FT_Byte,
}

/// FT_ColorIndex：调色板索引 + F2Dot14 alpha。
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_ColorIndex {
    pub palette_index: u16,
    pub alpha: i16,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_ColorStop {
    pub stop_offset: FT_Fixed,
    pub color: FT_ColorIndex,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_ColorLine {
    pub extend: c_int,
    pub color_stop_iterator: FT_ColorStopIterator,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_Affine23 {
    pub xx: FT_Fixed,
    pub xy: FT_Fixed,
    pub dx: FT_Fixed,
    pub yx: FT_Fixed,
    pub yy: FT_Fixed,
    pub dy: FT_Fixed,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintColrLayers {
    pub layer_iterator: FT_LayerIterator,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintSolid {
    pub color: FT_ColorIndex,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintLinearGradient {
    pub colorline: FT_ColorLine,
    pub p0: FT_Vector,
    pub p1: FT_Vector,
    pub p2: FT_Vector,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintRadialGradient {
    pub colorline: FT_ColorLine,
    pub p0: FT_Vector,
    pub p1: FT_Vector,
    pub p2: FT_Vector,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintSweepGradient {
    pub colorline: FT_ColorLine,
    pub center: FT_Vector,
    pub start_angle: FT_Fixed,
    pub end_angle: FT_Fixed,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintGlyph {
    pub paint: FT_OpaquePaint,
    pub glyph_id: FT_UInt,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintColrGlyph {
    pub glyph_id: FT_UInt,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintTransform {
    pub paint: FT_OpaquePaint,
    pub affine: FT_Affine23,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintTranslate {
    pub paint: FT_OpaquePaint,
    pub dx: FT_Fixed,
    pub dy: FT_Fixed,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintScale {
    pub paint: FT_OpaquePaint,
    pub scale_x: FT_Fixed,
    pub scale_y: FT_Fixed,
    pub center_x: FT_Fixed,
    pub center_y: FT_Fixed,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintRotate {
    pub paint: FT_OpaquePaint,
    pub angle: FT_Fixed,
    pub center_x: FT_Fixed,
    pub center_y: FT_Fixed,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintSkew {
    pub paint: FT_OpaquePaint,
    pub x_skew_angle: FT_Fixed,
    pub y_skew_angle: FT_Fixed,
    pub center_x: FT_Fixed,
    pub center_y: FT_Fixed,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_PaintComposite {
    pub source_paint: FT_OpaquePaint,
    pub composite_mode: c_int,
    pub backdrop_paint: FT_OpaquePaint,
}

#[repr(C)]
pub union FT_COLR_PaintUnion {
    pub colr_layers: FT_PaintColrLayers,
    pub glyph: FT_PaintGlyph,
    pub solid: FT_PaintSolid,
    pub linear_gradient: FT_PaintLinearGradient,
    pub radial_gradient: FT_PaintRadialGradient,
    pub sweep_gradient: FT_PaintSweepGradient,
    pub transform: FT_PaintTransform,
    pub translate: FT_PaintTranslate,
    pub scale: FT_PaintScale,
    pub rotate: FT_PaintRotate,
    pub skew: FT_PaintSkew,
    pub composite: FT_PaintComposite,
    pub colr_glyph: FT_PaintColrGlyph,
}

#[repr(C)]
pub struct FT_COLR_Paint {
    pub format: c_int,
    pub u: FT_COLR_PaintUnion,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct FT_Palette_Data {
    pub num_palettes: FT_UShort,
    pub palette_name_ids: *mut FT_UShort,
    pub palette_flags: *mut FT_UShort,
    pub num_palette_entries: FT_UShort,
    pub palette_entry_name_ids: *mut FT_Byte,
}

/// FT_Outline（只读字段：n_points/points/tags/contours）。
#[repr(C)]
pub struct FT_Outline {
    pub n_contours: c_short,
    pub n_points: c_short,
    pub points: *mut FT_Vector,
    pub tags: *mut c_char,
    pub contours: *mut c_short,
    pub flags: c_int,
}

/// FT_GlyphSlotRec 前缀（读到 outline 为止；后面字段不访问）。
#[repr(C)]
pub struct FT_GlyphSlotRec {
    pub library: FT_Library,
    pub face: *mut FT_FaceRec,
    pub next: FT_GlyphSlot,
    pub glyph_index: FT_UInt,
    pub generic: FT_Generic,
    pub metrics: FT_Glyph_Metrics,
    pub linear_hori_advance: FT_Fixed,
    pub linear_vert_advance: FT_Fixed,
    pub advance: FT_Vector,
    pub format: c_int,
    pub bitmap: FT_Bitmap,
    pub bitmap_left: FT_Int,
    pub bitmap_top: FT_Int,
    pub outline: FT_Outline,
}

/// FT_FaceRec 前缀（读到 glyph 为止；后面字段不访问）。
#[repr(C)]
pub struct FT_FaceRec {
    pub num_faces: FT_Long,
    pub face_index: FT_Long,
    pub face_flags: FT_Long,
    pub style_flags: FT_Long,
    pub num_glyphs: FT_Long,
    pub family_name: *mut c_char,
    pub style_name: *mut c_char,
    pub num_fixed_sizes: FT_Int,
    pub available_sizes: *mut c_void,
    pub num_charmaps: FT_Int,
    pub charmaps: *mut c_void,
    pub generic: FT_Generic,
    pub bbox: FT_BBox,
    pub units_per_em: FT_UShort,
    pub ascender: FT_Short,
    pub descender: FT_Short,
    pub height: FT_Short,
    pub max_advance_width: FT_Short,
    pub max_advance_height: FT_Short,
    pub underline_position: FT_Short,
    pub underline_thickness: FT_Short,
    pub glyph: FT_GlyphSlot,
}

pub type FT_Face = *mut FT_FaceRec;

#[link(name = "freetype")]
extern "C" {
    pub fn FT_Init_FreeType(alibrary: *mut FT_Library) -> FT_Error;
    pub fn FT_Done_FreeType(library: FT_Library) -> FT_Error;
    pub fn FT_New_Memory_Face(
        library: FT_Library,
        file_base: *const FT_Byte,
        file_size: FT_Long,
        face_index: FT_Long,
        aface: *mut FT_Face,
    ) -> FT_Error;
    pub fn FT_Done_Face(face: FT_Face) -> FT_Error;
    pub fn FT_Set_Pixel_Sizes(
        face: FT_Face,
        pixel_width: FT_UInt,
        pixel_height: FT_UInt,
    ) -> FT_Error;
    pub fn FT_Load_Glyph(face: FT_Face, glyph_index: FT_UInt, load_flags: c_int) -> FT_Error;
    pub fn FT_Render_Glyph(slot: FT_GlyphSlot, render_mode: c_int) -> FT_Error;
    // COLRv1 / 颜色 API（ftcolor.h 2.13+）
    pub fn FT_Get_Color_Glyph_Paint(
        face: FT_Face,
        base_glyph: FT_UInt,
        root_transform: c_int,
        paint: *mut FT_OpaquePaint,
    ) -> u8;
    pub fn FT_Get_Paint(
        face: FT_Face,
        opaque_paint: FT_OpaquePaint,
        paint: *mut FT_COLR_Paint,
    ) -> u8;
    pub fn FT_Get_Paint_Layers(
        face: FT_Face,
        layer_iterator: *mut FT_LayerIterator,
        paint: *mut FT_OpaquePaint,
    ) -> u8;
    pub fn FT_Get_Colorline_Stops(
        face: FT_Face,
        color_stop: *mut FT_ColorStop,
        iterator: *mut FT_ColorStopIterator,
    ) -> u8;
    pub fn FT_Palette_Select(
        face: FT_Face,
        palette_index: FT_UShort,
        apalette: *mut *const FT_Color,
    ) -> FT_Error;
    pub fn FT_Palette_Data_Get(face: FT_Face, apalette: *mut FT_Palette_Data) -> FT_Error;
    pub fn FT_Outline_Transform(outline: *mut FT_Outline, matrix: *const FT_Matrix) -> FT_Error;
    pub fn FT_Outline_Translate(
        outline: *mut FT_Outline,
        offset_x: FT_Pos,
        offset_y: FT_Pos,
    ) -> FT_Error;
}
