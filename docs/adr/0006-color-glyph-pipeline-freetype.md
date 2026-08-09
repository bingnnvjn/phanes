# ADR-0006: 彩色字形通路 = FreeType + 内嵌 NotoColorEmoji COLRv1（ZWJ 一并解决）

- 状态：已确认（2026-08-09，决策窗口"工单 13"）
- 日期：2026-08-09
- 范围：fable-render（spike-render）彩色字形来源与光栅路线定案；灰度正文通路、双图集通道结构、核心（libghostty-vt）与 Kotlin 壳不变。

> 实施注记（2026-08-10，工单 13 实施时核实修正）：
> 1. "main 分支 `fonts/NotoColorEmoji.ttf` 10.7MB 为 COLRv1"有误——10.7MB 是 CBDT 位图版；COLRv1 完整版（含旗帜）为 `fonts/Noto-COLRv1.ttf`（4,991,984B，版本串 noto-emoji:20250818，sha256 `0ae57fe5…`）。已按后者实施。
> 2. "FreeType 2.13 起稳定支持 COLRv1"的表述易误读为自动光栅：FreeType 只自动合成 COLRv0 层列表，COLRv1 paint graph 需调用方遍历合成（`FT_Get_Color_Glyph_Paint` 系列仅解析；Ghostty 同款做法）。工单 13 已实现 `spike-render/src/colr.rs`。

## 背景

调研事实（2026-08-09，一手来源核实）：

- 现状（工单 12 产物）：灰度/彩色双图集；彩色字形来自内嵌 `assets/NotoColorEmoji.ttf`（googlefonts/noto-emoji v2017-03-10，CBDT/CBLC 位图，5.3MB，OFL-1.1），经 ttf-parser 提取内嵌 PNG + png 解码 RGBA。局限：字形停留在 Unicode 8.0 时代（部分样式老旧）；多 codepoint ZWJ 序列（家庭 emoji 等）不绘制；下划线真机不可见。
- **Android 15（目标真机系统）已移除旧 PNG 位图 emoji 字体**（`NotoColorEmojiLegacy.ttf`，Google 官方博客 2024-07）：设备上系统 emoji 字体只剩 COLRv1 矢量版——"读系统字体拿位图"路线不存在。
- 现代 NotoColorEmoji：COLRv1 格式；main 分支（Unicode 17.0）`fonts/NotoColorEmoji.ttf` 10.7MB（另有 5.0MB 无旗版、10.9MB emojicompat 版），OFL-1.1。
- Rust 生态：skrifa 可解码 COLRv1（含可变字体）但**不提供光栅**；纯 Rust COLRv1 光栅需自写 paint graph（skrifa+zeno）或押注 2026-07-26 发布的 taetype（未生产验证）；fontdue 只灰度；cosmic-text/swash 链不支持 COLRv1/CBDT。
- Ghostty 官方渲染器用 **FreeType** 光栅彩色字形（`font/freetype` presentation API：`hasColor() → emoji`）；FreeType 2.11+ 支持 COLRv1，2.13 起稳定，2.14 覆盖 COLRv1/CBDT/sbix。
- 工具链可行性（本机核实）：Termux clang 21.1.8 默认目标 `aarch64-unknown-linux-android24`；spike-render `build.rs` 已用 `cc` crate 编 C；宿主 freetype 2.14.3 + harfbuzz 14.2 可做离屏参照/自检。

## 决策

