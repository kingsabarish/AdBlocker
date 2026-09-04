# AdBlocker

A **no-root, system-wide ad blocker for Android**, built in native Kotlin with Jetpack Compose.

## What it does

AdBlocker runs a **local DNS-only VPN** (`VpnService`) that filters DNS for *every app*
on the device — no root required. It blocks common ads and trackers by intercepting
DNS lookups and returning `0.0.0.0` for known ad/tracking domains, while forwarding
everything else to a real upstream resolver. All filtering happens on-device; no
traffic is sent to a remote server except blocklist downloads and the upstream DNS
query itself.

> Status: **functional alpha.** The VPN, blocklist engine, per-app filtering, and
> Quick Settings tile are implemented and tested on a physical OnePlus device.

## Highlights

- No root, no backend, fully on-device.
- System-wide blocking via a split-tunnel DNS VPN (near-zero perf/battery impact).
- 4 built-in blocklists: StevenBlack, Hagezi Multi PRO, AdAway, Peter Lowe's.
- Disk-backed blocklist matcher (`DiskMatcher`) — memory-mapped binary search,
  no JVM heap for domain storage (OOM-safe on 256 MB devices).
- 3-tab Material 3 UI: **Home** (power button), **Apps** (per-app toggle),
  **Blocklists** (enable/disable lists).
- Quick Settings tile to toggle from the notification shade.
- Per-app bypass via `addDisallowedApplication()`.
- Boot receiver to auto-restart.

## Architecture

- **DNS-only split tunnel:** `addRoute("10.10.10.2", 32)` + `addDnsServer("10.10.10.2")`
  routes only DNS traffic through the TUN.
- **DiskMatcher:** Domains are sorted, deduplicated, and written to a binary file
  (`blocklist.bin`), then memory-mapped via `MappedByteBuffer`. Binary search on
  the mmap'd buffer — domain data lives in page cache, not JVM heap.
- **Two-phase blocklist load:** (1) download URLs to disk files, (2) parse from
  disk into a sorted binary file. OOM-safe with per-step `try/catch`.
- **Quick Settings tile:** Uses a transparent `VpnToggleActivity` (launched via
  `PendingIntent`) for the foreground context needed on Android 12+. Tile state
  is read from a marker file (`vpn_active`) in the app's `filesDir`.

## Repository layout

- `app/` — the native Android app (Kotlin, Jetpack Compose). Single-module Gradle
  project (the `:app` module).
- Root holds shared files: `AGENTS.md`, `README.md`, `.gitignore`.

## Development

See [`AGENTS.md`](./AGENTS.md) for architecture, tooling, the Android environment, the
git workflow, and how we test.

## License

TODO
