#!/bin/bash
# stress-loop.sh —— 模式 B stress 阶段（旧 Termux 运行，驱动 Fable 真机）：
# 12 分钟自动化：4 会话切换 + 滚动 + 复制粘贴 + 周期截图。
# 前置：4 会话已开、工作负载已起（s1..s4.txt 增长中）、Fable 在前台。
set -u
DEV="${ADB_DEV:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
W=1080
H=2400
R=$HOME/CODEX/Fable/.scratch/fable-v1/baseline/results-$(date +%Y%m%d-%H%M)
mkdir -p "$R"
SA() { adb -s "$DEV" shell "$@"; }
tap() { SA input tap "$1" "$2"; }
swipe() { SA input swipe "$1" "$2" "$3" "$4" "${5:-300}"; }
dump() {
  SA rm -f /sdcard/fable-ui.xml
  SA uiautomator dump /sdcard/fable-ui.xml >/dev/null 2>&1
  adb -s "$DEV" pull /sdcard/fable-ui.xml "$R/ui.xml" >/dev/null 2>&1
  sed 's/></>\n</g' "$R/ui.xml" > "$R/ui.lines"
}
bcenter() { sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }

close_ime() {
  local i
  i=$(SA dumpsys input_method 2>/dev/null | grep -oE "mInputShown=(true|false)" | head -1)
  [ "$i" = "mInputShown=true" ] && { SA input keyevent 4; sleep 1; }
}
front() { SA am start -n com.gph.fable/.app.TermuxActivity >/dev/null 2>&1; sleep 2; }

# 实测缓存坐标（2026-08-11 真机核实；失败自动重定位）
DRAWER_KEY="236 2200"
ROWS="336 307
336 485
336 663
336 841"
COPY_POS="725 187"
PASTE_POS="856 187"

open_drawer() {
  tap $DRAWER_KEY
  sleep 2
  dump
  if ! grep -q "left_drawer" "$R/ui.lines"; then
    local c
    c=$(grep -m1 'text="☰"' "$R/ui.lines" | bcenter)
    [ -n "$c" ] && { tap $c; sleep 2; dump; }
  fi
  grep -q "left_drawer" "$R/ui.lines"
}

switch_s() { # 1..4
  local idx=$1 row
  close_ime
  if ! open_drawer; then echo "switch $idx FAIL: drawer 未开"; return 1; fi
  row=$(printf '%s\n' "$ROWS" | sed -n "${idx}p")
  tap $row
  sleep 2
}
scroll_up() { swipe 540 1200 540 500 300; }
scroll_down() { swipe 540 500 540 1200 300; }
longpress() { swipe 540 800 540 800 1200; }

copy_sel() {
  longpress; sleep 2
  dump
  local c
  c=$(grep -m1 'text="复制"' "$R/ui.lines" | bcenter)
  [ -n "$c" ] || c=$COPY_POS
  tap $c; sleep 1
}
paste_here() {
  longpress; sleep 2
  dump
  local c
  c=$(grep -m1 'text="粘贴"' "$R/ui.lines" | bcenter)
  [ -n "$c" ] || c=$PASTE_POS
  tap $c; sleep 1
}
shot() { adb -s "$DEV" exec-out screencap -p > "$R/$1.png"; echo "saved $R/$1.png"; }

echo "stress start $(date '+%F %T')" | tee "$R/stress.log"
for cyc in $(seq "${START_CYCLE:-1}" 4); do
  echo "cycle $cyc start $(date '+%F %T')" >> "$R/stress.log"
  front
  for i in 1 2 3 4; do
    switch_s "$i" || break
    scroll_up; sleep 1; scroll_down; sleep 1
  done
  switch_s 1; copy_sel
  switch_s 2; paste_here
  switch_s 3; copy_sel
  switch_s 4; paste_here
  front
  shot "shot-$((cyc * 3))m"
  echo "cycle $cyc done $(date '+%F %T')" >> "$R/stress.log"
  [ "$cyc" -lt 4 ] && sleep 90
done
echo "stress end $(date '+%F %T')" | tee -a "$R/stress.log"
