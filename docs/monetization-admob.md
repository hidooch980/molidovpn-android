# MolidoVPN AdMob Monetization

## Status

This document defines the safe rollout contract. It does not claim that ads are live or that revenue is being earned. Production serving remains disabled until the publisher registers the app in AdMob, supplies the real App ID and ad-unit IDs, publishes the required privacy messages, and the integration passes review and device tests.

## Revenue model

Use Google AdMob as the initial demand source. Revenue is variable and depends on eligible impressions, geography, fill rate, format, advertiser demand, and invalid-traffic filtering. Never promise a fixed amount or encourage users to click ads.

## Approved placements

- One adaptive banner in a non-critical area of the mobile home/settings experience, with reserved layout space to avoid shifting controls.
- Optional rewarded ad only for a clearly disclosed, user-initiated, non-security convenience benefit. Reward completion must be verified by the SDK callback; no reward for clicks.
- No interstitial, app-open, or rewarded ad while connecting, connected, reconnecting, disconnecting, handling a VPN permission dialog, or displaying a tunnel failure.
- No ad overlay may obscure the connect/disconnect control, system VPN consent, emergency controls, or status information.
- Android TV should default to ads off until a TV-specific layout, remote-focus behavior, and policy review are completed.

## Privacy and EU/EEA

- Integrate Google's User Messaging Platform (UMP) and request consent information at each app launch.
- Request ads only when UMP reports `canRequestAds()`.
- Provide a visible privacy-options entry point whenever UMP says it is required, so consent can be changed later.
- Configure a Google-certified CMP message and publish a privacy policy that accurately describes ad processing and the VPN app's data practices.
- Do not send VPN traffic, destinations, DNS queries, IP addresses, credentials, imported profiles, or connection history to ad systems or analytics.
- Do not initialize/request ads before the required consent flow is complete.
- Obtain legal/privacy review for the actual jurisdictions and data practices before production rollout.

## Build and release configuration

Keep publisher identifiers out of source control where practical. Supply these at build time:
- `ADMOB_APP_ID`: AdMob application ID (the manifest value uses the `ca-app-pub-...~...` format).
- `ADMOB_HOME_BANNER_ID`: production banner ad-unit ID.
- `ADMOB_REWARDED_ID`: production rewarded ad-unit ID, if enabled.

Debug builds must use Google's official test app/ad-unit IDs only. Release builds must fail closed (no ad request) when production IDs are absent or still set to test IDs. Never use live ads for development or click your own ads.

## Rollout gates

1. Register the Android app in AdMob with package name `com.mobin.mobin_vpn`.
2. Create banner and (optionally) rewarded ad units.
3. Configure and publish GDPR/EEA/UK/Swiss consent messages in AdMob Privacy & messaging and add the privacy-options entry point.
4. Add the real identifiers through protected build configuration; do not commit account credentials or signing keys.
5. Integrate Mobile Ads SDK + UMP, with consent-before-ads sequencing and a hard VPN-state placement gate.
6. Test using Google's test ads, including EEA consent, denied consent, offline/no-fill, app background/foreground, active tunnel, reconnect, and Android TV.
7. Verify the Play Data safety form and privacy policy match actual SDK behavior.
8. Release only after CI, policy review, and signing-continuity checks pass; then verify impressions and finalized earnings in AdMob.

## Official references

- AdMob Android quick start: https://developers.google.com/admob/android/quick-start
- UMP Android privacy setup: https://developers.google.com/admob/android/privacy
- EEA/UK/Switzerland consent policy: https://developers.google.com/admob/android/privacy/gdpr
- AdMob policies: https://support.google.com/admob/answer/6128543
