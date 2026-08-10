#!/bin/bash
# adb-drive.sh —— 在旧 Termux（本环境）运行，经无线调试驱动 Fable 真机：
# 开 4 会话 + 12 分钟切换/滚动/复制粘贴 + 截图 + logcat + 内存 + verify。
# 前置：手机开「无线调试」，配对后 adb devices 可见设备。
#
# 用法:
#   bash adb-drive.sh connect <port> <code>   # 配对并连接（无线调试对话框里的端口+配对码）
#   bash adb-drive.sh prep                     # 启动 App + 开 4 会话 + 起工作负载
#   bash adb-drive.sh stress                   # 12 分钟自动化操作（4 周期）
#   bash adb-drive.sh finish                   # 等 DONE + verify + 拉取全部结果
#   bash adb-drive.sh status                   # 设备与进度

set -u
ADB=adb
PKG=com.gph.fable
ACT=.app.TermuxActivity
BASE=/storage/emulated/0/Download/fable-baseline-23
RESULTS=$HOME/CODEX/Fable/.scratch/fable-v1/baseline/results-$(date +%Y%m%d-%H%M)
mkdir -p "$RESULTS"

DEV="${ADB_DEV:-}"
if [ -z "$DEV" ]; then
  DEV=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
[ -n "$DEV" ] || { echo "无 adb 设备 —— 先配对/连接"; exit 1; }
SA() { adb -s "$DEV" shell "$@"; }

# 用户聊天/切走会把前台切回 Termux；每个手势前都把 Fable 调回前台（singleTask，不重建会话）。
ensure_front() {
  SA am start -n $PKG/$ACT >/dev/null 2>&1
  sleep 2
}

wm_size() { SA wm size | grep -o '[0-9]*x[0-9]*' | head -1; }
W=$(wm_size | cut -dx -f1)
H=$(wm_size | cut -dx -f2)

ui_dump() {
  local i
  for i in 1 2 3; do
    SA rm -f /sdcard/fable-ui.xml
    SA uiautomator dump /sdcard/fable-ui.xml >/dev/null 2>&1
    adb -s "$DEV" pull /sdcard/fable-ui.xml "$RESULTS/ui.xml" >/dev/null 2>&1
    if [ -s "$RESULTS/ui.xml" ]; then
      sed 's/></>\n</g' "$RESULTS/ui.xml" > "$RESULTS/ui.lines"
      return 0
    fi
    sleep 1
  done
  : > "$RESULTS/ui.lines"
  return 1
}

bounds_center() { # node line -> "x y"
  printf '%s' "$1" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 |
    sed 's/bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/' |
    awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}

center_of_text() { # TEXT -> "x y"（uiautomator 找文本节点）
  local line
  line=$(grep -m1 "text=\"$1\"" "$RESULTS/ui.lines")
  [ -n "$line" ] && bounds_center "$line"
}

center_of_id() { # resource-id 后缀 -> "x y"
  local line
  line=$(grep -m1 "resource-id=\"$PKG:id/$1\"" "$RESULTS/ui.lines")
  [ -n "$line" ] && bounds_center "$line"
}

session_row_centers() { # 抽屉会话行中心（session_title 节点，按顺序输出）
  grep 'resource-id="'"$PKG"':id/session_title"' "$RESULTS/ui.lines" |
    sed 's/.*text="\(\[[0-9]*\]\)[^"]*".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\2 \3 \4 \5/' |
    awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}

tap() { SA input tap "$1" "$2"; }
swipe() { SA input swipe "$1" "$2" "$3" "$4" "${5:-300}"; }
type_text() { SA input text "$(printf '%s' "$1" | sed 's/ /%s/g')"; }
enter() { SA input keyevent 66; }

open_drawer() {
  # <厂商 ROM> 手势导航会吃掉左缘滑动；改用扩展键行的 ☰（DRAWER 特殊键）开抽屉。
  ensure_front
  ui_dump
  local c
  c=$(center_of_text "☰")
  if [ -n "$c" ]; then tap $c; sleep 2; else echo "未找到 ☰ 抽屉键"; return 1; fi
}

new_session() {
  open_drawer
  ui_dump
  local c
  c=$(center_of_text "新会话")
  [ -z "$c" ] && c=$(center_of_text "New session")
  if [ -n "$c" ]; then tap $c; else echo "未找到新会话按钮"; return 1; fi
  sleep 2
}

switch_session() { # 1..4（创建顺序）
  local idx=$1
  open_drawer
  ui_dump
  local c
  c=$(session_row_centers | sed -n "${idx}p")
  if [ -n "$c" ]; then tap $c; else echo "未找到会话行 $idx"; return 1; fi
  sleep 1
}

