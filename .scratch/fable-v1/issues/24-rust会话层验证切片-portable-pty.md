# 24 — Rust 会话层验证切片（portable-pty 试编 + 真机探针）

**What to build:** 验证"portable-pty 0.9.0（或 nix 兜底）在 aarch64-linux-android + Termux clang 构建线下可用"：新 Rust crate 试编 portable-pty，薄 JNI 桥暴露 ptySpawn/Read/Write/Resize/Close；独立探针 APK（"Fable Session Probe" launcher 图标，不碰主终端）：spawn bash（com.gph.fable 环境注入：PREFIX/HOME/PATH/TERM/TMPDIR，环境由 Kotlin 传入）、读写交互、resize（40x10/80x24）、4 会话并行、seq 长输出性能粗测。若 portable-pty 链接/运行遇坑，切 nix 自拼（posix_openpt 序列）路径并记录证据。

**Blocked by:** None（与 fable-v1/23 并行）
Status: 待验收

## 验收清单

- [ ] portable-pty 0.9.0（或 nix 兜底）在 aarch64-linux-android 编译并链接成 .so，进 APK（jniLibs 直供），aapt2 badging / apksigner 校验通过
- [ ] 探针：spawn bash 出现提示符；`echo $PREFIX` = /data/data/com.gph.fable/files/usr；`pwd` = files/home
- [ ] 读写 + resize：40x10 / 80x24 行列正确、文本重排
- [ ] 4 会话并行互不崩、输出各自独立
- [ ] seq 200 流式输出不卡死（性能粗测）
- [ ] 环境注入正确（HOME/PREFIX/PATH/TERM/TMPDIR 与预期一致）
- [ ] 结论写回 Comments：portable-pty vs nix 判定、构建参数、坑（含 NDK/CI 线 min API ≥ 23 的风险说明）、对 fable-v1/25 的输入

## Comments

2026-08-10 建单（决策窗口 8：会话层搬 Rust，ADR-0008；调研依据 `.scratch/fable-v1/research-会话层Rust零件.md`）。

2026-08-11 实施完成（待真机验收）：

**1. 验证数据/命令**

- 构建：`cd spike-session && cargo build --release`（宿主即 aarch64-linux-android）→ 成功；产物 `libfable-session.so`（1.24MB 未 strip / 打包 strip 后 769,336B），NEEDED 仅 `libc.so`/`libdl.so`（比渲染器五件套更轻），无 PT_TLS 段，JNI 导出 7 个符号（ptySpawn/Read/Write/Resize/Close/buildInfo/lastError）与 `SessionProbe.java` 一一对应。
- 离屏自检（本机 bionic，PASS/FAIL 断言）：`cargo test --release` → `portable_pty_roundtrip` OK + `portable_pty_parallel_sessions` OK（2/2 PASS）。覆盖：spawn+读写（__READY__）、env 注入（PREFIX/HOME/PATH/TERM/TMPDIR）、pwd、resize 40x10→`10 40` 与 80x24→`24 80`、4 会话并行互不串、seq 200 全量（剥 ANSI 后按行核对 1/100/200 存在）。
- APK：`cd fable-app && JAVA_HOME=.../java-25-openjdk ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL（40s，增量）；`aapt2 dump badging` = `com.gph.fable` / targetSdk 35 / compileSdk 36；`apksigner verify` = <签名证书主体>；APK 内 `lib/arm64-v8a/libfable-session.so` 存在且 7 个 JNI 符号保留。
- 装机包：`~/storage/downloads/fable-session-24_arm64-v8a.apk`，sha256 `19035be02abc1d85f021f912f08d81b83e42f54a6eda694ccc68de419852bf5a`。Termux 无 shell 权限 `pm install` 被拒（SecurityException），装机交人工（与工单 08/10 同模式）。

**2. 踩过的坑与解法**

- 坑 A（阻塞读 vs close 死锁）：Rust 侧若 reader 持 Session Mutex 阻塞 read，ptyClose 会等锁而 reader 等数据 → 死锁。解法：spawn 时 `dup()` 一份 master fd 专供读，ptyRead 不持锁直接 `libc::read`；ptyClose 先 `close(read_fd)` 解除阻塞（read 返回 EIO→0，与 portable-pty PtyFd 语义一致），再取锁 kill child 收尾。复刻工单 08 C shim 语义。
- 坑 B（jni 0.22 API）：`with_env` 返回 `EnvOutcome`，须 `.resolve::<LogErrorAndDefault>()` 取 T；`String[]` 用 `JObjectArray<JString>` + `get_element` 免手动 cast；`set_byte_array_region` 已废弃，改用 `JByteArray::set_region`。`get_string` 有 deprecated 告警（仍可用，未强改）。
- 坑 C（静态项）：`static Mutex<HashMap<...>> = Mutex::new(HashMap::new())` 非 const → 改 `LazyLock`。
- 坑 D（离屏自检断言）：PTY 默认 ONLCR（\r\n）+ bracketed paste（ESC 序列）+ 键入回显 → 标记判断会误中回显。解法：`stty -echo` 后断言 + 剥 ANSI + 按行核对。
- 坑 E（spawn 错误路径泄漏）：dup/take_writer/注册表锁失败时先 `child.kill()` 再 bail，避免子进程残留。
- 坑 F（探针自检线程）：Kotlin 自检会话的 reader 线程忘启动会导致全超时——`spawnForTest()` 内统一启动，手动 spawn 复用同一路径。

**3. 结论写回**

- **portable-pty vs nix 判定：portable-pty 0.9.0 成立（主选确认，无需切 nix）**。Termux clang 构建线（宿主 aarch64-linux-android，无 NDK introduced-API 检查）下编译、链接、运行全过；`openpty@LIBC` 由 bionic 运行时解析成功。
- 构建参数：`cargo build --release`（lib name `fable_session` → `libfable-session.so`）；`[profile.dev] opt-level=1`；依赖 `portable-pty 0.9.0` + `jni 0.22.4` + `anyhow` + `libc`；NEEDED 仅 libc/libdl，无 TLS 段要求（区别于 ghostty 静态库线）。
- **NDK/CI 风险（照调研记录，未解决）**：若未来启用 GitHub Actions / cargo-ndk 构建线，必须显式 `-p 24`（min API ≥ 23），否则复现 distant PR #270 的 `openpty` 链接失败（bionic 自 API 23 提供）。本机 Termux 线无此检查，风险只影响 CI 备用线。
- 对 fable-v1/25 的输入：① JNI 桥 API 形态已验证（env 由 Kotlin 构造 `"KEY=VALUE"` 数组 + cwd 传入，`CommandBuilder::env/cwd` 生效，`--login` + controlling tty 默认开）；② 会话注册表句柄 + close 先解阻塞读的语义可直接沿用；③ `.so` 极轻（无 ghostty 静态依赖），后续 25 加事件流 JNI 时 NEEDED 不会膨胀；④ 4 会话并行与 seq 200 在 portable-pty 下无卡死，性能基线可作 27 对比的 Rust 侧口径。
- 真机验收步骤（装机后出现 "Fable Session Probe" 图标）：打开 → 自动 spawn 1 会话 → 手动 `echo $PREFIX`/`pwd`/resize/seq200 → 点 [自检] 跑 PASS/FAIL 全量断言；验收数据回报后改 Status。
