# 22 — emoji 字体切换为 Apple Color Emoji（sbix 160px 单档 + Rust 自解析 + Noto 兜底 + assets 加载）

**What to build:** 把彩色 emoji 字形从"内嵌 Noto COLRv1"切换为 **Apple Color Emoji `21.4d3e1`**（sbix，只保留 160px 档）：新增 Rust sbix 解码通路（png crate），恒 160px 超采样缩放（双线性 + 线性空间），Apple 与 Noto 两个字体均改为 fable-app APK assets（noCompress）运行时加载（mmap + sha256 校验 + 失败自动降级 Noto），Noto COLRv1 作兜底（回退以完整 cluster 为单位），布局保持原生比例（2 格、垂直居中、非透明包围盒视觉居中、行高稳定），性能指标（首现 ≤50ms / 加载 ≤200ms / 预热 50 个热门 / 缓存 512 张 LRU），验收含离屏类别化样例 + 真机主观 + 工单 13 全量回归，诊断输出字体版本与加载状态。

**Blocked by:** None
**Status:** 待验收

## 验收清单

- [ ] 字体资产：剥离后 Apple 字体（face 0、仅 160 档、3761 PNG、版本 `21.4d3e1`、sha256 记录）与 Noto COLRv1 放入 fable-app assets（noCompress）；构建脚本从固定 URL 下载 + sha256 校验，拉不到即构建失败
- [ ] 加载：运行时 mmap 读取；解析 ≤200ms；sha256/版本校验；失败自动降级 Noto；诊断输出字体版本 + 加载状态（正常/降级/缺失）
- [ ] sbix 通路：Rust 自解析 sbix + png crate；rustybuzz 整形照旧；恒 160 档解码；双线性 + 线性空间缩放；字号 >160px 用 160 原图放大
- [ ] 布局：原生比例不压缩不拉伸；2 格、垂直居中、非透明包围盒视觉居中；行高稳定（有无 emoji 行高一致、不顶天立地）
- [ ] 回退：Apple 无图 → Noto COLRv1 → 主题适配方框；以完整 cluster 为单位；输出 Apple vs Noto 覆盖差异报告
- [ ] 缓存：512 张 LRU（160px 原图缓存、缩放变体按需生成）；解码结果直接进图集；瀑布流场景内存不超过上限
- [ ] 预热：启动后后台解码前 50 个热门 emoji，2s 内完成
- [ ] 指标：新 emoji 首现（解码+缩放+上屏）≤50ms
- [ ] 离屏自动化：类别化样例（旗帜/家庭/肤色/职业 ZWJ/keycap/tag/Emoji 17 新码位/冷门码位）+ VS16 行为 + Noto 兜底路径 + Apple 36/36 覆盖
- [ ] 真机主观验收：40×10 与 80×24、字号 12/16/24/32/48、深浅主题；交付 Apple vs Noto 同尺寸对比图；深色背景边缘干净无灰边；RTL 顺序正确；选中/滚动无残影
- [ ] 回归：工单 13 验收项（下划线、布局、双图集、灰度正文）全量重跑
- [ ] 溯源与更新：溯源文件（原始 ttc sha256/版本/来源/剥离产物 sha256）、换字体步骤文档、上游自动检查脚本（每周比对 PoomSmart 最新 release）

## Comments

2026-08-10 决策窗口（多轮讨论，用户逐项确认；决策记录见 ADR-0007）：

- 用户要求：Apple 官方原版、最完整、最高清、体积不做妥协、原生比例。
- 重要事实修正：此前"Apple 行"对比图实为三星 One UI 9 图样（oneui.ttc 构建脚本实锤，非苹果原版）；已换真 Apple 官方文件并重新出图（`emoji_font_compare3.png`）。
- 版本核实：`21.4d3e1` = 当前最新（iOS 26.4/macOS 26.4 时代；macOS 26 最新 2026-07 构建仍同版本；iOS 27 beta 无更新证据；Emoji 18.0 预计 2026-09 定稿、苹果约 2027 春 iOS 27.4 推出）。
- 原版性交叉验证：iOS 提取版 vs macOS 独立提取版逐像素比对，⚽ 0% 差异、其余颜色一致 → 苹果原版位图。
- 旗帜事实修正：旗帜图在组合字形上，rustybuzz 整形后 36/36 全有（此前"无旗帜"是未整形误判）。
- 完整需求清单（34 条）已逐条确认并入验收清单；文件事实：原始 ttc sha256 `76c37f95…`、剥离产物（仅 160 档）30,770,720B sha256 `e7605cad…`。

