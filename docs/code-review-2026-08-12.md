# Fable 完整仓库代码审查报告

> 审查日期：2026-08-12  
> 审查范围：当前仓库的 Kotlin 壳、Java 兼容层、Rust 底层、会话层、渲染器、JNI、Android 配置和相关测试  
> 审查目标：可读性、可维护性、简洁性、一致性、安全性、正确性和性能  
> 审查方式：源码静态检查 + 架构/ADR/性能资料对照 + 本地测试  
> 本报告只记录审查结果，没有修改业务代码。

## 一、结论摘要

Fable 当前已经有清楚的长期方向：

- Kotlin 壳负责 Android UI、生命周期和系统集成。
- Rust 底层负责会话层、渲染器和事件流。
- `libghostty-vt` 是终端核心。
- UI 通过 `CoreAdapter` 连接核心。
- Java 会话层已经删除，Rust 是唯一会话层实现。

核心功能的测试基础也不错。`spike-session`、`spike-render` 和 `fable-boo` 的 Rust 测试全部通过；Android 的核心模块测试通过。

但是，当前代码还不适合直接称为“安全、长期可维护的最终结构”。主要原因不是代码行数，而是几个边界没有真正封装好：

1. 外部输入到文件系统的边界缺少统一的 canonical containment 校验。
2. JNI 的裸句柄没有生命周期保护，存在释放后使用风险。
3. PTY 输出和渲染命令都存在高流量下的无界或主线程压力。
4. Activity、Service、Renderer、Callback 的生命周期没有统一的所有权模型。
5. 同一类状态访问被复制到许多 wrapper，增加了接缝数量。
6. 性能资料已经记录 Rust 会话层创建期约 1.48GB 的内存尖峰，但代码仍没有完成归因和预算控制。
7. 工程仍有大量大文件、旧 Java 兼容层和编译警告，长期演进成本会继续上升。

审查结论：**当前版本不建议进入“只做小修小补”的阶段。应先修复 P0/P1 安全和生命周期问题，再进入内存优化窗口。**

## 二、仓库规模和验证结果

### 代码规模

本次盘点得到：

| 区域 | 规模 |
| --- | ---: |
| Java 源文件 | 125 |
| Kotlin 源文件 | 69 |
| XML 文件 | 67 |
| Rust 源文件 | 27 |
| 最大 Rust 文件 | `spike-render/src/render_android.rs`，约 184 KB |
| 最大 Android 文件 | `termux-shared/.../TermuxConstants.java`，约 83 KB |

大文件本身不是缺陷，但它会降低局部推理能力，并使生命周期、渲染、状态和兼容逻辑容易继续堆在一起。

### Rust 测试

执行：

```text
cd spike-session && cargo test --all-targets
cd spike-render && cargo test --all-targets
cd fable-boo && cargo test --all-targets
```

结果：

- `spike-session`：单元测试 0 个；PTY/并发/SessionManager 测试全部通过。
- `spike-render`：20 个核心测试通过；examples 和诊断二进制可编译。
- `fable-boo`：19 个测试通过。
- 编译存在若干 JNI deprecated、unused、dead-code 和 naming warnings。
- `cargo clippy` 结果：`spike-session` 18 个 library warnings（另有 1 个测试 warning）；
  `spike-render --lib` 69 个 warnings；`fable-boo` 无 warning。

### Android 测试

执行：

```text
cd fable-app
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-25-openjdk
./gradlew :fable-core:testDebugUnitTest \
  :termux-shared:testDebugUnitTest \
  :app:testDebugUnitTest \
  --rerun-tasks
```

结果：

- `fable-core`：通过。
- `termux-shared`：通过。
- `app`：34 项中 33 项通过，1 项失败：
  `FileReceiverActivityTest.testIsSharedTextAnUrl`。
