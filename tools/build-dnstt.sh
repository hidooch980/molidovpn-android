#!/usr/bin/env bash
# Builds the dnstt DNS-tunnel client (https://www.bamsoftware.com/software/dnstt/, public domain)
# for the app's ABIs as app/src/main/jniLibs/<abi>/libdnstt.so — an executable, exec()'d from
# nativeLibraryDir exactly like libxray.so / libtor.so (see packaging.jniLibs.useLegacyPackaging).
#
# Source is pinned to one commit and checked; Go module downloads are checked by go.sum.
# Needs Go >= 1.21 and the NDK (cgo: Android's resolver for DoH/DoT hostnames).
# Optional: without this file the app simply reports the DNS tunnel as "not available in this build".
#
#   ANDROID_NDK_HOME=... bash tools/build-dnstt.sh [--abi arm64-v8a,armeabi-v7a]
set -euo pipefail

DNSTT_REPO="https://www.bamsoftware.com/git/dnstt.git"
DNSTT_COMMIT="0c5c52a57d899c05428c116898941761a2ed83c2"  # v1.20260501.0 (CHANGELOG update)
ABIS="arm64-v8a,armeabi-v7a"
[ "${1:-}" = "--abi" ] && ABIS="$2"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/26.3.11579264}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$TC/clang" ] || { echo "NDK not found at $NDK (set ANDROID_NDK_HOME)"; exit 1; }
command -v go >/dev/null || { echo "go not found"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone -q "$DNSTT_REPO" "$WORK/dnstt"
git -C "$WORK/dnstt" checkout -q "$DNSTT_COMMIT"
[ "$(git -C "$WORK/dnstt" rev-parse HEAD)" = "$DNSTT_COMMIT" ] || { echo "dnstt commit mismatch"; exit 1; }

IFS=',' read -ra LIST <<< "$ABIS"
for abi in "${LIST[@]}"; do
  case "$abi" in
    arm64-v8a)   GOARCH=arm64; GOARM=;  CC="$TC/aarch64-linux-android26-clang" ;;
    armeabi-v7a) GOARCH=arm;   GOARM=7; CC="$TC/armv7a-linux-androideabi26-clang" ;;
    x86_64)      GOARCH=amd64; GOARM=;  CC="$TC/x86_64-linux-android26-clang" ;;
    *) echo "skip unknown ABI $abi"; continue ;;
  esac
  out="$ROOT/app/src/main/jniLibs/$abi/libdnstt.so"
  mkdir -p "$(dirname "$out")"
  (cd "$WORK/dnstt/dnstt-client" && \
    GOFLAGS=-mod=readonly CGO_ENABLED=1 GOOS=android GOARCH=$GOARCH GOARM=$GOARM CC="$CC" \
    go build -trimpath -buildvcs=false -ldflags='-s -w' -o "$out" .)
  echo "built $out ($(stat -c %s "$out") bytes) sha256=$(sha256sum "$out" | cut -d' ' -f1)"
done
