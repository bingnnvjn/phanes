/*
 * 工单 08 坑 1 复刻：把最终 PT_TLS 的 p_align 抬到 64。
 * NDK/Zig 产物的 .tbss 只有 8 对齐，ARM64 bionic（API 29+）拒绝加载；
 * 这里放一个 64 对齐的 __thread 占位，链接器会把 TLS 段对齐抬上去。
 *
 * 工单 10 修订：Rust 不能直接引用这个 __thread 符号（会生成 IE/TPREL64
 * 重定位，dlopen 的 DSO 被 bionic 拒绝）。改用普通函数锚点拉入目标文件，
 * TLS 访问全部留在 C 侧，并以 global-dynamic 模型编译。
 */
__attribute__((used, aligned(64))) __thread unsigned char fable_render_tls_pad[64];

__attribute__((used, noinline)) void fable_render_tls_pad_anchor(void) {
  volatile unsigned char *p = fable_render_tls_pad;
  (void)p;
}
