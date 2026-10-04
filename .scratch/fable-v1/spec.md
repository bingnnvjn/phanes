# Fable v1 规格（Spec / PRD）

> Status: 待开工
> 功能目录：fable-v1
> 更新：2026-10-04
> 来源：项目总览与交接 + 本仓库现状探索（to-spec）

> 架构约束（2026-08-07 补，见 `docs/adr/0003`、`docs/adr/0004`）：v1 交付范围不变（纯终端、打开即终端）；UI 只对着 **CoreAdapter** 缝写，保证后续换核不动 UI；终态方向 = Kotlin 壳 + Rust 底层（渲染/会话/事件流）+ libghostty-vt 核心，渲染器定案 Rust + wgpu（安卓 Vulkan）。
> 会话层 Rust 方向（2026-08-10 决策窗口 8，见 `docs/adr/0008`）：终态会话层（PTY/进程/环境/生命周期）全进 Rust、独立先行（不并入 Kotlin 壳重构）、双实现并行 + 切换开关、事件流最小集定案；落地工单 23（基线）→ 24（验证切片）→ 25（会话层实现+事件流+JNI）→ 26（双实现集成+切换）→ 27（验收对比+Java 下线评估）。
> 集成定案（2026-08-07，方向"正式集成主终端" to-spec）：主终端渲染路径切换为 Fable 自研渲染器（fable-render：libghostty-vt 核心 + Rust wgpu 画法层，ADR-0003/0004，工单 08–12 已验证）；UI 只对着 **CoreAdapter** 缝写；会话层（Termux Java：PTY/进程/环境/生命周期）阶段一不动。落地工单：14（渲染器集成 API 补齐）→ 15（主终端正式集成）→ 04（v1 最小集 UI，Blocked by 15）。

## Problem Statement

我有一台装有 Termux 的 Android 手机，里面跑着私人 Linux 环境：装好的包、配置好的 dotfiles、runit 服务，全是长期积累的资产。但 Termux 是别人的产品——包名、签名、UI 都不是我的，而且与已安装的 F-Droid Termux 签名冲突，换壳产物无法直接装到手机上。我想要一个真正属于自己的 Android 终端 App（Fable / 寓言）：打开就是终端，能整体继承现有环境，装上就能用，还能以稳定身份长期更新，而不是永远停在"fork 出来的 Termux"状态。

## Solution

Fable v1 是一个纯终端 Android App：包名 `com.gph.fable`、正式签名、点击图标直接进入终端，无首页。它以 termux-app fork 为引擎底座：会话层底盘（Termux Java：PTY/进程/环境/生命周期）阶段一不动，**渲染路径切换为 Fable 自研渲染器**（fable-render：libghostty-vt 核心 + Rust wgpu 画法层，ADR-0003/0004 产物，工单 08–12 已验证），界面/键盘/手势由 Fable 自研，UI 只对着 CoreAdapter 缝写。现有环境按确认清单**复制**入 Fable 的新数据目录（新 $PREFIX）：先搬家、不拆房——迁移全程旧 Termux 及其数据保持原样，一个字节不少。v1 不做首页、不做 AI、不做服务管理——打开即终端，一切为终端让路。

## User Stories

