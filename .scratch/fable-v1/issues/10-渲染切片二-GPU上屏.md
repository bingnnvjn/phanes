# 10 — 渲染切片二：wgpu + SurfaceView + Vulkan 真机上屏

**What to build:** 按 ADR-0004，把渲染器接进 fable-app：Rust wgpu（安卓 Vulkan）+ Kotlin SurfaceView/ANativeWindow，复用工单 09 的快照层，借 Shellow 的 GPU 整段（单 pipeline + 一次 draw call + WGSL + 脏行上传 + overlay）。真机出画面：文本、颜色、滚动、光标、选择叠加。

**Blocked by:** 09（快照层与 FFI 路线定案）。前置工具：cargo-ndk、Zig 0.16（若 09 定 B 路线则必须）。

Status: 已完成

## 目标

1. JNI 桥扩展（在工单 08 桥基础上加渲染状态/帧输出面）。
2. Rust 渲染器（快照 → 顶点 → wgpu 一次 draw call）接入，Kotlin 侧"内容签名触发渲染"驱动（借 Shellow）。
3. 真机验收：快速输出不卡、滚动流畅、中文/emoji 显示、多会话各自独立渲染。
4. 缺口补全顺序：正文迭代器路径 → 脏行增量顶点 → 彩色 emoji 图集 → sprite face（按 ADR-0004 决策 2）。

## 验收清单

- [x] wgpu 本机可行性探针：Termux 内创建 offscreen device 成功，backend/adapter 结果记录
- [x] Rust 渲染器编成 .so 并经 JNI 桥暴露给 Kotlin（快照 → 顶点 → wgpu 单 pipeline 一次 draw call）
- [x] Kotlin SurfaceView + ANativeWindow 接线：surface attach 后出帧，detach 不崩
- [x] "内容签名变化 → 触发渲染"驱动生效（Kotlin 触发、Rust 签名去重）
- [x] APK 构建成功且产物校验通过（aapt2 badging / apksigner / .so 清单）
- [x] 真机现象：文本、颜色、中文、光标可见；滚动回看；多会话各自独立渲染；快速输出不卡
- [x] 选择叠加（overlay）可见
- [x] 结论写回：wgpu backend/adapter、踩坑与解法、对后续工单影响

## Comments

2026-08-07 建单（ADR-0004 决策 4 的切片二；依赖 09 结论）。

2026-08-07 开工：按实施提示词补验收清单（原工单文件缺清单）；开始 wgpu 探针。

2026-08-07 实施完成（待真机验收）：

**1. wgpu 本机探针（已跑通）**

命令：`cd spike-render && cargo run --example wgpu_probe`

结果：`adapter_count=1`；`backend=Vulkan name=llvmpipe (LLVM 21.1.8, 128 bits) device_type=Cpu`；device 创建成功；offscreen clear pass 提交成功；`RESULT: OK vulkan-device-ready`。

结论：Termux 内 wgpu Vulkan device 可创建（走系统 Vulkan loader，本机枚举到 Mesa llvmpipe 软件适配器）；APK 内真机进程应枚举到硬件适配器（本机 Adreno），具体 backend/adapter 以装机后 `rendererInfo`/logcat 为准。

**2. 渲染器接线（已编进 APK）**

- `spike-render` 增加 lib 目标：`crate-type=["cdylib","rlib"]`，产物 `libfable_render.so`（release 9.36MB，打包后 6.57MB）。
- `src/render_android.rs`：快照（RenderState RowIterator/CellIterator）→ 字形图集（fontdue + JetBrainsMono 内嵌 + 系统 CJK 回退）→ 顶点（单 pipeline 一次 draw call，WGSL solid/glyph 双 mode）→ wgpu Vulkan Surface/ANativeWindow；内容签名变化才重绘，签名不变直接跳过。
- `src/jni.rs`：`com.gph.fable.app.RenderCore` JNI 桥（rendererCreate/Write/Resize/Scroll/Attach/Detach/Render/SetSelection/Info + ptySpawn/Read/Write/Resize/Close）。
- `src/pty_shim.c`：工单 08 探针 PTY 的 C 移植（bash --login，$PREFIX 指向 com.gph.fable）。
- Kotlin：`RenderActivity.java`（独立 launcher "Fable Render"，不碰 TermuxActivity/会话层）+ `RenderCore.java`；每会话 = PTY + 核心 + 渲染器 + 独立 SurfaceView，按钮覆盖 spawn/resize/scroll-10/seq200/select-demo/send。

