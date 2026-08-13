#!/bin/bash
# grep-log.sh <start_epoch_s> <end_epoch_s> —— 提取 fable-render-debug.log.txt
# 运行窗口内事件并计数（surface 生命周期 / 会话切换 / 选择）。
BASE="${FABLE_BASELINE_BASE:-/storage/emulated/0/Download/fable-baseline-23}"
RUN="$BASE/current"
LOG="/storage/emulated/0/Download/fable-render-debug.log.txt"
[ $# -ge 2 ] || { echo "用法: grep-log.sh <start_epoch_s> <end_epoch_s>"; exit 1; }
s_ms=$(( $1 * 1000 ))
e_ms=$(( $2 * 1000 ))
mkdir -p "$RUN"
if [ ! -f "$LOG" ]; then echo "无 $LOG（App 未写诊断日志？）"; exit 1; fi
W="$RUN/log-window.txt"
awk -v s="$s_ms" -v e="$e_ms" '{ if ($1+0 >= s && $1+0 <= e) print }' "$LOG" > "$W"
echo "-- fable-render-debug.log 窗口事件（$1 → $2）--"
echo "窗口日志行数: $(wc -l < "$W")"
for ev in "surfaceDestroyed" "autoRecreateSurface" "scheduleAutoRecreate" "attachSession switch" "selection:copyModeChanged" "== Fable diagnostics start =="; do
  # grep returns 1 when there is no match. Zero matches are valid here and
  # must not abort verification when the caller inherits errexit.
  c=$(grep -c "$ev" "$W" || true)
  echo "$ev: $c"
done