2026-08-10 实施完成（待真机验收）：

**验证数据**

- `cd spike-render && cargo run --release` → `结果: ALL PASS`（42 项 PASS）。新增/重写断言：字体加载（status=正常、load_ms=53.8 ≤200ms）、Apple 版本串 `21.4d3e1`/strike 160/PNG 3761/sha256 全量校验、预热 50/50（20.6ms ≤2s）、类别化 24 项（旗帜/家庭/肤色/职业 ZWJ/keycap/tag/冷门）非空彩色、Emoji 17 新码位（Apple-only 码位，见覆盖报告）、VS16（⌨️ 出图、⌨ 降级不崩溃）、Noto 兜底（覆盖差异 29 个，抽查 U+2640）、方框兜底（字体缺失态 None）、🚀 首现 0.69ms ≤50ms、✅/家庭/肤色/旗帜 Apple 图样、扩展 27 项、下划线/灰度正文/滚动/resize 全回归。
- 覆盖报告 `spike-render/coverage_report.txt`：Apple 单码位 emoji 覆盖 1368、Noto 1397、Apple 无图但 Noto 有 29（♀/♂/⚕ + 26 个地区指示符单码位——旗帜经整形为组合字形后 36/36 全有，单码位缺失符合预期）。
- `cargo test --release --lib` → 11 passed（sha256 NIST 向量、LRU、缩放、布局不重叠等）。
- APK：`fable-render-22_arm64-v8a.apk`（sha256 `f569d09e…`），badging `com.gph.fable`、apksigner <签名证书主体>；APK 内 `assets/fonts/AppleColorEmoji.ttf`（30,770,720B）与 `NotoColorEmoji.ttf` 均为 **Stored（0% 压缩，noCompress 生效）**；`lib/arm64-v8a/libfable-render.so` 8,080,152B，NEEDED 五件套不变（libm/log/android/dl/c，无 libfreetype），TLS p_align=0x40，JNI 导出 25 个（含新 `rendererSetFontPaths`）。
- 性能拆解：字体加载 53.8ms = Apple mmap+sha256（ARMv8 硬件指令 38ms，软实现 144ms）+ Noto sha256（7ms）+ FreeType 初始化；LRU 512 张（160px 原图 ~52MB 上限）；预热 50 个 20ms。

**坑与解法**

- **Apple sbix PNG 是 Indexed（调色板）色型**：png crate 不展开调色板，输出为每像素 1 字节索引 → 需自行应用 palette + tRNS（透明度）。初版只处理 Grayscale/RGB/RGBA 导致全部解码失败、静默走 Noto 兜底（预热 0/50、类别样例假失败）。修复后 50/50、全部通过。
- **ARMv8 SHA-256 指令版调度顺序**：手写 4 轮/迭代版算错（`vsha256h2q` 必须用保存的旧 STATE0、`su0/su1` 需按参考实现交错且参数顺序固定）。移植 noloader/SHA-Intrinsics 公有领域实现，NIST 三向量 + 与 sha2 crate 对拍全过；HWCAP_SHA2 运行时检测，无硬件指令回退软实现。
- **TTC 表偏移相对文件头**（非 face 起始）：初版 `face_base + offset` 解析失败；剥离工具按文件头偏移修正后 face 0/160 档/3761 PNG/版本串全部命中。
- **剥离产物可复现性**：脚本剥离产物（sha256 `6f6ad8b9…`，表内容与决策窗口手工产物 `e7605cad…` 逐表一致、仅表目录顺序不同）以脚本为准并固化进 fetch 脚本与溯源文档。
- **性能预算**：手写纯 Rust SHA-256 30MB 需 219ms，超加载预算 → sha2 crate（141ms）→ ARMv8 硬件指令（38ms），加载总耗时 53.8ms。
- **断言按 Apple 图样重写**：⚽ 在 Apple 图样中本就是黑白球（Noto 时代“彩色”断言不再适用）；家庭 ZWJ 为彩色全家福（非 Noto 灰卡，宽度≠更大）；方框兜底实测四 emoji 区被双字体全覆盖（Noto .notdef 亦出图），第三级只在字体缺失/加载失败时触发 → 断言改为缺失态。
- **运行时 sha256 校验**：Apple 字体 30MB 全量哈希在加载路径（硬件指令后 38ms，可接受）；`png_count` 缓存避免每帧诊断重扫 3761 个 gid。

