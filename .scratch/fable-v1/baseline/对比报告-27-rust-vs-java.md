# fable-v1/27 对比报告：Rust 会话层 vs Java 基线（同场景）

> 对比口径 = fable-v1/23 基线报告（`.scratch/fable-v1/baseline/基线报告-2026-08-11.md`）：
> 同场景（4 会话并行 + 90×5000 行 + 轮间 7s）、同脚本（`start-run.sh`/`run-session.sh`/
> `stress-loop.sh`/`verify.sh`）、同指标表、同报告目录。
> 结论已由用户拍板（2026-08-11）：**Java 会话层下线删除**，代码已随工单 27 清理。

## 1. 环境信息（2026-08-11 核实）

| 项 | Java 基线（23） | Rust（27） |
| --- | --- | --- |
| 机型 / Android | <测试机型>（<测试机型>）/ Android 15（SDK 35）/ <厂商 ROM 版本> / RAM 11.4GB | 同左 |
| APK | `fable-session-25` sha `968c2f77…`（会话层 Java） | fable-app-26 最终包 sha `63273cce…`（会话层 Rust，构建期默认） |
| versionName / Code | `0.119.0-beta.3` / `1022` | 同左 |
| 会话层 | Java（Termux TerminalSession PTY，工单 26 抽出为 JavaFableSession） | Rust（libfable-session + portable-pty，工单 25/26） |
| 渲染/界面 | fable-render 集成 + v1 最小集 + Apple emoji | 同左（工单 26 修复版：WIDE 权威/2027 提前/CPR/去 motd） |
| 仓库 HEAD | `708718d` | `a46f01b`（fable-app `426091d`） |

## 2. 运行窗口

| 项 | 值 |
| --- | --- |
| 运行日期 | 2026-08-11 |
| Rust 4 会话窗口 | 21:42:44 → 21:54:07（11:23，≥10 分钟 ✅） |
| 会话数 / 每会话输出 | 4 × 450,000 行（90 轮 × 5000 行，轮间 7s） |
| 自动化操作 | 4 周期（切换/滚动；复制粘贴见第 3 节坑记录） |

## 3. 稳定性指标（4 会话）

| 指标 | Java 基线（23） | Rust（27） | 判定 |
| --- | --- | --- | --- |
| 崩溃 | 0 | **0**（诊断日志窗口 0 重启标记；4 会话全 DONE） | PASS |
| ANR | 0 | **0**（无 ANR 迹象；logcat 因收尾 adb 断开未全量抓取，用进程连续性 + 采样 STOP 佐证） | PASS |
| 卡顿 / surface 强制重建 | 0（压力期） | **0**（窗口内 `autoRecreateSurface attempt`=0、`forcing recovery`=0；35 次切换 surface 生命周期全部正常） | PASS |
| 切换 | 36 次 | 35 次（attachSession switch；初始 attach 不计） | 相当 |
| 复制/粘贴 | 各 16 次 | 压力期 0（驱动几何失配，见坑 1）；受控探针确认长按选择/复制/粘贴事件链完整 | 功能 PASS，计数不可比 |
| 输出完整性 | 4/4 PASS | **4/4 PASS**（sha256 与期望流逐字节一致、行数 450000、首末行正确） | PASS |

## 4. 内存指标（4 会话，主进程 VmRSS/PSS）

| 阶段 | Java 基线（23） | Rust（27） |
| --- | --- | --- |
| 起点（rel 0） | 677MB / PSS 562MB | 694MB / PSS 558MB |
| 会话创建期（rel 20–50s） | 616→724MB 缓升 | **1,152→1,493MB 尖峰**（s2/s3 创建瞬间） |
| 持续输出期（rel 100–300s） | 936–947MB（峰值 949MB @ rel 235） | 1,177→886MB（回落） |
| 收尾（rel 400–680s） | 601–678MB | 638–653MB |
| 合计峰值 | 966,228 kB（≈944MB） | 1,512,832 kB（≈1,477MB，@ rel 31s） |
| 主进程 PSS 峰值 | 835,849 kB | 1,313,019 kB |

要点：Rust 的劣势 = **会话创建瞬间一次性尖峰（+~540MB），约 4 分钟回落**；稳态与 Java 相当或更低。
尖峰归属未坐实（缺尖峰时刻 meminfo/malloc profile），已记入内存优化 backlog
（`.scratch/fable-v1/内存优化-发散记录与共识.md`）。

## 5. 8 会话余量数据（中止 · 记录不判定）

| 项 | 值 |
| --- | --- |
| 会话数 / 每会话输出 | 8 × 450,000 行（prep 阶段即中止） |
| Rust 主进程峰值 | 1,721,700 kB（≈1.68GB，@ rel ~30s 创建期） |
| 结果 | 8 路重负载 + 后台 Termux 同机运行，设备内存压力顶满，**后台 Termux 被 LMK 无提示杀死**；Fable 本身未崩（负载继续写盘） |
| 判定 | 按用户指示不再补跑；该场景属"内存档位超限"，归入内存优化 backlog |