1. As a 用户, I want 在 Termux 还装着的状态下把 Fable（包名 com.gph.fable）装到手机上, so that 两个 App 并存、迁移期不丢环境。
2. As a 用户, I want 点击 Fable 图标直接打开终端（无首页、无引导页）, so that 体验是"打开即终端"。
3. As a 用户, I want 终端里能正常输入命令并看到输出（echo / pwd / ls 等）, so that 它首先是可用的 shell。
4. As a 用户, I want 终端支持滚动、方向键、复制粘贴等基本交互, so that 长输出可读、命令行可编辑。
5. As a 用户, I want App 的显示名称与图标是 Fable（寓言）而不是 Termux, so that 它是我的产品而不是换壳。
6. As a 用户, I want 长任务在切后台、转屏、锁屏后继续运行, so that 编译和下载不中断。
7. As a 用户, I want 可以新建、切换、关闭多个终端会话, so that 可以并行干多件事。
8. As a 用户, I want App 重启后能恢复会话（以 v1 最小集定案为准）, so that 不用每次从头再来。
9. As a 用户, I want 我现有的 Termux 环境（已装包、dotfiles、~/.termux 配置、runit 服务配置）整体搬进 Fable, so that 不需要重装重配。
10. As a 用户, I want 迁移是可验证的（shell 可用、包可执行、$PREFIX 指向新数据目录）, so that 删旧 App 前我有把握。
11. As a 用户, I want 迁移是复制而非移动，迁移后旧 Termux 及其数据一个不少, so that 旧环境永不丢失。
12. As a 用户, I want Fable 用我自己的稳定 keystore 签名, so that 以后每个版本都能覆盖安装更新。
13. As a 用户, I want 构建出的 APK 能在我的 aarch64 手机上安装并启动, so that 它真的能用而不是只在构建日志里"绿"。
14. As a 用户, I want Android 15 上通知与前台服务行为正常, so that 会话不会被系统随意杀死。
15. As a 用户, I want 基本的个性化（字号、主题、键盘布局，以 v1 最小集清单为准）, so that 用着舒服且是我的风格。
16. As a 用户, I want App 里不出现 AI 面板或工作台入口, so that v1 保持纯终端简洁。
17. As a 开发者, I want 一条可重复的构建 + 校验命令, so that 每次改动后能快速确认产物正确。
18. As a 开发者, I want 包名与 bootstrap 变体保持一致（改包名不破坏启动）, so that 不出现"能装但一开就崩"。
19. As a 用户, I want Fable 不依赖 Termux 配套 App（API/Boot/Float 等）, so that 单独安装即完整可用。
20. As a 用户, I want 卸载旧 Termux 后一切正常（启动脚本、runit 服务由 Codex 在新环境重配）, so that 迁移后没有"回头路"依赖。
21. As a 用户, I want 终端画面由 Fable 自研渲染器绘制（真彩色、中文/emoji、滚动流畅）, so that 它是我的产品而不是 Termux 换壳。

## Implementation Decisions

