# fable-v1/23 并发基线采集包（Java 会话层）

> 任务本体：`.scratch/fable-v1/issues/23-会话层并发基线采集.md`。本目录是采集包（场景脚本 + 真机运行副本 + 报告模板），供工单 23 真机采集与工单 27 同场景对比复用。

## 是什么 / 不是什么

- **是什么**：可重复的 12 分钟压力场景（4 会话并行 + 长输出 + 频繁切换/滚动 + 复制粘贴）+ 程序化采集工具（内存采样 / 输出完整性校验 / 诊断日志窗口统计）+ 报告模板。
- **不是什么**：不改任何功能代码；不重启 App；不杀会话。

## 目录结构

```
.scratch/fable-v1/baseline/
├── README.md               ← 本文件
├── scenario-协议.md         ← 12 分钟操作协议（27 同场景复用的口径来源）
├── 报告模板.md              ← 基线报告模板（环境信息已预填）
└── scripts/
    ├── start-run.sh        ← 步骤 1：准备 run 目录 + 启动内存采样器
    ├── run-session.sh      ← 步骤 2：每会话跑确定性长输出（约 12 分钟）
    ├── sampler-loop.sh     ← 后台内存采样（5s 间隔，app 进程 VmRSS/PSS）
    ├── verify.sh           ← 步骤 4：输出完整性 + 采样 + 日志窗口 全量校验
    ├── analyze.sh          ← 内存峰值分析（verify 调用）
    └── grep-log.sh         ← fable-render-debug.log 窗口事件统计（verify 调用）
```

真机运行副本（自动生成的可执行镜像）：
`/storage/emulated/0/Download/fable-baseline-23/`

## 前提（已核实 2026-08-11）

- 已验收装机包 = 工单 25 真机验收包（含主终端 fable-render + v1 最小集 + emoji 切换修复；会话层仍为 Java，未动）：
  - `fable-app_apt-android-7-debug_arm64-v8a.apk`（= `fable-session-25_arm64-v8a.apk`）
  - sha256 `968c2f7701934eca747d8363b33e52555651bf64d90a71ef09cde95682fd5992`
  - versionName `0.119.0-beta.3` / versionCode `1022`，包名 `com.gph.fable`
  - 构建产物时间 2026-08-11 03:07（UTC+8）
- 设备：<测试机型>/ Android 15（SDK 35）/ <厂商 ROM 版本> / arm64-v8a / RAM 11.4GB
- 仓库 HEAD（采集时）：`708718d`（2026-08-11）
- 真机上已安装该 APK，且能开 4 个会话（抽屉多会话）、长按选择复制/粘贴、触摸滚动

## 运行步骤

### 模式 A：手动（无需无线调试）

1. 在 Fable 主终端任一会话运行：
   `bash /storage/emulated/0/Download/fable-baseline-23/start-run.sh`
2. 在本会话运行：`bash /storage/emulated/0/Download/fable-baseline-23/run-session.sh s1`
   （脚本自动转后台，输出仍显示；手动加 `&` 也兼容）
3. 新建 3 个会话，分别运行：
   `bash /storage/emulated/0/Download/fable-baseline-23/run-session.sh s2`（s3 / s4 同理）
4. 按 `scenario-协议.md` 做 12 分钟切换/滚动/复制粘贴，按 3/6/9/12 分钟截图；
   异常（崩溃/ANR/卡顿）发生时记下时刻并截图。
5. 4 个会话都出现 `DONE` 后（约 12 分钟），任一会话运行：
   `bash /storage/emulated/0/Download/fable-baseline-23/verify.sh`
   把输出原样复制出来（作为验收数据）。

### 模式 B：adb 全自动（用户只做一次无线调试配对，其余我来）

1. 手机：设置 → 开发者选项 → 无线调试 → 开启；点「使用配对码配对设备」，把端口与配对码发给我。
2. 我在旧 Termux（本环境）配对连接后，依次运行：
   `bash .scratch/fable-v1/baseline/scripts/adb-drive.sh prep`（开 4 会话 + 起负载）
   `bash .../adb-drive.sh stress`（12 分钟自动化切换/滚动/复制粘贴 + 截图）
   `bash .../adb-drive.sh finish`（verify + logcat + meminfo + 拉结果）
3. 我写回工单 Comments（含截图与 logcat 原始证据）。

> 注意（模式 A）：`run-session.sh` 自动后台跑，会话保持可交互（粘贴直接生效）。运行期间不要
> 关会话、不要重跑 `start-run.sh`、不要 `kill %1`。误操作导致某会话文件不完整时，
> verify 会 FAIL，需要整轮重跑（旧 run 目录会被归档，不删除）。

## 产物（`/storage/emulated/0/Download/fable-baseline-23/current/`）

| 文件 | 含义 |
| --- | --- |
| `s1.txt` ~ `s4.txt` | 4 会话输出真值（= 写入各自 PTY 的同一字节流），每份 450,000 行 |
| `s1.done` ~ `s4.done` | 各会话完成标记 |
| `mem-samples.tsv` | 5s 间隔内存采样（pid/进程名/VmRSS/PSS） |
| `sampler.log` | 采样汇总（每样本总 VmRSS + 峰值 + STOP 行） |
| `run.log` / `run.txt` | 各会话起止、运行窗口（epoch） |
| `expected-sN.txt` | verify 生成的期望流（完整性真值对照） |
| `log-window.txt` | 诊断日志窗口内事件（供报告引用） |

## 回传要求（写回工单 23 Comments 前）

1. `verify.sh` 输出全文（PASS/FAIL + 内存分析 + 日志窗口统计）
2. 填好的 `报告模板.md`（崩溃/ANR/卡顿次数与时刻、截图清单、目视检查结果）
3. 截图：拷贝到 `Download/fable-baseline-23/screenshots/` 或记录路径（<厂商 ROM> 相册
   `DCIM/Screenshots/`），报告里列文件名即可

## 已知限制

- **logcat 不可用**：Termux/Fable 无 shell 权限，`adb logcat` 被 <厂商 ROM> <系统分身空间> 拦截；
  原始日志用 App 自写诊断日志替代：`Download/fable-render-debug.log.txt`
  （surface 生命周期 / 选择 / 重建事件，带 epoch-ms 时间戳，verify 自动按窗口统计）。
- **PSS 可能不可读**：`/proc/<pid>/smaps_rollup` 受限时记 `NA`，主内存指标用
  VmRSS 合计峰值（同 UID 进程之和，含终端 shell 自身进程——均属 app 进程树）。

## 与工单 27 的关系

- 27 用**同一脚本、同一参数、同一协议**在 Rust 会话层重跑：`start-run.sh` →
  `run-session.sh s1..s4` → 12 分钟协议 → `verify.sh`，填同一张 `报告模板.md`。
- 对比口径定义见 `报告模板.md` 末尾「对 fable-v1/27 的对比口径」。