- 失败原因与既有记录一致：Robolectric 4.8.1 在 JDK 25 下出现 `NoClassDefFoundError`，不是本次审查引入。
- Kotlin 编译产生较多 nullability、deprecated API、unchecked cast 和“condition is always true/false”警告。
- 构建过程中反复出现 Termux 原生 `aapt2` 的 `No package ID 7f` 诊断噪音；当前不阻断构建，但应继续隔离工具链问题。

## 三、必须优先处理的问题

### P0 — 签名密钥和密码硬编码

位置：

- `fable-app/app/build.gradle:103-117`
- 本地文件：`fable-app/keystore/<旧签名材料>`

问题：

- debug 和 release 共用同一个签名身份。
- keystore 路径、alias 和密码直接写在 Gradle 文件中。
- keystore 当前被 `.gitignore` 忽略，但密码仍会进入源码、构建日志、复制目录和可能的公开远端。

影响：

- 一旦密码或 keystore 泄露，攻击者可以生成同签名更新包。
- debug 构建和 release 构建无法形成安全边界。
- 这是供应链和更新身份问题，不是普通代码风格问题。

建议：

1. 立即停止在 Gradle 中保存密码。
2. 使用用户级 `gradle.properties`、环境变量或 CI secret。
3. debug 使用独立 debug key；release 使用只存在于发布环境的 key。
4. 如果该密码曾进入公开远端或共享日志，应按已泄露处理并重新签发发布身份。
5. 在构建检查中禁止匹配明文密码和 keystore 文件。

### P1 — 接收文件名可穿越目标目录

位置：

- `fable-app/app/src/main/java/com/gph/fable/app/api/file/FileReceiverActivity.java:200-227`
- 关键代码：`new File(receiveDir, attachmentFileName)`

问题：

`attachmentFileName` 来自 Intent、ContentProvider 或外部文件名，没有先做 basename、canonical path 和目标目录 containment 检查。

攻击输入可以包含：

- `../`
- 绝对路径
- 符号链接目标
- 目录分隔符

影响：

- 外部发送内容可能写入接收目录之外的可写文件。
- 已存在文件会被 `FileOutputStream` 覆盖。

建议：

- 只接受 `new File(name).getName()` 后的 basename，或明确拒绝包含分隔符的名字。
- 对最终路径执行 `getCanonicalFile()`。
- 使用：

```text
target == receiveDir
或 target.path.startsWith(receiveDir.path + File.separator)
```

- 使用 `CREATE_NEW` 或安全的冲突命名策略，避免覆盖已有文件。
- 用 try-with-resources 管理输入流。

### P1 — DocumentsProvider 没有根目录边界

位置：

- `fable-app/app/src/main/java/com/gph/fable/filepicker/FableDocumentsProvider.java:119-138`
- `:193-214`

问题：

- `createDocument()` 直接把 `parentDocumentId` 当作文件系统路径。
- `getFileForDocId()` 接受任何存在的绝对路径。
- `isChildDocument()` 只做字符串前缀比较。
- `querySearchDocuments()` 使用 `startsWith(TermuxConstants.TERMUX_HOME_DIR_PATH)`，没有路径分隔符边界，也没有可靠的 symlink containment。

影响：

导出的 DocumentsProvider 可能被构造出指向 `$HOME` 之外的 document ID，或者把相邻目录误判为子目录。

建议：

1. document ID 使用相对 `$HOME` 的稳定编码，不使用任意绝对路径。
2. 读取 document ID 时解析并 canonicalize。
3. 统一使用 `isWithin(base, candidate)`，要求：
   - `candidate.equals(base)`，或
   - `candidate.startsWith(base + File.separator)`。
4. 对符号链接单独处理，不允许搜索逃出 `$HOME`。
5. `listFiles()` 返回 null 时安全处理。
6. 搜索增加取消检查、遍历预算和后台索引。

### P1 — FableOpenReceiver 的路径前缀检查不安全

位置：

- `fable-app/app/src/main/java/com/gph/fable/app/FableOpenReceiver.java:201-231`

问题：

当前检查等价于：

```text
path.startsWith(root)
```

