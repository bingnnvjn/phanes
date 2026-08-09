# 13 — 彩色字形通路升级（FreeType + NotoColorEmoji COLRv1，含 ZWJ / 下划线）

**What to build:** 把 spike-render 的彩色 emoji 字形从"2017 CBDT 位图提取"升级为 **FreeType 光栅 COLRv1 + 内嵌现代 NotoColorEmoji**（COLRv1 完整版，Unicode 17.0，替换旧字体）；emoji run 经 rustybuzz 整形，**ZWJ 序列（家庭 emoji / 肤色修饰 / 旗帜）一并解决**；✅ 接受 COLRv1 原生彩色绿勾（不再保持灰勾）；**下划线真机可见专项并入本单**。灰度正文通路保持 fontdue 不动（FreeType 只接彩色字形）。本单继续作为"字形/显示细节类 UX 问题"的收集点。图集增量上传与灰度迁 FreeType 为独立 backlog（见 Comments，不占本单）。

**Blocked by:** None（工单 12 的双图集通道已可用；本单只换彩色字形来源/光栅，不动通道结构与灰度通路）

Status: 待验收

## 已定方向（2026-08-09 决策窗口，ADR-0006；禁止重新调研/讨论）

- 路线：**FreeType**（C FFI，静态链接进 `libfable-render.so`）光栅 COLRv1/CBDT/sbix 彩色字形；不选 Android Paint/JNI、纯 Rust 自写 paint graph、taetype（观察项）
- 字体：内嵌 `NotoColorEmoji.ttf` **COLRv1 完整版**（Unicode 17.0，10.7MB，OFL-1.1，含旗帜），替换 2017 CBDT（5.3MB）；真机验收通过后移除旧字体
- ZWJ：经 rustybuzz（管线已有）整形，家庭/肤色/旗帜一并解决
- ✅：接受 COLRv1 原生彩色绿底白勾，不做灰勾覆盖
- 灰度正文：fontdue 保留不动
- 下划线：并入本单（更粗/更高对比/独立字形）
- 不并入：图集增量上传（工单 12 遗留）、灰度迁 FreeType、taetype 评估

## 验收清单

- [x] FreeType 静态接入 spike-render：aarch64-linux-android 交叉编译通过；`libfable-render.so` 含静态 FreeType，既有产物校验不回归（badging / apksigner / .so 清单 / TLS p_align）
- [x] 字体替换：`assets/` 为 COLRv1 完整版（Comments 记录版本/sha256/许可），旧 CBDT 保留可回退，真机验收通过后移除
- [x] 离屏自检（`cargo run --release` 软件光栅 + 断言）：COLRv1 字形光栅出 RGBA；🚀 彩色不偏淡；✅ 为彩色绿底白勾；ZWJ 家庭/肤色/旗帜离屏断言 PASS
- [x] 灰度正文回归：既有软件光栅 ALL PASS 样本全保持（fontdue 通路未动）
- [ ] 真机验收：🚀（彩色不偏淡）、✅（绿勾）、👨👩👧👦（家庭 ZWJ）、👍🏻（肤色）、🇨🇳（旗帜）、⌨️🔋（杂项）、SGR 4 下划线可见；冷门码位抽查（实施时补充清单）
- [x] 结论写回 Comments（每项实现方式、性能数据、坑、对后续影响），更新 Status（待验收）

## Comments

2026-08-07 建单（工单 12 真机验收时用户提出；✅ 保持灰勾现状，不合成）。

2026-08-07 补充范围：用户确认下划线问题并入本单（非工单 12），后续其他"字形/显示细节 UX"问题也按用户偏好先记这里，攒够再拆专项。

2026-08-09 决策窗口（grill-with-docs 两轮，用户逐项确认）：

- 方向 R1 = FreeType 通路（Ghostty 官方同款），ZWJ 一并解决；✅ 接受 COLRv1 原生彩色绿勾（推翻"保持灰勾"特殊处理）；下划线并入；图集增量不并入
- 事实源（2026-08-09 一手核实）：Android 15 移除 legacy PNG emoji 字体；现代 NotoColorEmoji 为 COLRv1（main 分支 Unicode 17.0，10.7MB，OFL-1.1）；skrifa 只解码不提供 COLRv1 光栅；taetype 2026-07-26 发布未生产验证；Ghostty 用 FreeType 光栅彩色字形；本机 clang 21.1.8 默认 aarch64-unknown-linux-android24，spike-render build.rs 已用 cc crate 编 C
- 定案记录：`docs/adr/0006`；spec Implementation Decisions 12；启动包 `实施提示词-工单13.md`
- Backlog（不占本单）：图集增量上传（工单 12 遗留）；灰度正文统一迁 FreeType（单光栅器）；taetype 纯 Rust COLRv1 评估（重开条件，见 ADR-0006）

