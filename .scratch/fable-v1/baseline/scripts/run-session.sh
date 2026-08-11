#!/bin/bash
# run-session.sh <s1|s2|s3|s4> —— 在 Fable 主终端的一个会话运行（建议加 & 后台跑）。
# 生成确定性长输出（90 轮 × 5000 行，轮间 7s，约 12 分钟），同时 tee 到文件：
# 文件内容 = 写入该会话 PTY 的同一字节流，作为输出完整性真值。
SID="${1:-}"
BASE="${FABLE_BASELINE_BASE:-/storage/emulated/0/Download/fable-baseline-23}"
RUN="$BASE/current"
case "$SID" in s1|s2|s3|s4|s5|s6|s7|s8) ;; *) echo "用法: bash run-session.sh s1..s8（4 会话对比用 s1..s4；8 会话余量用 s1..s8）"; exit 1 ;; esac
# 首次调用自动转入后台（保留 stdout 在本会话显示），会话保持可交互（粘贴直达提示符）。
# 手动 `bash run-session.sh s1 &` 也兼容（双重后台无害）。
if [ -z "$FABLE_WL_DAEMON" ]; then
  export FABLE_WL_DAEMON=1
  bash "$0" "$@" &
  disown 2>/dev/null
  echo "run-session.sh $SID 已转入后台（输出仍显示在本会话）"
  exit 0
fi
if [ ! -d "$RUN" ]; then echo "先运行 start-run.sh"; exit 1; fi
FILE="$RUN/$SID.txt"
: > "$FILE"
echo "== $SID start $(date '+%F %T') ==" | tee -a "$RUN/run.log"
for ((r=1; r<=90; r++)); do
  awk -v sid="$SID" -v r="$r" 'BEGIN{for(i=1;i<=5000;i++) printf "%s_r%03d_%06d\n", sid, r, i}' | tee -a "$FILE"
  sleep 7
done
touch "$RUN/$SID.done"
echo "== $SID done $(date '+%F %T') lines=$(wc -l < "$FILE") ==" | tee -a "$RUN/run.log"
echo "DONE $SID —— 本会话保持打开"
