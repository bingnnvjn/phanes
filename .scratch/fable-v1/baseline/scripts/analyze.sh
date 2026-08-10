#!/bin/bash
# analyze.sh —— 解析 mem-samples.tsv / sampler.log：每进程峰值 + 合计峰值 + 时间线头尾。
BASE="${FABLE_BASELINE_BASE:-/storage/emulated/0/Download/fable-baseline-23}"
RUN="$BASE/current"
MS="$RUN/mem-samples.tsv"
LOG="$RUN/sampler.log"
[ -f "$MS" ] || { echo "无 mem-samples.tsv"; exit 1; }
n=$(( $(wc -l < "$MS") - 1 ))
echo "== 内存分析 (样本数=$n, 5s 间隔) =="
awk -F'\t' 'NR>1 && $5 != "NA" {
  if (!($3 in max)) { max[$3]=$5; name[$3]=$4 }
  else if ($5 > max[$3]) max[$3]=$5
} END {
  printf "覆盖进程数: %d\n", length(max)
  for (p in max) printf "  峰值 VmRSS(kB)=%8s  pid=%-7s %s\n", max[p], p, name[p]
}' "$MS"
echo "-- sampler.log 汇总 --"
grep -E "sample_total_kb|STOP" "$LOG" 2>/dev/null | tail -n 4
echo "-- 时间线头/尾 --"
sed -n 2p "$MS"
tail -n 1 "$MS"