因此类似 `root-sibling/...` 的路径可能通过检查。该 Provider 是 exported，并允许外部应用调用。

建议：

- 使用 canonical path。
- 使用 `equals(root)` 或 `startsWith(root + File.separator)`。
- 对外部存储根目录和 Fable 私有目录分别执行同一个 containment 函数。
- 为 `../`、相邻目录、符号链接和根路径编写回归测试。

### P1 — RunCommandService 在校验后重新使用符号链接

位置：

- `fable-app/app/src/main/java/com/gph/fable/app/RunCommandService.java:157-201`

问题：

代码先把 executable canonicalize 并校验，随后又把原始 `executableExtra` 恢复为执行路径。该路径可能是符号链接，校验对象和执行对象不再相同。

影响：

- 存在 TOCTOU 和符号链接替换风险。
- “已校验可执行文件”不一定是最后真正执行的文件。

建议：

- 校验和执行使用同一个 canonical target。
- 如果必须支持 coreutils/busybox applet，使用明确的允许目录和稳定的 applet 映射。
- 不要在校验后恢复未经重新校验的原始路径。
- 日志中不要输出完整命令、stdin 或可能包含 secret 的参数。

## 四、JNI、线程和生命周期问题

### P1 — Renderer 裸句柄存在释放后使用风险

位置：

- Java：`fable-app/app/src/main/java/com/gph/fable/app/terminal/adapter/FableRenderCoreAdapter.java:65-137, 312-482, 486-497`
- Rust：`spike-render/src/jni.rs:21-41, 94-105`

问题：

Java 方法先在锁内读取 `mHandle`，释放锁后调用 JNI。`destroy()` 可以在这期间把同一个 handle 设为 0 并释放 Rust `Renderer`。

Rust JNI 又直接把 `jlong` 转换为 `&mut Renderer`，`rendererDestroy()` 用 `Box::from_raw()` 释放它。`catch_unwind` 只能捕获 panic，不能使无效指针安全。

影响：

- 释放后使用。
- 双重释放。
- Activity 重建、Surface 销毁、reset 和后台回调并发时可能崩溃。

建议：

- Rust 侧建立 handle registry：`handle -> Arc<Renderer>` 或带 generation 的槽位。
- destroy 只标记关闭并等待在途调用结束。
- Java 侧不要暴露“读取裸句柄后再调用”的模式。
- 把生命周期同步放在一个 owner 中，不要由每个 wrapper 自己猜测。

### P1 — PTY 输出全部投递主线程

位置：

- `fable-app/app/src/main/java/com/gph/fable/app/session/RustPtySession.java:63-74`

问题：

每个 Rust `output_chunk` 都通过 `Handler(Looper.getMainLooper()).post()` 投递。Rust reader 每次最多读取 4096 字节，长输出会生成大量主线程任务。

已有压力资料显示：4 会话、180 万行输出场景下，Rust 会话层创建期约 1.48GB VmRSS 尖峰；主线程输出队列会进一步放大 UI 延迟和临时 byte[] 保留。

建议：

- native 回调写入每会话有界队列。
- Java/Kotlin 侧只 post 一次 drain runnable。
- drain 时批量消费多个 chunk。
- 队列满时执行明确的背压或合并策略，不静默无限排队。
- `onOutput` 只在主线程做最小状态转发，不做大块解析或日志拼接。

### P1 — Activity Context 被会话级 Renderer 持有

位置：

- `FableRenderCoreAdapter.java:39, 45`
- `FableTerminalView.java:96`

问题：

`FableTerminalView` 把 `getContext()` 传入 `FableRenderCoreAdapter`，而 Renderer 适配器随 `TerminalSession` 和 Service 存活。配置变化或 Activity 重建后，适配器可能继续持有旧 Activity Context。

影响：

- Activity、View 树和窗口资源泄漏。
- 多次重建后内存持续增长。

建议：

