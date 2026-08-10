#!/bin/bash
# start-run.sh —— 在 Fable 主终端任一会话运行一次：
# 准备干净的 current/ 目录 + 启动后台内存采样器（5s 间隔）。
BASE="${FABLE_BASELINE_BASE:-/storage/emulated/0/Download/fable-baseline-23}"
mkdir -p "$BASE"
if [ -d "$BASE/current" ]; then
  old="$BASE/current-old-$(date +%Y%m%d-%H%M%S)"
  mv "$BASE/current" "$old"
  echo "已归档上一次运行 → $old"
fi
mkdir -p "$BASE/current"
nohup bash "$BASE/sampler-loop.sh" >> "$BASE/current/sampler-start.out" 2>&1 &
echo "$(date +%s)" > "$BASE/current/run.txt"
date '+%F %T' >> "$BASE/current/run.txt"
echo "== run start $(date '+%F %T') ==" >> "$BASE/current/run.log"
echo "sampler_pid=$!"
echo ""
echo "已就绪。接下来："
echo "1) 本会话运行:      bash $BASE/run-session.sh s1 &"
echo "2) 新建 3 个会话:   bash $BASE/run-session.sh s2 &  (s3 / s4 同理)"
echo "3) 按 scenario-协议.md 做 12 分钟切换/滚动/复制粘贴"
echo "4) 4 个会话都 DONE 后: bash $BASE/verify.sh"
