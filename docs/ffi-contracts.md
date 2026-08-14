# spike-render FFI 与 unsafe 合约

（工单 50，2026-08-14）

本文件是 `spike-render` JNI、libghostty-vt、FreeType、字体 mmap、wgpu/native
window 和跨线程 renderer 边界的实现合约。代码中的 `SAFETY:` 注释只引用本文件
定义的不变量，不以“应该安全”作为证明。

## 统一不变量

- **Validity / provenance**：C 指针来自对应库的成功构造或 JNI/Android API；调用前
  检查返回码、空指针、长度和对齐。Rust 切片长度只来自已验证的 C 长度或 mmap
  文件长度；不得把 Java 长度或字体表偏移直接当作可访问范围。
- **ABI / layout**：跨边界结构均使用 `repr(C)`；整数宽度与 C/JNI 签名固定。
  `Ghostty*`、`FT_*` 联合体只读取库已写入的字段；Rust 不按截断结构分配 C 实例。
  JNI 导出统一 `extern "system"`，返回值只使用 JNI 定义宽度。
- **Ownership / release**：JNI renderer 参数是单调递增 registry token，不是裸指针。
  registry 持有一份 `Arc<Renderer>`；调用先 clone `Arc`，`destroy` 只移除 registry
  所有权，已在途调用结束后再 Drop。重复/迟到 token 调用返回安全默认值。
- **Native window**：`ANativeWindow_fromSurface` 成功后产生一份必须释放的引用。
  attach 命令入 mailbox 前由 JNI 路径释放；入队后所有权转给 renderer command；
  attach 失败、detach、surface 重建和 `RendererCore::drop` 各释放恰好一次。
  wgpu `Surface` 只在 renderer 线程使用；window 引用存活至 surface 销毁。
- **Threading**：JNI `Env` 只在进入导出的当前线程使用；registry 的 `Mutex` 保护
  token map；renderer command 通过有界 mailbox 送入单一 renderer 线程。`Send`
  实现仅适用于 command 内部不跨线程解引用 Java/JNI 指针、window 释放仍在 renderer
  线程完成的不变量。
- **Panic / unwind**：JNI 导出函数以 `catch_unwind` 包围；panic 转为 `0`、空字符串、
  `false` 或无操作，不穿过 `extern "system"`。C callback 只写入仍由 renderer 持有
  的事件盒，不执行可能 panic 的 Java 调用。
- **Failure**：OOM、字体解码失败、GPU device lost、surface outdated/lost、PTY
  关闭和重复 destroy 都走确定错误/默认返回；不得用失败后的半初始化句柄继续工作。

## 边界索引

| 边界 | 实现位置 | 自动化回归 |
| --- | --- | --- |
| JNI token / 重复 destroy | `src/jni.rs::HandleRegistry` | token 无效、重复 remove、在途 `Arc`、并发读销毁 |
| libghostty-vt snapshot | `src/render_android.rs::collect_snapshot` | 字形、emoji、选择、滚动、模式和快照探针 |
| FreeType / COLRv1 | `src/emoji.rs`, `src/colr.rs`, `src/freetype_ffi.rs` | Noto/Apple 回退、损坏输入返回 `None` |
| sbix mmap | `src/sbix.rs` | 表边界、LRU、Drop 释放映射 |
| native window / wgpu | `src/jni.rs`, `src/render_android.rs` | attach/detach/reset、离屏 GPU 自检；真机释放顺序需 Android 验收 |
| renderer 跨线程 | `src/render_android.rs::Renderer` | renderer thread/mailbox 句柄测试与既有回归探针 |

## 例外政策

`unsafe_op_in_unsafe_fn` 只在 Ghostty snapshot 适配模块保留局部 `expect`，因为该
模块把一组同一 C ABI 合约的读取集中在历史 `unsafe fn` 中；新增 unsafe 不得沿用该
例外，必须写显式 `unsafe {}` 与邻接 `SAFETY:`，并补边界测试。其余模块继续由
crate 级 `deny` 阻断。
