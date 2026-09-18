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
- Sing-box configuration builder for VLESS/Trojan/Hysteria2.
- AmneziaWG emitted as a sing-box `wireguard` endpoint with sanitized peer fields and AWG2 parameters.
- DNS local + DoH + FakeIP baseline.
- `VpnController.start(node)` wiring node validation/config generation into the VPN service.
- URI subscription parser for VLESS/Trojan/Hysteria2, including standard Base64-wrapped URI lists.
- Cloudflare WARP bootstrap client via `/reg`, optional WARP+ license application and AndroidKeyStore-backed encrypted cache.

Still to implement:

- Mihomo/sing-box YAML/JSON subscription parsing and broader Base64/V2Ray compatibility.
- Room-backed `NodeRepository` and import service.
- Rich structured TLS/transport model and URI parser coverage.
- UI and VPN permission flow.
- Instrumentation and parser/engine tests.
- Actual device verification against the pinned AAR.

The core bridge and config builder are intentionally separated: invalid or unsupported node fields are not blindly forwarded into the running sing-box document.
