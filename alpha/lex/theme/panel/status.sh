#!/bin/sh
# One line of machine state for the LEX top bar: CPU, memory and battery.
# Plain text, monochrome by design; genmon prints whatever this writes.
set -u

cpu() {
  # Busy share since the previous call, from the two first /proc/stat lines.
  set -- $(awk '/^cpu /{print $2+$3+$4, $5, $2+$3+$4+$5+$6+$7+$8}' /proc/stat 2>/dev/null)
  busy=$1; total=$3
  prev=$(cat /tmp/lex-cpu 2>/dev/null || echo "0 0")
  set -- $prev
  db=$((busy - ${1:-0})); dt=$((total - ${2:-0}))
  echo "$busy $total" > /tmp/lex-cpu 2>/dev/null || true
  [ "$dt" -gt 0 ] 2>/dev/null && echo $((db * 100 / dt)) || echo 0
}

mem() {
  awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} END{if(t>0)printf "%.1f/%.0fG", (t-a)/1048576, t/1048576}' /proc/meminfo 2>/dev/null
}

bat() {
  for p in /sys/class/power_supply/battery/capacity /sys/class/power_supply/*/capacity; do
    [ -r "$p" ] && { cat "$p" 2>/dev/null; return; }
  done
}

line="CPU $(cpu)%   RAM $(mem)"
level=$(bat)
[ -n "${level:-}" ] && line="$line   BAT ${level}%"
printf '%s\n' "$line"
