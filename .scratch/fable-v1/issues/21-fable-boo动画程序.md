# 21 — fable-boo：Ghostty 官网动画（观赏 + 渲染压力基准）

**What to build:** 一个 Rust 单二进制 `fable-boo`（aarch64-linux-android、零运行时依赖），两个子命令：`play` 观赏模式（与 Ghostty 官网动画效果和时长一致：235 帧、100×41、30fps、一轮约 7.83 秒循环）；`bench` 基准模式（全屏每帧必变压力图案顶满，测现有主终端渲染链路极限）。帧数据取自 Ghostty 官方仓库 `+boo`（MIT）。不改 App 代码、不重编 APK；拷入 Fable 环境即用。

**Blocked by:** None

**Status:** 待验收

## 验收清单

- [ ] 单二进制零运行时依赖（aarch64-linux-android），`./fable-boo play` / `./fable-boo bench` 可执行
- [ ] play：效果与时长和官网一致（235 帧、30fps、约 7.83 秒/轮、循环；字符与样式同官方；Ctrl-C 干净退出）
- [ ] play：尺寸检查（不足 100×41 时提示不画）、居中显示、播前清屏
- [ ] bench：每帧内容必变（绕过内容签名去重），无节流顶满；打印总帧数/字节/耗时/平均发射速率
- [ ] 旧 Termux 终端可跑通（通用 ANSI 通路验证）
- [ ] Fable 环境拷贝即用（共享存储 → home）；真机 play 与官网录屏对比效果/时长一致；bench 运行中 logcat 渲染侧无异常
- [ ] 许可：帧数据来源（Ghostty 官方仓库，MIT）记录在 README 与源码注释

## Comments

2026-08-09 建单（方案已确认：观赏 = 官方 30fps 节奏与时长一致、流畅度目标与官方持平；基准 = 压力顶满；Rust 单二进制、拷贝即用、不改 App）。

2026-08-09 实施记录：

验证数据/命令：
- 数据来源核实：Ghostty 官方 main 分支提交 `05221c11c9db0715666fc6e038915128fc6a563e`（2026-08-09），
  `src/cli/boo.zig`（frame_width=100、frame_height=41、framerate=1000/30、235 帧）；帧文件 235 个、
  每帧 4100 格子、相邻帧全不同；压缩格式与 `src/build/framegen/main.c` 一致（`\x01` 连接、raw DEFLATE、level 6）。
- 构建：`cargo build --target aarch64-linux-android --release` 成功；二进制 689K，
  `readelf -d` NEEDED 仅 `libc.so`/`libdl.so`（零运行时依赖）；sha256 `44002138f1956a8af28dae35c229023e089b644f5a77ba53332ed7a5d5cb9e5c`。
- 单元测试：19/19 PASS（帧解析 / ANSI 生成 / 数据完整性 / 居中 / 时间片 / bench 帧变化）。
- `play` 本机 PTY 验证：120x50 居中（col 11、row 5-45）、蓝色 span（`\x1b[34m`）正确、30fps 节奏循环；
  Ctrl-C 干净退出（exit 0，恢复光标/样式）；尺寸不足 100x41 时提示
  "Screen must be at least 100w x 41h" 且不画（exit 1）。
- `bench` 验证：无背压跑满约 2s：frames 245448、bytes 522067896、elapsed 2.000s、
  emit rate 122723.9 fps / 248.94 MiB/s；SIGTERM 干净停止并打印统计。
- 拷贝通路：二进制已放共享存储 `/storage/emulated/0/Download/fable-boo`（sha256 同上），可拷入 Fable home。

坑与解法：
- 帧含多字节 UTF-8（U+00B7），官方按码点计数 → 计数器跳过 continuation 字节；按字节数会多计 80。
- 帧尾带 `\n`，官方按行 split、换行不算格子 → 计数排除 `\n`。
- GitHub API 限流、raw 直连超时 → 走 `gh-proxy.com` 拉仓库 tarball（37MB）取 frames；
  `git clone --filter=blob:none --sparse` 逐 blob 懒取太慢，弃用。
- code-review 双轴评审发现并修复：ansi 状态机重复（合并为单一 walker）、
  多余 `max_frames`/`--duration` 参数（移除，bench 改纯信号停止）、term.rs 混杂（拆出 signals.rs）、
  offset 二元组（改 `Offset` 类型）。

结论写回：
- 本单未改 App 代码、未重编 APK（红线满足）。
- 剩余人工验收：Fable 环境（com.gph.fable）内拷入 home 后跑 `play` 与官网录屏对比效果/时长；
  `bench` 运行时用 logcat 观察渲染侧无异常。
- 对后续影响：`bench` 模式可作渲染链路回归工具（每帧内容必变，绕过内容签名去重）；
  帧数据嵌入方式（build.rs 压缩内嵌、运行时解压）可复用到其他动画/基准资产。
