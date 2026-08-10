# ADR-0007: emoji 字体 = Apple Color Emoji 21.4d3e1（sbix 160px 单档）+ Rust 自解析 sbix + Noto 兜底

- 状态：已确认（2026-08-10，决策窗口"emoji 字体切换"）
- 日期：2026-08-10
- 范围：spike-render 彩色字形来源（Noto COLRv1 → Apple sbix）与字体打包方式（内嵌 → APK assets）、回退策略（Noto COLRv1 兜底）；灰度正文通路、双图集通道结构、核心（libghostty-vt）与 Kotlin 壳不变。

## 背景

调研事实（2026-08-10，一手核实，日期均标注）：

- 现状（工单 13 产物）：彩色字形 = rustybuzz 整形 + FreeType 光栅 COLRv1（`spike-render/src/colr.rs`）+ 内嵌 `Noto-COLRv1.ttf`（5.0MB，OFL-1.1）；字体经 `include_bytes!` 内嵌进 .so；fable-app 尚无 assets 机制；JNI 桥 `RenderCore.java` 无传字体入口。
- 用户要求：Apple 官方图样、最完整、最高清、体积不做妥协、原生比例不压缩不拉伸。
- **版本事实**：Apple Color Emoji 有独立版本串，不跟 iOS 版本号走。当前最新 = `21.4d3e1`（iOS 26.4 / macOS 26.4 时代，2026-03 发布，覆盖 Emoji 17.0 全部新码位）。macOS 26 最新 7 月构建（samuelngs/apple-emoji-linux `macos-26-20260722`，2026-07-22）版本串仍为 `21.4d3e1` → 无更新。iOS 27 beta 未查到字体更新证据；Emoji 18.0 预计 2026-09 Unicode 定稿，苹果按惯例约 2027 春 iOS 27.4 推出（9to5Mac 2026-01 报道）。
- **来源与原版性**：PoomSmart/EmojiFonts 是社区（越狱圈）仓库，非苹果官方；release `17.0.0-apple` 的 `AppleColorEmoji-160px.ttc` 是从官方 iOS 固件提取的苹果原版位图（其构建脚本以官方 IPSW 字体为输入），不是仿版。交叉验证：iOS 提取版 vs 独立 macOS 提取版（samuelngs）逐像素比对，⚽ 96/64px 两档 **0% 差异**，其余表情颜色一致（如 😀 脸部 iOS `(255,220,49)` vs macOS `(255,221,50)`），差异仅抗锯齿边缘约 4% 像素 → 图样为苹果原版。注意：PoomSmart 为使老 iOS 可用动过 GSUB（合成表），位图本身原版。
- **旗帜事实修正**：Apple 旗帜图存放在组合字形（如 `u1F1E8_u1F1F3`）上而非单个地区字母字形；rustybuzz 整形后 36 个测试 cluster 全部有图（36/36，含 🇨🇳、🏳️🌈）。此前"Apple 无旗帜"结论来自未整形按单码位提取，系误判。
- **文件事实**：`AppleColorEmoji-160px.ttc` = 63,888,516B，sha256 `76c37f95…`（前 24 位 `76c37f95960d82a37a2c93c9`）；版本 `21.4d3e1`；4367 glyphs；strikes 40/64/96/160；160 档 3761 张 PNG。剥离验证（face 0 + 只留 160 档）→ 30,770,720B，sha256 前 24 位 `e7605cad9802272292e9d6cd`，160 档 3761 PNG，版本不变。
- **渲染现状约束**：FreeType 构建关闭 `FT_CONFIG_OPTION_USE_PNG`（工单 13 为裁体积所关），sbix/CBDT 内嵌 PNG 无法经 FreeType 解码；项目已有 `png = "0.18"` crate；rustybuzz 0.20.1 对 Apple 字体整形已验证正常（家庭 → 单 glyph gid 3237、🇨🇳 → gid 423）。

## 决策

