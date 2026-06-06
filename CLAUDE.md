# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**白い熊 防具** — a personal fork of [Aegis Authenticator](https://github.com/beemdevelopment/Aegis), a
free, secure, open-source 2FA (TOTP/HOTP) app for Android. Written in Java (with some Kotlin tooling),
targeting Android API 23–35.

This repository (`ShiroiKuma0/shiroikuma-hogu`) is a fork. We track upstream (`beemdevelopment/Aegis`)
and layer a small set of customizations on top of it. The fork carries **no functional code patch** so
far — it is a pure identity rebrand (applicationId + FileProvider authority + app label + signing + APK
naming) so the user runs their own signed build that installs **side-by-side** with the official Aegis.

## Fork Workflow — READ THIS FIRST

This is the most important section. The whole point of this repo is to maintain a small set of
customizations on top of upstream and rebuild as upstream releases new versions.

### Git remotes & branches

- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-hogu` — our fork (push here).
- `upstream` → `https://github.com/beemdevelopment/Aegis.git` — the original (read-only, for rebasing).
- **`master`** mirrors upstream's `master`. **Fast-forward only — we do not develop on it.**
- **`custom`** is our development branch. **All our work lives here.** This is the default working branch.

### Our customizations (what makes this a fork)

| What | Value | Where |
| --- | --- | --- |
| Installed app ID | `shiroikuma.hogu` | `gradle.properties` → `APP_ID` (→ `applicationId`) |
| Code namespace (unchanged from upstream) | `com.beemdevelopment.aegis` | `gradle.properties` → `APP_NAMESPACE` (→ `namespace`) |
| App label | `白い熊 防具` | `android:label` in `AndroidManifest.xml`, the `title` manifestPlaceholder in `app/build.gradle`, and `app_name` / `app_name_full` in `values/strings.xml` |
| FileProvider authority | `shiroikuma.hogu.fileprovider` (derived from `APP_ID`) | `fileProviderAuthority` in `app/build.gradle` |
| Signing | own keystore via `keystore.properties` (gitignored) → `~/.android-keystores/shiroikuma-hogu.jks` (alias `hogu`) | `app/build.gradle` `signingConfigs` (upstream ships none) |

The app ID is deliberately changed so this fork installs **alongside** the official Aegis without
conflict. The namespace is intentionally kept as `com.beemdevelopment.aegis` so `R` / `BuildConfig` and
every source package remain unchanged — only the installed package id differs.

**Why the FileProvider authority matters:** Android refuses to install a second app that declares a
content authority already owned by an installed app. So a side-by-side rebrand must change the
FileProvider authority too — ours derives from `APP_ID` (`shiroikuma.hogu.fileprovider`), kept consistent
in the manifest (`${fileProviderAuthority}` placeholder) and in code (`BuildConfig.FILE_PROVIDER_AUTHORITY`).

### Versioning & APK naming

We base our version on upstream and add a fork increment (`BUILD_NUMBER`).

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream** (currently `3.4.2` / `81`).
- `BUILD_NUMBER` is **our** increment. It starts at `1` and bumps by `1` on every build.
- Fork `versionName` = `"<VERSION_NAME>+<BUILD_NUMBER>"` (e.g. `3.4.2+1`).
- Fork `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER` (e.g. `81 * 10000 + 1 = 810001`).
- Output APK filename = `shiroikuma-hogu_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`
  (e.g. `shiroikuma-hogu_3.4.2+1_arm64-v8a.apk`).

So the first build is `+1` (`810001`), the next build with changes is `+2` (`810002`), and so on. The
`_arm64-v8a` suffix just names the deploy target — the APK has no native libs and is effectively universal.

### Building

