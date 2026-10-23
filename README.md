# QuietShield

A local-first Android DNS filter and accessibility rule runner. The first preview is built with Kotlin and Jetpack Compose (minSdk 26).

## Build

Open this directory in Android Studio with JDK 17, or run `gradle assembleDebug` after installing Android SDK platform 36 and build tools 36. The GitHub Actions workflow builds debug/release APK artifacts. Tag a version as `v*` to publish a signed release after adding repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The signing keystore is decoded into the runner temp directory and is not stored in the repository.

## Use

1. Select apps under **应用** separately for network DNS filtering and accessibility rules.
2. Select VPN or Root on **总览** and start protection. VPN authorization is requested by Android. Root mode uses per-UID iptables DNS redirection and falls back to VPN when root setup fails.
3. Enable the accessibility rule switch and grant the system accessibility permission. The service only examines selected apps and only clicks visible, clickable controls whose text contains an enabled keyword such as Skip/Close.
4. Add local domain or keyword rules, import hosts text, or manually fetch a GKD JSON5 subscription.
5. Configure a repeating protection window under **设置**.

## Current limits

This is an early, reviewable implementation, not a full GKD runtime. GKD imports accept common `apps/groups/rules` objects with a simple text selector and click action; advanced selectors and actions are rejected. Unmatched DNS questions are sent to the active network resolver (with 1.1.1.1 as a fallback). Network filtering handles IPv4 UDP DNS only. HTTPS/DNS-over-HTTPS ads, IPv6 DNS, TCP DNS, same-domain ads, and screen content that does not expose accessible text are not covered. Root mode depends on the device's `su` and iptables owner/NAT support; root DNS interception is experimental. Android may delay scheduled actions while idle. Validate on target devices before relying on it.

All rule data and counters are stored locally. Subscription data is fetched only after a manual user action.



