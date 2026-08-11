# fable-v1/27 同场景对比采集包（Rust 会话层）

> 任务本体：`.scratch/fable-v1/issues/27-验收对比与java下线评估.md`。
> 与 fable-v1/23 采集包的关系：**同场景、同脚本、同参数、同指标表**，仅会话层从 Java 换成 Rust
> （工单 26 双实现缝，构建期默认 Rust），输出目录独立（`fable-baseline-27`）。

## 与 23 的差异（口径保持）

- 脚本与 `.scratch/fable-v1/baseline/scripts/` 同源；真机镜像经
  `FABLE_BASELINE_BASE=/storage/emulated/0/Download/fable-baseline-27` 指到本目录，
  4 会话运行参数与 23 完全一致。
- 8 会话余量扩展（默认不影响 4 会话口径）：`FABLE_SESSIONS=N`（run-session.sh/verify.sh）、
  `FABLE_DONE_TARGET=N`（sampler-loop.sh 停止条件）、`adb-drive.sh` 经
  `FABLE_SESSIONS`/`FABLE_DONE_TARGET` 驱动开 N 会话与收尾。
- 本包真机镜像：`/storage/emulated/0/Download/fable-baseline-27/`
- 装机包：fable-app-26 最终包（sha256 `63273cce…`，Rust 默认，已装真机）。

## 运行（模式 B，adb 全自动）

1. 手机开「无线调试」配对（端口 + 配对码发给代理）。
2. `bash adb-drive.sh connect <port> <code>`
3. `bash adb-drive.sh prep`（开 4 会话 + 起负载；8 会话余量：`FABLE_SESSIONS=8 bash adb-drive.sh prep`）
4. `bash adb-drive.sh stress`（12 分钟 4 周期交互；仅 4 会话对比用）
5. `bash adb-drive.sh finish`（verify + 拉取全部结果；8 会话：`FABLE_SESSIONS=8 FABLE_DONE_TARGET=8`）
6. 宿主机本地重算权威校验（同 23：sha256 与期望流逐字节比对）。

## 产物

- 结果目录：`.scratch/fable-v1/baseline/results-<时间戳>/`（4 会话一轮，8 会话另一轮）
- 对比报告：`.scratch/fable-v1/baseline/对比报告-27-rust-vs-java.md`（模板见
  `对比报告模板-27.md`，与 23 基线报告同目录）
