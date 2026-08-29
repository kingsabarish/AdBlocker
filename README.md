# AdBlocker

A **no-root, system-wide ad blocker for Android**, built in native Kotlin with Jetpack Compose.

## What it does

AdBlocker runs a **local DNS-only VPN** (`VpnService`) that filters DNS for *every app*
on the device — no root required. It blocks the majority of common ads and trackers
by intercepting DNS lookups and returning `NXDOMAIN` for known ad/tracking domains,
while forwarding everything else to a real upstream resolver. All filtering happens
on-device; no traffic is sent to a remote server except blocklist downloads and the
upstream DNS query itself.

> Status: **planning / early build.** The architecture and development conventions are
> settled (see [`AGENTS.md`](./AGENTS.md)); app code lands on feature branches via PR.

## Highlights

- No root, no backend, fully on-device.
- System-wide blocking via a split-tunnel DNS VPN (near-zero perf/battery impact).
- Respected, auto-updating blocklists (StevenBlack, AdGuard, EasyList).
- Modern Material 3 UI with a live query log and per-domain allow/block.

## Repository layout

- `app/` — the native Android app (Kotlin, Jetpack Compose). Single-module Gradle
  project (the `:app` module).
- Root holds shared files: `AGENTS.md`, `README.md`, `.gitignore`.

## Development

See [`AGENTS.md`](./AGENTS.md) for architecture, tooling, the Android environment, the
git workflow, and how we test.

## License

TODO
