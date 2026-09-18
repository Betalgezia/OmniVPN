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

Pinned target: **sing-box-lx v1.14.1-lx.3**. Its Android AAR includes AmneziaWG support.

AAR SHA-256:

`bc9d313b040931323a5754500e62f5cbe8f5cb09503c44118eef4d3c0c8be83fd`

## Current status

Foundation committed:

- Android application skeleton
- Compose entry point
- Hilt application
- Room database and DAOs
- VPN domain models
- VPN event bus

Next: wire the exact libbox AAR into the Android `VpnService.PlatformInterface.OpenTun()` bridge, then add the validated sing-box configuration builder and VPN controller.
