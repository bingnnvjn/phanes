# 25 — fable-session 生产实现（SessionManager + 事件流 + JNI 边界）

**What to build:** 把验证切片升级为生产级 Rust 会话层 crate：SessionManager（创建/关闭/列表/生命周期，2–8 并发设计余量）、Session（PTY 读写、resize、进程退出捕获与回收）、环境注入（环境快照由 Kotlin 计算传入，Rust 不重实现 AndroidShellEnvironment）、事件流第一版最小集六事件（command_started / output_chunk / command_finished / exit_code / session_created / session_closed，schema 含 session_id/时间戳/扩展 metadata）、JNI 边界（SessionHandle 创建/读写/resize/close + 事件回调 + 诊断日志订阅）、Rust 单测（PTY 行为/事件序列/并发）。

**Blocked by:** fable-v1/24
Status: 待验收

## 验收清单

- [ ] SessionManager API：create/close/list/get（行为驱动，TDD 红绿一片）
- [ ] 事件流：六事件序列断言（session_created → command_started → output_chunk* → command_finished → exit_code → session_closed）；schema 含扩展点（metadata）
- [ ] 并发：8 会话本机/offscreen 压测不崩、输出独立（2–4 硬指标、8 余量数据记录）
- [ ] 进程生命周期：退出码捕获、僵尸回收、close/kill 语义
- [ ] JNI：SessionHandle 创建/读写/resize/close/事件回调可从 Kotlin 调用；诊断日志订阅生效
- [ ] 全量单测 + 类型检查通过
- [ ] 结论写回 Comments：事件流 schema 定稿、JNI 边界、坑、对 fable-v1/26 的输入

## Comments

2026-08-10 建单（决策窗口 8：会话层搬 Rust，ADR-0008）。

2026-08-11 实施完成（待真机验收）：

**1. 验证数据/命令**

