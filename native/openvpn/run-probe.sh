#!/usr/bin/env bash
set -euo pipefail
adb install -r probe-artifacts/probe-app.apk
adb install -r probe-artifacts/probe-test.apk
adb shell am instrument -w -r com.molido.vpn.nativeprobe.test/com.molido.vpn.nativeprobe.NativeProbe | tee probe-runtime.txt
grep -q 'MOLIDO_NATIVE_PROBE_OK:' probe-runtime.txt
if grep -qE 'MOLIDO_NATIVE_PROBE_FAILED|INSTRUMENTATION_FAILED|Process crashed' probe-runtime.txt; then
  exit 1
fi
