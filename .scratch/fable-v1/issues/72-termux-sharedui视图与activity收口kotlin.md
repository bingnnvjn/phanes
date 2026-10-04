# 72 — termux-shared UI 视图与 Activity 残留 Java 收口

**What to build:** 把扩展键视图族（按钮模型、常量表、视图与手柄）与两个 Activity（报告查看、文本输入输出）迁移为 Kotlin，行为等价。扩展键的手势/长按/弹跳纯逻辑抽缝后可测；两个 Activity 的 manifest 入口身份、intent extra 名、PendingIntent 与返回结果语义不变。

**Blocked by:** None
Status: 待开工

## 验收清单

- [ ] 目标类 100% Kotlin（对应 .java 删除）
- [ ] 扩展键纯逻辑（按钮状态迁移、长按/弹跳判定、复用弹窗）抽缝后有 JVM 测试覆盖并全绿
- [ ] 两个 Activity 的 manifest 入口、intent extra 名、PendingIntent 与 `setResult` 行为不变
- [ ] 单测基线不劣化；APK 构建 + 校验通过

## Comments

2026-10-04 建单（来源：工单 35 收尾时登记的 termux-shared 残留 Java 清单；用户 2026-10-04 拍板拆 3 张、独立可做）。

本单只覆盖「UI 视图与 Activity」这一簇，另两张为 `fable-v1/70`（常量与模型）与 `fable-v1/71`（外部入口与终端交互）。三张互不阻塞，不要顺手扩范围。