2026-08-10 实施完成（待真机验收）：

**实现方式**

- FreeType 2.14.3 静态接入：vendored `third_party/freetype`，`build.rs` 用 cc crate 编 16 个 C 模块（base/sfnt/truetype/cff/psaux/pshinter/psnames/smooth/autofit），裁剪 `ftmodule.h`（去 svg/sdf/raster1/bdf/pcf/pfr/type1 等）与 `ftoption.h`（关 USE_ZLIB/USE_PNG/USE_BZIP2）；`libfable-render.so` NEEDED 五件套不变、TLS p_align=0x40 不回归。本机即 aarch64-linux-android，`cargo build --release` 即交叉产物。
- 字体替换：内嵌 `Noto-COLRv1.ttf`（COLRv1 完整版，sha256 `0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2`，版本串 `Version 2.051;GOOG;noto-emoji:20250818:e92753bf…`，4,991,984B，OFL-1.1）；旧 CBDT 保留为 `assets/NotoColorEmoji-CBDT-2017.ttf` 待真机验收后移除（见许可文件）。
- **事实修正（重要）**：启动包/ADR-0006 称"10.7MB COLRv1 完整版"有误——`main/fonts/NotoColorEmoji.ttf`（10.7MB）是 CBDT 位图版；真正 COLRv1 完整版（含旗帜）是 `main/fonts/Noto-COLRv1.ttf`（5.0MB）。已按后者实施。
- **关键坑：FreeType 2.13+ 只自动合成 COLRv0 层列表，不自动光栅 COLRv1 paint graph**（`FT_Get_Color_Glyph_Paint` 只解析，Ghostty 也是调用方自行遍历）。本单新增 `src/colr.rs` 实现 paint graph 遍历 + 合成：ColrLayers / Glyph / ColrGlyph / Solid / Linear·Radial·Sweep 渐变 / Transform·Translate·Scale·Rotate·Skew / Composite 27 种混合模式（HSL 系降级 MULTIPLY，字体普查未用到）。
- 坐标坑：根变换 A（16.16）语义是 font 单位 → device px；NO_SCALE outline 是 26.6 font 单位，对 outline 应用矩阵需 M = A×64×65536（×64 修正），否则字形缩成 1px。`FT_OpaquePaint` 调用前必须清零（`p` 非空直接返回 0）。`FT_Palette_Data` 字段顺序与直觉不同（`num_palette_entries` 在第 4 位），按头文件实测修正。裸渐变/纯色 paint 作 composite 操作数时铺满 backdrop 区域（旗帜 emoji 的 SRC_IN 渐变源即此用法）。
- ZWJ：`emoji.rs` 先经 rustybuzz（新增 0.20.1；启动包称"管线已有"不实，此前无 rustybuzz）整形整段 cluster，取 glyph id 后 FreeType/colr 光栅；家庭/肤色/旗帜离屏断言 PASS。
- ✅：接受 COLRv1 原生绿底白勾（pal 1815=(104,159,56) 绿 + 白勾），未做灰勾覆盖。
- 下划线：软件/GPU 两路统一 `max(3px, 12% 行高)`，离屏断言底部 3px 前景像素 420 个 PASS。

2026-08-10 评审修复（code-review 双轴：Standards + Spec）：

- 下划线补齐"更高对比"：SGR 4 样式显式 `underline_color`（RGB tag）优先，否则前景色；软件/GPU 两路一致。`Cell` 新增 `underline_color: Option`，hash 同步。"独立字形"保持程序化矩形（本就独立于字体覆盖，与 sprite face 同思路），真机仍不可见再升级。
- 事实修正：🫖 U+1FAD6 为 Unicode 13.0（非 17）、🫶 U+1FAF6 为 Unicode 14.0（非 15）——离屏断言注释与输出已去掉版本断言；字体"Unicode 17.0"表述不可靠（版本串构建于 20250818，覆盖至 Emoji 16.0 时代），ADR 注记同步修正。
- **回退代价说明**：本构建 `ftoption.h` 关闭 `FT_CONFIG_OPTION_USE_PNG`，旧 CBDT-2017 的 PNG strike 无法经 FreeType 解码；真机验收通过后移除旧字体即无此约束。若中途需回退，重开 USE_PNG + 静态 libpng。
- FFI 清理：删除未使用声明（`FT_Get_Char_Index`/`FT_Get_Color_Glyph_ClipBox`/`FT_LOAD_DEFAULT`/`FT_LOAD_NO_HINTING`/`FT_ClipBox`）；`FT_GlyphSlotRec` 布局注释修正为"读到 outline"；`EmojiFont::is_bitmap` 改名 `has_color`。