**结论写回（对后续影响）**

- 彩色字形主字体 = Apple Color Emoji 21.4d3e1（sbix 160 单档，assets noCompress + mmap + sha256 校验）；Noto COLRv1 兜底保留（`colr.rs` 通路未动）；回退以完整 cluster 为单位（整段任一 glyph 无图即整段走 Noto）。
- 字体不再 include_bytes 内嵌：换字体只换 APK assets 文件（Emoji 18 升级走 `docs/fonts/换字体步骤.md` + `scripts/check-emoji-upstream.sh` 每周检查）。
- 渲染器新增 JNI `rendererSetFontPaths(handle, apple, noto)`（Rust 侧 mmap）；Kotlin `FontAssets.install()` 在 RenderActivity/主终端两处接入；诊断经 `rendererInfo` 输出字体版本/加载状态/缓存（`emoji=[status=… apple_version=… pngs=… cache=…]`）。
- 布局：非透明包围盒视觉居中 + 原生比例 + 2 格（GPU/软件两路一致）；恒 160 超采样（双线性 + 线性空间预乘插值，避免透明边缘灰边）。
- 待真机验收项：40×10 与 80×24、字号 12/16/24/32/48、深浅主题、Apple vs Noto 对比图、深色背景边缘、RTL、选中/滚动残影；探针新增 `[emoji22]` 按钮（类别化样例 25 个）。
- 性能指标全部达标（首现 0.69ms / 加载 53.8ms / 预热 20ms / LRU 512）。

2026-08-10 评审修复（code-review 双轴，Standards + Spec 并行子代理）：

- **FontAssets 部分失败也传路径**：原实现两字体全拷贝成功才调 `rendererSetFontPaths`，Apple 成功/Noto 失败时渲染器保持 Missing（Apple 通路白丢）；改为无条件传路径，Rust 侧缺文件返回 None → 状态=降级（与决策 4 一致）。
- **图集格子回 64**（容量恢复 1024，双图集通道结构不动）：Apple 160 档按目标字号缩放，≤64px 字号图样方形/格内放得下；评审指出 128 格会把容量削到 256 且动通道结构。
- **去掉 sbix transmute**：`SbixStrike` 不再持有 `raw` 引用（按需从字体字节读取），`AppleSbixFont` 不再需要 `transmute<'_, 'static>`；`glyph_data` 补长度边界（损坏记录返回 None，不 panic，符合决策 7）。
- **断言补强**：类别样例 24 → **36 项（Apple 36/36 覆盖）**；🚀 首现改在预热前测（冷 cache 1.5ms ≤50ms）；新增行高稳定断言（emoji 包围盒 ≤1 格高/2 格宽）；恢复 emoji 范围单测 + 新增默认字体加载单测（13 passed）。
- **去死代码/重复**：提取共用 `blit_src_over`（Noto 合成与 Apple 合成同公式）；删公开但无调用的 `Renderer::prewarm_emoji` 与 `FontAssets.sInstalled`；sha256 校验语义统一为全量等值（不再 starts_with 前缀）。
- **换字体步骤补 ssot 常量清单**：fetch 脚本 / emoji.rs / FontAssets.java / check 脚本 / main.rs 断言 / 溯源文档共 6 处同步点。
- 评审遗留说明：`coverage_diff` 为单码位粒度（组合字形经 36/36 样例断言覆盖，报告中注明）；系统字体回退链删除与新增 C shim（mmap/sha256，沿用既有 shim 先例）均按 ADR-0007 决策 4 记录在案。
- **新包（覆盖同路径）**：`~/storage/downloads/fable-render-22_arm64-v8a.apk`，sha256 `313c232c7dd0e6480d76464cbd359446d4f06602d270174c3e18c1e008a357d9`。

2026-08-10 真机反馈修复（用户验收不满意：清晰度糊/锯齿 + 国旗极小、电池极大）：

**用户可操作性问题（先解答）**

- 探针（RenderActivity）**没有字号按钮、没有深浅主题切换**；字号/主题在主终端（Termux 设置 + FableTerminalPalette）。探针固定 24px 字号、固定深色黑底。40×10 是格子变大而非字号变大。

