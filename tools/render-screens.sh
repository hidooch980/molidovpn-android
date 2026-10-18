#!/usr/bin/env bash
# Renders the real screens to PNG on the JVM (Robolectric native graphics) — no emulator/KVM.
# Usage: tools/render-screens.sh [out-dir]   (default: build/screens)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-$PWD/build/screens}"
JNI=app/src/test/jniLibs
mkdir -p "$JNI"
INC="${JAVA_HOME:?JAVA_HOME}/include"
gcc -shared -fPIC -I"$INC" -I"$INC/linux" -o "$JNI/libaether_jni.so" tools/screens/jni-stub.c
cp "$JNI/libaether_jni.so" "$JNI/libaether.so"
trap 'rm -rf "$JNI"' EXIT
MOLIDO_SCREENS="$OUT" bash ./gradlew testDebugUnitTest --tests 'com.molido.vpn.ScreenshotTest' --rerun -q
echo "screens in $OUT"
