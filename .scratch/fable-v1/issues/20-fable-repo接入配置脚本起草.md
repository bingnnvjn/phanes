# 20 — fable-repo 接入配置脚本起草（工单 18 前置准备）

**What to build:** 起草并离屏验证 Fable 侧接入 fable-repo 的配置脚本与说明：下载并核对仓库公钥（fable-repo-pub.asc/gpg，取自工单 17 已发布快照）、写入 Fable 系统信任区、生成 sources.list 指向 fable-repo 扁平仓库（`[signed-by=...]` 引用、停用官方 termux-main 源）、幂等可重复运行。本单只写脚本与离屏验证，不在真机实装；真机接入与 apt 补装验收归 fable-v1/18。

**Blocked by:** None

**Status:** 待开工

## 验收清单

- [ ] 脚本含环境守卫（HOME 必须 /data/data/com.gph.fable/*），非 Fable 环境拒绝执行
- [ ] 公钥获取：从工单 17 最新快照资产下载 fable-repo-pub.asc（含 URL 模式与校验和核对逻辑；GitHub 走镜像前缀）
- [ ] 公钥写入 Fable 信任区（trusted.gpg.d/fable-repo.gpg）
- [ ] sources.list 配置：指向 fable-repo（releases/latest/download 扁平源）且带 `[signed-by=...]`，不用 trusted=yes；官方 termux-main 源停用
- [ ] 幂等：重复运行不报错、不重复添加条目
- [ ] 离屏验证：`bash -n` / shellcheck 通过；dry-run 断言输出符合预期（不实写 Fable 环境）
- [ ] 说明文档：与工单 18 的衔接（17 快照就绪后真机执行 apt update/install 首批清单）

## Comments

2026-08-09 建单（并行任务：工单 17 构建期起草配置脚本，工单 18 直接套用；决策依据 ADR-0005）。