**根因调查（代码 + 数据双线，非点修）**

1. **布局基准错误（国旗/电池两极分化的直接机制）**：
   - 旧实现 `build_row_vertices` 用「非透明包围盒 bbox」fit 进「2 格宽 × 行高」盒。Apple sbix 原生语义是**位图画布（160×160 = 1em 方块，含透明边）按 origin 摆进 em 盒**；位图内容在画布内的位置/大小是字体设计。
   - 实测（160 档位图）：🇨🇳 画布 160×160、内容 bbox (9,29,142,102)（0.89em×0.64em，垂直画布内 y29..131）；🔋 画布 160×160、内容全满；⌨️ 画布 160×160、内容 (0,14,160,23)（0.14em 高的横条）。**国旗内容只有 0.64em 是苹果原生设计**（iOS 上同样如此；Apple 官方文档确认单 RI 码位无位图、旗帜在组合字形上）。
   - 叠加第二个 bug：`build_row_vertices` 用 `terminal_cell_width(chars().next())` 判宽，区域指示符（🇨）单码位判宽=1 → **国旗只按 1 格宽（27px）计算**，电池（U+1F50B 判宽=2）按 2 格宽（54px）计算 → 国旗直接比电池小一半。`merge_emoji_runs` 已把 emoji 归一 col_span=2，宽度基准却没用 col_span。
2. **清晰度（糊/锯齿）**：emoji 按固定 `pixels_per_em=24px` 光栅化，探针 40×10（行高 192px、2 格宽 54px）下上屏放大 2.3 倍；任何双线性在该倍率都糊。根因是**目标尺寸基准错位**：光栅化尺寸≠显示尺寸。
3. **探针行高与字号脱节**：探针行列写死（40×10/80×24），`row_h = surface_h/rows` 与字号无关；主终端行列由 `cell_size()`（字号驱动）推导，无此问题。

**修复（画布=em 盒统一语义，全类一劳永逸，不点修）**

- `apple_rasterize_cluster`：合成画布 = em 盒（advance × 1em，含透明边），位图按 origin 摆进画布，内容位置保持字体原生；`RgbaBitmap` 增加 `canvas_origin_x/y`。
- `build_row_vertices`：宽度基准改用 `merge_emoji_runs` 归一后的 `col_span`（emoji=2 格，tab=4 格保留）；布局以画布整体（非内容 bbox）缩放：目标 em 边长 `E = min(row_h×0.9, max(字号, 2格宽×0.95))`，画布 fit 2 格宽防溢出、垂直居中于行。国旗/电池/键盘画布尺寸统一 = em 盒，内容差异回归字体原生（国旗 0.64em、电池 1em、键盘横条 0.14em —— 与 iOS 一致）。
- 清晰度：emoji 按目标 em 边长光栅化（不再固定 24px）；彩色图集独立纹理 4096 + 动态格子（max(64, 2^⌈log2(画布最大边)⌉)；64px 格 4096 格、128px 格 1024 格，容量不削），大字号/大格子下清晰显示；灰度图集保持 2048、双图集通道结构不变。
- 探针：新增 `font-`/`font+` 字号调节（12..192px，`rendererSetFontSize`），emoji 按字号重光栅化；40×10 大格子下 emoji 清晰放大。

**验证数据（修复后）**

- `cargo run --release` → `结果: ALL PASS`（新增「画布语义全类回归 59 项」：26 个单 RI + 组合旗帜 + 竖条/横条硬件 + ZWJ + 冷门，断言画布=em 盒、内容不越界）。性能：加载 117.7ms ≤200ms、🚀 首现 1.37ms ≤50ms、预热 50/50 20.4ms ≤2000ms。
- `cargo test --release` 全绿（含新单测 `emoji_canvas_em_box_uniform_after_ticket22`：40×10 下国旗/电池/键盘画布统一 51×51、≤2 格宽、国旗内容 ≥0.55em）。
- 修复前 vs 后对比（40×10、1080×1920）：国旗 target 宽度 27px（1 格）→ 54px（2 格）；画布基准 内容 bbox → em 盒；光栅化 24px → 51px（160 档超采样 3.1×）。
- APK：`fable-render-22-fix_arm64-v8a.apk` 已重建（libfable-render.so 11,223,760B 未 strip / APK 内 strip 后 8,079,848B，NEEDED 五件套不变；assets 字体 noCompress 不变）。

