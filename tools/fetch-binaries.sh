#!/usr/bin/env bash
# Downloads pinned Psiphon, Tor, lyrebird and Xray assets from our binaries release.
# Every asset is checked against BOTH the release's SHA256SUMS.txt and GitHub's
# immutable release-asset digest. If the normal download URL is unavailable or
# serves bad bytes, retry through the GitHub Releases API asset endpoint.
# Never fall back to an unpinned upstream branch.
set -euo pipefail

TAG="${BINARIES_TAG:-binaries-7}"
REPO="hidooch980/molidovpn-android"
OURS="https://github.com/$REPO/releases/download/$TAG"
API="https://api.github.com/repos/$REPO"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SINGBOX_VERSION="1.12.25"
SINGBOX_API="https://api.github.com/repos/SagerNet/sing-box"
GITHUB_API_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"

curl_retry() {
  curl --fail --location --silent --show-error \
    --connect-timeout 20 --max-time 240 \
    --retry 5 --retry-all-errors --retry-delay 2 "$@"
}

# Obtain the immutable asset IDs and digests from GitHub's release metadata.
curl_retry -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "$API/releases/tags/$TAG" -o "$WORK/release.json"

asset_id() {
  jq -er --arg name "$1" '.assets[] | select(.name == $name) | .id' "$WORK/release.json"
}

asset_digest() {
  jq -er --arg name "$1" '.assets[] | select(.name == $name) | .digest | sub("^sha256:"; "")' "$WORK/release.json"
}

download_asset() { # asset name, output path, expected digest from GitHub API
  local name="$1" output="$2" expected="$3" id tmp
  id="$(asset_id "$name")"
  [[ "$expected" =~ ^[a-fA-F0-9]{64}$ ]] || {
    echo "Missing or invalid GitHub SHA-256 digest for $name" >&2
    return 1
  }
  tmp="$WORK/download.tmp"
  rm -f "$tmp"

  # Primary: conventional release download URL.
  if curl_retry "$OURS/$name" -o "$tmp" &&
     [[ "$(sha256sum "$tmp" | awk '{print $1}')" == "$expected" ]]; then
    mv "$tmp" "$output"
    return 0
  fi
  echo "Primary release URL unavailable or checksum mismatch for $name; trying GitHub asset API" >&2
  rm -f "$tmp"

  # Secondary: API asset endpoint. It is the same immutable release asset,
  # not a moving upstream branch or an unverified mirror.
  local auth_args=()
  if [[ -n "${GH_TOKEN:-}" ]]; then
    auth_args=(-H "Authorization: Bearer $GH_TOKEN")
  fi
  if curl_retry "${auth_args[@]}" -H "Accept: application/octet-stream" \
       -H "X-GitHub-Api-Version: 2022-11-28" \
       "$API/releases/assets/$id" -o "$tmp" &&
     [[ "$(sha256sum "$tmp" | awk '{print $1}')" == "$expected" ]]; then
    mv "$tmp" "$output"
    return 0
  fi

  echo "Unable to obtain verified release asset: $name" >&2
  rm -f "$tmp"
  return 1
}

# The manifest itself is pinned by GitHub's release-asset digest.
MANIFEST_DIGEST="$(asset_digest SHA256SUMS.txt)"
download_asset SHA256SUMS.txt "$WORK/SHA256SUMS.txt" "$MANIFEST_DIGEST"

get() { # asset name
  local name="$1" api_hash manifest_hash
  api_hash="$(asset_digest "$name")"
  manifest_hash="$(awk -v n="$name" '$2 == n || $2 == "*" n {print $1; exit}' "$WORK/SHA256SUMS.txt")"
  [[ -n "$manifest_hash" ]] || {
    echo "No checksum entry for $name in pinned SHA256SUMS.txt" >&2
    return 1
  }
  [[ "$manifest_hash" == "$api_hash" ]] || {
    echo "GitHub asset digest and SHA256SUMS.txt disagree for $name" >&2
    return 1
  }
  download_asset "$name" "$WORK/$name" "$manifest_hash"
}

get psiphontunnel-2.0.42.aar
for abi in arm64-v8a armeabi-v7a; do
  for lib in libtor.so libobfs4proxy.so libxray.so; do
    get "${abi}__${lib}"
  done
done

# Independently verify the complete pinned manifest after downloads.
(cd "$WORK" && sed 's/ \*/  /' SHA256SUMS.txt | sha256sum -c -)

singbox_arch() { # ABI -> release architecture
  case "$1" in
    arm64-v8a) echo arm64 ;;
    armeabi-v7a) echo arm ;;
    *) return 1 ;;
  esac
}

singbox_sha() { # ABI -> SHA-256 of the pinned archive
  case "$1" in
    arm64-v8a) echo b9acee7ba78ccb761ba87e5caf6c6db8eaa64bcb746fcef2f5616089efaf4e19 ;;
    armeabi-v7a) echo 8a49c92f05d26d44879072cdbeddab95e54e1b9764911301018a81f34d197ebe ;;
  esac
}

# Resolve immutable release asset IDs from the pinned upstream tag, then
# download through the authenticated GitHub API (avoids intermittent 403s
# from browser/CDN release URLs). The digest remains pinned below.
curl_retry -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  -H "Authorization: Bearer $GITHUB_API_TOKEN" \
  "${SINGBOX_API}/releases/tags/v${SINGBOX_VERSION}" -o "$WORK/singbox-release.json"

for abi in arm64-v8a armeabi-v7a; do
  name="sing-box-$SINGBOX_VERSION-android-$(singbox_arch "$abi")"
  expected="$(singbox_sha "$abi")"
  id="$(jq -er --arg name "$name.tar.gz" '.assets[] | select(.name == $name) | .id' "$WORK/singbox-release.json")"
  upstream_digest="$(jq -er --arg name "$name.tar.gz" '.assets[] | select(.name == $name) | .digest | sub("^sha256:"; "")' "$WORK/singbox-release.json")"
  [[ "$upstream_digest" == "$expected" ]] || {
    echo "Pinned sing-box release digest mismatch for $name" >&2
    exit 1
  }
  curl_retry -H "Accept: application/octet-stream" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Authorization: Bearer $GITHUB_API_TOKEN" \
    "$SINGBOX_API/releases/assets/$id" -o "$WORK/$name.tar.gz"
  (cd "$WORK" && echo "$expected  $name.tar.gz" | sha256sum -c -)
  tar -xzf "$WORK/$name.tar.gz" -C "$WORK"
done

mkdir -p "$ROOT/app/libs"
cp "$WORK/psiphontunnel-2.0.42.aar" "$ROOT/app/libs/"
for abi in arm64-v8a armeabi-v7a; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  for lib in libtor.so libobfs4proxy.so libxray.so; do
    cp "$WORK/${abi}__${lib}" "$ROOT/app/src/main/jniLibs/$abi/$lib"
  done
  cp "$WORK/sing-box-$SINGBOX_VERSION-android-$(singbox_arch "$abi")/sing-box" \
    "$ROOT/app/src/main/jniLibs/$abi/libsingbox.so"
done
echo "binaries ready (GitHub release digests and SHA256SUMS verified)"
