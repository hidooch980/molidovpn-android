# OpenVPN native foundation

This experimental branch builds the official OpenVPN 3 core and a JNI profile
inspection bridge. It does not yet connect a VPN, configure Android TUN, or
publish a production release. Native libraries are opt-in; ordinary releases are unchanged.

All source revisions are fixed in deps.lock.json. build.py verifies each Git
checkout, cross-builds OpenSSL, builds the core, and checks JNI exports, external
shared-library dependencies and 16 KB ELF load alignment for ARM64 and ARMv7.
The resulting artifact includes dependency pins, upstream license files and ELF
inspection output. A passing cross-build is not an Android runtime test.

The Java entry point rejects files that fail VpnProfilePreflight before entering
the native parser, and distinguishes an unavailable library from a valid profile.
Native failures return generic numeric flags; upstream messages and profile
contents are never returned to the UI or logged by the bridge.

After both cross-builds pass, remaining work includes Android runtime JNI tests,
authenticated TCP/UDP sessions, TUN/socket-protection callbacks, routes/DNS/IPv6,
cancel/reconnect and network handover. Only then can MethodRegistry advertise the
method. Server reachability must be measured on the user's current phone network.

Upstreams:
- https://github.com/OpenVPN/openvpn3 (license: see its pinned LICENSE.md)
- https://github.com/openssl/openssl
- https://github.com/chriskohlhoff/asio
- https://github.com/lz4/lz4
- https://github.com/fmtlib/fmt

## Android runtime and packaging gates

The native workflow now also cross-builds x86_64 **for emulator testing only**.
A separate, minimal probe APK packages the actual production OpenVpnCore and
VpnProfilePreflight Java sources plus the JNI library. It has no INTERNET
permission, no launcher and no VPN service. Its instrumentation verifies native
loading, the pinned revision, repeated parsing of a generated CA/auth profile,
and rejection of an oversized profile that passes Java preflight. The metadata
parser does not validate a certificate chain or authenticate a server. This is a parser
runtime test, not an authenticated OpenVPN connection or ARM phone runtime test.

ARM libraries can be included explicitly with
`-PopenVpnNativeDir=/absolute/path/to/native/openvpn/dist`.
This opt-in uses a separate `com.mobin.mobin_vpn.openvpnlab` package, debug signing,
and an inspection-only version label so it cannot replace the production app.
The workflow verifies both ARM ABIs in the lab APKs and retains upstream license
files alongside the artifacts. Do not install this build as a production update.

A green cross-build alone is insufficient: inspect the android-runtime and
experimental-app jobs separately. OpenVPN remains absent from the selectable
connection methods until authenticated TUN/socket-protection and routing tests pass.