**已知限制（如实记录，非本次修复范围）**

- 国旗内容 0.64em、键盘横条 0.14em 是字体原生设计（iOS/macOS 同字体同比例）；本次修复保证画布（em 盒）与位置语义与苹果一致，不做内容强行放大（点修被明确禁止）。
- 👩❤️👨 等部分 ZWJ 序列 rustybuzz 整形成 2 个 glyph 且 x_offset 回退重叠（Apple 字体该序列无 GSUB 预组合；👨👩👧👦 等家庭序列有预组合单 glyph）；渲染结果可能只显示后半组合。已在诊断中记录，列为后续观察项（不属于本次国旗/电池/清晰度验收范围）。

2026-08-10 真机回归修复（新 APK 探针 spawn 后黑屏，bash 登录不显示）：

- **根因**：工单 22 修复把灰度图集从「单纹理 2 层数组」拆成「灰度 2048 + 彩色 4096 两个独立纹理」，bind group layout 与视图已改为 D2，但 shader 里灰度图集仍声明 `texture_2d_array<f32>` 并带 array index 采样 → `create_render_pipeline` 校验失败（shader 与 layout 类型不匹配）→ 整帧渲染失败 → 探针黑屏。
- **为何本地自检没抓到**：`wgpu_offscreen_check` 用独立旧 shader 只画 solid 红色，从未覆盖真实 SURFACE_SHADER 的纹理路径；宿主 `cargo run` 是软件光栅（不走 wgpu）。已重写 `wgpu_offscreen_check`：直接用 `render_android::SURFACE_SHADER` + 3-binding 双纹理布局 + 4096 彩色纹理，画 mode=2 彩色 glyph 读回验证 → PASS（修复前该检查必 FAIL）。
- **顺带加固**：彩色图集尺寸按设备 `max_texture_dimension_2d` 自适应降级（默认 4096，不足时降到 1024/2048/4096 的下一个 2 幂；`GlyphAtlas::set_color_size` 重建缓冲并清空条目，容量随格子动态换算）——避免旧设备不支持 4096 纹理时再次黑屏。
- **验证**：`cargo run --release` ALL PASS；`cargo test --release` 14 passed；`wgpu_offscreen_check` PASS（真实管线 + 4096 采样）；APK 重建 `fable-render-22-fix_arm64-v8a.apk`（so 8,080,856B，NEEDED 五件套不变）。

2026-08-10 真机反馈修复（emoji22 命令行/输出行合成不一致）：

- **现象**：命令输入行中间家庭（👨👩👦）未合成、输出行肤色第一个（👍🏻）未合成；两边还互相反着。
- **根因（实测定位，非渲染 bug）**：交互式 bash（PTY + readline）把输入里的 **ZWJ（U+200D）吞掉**：
  - python pty 跑真实 bash 捕获：`echo <emoji22>` 的完整字节流（回显 + echo 输出）里 **ZWJ 计数 = 0**（家庭/职业全部失去连接符，被拆成独立 emoji）。
  - 对照实验：非交互管道 `printf 'echo 👨\u200d👩\n' | bash` 输出 **ZWJ 保留**（1 个）→ 吞 ZWJ 是 readline 交互行为，不是渲染器/核心问题。
  - 核心 cell 拆分（真实 bash 字节流喂 libghostty-vt）确认：命令行和输出行都是 `👨 / 👩 / 👦`（无 ZWJ），merge 无从合成。
- **修复**：探针 `sendToAll` 统一用 **bracketed paste**（`\x1b[200~...\x1b[201~`）发送命令。readline 把粘贴内容当字面文本插入、**不吞 ZWJ**：
  - pty 验证：bracketed paste 发送 emoji22 → 回显+输出 **ZWJ 11/11 完整**；
  - 核心拆分验证：命令行家庭 `👨|ZWJ / 👩|ZWJ / 👧|ZWJ / 👦`、职业 `🧑|ZWJ / 🚀` 全部含 ZWJ → merge 正常合成；
  - 肤色（非 ZWJ，readline 不吞）随 merge 的 next_is_skin 修复一并正常。
- 手动输入框（sendInput）同样走 sendToAll，粘贴 emoji 也保留 ZWJ。
- APK：`fable-render-22-fix_arm64-v8a.apk`（sha256 `8232a61c…`）。

