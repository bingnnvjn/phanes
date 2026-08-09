# 19 — Fable 侧准备 android-sdk（工单 02 构建验收前置）

**What to build:** 在 Fable 环境准备可用的 android-sdk（platforms + build-tools，必要时含 platform-tools/cmdline-tools），来源为从旧 Termux 经共享存储拷贝或直接下载；同步 fable-app 的 local.properties 指向 Fable 侧 sdk 路径。为工单 02 的 CODEX 构建验收与后续 fable-app 构建铺路；不依赖 fable-repo（工单 17/18），可与 CI 构建并行。

**Blocked by:** None

**Status:** 待开工

## 验收清单

- [ ] android-sdk 就位于 Fable 侧：platforms（android-30/34/35/36）与 build-tools（30.0.2/34.0.0/36.0.0）与旧侧一致（2026-08-09 旧侧核实，约 1.2G）
- [ ] 工具抽查：build-tools 36.0.0 的 `aapt2 dump badging` 能正常输出（原生可执行，不需 JDK）；apksigner 验签等 JDK 就位后（工单 02/18 后）再验
- [ ] fable-app 的 `local.properties` 指向 Fable 侧 sdk 路径（不再指向 com.termux 旧路径）
- [ ] 拷贝过程旧侧 sdk 原样保留（只复制，不移动/删除）
- [ ] 结果写回 Comments：来源、体积、版本清单、抽查输出

## Comments

2026-08-09 建单（并行任务：工单 17 构建期不空等；工单 02 剩余验收"android-sdk 属不迁清单，需在 Fable 侧另备"的落地工单）。