scroll_up() { swipe "$((W/2))" "$((H*7/10))" "$((W/2))" "$((H*3/10))" 250; }
scroll_down() { swipe "$((W/2))" "$((H*3/10))" "$((W/2))" "$((H*7/10))" 250; }
longpress() { swipe "$1" "$2" "$1" "$2" 1200; }

copy_selected() {
  ui_dump
  local c
  c=$(center_of_text "复制")
  if [ -n "$c" ]; then tap $c; sleep 1; return 0; fi
  # 兜底：TYPE_PRIMARY ActionMode 顶部条，复制在最右
  tap "$((W-90))" 110; sleep 1
}

paste_here() {
  ui_dump
  local c
  c=$(center_of_text "粘贴")
  if [ -n "$c" ]; then tap $c; sleep 1; return 0; fi
  tap "$((W-220))" 110; sleep 1
}

screenshot() {
  adb -s "$DEV" exec-out screencap -p > "$RESULTS/$1.png"
  echo "截图 $RESULTS/$1.png"
}

cmd_prep() {
  ensure_front
  echo "== prep: 启动 App =="
  SA am force-stop $PKG
  sleep 1
  SA am start -W -n $PKG/$ACT
  sleep 5
  SA logcat -c
  ensure_front
  echo "== prep: 会话1 start-run + 工作负载 =="
  type_text "bash $BASE/start-run.sh"; enter; sleep 2
  type_text "bash $BASE/run-session.sh s1"; enter; sleep 2
  for s in s2 s3 s4; do
    new_session
    type_text "bash $BASE/run-session.sh $s"; enter; sleep 2
  done
  ensure_front
  open_drawer; ui_dump
  echo "会话行数（期望 4）：$(session_row_centers | wc -l)"
  echo "$(date +%s) prep_done" >> "$RESULTS/drive.log"
  echo "prep 完成"
}

cmd_stress() {
  echo "== stress: 12 分钟 4 周期 =="
  for cyc in 1 2 3 4; do
    ensure_front
    echo "cycle $cyc $(date '+%F %T')" >> "$RESULTS/drive.log"
    for i in 1 2 3 4; do
      ensure_front
      switch_session "$i"
      scroll_up; sleep 1; scroll_down; sleep 1
    done
    ensure_front
    switch_session 1; longpress "$((W/2))" "$((H*6/10))"; copy_selected
    ensure_front
    switch_session 2; longpress "$((W/2))" "$((H*6/10))"; paste_here
    ensure_front
    switch_session 3; longpress "$((W/2))" "$((H*6/10))"; copy_selected
    ensure_front
    switch_session 4; longpress "$((W/2))" "$((H*6/10))"; paste_here
    ensure_front
    screenshot "shot-$((cyc*3))m"
  done
  echo "$(date +%s) stress_done" >> "$RESULTS/drive.log"
  echo "stress 完成"
}

cmd_finish() {
  ensure_front
  echo "== finish: 等 4 会话 DONE =="
  for i in $(seq 1 60); do
    n=$(SA ls "$BASE/current/" 2>/dev/null | grep -c '\.done$')
    [ "$n" -ge 4 ] && break
    sleep 10
  done
  echo "done 文件数: $n"
  ensure_front
  switch_session 1
  ensure_front
  type_text "bash $BASE/verify.sh"; enter
  sleep 90
  adb -s "$DEV" pull "$BASE/current/" "$RESULTS/current/" >/dev/null 2>&1
  adb -s "$DEV" shell dumpsys meminfo $PKG > "$RESULTS/meminfo.txt" 2>&1
  adb -s "$DEV" logcat -d -v time > "$RESULTS/logcat.txt" 2>&1
  adb -s "$DEV" pull /storage/emulated/0/Download/fable-render-debug.log.txt "$RESULTS/" >/dev/null 2>&1
  echo "$(date +%s) finish_done" >> "$RESULTS/drive.log"
  echo "结果目录: $RESULTS"
  ls -la "$RESULTS" | head -n 30
}

cmd_status() {
  adb devices
  echo "--- current/ ---"
  SA ls -la "$BASE/current/" 2>/dev/null
  echo "--- drive.log ---"
  cat "$RESULTS/drive.log" 2>/dev/null
}

case "${1:-}" in
  connect)
    adb pair "localhost:$2" "$3" && adb connect "localhost:$2"
    ;;
  prep) cmd_prep ;;
  stress) cmd_stress ;;
  finish) cmd_finish ;;
  status) cmd_status ;;
  *)
    echo "用法: $0 connect <port> <code> | prep | stress | finish | status"
    exit 1
    ;;
esac
