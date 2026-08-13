#!/bin/bash
# verify.sh —— 输出完整性 + 内存采样 + 诊断日志窗口 全量校验。
BASE="${FABLE_BASELINE_BASE:-/storage/emulated/0/Download/fable-baseline-23}"
RUN="$BASE/current"
if [ ! -d "$RUN" ]; then echo "没有 $RUN —— 先运行 start-run.sh"; exit 1; fi
exec > >(tee -a "$RUN/run.log" "$RUN/verify.log") 2>&1
echo "== verify $(date '+%F %T') ==" | tee -a "$RUN/run.log"
pass=0
fail=0
ok() { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1"; fail=$((fail + 1)); }

echo "---- 输出完整性 ----"
for sid in $(seq -f 's%g' 1 "${FABLE_SESSIONS:-4}"); do
  f="$RUN/$sid.txt"
  d="$RUN/$sid.done"
  if [ ! -f "$d" ]; then
    bad "$sid: 无 done 标记（未完成/被中断）"
    continue
  fi
  if [ ! -s "$f" ]; then bad "$sid: 输出文件为空"; continue; fi
  lines=$(wc -l < "$f")
  if [ "$lines" -eq 450000 ]; then ok "$sid: 行数=450000"; else bad "$sid: 行数=$lines (期望 450000)"; fi
  awk -v sid="$sid" 'BEGIN{for(r=1;r<=90;r++)for(i=1;i<=5000;i++)printf "%s_r%03d_%06d\n", sid, r, i}' > "$RUN/expected-$sid.txt"
  exp=$(sha256sum "$RUN/expected-$sid.txt" | cut -d' ' -f1)
  act=$(sha256sum "$f" | cut -d' ' -f1)
  if [ "$exp" = "$act" ]; then ok "$sid: sha256 与期望流一致"; else bad "$sid: sha256 不一致（丢行/乱序/重复）"; fi
  first=$(head -1 "$f")
  last=$(tail -1 "$f")
  if [ "$first" = "${sid}_r001_000001" ]; then ok "$sid: 首行正确"; else bad "$sid: 首行=$first"; fi
  if [ "$last" = "${sid}_r090_005000" ]; then ok "$sid: 末行正确"; else bad "$sid: 末行=$last"; fi
done

echo "---- 内存采样 ----"
ms="$RUN/mem-samples.tsv"
if [ -f "$ms" ]; then
  n=$(( $(wc -l < "$ms") - 1 ))
  if [ "$n" -ge 100 ]; then ok "内存采样条数=$n (>=100)"; else bad "内存采样条数=$n (<100，采样可能被中断)"; fi
  bash "$BASE/analyze.sh"
else
  bad "内存采样文件缺失"
fi

echo "---- 运行窗口与诊断日志 ----"
if [ -f "$RUN/run.txt" ]; then
  start_epoch=$(head -1 "$RUN/run.txt")
  now=$(date +%s)
  echo "run_start=$start_epoch  end=$now  时长=$((now - start_epoch))s"
  if grep -q "^STOP " "$RUN/sampler.log" 2>/dev/null; then ok "采样器正常结束（STOP 存在）"; else bad "采样器无 STOP 记录（可能被崩溃中断）"; fi
  bash "$BASE/grep-log.sh" "$start_epoch" "$now" | tee -a "$RUN/run.log"
else
  bad "run.txt 缺失"
fi

echo "----"
echo "SUMMARY pass=$pass fail=$fail"
if [ "$fail" -eq 0 ]; then echo "RESULT: ALL PASS"; else echo "RESULT: $fail FAIL —— 细节按报告模板记录"; fi