Requires **JDK 17+** and the **Android SDK**. On this machine the default `java` is JDK 11, so builds
must run with JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildRelease
```

(`sdk.dir` lives in the gitignored `local.properties` → `/home/shiroikuma/android-sdk`.) See the
**build-apk** skill for the full build-and-push procedure.

`buildRelease` (defined in `app/build.gradle`):
1. builds `assembleRelease` (signed, via `keystore.properties`),
2. copies the APK to `~/tmp/shiroikuma-hogu_<version>_arm64-v8a.apk`,
3. **auto-increments `BUILD_NUMBER`** in `gradle.properties` for the next build.

Unlike the Fossify sibling forks, this fork has **no** patched-Commons / `mavenLocal` prerequisite — it
builds straight from public Maven + jitpack, so there is nothing to pre-publish.

### Rebasing onto a new upstream release

When the user says a new upstream version is out, follow the **upstream-new-version** skill. In short:
1. `git fetch upstream --tags`.
2. Fast-forward `master` to the new upstream release tag.
3. Rebase `custom` onto the new `master`, preserving every customization in the table above.
4. Set `VERSION_NAME` / `VERSION_CODE` to the new upstream values and **reset `BUILD_NUMBER` to `1`**.
5. Build the new `+1` version with `./gradlew buildRelease`; continue further changes as `+2`, `+3`, …

### HARD RULES (do not violate)

- **Never install APKs to the phone automatically.** After building, **ask** the user (via
  `AskUserQuestion`). Only when they confirm, `adb push` the APK to `/sdcard/tmp/` (the user installs it
  manually from there). Do **not** use `adb install`.
- **Never commit or push on your own.** Develop and build, let the user test, and **only commit/push when
  the user explicitly says "Push"**. Push goes to `origin` (`custom` branch). Because rebasing rewrites
  `custom`, publishing it after an upstream bump is a force-push (`git push --force-with-lease`).

## Build Commands

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildRelease     # Our fork build: release → ~/tmp + bump BUILD_NUMBER (use this)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleRelease  # Build the signed release APK only
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug    # Debug APK (app id gets a .debug suffix, separate FileProvider authority)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test             # JVM unit tests (Robolectric)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew lint             # Android lint (abortOnError true)
```

There are no product flavors — just `debug` and `release` build types.

## Architecture

Single Gradle module `:app`, package root `com.beemdevelopment.aegis` (unchanged namespace).

- **Entry point:** `AegisApplication` (Hilt `@HiltAndroidApp`); UI lives under `ui/` — `MainActivity`
  (the vault list / authenticator screen), plus `AuthActivity`, `IntroActivity`, `ScannerActivity`,
  `EditEntryActivity`, `PreferencesActivity`, `GroupManagerActivity`, `AssignIconsActivity`,
  `AboutActivity`, etc.
- **Vault model:** `vault/` — `Vault`, `VaultEntry`, `VaultFile`, `VaultBackupManager`,
  `VaultFileCredentials`. The vault is the encrypted store of 2FA entries.
- **OTP:** `otp/` — `TotpInfo` / `HotpInfo` / `SteamInfo` / `MotpInfo` / `YandexInfo` (+ `GoogleAuthInfo`
  for `otpauth://` parsing) implement the code-generation algorithms.
- **Crypto & persistence:** `crypto/` (key derivation, BouncyCastle-backed encryption), `database/`
  (Room, schemas under `app/schemas`), `encoding/`.
- **Cross-cutting:** `Preferences.java` wraps SharedPreferences; `helpers/`, `util/`, `icons/` (icon
  packs), `importers/` (import from other authenticator apps), `services/` (quick-settings tiles),
  `receivers/` (boot / vault-lock). DI is Dagger **Hilt** (`AegisModule`).
- **Backup:** `AegisBackupAgent` + `xml/backup_rules*`. Protobuf (`protobuf-javalite`) is used for some
  serialized structures (generated at build time).

## Key Configuration Files

- `gradle.properties` — fork app id/namespace (`APP_ID` / `APP_NAMESPACE`), `VERSION_NAME` /
  `VERSION_CODE`, `BUILD_NUMBER`.
- `app/build.gradle` — Android config, fork version logic, FileProvider authority, signing config, and
  the `buildRelease` task. **This is where most of our customizations live.**
- `keystore.properties` — signing config (gitignored; points to `~/.android-keystores/shiroikuma-hogu.jks`).
- `local.properties` — `sdk.dir` (gitignored).

## Conventions

- Match upstream Aegis's Java style (4-space indent, existing patterns) — keep our diff a small, legible
  layer so it rebases cleanly onto new releases.
- Prefer adding NEW files / minimal edits over rewriting upstream files, to reduce rebase conflicts.
- Lint runs with `abortOnError true` and `checkDependencies true`; don't introduce new lint errors.