1. **彩色字形通路 = FreeType**（C FFI，静态链接进 `libfable-render.so`），光栅 COLRv1/CBDT/sbix 彩色字形；不采用 Android Paint/JNI，不写纯 Rust paint graph，不押注 taetype（观察项）。
2. **字体 = 内嵌 NotoColorEmoji COLRv1 完整版**（main 分支 Unicode 17.0，10.7MB，OFL-1.1，含旗帜），替换 2017 CBDT（5.3MB）；真机验收通过后移除旧字体，不保留双字体。
3. **ZWJ 一并解决**：emoji run 经 rustybuzz（管线已有）整形，家庭 emoji / 肤色修饰 / 旗帜等序列字形走 FreeType 光栅。
4. **✅ 接受 COLRv1 原生彩色绿底白勾**，不做灰勾覆盖（推翻工单 12 的"保持灰勾"特殊处理）。
5. **灰度正文通路不动**（fontdue 保留）；FreeType 只接彩色字形；全量统一到 FreeType 留作后续优化项。
6. **下划线专项并入工单 13**（更粗 / 更高对比 / 独立字形）。
7. **不并入本单**：图集增量上传（工单 12 遗留）、灰度迁 FreeType、taetype 评估——均为独立 backlog。

## 理由（带证据）

- FreeType 是 Ghostty 官方同款通路，延续 ADR-0004"按官方补齐"的取向；**离屏自检保持**——同一套代码在 Termux 本机（freetype 2.14.3）与安卓（静态链接）都能跑 COLRv1 光栅，`cargo run --release` 软件光栅 ALL PASS 可作程序化验收。
- ZWJ 由整形层解决（rustybuzz 已支持 NotoColorEmoji 的 GSUB 序列），比逐码位特判/自绘可维护；Android 15 只剩 COLRv1 也意味着内嵌 COLRv1 是唯一可离线、可复现、跨真机的方案。
- 灰度保持 fontdue：回归面最小（既有 ALL PASS 样本不动）；管线本就是混源（fontdue 灰度 + 位图彩色），加 FreeType 只替换彩色源，不改变模式。
- 纯 Rust 路线（skrifa+zeno 自写 / taetype）现阶段工作量与风险不成比例；taetype 2026-07-26 刚发布，待生产验证后评估（重开条件之一）。

## 冲突标注

- **ADR-0004（细化）**：决策 2 中彩色字形来源由 CBDT 位图提取细化为 FreeType 光栅 COLRv1；灰度/彩色双图集通道结构不变。
- **工单 12 结论（推翻一条特殊处理）**："✅ 保持灰勾现状"不再成立——改为接受 COLRv1 原生彩色绿勾。
- **ADR-0003 / ADR-0005（不冲突）**：核心、壳、包仓库均不受影响。

## 被否选项

- Android Paint/Canvas JNI 平台渲染（渲染线程跨 JNI 回 Java；离屏彩色自检失效需 CBDT 兜底；渲染器与安卓 UI 框架耦合，与"Rust 底层可本机验证"取向相悖）
- 纯 Rust 自写 COLRv1 paint graph（skrifa 解码 + zeno 光栅；工作量大、风险高）
- taetype（纯 Rust、`forbid(unsafe_code)`，2026-07-26 刚发布；未生产验证，作观察项）
- 末代 CBDT 位图升级（v2020 Unicode 13.1；管线不动但 ZWJ 仍不绘制，只作止血不作终态）
- 自绘 sprite 风格作为主方向（覆盖面有限；只可作个别字符覆盖手段）

## 重开条件

- FreeType 交叉编译 / COLRv1 光栅在目标真机不可行 → 回退评估 Android Paint/JNI 或末代 CBDT。
- taetype 成熟（生产验证通过）→ 评估纯 Rust 替换，删除 FreeType 依赖。
- 多会话/大输出实测暴露彩色光栅性能瓶颈 → 图集增量上传或灰度统一迁 FreeType 提上日程。

## 参考

- googlefonts/noto-emoji main `fonts/NotoColorEmoji.ttf`（COLRv1，Unicode 17.0，10.7MB，OFL-1.1，2026-08-09 核实）
- Google 官方博客：Android 15 Beta 4 移除 legacy PNG emoji 字体（2024-07）
- Ghostty 源码 `font/freetype`（presentation API：`hasColor() → emoji`）
- FreeType 2.14（COLRv1/CBDT/sbix 支持）
- taetype 发布帖（users.rust-lang.org，2026-07-26）