1. **基线**：termux-app v0.119.0-beta.3 fork，模块结构不变——app（界面/Activity/会话服务）、termux-shared（共享常量、bootstrap、shell 管理）、terminal-view（渲染视图）、terminal-emulator（仿真核心）。其中 app、termux-shared、terminal-emulator 三个模块含 JNI。
2. **包名（永久身份）**：`com.gph.fable`。applicationId、manifest package、manifest 占位符（驱动 sharedUserId、provider authorities、RUN_COMMAND 权限名等）、Java 包名与代码内引用全部替换；新数据目录由新包名决定，即新 $PREFIX。
3. **签名**：生成个人 keystore（正式签名），debug 与 release 共用该身份，建立"覆盖安装即更新"的循环；现有 debug key 不作为正式产品身份。
4. **启动路径**：launcher 直接进入终端 Activity，无首页；复用现有"会话服务 + PTY + 进程 + 环境 + 生命周期"底盘，v1 不动会话层。
5. **界面层**：v1 界面、键盘、手势为 Fable 自研（XML/经典 View，ADR-0001，不引入 Compose）；渲染核心经 **CoreAdapter 缝**接入——主终端使用 Fable 渲染器（fable-render：libghostty-vt 核心 + Rust wgpu 画法层，ADR-0003/0004），TerminalView/TerminalEmulator 保留为旧路径但不再承担主终端渲染。UI 代码不依赖具体核心实现。v1 最小集清单（字号/主题/键盘/会话恢复/设置精简/界面中文化）见 ADR-0002，在集成落地（工单 15）之上实施（工单 04）。
6. **数据迁移（先搬家、不拆房）**：只复制、不搬走——旧 Termux 及其数据在迁移全程保持原样，不删除旧 App。迁移内容按用户确认清单（详见工单 02）：CODEX/ 全量、配置与身份（.bashrc 两个都留、.profile、.gitconfig、.gitignore、.ssh、.termux、AGENTS.md）、服务脚本（个人服务脚本（若干））、代理配置（.agents、.pi）；已装包按包清单在新环境重新安装（不拷贝二进制，避免硬编码旧路径）；第②类项目与第③类缓存/临时产物一律不迁。机制：备份到共享存储 → Fable 恢复 → 验证（shell/配置/服务）→ 旧 App 原样保留；触发形态（手动脚本 vs App 内流程）留待 ticket 定案。
7. **Android 15**：通知权限、前台服务类型、edge-to-edge 与 targetSdk 升级一并处理；当前 targetSdk/compileSdk 陈旧，需在此项内统一升级并重验构建。
8. **构建约束（技术澄清）**：aarch64 手机本地构建——官方 NDK 与 AAPT2 是 x86_64-only，使用 Termux 原生 clang、假 NDK 与原生 aapt2 覆盖；原生库以 jniLibs 直供 .so；改包名后仍须保持 bootstrap 变体（apt-android-7）与代码内 PackageVariant 一致，否则启动崩溃；构建成功不等于产物正确，必须校验 APK 内容。
9. **架构方向（非 v1 实现）**：终端三层（界面层/仿真核心/会话层）、CoreAdapter 换引擎缝、Environment 环境接口、会话事件流、HUD 交互语言——v1 只保证不阻碍这些方向（如事件流定义成本低，可在需要时补），不在 v1 实现。
10. **主终端渲染路径（2026-08-07 集成定案）**：会话层 Termux Java（PTY/进程/环境/生命周期）保持不动；字节经 CoreAdapter 缝从会话层喂给 fable-render 核心（libghostty-vt）；每会话一个渲染器 + 一个 SurfaceView；渲染驱动 = 独立渲染线程 + mailbox（工单 12 产物）；选中文本读取、字号、配色板等集成所需能力经渲染器 JNI API 补齐（工单 14），主终端接线与真机验收见工单 15。
11. **包安装源（2026-08-09 决策窗口 5）**：Fable 环境不消费官方 termux-main（com.termux 前缀包路径硬编码）；自建 Fable 包仓库（fable-repo，ADR-0005）——扁平 apt 仓库托管于 fable-bootstrap GitHub Releases（`latest/download` 稳定 URL），GPG 签名，CI 按需构建指定包；迁移期先本地 dpkg 装归档内 debs（工单 02 现状），自举 + 日常缺口（git/nodejs/openjdk/rust/clang/runit 等）经仓库按需补齐。
12. **彩色字形通路（2026-08-09 决策窗口 6 / 工单 13，ADR-0006）**：彩色 emoji 字形 = FreeType 光栅 COLRv1 + 内嵌 NotoColorEmoji（COLRv1 完整版，Unicode 17.0，替换 2017 CBDT）；ZWJ 家庭/肤色/旗帜经 rustybuzz 整形一并解决；✅ 接受原生彩色绿勾；灰度正文保持 fontdue（FreeType 只接彩色字形）；下划线专项并入工单 13；图集增量上传与灰度统一为独立 backlog。
13. **emoji 字体（2026-08-10 决策窗口 7 / 工单 22，ADR-0007）**：彩色 emoji 主字体切换为 Apple Color Emoji `21.4d3e1`（sbix，只保留 160px 档，恒 160 超采样缩放，Rust 自解析 sbix + png crate，不加 C 依赖）；Noto COLRv1 保留为兜底（回退以完整 cluster 为单位）；Apple 与 Noto 均改为 fable-app APK assets（noCompress）运行时加载（sha256 校验、失败自动降级）；布局保持原生比例（2 格、垂直居中、非透明包围盒视觉居中、行高稳定）；性能指标（新 emoji 首现 ≤50ms、加载 ≤200ms、预热 50 个热门、缓存 512 张 LRU）；更新机制含溯源文件 + 上游自动检查（每周比对，Emoji 18.0 发布后按流程换字体）。
14. **会话层搬 Rust（2026-08-10 决策窗口 8，ADR-0008）**：终态会话层（PTY 生命周期 spawn/close/resize、进程管理、环境注入、字节 I/O）全进 Rust；Java 壳只保留 UI、设置、系统集成（通知/前台服务/Intent）。驱动优先级 = 多 Agent 并行（2–4 个 Agent 同时跑）的并发安全与稳定性第一，语言统一（壳内单一 Kotlin↔Rust 边界、去 Java 第三套 JNI）与事件流为综合收益一并覆盖。零件选型 = **portable-pty 0.9.0（MIT）主选 + nix 自拼（posix_openpt 序列）兜底**——事实依据：portable-pty Unix 实现依赖 `libc::openpty()`，Bionic 自 API 23 起提供该符号（本项目 minSdk 24），Termux clang 实测编译链接运行通过；仅 NDK/GitHub Actions 备用构建线需显式 min API ≥ 23（见 `.scratch/fable-v1/research-会话层Rust零件.md`）。排期 = 独立先行、验证切片先行（工单 24 试编 + 真机探针），不并入 Kotlin 壳重构；过渡 = 双实现并行 + 切换开关，Java 会话层原样保留至验收对比后再评估去留。并发指标 = 2–4 会话硬指标、8 并发设计余量；先采 Java 会话层并发基线（工单 23）作验收对比。事件流（头脑风暴 §3.3 转正）第一版 = 最小集六事件：`command_started` / `output_chunk` / `command_finished` / `exit_code` / `session_created` / `session_closed`，schema 含 session_id、时间戳并留扩展 metadata；Rust 侧产出、经 JNI 事件回调暴露 Kotlin，Kotlin 第一版只接诊断/日志订阅。
15. **Kotlin 壳重构（2026-08-12 决策窗口 9，ADR-0009）**：终态收口——全仓自有 Java→Kotlin（app / termux-shared / terminal-view / fable-core）；UI 框架保持 XML/经典 View（ADR-0001 不用 Compose 部分继续有效），工具链 = AGP 9 内置 Kotlin（默认启用，不引 KGP）；旧模拟器/旧渲染器先扩 CoreAdapter UI 状态 API（title/bell/mode，libghostty-vt 原生已具备）再删除；模块保留四个，terminal-emulator 改名 fable-core；顺序 = Kotlin pilot → 补缝/切缝 → 删旧 → 清理探针与死代码 → 品牌化（Termux*→Fable*，`termux.properties`/`~/.termux` 兼容）→ 分模块迁移 → 选择浮条 + 更多入口 → 全量回归真机验收。外部入口（RUN_COMMAND/文件分享/查看/DocumentsProvider/OpenReceiver）本次暂保留，稳定后原生化再删（用户 2026-08-12 拍板）。范围外：浅色专项 fable-v1/16、内存优化窗口、渲染器新功能。
16. **核心读取缝（2026-10-04 决策窗口 12，来源：代码库架构体检 C1）**：渲染器"从核心读数据"的代码此前散成 9 份（渲染器本体、头部验收程序、7 个无引用的诊断程序），且已经各自漂移——同一段 unsafe 取值有 4 种写法（`truncate` 与 `from_utf8_lossy` 混用），`check` 有两种不兼容策略（一个 panic、一个返回 bool），头部验收程序还有自己的一份格子类型。终态 = 读取能力从渲染线程内部搬出，成一个独立 module，由两个对象组成：一个持核心连接（喂字节、resize、滚动、标题与模式等窄查询），一个持渲染状态（快照与文本读取）。分两个对象不是自造抽象：核心本身就是两个东西，且规定 update 时独占核心实例、读渲染状态时不碰核心（`libghostty/lib/include/ghostty/vt/render.h`，2026-10-04 核实），所以照核心自己的分界切。渲染器本体只保留 GPU 与渲染线程，持有这两个对象。读取的 unsafe 推导、"读之前必须先 update"的规矩、出错语义都只在一处。出错语义按后果分：会改变结果的调用返回错误，单字段读取失败按内部不变量处理（测试期断言）；module 自身不写日志，由调用方决定策略。7 个无引用且无断言的诊断程序删除；快照与格子等数据类型随读取能力迁移，不留旧 import 路径。测试只覆盖抽出来的纯逻辑（取词边界、去行尾空白、续行判断）——renderer 的测试目标必须链接不入库的预编译核心，该 crate 的测试只在 Android 宿主运行（恢复 CI 测试面另单）。范围外：界面侧查询扇形（体检 C2）、离屏画法（体检 C4）、恢复 renderer 的 CI 测试面。

