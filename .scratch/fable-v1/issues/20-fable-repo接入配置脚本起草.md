# 20 — fable-repo 接入配置脚本起草（工单 18 前置准备）

**What to build:** 起草并离屏验证 Fable 侧接入 fable-repo 的配置脚本与说明：下载并核对仓库公钥（fable-repo-pub.asc/gpg，取自工单 17 已发布快照）、写入 Fable 系统信任区、生成 sources.list 指向 fable-repo 扁平仓库（`[signed-by=...]` 引用、停用官方 termux-main 源）、幂等可重复运行。本单只写脚本与离屏验证，不在真机实装；真机接入与 apt 补装验收归 fable-v1/18。

**Blocked by:** None

Status: 已完成

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

2026-08-09 实施完成（主线程）：

产物：`.scratch/fable-v1/migration/configure-fable-repo.sh`（配置脚本，幂等）+ 同目录
`fable-repo接入说明.md`（工单 18 衔接说明），随迁移包/共享存储分发。

验收逐项结果（离屏验证，未实写 Fable 环境）：

1. 环境守卫 PASS：HOME 非 `/data/data/com.gph.fable/*` 拒绝执行（实测旧 Termux HOME 下 exit 1）。
2. 公钥获取 PASS：按 `releases/latest/download/<name>` 下载 fable-repo-pub.asc + SHA256SUMS，
   双重校验一致（脚本锚定 sha256 `88c72816...` + SHA256SUMS 清单值）；篡改公钥实测在校验和
   环节即中止（exit 1，未写任何文件）；GitHub 走 ghfast.top / gh-proxy.com 镜像，直连兜底
   （本环境直连 25s 超时实测，ghfast 2.7s）。
3. 信任区写入 PASS：dearmor 二进制 `trusted.gpg.d/fable-repo.gpg`（292B，chmod 644），
   指纹复验 = `97291249E5BE2D529939F7F7A960D6CE7BA2DBED`。
4. sources 配置 PASS：`deb [signed-by=...] .../releases/latest/download/ ./`，无 trusted=yes；
   官方 termux-main 注释（sources.list 与 sources.list.d/*.list 都处理；首次运行备份
   `sources.list.fable-bak`，只备份不删除）。
5. 幂等 PASS：同一临时前缀连跑两次，第二次 exit 0、备份不覆盖、fable-repo.list 单条目无重复。
6. 离屏验证 PASS：`bash -n` 0 错误；shellcheck 0 告警（在线 API，真 shellcheck 版本）；
   `--dry-run` 输出符合预期且未写任何文件；脚本产物端到端：apt 2.8.1 `update` 0 退出、
   `apt-cache policy git/jq/openssh` 候选版本正确、`apt-get download zip` 成功、
   `gpgv` InRelease Good signature（非 trusted 模式）。
7. 说明文档 PASS：含工单 18 执行顺序（Phase B → 本脚本 → apt update → 首批清单补装）、
   回滚、大陆网络坑、公钥轮换提示。

踩过的坑与解法：

- gpg 需要可写 homedir：脚本显式 `GNUPGHOME=$FABLE_HOME/.fable-repo/.gnupg`（不设时在
  受控测试的假 HOME 下指纹读取为空，真机 HOME 正常但显式更稳）。
- sed 以 `#` 作分隔符时，替换串里的 `#` 必须转义 `\#`，否则报 `unknown option to 's'`
  （初版未转义，实测踩到）。
- GitHub API 未认证共享限流（60 次/小时，实测 403），快照/Release 核实改走 Release 资产
  直链而非 API。

结论写回：

- 工单 18 直接用本脚本；当前最新快照 `fable-repo-2026.08.09-r1`（185 资产）已含首批清单
  21 个中的 12 个（git/gh/openssh/openssh-sftp-server/nodejs/npm/runit/termux-services/
  wget/zip/python-pip/jq）；openjdk-17/21/25（含 -x）与 rust/rust-std/clang 共 9 个待
  工单 17 重跑补齐（首次全量构建 run 31280231793 已失败）。
- 真机 apt 直连 GitHub 若超时（大陆网络），按说明文档把源条目临时换镜像 URL 或配 apt
  代理（可逆，工单 18 现场决策）。
- 公钥轮换时需同步更新脚本内嵌指纹与校验和（有意的安全行为：脚本会拒绝安装新公钥）。
