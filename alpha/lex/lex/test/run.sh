#!/bin/sh
# Host-only tests for the native RFB client. Compiles LexRfbClient.java with
# the host javac, runs the JVM unit checks and one end-to-end session against
# fake_rfb_server.py. Not part of the APK build.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE="$HERE/../../apps/Lambda/src/org/lambdaproject/lambda/LexRfbClient.java"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/lex-rfb-test.XXXXXX")
SERVER_PID=
cleanup(){
    if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null || true; fi
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

fail(){
    echo "FAIL: $*"
    exit 1
}

command -v javac >/dev/null 2>&1 || fail "javac not found"
command -v java >/dev/null 2>&1 || fail "java not found"
command -v python3 >/dev/null 2>&1 || fail "python3 not found"

mkdir -p "$WORK/classes"
javac --release 8 -d "$WORK/classes" "$SOURCE" "$HERE/RfbClientTest.java" \
    || fail "javac could not build LexRfbClient and the test"

python3 -I "$HERE/fake_rfb_server.py" "$WORK/report.json" "$WORK/port.txt" &
SERVER_PID=$!
i=0
while [ ! -s "$WORK/port.txt" ]; do
    i=$((i + 1))
    [ "$i" -gt 100 ] && fail "fake server did not open a port"
    kill -0 "$SERVER_PID" 2>/dev/null || fail "fake server exited early"
    sleep 0.05
done
PORT=$(cat "$WORK/port.txt")

java -cp "$WORK/classes" org.lambdaproject.lambda.RfbClientTest "$PORT" "$WORK/report.json" \
    || fail "JVM tests"

wait "$SERVER_PID" || fail "fake server rejected the session"
SERVER_PID=
echo "PASS: run.sh completed"