## Rust 严格安全编码门禁（2026-08-14 决策窗口）

Rust 底层的质量目标是“默认不信任，除非通过可重复的自动门禁与独立审查”。适用范围为渲染器、会话层和宿主工具三个 crate；不以“能编译、能运行”作为合并条件。

1. **分层门禁**：每个 Rust 改动必须经过固定工具链、格式、`cargo check`、严格 Clippy、全目标测试、rustdoc、锁文件与依赖策略检查；JNI/FFI/`unsafe` 改动还必须经独立 Agent 按有效性、ABI、所有权、线程、panic/unwind、失败语义和测试逐项审查；Miri、fuzz、突变测试、Loom/Kani、Android HWASan 为定期或专项验证。
2. **严格但不反惯用**：默认 warning 必须为零；逐条启用高价值 lint（`unsafe_op_in_unsafe_fn`、文档化 unsafe、FFI ABI、rustdoc、生产路径 panic/unwrap 等）。不整体启用 `clippy::restriction`、`clippy::nursery` 或全 crate `forbid(unsafe_code)`；受控索引、转换、算术与必要 FFI 只能在局部不变量、测试和审查理由充分时保留。
3. **例外与安全合约**：禁止无理由 `#[allow]`；例外必须可追踪、写清删除条件。每个 `unsafe` block 紧邻 `SAFETY:` 前提，公共 unsafe API 有 `# Safety`；JNI、FreeType、wgpu/native window、PTY、mailbox 和 renderer registry 的所有权/线程/销毁契约必须可审查。
4. **Agent 流程**：每个 Rust 工单先由实施 Agent 使自动门禁全绿，再由独立审查 Agent 对照严格规则、`CONTEXT.md` 与 ADR 阻断式复核；发现存在更简单、更惯用且兼容的 Rust 写法而没有书面理由时，不得合并。
5. **落地顺序**：先固定工具链、建立规则和基线（工单 48）；分别清零会话层与渲染器基线（49、50）；再启用 CI/Agent 门禁（51）和供应链策略（52）；最后加入专项动态验证（53、54）。完整来源与规则草案见 `.scratch/fable-v1/research-Rust严格安全编码规则.md`。