- 只保存 `context.getApplicationContext()`。
- 更好的方案是 `FontAssets.install()` 接收 Application Context，Renderer 适配器本身不持有 Context。
- 把 Surface 和 Activity 生命周期留在 `FableTerminalView`，不要让会话级对象持有 UI Context。

### P1 — Renderer 创建失败路径会在清理时 NPE

位置：

- `FableTerminalView.java:92-103`
- `FableTerminalView.java:307-312`

问题：

Renderer 创建失败时 `adapter` 可以为 null，`SessionRender` 仍会被放入 map。之后 `destroy()` 无条件调用 `adapter.destroy()`。

影响：

- 异常环境下，错误处理路径本身可能崩溃。

建议：

- 用显式 `UnavailableRenderer` 状态，或让 `SessionRender` 只在 adapter 非 null 时创建。
- `destroy()`、`release()`、`show()` 都必须处理 unavailable 状态。
- 增加“rendererCreate 返回 0 → session remove → cleanup”测试。

### P1 — 事件回调和句柄释放没有统一取消协议

位置：

- Rust：`spike-session/src/jni_handle.rs:182-230, 421-430`
- Java：`RustPtySession.java:123-139`

问题：

- Rust 每会话一个附着 JVM 的事件线程。
- Java `close()` 立即把 `mHandleReleased` 设为 true。
- 已投递到主线程的 callback 仍可能在 Activity 或 Session 已经清理后执行。
- 全局日志 dispatcher 一旦启动就永久运行；取消 callback 不会停止线程，也不能重新启动一个新的 dispatcher。

建议：

- 关闭顺序固定为：停止接收 → 移除 callback → drain/取消 Java 主线程任务 → 关闭 native session → join dispatcher。
- 为 callback 使用 generation/token，旧 generation 的事件直接丢弃。
- 全局日志线程使用可停止的 receiver 或统一进程生命周期 owner。
- 缓存 JNI method ID 和签名，不要每个事件重新构造 method signature。

## 五、文件流、Intent 和生命周期问题

### P1 — FileReceiverActivity 重复处理 Intent

位置：

- `FileReceiverActivity.java:64-126`

问题：

文件处理放在 `onResume()`，同一个 Intent 在对话框关闭、Activity 暂停后可能再次处理。

同时：

- `handleContentUri()` 在弹出对话框前打开 `InputStream`。
- `saveStreamWithName()` 不关闭输入流。

影响：

- 同一份内容可能保存或执行两次。
- ContentProvider fd 泄漏。
- 延迟操作使用已经失效的 stream。

建议：

- 在 `onCreate()`/`onNewIntent()` 处理并消费 Intent。
- 保存 `Uri`，在用户确认保存时重新打开流。
- 所有复制操作使用 try-with-resources。
- 增加“暂停/恢复/重复 onResume”测试。

### P2 — Service 暴露可变内部会话列表

位置：

- `FableService.java:875-885`
- 使用方：`FableTerminalSessionActivityClient.java:167-176`

问题：

`getFableShellSessions()` 返回内部 mutable list。调用方遍历时，退出回调仍可在 `FableService.java:640-650` 修改该列表。

影响：

- `ConcurrentModificationException`。
- 视图读到部分更新状态。
- Service 的 synchronized 只保护 getter 返回动作，不保护调用方后续迭代。

建议：

- 返回 `new ArrayList<>(...)` 或不可变快照。
- 更好的方案是提供 `forEachSessionSnapshot()` 或事件驱动的更新接口。
- 将 session collection 的所有修改集中到一个 owner。

### P2 — Service 锁覆盖 PTY 创建和回调

位置：

- `FableService.java:575-595`

问题：

`createFableShellSession()` 持有 Service monitor，同时执行 `FableShellSession.execute()`、PTY 创建和回调处理。

影响：

- 如果调用来自 UI 线程，会阻塞界面。
- 创建一个会话会串行化无关的 Service 状态访问。

建议：

只在快照和最终插入列表时持锁；PTY 创建在锁外进行，最后用状态校验完成原子提交。

