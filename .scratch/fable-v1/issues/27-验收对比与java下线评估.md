# 27 — 验收对比 + Java 会话层下线评估

**What to build:** 用工单 23 同场景在 Rust 会话层上重跑，输出对比报告（崩溃/卡顿/内存/输出完整性）；主终端全量回归（含 v1 最小集与 emoji/渲染回归）；按 2–4 会话硬指标 + 8 余量数据判定；结论写回：Java 会话层下线 or 保留双实现，含对 ADR-0008 与后续工单的影响。

**Blocked by:** fable-v1/23、fable-v1/26
Status: 已完成

## 验收清单

- [ ] 同场景对比数据（Rust vs fable-v1/23 基线）：4 会话硬指标 PASS；8 会话余量数据记录
- [ ] 主终端全量回归（字号/主题/键盘/会话恢复/中文/emoji/滚动/复制粘贴/深色浅色）
- [ ] 对比报告落盘（与 fable-v1/23 基线报告同目录）
- [ ] 结论写回 Comments：Java 会话层去留决定 + 影响（ADR-0008 状态、后续工单、事件流/AI 方向的衔接）

## Comments

2026-08-10 建单（决策窗口 8：会话层搬 Rust，ADR-0008）。

2026-08-11 开跑（本地准备）：

- 环境核实：真机已装 APK = 工单 26 最终包（sha256 `63273cce…`，versionCode 1022，
  Rust 会话层默认开启），无需重装；仓库 HEAD `a46f01b`（fable-app `426091d`），工作树干净。
- 对比口径复用 fable-v1/23 采集包：同脚本（`start-run.sh`/`run-session.sh`/`stress-loop.sh`/
  `verify.sh`）、同参数（90×5000 行、轮间 7s、12 分钟 4 周期）、同指标表、同报告目录。
- 8 会话余量：`run-session.sh`/`sampler-loop.sh`/`verify.sh` 增加会话数可配（默认 4，行为不变），
  供 8 会话余量采集（ADR-0008 设计余量，只记录数据）。
- 真机镜像：`/storage/emulated/0/Download/fable-baseline-27/`（部署中）。

2026-08-11 4 会话对比完成（Rust vs Java 同场景，真机全自动）：

- 运行窗口 21:42:44–21:54:07（≥10 分钟），4 会话并行，每会话 450,000 行。
- 输出完整性 4/4 PASS（sha256 与期望流逐字节一致、行数 450000、首末行正确）——180 万行零丢失。
- 崩溃 0 / 强制 surface 重建 0（诊断日志窗口无重启标记、0 次 `forcing recovery`）；切换 35 次，
  surface 生命周期 35 轮正常。内存：合计 VmRSS 峰值 1,512,832 kB（≈1,477 MB，@ rel 31s 会话创建期），
  主进程 VmRSS 峰值 1,493,452 kB / PSS 峰值 1,313,019 kB。
- 复制/粘贴：12 分钟压力期驱动坐标落在扩展键工具条（几何失配，0 事件）；受控探针确认 Rust 模式
  长按选择/复制/粘贴正常（startTextSelectionMode → actionModeStarted → copyModeChanged → toolbarShow）。
- 数据：`.scratch/fable-v1/baseline/results-27-rust4/`（data/ 4×6.75MB 真值 + mem-samples.tsv +
  verify 输出 + 4 张截图 + drive.log）。

2026-08-11 8 会话余量测试中止（用户暂停）：

- 现象：prep 开 8 会话并全部起负载后（22:00:53 起，s8 22:03:35），设备内存压力陡增
  （8 会话 Fable 峰值合计 VmRSS ≈1,747,332 kB）；后台 Termux（本会话宿主）被系统无提示杀死
  （LMK，无弹窗），8 路负载在 Fable 内继续运行至 22:08+（Fable 本身未崩）。
