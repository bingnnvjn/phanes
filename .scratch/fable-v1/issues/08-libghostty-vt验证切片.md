# 08 — libghostty-vt 验证切片（核心 + Termux 环境 + 字节管道，零渲染）

**What to build:** 用最小代价验证"libghostty-vt 核心 + Termux Java 会话层 + 字节管道"在真机上成立：把 libghostty-vt（arm64-v8a）接进 fable-app 构建并打进 APK，经薄 JNI 桥从现有会话层取 PTY 字节喂入核心，拉出网格状态，以**文本 dump**（logcat / TextView）验证输出、resize、滚动、多会话与性能。**不写正式渲染器**（正式渲染语言与画法在切片结论后另行决策）。

**Blocked by:** None — can start immediately。（其结论是工单 04 渲染部分与渲染器决策的前提。）

Status: 已完成

- [x] libghostty-vt（arm64-v8a）接入构建：APK 内容清单含新增 .so，aapt2 badging / apksigner 校验保持通过
- [x] 真机：PTY 字节 → 网格 → 文本 dump 正确（echo/pwd/ls 输出与预期一致）
- [x] resize 后行列正确（40x10 / 80x24，文本重排）
- [x] 滚动缓冲增长正确（seq 1 200 → 全量 1~200 可从核心取出）
- [x] 多会话（2+ 并行）不崩，各自独立输出
- [x] bootstrap 环境兼容：$PREFIX=/data/data/com.gph.fable/files/usr、pwd=files/home、bash 交互正常（提示符 ~$）
- [x] 性能粗测：seq 200 行流式输出不卡死、无 ANR
- [x] 结论写回（见下）

## Comments

2026-08-06 建单（grilling 会话产出，ADR-0003）。

2026-08-06 进展（主机端探针 ALL PASS）：libghostty-vt 已在**本机**（aarch64 安卓/Termux）编译运行通过——`spike-libghostty/probe/spike_probe`，验证项全绿：

- 产物：expo-libghostty 0.8.1 官方 release（storage.libghostty-vt.b0947378349e.r3），ghostty commit b0947378，Zig 0.15.2 / NDK 27.1.12297006 / -Dsimd=false；官方 sha256 b38f032… 已核验（`spike-libghostty/lib/SHA256SUMS`）
- 验证项：terminal_new → vt_write（SGR/多行）→ formatter PLAIN 文本 dump → resize(40x10) 重排 → 滚动回看（delta -10 可访问旧行）→ 畸形字节不崩；build info：simd=0、kitty_graphics=1
- **坑 1（已解）**：NDK/Zig 产物的 .tbss 只有 8 对齐，且 Termux clang 默认 emutls（不产生真 TLS 段）→ 链接后的 PT_TLS p_align=8，ARM64 bionic（API 29+）拒绝运行："executable's TLS segment is underaligned"。解法：`-fno-emulated-tls` + 源码内 `__attribute__((used, aligned(64))) __thread` 占位，PT_TLS 抬到 64（LLVM D61824 同思路）。后续编 JNI .so 时需复验 loader 是否接受 DSO 的 TLS 对齐。
- `.a` 外部依赖仅 libm/libc/compiler-rt，Termux clang 可直接链接（无 android/log 依赖）
- 下一步：写薄 JNI 桥（C）→ 链成 libghostty-spike.so → 放进 fable-app jniLibs/arm64-v8a → 构建 APK（含真机验证 harness：自带 PTY 起 `$PREFIX/bin/bash`，喂字节 → dump 文本；不碰 termux-shared 会话层，保持切片隔离）→ 交用户装机验收

2026-08-06 进展（JNI 桥 + APK 构建完成，待真机验收）：

