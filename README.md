# Android HNS Resolver (Native SPV)

A lightweight, zero-bullshit, native Android Handshake (HNS) DNS resolver app.

Resolves Handshake TLDs natively across all Android apps and browsers without proxies, without paid DNS services, and without routing normal traffic through a VPN tunnel.

---

## Architecture Overview

```
+-------------------------------------------------------------+
|                      Android System                         |
|   (Chrome, Firefox, Apps, curl, getaddrinfo / Bionic libc)  |
+------------------------------+------------------------------+
                               | DNS queries (UDP :53)
                               v
+-------------------------------------------------------------+
|               HnsVpnService (DNS-Only Loopback)             |
|                                                             |
|  * TUN Address: 10.254.1.2/30                               |
|  * DNS Server:  10.254.1.1:53                               |
|  * Route:       10.254.1.1/32 ONLY                          |
|                                                             |
|  (All regular web traffic bypasses VPN at native line-speed)|
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                         DnsRouter                           |
|                                                             |
|  * Fast-path: ICANN TLDs (.com, .org, .net, etc.)           |
|    -> Upstream recursive DNS (Cloudflare 1.1.1.1:53)        |
|                                                             |
|  * Handshake TLDs (.badass, .c, .nb, etc.)                  |
|    -> Query local Handshake SPV resolver (127.0.0.1:5349)   |
|    -> Fallback to upstream if not found                     |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|          HnsEngine Abstraction (Decoupled Engine)           |
|                                                             |
|  interface HnsEngine { start(), stop(), getProgress(), ... }|
+------------------------------+------------------------------+
                               |
            +------------------+------------------+
            |                                     |
            v                                     v
+-----------------------+             +-----------------------+
|   HnsdNativeEngine    |             |    (Future Engine)    |
|   (C / Android NDK)   |             |   Node.js hsd / SPV   |
|                       |             |   Wallet Companion    |
| * hnsd + libuv + secp |             |                       |
| * 1.2 MB shared lib   |             +-----------------------+
| * ~15 MB RAM usage    |
| * SPV header sync     |
+-----------------------+
```

### Why a "Lie" VPN?
Android does not allow non-root applications to bind port 53 or modify system DNS settings reliably without being disabled by Doze mode.

By creating an Android `VpnService` that **only routes `10.254.1.1/32`**, we avoid routing user internet traffic through any tunnel:
- All TCP, UDP, HTTPS, video streaming, and gaming traffic flows directly through Wi-Fi / cellular interfaces at full line speed.
- Only DNS queries destined for `10.254.1.1:53` are intercepted, resolved locally, and returned.

### Engine Decoupling
The core Handshake resolver is isolated behind the `HnsEngine` Kotlin interface:
- **`HnsdNativeEngine`**: Uses the official C light client (`hnsd` / `libhsk`) compiled via Android NDK with Clang. It has a memory footprint under 20MB and synchronizes headers from hardcoded checkpoints.
- If you later decide to plug in Node.js `hsd` in SPV mode (e.g. to build a wallet on top of the resolver), implement `HnsEngine` and swap the implementation. The VPN service, packet loop, UI, and notifications remain untouched.

---

## Features

- **Progressive Header Sync**: Visual sync progress bar (0% - 100%) tracking block header height against the network.
- **Initial Sync Warning**: Recommends keeping the app open during first sync to avoid OS sleep interruption.
- **Foreground Service**: Persistent status notification keeping the resolver alive against aggressive battery killers.
- **In-App Resolver Tester**: Test resolving any Handshake name (e.g. `welcome.nb` or `proofofconcept.badass`) directly inside the UI.
- **Zero Bloatware**: Pure Kotlin, XML layouts, zero heavy UI frameworks, tiny APK size (~13MB debug with 3 ABIs; ~3MB single ABI).

---

## Building the Project

### Requirements
- Android SDK (API 35, Build-tools 35+)
- Android NDK (r26+ or r28)
- Java 17 or 21

### Compile Debug APK
```bash
./gradlew assembleDebug
```
The APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install to Connected Device / Emulator
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
