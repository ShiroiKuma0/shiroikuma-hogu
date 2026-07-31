<div align="center">

<img src="metadata/en-US/images/icon-hogu.png" width="120" alt="白い熊 防具 app icon" />

# 白い熊 防具

## 「防具」 is read BŌGU — *armour*, the protective gear a kendōka wears.
Fitting for the app that guards every one-time code you own.

**A free, secure, open-source 2FA authenticator — rebuilt in black and yellow, and made to back itself up.**

A fork of [Aegis Authenticator](https://github.com/beemdevelopment/Aegis) with **major additions**: a
complete category-based Export/Import for everything the app holds, headless token-gated backup
automation for the 白い熊 sister-app fleet, and a deep per-element font & colour customization page.

Installs **side-by-side** with the official Aegis (app id `shiroikuma.hogu`), so both can sit on the
same phone without fighting over the vault.

**📥 Latest release: [`3.4.2+14`](https://github.com/ShiroiKuma0/shiroikuma-hogu/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-hogu/releases)

</div>

---

## 🗂 Export / Import — everything, in one file

Stock Aegis exports its vault and nothing else: your theme, your fonts, your icon packs, every
preference you ever tuned all evaporate on a reinstall. This fork replaces that with a single
**category-based Export/Import** panel.

Tick what to carry and get **one ZIP** — `shiroikuma-hogu_2026-07-25_23-14-02.zip` — holding a
manifest plus one entry per category. Import runs the same checklist in reverse, applying only the
categories you ticked and skipping anything the archive doesn't have.

The split follows the app's own settings screens, with sub-options that fold under their parent:

| | |
| --- | --- |
| **白い熊 防具 UI** | every colour and font you set — *and, as a sub-option, the font files you imported* |
| **App settings** | Appearance · Behaviour · Security · Backups — *plus a catch-all, so a preference a future Aegis adds is carried without anyone editing a list* |
| **Vault** | entries, groups and icons — *plus usage statistics as a sub-option* |
| **Icon packs** | the packs you installed |

**The vault travels exactly as it sits on disk** — still encrypted whenever your vault is encrypted.
The backup is never weaker than the app's own storage, and restoring it asks first, because it
replaces every entry and the password along with them.

A persisted backup directory is queried the moment the page opens, so the newest backup's timestamp
is simply *there* — and the row glares **red** until you set one.

---

## 🤖 保存復元 — back up every sister app in one run

防具 speaks the **保存復元 state-export contract**: a token-gated intent that makes it export itself
**headlessly**, with no UI and no taps, so [白い熊 自由作業盤](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban)
can sweep the whole sister-app fleet in a single task and collect the results.

- **`LIST_CATEGORIES`** enumerates what this app can export, sub-options and all, so the caller can
  render a picker — and says for each one **whether it starts ticked**, the same defaults the
  in-app sheet opens on.
- **`EXPORT_STATE`** writes exactly one ZIP — to a directory the caller names, or to the one
  configured in-app — and replies with the absolute path, the real byte count, a human-readable
  size, and how many categories went in.
- **`CANCEL_EXPORT`** stops a running export at the next entry boundary and **deletes the
  half-written archive**, so a cancelled run leaves the backup folder exactly as it found it. Safe
  to send at any time: with nothing running it is a silent no-op.
- **Progress reports real numbers**, never a percentage: `区分 3/10 — Vault`, throttled to one
  update every 500 ms.

Nothing is reachable until you flip the switch. The gate is a **24-byte `SecureRandom` token**,
compared in constant time, kept in a device-local preferences file that **no category exports** — so
the secret can never ride along inside a backup. Tap the token row to copy it; *Regenerate* mints a
fresh one and revokes every copy you pasted elsewhere.

---

## 🎨 The 白い熊 防具 UI page

A hand-built customization page — reached from the top of Settings, or by **long-pressing the
settings cog** on the main screen — that goes far past stock's light/dark switch.

- **Per-element fonts.** Issuer, account name and OTP code each get their own family, weight and
  size. Every attribute is independently *unset = inherit*, so you change only what you mean to.
- **Import your own fonts.** Drop in any `.ttf`/`.otf`; the picker previews each one **in its own
  glyphs** before you commit.
- **Per-element colours** with a recently-used palette, and an accent colour that drives the whole
  Material 3 chrome — toolbar, backgrounds, sub-screens.
- Laid out in the shared 白い熊 settings language: bold accent headings underlined to **exactly the
  width of their own text**, separated by hairline spacers, on a deep indent ladder.

---

## ⬛ Black and yellow, from the launcher in

The launcher icon is the Aegis "A" redrawn as a vivid `#FFFF00` contour on a pure black field. On
first run the app seeds itself to match — AMOLED black with yellow accent, issuer and code — and
from that moment every one of those values is yours to change or reset.

---

## Built on Aegis Authenticator

A fork of [Aegis Authenticator](https://github.com/beemdevelopment/Aegis) by Beem Development
(app id `shiroikuma.hogu`, so it coexists with the official build). Aegis is a genuinely excellent
2FA app — properly encrypted vaults, real backups, HOTP/TOTP/Steam/Yandex/mOTP support, and imports
from just about every other authenticator — and all of that is theirs. This fork adds a
customization layer and a backup contract on top; the underlying security design is unchanged.

The code remains under the **GNU General Public License v3**.

## Building

```bash
git clone git@github.com:ShiroiKuma0/shiroikuma-hogu.git
cd shiroikuma-hogu
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildRelease
```

Needs **JDK 17+** and the Android SDK (`compileSdk 35`). `buildRelease` builds the signed release
APK, copies it to `~/tmp/`, and bumps the fork's build counter. Signing credentials come from a
gitignored `keystore.properties`; without it the build is unsigned and won't install.

Fork versions are `<upstream version>+<build>` — this release is Aegis `3.4.2`, fork build `13`.
