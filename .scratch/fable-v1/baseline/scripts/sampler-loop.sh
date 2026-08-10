#!/bin/bash
# sampler-loop.sh —— 后台内存采样：每 5s 记录 com.gph.fable 同 UID 进程的
# VmRSS/PSS；全部会话完成（s?.done）或超过 1100s 后停止。
BASE="${FABLE_BASELINE_BASE:-/storage/emulated/0/Download/fable-baseline-23}"
RUN="$BASE/current"
mkdir -p "$RUN"
OUT="$RUN/mem-samples.tsv"
LOG="$RUN/sampler.log"
echo -e "ts_epoch\trel_s\tpid\tname\tvmrss_kb\tpss_kb" > "$OUT"
start=$(date +%s)
peak_total=0
peak_at=""
while true; do
  now=$(date +%s)
  rel=$((now - start))
  done_count=$(ls "$RUN"/s?.done 2>/dev/null | wc -l)
  if [ "$done_count" -ge 4 ] || [ "$rel" -ge 1100 ]; then break; fi
  total=0
  for pid in $(ls /proc | grep -E '^[0-9]+$'); do
    cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
    case "$cmd" in
      *com.gph.fable*)
        vmrss=$(awk '/^VmRSS:/{print $2}' "/proc/$pid/status" 2>/dev/null)
        pss=$(awk '/^Pss:/{print $2}' "/proc/$pid/smaps_rollup" 2>/dev/null)
        [ -z "$vmrss" ] && vmrss=0
        [ -z "$pss" ] && pss="NA"
        echo -e "$now\t$rel\t$pid\t${cmd:0:40}\t$vmrss\t$pss" >> "$OUT"
        total=$((total + vmrss))
        ;;
    esac
  done
  if [ "$total" -gt "$peak_total" ]; then peak_total=$total; peak_at=$now; fi
  echo "$now sample_total_kb=$total peak_so_far_kb=$peak_total" >> "$LOG"
  sleep 5
done
echo "STOP $(date +%s) peak_total_kb=$peak_total peak_at=$peak_at samples=$(($(wc -l < "$OUT") - 1))" >> "$LOG"
