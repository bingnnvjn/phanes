# 21 — fable-boo：Ghostty 官网动画（观赏 + 渲染压力基准）

**What to build:** 一个 Rust 单二进制 `fable-boo`（aarch64-linux-android、零运行时依赖），两个子命令：`play` 观赏模式（与 Ghostty 官网动画效果和时长一致：235 帧、100×41、30fps、一轮约 7.83 秒循环）；`bench` 基准模式（全屏每帧必变压力图案顶满，测现有主终端渲染链路极限）。帧数据取自 Ghostty 官方仓库 `+boo`（MIT）。不改 App 代码、不重编 APK；拷入 Fable 环境即用。

**Blocked by:** None

**Status:** 待开工

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