- 核心离屏自检（本机 bionic，PASS/FAIL 断言）：`cargo test` → 10/10 PASS（工单 24 遗留 2 项 + 工单 25 新增 8 项：SessionManager create/close/list/get、六事件序列（exit 7 全序 + 时间戳单调 + session_id 一致）、事件 schema 名称、8 会话并发（477ms 完成、输出互不串）、exit 3 退出码捕获 + 状态流转 Exited{3}、close kill 运行中会话 <5s、40x10/80x24 resize、read_output 与事件流同源）。
- 构建：`cargo build --release` → `libfable-session.so`（未 strip 1.59MB）；`readelf -d` NEEDED 仅 `libc.so`/`libdl.so`（与工单 24 一致，事件流 JNI 未引入新动态依赖）；`nm -D` 导出 15 符号 = 旧 SessionProbe 7 + 新 SessionHandle 8（sessionCreate/sessionWrite/sessionRead/sessionResize/sessionClose/sessionSetEventCallback/sessionSetLogCallback/sessionLastError），与 Kotlin `SessionHandle.java` 一一对应。
- APK：`cd fable-app && JAVA_HOME=.../java-25-openjdk ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL（49s，增量）；`aapt2 dump badging` = `com.gph.fable` / minSdk 24 / targetSdk 35 / compileSdk 36；`apksigner verify` = <签名证书主体>；APK 内 `lib/arm64-v8a/libfable-session.so` 存在（打包 strip 后 991,144B）且 8 个 SessionHandle 符号保留。
- 装机包：`~/storage/downloads/fable-session-25_arm64-v8a.apk`，sha256 `968c2f7701934eca747d8363b33e52555651bf64d90a71ef09cde95682fd5992`。Termux 无 shell 权限 `pm install` 被拒（同工单 24 模式），装机交人工。
- 探针（真机自检项，装机后跑）：新增 launcher "Fable Session Handle Probe"（独立图标，不碰主终端），[自检] 断言：sessionCreate + 事件回调（session_created → command_started）→ 写入 `echo __HANDLE_READY__` 后 output_chunk 事件含标记 → sessionRead 读到同源字节 → resize 40x10 后 stty size 输出 `10 40` → `exit 3` 后 command_finished + exit_code=3 → sessionClose 后 session_closed → 诊断日志订阅收到 onLog → 8 会话并发各自 UNIQ 标记互不串。

**2. 踩过的坑与解法**

- 坑 A（jni 0.22 新 API 形状）：`Global<JObject>` 需显式 `'static` 且**不可 Clone** → `CallbackSlot` 持 `Arc<Global<JObject<'static>>>` 共享；`call_method`/`find_class` 的参数名与签名不再收裸 `&str`，要 `JNIString::from(...).as_ref()` + `RuntimeMethodSignature::from_str(...)` + `MethodSignature::from(&sig)`；`new_object_array` 长度是 `jsize`(i32)；`byte_array_from_slice`/`new_string` 的临时值借进 `JValue::Object` 会悬垂 → 先 `let` 绑定（data_arr/message_str/extra_arr）再构造 args。
- 坑 B（reader × close 交互）：EOF 后不能阻塞 `child.wait()`（close 会 join 而 wait 永不返回 → 死锁）→ 改用 `try_wait` 20ms 轮询，close 置标志后 kill + 1s 余量取状态；事件发送用 `send_timeout(100ms)` 循环且每轮查 close 标志，消费方停顿时 close 不会卡死。
- 坑 C（事件顺序）：`session_closed` 必须由 close() 在 **join reader 之后**发出，才能保证 command_finished/exit_code 严格在前；reader 发 exit 事件**前**先置 `state=Exited`，避免测试读到旧状态。
- 坑 D（残留清理）：分发线程因会话已不存在而无法启动时 `EVENT_CALLBACKS` 槽残留 → 补 `remove`；`start_reader` 失败路径用 `close()` 幂等清理（kill 子进程 + 关读 fd），避免子进程泄漏。
- 坑 E（日志线程）：日志分发线程常驻（每进程 1 个 VM-attached 线程），`sessionSetLogCallback(null)` 只清槽不停线程——v1 接受，代码注释说明。

**3. 结论写回**

- **事件流 schema 定稿**：六事件 `session_created / command_started / output_chunk / command_finished / exit_code / session_closed`；`Event{kind, session_id, timestamp_ms, meta}`；meta 扩展点 = `bytes / exit_code / signal / message / extra(BTreeMap<String,String>)`。JNI 回调 `onEvent(long sessionId, String event, long timestampMs, byte[] data, int exitCode, String message, String[] extra)`；日志 `onLog(long sessionId, int level, String tag, String message, long timestampMs)`（level 0/1/2 = info/warn/error）。
- **JNI 边界定稿**：`SessionHandle` 8 native（create(shell, args, env, cwd, cols, rows, callback) / write / read / resize / close / setEventCallback / setLogCallback / lastError）；事件回调 = 每会话一个分发线程（`JavaVM::attach_current_thread`，从会话事件流收事件回调 Kotlin）；事件回调与 `sessionRead` 是同一输出流的两种消费方式（read 消费只读侧缓冲副本，字节不因一方消费而消失），Kotlin 第一版只接诊断/日志（ADR-0008 决策 7 落地）。
- **对 fable-v1/26 的输入**：① Rust 事件流已就绪，26 的会话层抽象（SessionFactory 接口）可直接对接 `SessionHandle`（env 快照 + args 数组 + 事件回调均从 Kotlin 传入）；② `sessionRead`（非阻塞侧缓冲）供不用事件回调的消费方；③ 切回 Java 时 `sessionSetEventCallback(handle, null)` 即可停掉分发线程；④ 诊断日志订阅在 Rust 模式的可见性已由探针自检覆盖；⑤ 并发余量数据：8 会话 477ms 无串（本机 bionic，可作 27 对比的 Rust 侧口径）；⑥ 参数面：事件队列有界 4096 条/会话（约 16MB 背压上限）、只读缓冲 1MiB 丢最旧记 warn，26 如需调参改 `SessionConfig::event_capacity`。

Status → 待验收（真机安装 "Fable Session Handle Probe" 后跑 [自检]，验收数据回报后改 已完成）。