## 六、性能和内存问题

### P1 — 字体安装和完整 SHA-256 校验发生在渲染器构造路径

位置：

- `FontAssets.java:35-52, 87-104`
- `FableTerminalView.java:96`

问题：

- `FontAssets.install()` 是 synchronized。
- 每次 renderer 创建都可能读取并 hash AppleColorEmoji 字体。
- Apple 字体约 30 MB。
- `sha256()` 用 `String.format()` 逐字节格式化，额外产生大量临时对象。
- 构造路径由 `attachSession()` 触发，位于 UI 生命周期路径。

影响：

- 多会话创建时串行化。
- 冷启动或切换时 UI 卡顿。
- 与已观测的 native 内存创建尖峰叠加。

建议：

1. 在 Application 初始化阶段异步安装和校验字体。
2. 以文件大小、mtime、版本号和已知 digest 做缓存。
3. digest 转 hex 使用无分配的查表实现。
4. Renderer 只接收已经准备好的字体路径，不负责文件安装。
5. 用性能测试记录字体安装耗时和分配量。

### P1 — Rust 会话层创建期内存尖峰仍未归因

证据：

- `.scratch/fable-v1/baseline/对比报告-27-rust-vs-java.md`
- `.scratch/fable-v1/内存优化-发散记录与共识.md`

已知数据：

- Java 4 会话峰值约 949 MB。
- Rust 4 会话峰值约 1.48 GB。
- Rust 创建期比 Java 多约 540 MB。
- Rust 稳态随后回落，与 Java 相当或更低。
- 8 会话压力下主进程约 1.72 GB，设备出现 LMK。

结论：

这不是“已经证明 Rust 一定泄漏”，但它已经超过 8GB 目标设备的安全预算。当前没有创建尖峰时刻的 `dumpsys meminfo` 或 malloc profile，因此不能直接指定某个分配点为根因。

下一步必须先测量：

1. 会话创建前后 native/Dalvik/Graphics 分块。
2. 字体、图集、核心 scrollback、PTY 和 JNI 缓冲的独立占用。
3. 每个会话的分配曲线。
4. 创建完成后 1、5、10 分钟的回收曲线。

在归因前，不建议凭感觉改 scrollback、图集或事件队列。

### P2 — Renderer mailbox 无界，写入时复制完整 byte[]

位置：

- `spike-render/src/render_android.rs:4645`
- `:4674-4675`
- `RenderCommand::Write(Vec<u8>)`

问题：

Renderer 使用无界 `std::sync::mpsc::channel()`。每次 `write()` 都将 JNI 输入复制成新的 `Vec<u8>` 再进入队列。

影响：

- 输出速度超过渲染速度时，队列可以持续增长。
- 产生大量短生命周期 byte buffer。
- 多会话切换或后台 Surface 缺失时，积压会扩大。

建议：

- 使用有界 mailbox。
- 设计明确的输出合并策略。
- Surface 不可见时只保留核心需要的最小输入，不把每个 chunk 原样排队。
- 通过单一 batch command 合并连续 `Write`。

### P2 — 每次 dirty render 都构造完整快照

位置：

- `spike-render/src/render_android.rs:269-501`
- `:4419-4444`
- `:4585-4597`

问题：

即使只重建少数 dirty rows，`current_snapshot()` 仍会遍历并分配完整 `Snapshot`、每行 `Vec<Cell>`、每个 cell 的 `String`。随后才用 dirty rows 决定上传哪些行。

影响：

- 高输出场景产生大量 cell/string 分配。
- 内存峰值和 GC/allocator 压力增加。
- 增量渲染的收益被完整快照成本部分抵消。

建议：

- 将 snapshot 分成 metadata、dirty row iterator 和按需 cell decode。
- 只读取 dirty rows；需要全量查询时再走 transcript/text 专用路径。
- 为 cell 文本使用共享 scratch buffer 或小字符串优化。
- 用 `RenderStats` 增加每帧 snapshot bytes、cell count 和 allocation proxy。

