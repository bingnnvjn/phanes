# 公开发布审计工具固定版本

工单 57 的工具只安装在仓库外缓存，不进入仓库或任何构建输入。
运行闸门时显式传入绝对路径，避免误用系统中另一版本的同名程序。

| 工具 | 固定版本 | 来源 | 本机可执行文件 SHA-256（2026-08-16） |
| --- | --- | --- | --- |
| gitleaks | `v8.29.0` | `github.com/zricethezav/gitleaks`, tag `v8.29.0`, source commit `39fdb480a06768cc41a84ef86959c07ff33091c4` | `520a7050c550d2f914341b2f0d6954630c0e69546b983fbe44430f9fcf1aa25b` |
| cargo-deny | `0.20.2` | crates.io package `cargo-deny`, `--locked` install | `c2c50bf1786cf4d6cedfd529482cb591b371bbf937c9d6be45ef304e13d89f2f` |

本机安装位置（不属于仓库）：

```text
$HOME/.cache/fable-public-release-tools/gitleaks
$HOME/.cache/fable-public-release-tools/cargo-deny/bin/cargo-deny
```

复现安装（需人工选择仓库外的 `FABLE_PUBLIC_TOOL_ROOT`）：

```bash
FABLE_PUBLIC_TOOL_ROOT=/path/outside/repository/fable-public-release-tools
mkdir -p "$FABLE_PUBLIC_TOOL_ROOT"
GOPROXY=https://goproxy.cn,direct \
  GOBIN="$FABLE_PUBLIC_TOOL_ROOT" \
  go install -ldflags='-X github.com/zricethezav/gitleaks/v8/version.Version=v8.29.0' \
  github.com/zricethezav/gitleaks/v8@v8.29.0
cargo install cargo-deny --version 0.20.2 --locked \
  --root "$FABLE_PUBLIC_TOOL_ROOT/cargo-deny"
```

扫描器命令只输出 PASS/FAIL、路径和摘要；任何 finding 的原文都不得写入
工单、构建日志或聊天记录。