2026-08-10 真机反馈修复（光标半格 + 组合 emoji 长空白）：

- **现象**：中文/emoji 占 2 格但光标/选择只盖 1 格（半格）；组合 emoji（🧑🎓 学生）连敲 3-4 次后后面出现一段"光标能进、不能输入、不能删除"的长空白。
- **根因（核心诊断）**：
  - 长空白：核心默认按**每码位**算宽（🧑 2 列 + 🎓 2 列 = 4 列），渲染按完整 emoji（2 格）画 → 每敲一个学生多 2 列"核心认为有内容"的空白；×3 多 6 列。光标落点诊断：学生 cursor_x=8（4 列/个）、中文 cursor_x=2（正常 2 列）。
  - 半格光标：画法层 `build_overlay_vertices` 光标固定 1 格宽，不检查所在列是否为宽字符。
- **修复**：
  1. **DECSET 2027（grapheme clustering）**：探针 spawn 与主终端 `SessionRender` 建立时各发一次 `ESC[?2027h`（幂等）。核心诊断验证：学生 4 列 → **2 列**、×3 12 列 → **6 列**，cluster 合并为 1 cell（`🧑|ZWJ|🎓`）+ 空列，光标/选择/删除/换行全部按 2 列正确；中文/肤色/keycap 均正常。这是标准终端模式（kitty/ghostty/wezterm 同款）。
  2. **宽字符光标**：`build_overlay_vertices` 新增 `cell_render_span`（merge col_span 与 `terminal_cell_width` 取大），光标落在宽字符第一列或第二列时归到起始列并画 2 格宽（BLOCK/UNDERLINE/描边；BAR 保持 1 格细竖条）。
- **验证**：`cargo test --release` 16 passed（新增 `overlay_cursor_wide_span_ticket22`：中/🚀/学生 cluster/A 四种光标宽度断言）；`cargo run --release` ALL PASS；APK `fable-render-22-fix_arm64-v8a.apk`（sha256 `c2fd39c4…`）。

2026-08-10 全面回退审查（2027 版真机反馈：按钮不执行、中文重叠、肤色挤一起、家庭错乱）：

- **审查结论（每项都有核心诊断证据）**：
  1. **按钮不执行**：bracketed paste（`ESC[200~...`）在用户设备 readline 上不可靠（命令只回显不执行）。回退 paste 包装，恢复直接发送（可靠回车）。实测直接发送时 readline **只吞 ZWJ**，VS16/20E3/tag/肤色全部保留。
  2. **中文重叠**：渲染列定位 `col_pos += col_span`（中文 col_span=1）但渲染 2 格宽 → 下一字重叠进上一字第二格。**此 bug 一直存在**（工单 13 col_span 定位引入），2027 版用户测试「你好吗」才暴露。修复：`col_pos += cell_render_span`（渲染宽度，中文/emoji 2 格）。
  3. **肤色挤一起**：2027 模式下核心已把 `👍🏻` 合成一格（含 base），merge 的 `next_is_skin`（next 含修饰符）把连续完整肤色全并成一个 cluster。修复：合并条件改为「下一格是**纯**肤色修饰符」（无 2027 时核心拆 `👍 / 🏻` 才合并）。
  4. **家庭错乱**：与 3 同源（2027 cells 与 merge 假设冲突），修复 3 后家庭各 cluster 独立。
  5. **DECSET 2027 保留**：核心诊断证明 2027 下中文/肤色/家庭/学生全部按 2 列（`你/␣/好/␣`、`👍|🏻/␣`、`👨|ZWJ|👩|ZWJ|👧|ZWJ|👦/␣`、`🧑|ZWJ|🎓/␣`），长空白根治；渲染层已适配 2027 cells。
- **修复**：Rust（列定位、merge 肤色条件）+ Kotlin（去 paste、emoji13/22 按钮改 `printf '...\u200d...'` 转义；bash printf 展开 ZWJ，readline 不吞 ASCII 转义）。
- **验证**：`cargo test --release` 18 passed（新增 2027 肤色/家庭不合并、中文列距、光标宽字符）；`cargo run --release` ALL PASS；真实 bash pty 验证 `printf '🧑\u200d🎓\n'` 输出 ZWJ 保留、命令执行；APK `fable-render-22-fix_arm64-v8a.apk`（sha256 `bb6a36c4…`）。