**3. 构建与产物校验（全过）**

- `cd spike-render && cargo build --release` → 成功；`readelf -lW`：`PT_TLS p_align 0x40`（工单 08 坑 1 在 DSO 上复验通过）；NEEDED = libm/liblog/libandroid/libdl/libc；JNI 导出符号 15 个。
- `cd fable-app && export JAVA_HOME=... && ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL in 1m 11s`。
- APK：`app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`（173MB）；aapt2 badging = `com.gph.fable`；apksigner = `<签名证书主体>`；APK 内含 `lib/arm64-v8a/libfable-render.so`（打包后 PT_TLS p_align 仍 0x40，JNI 符号 15 个）。
- 装机包已放：`/storage/emulated/0/Download/fable-render-10_arm64-v8a.apk`（sha256 `27f41c09773ff48ac021f66dd7e8d0c3cb65e824463c75a5fef850967d9b4fc1`）。
- 工单 09 软件光栅回归：`cargo run --release` → `结果: ALL PASS`（未破坏）。

**4. 踩过的坑与解法**

- **坑 A（Cargo 链接标志丢失）**：给包加 `[lib]` 后，二进制目标链接命令不再带 `-lghostty-vt` 等 build.rs 输出，`spike-render` 主程序出现大量 `ghostty_*` undefined。解法：把 `#[link(name=..., kind="static")]` 直接放到 `src/ffi.rs` 的 extern block，并用 `force_tls_pad()` 显式引用 TLS 占位（不再依赖 `--undefined` 才能拉入）。
- **坑 B（jni 0.22 API）**：`JNIEnv` 现在是 `EnvUnowned`，数组/字符串方法要经 `with_env(|env| ...)` 拿到 `Env` 后调用；`get_native_interface` 不存在，用 `Env::get_raw()`。
- **坑 C（fontdue Metrics）**：`fontdue::Metrics` 没有 ascent/descent/line_gap（那是 `LineMetrics`），用 `Font::horizontal_line_metrics(px)` 单独取并随字形条目缓存。
- **坑 D（线程安全）**：PTY reader 线程若直接写 renderer，会与主线程渲染并发竞争核心状态；harness 改为 reader 只 post 字节到主线程，主线程串行 write+render。
- **坑 E（挂起时 post 的 use-after-free）**：reader 在 onDestroy 后可能仍有 post 在队列；post runnable 先查 `s.alive && sessions.contains(s)` 再碰 renderer 指针。
- **评审修正（选择叠加顺序）**：overlay 矩形原在单元格背景之前绘制，自定义背景会盖住选择色；改为单元格循环后、光标前绘制，选择叠加对自定义背景仍可见。

**5. 结论与后续影响**

- ADR-0004 决策 4 的"先 offscreen 验证还是直接进 APK"：两路都走通了——本机 offscreen 探针成功（llvmpipe），APK 已带 SurfaceView 上屏 harness；真机硬件 adapter 待用户确认。
- Shellow 的 GPU 整段（ANativeWindow attach、单 pipeline、一次 draw call、WGSL、内容签名触发）已按本切片最小集落地；脏行增量顶点 / 彩色 emoji 图集 / sprite face 维持后置，未挤入本工单。
- 对后续工单：多会话渲染已按"每会话一个 renderer + 一个 SurfaceView"验证结构；性能优化（独立渲染线程/demand lock、脏行增量）以真机实测为前置条件。

