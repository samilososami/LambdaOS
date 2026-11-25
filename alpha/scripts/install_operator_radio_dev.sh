#!/usr/bin/env bash
# Explicit development provisioning. No partitions, SELinux policies or boot hooks.
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
phone=${ANDROID_SERIAL:?set ANDROID_SERIAL to the target device}
out="$base/out/operator-handshake/Lambda-operator2.apk"
adb -s "$phone" get-state
test "$(adb -s "$phone" shell getprop ro.product.device | tr -d '\r')" = beyond1lte
python3 "$base/scripts/build_lambda.py"
python3 "$base/scripts/build_radio_helpers.py"
node "$base/scripts/sign_operator.mjs" "$base/out/lambda-0.2.0-operator.2-dev.apk" "$out"
adb -s "$phone" root
adb -s "$phone" wait-for-device
test "$(adb -s "$phone" shell getenforce | tr -d '\r')" = Enforcing
# Stop only this worker, after its own shutdown hook has restored the radio.
adb -s "$phone" shell 'if [ -f /data/local/tmp/lambda-operator-radio/worker.pid ]; then
 pid=$(cat /data/local/tmp/lambda-operator-radio/worker.pid)
 case "$pid" in *[!0-9]*|"") exit 1;; esac
 if [ -r /proc/$pid/cmdline ] && tr "\000" " " < /proc/$pid/cmdline | grep -q "org.lambdaproject.lambda.RadioWarden"; then
   kill -TERM "$pid"
   for n in 1 2 3 4 5 6 7 8 9 10; do [ ! -d /proc/$pid ] && break; sleep 1; done
   [ ! -d /proc/$pid ] || exit 1
 fi
fi'
adb -s "$phone" install --no-incremental -r "$out"
adb -s "$phone" shell 'mkdir -p /data/local/tmp/lambda-operator-radio; chmod 700 /data/local/tmp/lambda-operator-radio; chmod 644 /data/local/tmp/lambda-operator-radio/worker.apk 2>/dev/null || true'
adb -s "$phone" push "$out" /data/local/tmp/lambda-operator-radio/worker.apk
for binary in nexutil; do
 adb -s "$phone" push "$base/../kernel/reference/nexmon-s10/extracted/system/bin/$binary" "/data/local/tmp/lambda-operator-radio/$binary"
done
adb -s "$phone" shell 'test ! -L /data/lambda-op; mkdir -p /data/lambda-op; chown root:root /data/lambda-op; chmod 700 /data/lambda-op'
for binary in iwpriv iwconfig; do
 adb -s "$phone" push "$base/out/operator-handshake/native/$binary" "/data/lambda-op/$binary"
done
adb -s "$phone" push "$base/out/operator-handshake/native/aireplay-ng" /data/local/tmp/lambda-operator-radio/aireplay-ng
adb -s "$phone" push "$base/out/operator-handshake/native/airodump-ng" /data/local/tmp/lambda-operator-radio/airodump-ng
adb -s "$phone" shell chmod 755 /data/local/tmp/lambda-operator-radio/airodump-ng
adb -s "$phone" push "$base/out/operator-handshake/native/radio-guard" /data/local/tmp/lambda-operator-radio/radio-guard
adb -s "$phone" shell chmod 755 /data/local/tmp/lambda-operator-radio/radio-guard
adb -s "$phone" shell 'cp /data/local/tmp/lambda-radio-validation/libnexmon.so /data/local/tmp/lambda-operator-radio/libnexmon.so; chmod 755 /data/lambda-op/iwpriv /data/lambda-op/iwconfig /data/local/tmp/lambda-operator-radio/nexutil /data/local/tmp/lambda-operator-radio/aireplay-ng; chmod 444 /data/local/tmp/lambda-operator-radio/worker.apk; CLASSPATH=/data/local/tmp/lambda-operator-radio/worker.apk nohup setsid /system/bin/app_process /system/bin org.lambdaproject.lambda.RadioWarden > /data/local/tmp/lambda-operator-radio/worker.log 2>&1 < /dev/null &'
sleep 2
adb -s "$phone" shell content call --uri content://org.lambdaproject.lambda.core --method radio_status
