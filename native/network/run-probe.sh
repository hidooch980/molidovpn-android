#!/usr/bin/env bash
set -euo pipefail
adb install -r -t network-artifacts/probe-app.apk
adb install -r -t network-artifacts/probe-test.apk
adb logcat -c
timeout 120s adb shell am instrument -w com.molido.vpn.networkprobe.test/com.molido.vpn.networkprobe.NetworkProbe | tee network-runtime.txt
adb logcat -d -v threadtime > network-logcat.txt
grep -q 'MOLIDO_NETWORK_PROBE_OK' network-runtime.txt
if grep -Eq 'MOLIDO_NETWORK_PROBE_FAILED|INSTRUMENTATION_FAILED|Process crashed' network-runtime.txt; then exit 1; fi