2026-08-10 地毯式全面审查与全局重构（6 个并行审查子代理：Standards/Spec/emoji 数据通路/渲染布局/成熟终端语义/Kotlin 壳；每项结论带代码行或核心诊断证据）：

**审查确认的"全局正确"部分**
- Apple sbix 合成/缩放/画布链路统一：位图摆放公式与 FreeType `horiBearingY=originOffsetY+height` 逐字一致；画布=em 盒（含透明边）、内容保持字体原生位置；无任何按 emoji 特判分支；36+59 项自检画布统一。
- 成熟终端语义交叉验证（≥2 来源）：DECSET 2027 按 grapheme cluster 算宽（Ghostty/foot/wezterm/contour/iTerm2 正确；Alacritty/kitty/Terminal.app 反而 4-6 格）；sbix 位图按 origin 摆 em 盒、禁止 bbox fit；block 光标盖满宽字符是渲染层职责。

**审查发现并已全局修复（不是单 emoji 补丁）**
1. **[架构] 废除 merge_emoji_runs 启发式补丁**：核心（2027）每行 cells=cols、宽字符=「1 非空格+1 空占位格」=2 列，本身就是唯一宽度权威。渲染层改为直接遍历核心 cells（列定位=cell 下标、宽度=下一格是否空占位），行布局/光标/选区/selection_text 全部共用同一列模型；删除 merge（render_android + main.rs 副本）与手写 terminal_cell_width 宽度表。核心诊断验证：中文/旗帜/英格兰/keycap/肤色/家庭/职业在 2027 下全部「cluster+占位」2 列，两种模式（2027 开/关）渲染都与核心一致。
2. **[高] 彩色图集统一格子尺寸**：64/128px 格混排时全局编号会映射同一像素区互相覆盖（大字号 + Noto 兜底即触发）。修复：图集级统一格子（遇更大画布升级并清空重建），容量 64px 格 4096 / 128px 格 1024（≥512 LRU）。
3. **[高] 图集降级越界写**：`ensure_color_glyph` 行跨步硬编码 4096，设备 max_texture<4096 降级后越界写 + 不上传。修复：统一 `self.color_size` + 降级后立即重传。
4. **[高] 主终端 reset 后 2027 丢失**：`FableRenderCoreAdapter.reset()` 重建渲染器后补发 `ESC[?2027h`。
5. **[中] Noto 兜底内部画布语义**：Noto 字形 ascent>1em，em 盒画布会裁内容；回退为内容范围画布，显示统一由布局层 fit 2 格宽/行高保证（记录为已知内部差异）。
6. **[中] 自检开启 2027**：main.rs 测试终端发 `ESC[?2027h`，软件路径覆盖核心占位格列模型。
7. **[中] emoji27 与 13/22 统一**：🏳️🌈 改 `printf '\u200d'` 转义。
8. **[低] 新测试**：统一格子升级、核心占位格列定位（中文不重叠）、光标 2 格、选区文本。

**交叉验证修正的旧诊断**
- "readline 吞 ZWJ" 被推翻/弱化：本机真实 bash 5.3 PTY 多种写入方式验证 readline **保留** ZWJ（旧捕获 ZWJ=0 是 heredoc 编码损坏的误判）；但 zsh/readline 对**逐字符交互键入** ZWJ 确有缺陷（zsh ML + readline sr #110601 + VS Code issue 交叉）。按钮保留 `printf '\u200d'` 转义（无论哪种 readline 行为都稳）。

**记录为后续工单/提升项（本次未动）**
- 主终端 terminal-emulator 软件模型（IME 光标/长按选择）仍按码位算宽，与核心 2027 双轨——主终端光标/选择可能错位，需单独架构工单（探针单模型一致，本次不扩范围）。
- 恒 160 档超采样，>160px 目标放大（Apple 字体有 320 档，可提升大字号清晰度）。
- emoji 垂直居中 vs 基线：成熟终端 emoji 视觉居中常见，用户未反馈，暂不调整。
- color_entries 无 LRU 淘汰（图集满后新 emoji 走方框，已记录）。

**验证**：`cargo test --release` 17 passed；`cargo run --release` ALL PASS（含 2027 网格列模型）；APK `fable-render-22-fix_arm64-v8a.apk`（sha256 `ca0bfe04…`）。