- 用户观察：第 6 会话起小卡、Fibo 弹窗异常、第 8 个命令未回车即 Termux 卡死退出。
- 判定：属 8 路重负载 + 后台宿主同机运行的内存压力场景，Fable Rust 会话层在 4 会话硬指标下
  全部 PASS；8 会话余量数据按用户指示**不再补跑**，partial 数据不保留。
- 工单 27 剩余项：对比报告落盘、主终端全量回归清单、Java 去留结论（由用户拍板）——待用户决定
  是否继续。

2026-08-11 收尾（Java 会话层下线删除，用户拍板；Status → 已完成）：

**1. 验证数据/命令**

- 对比报告落盘：`.scratch/fable-v1/baseline/对比报告-27-rust-vs-java.md`
  （与 fable-v1/23 基线报告同目录；4 会话硬指标 PASS：崩溃 0 / ANR 0 / 180 万行零丢失 /
  surface 零强制重建；内存创建尖峰 ~1.48GB vs Java ~949MB，稳态相当；8 会话余量中止记录在案）。
- Java 会话层删除后构建验证：`:terminal-emulator:testDebugUnitTest` **152/152 PASS**
  （原 157 − ByteQueueTest 4 − 默认 Java 工厂测试 1）；`:app:testDebugUnitTest` 31 项 1 失败 =
  既有基线 FileReceiverActivityTest（Robolectric+JDK25，工单 15 已记录，非本次引入）；
  `:app:assembleDebug` BUILD SUCCESSFUL。
- APK 校验：无 `libtermux.so`；30 个 dex 全部 0 命中 `JavaFableSession/FableSessionSwitch/
  ByteQueue/fable_session_rust_enabled`；`libfable-session.so`（992,688B）保留。
  新装机包 sha256 `3c1912fafd84a755a0ec58eef995f522aaabb81d3b91a899c19901419d59b841`
  （未装机：按用户决定回归测试推迟到内存优化窗口）。

**2. 踩过的坑与解法**

- 复制粘贴压力期 0 事件 = 驱动长按坐标在无 IME 布局下落进扩展键工具条（几何失配），
  受控探针证明功能正常；23 基线同坐标有效是因为当时 IME 布局不同。
- 收尾 adb 断开（无线调试端口失效），logcat/meminfo 未按 23 口径全量抓取；
  崩溃/ANR 改用诊断日志重启标记 + 进程连续性 + 采样器 STOP 三重证据。
- 8 会话 prep 期间后台 Termux 被系统 LMK（整机内存压力），自动化中断；Fable 未崩，
  按用户指示中止不再补跑。

**3. 结论写回（对后续工单/决策的影响）**

- **Java 会话层下线删除（用户拍板，2026-08-11）**：删除 JavaFableSession/JavaFableSessionFactory/
  ByteQueue/JNI(PTY)/FableSessionSwitch（含设置页开关）/libtermux.so/termux.c；
  TerminalSession 工厂改为必传 RustFableSessionFactory。自有代码 = Kotlin + Rust；
  Zig（libghostty-vt）为引擎不算自有。
- **ADR-0008**：决策 5 过渡期结束（双实现 → 单一 Rust）；4 会话硬指标下重开条件不触发；
  内存创建尖峰为已知差异，转内存优化 backlog，不视为重开条件。
- **后续工单**：会话层缝（FableSession 接口族）保留为 Rust 唯一实现；内存优化窗口
  （backlog，见 `.scratch/fable-v1/内存优化-发散记录与共识.md`）首项 = 创建尖峰归属调查 +
  headless 后台会话（事件流方向）；TerminalEmulator（旧模拟器 Java）与验证切片探针（Java）
  属换核/壳重构收尾清理，不在本单范围。
- **事件流/AI 衔接**：六事件最小集继续由 Rust 侧产出，Kotlin 诊断订阅不变；
  headless 后台会话（只留事件流）是多 Agent 场景的下一步方向。
- **遗留（不阻塞本单）**：主终端全量回归推迟到内存优化窗口一并测试（用户决定）；
  portable-pty 0.9.0 上游 argv0 补丁跟踪；合成 emoji readline 宽度限制（工单 26 记录项）。
