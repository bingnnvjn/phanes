//! 工单 24 验证切片：portable-pty 0.9.0 在 aarch64-linux-android 的可用性探针。
//!
//! 产出 `libfable-session.so`：薄 JNI 桥，暴露 ptySpawn/Read/Write/Resize/Close。
//! 环境快照（PREFIX/HOME/PATH/TERM/TMPDIR）由 Kotlin 侧构造传入，本 crate 不重建
//! termux-shared 环境逻辑。

mod jni;
