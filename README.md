# OmniVPN

Universal Android VPN client based on **sing-box-lx**.

## Protocols

- VLESS
- Trojan
- Hysteria2
- AmneziaWG 2.0
- Cloudflare WARP bootstrap

Ordinary WireGuard is intentionally not exposed as a separate protocol in the OmniVPN domain model.

## Stack

- Kotlin
- Jetpack Compose
- Kotlin Coroutines / Flow
- Hilt
- Room
- Android VpnService
- sing-box-lx / libbox

## Core

Pinned target: **sing-box-lx v1.14.1-lx.3**.

AAR SHA-256:

`bc9d313b040931323a5754500e62f5cbe8f5cb09503c44118eef3d0c8cbe83fd`

The AAR is intentionally not committed to Git. Run the platform-specific fetch script before the first Gradle build:

- Linux/macOS: `./scripts/fetch-libbox.sh`
- Windows PowerShell: `./scripts/fetch-libbox.ps1`

## Core integration status

Implemented:

- `Libbox.setup`, `Libbox.checkConfig` and `Libbox.newCommandServer(handler, platform)`.
- `CommandServer.start()`, `startOrReloadService(config, OverrideOptions())` and `close()`.
- Android `VpnService` foreground lifecycle with atomic TUN ownership.
- `VpnService.protect()` for sing-box egress sockets.
- Typed VPN `StateFlow`/event bus plus guarded start/stop lifecycle.
- Network-change detection and exponential recovery starting at 5 seconds, capped at 60 seconds.
- VLESS/Trojan/Hysteria2 configuration generation.
- AmneziaWG 2.0 as a sing-box `wireguard` endpoint with peer sanitization.
- Local DNS + DoH + FakeIP with sing-box 1.14 reverse mapping/resolve routing.
- VLESS/Trojan/Hysteria2 URI import and standard Base64 URI lists.
- Mihomo/sing-box JSON/YAML import for VLESS, Trojan, Hysteria2 and WireGuard.
- WireGuard/AmneziaWG `.conf` import with multiple peers and AWG2 parameters.
- HTTPS-only subscription fetching with bounded redirects/body size and per-source Room replacement.
- Room-backed node/subscription persistence.
- Cloudflare WARP bootstrap, optional WARP+ license application and encrypted AndroidKeyStore cache.
- Minimal functional Compose UI and VPN permission flow.
- Gradle Wrapper 9.3.1 committed to the repository.
- GitHub Actions build/test workflow plus Gradle Wrapper integrity validation.

## In-app updates

The app updates itself from this repository's GitHub Releases:

- at launch, and again on returning after a long pause (at most once every 6 hours), it asks `releases/latest`;
- when the release is newer, a card appears on «Главная» and «О приложении» gets a check row;
- «Обновить» downloads the APK for the phone's ABI (`OmniVPN-<version>-arm64.apk` or `-armv7.apk`), verifies the SHA-256 published by GitHub and hands the file to Android's installer;
- Android asks once to allow OmniVPN to install apps; after that an update is one button;
- Android accepts an install only over the same signing key, so every release is signed with the release key.

To publish an update, push a tag such as `v1.3.0`. `.github/workflows/release.yml` runs the unit tests, builds signed APKs and attaches them to that tag's release. `versionCode` is the commit count, so a newer release always installs over an older one. The first build that contains the updater has to be installed manually once.

## DNS

Settings → «DNS-сервер» picks the resolver AmneziaWG/WARP use: named DoH presets (Cloudflare, Google, Quad9) or a group of geo-unblock/AI resolvers, plus a custom DoH URL or plain DNS IP. The choice is stored locally and applied on the next connection; DoH queries, including the bootstrap lookup of the DoH hostname, go through the tunnel. Proxy protocols (VLESS/Trojan/Hysteria2/…) are unaffected: the proxy server resolves their destination domains itself.

## Analytics

Yandex AppMetrica (`io.appmetrica.analytics:analytics`, initialized once in `OmniVpnApplication`) provides first launches, active users, sessions and retention automatically. The app additionally reports `app_launch`, `vpn_connect_attempt`, `vpn_connected`, `vpn_connect_error`, `vpn_disconnected`, `warp_connected` and `amneziawg_connected`.

Event parameters are limited to `protocol`, `phase` and `reason`. No node names or addresses, subscription links, configuration contents, keys, DNS queries or VPN traffic are sent. Location, advertising/App Set IDs, billing and ad-revenue modules are excluded, crash/ANR interception is disabled, and Google Play Install Referrer is excluded, so analytics has no Google Play dependency. AppMetrica queues events while offline and uploads them when connectivity returns.

## Command-line build

The repository is self-contained except for the pinned `libbox.aar`, which is fetched and SHA-256 verified before the build. Active VPN configuration is encrypted at rest with Android Keystore.

### Windows PowerShell

```powershell
.\scripts\fetch-libbox.ps1
.\gradlew.bat testDebugUnitTest assembleDebug
```

### Linux/macOS

```bash
./scripts/fetch-libbox.sh
./gradlew testDebugUnitTest assembleDebug
```

To only verify the wrapper:

```text
gradlew --version
```

The project requires the Android SDK with API 36 installed and a JDK compatible with the checked-in Gradle/Android Gradle Plugin toolchain.

### Windows troubleshooting

In `local.properties`, use a direct-slash SDK path, for example `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`. Avoid backslashes in `sdk.dir`.

Before running `gradlew.bat`, make sure `JAVA_HOME` points to the JDK you intend Gradle to use, then open a new PowerShell window. Verify with:

```powershell
$env:JAVA_HOME
java -version
```

For the current Android build, the Kotlin/Java bytecode target is Java 17.

GitHub Actions runs on pushes to `main`, pull requests targeting `main`, and manual `workflow_dispatch`. Commits made by automation with GitHub's `GITHUB_TOKEN` do not normally create another workflow run, so for a deterministic CI check use **Run workflow** or push from a human-authenticated account. citeturn420831search0


## Remaining verification

A local Windows build was successfully confirmed before the latest runtime/parser hardening with Gradle 9.3.0, AGP 8.13.0 and JDK 25.

The latest commits add additional Kotlin, parser, Room, notification and runtime changes. They have been statically audited, but they have not yet been executed by a fresh local Gradle build or on a physical Android device in this workspace. The repository CI workflows are present, but no completed run for the latest push is surfaced by the available GitHub integration.

The next validation target is a real device/emulator run covering VPN permission, TUN establishment, VLESS/Trojan/Hysteria2, AmneziaWG, WARP bootstrap, DNS/FakeIP and network handover/recovery.

The core bridge and config builder are intentionally separated: invalid or unsupported node fields are not blindly forwarded into the running sing-box document.
