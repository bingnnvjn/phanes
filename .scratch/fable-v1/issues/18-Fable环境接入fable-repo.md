# 18 — Fable 环境接入 fable-repo（真机补装）

**What to build:** 把 Fable 环境的包源从官方 termux-main（com.termux 前缀，不可用）切换到 fable-repo：分发仓库公钥到系统信任区、配置 sources.list 指向扁平仓库并停用官方源，真机验证 `apt update` 与首批清单补装；与 fable-v1/02 的 Phase B 顺序衔接（先恢复 + 本地装归档内包，再用仓库补装缺口）。

**Blocked by:** fable-v1/17

**Status:** 进行中

## 验收清单

- [ ] 公钥分发：仓库 GPG 公钥写入 Fable 系统信任区（apt 验签路径生效，不依赖 trusted=yes）
- [ ] 源配置：sources.list 指向 fable-repo；官方 termux-main 源停用，apt 不再使用
- [ ] `apt update` 成功且无错误；仓库签名验证通过（非 trusted 模式）
- [ ] 抽查 `apt install`（git、nodejs、openjdk-25、rust 等）成功，安装路径落在 Fable 前缀
- [ ] 装后可用：git --version、node --version、java -version 等可执行且输出正常
- [ ] 与工单 02 顺序一致：先跑 Phase B 恢复脚本与本地 dpkg 安装（归档内包），本单负责剩余缺口补装；补装后原缺失清单可对照核销

## Comments

2026-08-09 建单（决策窗口 5：Fable 包安装源，ADR-0005；依赖 fable-v1/17 的仓库端产物）。

2026-08-13 实施启动：工单 17 的 stable 仓库已核实为 `fable-repo-current`（2026-08-13 03:58 UTC 更新，310 个 deb）；接入脚本采用签名公钥指纹 `97291249E5BE2D529939F7F7A960D6CE7BA2DBED`，开始进行 Fable 真机侧接入与补装验收。

2026-08-13 真机反馈：旧快照中的 `~/CODEX/Fable` 不含工单 20 之后新增的接入脚本，导致首次命令报“文件不存在”；`apt update` 仍命中官方 `termux-main`，直接安装 clang/nodejs/openjdk/rust 产生未满足依赖。已新增一键脚本 `fable-repo-install.sh`，同时复制到共享迁移目录：
`/storage/emulated/0/Download/fable-migration-2026-08-06/fable-repo-install.sh`。

脚本行为：默认幂等执行 Phase B 本地 deb 安装（不重跑 restore，避免覆盖 Fable home）→公钥校验→停用官方源→启用 `[signed-by=...]` 的固定 stable 源→`apt update`→安装首批 21 个包→核验 git/node/java/rustc。显式 `--restore` 才会重跑 restore。