2026-08-10 真机反馈修复（探针 UI）：

- 用户反馈：探针顶部黄字状态区占满屏幕，看不到终端。根因：spawn/attach 后 `setStatus` 把整段 `rendererInfo`（几十个字段）塞进 WRAP_CONTENT TextView，换行成十几行。
- 修复：状态区 `maxLines(2)` + 省略号 + 深色底；自动状态文本精简（attach/spawn/render OK 不再拼 rendererInfo，完整信息进 debug log）；点击状态区可展开/收起全文；`[info]`/`[gpu]` 等主动查询仍显示完整信息。
- **新包（覆盖同路径）**：`~/storage/downloads/fable-render-13_arm64-v8a.apk`，sha256 `cfc187653883e65b9cf4e64b8dbb960e53a324bfbc20dc6830cf8d4596f8b7b1`（仅 Java 改动，.so 未变）。

2026-08-10 真机反馈第二轮（emoji 全错 + 下划线"完全没有"）修复：

**emoji 全错根因（两个独立 bug）**

1. **层位图 y 坐标符号错误（最大 bug）**：FT 位图行 0 是顶边（y-up `bitmap_top`），图像 y-down 顶应为 `-bitmap_top`；旧代码 `bitmap_top - rows` 把"底边"当顶边，整层垂直翻转 + 错位 → 火箭解体/头颠倒/勾变竖条/毛边。修复：`colr.rs` `offset_y = -bitmap_top`（渐变反算 top 同步用 `bitmap_top`）。
2. **cluster 画布 alpha 混合公式错误**：直通 alpha src-over 用了预乘公式且强制 alpha=255，半透明层（家庭灰卡圆角抗锯齿）被染暗 + 变不透明黑条。修复：`emoji.rs` blit 用正确 `oa = sa + da*(1-sa)`。

**验证（与官方 png/128 + SVG 源对比）**

- 🚀 alpha IoU：0.392 → 0.982（翻转版 0.311）；👍：0.875 → 0.980；✅ 白勾轨迹与参考一致；家庭 y0 黑条消失（dark px 0）。
- 新增回归断言：🚀 黄色火焰必须在字形下半部（y 翻转防护）。
- `cargo run --release` ALL PASS / `cargo test --release --lib` 7 passed。
- **新包（覆盖同路径）**：`~/storage/downloads/fable-render-13_arm64-v8a.apk`，sha256 `cf4d23eb8528d5f4d261ffbc0170ec357353e3bb3b4715ed5fd37c7826ebe4e2`（新 .so 含修复）。

**下划线专项排查（用户报告"完全没有"）**

- 本地全链路验证：核心 SGR 4 → `collect_snapshot`（`any_underline=true`）→ `build_row_vertices`（每格生成 10x3 白色 solid 矩形，mode=0）→ shader（mode<0.5 返回 color）全部正确；下划线与光标同属 solid 路径（工单 12 真机光标正常）。
- 结论：下划线代码链未发现 bug；用户此前观察可能受 emoji 全错画面干扰或为旧包。请用新包重测 `[u-line]`；若仍无，需查真机呈现层（非本单合成器）。

2026-08-10 真机反馈第三轮（布局/间距 + 下划线按钮）：

**emoji 布局根因（核心拆分 + 渲染器兜底）**

- 实测 libghostty-vt（预编译 b0947378）把 ZWJ 家庭/肤色/旗帜拆成多个 cell：👨👩👧👦 → 4 格、👍🏻 → 👍+🏻、🇨🇳 → 🇨+🇳（"CN 间隔大"根因）、🧑🚀 → 2 格；⌨️（带 VS16）按 1 格宽 → 与 🔋 相邻导致 2 格位图重叠（"叠在一起"根因）。
- 渲染器兜底：`merge_emoji_runs`（render_android.rs + main.rs 各一份）在收集后把片段重组成完整 cluster，`Cell.col_span` 归一 emoji 为 2 格，`build_row_vertices`/`rasterize_grid` 改累计列定位。**不能改核心（红线），渲染层合并是 Ghostty 官方同款思路（cell → shaping run）。**
- 软件光栅缩放补 0.92 系数（与 GPU 一致），扁宽字形（旗帜/键盘）不再占满 2 格紧贴。
- 验证：离屏 10 个 emoji 各 2 格、中心精确（10/30/50/…/190）、间距正常；ALL PASS。

