# 53 — Rust 纯逻辑深度验证

**What to build:** 为可脱离 Android/JNI/GPU 的关键 Rust 状态机、解析器、缓冲区和并发模型建立 property test、突变测试、Miri、fuzz 与必要的 Loom/Kani 验证；将发现的输入或交错转为普通回归测试。

**Blocked by:** fable-v1/49、fable-v1/50
Status: 待开工

## 验收清单

- [ ] renderer token/registry、mailbox 合并与容量、session closed 后 callback 禁止投递等关键不变量有纯 Rust 验证目标
- [ ] 终端字节、Unicode、快照和字体元数据的高风险解析边界有可重复 fuzz 入口与 corpus 管理
- [ ] Miri、突变测试以及适用的 Loom/Kani 有可控运行预算、失败复现和结果留档
- [ ] 发现的 crash、存活突变或并发反例均沉淀为常规回归测试
- [ ] 不把 Miri/fuzz/模型验证误用为 Android JNI、FreeType 或 GPU 真机验收替代品

## Comments

2026-08-14 建单。只为可建模的纯 Rust 部分引入高成本工具；不得为了接入工具把 Android 真实生命周期伪造为已验证。
