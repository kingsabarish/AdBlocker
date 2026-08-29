# AdBlocker — Development Conventions

Shared Android/Kotlin engineering standards for this repo. Project-specific design
notes (e.g. the DNS/VPN internals) live in code and feature PRs, not here.

## Android app architecture & conventions

- **Language:** native **Kotlin + Jetpack Compose** (Material 3).
- **Layout:** organized **package-by-feature** under
  `app/src/main/java/com/adblocker/`. Keep layers as folders
  (`data/`, `domain/`, `ui/`, `vpn/` / `filter/`, `di/`) and hold empty ones with
  `.gitkeep` until filled.
- **On-device architecture** (no backend at runtime): the UI depends only on
  **repository interfaces** in `domain/repository/`; implementations in
  `data/repository/` adapt storage ↔ domain models. `domain/**` has **no** Android /
  framework imports; `ui/**` never imports `data/**` directly. Errors cross the
  boundary as a **sealed `AppResult`** (or similar), not raw exceptions.
- **Persistence:** `DataStore` for settings; `Room` if a local DB is needed
  (schema exported via `exportSchema = true`).
- **DI:** `Hilt` for non-trivial graphs; a minimal manual container is acceptable for
  a small single-page app.

## Stack & tooling

- Versions are centralized in `gradle/libs.versions.toml`. Current pins:
  - AGP **9.3.0**, Gradle **9.5.0**, Kotlin **2.4.10**, KSP **2.3.11**
    (KSP uses *decoupled* versioning — not `<kotlin>-<ksp>`).
  - compileSdk / targetSdk **37**, minSdk **26**, JVM target **17**.
- **AGP 9 provides built-in Kotlin:** do **not** apply the
  `org.jetbrains.kotlin.android` plugin (it errors). Apply the compose, serialization,
  and KSP plugins on top; Kotlin compiler options go in the `kotlin { compilerOptions { }
  }` DSL (jvmTarget defaults to `compileOptions.targetCompatibility`).
- **Configuration cache** is temporarily **off**
  (`org.gradle.configuration-cache=false`) until an AGP version fixes the
  `ProcessNavigationXmlTask` serialization failure. Re-enable once safe.
- **Dynamic color** (Material You) only on API 31+ — guard with
  `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`, else fall back to the static
  scheme (crashes on 26–30 without the guard).

## Android environment

- Built **SDK-only, without Android Studio** — the Android command-line tools + a JDK,
  driven from VS Code / a terminal, deployed to a **physical device** over USB/Wi-Fi
  debugging (no emulator).
- Toolchain on the dev PC: a **JDK** (via `JAVA_HOME`) and the **Android SDK** (via
  `ANDROID_HOME` / `ANDROID_SDK_ROOT`), with `cmdline-tools\latest\bin` and
  `platform-tools` on `PATH`.
- Build & run **natively on Windows** (no WSL/Docker):
  `./gradlew installDebug` from the app module builds and installs to the connected
  phone.
- `app/local.properties` (holds `sdk.dir`) is **machine-local and gitignored** — never
  commit it. Every other `app/` config is committed.
- Gradle runs via the wrapper (`./gradlew`). The wrapper files (`gradlew`,
  `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` + `.properties`) are **committed**
  — clone and run, no `gradle wrapper` step.

## Workflow & git

- **I review every change.** After you make a change, stop and let me review it.
- **Do NOT commit or push** unless I explicitly tell you to. Only after I say "commit"
  / "push" may you run those git commands.
- **Modular commits.** When asked to commit, do **not** dump everything into one commit.
  Split changes into reasonable, logically-grouped commits (e.g. restructure vs. feature
  vs. docs), each with its own clear message.
- **Branching & PR flow:**
  - Every new feature starts on a **feature branch created from `main`**. Do the
    development there.
  - Before creating a new branch, **fetch the latest `main`** and branch from it
    (`git fetch origin && git checkout -b <branch> origin/main`) so it always starts
    from up-to-date `main`.
  - Only after the feature is **well tested and working** does it go to `main` via a
    **PR review**.
  - **No local merge to `main`, and no direct push to `main`.** `main` is updated
    exclusively through the PR review process.

## Testing guide

- **Build verification:** `./gradlew installDebug` builds and installs to a connected
  physical device; a green build is the first gate.
- **Manual on-device verification:** confirm behavior on a real phone (this project does
  **not** use an emulator). For the VPN, verify the tunnel comes up, ads/trackers drop,
  and legitimate traffic still works.
- **Lint:** run `./gradlew lint` as a pre-PR check and fix (or consciously suppress)
  warnings.
- **Unit tests (JUnit):** add them for pure logic as the engine grows — e.g. DNS/IP
  packet parsing, blocklist/domain matching (Trie/HashSet), and cache behavior. UI is
  verified manually.

## On-device deployment & manual testing (OnePlus / ColorOS)

The dev phone is a OnePlus (ColorOS, Android 14+). SDK-only workflow:

- JDK via `JAVA_HOME`, Android SDK via `ANDROID_HOME`; `platform-tools` on `PATH`.
- Build + install: `./gradlew installDebug` (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
- **Side-loaded APKs trigger the OEM installer.** After `adb install -r` the flow is:
  1. Installer screen `com.oplus.stdsp` → tap **Continue installation**
     (`com.oplus.stdsp:id/btn_third`, bounds `[82,1858][998,1970]`, tap `540,1914`).
  2. Next screen → tap **Open** (`com.oplus.stdsp:id/btn_open`, bounds `[889,327][1039,398]`,
     tap `964,362`). If you launched the app another way, skip this.
- In-app: tap **Start** (`[433,1292][648,1414]`, tap `540,1353`). First run shows the system
  VPN consent dialog `com.oplus.wirelesssettings` → tap **Allow**
  (`com.oplus.wirelesssettings:id/button1`, bounds `[541,2127][999,2276]`, tap `770,2201`).
- **Verify the tunnel is up:** `adb shell ip addr show tun0` should list `10.10.10.1/32`.
- **Verify blocking:** from the device, `ping -c1 <blocklisted-domain>` (e.g.
  `ad.doubleclick.net`) resolves to `127.0.0.1`/loopback (the app answers `0.0.0.0`), while a
  legit domain (`example.com`) resolves to a real IP and pings. Watch the blocked counter in
  the UI and `adb logcat -d | grep AdBlock/` (`BLOCK` / `FORWARD ok` lines).
- **`getent`/`nslookup` are NOT on the device** — `ping -c1` is the quickest DNS probe.
- If the UI shows the app backgrounded to the launcher right after Start, the foreground
  service crashed: check `adb logcat -b crash` and the `AdBlock/` tags.

### Foreground service / `targetSdk` note (important)

- `VpnService` here calls a **plain `startForeground()`**. On API 34+ the system normally
  requires `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_VPN)` **and**
  `android:foregroundServiceType="vpn"` in the manifest. This toolchain (AGP 9.3.0 → build-tools
  36/37) **rejects `"vpn"` as a `foregroundServiceType` flag** (`'vpn' is incompatible with
  attribute foregroundServiceType` — the flag is missing from the framework enum in those
  build-tools). Until build-tools ship the flag, the app is pinned to **`targetSdk = 33`** so a
  plain `startForeground()` stays valid. Revisit this when build-tools support `vpn` (or drop
  AGP's minimum build-tools) — then declare the type properly and raise `targetSdk`.
- The DNS forwarder `DatagramSocket` **must** be `VpnService.protect()`ed and given a
  **`soTimeout`** (3s). Without the timeout a single unanswered upstream reply stalls the
  single reader thread forever and every later query fails (`unknown host`).

