# Fable 领域词汇与上下文

> 单一事实源：本文件定义项目词汇；架构决策见 `docs/adr/`；事实来源映射见 `docs/agents/ssot.md`。

## 项目一句话

Fable（寓言）= 私人 Android 终端 App：**Kotlin 壳 + Rust 底层 + libghostty-vt 核心**，包名 `com.gph.fable`（已定，永久身份）。

## 分层（从外到内）

- **Kotlin 壳**：界面、输入法、权限、生命周期、设置页（XML/经典 View，ADR-0001）
- **Rust 底层**：渲染器、会话层（终态）、事件流（ADR-0003/0004）
- **核心（引擎）**：`libghostty-vt`——终端模拟核心（解析器 + 网格 + 滚动缓冲 + 状态），**无渲染**（ADR-0003，已定）

## 核心术语（用词以此为准，勿漂移）

| 术语 | 含义 |
| --- | --- |
| 会话层 | PTY + 进程 + 环境 + 生命周期；阶段一 Termux Java 不动，终态搬 Rust |
| 渲染状态 | 核心交给渲染器的数据（脏行、格子文本/样式、配色板、光标）；C API `ghostty_render_state_*` |
| 画法层 | 把渲染状态变成像素的部分（字形图集、GPU 绘制、shader） |
| 快照层 | Fable 渲染器内部"渲染状态 → 结构化快照"的一层（脏行/行文本/样式 run/光标/模式/滚动） |
| 字形图集 | 字形光栅化后放进 GPU 纹理的缓存（灰度/彩色） |
| 脏行 | 渲染状态标记的"需要重画的行" |
| 核心/引擎 | 指 `libghostty-vt`（ADR-0003 已定；可替换但当前不讨论） |
| CoreAdapter | 换引擎的缝（喂字节 / 拉网格 / 脏行事件 / 能力清单）；v1 UI 只对着它写 |

## 决策指针

- ADR-0001：v1 用 XML/经典 View，不引入 Compose
- ADR-0002：v1 最小集（字号/主题/键盘/会话恢复）
- ADR-0003：终态 = Kotlin 壳 + Rust 底层 + libghostty-vt 核心；验证切片先行
- ADR-0004：渲染器 = Rust + wgpu（安卓 Vulkan），以 Shellow 为骨架、按官方架构补齐
