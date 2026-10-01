# ADR-0011: 单一仓库形态与角色化目录

- 状态：已确认
- 日期：2026-10-01
- 范围：仓库拓扑、目录命名、历史合并策略

## 背景

项目此前按"三个 Rust crate 独立公开、Fable App 保持私有"的方向拆分（ADR-0010、工单 56）。
那次拆分的唯一动机是：GitHub 免费账号下**只有公开仓库**能配置 rulesets 或 branch
protection，而质量门禁需要服务器端强制。2026-10-01 实测确认该限制仍然成立
（私有仓库的 rulesets 与 branch protection 接口均返回 403）。

项目目标随后改变：整个项目要作为一个公开仓库存在；同时开发要在手机与电脑两台设备上
进行，单一 clone 才便于迁移。当"整体公开"成立时，上述拆分动机消失。

## 决策

1. 五段历史合并为一个仓库：以根仓库为基底，`fable-app`、`spike-render`、
   `spike-session`、`fable-boo` 以 subtree 方式并入。
2. 目录使用角色名，不含产品名：`android/`、`renderer/`、`session/`、`boo/`。
   产品名只出现在产品标识上，不出现在结构标识上。
   同一规则适用于模块与文档目录：`android/fable-core/` 改名 `android/core/`；
   `docs/security/` 在移除内部台账后改名 `docs/release/`（其内容变为发布流程与
   第三方来源台账）。`.scratch/` 保留原名。
3. 合并前先清理 `fable-app` 历史中的签名材料；合并后不再重写历史。
4. 现有四个远端保持不动，直到新仓库 fresh clone 验证通过。合并后的仓库不保留
   Termux 上游远端。
5. ADR-0010 第 1 条与第 3 条（三个 crate 独立公开、在三个 crate 上配置公开仓库门禁）
   作废；其第 5 条关于"公开属不可逆外部动作、需再次确认"的约束保留，转入 ADR-0012。

## 考虑过的其他方案

- **多仓库加 submodule**：能保留每个 crate 的独立身份，但 submodule 的指针极易过期，
  而本项目是单人双设备开发，不需要版本锁，收益不抵成本。
- **保持五个独立仓库不做关联**：电脑需要多次 clone，且"哪几份代码属于同一个版本"
  没有记录。

## 后果

- 一次 clone 得到完整工程；`spike-` 前缀随之消失，库名与包名对齐为角色名。
- 三个 crate 的既有远端降级为历史副本，其去留需单独决定。
- 公开时只需公开一个仓库，rulesets 与 branch protection 因此重新可用。
- 供应链门禁仍按每个 crate 的独立 lockfile 运行，不引入 Cargo workspace。