## Testing Decisions

- **好测试的定义**：只测外部行为，不测实现细节；接口即测试面——测试从缝的这一侧驱动完整实现。
- **缝 1（主缝 · Activity 行为缝）**：instrumentation 测试驱动真实 App——launch → 终端可见 → 输入命令 → 输出返回；覆盖启动路径、会话存活、基本交互验收。先例：termux-shared 现有占位 instrumented test（需扩展为真实用例）；辅以真机手动验收（安装、启动、shell 可交互、迁移验证）。
- **缝 2（复用缝 · 核心逻辑 JVM 单元测试）**：仿真核心行为沿用 terminal-emulator 既有 JUnit 测试族（TerminalTest、CursorAndScreenTest、HistoryTest 等为先例）；新增纯逻辑（迁移规划、会话清单、包名相关常量）沿用 app 模块 plain JUnit 模式（TermuxActivityTest 为先例）。
- **缝 3（产物缝）**：构建产物校验——包名/版本用 aapt2 badging、签名用 apksigner verify、原生库存在性用 APK 内容清单检查；先例为交接文档中的验证命令。
- **emoji 验收（工单 22）**：离屏自动化覆盖类别化样例（旗帜/家庭/肤色/职业 ZWJ/keycap/tag/Emoji 17 新码位/冷门码位）、VS16 行为、Noto 兜底路径与 Apple 36/36 覆盖；性能指标（首现 ≤50ms、加载 ≤200ms、预热时限、缓存上限）进程序化断言；真机主观验收含两档终端尺寸、字号 12–48、深浅主题、对比图、RTL 与选中/滚动无残影。
- **核心读取缝（工单 69）**：读取能力搬出渲染线程后，测试面 = 抽出来的纯逻辑（取词边界、去行尾空白、续行判断），用不接触核心的函数驱动；跨行取词一类语义仍靠真机验收。renderer 的测试目标必须链接不入库的预编译核心，因此该 crate 的测试只在 Android 宿主运行，恢复 CI 测试面单独立项。
- **模块测试范围**：app（启动/会话/迁移）、terminal-emulator（行为回归）、termux-shared（常量/工具逻辑）、terminal-view（渲染集成）。

