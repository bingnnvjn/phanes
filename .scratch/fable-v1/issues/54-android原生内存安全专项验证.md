# 54 — Android 原生内存安全专项验证

**What to build:** 验证并建立 Android arm64 上的 sanitizer 专项构建与真机压力路径，重点检查 JNI、FreeType、native window、renderer 销毁、回调关闭、图集缓冲和多会话输出的原生内存安全问题。

**Blocked by:** fable-v1/50、fable-v1/51
Status: 待开工

## 验收清单

- [ ] Android arm64 sanitizer 工具链、限制、第三方未插桩盲区和执行入口有可重复记录
- [ ] renderer attach/detach/reset/destroy、JNI callback、字体映射和多会话压力覆盖真实设备路径
- [ ] sanitizer 报告可拉取、符号化、归因并转为回归测试或工单
- [ ] 验证不破坏正常 release 构建、APK 安装和既有真机验收
- [ ] 结果明确区分“已覆盖的内存错误类别”与“未覆盖的驱动/第三方库盲区”

## Comments

2026-08-14 建单。优先验证 Android arm64 的内存安全工具是否可用；这是专项真机验证，不替代 43/44/45/46/47 的功能和性能验收。
