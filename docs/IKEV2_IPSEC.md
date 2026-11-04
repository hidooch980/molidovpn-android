# IKEv2 / IPsec support

## Free implementation selected

Molido uses Android's built-in `Ikev2VpnProfile` + `VpnManager` API on Android 11 (API 30)+.
This has no separate SDK/runtime dependency or protocol license fee and uses the platform IPsec implementation. The profile is system-managed; it does not run through Molido's existing Aether/VpnService packet engine.

The independent strongSwan project is also a mature free/open-source IKEv2 implementation under GPLv2. It is a viable alternative if a bundled cross-version engine is needed later, but integrating its Android port into this app is not a drop-in library operation and has GPL compliance implications. We do not copy its APK or embed an unreviewed binary.

## Current implementation

- A separate IKEv2/IPsec screen is available from Settings.
- Uses Android's system IKEv2 implementation; requires Android 11+ and `FEATURE_IPSEC_TUNNELS`.
- Supports EAP-MSCHAPv2 username/password profiles with a server identity (Remote ID, defaults to the server hostname).
- Imports a user-selected PEM/DER CA certificate for the current session only (max 256 KiB, X.509 CA constraint required); otherwise uses the Android system trust store.
- Adds an external link to VPN Jantit's temporary free Turkey IKEv2 account generator. The user creates their own username/password, obtains the current server hostname and downloads its CA certificate; no public credentials or server IP are embedded.
- Uses the platform trust store for server certificate validation (`serverRootCa = null)); hostname/certificate identity matching remains the server operator's responsibility.
- Requests a non-bypassable, metered VPN profile. It does not enable local-route exclusions.
- Android's system consent UI is used. Android may revoke the existing Molido VpnService tunnel when the system IKEv2 tunnel takes over; only one VPN can be active at a time.
- Credentials are not written to Molido preferences, app files, backups or logs. Android retains the provisioned system VPN profile. Disconnect is sent through VpnManager.
- The basic Android profile API does not expose a custom IKE port; servers requiring a non-standard port (for example, some provider-specific port-443 profiles) are not compatible with this screen. The app reports that connection was requested, not that the remote server authenticated or that internet traffic was verified. Check the Android VPN indicator and perform an IP/DNS/IPv6 leak test after connection.

## Free server availability

A free protocol implementation is not the same as a free VPN server. VPN Jantit advertises temporary free IKEv2 EAP-MSCHAPv2 accounts, typically for up to seven days, and provides a CA certificate download. Molido links to its Turkey account-generation page. This is an external, third-party provider and has not been independently audited or end-to-end tested by Molido. Its live server list, account duration, speed, limits, certificate and privacy policy can change. Import the provider CA only if you explicitly trust that operator.

hide.me also advertises a free plan with IKEv2, but its documented third-party Android/strongSwan setup specifies server identity `hide.me` and port 443. The basic Android `VpnManager` profile API used here does not expose a custom IKE port, so that manual profile is not compatible with this implementation. hide.me's own app supports its free IKEv2 service; use its official app until a custom-port IKE engine is integrated.

Molido does not hard-code public credentials or server IPs. No free provider has been independently validated for privacy, IPv4/IPv6 routing, DNS behavior, stability or suitability on Iranian networks.

## Not yet implemented / test gates

- PSK and certificate-based authentication UI; current screen is EAP-MSCHAPv2 only.
- Importing a private CA or client certificate.
- Full status/error callbacks and Smart Connect integration.
- Physical-device tests on Android 11+, including Samsung/Pixel OEM variants, network handover, server certificate rejection, authentication failure, IPv4, IPv6, DNS, kill-switch behavior and disconnect/reconnect.
- The native Android API is not available on Android 10 and older. Those versions need a separately licensed/compliance-reviewed strongSwan integration; the current screen refuses to connect there.

## References

- Android Ikev2VpnProfile: https://developer.android.com/reference/android/net/Ikev2VpnProfile
- Android Ikev2VpnProfile.Builder: https://developer.android.com/reference/android/net/Ikev2VpnProfile.Builder
- Android VpnManager: https://developer.android.com/reference/android/net/VpnManager
- strongSwan Android client docs: https://docs.strongswan.org/docs/latest/os/androidVpnClient.html
- strongSwan source/license: https://www.strongswan.org/
- VPN Jantit temporary free IKEv2 account generation: https://www.vpnjantit.com/create-free-account?server=tr6&type=IKEV2
- hide.me free IKEv2/Android protocol support: https://hide.me/en/blog/the-best-free-vpn-for-android/
- hide.me manual strongSwan profile (server identity and port requirements): https://hide.me/en/help/ikev2-vpn-setup-via-strongswan-app-for-android/
