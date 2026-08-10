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
