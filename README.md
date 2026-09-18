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

- `Libbox.setup`, `Libbox.checkConfig` and `Libbox.newService(config, platform)`.
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
- Gradle Wrapper 9.3.0 committed to the repository.
- GitHub Actions build/test workflow plus Gradle Wrapper integrity validation.

## Command-line build

The repository is self-contained except for the pinned `libbox.aar`, which is fetched and SHA-256 verified before the build.

### Windows PowerShell

```powershell
.scriptsetch-libbox.ps1
.gradlew.bat testDebugUnitTest assembleDebug
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

## Verification status

The project has been successfully built locally on Windows with Gradle 9.3.0, AGP 8.13.0 and JDK 25. The additional changes after that local build have not yet been executed in a local build or on a physical Android device, and GitHub Actions has not returned a completed run for them yet.

The core bridge and config builder are intentionally separated: invalid or unsupported node fields are not blindly forwarded into the running sing-box document.