**扩展测试集（用户要求 20-30 个）**

- 离屏断言新增 27 个代表性码位（黄脸 😀😢😂😍😡🥺 / 动物 🐶🐱🐼🦊 / 食物 🍎🍕🍜 / 活动 ⚽🎮🎵 / 物体 📱💻☕ / 符号 ❤️⭐⚠️ / 节日 🎄🎂 / 💯 / ZWJ 👋🏻🏳️‍🌈），每个断言非空+彩色+尺寸正常（至少一维 ≥0.7em）。
- 探针新增 `[emoji27]` 按钮输出上述 27 个。

**下划线按钮（用户坚持按钮发错命令）**

- 反编译 APK dex 提取按钮字符串：`"printf '\033[4munderline\033[0m\n'`（结尾单引号，正确）；bash 实测该命令输出 `ESC[4munderline ESC[0m`。用户复制的 `\n"`（双引号）会导致 bash `unexpected EOF` 不执行——请直接点 `[u-line]` 按钮。
- **新包（覆盖同路径）**：`~/storage/downloads/fable-render-13_arm64-v8a.apk`，sha256 `9502acb66c8b51b725f7fe4dbb46208e81b9542669a6dadb7707c9d5b19e325a`。

**验证数据**

- `cd spike-render && cargo run --release` → `结果: ALL PASS`（新增：🚀 饱和差≥96、✅ 绿底白勾、ZWJ 家庭≠单人且更宽、👍🏻≠👍、🇨🇳 红色、🧑🚀/🫖(U+17)/🫶 冷门码位非空彩色、下划线 420px；既有灰度文本/滚动/resize 全保持）。
- `cargo test --release --lib` → 7 passed。
- `.so`：`readelf -d` NEEDED = libm/liblog/libandroid/libdl/libc（无 libfreetype，静态链接）；PT_TLS p_align=0x40；JNI 导出 24 个。
- APK：`fable-app_apt-android-7-debug_arm64-v8a.apk`，badging `com.gph.fable`/targetSdk 35，apksigner <签名证书主体>，APK 内 `libfable-render.so` TLS p_align=0x40。
- **新包**：`~/storage/downloads/fable-render-13_arm64-v8a.apk`，sha256 `f038e1c112438fb4324c05adac2afd874c66e4d25da49a0e28f116ec62319057`（评审修复后重建；`--target aarch64-linux-android --release` 显式交叉产物为源）。

**坑汇总**

- FreeType 不自动合成 COLRv1（最大坑，见上；因此多了约 800 行 paint graph 合成器，属 Ghostty 同款做法而非范围蔓延）。
- 坐标 ×64 修正 / OpaquePaint 清零 / Palette_Data 字段顺序（均踩过并修）。
- 家庭 emoji 设计为灰卡+黑色人形（SVG 源核实），不是"彩色"——离屏断言按"≠单人字形且更宽"设计，真机看效果。
- fable-app `local.properties` 的 `sdk.dir` 指向旧路径 `/data/data/com.gph.fable/files/home/android-sdk`，已改为 `<仓库外 android-sdk>`（文件 gitignore，不入库；属环境漂移，非本单代码）。

**结论写回（对后续影响）**

- 彩色字形通路 = FreeType + COLRv1 paint graph 合成（`colr.rs`），替换旧 CBDT 位图提取；真机验收通过后移除 `assets/NotoColorEmoji-CBDT-2017.ttf`。
- 灰度正文仍 fontdue（单光栅器统一留 backlog）；图集增量上传 / taetype 观察项不变。
- ADR-0006 需补事实修正（10.7MB → `Noto-COLRv1.ttf` 5.0MB；"FreeType 光栅 COLRv1"实为调用方遍历 paint graph），见 `docs/adr/0006` 注记。
- 后续换字体/灰度迁 FreeType 时 `colr.rs` 合成器可直接复用。
- 真机验收项：RenderActivity 新增 `[emoji13]` 按钮输出 `🚀✅👨‍👩‍👧‍👦👍🏻🇨🇳⌨️🔋🧑‍🚀🫖🫶`（覆盖彩色/绿勾/ZWJ 家庭/肤色/旗帜/杂项/冷门码位），`[u-line]` 验下划线。
