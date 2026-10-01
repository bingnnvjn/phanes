//! 工单 10：Fable 安卓渲染器（cdylib：libfable_render.so）。

#![deny(unsafe_op_in_unsafe_fn)]
#![deny(clippy::undocumented_unsafe_blocks)]
#![deny(clippy::missing_safety_doc)]
#![deny(clippy::todo, clippy::unimplemented, clippy::dbg_macro)]
#![cfg_attr(
    not(test),
    deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)
)]
#![deny(improper_ctypes, improper_ctypes_definitions)]
#![deny(ffi_unwind_calls)]
#![deny(rustdoc::broken_intra_doc_links, rustdoc::private_intra_doc_links)]

pub mod colr;
pub mod emoji;
pub mod ffi;
pub mod freetype_ffi;
mod jni;
pub mod render_android;
pub mod sbix;
pub mod sha256;
pub mod symbols;

pub use render_android::Renderer;
