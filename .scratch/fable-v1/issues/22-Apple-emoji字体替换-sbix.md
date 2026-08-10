# 22 — emoji 字体切换为 Apple Color Emoji（sbix 160px 单档 + Rust 自解析 + Noto 兜底 + assets 加载）

**What to build:** 把彩色 emoji 字形从"内嵌 Noto COLRv1"切换为 **Apple Color Emoji `21.4d3e1`**（sbix，只保留 160px 档）：新增 Rust sbix 解码通路（png crate），恒 160px 超采样缩放（双线性 + 线性空间），Apple 与 Noto 两个字体均改为 fable-app APK assets（noCompress）运行时加载（mmap + sha256 校验 + 失败自动降级 Noto），Noto COLRv1 作兜底（回退以完整 cluster 为单位），布局保持原生比例（2 格、垂直居中、非透明包围盒视觉居中、行高稳定），性能指标（首现 ≤50ms / 加载 ≤200ms / 预热 50 个热门 / 缓存 512 张 LRU），验收含离屏类别化样例 + 真机主观 + 工单 13 全量回归，诊断输出字体版本与加载状态。

**Blocked by:** None
**Status:** 待开工

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
