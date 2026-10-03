# 07 — 集成 com.gph.fable 前缀 bootstrap 并重建 APK（真机验收）

**What to build:** 把工单 06 产出的 `com.gph.fable` 前缀 bootstrap 集成进 fable-app：替换内嵌 bootstrap zip → 重编原生库与 APK → 产物校验 → 装到手机，验证 Fable 打开即终端、不再报 Bootstrap Error。

**Blocked by:** 06

Status: 已完成

- [x] `app/src/main/cpp/bootstrap-aarch64.zip` 替换为工单 06 产物（sha256 `d9fc5f96691afdeb83ecffc0e9571a7e88cf127d775beefc53d9a0e353e0a601` 核验）
- [x] `./gradlew :app:assembleDebug` 构建成功
- [x] 产物校验：APK 包名 `com.gph.fable`（aapt2 badging）；签名 <签名证书主体>（apksigner）；含 arm64 原生库；**APK 内嵌 bootstrap 的第二阶段脚本与关键二进制前缀为 `/data/data/com.gph.fable`**（从 APK 抽取 libtermux-bootstrap.so 内的 zip 抽查）
- [x] APK 复制到内部存储 Download 目录，供用户安装
- [x] 真机验收（需用户配合安装）：打开 Fable 直接进终端；`echo ok`/`pwd` 正常；`$PREFIX=/data/data/com.gph.fable/files/usr`；`uname -m=aarch64`；无 Bootstrap Error
- [x] 记录：APK 路径、验收结果写回本工单 Comments（含工单 01 两个未勾验收项的关联结论）

## Comments

2026-08-06 说明：bootstrap 本地副本已存在 `$HOME/.codex_tmp/fable06/bootstrap-aarch64.zip`（sha256 已验证）；Release 来源见工单 06。本工单不涉及迁移（工单 02），旧 Termux 数据保持原样。

2026-08-06 实施完成记录（主线程）：bootstrap 集成、重建、产物校验、交付全部完成；唯一未完成项是"用户安装后真机验收"。

2026-08-06 真机验收通过（用户确认）：覆盖安装成功，打开 Fable 直接进终端，无 Bootstrap Error；`echo ok`/`pwd` 正常；`$PREFIX=/data/data/com.gph.fable/files/usr`；`uname -m=aarch64`。工单 01 的"装到手机成功"与"点击图标直接进入终端"两项随之闭环。备注：终端首屏显示 "Welcome to Termux!" MOTD（bootstrap 自带 etc/motd，品牌文案仍为官方 Termux，属预期，不影响功能）。

### 实施过程（含两个必要的构建侧修复）

1. 替换 `app/src/main/cpp/bootstrap-aarch64.zip` 为工单 06 产物，sha256 `d9fc5f96691afdeb83ecffc0e9571a7e88cf127d775beefc53d9a0e353e0a601` 核验通过。
2. **修复一（build.gradle 哈希钉）**：首次构建时发现 `downloadBootstraps` 任务按钉死的旧哈希（`c8d702b6...`）校验 aarch64 zip，不匹配就删除本地 zip 并改从官方 termux-packages Release 重新下载 com.termux 前缀 bootstrap。已在 `fable-app/app/build.gradle` 把 aarch64 期望哈希改为 `d9fc5f96...`（其余 ABI 不动）。这是集成工单 06 产物所必需的改动。
3. **修复二（原生库重编机制）**：仓库实际管线是 jniLibs 直供 .so（绕过 AGP ndkBuild，见 `构建流水线验证-完整执行记录.md` §3.17），仅换 zip 不会自动重编。已用 Termux clang 重编 `libtermux-bootstrap.so`（`clang --target=aarch64-linux-android24 -fPIC -shared -O2 termux-bootstrap.c termux-bootstrap-zip.S`，产物 142,301,864 B）并覆盖 `app/src/main/jniLibs/arm64-v8a/`。**后续再换 bootstrap 必须重复此 clang 步骤**（或把该步骤并入 build-and-verify.sh）。
4. 重建：`./gradlew :app:assembleDebug` 成功（merge/package 均重跑）。

### 产物与校验（全部通过）

- APK：`fable-app/app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`（173,218,347 B，sha256 `90c6680b67e551e5fb500ae60426f4820c7c4c88f391af235c1bfde352a62511`）
- aapt2 badging：包名 `com.gph.fable`、versionCode 1022、versionName 0.119.0-beta.3
- apksigner verify：<签名证书主体>，证书 SHA-256 `<证书指纹>`
- APK 内含 `lib/arm64-v8a/` 3 个 .so：libtermux-bootstrap.so（142,300,448 B）、liblocal-socket.so、libtermux.so
- 内嵌 bootstrap（从 APK 抽 .so → `.rodata` 偏移 0x510 处切出 zip）：
  - 抽取 zip sha256 = `d9fc5f96691afdeb83ecffc0e9571a7e88cf127d775beefc53d9a0e353e0a601`，与工单 06 产物完全一致；`unzip -t` 通过，18712 条目
  - `etc/termux/bootstrap/termux-bootstrap-second-stage.sh`：shebang `#!/data/data/com.gph.fable/files/usr/bin/bash`；`export TERMUX_PREFIX="/data/data/com.gph.fable/files/usr"`
  - `bin/bash`、`bin/dpkg` 内 `com.termux` 出现 0 次

### 交付与装机验收（剩一步需用户）

- 已复制到 `/storage/emulated/0/Download/fable-app_apt-android-7-debug_arm64-v8a.apk`（sha256 与构建产物一致；原件保留在构建目录）
- 本环境即手机上的 Termux，已尝试 `pm install -r` 覆盖安装，被 <厂商 ROM> 权限拒绝（SecurityException：需 MANAGE_USERS/CREATE_USERS），无法代装，须由用户从 Download 安装
- 本机已装有 com.gph.fable（工单 01 版本），新 APK 同签名，可直接覆盖安装（若提示 INSTALL_FAILED_UPDATE_INCOMPATIBLE，先卸载旧版再装）
- 用户验收步骤：安装 Download 中 APK → 打开 Fable → 应直接进终端，无 Bootstrap Error → `echo ok`、`pwd` 正常 → `echo $PREFIX` 输出 `/data/data/com.gph.fable/files/usr` → `uname -m` 输出 `aarch64`

### 工单 01 关联结论

- "装到手机成功"：已达成（本机已安装 com.gph.fable，工单 01 即已装）。
- "点击图标直接进入终端"：待用户完成本次覆盖安装后确认；内嵌 bootstrap 第二阶段的脚本/二进制前缀已全部为 `/data/data/com.gph.fable`，预期不再报 Bootstrap Error。确认后即可把工单 01 该项与工单 07 真机验收一并勾掉。

### 已知事项

- `clean` 会删掉 `src/main/cpp/bootstrap-*.zip`，downloadBootstraps 对 aarch64 的下载 URL 仍指向官方 termux-packages Release（不会命中新哈希 → 构建失败）。建议后续把 aarch64 下载 URL 指向工单 06 Release（`bingnnvjn/fable-bootstrap`）或把 zip 重下步骤并入脚本。
