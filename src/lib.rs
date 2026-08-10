//! 工单 10：Fable 安卓渲染器（cdylib：libfable_render.so）。

pub mod ffi;
pub mod emoji;
pub mod colr;
pub mod freetype_ffi;
pub mod sbix;
pub mod sha256;
pub mod symbols;
mod jni;
pub mod render_android;

pub use render_android::Renderer;
