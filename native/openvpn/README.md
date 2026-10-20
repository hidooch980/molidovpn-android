# OpenVPN native foundation

This experimental branch builds the official OpenVPN 3 core and a JNI profile
inspection bridge. It does not yet connect a VPN, configure Android TUN, or
publish an APK. The generated .so is not automatically copied into app/jniLibs.

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