### P2 — UI 状态轮询造成重复 JNI 同步查询

位置：

- `FableTerminalSessionActivityClient.java:166-189`
- `FableRenderCoreAdapter.java:333-482`

问题：

每 200ms 遍历每个会话，并对 title、bell、mode 等状态发送同步 mailbox 查询。每个查询都会创建 channel、发送 command、阻塞等待渲染线程。

影响：

- 会话数量增加时，主线程工作量按会话数增长。
- 渲染线程被大量小查询打断。

建议：

- 用 renderer 事件/dirty mask 推送状态变化。
- 或一次查询返回完整 `UiState`，减少 10+ 次 JNI 往返。
- 只轮询当前会话；后台会话使用事件队列。

### P2 — 诊断日志同步写外部存储且无轮转

位置：

- `fable-app/app/src/main/java/com/gph/fable/app/terminal/FableDiagnostics.java:48-131`
- 调用点：`FableTerminalView.java:45-52`、`FableRenderCoreAdapter.java:207-250`

问题：

- `append()` 同步尝试写 Download、MediaStore 或私有文件。
- watchdog 每 500ms 运行并写诊断内容。
- 日志文件没有大小上限和轮转。

影响：

- UI 或 Surface 回调可能阻塞在文件 I/O。
- 长时间运行会持续增长外部日志。

建议：

- 用有界异步日志队列和单独 writer 线程。
- 按大小轮转，保留最近 N 个文件。
- watchdog 只记录状态变化和限频事件。
- 普通构建关闭详细外部诊断，调试开关显式启用。

## 七、可读性、简洁性和一致性问题

### P2 — 过大的模块和职责混合

代表文件：

- `spike-render/src/render_android.rs`：约 184 KB。
- `fable-app/terminal-view/.../TerminalView.java`：约 72 KB。
- `fable-app/app/.../FableService.java`：约 46 KB。
- `fable-app/termux-shared/.../TermuxConstants.java`：约 83 KB。

问题：

同一个文件同时包含：

- 外部 API。
- JNI 细节。
- 生命周期。
- 状态查询。
- 渲染策略。
- 诊断。
- 兼容逻辑。

建议：

按深模块边界拆分，而不是按“每个方法一个文件”拆分。例如：

- `renderer_core.rs`：核心和快照。
- `renderer_atlas.rs`：字形图集。
- `renderer_gpu.rs`：wgpu/Surface。
- `renderer_queries.rs`：选择、文本、光标查询。
- `renderer_ffi.rs`：JNI 与句柄管理。
- `SessionOwner`：会话生命周期和 callback 所有权。

### P2 — FableRenderCoreAdapter 有大量重复 wrapper

位置：

- `FableRenderCoreAdapter.java:65-482`

问题：

大约 35 个方法重复“加锁读取 handle → 解锁 → JNI 调用”的结构。重复不仅影响简洁性，也让生命周期修复必须修改许多位置。

建议：

- 使用一个集中式 `withLiveRenderer()` 门面。
- 或直接把 handle 生命周期移入 Rust owner。
- wrapper 只保留 API 语义，不自己管理 raw handle。

### P2 — Kotlin 迁移产生的警告没有形成质量门槛

当前编译警告包括：

- nullable 值传入 non-null 参数。
- unchecked array cast。
- “condition is always true/false”。
- 大量 deprecated Android API。
- JNI deprecated API。
- Rust unused/dead-code/naming warnings。

建议：

- 将新代码的 nullability warning 设为错误。
- 为 JNI 和文件系统边界增加明确的 nullable/validated 类型。
- 给迁移后的 Kotlin 类设置 warning baseline，但禁止新增 warning。
- 对 Rust 使用 `cargo clippy --all-targets -- -D warnings`，对 FFI 命名保留局部 allow 并写明原因。

### P3 — FableSessionSpec 和 TerminalSession 保存可变数组

位置：

