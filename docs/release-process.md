# Android release process

## Versioning and automatic publishing

- The release workflow runs automatically when a version tag beginning with `v` is pushed (for example, `v4.0.2`).
- The workflow validates strict stable SemVer, requires the tagged commit to already be contained in `main`, and refuses to overwrite an existing tag or GitHub Release.
- Each valid tag automatically runs native-binary checksum verification, strict emergency-config validation, Android release unit tests, signed ARM64/ARMv7/universal APK builds, package/version/signature checks, SHA-256 generation and GitHub Release publication.
- Manual `workflow_dispatch` remains available for controlled recovery. A normal push to `main` still runs unsigned validation only; it does not publish a release.
- Use a new version tag for every release. Never move or reuse a published version tag.

## Mandatory release gates

1. Fetch native binaries and verify their pinned SHA-256 checksums.
2. Syntax-check and run the emergency-config refresh in strict mode. Release is blocked unless valid resilience defaults and at least five unique emergency configs are available.
3. Run Android release unit tests and build ARM64, ARMv7 and universal APKs.
4. Verify all three APKs are non-empty, package ID is `com.mobin.mobin_vpn`, version name/code match the requested release, APK signatures validate, and the signing certificate matches the configured release keystore and published v4.0.0 baseline.
5. Publish the APKs and SHA-256 checksums only after all prior gates pass.

## Required GitHub Actions secrets

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If a secret is missing or invalid, the workflow fails closed and does not publish installable APKs.

## Device acceptance still required

CI cannot inspect the signing certificate of an already-installed user app or prove a successful in-place update on a physical phone. Before calling an update production-verified, test installation over the existing app without uninstalling, confirm settings and saved profiles remain, and exercise connect/disconnect, IPv4/IPv6, DNS, network handover and background recovery. Never tell users to uninstall to work around a signing mismatch.

## Scope honesty

Android source tests and CI builds are not a substitute for authorized real-network tests. iOS remains gated on physical-device, Network Extension entitlement, signing, localization and leak tests. OpenVPN/OpenConnect native engines, Windows/Linux end-user clients and a Bluetooth/Wi-Fi Direct mesh are not production-ready unless their own acceptance gates pass.
