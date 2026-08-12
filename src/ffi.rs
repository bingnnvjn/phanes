//! 手写最小 FFI：只覆盖渲染切片需要的 libghostty-vt C API 子集。
//! 签名以 spike-libghostty/lib/include/ghostty/vt/*.h（ghostty b0947378）为准。
#![allow(non_camel_case_types, dead_code)]

use std::os::raw::c_void;

pub type GhosttyResult = i32;
pub const GHOSTTY_SUCCESS: i32 = 0;
pub const GHOSTTY_OUT_OF_MEMORY: i32 = -1;
pub const GHOSTTY_INVALID_VALUE: i32 = -2;
pub const GHOSTTY_OUT_OF_SPACE: i32 = -3;

pub type GhosttyTerminal = *mut c_void;
pub type GhosttyRenderState = *mut c_void;
pub type GhosttyRenderStateRowIterator = *mut c_void;
pub type GhosttyRenderStateRowCells = *mut c_void;
/** screen.h：typedef uint64_t GhosttyRow。 */
pub type GhosttyRow = u64;

// GhosttyTerminalData（terminal_get 用）
pub const TERMINAL_DATA_SCROLLBAR: i32 = 9;
/** 是否有任一 mouse tracking 模式激活（X10/1000/1002/1003）。 */
pub const TERMINAL_DATA_MOUSE_TRACKING: i32 = 11;
/** 终端标题（OSC 0/2 设置；借出串，下一次 vt_write/reset 前有效）。 */
pub const TERMINAL_DATA_TITLE: i32 = 12;
pub const TERMINAL_DATA_TOTAL_ROWS: i32 = 14;
pub const TERMINAL_DATA_SCROLLBACK_ROWS: i32 = 15;
pub const TERMINAL_DATA_VIEWPORT_ACTIVE: i32 = 32;

// GhosttyTerminalOption（ghostty_terminal_set 用；effects 回调注册）
pub const TERMINAL_OPT_USERDATA: i32 = 0;
pub const TERMINAL_OPT_BELL: i32 = 2;
pub const TERMINAL_OPT_TITLE_CHANGED: i32 = 5;

// GhosttyMode：ghostty_mode_new(value, ansi) = (value & 0x7FFF) | (ansi << 15)。
// 本工单只查 DEC 私有模式（ansi=false → 高位置 0）。
pub type GhosttyMode = u16;
/** 光标键 application mode（DECCKM，DECSET ?1）。 */
pub const GHOSTTY_MODE_CURSOR_KEYS_APPLICATION: GhosttyMode = 1;
/** 光标闪烁（DECSET 12）。 */
pub const GHOSTTY_MODE_CURSOR_BLINKING: GhosttyMode = 12;
/** 光标可见（DECTCEM，DECSET 25）。 */
pub const GHOSTTY_MODE_CURSOR_VISIBLE: GhosttyMode = 25;
/** 小键盘 application mode（DECKPAM，DECSET ?66）。 */
pub const GHOSTTY_MODE_KEYPAD_APPLICATION: GhosttyMode = 66;
/** Alternate screen（DECSET 1047）。 */
pub const GHOSTTY_MODE_ALT_SCREEN: GhosttyMode = 1047;
/** Alternate screen + 保存光标 + 清屏（DECSET 1049）。 */
pub const GHOSTTY_MODE_ALT_SCREEN_SAVE: GhosttyMode = 1049;
/** Bracketed paste（DECSET 2004）。 */
pub const GHOSTTY_MODE_BRACKETED_PASTE: GhosttyMode = 2004;
/** Button-event mouse tracking（DECSET 1002）。 */
pub const GHOSTTY_MODE_BUTTON_MOUSE: GhosttyMode = 1002;
/** Any-event mouse tracking（DECSET 1003）。 */
pub const GHOSTTY_MODE_ANY_MOUSE: GhosttyMode = 1003;
/** SGR mouse format（DECSET 1006）。 */
pub const GHOSTTY_MODE_SGR_MOUSE: GhosttyMode = 1006;