- `fable-core/.../FableSessionSpec.java:24-49`
- `fable-core/.../TerminalSession.java:97-105`

问题：

构造函数直接保存 `args/env` 数组，getter 也直接返回内部数组。调用方可以在会话创建后修改 shell 参数和环境快照。

建议：

- 构造时 clone。
- getter 返回 clone 或不可变集合。
- `TerminalSession` 也不要直接保存调用方数组。
- 增加“构造后修改原数组不影响 session”测试。

### P3 — Android 模块 targetSdk 不一致

位置：

- `app/build.gradle`：targetSdk 35。
- `fable-core/build.gradle`、`terminal-view/build.gradle`、`termux-shared/build.gradle`：targetSdk 28。

问题：

这会让模块行为、manifest 合并和 API 约束不一致，也容易让开发者误判 Android 15 的真实行为。

建议：

- 在根 Gradle convention 中统一 compile/min/target 配置。
- 只有明确需要旧 target 的库模块才保留差异，并记录原因。

## 八、做得好的部分

本次审查也确认了几项值得保留的设计：

1. `CoreAdapter` 已成为 UI 与终端核心的明确接缝。
2. 会话层的 `SessionManager`、`Session`、事件流和 JNI 已经有基本分层。
3. Rust 会话层使用有界事件通道，并避免在 reader 阻塞读时持有大锁。
4. `Session` 的自然退出路径已经补齐 `session_closed`，修复了会话退出后 Kotlin 不收尾的问题。
5. 渲染器有 dirty row、内容签名和 `RowVertexStore`，不是每一帧都无条件上传整屏。
6. 字形、emoji、宽字符、ZWJ 和 palette 有对应离屏/单元测试。
7. 文件操作中部分输出流已经使用 try-with-resources；问题主要集中在外部输入和延迟回调路径。
8. 性能报告已经真实记录了 Rust 内存尖峰和 8 会话 LMK，而不是只报告“测试通过”。

## 九、推荐修复顺序

### 阶段 1：安全和生命周期阻断项

1. 移除 Gradle 中的签名密码，隔离 debug/release 签名。
2. 统一实现 canonical path containment。
3. 修复 FileReceiver 的 basename、覆盖和 stream 生命周期。
4. 修复 Renderer/JNI handle registry 和 destroy 并发问题。
5. 修复 callback 取消、主线程队列和 renderer failure cleanup。

### 阶段 2：性能稳定性

1. 为 PTY 输出增加批处理和有界队列。
2. 为 Renderer mailbox 增加背压和 write 合并。
3. 把字体安装移出 Renderer/UI 构造路径。
4. 先测量并归因 Rust 会话创建期内存尖峰。
5. 将 UI 状态轮询合并为一个状态快照或事件推送。
6. 将诊断日志改为异步、有界、可轮转。

### 阶段 3：结构和维护性

1. 拆分 `render_android.rs`、`FableService.java` 和 TerminalView 大文件。
2. 集中 JNI wrapper 和生命周期门面。
3. 清理 Kotlin/Rust 编译警告，建立新增 warning 不能增加的门槛。
4. 统一 Android 模块的 SDK 配置。
5. 为文件系统、Intent、SessionSpec 和 callback 生命周期补回归测试。

## 十、最终判定

当前代码不是“烂尾楼”。核心方向、测试意图和许多底层实现已经有良好基础。

但是，它已经出现几处典型的“接缝补丁”：

- 同一个资源由多个层分别管理生命周期。
- 同一个 raw handle 在多个 wrapper 中重复读取。
- 同一个外部路径在多个 Provider 中分别校验。
- 同一个输出流分别经过 Rust event、JNI callback、主线程 Handler 和 CoreAdapter。
- 同一个状态通过轮询和事件两套路径传播。

如果现在继续叠加功能，这些接缝会把问题放大。建议先完成阶段 1 的安全和生命周期修复，再开始专门的内存性能窗口。完成后，Fable 才更接近“设计、施工和维护都一致”的长期代码库。
