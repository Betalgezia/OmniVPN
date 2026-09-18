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

- `Libbox.setup` during application initialization.
- `Libbox.checkConfig` before engine startup.
- `Libbox.newService(config, platform)` lifecycle wrapper.
- Android `VpnService` foreground service.
- `PlatformInterface.openTun()` backed by `VpnService.Builder.establish()`.
- `PlatformInterface.autoDetectInterfaceControl()` using `VpnService.protect()`.
- Atomic TUN `ParcelFileDescriptor` ownership and cleanup on stop/revoke/destroy.
- `StateFlow` + typed VPN events.
- Network-change detection with exponential recovery starting at 5 seconds and capped at 60 seconds.

Still to implement:

- Validated sing-box configuration builder for VLESS/Trojan/Hysteria2/AmneziaWG.
- Cloudflare WARP bootstrap.
- Subscription parsers (Mihomo/sing-box YAML/JSON and Base64 V2Ray formats).
- DNS detour/fake-IP configuration.
- UI and VPN permission flow.
- Instrumentation and parser/engine tests.

The core bridge is intentionally kept separate from the future config builder so invalid or unsupported node fields are never blindly forwarded.
