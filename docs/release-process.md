# Android release process

## Versioning

- The first 4.x release is `4.0.0`.
- The release workflow is manual-only. A normal push to `main` must never overwrite an existing release tag.
- To publish a later release, run **Build Molido Mesh AI Android** with an unused stable version such as `4.0.1`.
- The workflow rejects an existing `vX.Y.Z` tag and keeps Android `versionCode` monotonic using the Actions run number plus 400000.

## Mandatory release gates

1. Fetch native binaries and verify their pinned SHA-256 checksums.
2. Syntax-check and run the emergency-config refresh in strict mode. Release is blocked unless valid resilience defaults and at least five unique emergency configs are available.
3. Run Android release unit tests and build ARM64, ARMv7 and universal APKs.
4. Verify all three APKs are non-empty, package ID is `com.mobin.mobin_vpn`, version name/code match the requested release, APK signatures validate, and the signing certificate matches the configured release keystore.
5. Publish the APKs and SHA-256 checksums only after all prior gates pass.

## Device acceptance still required

CI cannot inspect the signing certificate of an already-installed user app or prove a successful in-place update on a physical phone. Before calling an update production-verified, test installation over the existing app without uninstalling, confirm settings and saved profiles remain, and exercise connect/disconnect, IPv4/IPv6, DNS, network handover and background recovery. Never tell users to uninstall to work around a signing mismatch.

## Scope honesty

Android source tests and CI builds are not a substitute for authorized real-network tests. iOS remains gated on physical-device, Network Extension entitlement, signing, localization and leak tests. OpenVPN/OpenConnect native engines, Windows/Linux end-user clients and a Bluetooth/Wi-Fi Direct mesh are not production-ready unless their own acceptance gates pass.