## 6. 主终端全量回归

按用户决定（2026-08-11）**推迟到内存优化窗口一并测试**，不在本单重复真机测试。
已覆盖部分：工单 26 真机验收（Rust 模式打开即终端/输入回显/exit 收尾/多会话/滚动/复制粘贴/
字号/主题/键盘/中文宽光标/emoji/去欢迎语 + Java 模式零回归，均在 fable-app-26 最终包上通过）；
本次受控探针复验复制粘贴事件链正常（startTextSelectionMode → actionModeStarted →
copyModeChanged → toolbarShow）。清单见 `.scratch/fable-v1/baseline/对比报告模板-27.md` 第 7 节。

## 7. 原始证据

| 证据 | 位置 |
| --- | --- |
| Rust 4 会话真值 + 采样 | `.scratch/fable-v1/baseline/results-27-rust4/data/`（s1~s4.txt 各 6.75MB、mem-samples.tsv、run.log） |
| Rust verify 输出 | `results-27-rust4/verifybase/verify-local.txt`（18 PASS / 0 FAIL） |
| 截图 | `results-27-rust4/shot-{3,6,9,12}m.png` |
| 驱动日志 | `results-27-rust4/drive.log` |
| Java 基线 | `results-20260811-0512/`（含 meminfo.txt：Native Heap 分配 1.33GB、Graphics ~570MB、总 RSS 1.06GB、swap ~964MB） |
| 8 会话 partial | `~/storage/downloads/fable-baseline-27/current/`（不保留，随下次 start-run 归档） |

## 8. 坑记录

1. **压力期复制粘贴 0 事件的根因是驱动几何失配，不是应用缺陷**：`adb-drive.sh` 长按坐标
   `(540, 1440)` 在无输入法布局下落在扩展键工具条（y=1312–1516）而非终端文本区；
   工单 23 同坐标有效是因为当时 IME 布局不同。受控探针（长按终端区）事件链完整，功能正常。
2. 收尾阶段无线调试连接断开（Termux 宿主此前崩溃/端口失效），logcat/meminfo 未按 23 口径全量抓取；
   崩溃/ANR 判定改用诊断日志重启标记 + 进程连续性 + 采样器 STOP 三重证据。
3. 8 会话 prep 期间宿主 Termux 被系统回收（LMK），自动化会话中断；属整机内存压力场景，
   非 Fable 崩溃（负载继续运行），按用户指示中止并记录。

## 9. 对比结论与 Java 去留（用户拍板）

### 9.1 两模式差异

- 稳定性/完整性：4 会话硬指标两者均 PASS（崩溃 0、ANR 0、输出零丢失、surface 零强制重建）。
- 内存：Rust 创建尖峰明显（峰值 ~1.48GB vs ~949MB，+~540MB），稳态相当或更低（收尾均 ~640MB）；
  尖峰归属列入内存优化 backlog 首项调查。
- 行为一致性（工单 26 已对齐）：$0="-bash" 与 profile 加载一致；termios（IUTF8、-ixon/-ixoff）一致；
  已知差异：Rust getPid()==0（上下文菜单显示 Kill Process (0)，功能正常）、getCwd()=创建时目录。
- 依赖：portable-pty 0.9.0 带本地 argv0 补丁（约 15 行，`spike-session/vendor/`），后续升级需确认上游支持。

### 9.2 Java 会话层去留决定

**下线删除（用户拍板，2026-08-11）**：保持代码纯洁性——会话层唯一实现 = Rust
（Kotlin + Rust 为自有语言；Zig/libghostty-vt 为引擎，不算自有）。已删除：
`JavaFableSession` / `JavaFableSessionFactory` / `ByteQueue` / `JNI`（PTY）/ `FableSessionSwitch`
（设置页开关）/ `libtermux.so`（jniLibs）/ `terminal-emulator/src/main/jni/`（termux.c）；
`TerminalSession` 工厂改为必传 Rust 实现。

### 9.3 影响

- **ADR-0008**：决策 5（双实现并行 + 切换开关）已到期——Java 实现删除，过渡期结束；
  重开条件"Rust 不优于基线"在 4 会话硬指标下不触发（内存尖峰为已知差异，非重开条件，列 backlog 优化）。
- **后续工单**：会话层缝（FableSession/Factory/Spec/Callbacks）保留为单一 Rust 实现的抽象；
  Rust 会话层相关后续 = 内存优化窗口（创建尖峰、headless 后台会话）；TerminalEmulator（旧模拟器，
  仍为 Java）与验证切片探针（Java）不在会话层范围，属换核/壳重构收尾时清理。
- **事件流/AI 衔接**：六事件最小集继续由 Rust 侧产出（command_started/output_chunk/
  command_finished/exit_code/session_created/session_closed），Kotlin 诊断订阅路径不变；
  headless 后台会话（只留事件流）将是多 Agent 场景的下一步方向（见内存优化发散记录 C 线）。