## Out of Scope

- AI 集成（现有 PWA 工作台继续在浏览器使用）
- 服务管理（runit 那套由 Codex 在新环境里原样重配，不在 App 内实现）
- 删除或清理旧 Termux 及其数据（用户明确不做——迁移只复制、不拆房）
- 环境切换器（Environment 接口）
- 高级终端能力与后续核心演进（连字/图片协议/性能现代化；CoreAdapter 缝与 libghostty-vt 已在 v1 落地，见决策 10）
- HUD 交互设计语言的实现
- Termux 配套 App（API/Boot/Float/Widget/Tasker 等）的依赖或兼容
- 桌面端与其他平台
- 首页、引导页、任何非终端主界面

## Further Notes

- **红线**：当前 APK 仍是 com.termux + debug 签名，与已装 F-Droid Termux 冲突——包名与签名是全部后续工作的前提，优先级最高。
- **迁移内容清单（用户确认版）**：CODEX/ 全迁；配置与身份全迁（.bashrc 含带尾随空格的那个、.profile、.gitconfig、.gitignore、.ssh、.termux、AGENTS.md）；服务脚本与代理配置全迁；第②类项目（ChatGPT_smali/reader/chat-agent-build/pi-pokemon-cn 等）全部不迁；第③类缓存与临时产物（.gradle/.cache/.npm/android-sdk/perf 结果等）全部不迁；已装包按清单重装。
- **未决事项（ticket 阶段定案）**：UI 框架选型（已定案：XML/经典 View，ADR-0001）、v1 最小集清单（已定案：ADR-0002）、迁移触发形态（仍待定，工单 02）。
- 头脑风暴四条（终端三层/环境接口/会话事件流/HUD）是设计方向而非 v1 承诺，记录于此仅作语境，避免后续实现偏离已定的产品逻辑（终端优先、AI 增强）。其中 **3.3 会话事件流已随决策窗口 8 转正**（最小集六事件 + 扩展点，2026-08-10，见决策 14 与 ADR-0008）；3.2 环境接口与 3.4 HUD 交互语言仍为候选，不阻塞本次。
- 工作流约定：本规格与后续 tickets 在同一上下文窗口内产出；每个 ticket 单独干净上下文实施，内部 tdd 驱动，收尾 code-review。
- 构建环境事实（JDK 17、腾讯镜像、gh-proxy 前缀、本地 SDK 路径）在实施 ticket 时按交接文档速查，不在本规格重复。
- 2026-08-06 决策：重建 com.gph.fable 前缀 bootstrap（工单 06，公开仓库 bingnnvjn/fable-bootstrap + GitHub Actions，安全清单见工单）；fable-app 仓库暂不创建。官方 bootstrap 二进制硬编码 /data/data/com.termux 前缀，改包名后必须整体重建（termux-app issue #3973/#1059/#2160）。
- 2026-08-06 决策：子代理消息投递问题已修复（实测可用），恢复按技能原流程使用子代理；推理强度策略见 `$HOME/AGENTS.md`，`.scratch` 模板与提示词中的禁令已移除。
- 2026-08-06 工单 07：集成工单 06 产出的 com.gph.fable 前缀 bootstrap 到 fable-app 并重建 APK（替换 bootstrap-aarch64.zip → 重编 → 装机验收），验收通过后工单 01 的真机验收项随之闭环。
- 2026-08-06 待办（暂缓）：bootstrap motd 品牌化——在 termux-tools 打包层把 `$PREFIX/etc/motd` 文案换成 Fable 欢迎语，随 UI 品牌化阶段再处理；当前保持官方文案，不影响功能。
- 2026-08-06 决策：UI 框架选型与 v1 最小集定案（工单 03）——v1 界面层用 XML/经典 View（ADR-0001）；最小集含字号/主题（外壳三选一 + colors.properties 优先）/键盘/会话恢复（方案 B）/设置页精简（删配套入口、留调试与关于）/界面原生中文（ADR-0002）。
- 2026-08-06 决策（ADR-0003）：引擎与语言架构终态 = **Kotlin 壳 + Rust 底层（渲染/会话/事件流）+ libghostty-vt 核心**。核心选 libghostty-vt（Zig/C API，安卓官方支持；xterm.js 挂起为未来分支）；渲染器必须自研但借代码起步（expo-libghostty / Termux 绘制代码 / Ghostling）；会话层阶段一保留 Termux Java、终态搬 Rust（借 portable-pty / alacritty tty 零件拼装）；B（C/C++ 底层）淘汰。**工单 04 新增约束：UI 只对着 CoreAdapter 缝写**（喂字节、拉网格、脏行事件），今天 TerminalView 实现该缝、终态 libghostty-vt 实现，UI 代码不依赖具体核心。**验证切片（工单 08）先行**：零渲染文本 dump 验证 libghostty-vt + Termux 环境 + 字节管道真机可行，再定正式渲染方案。
- 2026-08-07 方向定案（to-spec）：**正式集成主终端**——fable-render（工单 08–12 已验证）切入主终端：CoreAdapter 缝定案 + 会话层字节管道 + 每会话 SurfaceView 渲染；渲染器集成 API（选中文本/字号/配色板）先补齐（工单 14），主终端切换（工单 15）后，工单 04 最小集在其上实施。TerminalView 旧路径保留不删，作为回退参考；集成稳定后另行评估去留。
- 2026-08-10 决策窗口 8（to-spec）：**会话层搬 Rust**——ADR-0003 决策 4 的空白（搬移时机）已填：独立先行、验证切片先行；范围 = PTY/进程/环境/生命周期全搬，Java 壳保留 UI/设置/系统集成；驱动 = 多 Agent 并发安全第一，语言统一 + 事件流为综合收益；零件 = portable-pty 0.9.0 + nix 兜底（调研文件 `.scratch/fable-v1/research-会话层Rust零件.md`）；过渡 = 双实现并行 + 切换开关；先采 Java 基线；事件流最小集定案。ADR-0008 记录，工单 23–27。
- 2026-08-12 决策窗口 9（to-spec）：**Kotlin 壳重构**——ADR-0003"Kotlin 壳"落地路径定案（ADR-0009）：终态收口、XML/View 不变、AGP 9 内置 Kotlin、先扩缝后删旧模拟器、四模块（terminal-emulator→fable-core）、先删旧再迁、测试策略（旧 152 测试随删，Rust 测试 + 真机回归 + 缝契约测试兜底）。
- 2026-08-19 决策：**公开 Rust crate，以免费规则保护合并**——只公开 `fable-boo`、`spike-render`、`spike-session`；根仓库、`fable-app`、`spike-libghostty`、签名材料和内部资料保持私有。执行顺序为工单 `60 → 61 → 59 → 51 → 55`：先补 renderer 的 unsafe 合约，再审计公开 CI/ref，随后执行公开切换，最后在 public `master` 配置 required checks、CODEOWNERS 与独立 Agent 审查。见 ADR-0010。
