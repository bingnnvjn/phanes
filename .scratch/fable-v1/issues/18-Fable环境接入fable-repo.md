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
