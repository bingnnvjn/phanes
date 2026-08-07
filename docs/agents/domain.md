# 域文档

工程技能在探索代码库时如何消费本仓库的域文档。

## 探索前先读

- 根目录的 `CONTEXT.md`，或
- 如果存在根目录的 `CONTEXT-MAP.md`——它指向每个上下文各一个 `CONTEXT.md`。读与你主题相关的每一个
- `docs/adr/`——读与你将要动手的领域相关的 ADR。多上下文仓库里，还要看 `src/<context>/docs/adr/` 的上下文级决策

如果这些文件不存在，**静默继续**。不要标记缺失、不要主动建议创建。`/domain-modeling` 技能会在术语或决策真正确定时惰性创建它们。

## 文件结构

单上下文仓库（大多数仓库）：

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多上下文仓库（根目录存在 `CONTEXT-MAP.md`）：

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← 系统级决策
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← 上下文级决策
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## 用词汇表的词

当你的输出命名一个领域概念（issue 标题、重构提案、假设、测试名）时，用 `CONTEXT.md` 里定义的那个词。不要漂移到词汇表明确回避的同义词。

如果需要的概念不在词汇表里，那是个信号——要么你在发明项目不用的语言（重新考虑），要么真的有缺口（记下来交给 `/domain-modeling`）。

## 标注 ADR 冲突

如果你的输出与既有 ADR 冲突，显式标注而不是默默覆盖：

> _与 ADR-0007（event-sourced orders）冲突——但值得重新打开，因为…_
