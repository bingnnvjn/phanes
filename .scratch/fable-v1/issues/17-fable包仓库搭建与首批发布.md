# 17 — Fable 包仓库（fable-repo）搭建与首批发布

**What to build:** 在 fable-bootstrap 公开仓库上搭建 fable-repo：支持按需构建指定包（含依赖闭包），把构建出的 debs 组织成扁平 apt 仓库（Packages / Release / InRelease，GPG 签名），以全量快照发布到 GitHub Releases；首批按"自举 + 日常"清单构建并发布。本单只做仓库端；Fable 环境侧接入与真机补装见 fable-v1/18。

**Blocked by:** None

**Status:** 已完成

## 验收清单

- [x] 按需构建：workflow 输入包名列表即可构建指定包及其依赖闭包；actions/cache 增量生效（二次构建明显变快）
- [x] 发布产物完整：stable Release `fable-repo-current` 含 310 个 debs + Packages/Packages.gz + Release + InRelease；日期审计快照为 `fable-repo-2026.08.13-r3`
- [x] 签名有效：InRelease/Release.gpg 用仓库公钥 `gpgv` 通过，Release/Packages 哈希一致
- [x] 索引可解析：真实 apt 使用固定 stable URL 完成 update，21 个首批包均解析出候选版本，无 `com.termux` 前缀路径
- [x] 首批清单 21 个全部构建成功并出现在 stable 快照（git、gh、openssh、openssh-sftp-server、nodejs、npm、openjdk-17/-x、openjdk-21/-x、openjdk-25/-x、rust、rust-std-aarch64-linux-android、clang、runit、termux-services、wget、zip、python-pip、jq）
- [x] 追加构建：同一 workflow 输入 `openjdk-25 openjdk-25-x` 后保留既有快照并发布 stable tag，"按需补装"闭环成立
- [x] 安全红线：GPG 私钥只存在于 Actions secret；仓库扫描、Release 资产与日志未发现私钥；workflow 顶层 permissions 为空，build/publish 分别最小授权

## Comments

2026-08-09 建单（决策窗口 5：Fable 包安装源，ADR-0005）。

2026-08-09 实施启动：已完成密钥生成与 secret 设置（ed25519，指纹 `97291249E5BE2D529939F7F7A960D6CE7BA2DBED`，私钥只存 `FABLE_REPO_GPG_PRIVATE_KEY`）；脚本与 workflow 初稿完成，本地端到端验证通过（种子 debs 组装 + gpgv 验签 + apt 2.8.1 真实解析 13 包全 PASS、错钥拒绝退出码 100）。下一步：推送 fable-bootstrap → CI 冒烟 → 首批 21 包。

2026-08-09 CI 冒烟与增量验证通过（主线程）：

- 提交：`5278124`（初版）、`770a80a`（组装移到 runner，容器 AppArmor 禁 apt sandbox）、`29b36ef`（signed-by 绝对路径）、`21429fe`（deb 文件名净化 + Filename 去 `./`）
- 冒烟 Release：`fable-repo-2026.08.08-r1`（wget/zip/jq + 闭包 59 资产）、`r2`（追加 tree，61 资产，旧包全保留；二次构建 ~4min vs 首次 ~13min，actions/cache 增量生效）
- 生产快照独立验证（本机 apt 2.8.1 + 真实公钥）：gpgv InRelease/Release.gpg 通过；SHA256SUMS 全 OK；apt update 0 退出；wget/zip/jq/openssl/ca-certificates-java 候选版本正确（含 epoch `1:3.6.3`）；`apt-get download` 拉取成功；dpkg-deb 扫描 0 处 com.termux 路径
- 关键坑（实测定位）：① builder 容器 AppArmor 禁 apt 的 setgroups/setuid，容器内装不了 dpkg-dev/apt-utils → 组装/验签/apt 自检全部移到 runner 宿主；② GitHub Releases 会把资产名里的 `:`（epoch 版本）改名（实测 `openssl_1:3.6.3` → `openssl_1.3.6.3`）→ 组装时 deb 文件名净化到 `[A-Za-z0-9._-]`，apt 扁平仓库按 Packages 的 Filename 字段抓取（http 实测实证），语义不受影响；③ 新版 apt 的 signed-by 拒绝相对路径 → realpath 归一化；④ 本机 apt 2.8.1 的 file:// method 下载时本地文件名按包版本命名（曾误导判断），http 请求日志证明实际按 Filename 抓取
- 首批 21 包全量构建已派发（run `31280231793`），监控中

2026-08-13 完成记录：

- 最终 workflow 提交：远端 `e95cf631`（通过 GitHub Contents API 推送；本地 Git HTTPS 推送遇 TLS EOF），核心改动另有本地提交 `dfe2192`。
- 最终成功 run：`31660896153`，输入 `openjdk-25 openjdk-25-x`，build/publish 全部 success；job 硬超时已收紧为 345 分钟，低于 GitHub 6 小时上限 15 分钟。
- 发布结果：日期审计快照 `fable-repo-2026.08.13-r3`，固定 stable tag `fable-repo-current`；stable 快照 310 个 deb、310 条 Packages 索引记录，包含首批 21 个目标包。
- 程序化验收：`gpgv` 验证 InRelease/Release.gpg 通过（ed25519 指纹 `97291249E5BE2D529939F7F7A960D6CE7BA2DBED`）；Release SHA256 与 Packages/Packages.gz 一致；真实 apt 使用 `https://github.com/bingnnvjn/fable-bootstrap/releases/download/fable-repo-current/` 执行 `apt update` 通过，21 个候选版本全部 PASS；Filename 扫描无 `./` 或 `com.termux`。
- 踩坑与解法：① `apr-util` 上游地址 404，后续缓存命中后恢复；② `libgnutls` 原地址被 GitHub runner 403，MIT 镜像超时，改用 Fossies 同 SHA256 镜像；③ 增量发布最初只上传新增闭包导致最新快照丢旧包，workflow 改为从 stable/最近 `fable-repo-*` 快照导入全部 deb 后再组装；④ 仓库同时发布 bootstrap Release，不能使用仓库级 `releases/latest/download/`，改为固定 `fable-repo-current` stable tag。
- 结论写回：工单 18/20 必须使用 `releases/download/fable-repo-current/`；后续增量构建继续复用 stable tag，不得从仓库级 latest 或 bootstrap Release 取种子。
