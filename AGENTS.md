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