/** 借出字节串（terminal_get GHOSTTY_TERMINAL_DATA_TITLE 输出）。 */
#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyString {
    pub ptr: *const u8,
    pub len: usize,
}

// GhosttyTerminalScrollViewportTag
pub const SCROLL_VIEWPORT_TOP: i32 = 0;
pub const SCROLL_VIEWPORT_BOTTOM: i32 = 1;
pub const SCROLL_VIEWPORT_DELTA: i32 = 2;
pub const SCROLL_VIEWPORT_ROW: i32 = 3;

#[repr(C)]
#[derive(Clone, Copy)]
pub union GhosttyTerminalScrollViewportValue {
    pub delta: isize,
    pub row: usize,
    pub _padding: [u64; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyTerminalScrollViewport {
    pub tag: i32,
    pub value: GhosttyTerminalScrollViewportValue,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GhosttyTerminalScrollbar {
    pub total: u64,
    pub offset: u64,
    pub len: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyTerminalOptions {
    pub cols: u16,
    pub rows: u16,
    pub max_scrollback: usize,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GhosttyColorRgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

#[repr(C)]
pub struct GhosttyRenderStateColors {
    pub size: usize,
    pub background: GhosttyColorRgb,
    pub foreground: GhosttyColorRgb,
    pub cursor: GhosttyColorRgb,
    pub cursor_has_value: bool,
    pub palette: [GhosttyColorRgb; 256],
}

#[repr(C)]
pub struct GhosttyBuffer {
    pub ptr: *mut u8,
    pub cap: usize,
    pub len: usize,
}

// GhosttyStyle（style.h：行格样式，含 underline/strikethrough/overline）
#[repr(C)]
#[derive(Clone, Copy)]
pub union GhosttyStyleColorValue {
    /// GHOSTTY_STYLE_COLOR_PALETTE：0-255 索引（GhosttyColorPaletteIndex）。
    pub palette: u8,
    /// GHOSTTY_STYLE_COLOR_RGB：直接 RGB。
    pub rgb: GhosttyColorRgb,
    /// 对齐填充（C union 大小 = 最大成员 u64）。
    pub _padding: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyStyleColor {
    pub tag: i32,
    pub value: GhosttyStyleColorValue,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyStyle {
    pub size: usize,
    pub fg_color: GhosttyStyleColor,
    pub bg_color: GhosttyStyleColor,
    pub underline_color: GhosttyStyleColor,
    pub bold: bool,
    pub italic: bool,
    pub faint: bool,
    pub blink: bool,
    pub inverse: bool,
    pub invisible: bool,
    pub strikethrough: bool,
    pub overline: bool,
    pub underline: i32,
}

// GhosttyRenderStateData
pub const DATA_COLS: i32 = 1;
pub const DATA_ROWS: i32 = 2;
pub const DATA_DIRTY: i32 = 3;
pub const DATA_ROW_ITERATOR: i32 = 4;
pub const DATA_COLOR_BACKGROUND: i32 = 5;
pub const DATA_COLOR_FOREGROUND: i32 = 6;
pub const DATA_CURSOR_VISUAL_STYLE: i32 = 10;
pub const DATA_CURSOR_VISIBLE: i32 = 11;
pub const DATA_CURSOR_BLINKING: i32 = 12;
pub const DATA_CURSOR_VIEWPORT_HAS_VALUE: i32 = 14;
pub const DATA_CURSOR_VIEWPORT_X: i32 = 15;
pub const DATA_CURSOR_VIEWPORT_Y: i32 = 16;
/** 光标是否落在宽字符尾部（工单 26 光标修复；仅 CURSOR_VIEWPORT_HAS_VALUE 时有效）。 */
pub const DATA_CURSOR_VIEWPORT_WIDE_TAIL: i32 = 17;

// GhosttyRenderStateOption
pub const RENDER_STATE_OPTION_DIRTY: i32 = 0;

// GhosttyRenderStateRowOption
pub const ROW_OPTION_DIRTY: i32 = 0;

// GhosttyRenderStateDirty
pub const DIRTY_FALSE: i32 = 0;
pub const DIRTY_PARTIAL: i32 = 1;
pub const DIRTY_FULL: i32 = 2;

// GhosttyRenderStateRowData
pub const ROW_DATA_DIRTY: i32 = 1;
pub const ROW_DATA_CELLS: i32 = 3;

// GhosttyRenderStateRowCellsData
/** 原始 GhosttyCell 句柄（经 ghostty_cell_get 查询 WIDE 等属性）。 */
pub const CELL_DATA_RAW: i32 = 1;
pub const CELL_DATA_STYLE: i32 = 2;
pub const CELL_DATA_BG_COLOR: i32 = 5;
pub const CELL_DATA_FG_COLOR: i32 = 6;
pub const CELL_DATA_SELECTED: i32 = 7;
pub const CELL_DATA_HAS_STYLING: i32 = 8;
pub const CELL_DATA_GRAPHEMES_UTF8: i32 = 9;

// GhosttyCellData（screen.h）：raw cell 经 ghostty_cell_get 查询
/** 单元格宽属性（GhosttyCellWide）。 */
pub const GHOSTTY_CELL_DATA_WIDE: i32 = 3;

/** 不透明单元格句柄（screen.h：typedef uint64_t GhosttyCell）。 */
pub type GhosttyCell = u64;

// GhosttyCellWide
pub const CELL_WIDE_NARROW: i32 = 0;
pub const CELL_WIDE_WIDE: i32 = 1;
pub const CELL_WIDE_SPACER_TAIL: i32 = 2;
pub const CELL_WIDE_SPACER_HEAD: i32 = 3;

// GhosttyPointTag（point.h）
pub const POINT_TAG_ACTIVE: i32 = 0;
pub const POINT_TAG_VIEWPORT: i32 = 1;
/** 全屏坐标（含滚动历史；y 0 = 历史顶，活动屏顶 = scrollback_rows）。 */
pub const POINT_TAG_SCREEN: i32 = 2;
pub const POINT_TAG_HISTORY: i32 = 3;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyPointCoordinate {
    pub x: u16,
    pub y: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union GhosttyPointValue {
    pub coordinate: GhosttyPointCoordinate,
    pub _padding: [u64; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyPoint {
    pub tag: i32,
    pub value: GhosttyPointValue,
}

// GhosttyGridRef（grid_ref.h：sized struct，size 须设 sizeof）
#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyGridRef {
    pub size: usize,
    pub node: *mut c_void,
    pub x: u16,
    pub y: u16,
}

// GhosttyRowData（screen.h）
/** 当前行是否为软换行的续行（旧 TerminalBuffer mLineWrap 同义）。 */
pub const ROW_DATA_WRAP_CONTINUATION: i32 = 2;

// GhosttySgrUnderline
pub const SGR_UNDERLINE_NONE: i32 = 0;

#[link(name = "ghostty-vt", kind = "static")]
#[link(name = "tls_shim", kind = "static")]
#[link(name = "pty_shim", kind = "static")]
#[link(name = "m")]
#[link(name = "log")]
#[link(name = "android")]
extern "C" {
    pub fn ghostty_terminal_new(
        allocator: *const c_void,
        terminal: *mut GhosttyTerminal,
        options: GhosttyTerminalOptions,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_free(terminal: GhosttyTerminal);
    pub fn ghostty_terminal_vt_write(terminal: GhosttyTerminal, data: *const u8, len: usize);
    /** 设置终端选项/effect 回调（value 对回调与 userdata 直接传指针）。 */
    pub fn ghostty_terminal_set(
        terminal: GhosttyTerminal,
        option: i32,
        value: *const c_void,
    ) -> GhosttyResult;
    /** 查询终端模式（GhosttyMode 为打包 16 位值，见 modes.h）。 */
    pub fn ghostty_terminal_mode_get(
        terminal: GhosttyTerminal,
        mode: GhosttyMode,
        out_value: *mut bool,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_resize(
        terminal: GhosttyTerminal,
        cols: u16,
        rows: u16,
        cell_width_px: u32,
        cell_height_px: u32,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_scroll_viewport(
        terminal: GhosttyTerminal,
        behavior: GhosttyTerminalScrollViewport,
    );
    pub fn ghostty_terminal_get(
        terminal: GhosttyTerminal,
        data: i32,
        out: *mut c_void,
    ) -> GhosttyResult;
    /** 解析网格位置为（未跟踪）格引用；借用有效至下一次终端变更。 */
    pub fn ghostty_terminal_grid_ref(
        terminal: GhosttyTerminal,
        point: GhosttyPoint,
        out_ref: *mut GhosttyGridRef,
    ) -> GhosttyResult;

    /** 格引用的完整字素簇码点（两段式：先问长度，再取数据）。 */
    pub fn ghostty_grid_ref_graphemes(
        ref_: *const GhosttyGridRef,
        buf: *mut u32,
        buf_len: usize,
        out_len: *mut usize,
    ) -> GhosttyResult;
    /** 格引用所在行句柄（screen.h GhosttyRow）。 */
    pub fn ghostty_grid_ref_row(
        ref_: *const GhosttyGridRef,
        out_row: *mut GhosttyRow,
    ) -> GhosttyResult;
    pub fn ghostty_row_get(
        row: GhosttyRow,
        data: i32,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_new(
        allocator: *const c_void,
        state: *mut GhosttyRenderState,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_free(state: GhosttyRenderState);
    pub fn ghostty_render_state_update(
        state: GhosttyRenderState,
        terminal: GhosttyTerminal,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_get(
        state: GhosttyRenderState,
        data: i32,
        out: *mut c_void,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_set(
        state: GhosttyRenderState,
        option: i32,
        value: *const c_void,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_colors_get(
        state: GhosttyRenderState,
        colors: *mut GhosttyRenderStateColors,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_iterator_new(
        allocator: *const c_void,
        out_iterator: *mut GhosttyRenderStateRowIterator,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_iterator_free(iterator: GhosttyRenderStateRowIterator);
    pub fn ghostty_render_state_row_iterator_next(iterator: GhosttyRenderStateRowIterator) -> bool;
    pub fn ghostty_render_state_row_get(
        iterator: GhosttyRenderStateRowIterator,
        data: i32,
        out: *mut c_void,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_set(
        iterator: GhosttyRenderStateRowIterator,
        option: i32,
        value: *const c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_cells_new(
        allocator: *const c_void,
        out_cells: *mut GhosttyRenderStateRowCells,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_cells_free(cells: GhosttyRenderStateRowCells);
    pub fn ghostty_render_state_row_cells_next(cells: GhosttyRenderStateRowCells) -> bool;
    pub fn ghostty_render_state_row_cells_get(
        cells: GhosttyRenderStateRowCells,
        data: i32,
        out: *mut c_void,
    ) -> GhosttyResult;
    pub fn ghostty_cell_get(cell: u64, data: i32, out: *mut c_void) -> GhosttyResult;
}

/// 调用 C 锚点函数，强制链接器从 tls_shim.a 拉入该目标文件
/// （ARM64 bionic 要求 PT_TLS p_align=64，工单 08 坑 1）。
/// 不能在这里直接引用 `fable_render_tls_pad`：Rust 会生成 IE/TPREL64
/// TLS 重定位，dlopen 加载 DSO 时 bionic 拒绝（工单 10 实测）。
pub fn force_tls_pad() {
    extern "C" {
        fn fable_render_tls_pad_anchor();
    }
    unsafe {
        fable_render_tls_pad_anchor();
    }
}
