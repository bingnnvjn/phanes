# 06 — 重建 com.gph.fable 前缀 bootstrap（公开仓库 + GitHub Actions）

**What to build:** 用 termux-packages 构建系统，以 `TERMUX_PREFIX=/data/data/com.gph.fable/files/usr` 重新构建 bootstrap（apt-android-7 / aarch64），产出不再硬编码 com.termux 路径的 bootstrap zip；通过**公开仓库** `bingnnvjn/fable-bootstrap` + GitHub Actions CI 完成构建与发布，交付给 fable-app 集成。

**Blocked by:** None — can start immediately。（其输出解除工单 01 的真机验收阻塞，并是工单 02 迁移的前提之一。）

Status: 已完成

- [ ] 公开仓库 `bingnnvjn/fable-bootstrap` 建立（termux-packages 源码 + 我们的改动，提交历史干净）
- [ ] `TERMUX_PREFIX` 改为 `/data/data/com.gph.fable/files/usr`
- [ ] CI workflow 可重复运行并产出 aarch64 bootstrap zip（+ 本地 apt 仓库或等价物），产物可下载
- [ ] 产物验证：zip 完整性；SYMLINKS.txt / 第二阶段脚本 / bin/bash / bin/dpkg / bin/apt 抽查中 `/data/data/com.termux` 出现 0 次；变体与版本信息记录
- [ ] 安全清单全过（公开仓库）：
  - 无 token / keystore / 密码进仓库
  - workflow 仅用 `secrets.GITHUB_TOKEN`，`permissions` 最小化
  - 无 `pull_request_target`；无含密钥的 debug 日志
  - 提交历史干净，无本地路径/个人文档/交接文档
- [ ] 交付说明：产物 URL + sha256 + 集成 fable-app 步骤（替换 bootstrap zip、重编 libtermux-bootstrap.so、重建 APK），写回本工单 Comments

## Comments

2026-08-06 实施完成记录（主线程）：公开仓库、CI 构建、产物验证全部完成，bootstrap 已发布。

### 产物（v1 交付，r2 为当前推荐）

- 仓库：https://github.com/bingnnvjn/fable-bootstrap （public，main 分支，干净单根提交历史）
- Release：https://github.com/bingnnvjn/fable-bootstrap/releases/tag/bootstrap-2026.08.06-r2%2Bapt-android-7
- 产物 URL：https://github.com/bingnnvjn/fable-bootstrap/releases/download/bootstrap-2026.08.06-r2%2Bapt-android-7/bootstrap-aarch64.zip
- sha256：`d9fc5f96691afdeb83ecffc0e9571a7e88cf127d775beefc53d9a0e353e0a601`
- 附件：bootstrap-aarch64.zip（142,296,286 B）、bootstrap-aarch64.zip.sha256、bootstrap-info.txt、debs-aarch64.tar.gz（119,420,004 B，全部构建出的 .deb，可作后续 Fable 包仓库种子）
- 变体/版本：apt-android-7 / aarch64；TERMUX_PREFIX=/data/data/com.gph.fable/files/usr；构建于 2026-08-06，基于 termux-packages master 导入（e2a23fa）+ fork 提交 ddd1e7e；zip 18712 条目。

### 验证结果（本地复验 + CI 内置校验双通过）

- `unzip -t`：无错误，zip 完整；sha256 与 Release 记录一致。
- 验收清单文件 `/data/data/com.termux` 出现 0 次：SYMLINKS.txt、etc/termux/bootstrap/termux-bootstrap-second-stage.sh、etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh、bin/bash、bin/dpkg、bin/apt、bin/apt-get 全部为 0。
- 第二阶段脚本路径与 fable-app（v0.119.0-beta.3）期望一致：zip 内 `etc/termux/bootstrap/termux-bootstrap-second-stage.sh`（TermuxInstaller.java 硬编码路径）。
- 全树含 `/data/data/com.termux` 路径的文件仅剩 3 个，均为良性：bin/am 与 bin/termux-reset 中的 `com.termux.termuxam.Am` 类名（与 am.apk 自身包名自洽，非前缀路径）、bin/termux-exec-ld-preload-lib 与 termux.properties/ExecIntercept.h 中的注释文本。

### 集成 fable-app 步骤（下一步，需真机验收）

1. 下载 `bootstrap-aarch64.zip`（上述 Release）。
2. 替换 `fable-app/app/src/main/cpp/bootstrap-aarch64.zip`。
3. 重编原生库与 APK：`cd fable-app && export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk && ./gradlew :app:assembleDebug`（zip 经 termux-bootstrap-zip.S 嵌入 libtermux-bootstrap.so）。
4. 装机验收：安装 APK → 打开 Fable → 终端可执行 `echo ok`/`pwd`；`$PREFIX` 应为 `/data/data/com.gph.fable/files/usr`；`uname -m` 应为 aarch64。

### 安全清单（公开仓库，全部通过）

- 无 token / keystore / 密码进仓库（提交历史仅 termux-packages 源码 + fork 改动 + README）。
- workflow 只用 `${{ github.token }}`；顶层 `permissions: {}`，build job `contents: read`，仅 publish job `contents: write`。
- 无 `pull_request_target`；无密钥 debug 日志；main 分支保护已配（禁强推、禁删除）。

### 已知事项与建议（不影响启动验收）

- 体积：zip 142MB / 18712 条目，明显大于官方 29MB/3490 条——build-bootstraps.sh 的 extract_debs 把依赖闭包内全部 deb（含 -dev/-doc 及构建期依赖如 python/perl/tor 等）都打进了 bootstrap。如需按官方清单瘦身（~29MB），后续可用 generate-bootstraps.sh + 本地 apt 仓库（debs 附件可作种子）重打，或给 extract_debs 加子包过滤。
- apt sources.list 仍指向官方 termux-main（packages-cf.termux.dev），官方仓库的包是 com.termux 前缀——bootstrap 本身不受影响，但后续 `apt install` 需要 Fable 自己的包仓库/镜像（与工单 02 相关，需另行定案）。
- 构建耗时：workflow_dispatch 全量重建约 1h15m（无缓存，每次从头构建；如需可加 actions/cache 缓存 ~/.termux-build）。
