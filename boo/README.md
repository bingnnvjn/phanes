# boo

独立 Rust 单二进制：Ghostty 官网动画观赏（`play`）+ 终端渲染压力基准（`bench`），
供 Fable 主终端链路做观赏对比与渲染压力测试。

## 构建

```bash
cargo build --target aarch64-linux-android --release
```

产物：`target/aarch64-linux-android/release/boo`。
零运行时依赖（仅动态链接系统 bionic）；拷入 Fable 环境即用。

## 用法

```bash
./boo play                  # 观赏模式：235 帧、100x41、30fps，一轮约 7.83 秒，循环
./boo bench                 # 基准模式：全屏每帧必变，无节流顶满；Ctrl-C 后打印统计
```

- `play`：播前清屏、居中显示；终端不足 100x41 时提示 "Screen must be at least 100w x 41h" 且不画；
  Ctrl-C 干净退出（恢复光标与样式）。
- `bench`：每帧内容必变（绕过内容签名去重），PTY 背压自动钳速；退出时打印
  总帧数 / 字节 / 耗时 / 平均发射速率。

## 帧数据来源与许可

动画帧取自 Ghostty 官方仓库（MIT License）：

- 参考实现：`ghostty-org/ghostty` `src/cli/boo.zig`（frame_width=100、frame_height=41、
  framerate=1000/30、235 帧、帧间分隔 `\x01`）
- 帧数据：`src/build/framegen/frames/frame_001.txt` ~ `frame_235.txt`
- 引用提交：`05221c11c9db0715666fc6e038915128fc6a563e`（2026-08-09 核实）
- 压缩格式与上游 `src/build/framegen/main.c` 一致：帧按文件名排序、`\x01` 连接、
  raw DEFLATE（无 zlib 头）；`build.rs` 在构建期生成，运行时解压

本 crate 代码亦按 MIT 许可发布。详见 `LICENSE`。

第三方来源和许可证证据见 [`THIRD_PARTY.md`](THIRD_PARTY.md)。