1. **字体 = Apple Color Emoji `21.4d3e1`**（PoomSmart release `17.0.0-apple` 的 `AppleColorEmoji-160px.ttc`），**只保留 160px 档**（40/64/96 三档删除），图样零删减（3761 张 PNG 一张不少，不重采样、不改色、不降质）。
2. **清晰度 = 恒从 160px 档解码再缩放**（超采样）；字号 >160px 时用 160 原图放大（接受轻微模糊）；缩放 = 双线性 + 线性空间 gamma 校正，先按此实现，用户验收不满意再换算法。
3. **解码 = Rust 自解析 sbix 表 + 现有 png crate**（类似 `colr.rs` 自解析 COLRv1 的先例），不重开 FreeType PNG/libpng，不新增 C 依赖；rustybuzz 整形照旧，只替换"glyph → 像素"环节。
4. **打包 = Apple 与 Noto 两个字体都改为 fable-app APK assets（noCompress）运行时加载**（JNI 传 fd/路径，Rust 侧 mmap；宿主/Termux 自检走文件路径）；不再 `include_bytes!` 内嵌；加载校验 sha256/版本，失败自动降级 Noto，绝不崩溃。
5. **回退 = Apple 有图用 Apple → Noto COLRv1 → 都没有显示主题适配方框**；回退以完整 cluster 为单位，不拆半；实施时输出 Apple vs Noto 覆盖差异报告（触发 Noto 的完整码位清单）。
6. **布局 = 原生比例、不压缩不拉伸**；占 2 格、垂直居中、按非透明内容包围盒做视觉居中；行高稳定（有无 emoji 行高一致、不顶天立地）。
7. **性能指标**：新 emoji 首现（解码+缩放+上屏）≤50ms；字体加载解析 ≤200ms；启动后 2s 内后台预热前 50 个热门 emoji；解码缓存 512 张 LRU（约 50MB，缓存 160px 原图、缩放变体按需生成）；解码结果直接进渲染图集避免中间拷贝；瀑布流持续刷屏不掉帧、内存不超过缓存上限；单字形解码失败回退 Noto/方框，不崩溃。
8. **更新机制**：溯源文件（原始 ttc sha256、版本串、来源 URL、提取日期、剥离脚本、剥离产物 sha256）+ 换字体步骤文档 + 上游自动检查（脚本/CI 每周比对 PoomSmart 最新 release，有新版即提示）；Emoji 18.0（预计 2027 春）发布后按此流程升级。
9. **验收**：类别化样例离屏自动化（旗帜/家庭/肤色/职业 ZWJ/keycap/tag/Emoji 17 新码位/冷门码位/VS16 行为/Noto 兜底路径）+ 真机主观验收（40×10 与 80×24、字号 12/16/24/32/48、深浅主题、Apple vs Noto 对比图、深色背景边缘干净、RTL 顺序正确、选中/滚动无残影）+ 工单 13 全量回归（下划线、布局、双图集、灰度正文）；诊断输出字体版本串与加载状态（正常/降级/缺失）。

## 理由（带证据）

- 用户明确偏好 Apple 官方图样；`21.4d3e1` 经多来源核实为当前最新（macOS 26 最新构建仍同版本）。
- 原版性经两独立提取渠道像素交叉验证（⚽ 0% 差异、其余颜色一致），排除仿版风险。
- 恒 160 超采样满足"最高清"且规避 40px 档缺家庭/🫶 的问题（实测 40 档 36/36 中缺 2 项）。
- Rust 自解析 sbix 与"恒 160 自选档"的控制需求一致，且 png crate 已在依赖中、`colr.rs` 已有自解析先例，不新增 C 依赖（FreeType 重开 PNG 会与超采样策略冲突且缩放不可控）。
- assets + noCompress + mmap：换字体（Emoji 18）只换文件不重编译，与"保持最新"诉求一致；内嵌则每次升级需重编译发版。
- Noto COLRv1 兜底覆盖 Apple 缺失的冷门码位，避免终端豆腐泛滥；保留 FreeType/colr.rs 通路成本低。

## 冲突标注

- **ADR-0006（部分推翻/细化）**：彩色字形主字体由"内嵌 Noto COLRv1"改为"Apple sbix 主 + Noto COLRv1 兜底"；FreeType COLRv1 通路保留（供 Noto 兜底）；新增 Rust sbix 通路。
- **工单 13（验收项需重写）**：emoji 图样断言按 Noto 设计（🚀 黄焰位置、✅ 绿勾、家庭灰卡宽度等），换 Apple 后按新图样重写；下划线、灰度正文、双图集通道不受影响。
- **内嵌 → assets（接口新增）**：渲染器新增字体加载入口（JNI），`RenderCore.java` 扩展，不破坏既有 API。

## 被否选项

- 继续 Noto COLRv1（用户不选）
- 三星 One UI 变体（用户曾误选为"Apple"；非苹果原版，已纠正）
- FreeType 重开 `USE_PNG` + 静态 libpng 渲染 sbix（与"恒 160 超采样"冲突、新增 C 依赖、strike 选择/缩放不可控）
- 保留 40/64/96 四档（用户明确只保留 160 档）
- 字体仍内嵌 .so（每次换字体需重编译，与"保持最新"冲突）
- 纯 Apple 无 Noto 兜底（冷门码位豆腐多）

## 重开条件

- 上游出现新版本（如 iOS 27.4 / Emoji 18.0）→ 按第 8 条更新流程换字体。
- 低端机性能不达标（首现 >50ms）→ 评估预热扩展/缓存优化；必要时评估保留小档（需用户重新确认）。
- Apple 图样与真机 iOS 合成行为明显不符（GSUB 差异导致序列渲染不同）→ 换用 macOS 独立提取版或 IPSW 直提重做。

## 参考

- PoomSmart/EmojiFonts release `17.0.0-apple`（2026-03-28 发布，04-03 更新；`AppleColorEmoji-160px.ttc` 63,888,516B）
- samuelngs/apple-emoji-linux release `macos-26-20260722`（macOS 独立提取版，交叉验证用）
- 9to5Mac 报道：Emoji 18.0 预计 2026-09 定稿、iOS 27.4 推出（2026-01-08）
- 本仓库：ADR-0006、工单 13、`spike-render/src/emoji.rs`、`spike-render/src/colr.rs`、fable-app `RenderCore.java`
