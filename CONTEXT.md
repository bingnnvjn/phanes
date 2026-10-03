# Phanes（原名 Fable）领域词汇与上下文

> 单一事实源：本文件定义项目词汇；架构决策见 `docs/adr/`；事实来源映射见 `docs/agents/ssot.md`。

## 项目一句话

Phanes（原名 Fable／寓言）= 私人 Android 终端 App：**Kotlin 壳 + Rust 底层 + libghostty-vt 核心**。应用身份 = `com.gph.fable`（已冻结，见 ADR-0013）。

## 分层（从外到内）

- **Kotlin 壳**：界面、输入法、权限、生命周期、设置页（XML/经典 View，ADR-0001）
- **Rust 底层**：渲染器、会话层（终态）、事件流（ADR-0003/0004）
- **核心（引擎）**：`libghostty-vt`——终端模拟核心（解析器 + 网格 + 滚动缓冲 + 状态），**无渲染**（ADR-0003，已定）

## 核心术语（用词以此为准，勿漂移）

| 术语 | 含义 |
| --- | --- |
| 会话层 | PTY + 进程 + 环境 + 生命周期；唯一实现 = Rust（libfable-session，portable-pty）；Java 会话层已随工单 27 下线删除（2026-08-11） |
| 渲染状态 | 核心交给渲染器的数据（脏行、格子文本/样式、配色板、光标）；C API `ghostty_render_state_*` |
| 画法层 | 把渲染状态变成像素的部分（字形图集、GPU 绘制、shader） |
| 快照层 | Fable 渲染器内部"渲染状态 → 结构化快照"的一层（脏行/行文本/样式 run/光标/模式/滚动） |
| 字形图集 | 字形光栅化后放进 GPU 纹理的缓存（灰度/彩色） |
| 脏行 | 渲染状态标记的"需要重画的行" |
| 核心/引擎 | 指 `libghostty-vt`（ADR-0003 已定；可替换但当前不讨论） |
| CoreAdapter | 换引擎的缝（喂字节 / 拉网格 / 脏行事件 / 能力清单）；v1 UI 只对着它写 |
| 应用身份 | Android 的 `applicationId` 及其派生物：`$PREFIX`、JNI 符号前缀、签名身份；改名只动名字层，不动它（ADR-0013） |
| Fable 包仓库（fable-repo） | 以 `com.gph.fable` 前缀构建、供 Fable 环境 `apt` 安装的包源（ADR-0005）；区别于官方 termux-main |
| bootstrap 归档 | Fable App 启动所需的预构建最小系统（bootstrap zip + 全量 .deb 归档，工单 06 产物） |
| 构建输入 | 构建 APK 所需、但不是本项目源码的外部资产：bootstrap 归档、核心的预编译 `.a`、字体、预编译 `.so`、工具链 |
| 操作记录 | 只在当时那一刻成立的过程产物：真机运行数据、诊断现场、一次性恢复副本、签名材料台账；不进仓库（ADR-0012） |
| sbix | Apple 彩色 emoji 位图格式：每个字形一张固定尺寸 PNG，分档存放（40/64/96/160px）；本仓库用 160px 单档 |
| 超采样 | 解码 160px 档再缩放到目标格子尺寸，保证小字号下最清晰 |

## 决策指针

- ADR-0001：v1 用 XML/经典 View，不引入 Compose
- ADR-0002：v1 最小集（字号/主题/键盘/会话恢复）
- ADR-0003：终态 = Kotlin 壳 + Rust 底层 + libghostty-vt 核心；验证切片先行
- ADR-0004：渲染器 = Rust + wgpu（安卓 Vulkan），以 Shellow 为骨架、按官方架构补齐
- ADR-0005：Fable 包仓库（自建 fable 前缀扁平 apt 仓库，GitHub Releases 托管 + GPG 签名）
- ADR-0006：彩色字形通路 = FreeType + 内嵌 NotoColorEmoji COLRv1（ZWJ 经 rustybuzz 整形一并解决；灰度正文保持 fontdue）
- ADR-0007：emoji 字体 = Apple Color Emoji 21.4d3e1（sbix 160px 单档）+ Rust 自解析 sbix + Noto 兜底（2026-08-10 定案）
- ADR-0008：会话层 = Rust（portable-pty 主选 + 事件流六事件最小集）；Java 会话层已下线（2026-08-11 工单 27，过渡期结束）
- ADR-0009：Kotlin 壳重构（终态收口）——全仓 Java→Kotlin、删旧模拟器/旧渲染器、XML/经典 View 不变、AGP 9 内置 Kotlin、terminal-emulator 改名 fable-core（2026-08-12 决策窗口 9 定案；该模块的目录与 Gradle 模块名自工单 63 起按 ADR-0011 用角色名 `core`）
- ADR-0010：公开三个独立 Rust crate，以 GitHub Free 的 public 仓库规则保护合并；Fable App、libghostty-vt 集成输入和工程内部资料继续私有（2026-08-19）
- ADR-0011：单一仓库形态——五段历史合并、目录改用角色名（`android/`、`renderer/`、`session/`、`boo/`）、先清理后合并；ADR-0010 中"三个 crate 独立公开"的条款作废（2026-10-01 决策窗口 10）
- ADR-0012：公开边界——目标是一个完整公开仓库；入库 = 产品 + 设计 + 可复现的结论；操作记录与构建输入不入库（2026-10-01）
- ADR-0013：项目改名 Phanes；应用身份 `com.gph.fable` 冻结；本轮改对外可见层（仓库名、文档、App 显示名，工单 64 已完成），代码标识符改名另行排期，清单见 `docs/rename-inventory.md`（2026-10-01）
- ADR-0014：一次性历史清理——按用户 2026-10-02 裁决，把一次性迁移脚本与内部安全台账从全部历史移除；这是 ADR-0011 决定 3 的一次性例外，此后重写历史仍需新授权
- ADR-0015：构建链宿主矩阵——四个原生库由 `scripts/build-native-libs.sh` 一条通路生成；Termux 宿主用 `clang` + `ndk-sysroot`，其它宿主用官方 NDK（2026-10-02）
- ADR-0016：公开切换用「同名重建仓库」执行——删除并同名重建 `phanes` 后先复测旧 SHA 必须 404，再切 public；取代 2026-10-03 决策窗口 11 的"切现有仓库，不新建"（2026-10-03）