**待用户真机验收（覆盖安装，装完出现 "Fable Render" 图标）**

1. 打开 Fable Render → 点 [spawn] 建 1 个会话，SurfaceView 内应出现 bash 登录输出（文本/颜色）。
2. 输入 `echo $PREFIX`（期望 `/data/data/com.gph.fable/files/usr`）、`echo 中文`（期望中文可见）。
3. [40x10] / [80x24]：行列与重排正确。
4. `seq 1 200` 后 [scroll-10]：能看到旧行，滚动回看不卡。
5. [select]：第 1 行出现蓝色选择叠加。
6. [spawn] 建 2–4 个会话并行跑，各自独立渲染、互不崩。
7. 崩溃/黑屏时抓 logcat（tag `FableRender` / `AndroidRuntime`）回传。

2026-08-07 真机验收通过（用户逐项确认），工单完成：

- 文本/颜色/中文/光标：通过（`echo $PREFIX` → `/data/data/com.gph.fable/files/usr`；`echo 中文` 正常；白字 + 蓝色光标）。
- 滚动回看：`seq 1 200` + 新增 [top] 按钮一键滚到第 1 行，通过；原 [scroll-10] 只滚 10 行，在 160~190 区间内，容易误以为没动（语义正确）。
- 选择叠加：通过（[select] 蓝色条可见）。
- 多会话：spawn 第二个会话后出现两个独立 SurfaceView/光标，各自渲染互不干扰，通过。
- 快速输出：seq200 流式刷出不卡，通过。

**调试过程关键坑（本轮新增，均已在代码里修掉）**

- 坑 F（dlopen TLS IE 模型）：Rust 直接引用 C `__thread` 占位生成 `R_AARCH64_TLS_TPREL64`，bionic 拒绝 dlopen DSO（`TLS symbol ... using IE access model`）。解法：tls_shim 用 C 锚点函数（`fable_render_tls_pad_anchor`）拉入目标文件，编译加 `-ftls-model=global-dynamic -fPIC`；重定位全部变为 TLSDESC，`PT_TLS p_align=0x40` 保持。**根因坑（同 08 坑 1 的 DSO 版本）。**
- 坑 G（cc 不重编 C 源）：build.rs 缺 `rerun-if-changed`，改了 pty_shim.c 后新符号（`fable_anw_get_width/Height`）没进 .a，运行时报 `cannot locate symbol`。已补两条 rerun 声明。
- 坑 H（真机黑字/黑光标）：设备上默认前景/光标被解析成近黑，黑底上看不见；harness 层强制近黑前景（无显式背景时）转白、光标转亮蓝。9 验证时 host 是白字，真机行为不同，属设备主题差异。
- 坑 I（表面重连清零计数）：surfaceChanged 多次触发导致 GpuSurface 重建、计数器清零，info 一度误报 `terminal_frames=0`；计数器移到 GpuRuntime 级跨 attach 保留，attach 后强制 `last_signature=None` 重绘。
- 诊断方法记录：本地离屏自检（`cargo run --example wgpu_offscreen_check`）用同一管线渲染红块读回像素 → PASS，证明问题不在管线；[gpu] 测试块（左红右白 A）真机可见 → 证明 surface/管线 OK；最后定位到颜色层。

**最终产物**

- APK：`/storage/emulated/0/Download/fable-render-10_arm64-v8a.apk`（sha256 `cf4aec1cd52410dfd00eb40c610b8a458f5ee444be6df3fbafe83df813c89c0e`）
- 渲染器：`spike-render`（`render_android.rs` + `jni.rs` + `pty_shim.c` + `tls_shim.c`）
- Kotlin harness：`RenderActivity.java` / `RenderCore.java`（探针专用，主终端与会话层未动）
- 遗留待办（ADR-0004 后置，不属本工单）：脏行增量顶点、彩色 emoji 图集、sprite face、独立渲染线程/demand lock。
