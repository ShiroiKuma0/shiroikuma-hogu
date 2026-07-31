# Changelog

All notable changes this fork makes on top of stock
[Aegis Authenticator](https://github.com/beemdevelopment/Aegis).

Fork versions are `<upstream version>+<fork build>`; the fork `versionCode` is
`<upstream versionCode> * 10000 + <fork build>`.

## 3.4.2+14 — 2026-07-31

Base: Aegis `3.4.2` (versionCode 81) → fork versionCode `810014`. Two additions to the
保存復元 automation contract, and nothing else moves.

### 保存復元 — per-category default flag

- **`LIST_CATEGORIES` now answers the contract's optional fourth field**: whether an item
  **starts ticked**. Every line is `id<TAB>label<TAB>parent<TAB>on|off`, with an **empty
  third field** on a top-level item so the flag always stays the fourth, positional one.
- `HoguExport.Cat` carries a `defaultSelected` flag (a constructor overload defaulting to
  `true`, so only the exceptions are spelled out). **`vault.usage` is the only `off`** —
  derived counters that rebuild themselves simply by using the app. The vault itself, and
  every other category, stays `on`.
- **An `EXPORT_STATE` with no `items` extra now means that default set** — exactly the
  categories the listing marks `on` — instead of blindly everything.
- **The in-app Export/Import sheet seeds its checkboxes from the same flag**, so the app's
  own panel and a caller's picker open on an identical selection. *Select all* is
  pre-ticked only when the default really is everything.

### 保存復元 — a cancellable export (`CANCEL_EXPORT`)

- **New `<pkg>.action.CANCEL_EXPORT`**, declared on the **same exported receiver** as the
  other two: a stop path hidden on an `exported="false"` component would be unreachable to
  the caller that needs it.
- Extras: `token` (required — the same gate as every other action) and an optional
  `reply_id` (absent = the export running now).
- **Fire-and-forget: never answered**, not with `OK:` and not even with an error — a
  refused or unmatched cancel is only logged locally.
- **Safe to send at any time.** Arriving when nothing is running, after the export already
  finished, or naming a different `reply_id`, it is a **silent no-op** — not an error, not
  a reply, not a crash.
- The running export lives in a **static registry** (a `BroadcastReceiver` instance dies
  with each broadcast, so the flag has to outlive it), and its `AtomicBoolean` is polled
  **before every category and before every file** copied into the archive. The run unwinds
  at a clean entry boundary — never an interrupted thread, never `System.exit`.
- **A cancelled export leaves the backup directory exactly as it found it**: the
  half-written ZIP is deleted in the same `finally` that now also cleans up after **every
  other failure**, so no short archive is ever left behind.
- The original request still receives its **one terminal reply, `ERROR:cancelled`**,
  guarded by the existing `AtomicBoolean` so it can never double-fire with a success — it
  is sent even when nobody is still listening, because it is what proves the run ended
  rather than carrying on unseen.
- No foreground service and no wakelock are involved here: the broadcast is held open with
  `goAsync()` and released in that same `finally`.

## 3.4.2+13 — 2026-07-25

First published release of 白い熊 防具. Base: Aegis `3.4.2` (versionCode 81) →
fork versionCode `810013`. Everything below is what this fork adds to stock.

### Major features

**Category Export / Import — one ZIP for everything the app holds**
- A new Export/Import panel, opened from the first section of the 白い熊 防具 UI page.
  One checklist drives both directions: Export writes the ticked categories, Import
  applies the ticked categories the chosen archive contains.
- **Exactly one ZIP per export**, named `shiroikuma-hogu_<yyyy-MM-dd_HH-mm-ss>.zip` —
  the 白い熊 family naming convention, so every sister app's backups sort and read
  uniformly in one shared directory. No version, no `-export` infix, no decoration.
- The archive holds `manifest.json` (format, version, app, appVersion, createdTs,
  categories) plus one entry per selected category.
- **Categories, split by the app's own settings screens, with sub-options:**
  - `ui` — the 白い熊 防具 UI page's colours and fonts
    - `ui.fonts` — the `.ttf`/`.otf` files imported into the app
  - `settings` — a **catch-all** for every preference no sub-category claims, so a
    preference a future upstream Aegis adds is carried automatically
    - `settings.appearance` — theme, language, view mode, code grouping, account-name
      position, icon visibility, next code, expiration state, sort order
    - `settings.behavior` — copy behaviour, minimize on copy, search behaviour, haptics,
      focus search, group multiselect, entry highlight, pause on focus
    - `settings.security` — auto-lock mask, secure screen, tap-to-reveal (+ its timeout),
      PIN keyboard, password-reminder frequency, panic trigger, time-sync warning
    - `settings.backups` — Android backups, built-in backups, backup location, version
      count, backup reminder, plaintext-backup warning
  - `vault` — the vault itself: entries, groups and icons
    - `vault.usage` — usage counts, last-used timestamps, group filter
  - `iconpacks` — installed icon packs
- **The vault is exported exactly as it sits on disk**, i.e. still encrypted whenever the
  vault is encrypted. The backup is never weaker than the app's own storage.
- Importing the vault **asks for confirmation first** — it replaces every entry on the
  device and the vault password becomes the backup's.
- **Import is merge-per-key and future-proof**: absent entries are skipped, unknown keys
  ignored, and a missing key keeps its current value — so an older app reading a newer
  archive never breaks.
- A **persisted SAF backup directory**, stored in a device-local preferences file that is
  itself never exported. It is queried when the page opens, so the newest backup's
  timestamp shows without a tap.
- Restored font files drop the resolved-typeface cache, so an imported font applies
  without a restart; icon-pack and font entry names are sanitised against path traversal.

**保存復元 — the sister-app state-export automation contract**
- New exported `StateExportReceiver` implementing the 白い熊 fleet backup contract, so
  白い熊 自由作業盤 can back up every sister app in one headless run.
- `<pkg>.action.LIST_CATEGORIES` — token-gated, instant. Replies `OK:` plus one
  `id<TAB>label` line per category, with a third `parent-id` field on sub-options.
- `<pkg>.action.EXPORT_STATE` — runs the same export core headlessly (no Activity, no
  interaction). Extras: `token`, optional `path` (absolute directory override), optional
  `items` (comma list of category ids; absent = everything), optional `progress_action`,
  and the reply trio `reply_action` / `reply_package` / `reply_id`.
- Directory precedence: `path` extra → the configured backup directory →
  `ERROR:no-directory`. Without All-files access the `path` override falls back to the
  configured directory, or replies `ERROR:no-storage-access`.
- Replies as a **fresh broadcast** with `FLAG_INCLUDE_STOPPED_PACKAGES` — never a
  `ResultReceiver`/`PendingIntent`/`Messenger`, never the ordered-broadcast result, both
  of which EMUI severs between third-party apps.
- Success reply: `OK:<absolute path>|<bytes>|<human size>|<n> categories`, with the byte
  count read back from the written file. **Exactly one terminal reply**, guarded by an
  `AtomicBoolean`, so an async success and a synchronous error can never both fire.
- `automation disabled` and `bad token` are reported as **distinct** errors.
- **Progress broadcasts carry real counts, never a percentage** — `区分 3/10 — Vault` —
  with structured `current`/`total` (long) and `unit`, throttled to one per 500 ms and a
  final one always sent at completion.
- `goAsync()` plus a background thread, so a long export never blocks the broadcast.
- Every request is logged locally, since the reply is invisible on this side.

**Automation token infrastructure**
- Master switch (**default off**) and a 24-byte `SecureRandom` token, hex-encoded and
  generated lazily on first read, compared **constant-time** (never `equals` on a secret).
- Both live in a device-local preferences file that **no export category touches**, so the
  token can never travel inside a backup ZIP.
- The token row shows the token abbreviated, copies the full value on tap, and carries a
  *Regenerate* action that warns pasted copies must be updated.
- `MANAGE_EXTERNAL_STORAGE` is declared solely for the automation path override, and is
  never requested until the switch is turned on.

### UI & theming

**The 白い熊 防具 UI page** — a hand-built, sectioned customization screen, reached from a
row at the top of Settings and by long-pressing the main-screen settings cog.
- **Per-element fonts** for the vault list (issuer / account name / OTP code): independent
  family, weight and size, each *unset = inherit* so untouched attributes keep the
  layout's original values. Applied across all four entry-card layouts.
- **Font import** — any `.ttf`/`.otf` brought in through SAF into `filesDir/fonts`, with a
  picker that previews each font **in its own glyphs**.
- **Per-element colours** with an `UNSET = inherit` sentinel and a recently-used list.
- **App-wide chrome theming** driven by the accent and text colours through exact swatch
  presets, so toolbars, backgrounds and sub-screens follow the identity too.
- **App theme picker** (Light / Dark / AMOLED / Follow system / Follow system AMOLED).
- **One-time seed** on first run — AMOLED black with a vivid yellow accent, issuer and
  code — applied in the `Preferences` constructor so the theme is set before the first
  activity renders. A later migration lifted the earlier softer `#FFEB00` to `#FFFF00`.
- **The kxkb settings visual language**: section headings 20sp bold accent with a
  **text-wide** underline (never full-width), thin full-width spacers between sections
  (none above the first), 17sp sub-section headings with a 1.5dp text-wide underline, and
  a 36 / 54 / 72 / 90 dp indent ladder with tight 5dp rows.
- Rows support a second summary line, a right-hand text action and a switch, so one layout
  serves value rows, toggles and the token row.
- The backup-directory row and its message are **red** until a directory is set, and the
  accent colour once it is — mirrored identically inside the Export/Import panel.
- **ArcaneChat-style action row**: round pill buttons, Cancel alone on the left, Import and
  Export grouped on the right.
- **Result dialogs**: black surface, yellow border, yellow text. Acknowledging one closes
  the whole chain — the dialog, the Export/Import panel, and the UI page behind it — on
  *OK* after an export and on both *Later* and *Restart now* after an import. Failures
  stay toasts and deliberately leave the panel open.
- The settings cog was promoted from an overflow item to a visible toolbar icon: tap opens
  Settings, long-press opens the UI page.

**Launcher icon**
- Redrawn: the artistic Aegis "A" as a vivid `#FFFF00` contour on a pure black field, with
  a thicker stroke and the glyph scaled to 55%. Adaptive foreground/background/monochrome
  vectors.

### Fixes

- **Import-from-file crash** introduced by the `app_name` rebrand: the importer pre-select
  matched the literal string "Aegis" from `app_name`, which no longer exists in this fork.
  Now matched by importer type instead.
- **New-issue form** fixed and de-branded to point at this fork rather than upstream.

### Packaging

- **Installed app id `shiroikuma.hogu`**, so this fork installs **side-by-side** with the
  official Aegis. The code namespace stays `com.beemdevelopment.aegis`, so `R`,
  `BuildConfig` and every source package are unchanged and rebases stay clean.
- **FileProvider authority `shiroikuma.hogu.fileprovider`**, derived from the app id —
  required, because Android refuses to install a second app declaring an authority an
  installed app already owns. Kept consistent in the manifest and in code via
  `BuildConfig.FILE_PROVIDER_AUTHORITY`.
- **App label 白い熊 防具** across the manifest, the build's title placeholder and strings.
- **Own release signing config** reading a gitignored `keystore.properties` (upstream ships
  none — CI/F-Droid signs the official build).
- **Fork versioning**: `versionName = "<upstream>+<BUILD_NUMBER>"`,
  `versionCode = <upstream code> * 10000 + BUILD_NUMBER`, with the counter auto-bumped by
  the `buildRelease` task so no build ever reuses a number or overwrites an older APK.
- **APK naming** `shiroikuma-hogu_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`, copied to
  `~/tmp/` by `buildRelease`.

### Repository tooling

- `CLAUDE.md` documenting the fork workflow, the customization table, and the hard rules
  (never auto-install to the phone, never commit or push unprompted).
- Agent skills: `build-apk` (build and auto-deliver), `upstream-new-version` (rebase onto a
  new Aegis release, reset the build counter), `publish-version` (cut this release).
- No Claude/Anthropic attribution anywhere in the history — existing trailers were scrubbed.
