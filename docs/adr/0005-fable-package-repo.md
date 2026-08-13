# ADR-0005: Fable 包仓库（fable-repo）= 自建 fable 前缀扁平 apt 仓库

- 状态：已确认（2026-08-09，决策窗口 5 定案：Q1=GitHub Releases 扁平仓库、Q2=GPG 签名且私钥存 Actions secret（CI 自动签名）、Q3=按需构建、Q4=先迁移后建源；首批清单 21 个）
- 日期：2026-08-09
- 范围：Fable 环境（`com.gph.fable` 前缀）在 bootstrap 之后的**包安装/更新来源**；不涉及 App 构建链，不消费官方 termux-main 仓库。

## 背景

事实（2026-08-09 核实）：

- 官方 termux-main 的包以 `TERMUX_PREFIX=/data/data/com.termux/files/usr` 构建，路径硬编码；Fable 前缀为 `/data/data/com.gph.fable/files/usr`，直接 `apt install` 官方源会得到错误路径的包，不算"可用"（工单 02 已记录，`install-packages.sh` 含警示）。
- 旧环境 apt 2.8.1（2026-08-09 核实），Fable bootstrap 同期构建，apt/gpgv 为现代版本，ed25519 签名可支持（实施时复验）。
- 工单 06 已验证：termux-packages fork（`bingnnvjn/fable-bootstrap`）以 Fable 前缀重建 bootstrap 可行，Release 附 219 个 fable 前缀 .deb（`debs-aarch64.tar.gz`，119MB，可作包仓库种子）。
- 旧环境当前已装 227 个包（2026-08-09 `dpkg -l` 核实；08-06 快照为 224）；08-06 归档只覆盖 107 个，缺口含 git / gh / openssh / nodejs / npm / openjdk-17·21·25 / rust / clang / runit / termux-services 等自举与日常必需包。
- `fable-bootstrap` 目前只有一个手动触发的 bootstrap 全量构建 workflow（约 1h15m、无缓存），**没有**"按需构建指定包"能力，也**没有** apt 仓库发布机制（无 Packages 索引、无签名）。
- GitHub Releases 单资产上限 2GB；用专用 stable tag `releases/download/fable-repo-current/<name>` 提供稳定 URL（避免同仓 bootstrap Release 改变仓库级 `latest`）；GitHub Pages 有 1GB 站点/100MB 单文件软限制，openjdk 等大 deb 有越限风险。

## 决策

1. **Fable 自建包仓库（fable-repo）**：以 `com.gph.fable` 前缀构建的 apt 仓库，作为 Fable 环境唯一的装包/更新源；v1 阶段不做 termux-main 全量镜像或定期同步。
2. **仓库形态 = 扁平 apt 仓库（flat repo），托管于 fable-bootstrap 的 GitHub Releases**：每次发布 = 全量快照（全部 .deb + `Packages`/`Packages.gz` + `Release`/`InRelease`），另更新固定 stable Release tag `fable-repo-current`；Fable 侧 sources.list 指向 `https://github.com/bingnnvjn/fable-bootstrap/releases/download/fable-repo-current/`（稳定 URL，`apt update` 自动取最新快照）。
3. **GPG 签名（私钥归 CI）**：自签 GPG，密钥类型 ed25519（实施时复验 gpgv 兼容，必要时回退 rsa4096）；**私钥只存 GitHub Actions secret，workflow 构建后自动 `gpg --clearsign` 生成 `InRelease`**（CI 自动签名，发布全自动）；公钥提交进仓库，先经工单 18 配置脚本分发到 Fable 的 `/data/data/com.gph.fable/files/usr/etc/apt/trusted.gpg.d/`，后续 bootstrap 重建时顺手内嵌；Fable 侧用 `[signed-by=...]`，不用 `trusted=yes`。私钥丢失/泄露：重新生成密钥、更新 secret、公钥随新快照重新分发、Fable 侧更新信任区（轮换/撤销流程记录于本 ADR）。
4. **更新流 = 按需构建**：`fable-bootstrap` 新增 `build-package` workflow（`workflow_dispatch` 输入包名），用 termux-packages 原生 `build-package.sh` 构建指定包及其依赖闭包，成功并入仓库快照、重生成索引并发布新 Release；加 `actions/cache` 做增量加速。bootstrap 全量重建流程保留不动（后置）。
5. **首批补齐范围（自举 + 日常，2026-08-09 刷新）**：git、gh、openssh、openssh-sftp-server、nodejs、npm、openjdk-17/-x、openjdk-21/-x、openjdk-25/-x、rust、rust-std-aarch64-linux-android、clang、runit、termux-services、wget、zip、python-pip、jq（21 个）；实施时以迁移时点 `dpkg -l` 刷新缺口。ffmpeg 全家、proot、redis 等其余缺口经同一机制按需补。
6. **顺序**：工单 02 Phase B（用户侧 restore + 本地 dpkg 装归档内 107 个）先行；fable-repo 就绪后，Fable 侧 `apt update` 补装首批清单。

## 理由（带证据）

- 官方源路径不匹配是硬前提，不是可选优化；自建仓库是唯一让 `apt install` 在 Fable 前缀下可用的通道。
- 扁平仓库 + Releases `latest/download`：实现最简（`dpkg-scanpackages` + `apt-ftparchive release` + `gpg --clearsign` 即可），URL 稳定、支持 2GB 资产，满足个人单用户规模；Pages 的 1GB/100MB 限制对大 deb 有真实风险，否掉。
- 签名成本低但收益实：防发布端被攻破/误传的供应链篡改，且 Fable 是长期身份产品；私钥进 secret 不违反工单 06 的"无密钥进仓库"红线。
- 按需构建比全量镜像便宜一个量级：缺口只有 100 多个包，termux-packages 原生机制直接可用；全量镜像/定期重建是 v1 之后的选项。

## 冲突标注（与既有 ADR / 工单）

- **工单 06（收口）**：其"apt sources.list 仍指向官方 termux-main（已知问题，需另行定案）"由本 ADR 收口。
- **工单 02（补充）**：`install-packages.sh` 的"暂不装 + 缺失清单 + 另定案"由此升级为"仓库按需补齐"。
- **ADR-0001~0004（无冲突）**：包源只影响环境侧，不影响 App 架构/渲染/构建链。

## 被否选项

- 继续只靠 debs 归档 + 本地 dpkg：快照会过期，无法增量装包，Fable 作为长生命周期环境不可持续。
- 完整 termux-main 镜像 / 定期全量重建：v1 过度建设，维护成本高。
- GitHub Pages 托管：1GB 站点 / 100MB 单文件软限制，openjdk 等大 deb 有越限风险。
- 不签名 `trusted=yes`：HTTPS 只防窃听不防发布端篡改；签名成本低。
- 复用官方 termux GPG key：私钥不可得，且只签官方仓库。

## 重开条件

- v1 之后若需要与 termux-main 同步全量包或正式更新流，评估镜像路线。
- 若 GitHub Releases 因超大资产/带宽不适配（未来全量镜像），换自托管或 Pages + CDN。
