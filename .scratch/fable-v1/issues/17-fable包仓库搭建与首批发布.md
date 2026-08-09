# 17 — Fable 包仓库（fable-repo）搭建与首批发布

**What to build:** 在 fable-bootstrap 公开仓库上搭建 fable-repo：支持按需构建指定包（含依赖闭包），把构建出的 debs 组织成扁平 apt 仓库（Packages / Release / InRelease，GPG 签名），以全量快照发布到 GitHub Releases；首批按"自举 + 日常"清单构建并发布。本单只做仓库端；Fable 环境侧接入与真机补装见 fable-v1/18。

**Blocked by:** None

**Status:** 进行中

## 验收清单

- [ ] 按需构建：workflow 输入包名列表即可构建指定包及其依赖闭包；actions/cache 增量生效（二次构建明显变快）
- [ ] 发布产物完整：最新 Release 含全部 debs + Packages/Packages.gz + Release + InRelease
- [ ] 签名有效：InRelease 用仓库公钥 `gpg --verify` 通过，Packages 与 debs 与签名一致
- [ ] 索引可解析：离线自检（PASS/FAIL 断言）——apt 逻辑可列出首批清单包，版本与依赖解析正确，无 com.termux 前缀路径包
- [ ] 首批清单 21 个全部构建成功并出现在最新快照（2026-08-09 定稿：git、gh、openssh、openssh-sftp-server、nodejs、npm、openjdk-17/-x、openjdk-21/-x、openjdk-25/-x、rust、rust-std-aarch64-linux-android、clang、runit、termux-services、wget、zip、python-pip、jq）
- [ ] 追加构建：同一 workflow 再次输入新包名可增量构建并发布新快照（"按需补装"闭环成立）
- [ ] 安全红线：GPG 私钥只存在于 Actions secret，不出现在仓库、日志、Release 或任何提交中；workflow permissions 最小化（沿用工单 06 安全清单）

## Comments

2026-08-09 建单（决策窗口 5：Fable 包安装源，ADR-0005）。

2026-08-09 实施启动：已完成密钥生成与 secret 设置（ed25519，指纹 `97291249E5BE2D529939F7F7A960D6CE7BA2DBED`，私钥只存 `FABLE_REPO_GPG_PRIVATE_KEY`）；脚本与 workflow 初稿完成，本地端到端验证通过（种子 debs 组装 + gpgv 验签 + apt 2.8.1 真实解析 13 包全 PASS、错钥拒绝退出码 100）。下一步：推送 fable-bootstrap → CI 冒烟 → 首批 21 包。

2026-08-09 CI 冒烟与增量验证通过（主线程）：

- 提交：`5278124`（初版）、`770a80a`（组装移到 runner，容器 AppArmor 禁 apt sandbox）、`29b36ef`（signed-by 绝对路径）、`21429fe`（deb 文件名净化 + Filename 去 `./`）
- 冒烟 Release：`fable-repo-2026.08.08-r1`（wget/zip/jq + 闭包 59 资产）、`r2`（追加 tree，61 资产，旧包全保留；二次构建 ~4min vs 首次 ~13min，actions/cache 增量生效）
- 生产快照独立验证（本机 apt 2.8.1 + 真实公钥）：gpgv InRelease/Release.gpg 通过；SHA256SUMS 全 OK；apt update 0 退出；wget/zip/jq/openssl/ca-certificates-java 候选版本正确（含 epoch `1:3.6.3`）；`apt-get download` 拉取成功；dpkg-deb 扫描 0 处 com.termux 路径
- 关键坑（实测定位）：① builder 容器 AppArmor 禁 apt 的 setgroups/setuid，容器内装不了 dpkg-dev/apt-utils → 组装/验签/apt 自检全部移到 runner 宿主；② GitHub Releases 会把资产名里的 `:`（epoch 版本）改名（实测 `openssl_1:3.6.3` → `openssl_1.3.6.3`）→ 组装时 deb 文件名净化到 `[A-Za-z0-9._-]`，apt 扁平仓库按 Packages 的 Filename 字段抓取（http 实测实证），语义不受影响；③ 新版 apt 的 signed-by 拒绝相对路径 → realpath 归一化；④ 本机 apt 2.8.1 的 file:// method 下载时本地文件名按包版本命名（曾误导判断），http 请求日志证明实际按 Filename 抓取
- 首批 21 包全量构建已派发（run `31280231793`），监控中
