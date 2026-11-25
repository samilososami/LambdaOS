#!/usr/bin/env bash
# Explicit developer provisioning, no reboot/partitions/SELinux changes.
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
phone=${ANDROID_SERIAL:?set ANDROID_SERIAL to the target device}
out="$base/out/cracking-speed"
test "$(adb -s "$phone" shell getprop ro.product.device | tr -d '\r')" = beyond1lte
python3 "$base/scripts/build_lambda.py"
node "$base/scripts/sign_operator.mjs" "$base/out/lambda-0.2.0-operator.2-dev.apk" "$out/Lambda-operator-benchmark.apk"
adb -s "$phone" root
adb -s "$phone" wait-for-device
trap 'adb -s "$phone" unroot >/dev/null 2>&1 || true' EXIT
test "$(adb -s "$phone" shell getenforce | tr -d '\r')" = Enforcing
adb -s "$phone" shell 'if [ -f /data/local/tmp/lambda-operator-radio/worker.pid ]; then
 pid=$(cat /data/local/tmp/lambda-operator-radio/worker.pid)
 case "$pid" in *[!0-9]*|"") exit 1;; esac
 if [ -r /proc/$pid/cmdline ] && tr "\000" " " < /proc/$pid/cmdline | grep -q "org.lambdaproject.lambda.RadioWarden"; then
   kill -TERM "$pid"
   for n in 1 2 3 4 5 6 7 8 9 10; do [ ! -d /proc/$pid ] && break; sleep 1; done
   [ ! -d /proc/$pid ] || exit 1
 fi
fi'
adb -s "$phone" install --no-incremental -r "$out/Lambda-operator-benchmark.apk"
adb -s "$phone" shell 'test ! -L /data/local/tmp/lambda-crack-bench; mkdir -p /data/local/tmp/lambda-crack-bench/gpu/modules /data/local/tmp/lambda-crack-bench/gpu/feeds /data/local/tmp/lambda-crack-bench/cache; chmod 644 /data/local/tmp/lambda-operator-radio/worker.apk'
adb -s "$phone" push "$out/Lambda-operator-benchmark.apk" /data/local/tmp/lambda-operator-radio/worker.apk
for f in aircrack-ng libc++_shared.so benchmark-guard opencl-probe; do
 adb -s "$phone" push "$out/$f" "/data/local/tmp/lambda-crack-bench/$f"
done
adb -s "$phone" push "$out/vendor/aircrack-ng-1.7-sha/aircrack-ng" /data/local/tmp/lambda-crack-bench/aircrack-ng-sha
adb -s "$phone" shell chmod 755 /data/local/tmp/lambda-crack-bench/aircrack-ng-sha
adb -s "$phone" push "$out/vendor/aircrack-ng-1.7-hybrid/aircrack-ng" /data/local/tmp/lambda-crack-bench/aircrack-ng-hybrid
adb -s "$phone" push "$out/pmk-hybrid-worker" /data/local/tmp/lambda-crack-bench/pmk-hybrid-worker
adb -s "$phone" push "$base/apps/Lambda/native/pmk-hybrid.cl" /data/local/tmp/lambda-crack-bench/pmk-hybrid.cl
adb -s "$phone" shell chmod 755 /data/local/tmp/lambda-crack-bench/aircrack-ng-hybrid /data/local/tmp/lambda-crack-bench/pmk-hybrid-worker
adb -s "$phone" push "$out/fixtures" /data/local/tmp/lambda-crack-bench/
adb -s "$phone" push "$out/notices" /data/local/tmp/lambda-crack-bench/
adb -s "$phone" push "$out/vendor/hashcat-8.0.0/hashcat" /data/local/tmp/lambda-crack-bench/gpu/hashcat
for f in module_00000.so module_22000.so; do
 adb -s "$phone" push "$out/vendor/hashcat-8.0.0/modules/$f" /data/local/tmp/lambda-crack-bench/gpu/modules/
done
adb -s "$phone" push "$out/vendor/hashcat-8.0.0/feeds/feed_wordlist.so" /data/local/tmp/lambda-crack-bench/gpu/feeds/
adb -s "$phone" push "$out/vendor/hashcat-8.0.0/OpenCL" /data/local/tmp/lambda-crack-bench/gpu/
adb -s "$phone" shell 'chown -R root:root /data/local/tmp/lambda-crack-bench; chmod -R u=rwX,go=rX /data/local/tmp/lambda-crack-bench; chmod 755 /data/local/tmp/lambda-crack-bench/benchmark-guard /data/local/tmp/lambda-crack-bench/opencl-probe /data/local/tmp/lambda-crack-bench/aircrack-ng /data/local/tmp/lambda-crack-bench/gpu/hashcat; chown -R shell:shell /data/local/tmp/lambda-crack-bench/cache; chmod 700 /data/local/tmp/lambda-crack-bench/cache; chmod 444 /data/local/tmp/lambda-operator-radio/worker.apk; CLASSPATH=/data/local/tmp/lambda-operator-radio/worker.apk nohup setsid /system/bin/app_process /system/bin org.lambdaproject.lambda.RadioWarden > /data/local/tmp/lambda-operator-radio/worker.log 2>&1 < /dev/null &'
sleep 2
adb -s "$phone" shell content call --uri content://org.lambdaproject.lambda.core --method radio_status
adb -s "$phone" unroot
adb -s "$phone" wait-for-device
test "$(adb -s "$phone" shell id -u | tr -d '\r')" = 2000
trap - EXIT
