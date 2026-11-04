# IKEv2 / IPsec support

## Free implementation selected

Molido uses Android's built-in `Ikev2VpnProfile` + `VpnManager` API on Android 11 (API 30)+.
This has no separate SDK/runtime dependency or protocol license fee and uses the platform IPsec implementation. The profile is system-managed; it does not run through Molido's existing Aether/VpnService packet engine.

The independent strongSwan project is also a mature free/open-source IKEv2 implementation under GPLv2. It is a viable alternative if a bundled cross-version engine is needed later, but integrating its Android port into this app is not a drop-in library operation and has GPL compliance implications. We do not copy its APK or embed an unreviewed binary.

## Current implementation

- A separate IKEv2/IPsec screen is available from Settings.
- Uses Android's system IKEv2 implementation; requires Android 11+ and `FEATURE_IPSEC_TUNNELS`.
- Supports EAP-MSCHAPv2 username/password profiles with an optional IKE identity (defaults to the EAP username).
- Uses the platform trust store for server certificate validation (`serverRootCa = null)); hostname/certificate identity matching remains the server operator's responsibility.
- Requests a non-bypassable, metered VPN profile. It does not enable local-route exclusions.
- Android's system consent UI is used. Android may revoke the existing Molido VpnService tunnel when the system IKEv2 tunnel takes over; only one VPN can be active at a time.
- Credentials are not written to Molido preferences, app files, backups or logs. Android retains the provisioned system VPN profile. Disconnect is sent through VpnManager.
- The app reports that connection was requested, not that the remote server authenticated or that internet traffic was verified. Check the Android VPN indicator and perform an IP/DNS/IPv6 leak test after connection.

## Free server availability

A free protocol implementation is not the same as a free VPN server. No free public IKEv2 endpoint has been independently authenticated and validated for availability, certificate identity, privacy policy, IPv4/IPv6 routing and DNS behavior for inclusion as a built-in server.

Third-party sites advertise short-lived free EAP accounts, and some providers offer free plans with IKEv2 access. These require user signup/credential generation and can change or disappear. Molido therefore does not hard-code public credentials or route user traffic through an unverified free operator. Users can enter an account from a provider they trust.

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
