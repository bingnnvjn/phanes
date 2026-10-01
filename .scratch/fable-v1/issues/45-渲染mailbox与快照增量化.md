# 45 — 渲染 mailbox 与快照增量化

**What to build:** 为每会话渲染 mailbox 增加容量、合并和背压；把 dirty render 从完整 `Snapshot<Vec<Vec<Cell>>>` 改为元数据 + 按需 dirty row 解码，降低高输出时的复制、分配和峰值内存。

**Blocked by:** fable-v1/43
Status: 待开工

## 验收清单

- [ ] Renderer mailbox 有界，连续 Write 可合并，队列积压可观测
- [ ] Surface 不可见或渲染落后时有明确策略，不允许无限内存增长
- [ ] dirty render 只解码必要行；全文/选择查询走独立按需路径
- [ ] 增加 snapshot bytes、cell count、queue depth 和 build time 指标
- [ ] 现有字形/emoji/选择/滚动测试全绿，基准显示分配和构建时间不劣化

## Comments

2026-08-12 建单（完整仓库代码审查 P2）。不在缺少基准时改变图集尺寸或 scrollback 上限。
