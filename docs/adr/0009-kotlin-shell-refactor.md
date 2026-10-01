# ADR-0009: Kotlin 壳重构（终态收口）定案

- 状态：已确认（2026-08-12，决策窗口 9）
- 日期：2026-08-12
- 范围：fable-app 壳层 Java→Kotlin 迁移、旧模拟器/旧渲染器退场、模块重组、遗留 UI 落地；v1 已交付行为不回退

## 背景

ADR-0003 已定终态 = Kotlin 壳 + Rust 底层（渲染/会话/事件流）+ libghostty-vt 核心。
工单 27（2026-08-11）后会话层唯一实现 = Rust，但壳仍是全 Java：

- fable-app 共 239 个 `.java`、0 个 `.kt`（2026-08-12 核实）。
- 主终端渲染已走 fable-render + CoreAdapter（工单 15 起），但旧 `TerminalEmulator`（Java 模拟器）仍承担 UI 状态：标题、光标闪烁、选择、鼠标模式、alt screen、配色、粘贴、transcript。
- 旧 `TerminalView`/`TerminalRenderer` 的 Canvas 画法已不承担正文，但输入视图（`FableInputTerminalView`）仍继承其选择/手柄/光标机制。
- 探针 Activity（Spike/Render/SessionProbe/SessionHandleProbe）与 Termux API 尾巴（FileReceiver 族、DocumentsProvider、OpenReceiver、RunCommandService、plugins 目录等）仍在 manifest/代码中。
- 工单 04 遗留"选择菜单自绘浮条 + 更多入口"、工单 27 延期的全量回归、内存优化窗口（`.scratch/fable-v1/内存优化-发散记录与共识.md`）都等本次重构。

事实（2026-08-12 核实）：

- libghostty-vt 原生 C API 已具备 UI 状态能力：`ghostty_terminal_get`（标题）、`title_changed`/`bell` 回调、`ghostty_terminal_mode_get`（alt screen / mouse tracking / cursor visible / blink）、selection、scroll（`spike-libghostty/lib/include/ghostty/vt/terminal.h`）。
- fable-render JNI 已暴露 cursor / selection / scroll，尚未暴露 title / bell / mode（`app/src/main/java/com/gph/fable/app/RenderCore.java` 与 `spike-render/src/jni.rs` 核实）。
- AGP 9.0+ 内置 Kotlin 支持且默认启用，无需再应用 `org.jetbrains.kotlin.android` 插件；本项目 AGP 9.3.0 / Gradle 9.7.0 满足（官方文档 Migrate to built-in Kotlin，2026-03 更新）。

## 决策

1. **范围 = 终态收口**：全仓自有 Java → Kotlin；删除旧模拟器（TerminalEmulator）与旧渲染器（TerminalView/TerminalRenderer 画法）；删除探针；清理确认无引用的死代码；实现选择浮条（PopupWindow 自绘，仅复制/粘贴，样式仿 <厂商 ROM>）与"更多"入口；代码品牌化（`Termux*` → `Fable*`）。配置兼容红线：`termux.properties`、`~/.termux` 的读取键与路径不变。
2. **UI 框架 = XML/经典 View + Kotlin**（ADR-0001 保留其"不用 Compose"部分）：不引入 Compose；Compose 留待 HUD/界面现代化阶段单独评估。
3. **Kotlin 工具链 = AGP 9 内置 Kotlin**：不引入 Kotlin Gradle Plugin；按官方迁移文档使用内置 Kotlin（默认启用），模块按需 `enableKotlin` 开关，编译器选项走 `kotlin.compilerOptions` DSL。
4. **旧模拟器退场 = 先扩缝、后删除**：先给 CoreAdapter/RenderCore 补 UI 状态 API（title_changed / bell / mode 查询，selection/cursor/scroll 已具备），UI 切到只依赖 CoreAdapter，再删除旧 TerminalEmulator 与旧画法。不做"先迁 Kotlin 再删"。
5. **模块结构 = 保留四模块**：app / termux-shared / terminal-view / fable-core（terminal-emulator 删旧模拟器后改名；内容 = CoreAdapter、FableSession 接口族、TerminalSession、KeyHandler 等缝与输入翻译）。依赖方向维持 termux-shared → terminal-view → fable-core。
6. **顺序 = 先删旧、再迁 Kotlin**：Kotlin 工具链 pilot → 补缝/UI 切缝 → 删旧模拟器/旧渲染器 → 清理探针与死代码 → 模块改名与品牌化 → Java→Kotlin 分模块迁移（termux-shared → terminal-view → app）→ 自绘浮条 + 更多入口 → 全量回归 + 真机验收。
7. **测试策略**：旧模拟器 152 个 JVM 行为测试随删除下线；核心行为验证由 Rust 侧测试 + 真机回归兜底；UI 侧补 CoreAdapter 缝契约测试（fake in-memory 核心），覆盖 title/mode/selection/scroll 等 UI 依赖。
8. **范围外**：浅色专项 fable-v1/16、内存优化窗口、渲染器新功能（连字/图片协议等）不进本次；外部入口的原生替代方案不进本次（稳定后单独评估）；工单 27 延期的全量回归作为最后一张验收工单纳入。
9. **外部入口暂保留**（用户 2026-08-12 拍板）：RUN_COMMAND、文件分享/查看、DocumentsProvider、OpenReceiver 及依赖代码本次不删，只随迁移保持行为；待重构稳定后按"原生支持替代"方向再评估删除。

## 冲突标注

- **ADR-0001（部分重开）**：其"不引入 Kotlin"条款被本 ADR 推翻（工具链已升级，AGP 9 内置 Kotlin）；"XML/经典 View、不引入 Compose"仍成立。
- **ADR-0003（执行，非推翻）**："Kotlin 壳"由本 ADR 填充落地路径；终态架构不变。
- **ADR-0008（兼容）**：环境快照继续由 Kotlin 计算传入 Rust（决策 8 不变）；本 ADR 只迁移该代码的语言，不重实现。

## 重开条件

- AGP 9 内置 Kotlin 在 aarch64 手机本地构建链（假 NDK + Termux 原生 aapt2 override）上无法稳定编译 Kotlin 源码 → 回退评估独立 KGP 或先 Java 后迁。
- CoreAdapter 补全 title/bell/mode 后真机验收显示 UI 状态不可用（如 alt screen/mouse 应用行为异常）且无法修复 → 暂停旧模拟器删除，保留双轨并评估原因。
- 用户产品决策变化（如选择浮条交互、外部入口保留清单）→ 以用户验收为准。

## 参考

- Migrate to built-in Kotlin（Android Developers，2026-03 更新）：https://developer.android.com/build/migrate-to-built-in-kotlin
- libghostty-vt C API 头文件：`spike-libghostty/lib/include/ghostty/vt/terminal.h`
- fable-render JNI 现状：`spike-render/src/jni.rs`、`fable-app/app/src/main/java/com/gph/fable/app/RenderCore.java`
- 遗留来源：工单 04 Comments（选择浮条）、工单 27 Comments（全量回归延期）、`.scratch/fable-v1/内存优化-发散记录与共识.md`
