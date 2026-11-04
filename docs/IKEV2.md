# IKEv2 / IPsec

Molido's Android IKEv2 path uses the platform `Ikev2VpnProfile` and `VpnManager` APIs (Android 11 / API 30+). It is a system-managed VPN profile, separate from the app's VpnService tunnel; Android permits only one active VPN at a time.

## Free community directory

The in-app directory reads the public VPN Jantit IKEv2 country index and the linked country pages over HTTPS. It follows only links on `www.vpnjantit.com`, extracts public hostnames and the matching account-creation URL, and caches only non-secret metadata (country, hostname, IKE identity, provider URL and the page's availability label).

- Manual refresh is available in the IKEv2 screen.
- While that screen is open, the directory is refreshed every 5 minutes.
- A network-constrained Android JobScheduler task refreshes the cache periodically (Android's minimum periodic job interval is 15 minutes); it can be delayed by Doze, battery policy, connectivity or provider rate limits. This is not a push feed or second-by-second live status.
- The last successful cache remains visible if the provider cannot be reached.
- Provider availability text is informational and is not an IKE negotiation, authentication, latency, throughput, DNS, IPv4/IPv6 or leak test.

VPN Jantit states that its free IKEv2 accounts are temporary (up to 7 days) and are reset daily. Users must create their own account on the provider site. Molido does not create accounts, store EAP usernames/passwords, or send credentials to the directory service. The provider's published privacy notice says it may collect information users submit, including usernames and passwords. The user must review the provider's current terms and privacy notice before registration; Molido does not audit or endorse this third-party provider.

## Certificate and credentials

For VPN Jantit community endpoints, the app requires the user to obtain the CA certificate from the provider's official account page and import it explicitly before connecting. The app checks that the selected X.509 certificate is a CA certificate, but that structural check does not prove that the certificate is authentic. Never import a certificate from an untrusted mirror or chat.

EAP username/password are entered only for the Android system profile provisioning call. They are not included in the directory cache, app preferences, backup, telemetry or logs. Android manages the provisioned system VPN profile and may retain its credentials as part of that profile. The provider's own registration and privacy policies apply to the account data entered on its website.

## Verification limits

Unit tests cover server input validation and parsing/trust-boundaries for directory entries. CI compilation and unit tests do not establish that a provider server accepts an account or certificate. Before claiming IKEv2 works end-to-end, test on physical Android 11+ devices with a newly created account, official CA certificate, IPv4 and IPv6, DNS, UDP, disconnect/reconnect and network handover. Do not label public community endpoints as secure or leak-free without those tests.
