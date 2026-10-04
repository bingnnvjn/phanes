# 70 — termux-shared 常量与模型残留 Java 收口

**What to build:** 把 termux-shared 里被全模块广泛引用的配置常量类与共享模型类迁移为 Kotlin，行为等价——常量名与常量值逐条保值（数据目录路径、属性键、偏好键、外部插件类名、仓库与反馈 URL 等），嵌套类型与静态入口形态保持，既有 Java/Kotlin 调用方无需改动。

**Blocked by:** None
Status: 待开工

## 验收清单

- [ ] 目标类 100% Kotlin（对应 .java 删除）
- [ ] 常量零漂移：常量名、常量值、嵌套类结构逐条对照原件；用 `javap -constants` 或 dex 扫描确认无值改动
- [ ] 与 app / core / terminal-view 的互调编译通过
- [ ] 可测逻辑 JVM 单测全绿；单测基线不劣化
- [ ] APK 构建 + 校验通过（bootstrap 归档与四个原生库仍在）

## Comments

2026-10-04 建单（来源：工单 35 收尾时登记的 termux-shared 残留 Java 清单；用户 2026-10-04 拍板拆 3 张、独立可做）。

本单只覆盖「常量与模型」这一簇，另两张为 `fable-v1/71`（外部入口与终端交互）与 `fable-v1/72`（UI 视图与 Activity）。三张互不阻塞，不要顺手扩范围。
