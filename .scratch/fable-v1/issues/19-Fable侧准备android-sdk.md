# 19 — Fable 侧准备 android-sdk（工单 02 构建验收前置）

**What to build:** 在 Fable 环境准备可用的 android-sdk（platforms + build-tools，必要时含 platform-tools/cmdline-tools），来源为从旧 Termux 经共享存储拷贝或直接下载；同步 fable-app 的 local.properties 指向 Fable 侧 sdk 路径。为工单 02 的 CODEX 构建验收与后续 fable-app 构建铺路；不依赖 fable-repo（工单 17/18），可与 CI 构建并行。

**Blocked by:** None

**Status:** 挂起（待工单17/18补装原生aapt2后补验）

## 验收清单

- [x] android-sdk 就位于 Fable 侧：platforms（android-30/34/35/36）与 build-tools（30.0.2/34.0.0/36.0.0）与旧侧一致（2026-08-09 旧侧核实，约 1.2G；Fable 侧落盘 1.2G / 56,146 条目，与源一致）
- [ ] 工具抽查：build-tools 36.0.0 的 `aapt2 dump badging` 能正常输出——**实测 FAIL（2026-08-09）**：Google build-tools 的 aapt2 为 x86_64 ELF，aarch64 Exec format error；原生 aapt2 需 fable-repo（工单 17/18）补装后补验（与 JDK/apksigner 同列后置）；apksigner 验签等 JDK 就位后（工单 02/18 后）再验
- [x] fable-app 的 `local.properties` 指向 Fable 侧 sdk 路径（不再指向 com.termux 旧路径；仓库版与 Fable 侧均已改）
- [x] 拷贝过程旧侧 sdk 原样保留（只复制，不移动/删除；打包前后条目一致 56,146）
- [x] 结果写回 Comments：来源、体积、版本清单、抽查输出（见下）

## Comments

2026-08-09 Fable 侧执行完成（用户在 Fable 终端跑 `sdk-unpack-verify.sh`，报告
`/data/data/com.gph.fable/files/home/<仓库外暂存目录>/sdk-verify-20260809-210410.txt`）：

### 验收结果（Fable 侧）

- 解包：OK；落盘 1.2G / 56,146 条目（与源 tar 条目一致）。
- 结构：platforms android-30/34/35/36、build-tools 30.0.2/34.0.0/36.0.0、
  cmdline-tools / platform-tools / licenses / ndk 全 PASS（结构验收 ALL PASS）。
- aapt2 抽查：**FAIL（预期内，已记录根因）**——`build-tools/36.0.0/aapt2` 为
  x86_64 ELF（e_machine=62），aarch64 `Exec format error`；PATH 无原生 aapt2。
  补验路径：fable-repo（工单 17/18）装 aapt2 包后跑 `aapt2 dump badging`。
- local.properties（Fable 侧）：PASS `sdk.dir=/data/data/com.gph.fable/files/home/android-sdk`。
- 旧侧：只读打包，未删除/移动（打包前后条目数 56,146 不变）。
- 备包：`/storage/emulated/0/Download/fable-android-sdk-2026-08-09/`
  （tar sha256 `7b420024…`；仓库镜像 `.scratch/fable-v1/migration/`）。

### 结论

除 aapt2 抽查项外全部 PASS。该验收项假设"sdk 内 aapt2 原生可执行"不成立：
Google build-tools 二进制是 x86_64，旧侧同样不能跑（构建一直走
`android.aapt2FromMavenOverride` 指向 Termux 原生 aapt2）。Fable 侧构建不受本项
阻塞（gradle.properties 覆盖已随 restore 改写为 Fable 前缀）；原生 aapt2 包属
工单 02 缺失清单，由工单 17/18 补装后闭环，与 JDK/apksigner 同路径。Status 置
挂起（待 17/18），不把未验项当完成。

2026-08-09 旧侧打包完成（待用户在 Fable 终端执行解包验收）：

### 旧侧备包数据（2026-08-09 核实）

- 源：`<仓库外 android-sdk>`，1,049,916,201 B（du -sb），
  56,146 条目；打包后 `android-sdk.tar.gz` 547,882,103 B，56,146 条目（与源一致）。
- 版本清单：platforms android-30/34/35/36；build-tools 30.0.2/34.0.0/36.0.0；
  cmdline-tools / platform-tools / licenses / ndk（21.4.7075529 22.1.7171670
  28.2.13676358）全量拷贝。
- 落盘：`/storage/emulated/0/Download/fable-android-sdk-2026-08-09/`
  （tar sha256 `7b420024…`；脚本/README/基线/校验和同目录，仓库镜像在
  `.scratch/fable-v1/migration/`）。
- 旧侧只读打包，未删除/移动任何数据（打包前后条目数一致）。
- local.properties（仓库版）已改为 `sdk.dir=/data/data/com.gph.fable/files/home/android-sdk`。

### 坑（aapt2 验收项需后置，与 JDK/apksigner 同理）

- build-tools 内 aapt2（30.0.2/34.0.0/36.0.0）是 Google **x86_64 ELF**（e_machine=62），
  aarch64 手机 `Exec format error`，旧侧同样不能跑（本环境实测）。
  旧侧构建一直用 Termux 原生 aapt2（`fable-app/gradle.properties` 的
  `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`，
  restore-fable.sh 会改写成 Fable 前缀）。
- 原生 aapt2 属工单 02 缺失清单（117 包含 aapt2），需 fable-repo（工单 17/18）
  补装后补验 `aapt2 dump badging`；apksigner 同理等 JDK（工单 02/18）。
- 旧侧原生 aapt2 参考输出（证明"能正常输出"的形态，供 17/18 后对比）：
  `aapt2 version` → `Android Asset Packaging Tool (aapt) 2.20-android-16.0.0_r4`；
  `aapt2 dump badging` arm64 debug APK → `package: name='com.gph.fable' …`。

### 待用户（Fable 终端执行）

```bash
bash /storage/emulated/0/Download/fable-android-sdk-2026-08-09/sdk-unpack-verify.sh
```

脚本自动：解包到 `$HOME/android-sdk` → 版本结构验收 → aapt2 抽查（预期记录 x86_64
FAIL 与原因）→ 改写 Fable 侧 `CODEX/Fable/fable-app/local.properties` → 汇总报告
（`$HOME/<仓库外暂存目录>/sdk-verify-*.txt`），把输出粘贴回本工单。

2026-08-09 建单（并行任务：工单 17 构建期不空等；工单 02 剩余验收"android-sdk 属不迁清单，需在 Fable 侧另备"的落地工单）。
