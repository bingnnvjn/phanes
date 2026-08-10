#!/bin/bash
# run-session.sh <s1|s2|s3|s4> —— 在 Fable 主终端的一个会话运行（建议加 & 后台跑）。
# 生成确定性长输出（90 轮 × 5000 行，轮间 7s，约 12 分钟），同时 tee 到文件：
# 文件内容 = 写入该会话 PTY 的同一字节流，作为输出完整性真值。
SID="${1:-}"
BASE="${FABLE_BASELINE_BASE:-/storage/emulated/0/Download/fable-baseline-23}"
RUN="$BASE/current"
case "$SID" in s1|s2|s3|s4) ;; *) echo "用法: bash run-session.sh s1|s2|s3|s4"; exit 1 ;; esac
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
