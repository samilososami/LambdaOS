#!/system/bin/sh
# LEX desktop stop: VNC, websockify and chroot bind mounts.
set -eu
PATH=/system/bin:/system/xbin
BASE=/data/local/lex
MNT=$BASE/rootfs
DISP=1

mounted() { grep -q " $1 " /proc/mounts 2>/dev/null; }

# Signal Xvnc directly: vncserver -kill is a slow Perl path and the lock
# already names the server. X clients exit when their display disappears.
XPID=$(cat "$MNT/tmp/.X$DISP-lock" 2>/dev/null | tr -dc '0-9' || true)
case "$XPID" in
  *[!0-9]*|"") ;;
  *)
    if [ -r "/proc/$XPID/cmdline" ] && tr '\000' ' ' < "/proc/$XPID/cmdline" | grep -q Xtigervnc; then
      kill -TERM "$XPID" 2>/dev/null || true
      n=0
      while [ -d "/proc/$XPID" ] && [ "$n" -lt 10 ]; do sleep 0.5; n=$((n+1)); done
      [ -d "/proc/$XPID" ] && kill -9 "$XPID" 2>/dev/null || true
    fi
    ;;
esac
rm -f "$MNT/tmp/.X$DISP-lock" "$MNT/tmp/.X11-unix/X$DISP" 2>/dev/null || true

if [ -s "$BASE/websockify.pid" ]; then
  kill "$(cat "$BASE/websockify.pid")" 2>/dev/null || true
  rm -f "$BASE/websockify.pid"
fi
# websockify 0.13 forks multiprocessing helpers that inherit the listening
# socket; kill every process whose command line names the binary.
for c in /proc/[0-9]*/cmdline; do
  cmd=$( { tr '\0' ' ' < "$c"; } 2>/dev/null || true )
  case "$cmd" in
    *"/usr/bin/websockify"*)
      p=${c#/proc/}; p=${p%/cmdline}
      [ "$p" != "$$" ] && kill -9 "$p" 2>/dev/null || true
      ;;
  esac
done
n=0
while grep -q ":17C0 .* 0A" /proc/net/tcp && [ "$n" -lt 10 ]; do
  sleep 0.5
  n=$((n+1))
done
sleep 1
for d in dev/pts dev/shm dev sys proc; do
  if mounted "$MNT/$d"; then
    umount "$MNT/$d" 2>/dev/null || umount -l "$MNT/$d" 2>/dev/null || true
  fi
done
LEFT=0
if grep -q ":170D .* 0A" /proc/net/tcp; then LEFT=1; fi
if grep -q ":17C0 .* 0A" /proc/net/tcp; then LEFT=1; fi
if [ "$LEFT" = 1 ]; then
  printf '{"running":true,"stop_incomplete":true}\n' > "$BASE/status.json"
else
  printf '{"running":false}\n' > "$BASE/status.json"
fi
chmod 600 "$BASE/status.json"
cat "$BASE/status.json"
