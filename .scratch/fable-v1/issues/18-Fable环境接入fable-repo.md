# 18 — Fable 环境接入 fable-repo（真机补装）

**What to build:** 把 Fable 环境的包源从官方 termux-main（com.termux 前缀，不可用）切换到 fable-repo：分发仓库公钥到系统信任区、配置 sources.list 指向扁平仓库并停用官方源，真机验证 `apt update` 与首批清单补装；与 fable-v1/02 的 Phase B 顺序衔接（先恢复 + 本地装归档内包，再用仓库补装缺口）。

**Blocked by:** fable-v1/17

**Status:** 已完成

## 验收清单

- [x] 公钥分发：仓库 GPG 公钥写入 Fable 系统信任区（apt 验签路径生效，不依赖 trusted=yes）
- [x] 源配置：sources.list 指向 fable-repo；官方 termux-main 源停用，apt 不再使用
- [x] `apt update` 成功且无错误；仓库签名验证通过（非 trusted 模式）
- [x] 抽查 `apt install`（git、nodejs、openjdk-25、rust 等）成功，安装路径落在 Fable 前缀
- [x] 装后可用：git --version、node --version、java -version 等可执行且输出正常
- [x] 与工单 02 顺序一致：先跑 Phase B 恢复脚本与本地 dpkg 安装（归档内包），本单负责剩余缺口补装；补装后原缺失清单可对照核销

## Comments

2026-08-09 建单（决策窗口 5：Fable 包安装源，ADR-0005；依赖 fable-v1/17 的仓库端产物）。

2026-08-13 真机验收完成：

### 验证数据 / 命令

- 工单 17 stable 仓库已核实为 `fable-repo-current`（2026-08-13 03:58 UTC 更新，310 个 deb）；真机一键安装摘要（2026-08-13 15:05:52 +0800）全部步骤 PASS。
- 包源为 `https://ghfast.top/https://github.com/bingnnvjn/fable-bootstrap/releases/download/fable-repo-current`；公钥指纹 `97291249E5BE2D529939F7F7A960D6CE7BA2DBED`；`InRelease` 经 `gpgv` 验签通过。官方 `termux-main` 已停用，未使用 `trusted=yes`。
- `apt-get -f install -y` 修复 Phase B 遗留依赖后，`apt-get install -y` 首批 21 个目标包通过；日志记录 `git 2.55.0`、`nodejs 26.4.0`、`openjdk-17/21/25`、`rust 1.97.1`、`clang 21.1.8-3`、`runit`、`termux-services` 等均完成配置。
- 脚本命令核验 `git`、`node`、`java`、`rustc` 全部 PASS。
- 原 `missing-packages.txt` 117 项中，本轮补装核销 57 项；余 60 项为 ffmpeg/Vulkan/proot/redis 等非首批范围，继续按工单 17 的按需构建机制补齐。

### 踩过的坑与解法

- 旧迁移快照未包含接入脚本：将一键脚本放入共享迁移目录，并保留仓库副本。
- 初版未正确注释官方源、且 `tee` 曾掩盖 apt 失败退出码：修正 sed 表达式，启用 `pipefail` 并改用 `apt-get`。
- 直连 GitHub Release 下载大 deb 多次断线并产生 `jwt:expired`：apt 源改用 `ghfast.top` 下载镜像；完整 apt 输出写日志，终端只显示阶段和 PASS/FAIL。

### 结论写回

- fable-repo 已成为 Fable 环境的可验签包源，首批自举/日常缺口已补齐；fable-v1/02 可继续完成 JDK/git/node 相关构建验证与 runit 服务重配。
- 其余 60 项缺失包不阻塞本单，后续由 fable-v1/17 的按需构建/发布机制处理；不得重新启用官方 `termux-main`。