- `spike-libghostty/jni/ghostty_spike_jni.c`：JNI 桥（RegisterNatives），暴露 terminalCreate/Write/Resize/Scroll/FormatPlain/Free + ptySpawn/Read/Write/Resize/Close + buildInfo；PTY 用 posix_openpt + fork + exec `bash --login`（环境变量 HOME/PREFIX/TMPDIR/TERM/PATH 由 C 侧设置）
- `libghostty-spike.so`：Termux clang 链接成功（-llog 可用），PT_TLS p_align=64（-fno-emulated-tls + 64 对齐占位），导出 173 个 ghostty_* 符号；已放入 `fable-app/app/src/main/jniLibs/arm64-v8a/`
- 探针 harness：`app/src/main/java/com/gph/fable/app/SpikeActivity.java`（Java，程序化 UI）+ manifest 注册 "Fable Spike" 独立 launcher 图标；不碰 TermuxActivity/会话层
- 构建：`./gradlew :app:assembleDebug` BUILD SUCCESSFUL（57s，88 任务）
- 产物校验全过：aapt2 badging（Termux 原生 aapt2）= com.gph.fable / versionCode 1022 / 0.119.0-beta.3；apksigner = <签名证书主体>（<证书指纹>…）；APK 含 lib/arm64-v8a/ 4 个 .so（libghostty-spike.so + 原有 3 个）
- 装机包：`/storage/emulated/0/Download/fable-spike-08_arm64-v8a.apk`（173MB，sha256 0eeca2b5…）
- **待用户真机验收**（覆盖安装即可，装完有两个图标：Fable 主终端不变 / Fable Spike 探针）：
  1. 打开 Fable Spike → 点 [spawn] 建 1 个会话，应出现 bash 提示符/登录输出
  2. 输入 `echo $PREFIX` → 输出应为 `/data/data/com.gph.fable/files/usr`；`pwd` → files/home；`ls` 有内容
  3. [40x10] / [80x24]：屏幕行数随之变化（文本重排）
  4. 输出 `seq 1 200` 后点 [scroll-10]：能看到旧行（滚动回看）
  5. [spawn] 建满 4–6 个会话并行跑，互不崩、输出各自独立
  6. [seq200] 给所有会话发 200 行输出，不卡死（性能粗测）
  7. 崩溃时抓 logcat（tag: GhosttySpike / AndroidRuntime）回传

2026-08-06 真机验收结论（用户配合，v5→v7 多轮）：

- **结论：切片通过（核心可行性成立）**。libghostty-vt 在真机（com.gph.fable 环境）上：PTY 字节 → 核心 → 文本 dump 全链路工作，bash 交互正常。
- 验收明细：
  - `echo $PREFIX` → `/data/data/com.gph.fable/files/usr`；`pwd` → `/data/data/com.gph.fable/files/home`（bash 启动 chdir 到 home 后）；`ls` → 空 home，无错误
  - resize 40x10 / 80x24 → 行列与重排正确
  - `seq 1 200` → 流式刷到 200，无卡死；全量 1~200 可从核心取出（formatter 输出含滚动缓冲）
  - 多会话 0/1 并存各自输出，无崩溃
  - 提示符 `~$`（home 生效）
- **坑 2（探针显示，已解）**：快速刷出的输出卡在 200ms 刷新节流里不显示 → 改为每次读到数据立即 dump。
- **发现（formatter 语义，非 bug）**：`ghostty_formatter_format_*`（PLAIN）输出**完整滚动缓冲 + 屏幕**，与视口滚动无关——所以 [scroll] 按钮不改变 dump 文本。滚动/视口语义验证属渲染器阶段（用 render-state API），本切片以"全量内容可取出"为准，验收成立。
- 坑 1（TLS 对齐）+ 坑 2 已记录；构建参数/来源见上。
- **下一步**：渲染器决策（wgpu vs Skia / 借 expo-libghostty 渲染器起步）——切片已提供事实依据：核心吞吐与稳定性在真机可接受，渲染层可以放手选型；工单 04 的 CoreAdapter 缝约束不变。

2026-08-06 用户确认：**验收通过**，本工单关闭。

- **信息新鲜度原则（2026-08-06 用户强调）**：每次动手前先做信息新鲜度检查，以当天为准——不拿旧消息开发。libghostty-vt 安卓支持是 2026-06 才落地的快变区域，开工时必须重新核实：最新 Ghostty 版本、最新预编译产物（来源/版本/校验和/对应 ghostty commit）、最新已知坑与集成案例（expo-libghostty / ghostty_vte / Shellow 等），确认后再选下载源。

- 为什么是它：libghostty-vt 安卓支持 2026-06 才落地（官方 PR #10925），"借 Ghostty 核心 + Termux 本地环境"这个组合此前无人验证，是全计划最大未知数，排在最前。
- 参考实现：
  - expo-libghostty（MIT）：安卓 = libghostty-vt + JNI + Canvas/Skia 渲染；本切片只借它的 JNI / 接线思路，不借渲染。
  - Ghostling（Ghostty 官方，MIT）：单文件 C 演示 libghostty C API 最小接线。
  - 预编译路径：expo-libghostty 用 Zig 0.15.2 + NDK r27 交叉编译 libghostty-vt 静态库并固定校验和——本切片可复用该产物或自行交叉编译；本机 Zig 未装。
- 约束：零渲染（文本 dump 即可）；会话层不动（Termux Java）；构建环境配置（JDK 17 / aapt2 覆盖 / 假 NDK）不动；不写正式渲染器、不决定 wgpu/Skia。
- 红线：不把 token/keystore/密码写入仓库；不改与工单无关的文件；旧 Termux 数据原样保留。
- 完成后更新 Status 并追加 Comments；产出物：APK 路径、验证结论、对 ADR-0003 的反馈（重开条件之一）。
