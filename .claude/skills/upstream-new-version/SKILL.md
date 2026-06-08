---
name: upstream-new-version
description: Rebase our fork onto a new upstream release of beemdevelopment/Aegis. Fast-forward the `master` mirror to the new release, replay our `custom` customizations on top of it, reset the version base + build counter, and produce a fresh +1 build via the build-apk skill (never auto-deploying). Use when the user says a new upstream Aegis version is out, runs /upstream-new-version, asks to update/sync to upstream, bump to the new Aegis release, or rebase custom onto the latest upstream.
---

# Rebase the 白い熊 防具 fork onto a new upstream Aegis release

This codifies the "new upstream version" half of the fork workflow for the user's rebranded Aegis fork
(`shiroikuma.hogu` / `白い熊 防具`). The goal: move `master` to the new upstream release, replay our
`custom` customizations on top of it, reset the version base, and produce a fresh `+1` build. It is the
front-end to the **build-apk** skill: this skill does the git work and hands the **build** to build-apk.

> **Never `git push` or `git commit` unprompted, and never `adb install`.** Same hard rules as everyday
> development (see CLAUDE.md and the build-apk skill). After the rebase + build you **stop** and let the
> user test on-device; you only `git push` when they explicitly say **"Push"**. Because the rebase
> rewrites `custom`'s history, publishing it is a **force-push** (`git push --force-with-lease origin
> custom`); `master` is a plain fast-forward.

## Background — how the fork is laid out

- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-hogu` — our fork (push here).
- `upstream` → `https://github.com/beemdevelopment/Aegis.git` — the original (read-only, for rebasing).
- **`master`** mirrors upstream's `master`. **Fast-forward only — never develop on it.**
- **`custom`** is our development branch. **All our work lives here**, as a small stack of commits on top
  of the upstream release, rebased onto each new release tag.

### How versioning works here

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream** (currently `3.4.2` / `81`).
- `BUILD_NUMBER` is **our** fork increment. It **resets to `1`** on each new upstream version.
- Fork `versionName` = `"<VERSION_NAME>+<BUILD_NUMBER>"`, `versionCode` = `VERSION_CODE * 10000 + BUILD_NUMBER`.

So when upstream's `versionCode` climbs (e.g. 81 → 82), our new line's codes (`820001`, `820002`, …) all
exceed the previous line's (`810001`, …), keeping sideloaded upgrades monotonic.

## Steps

Run in order. Echo what you find at each gate; stop at the named STOP points.

### 1. Preflight

- `cd ~/git/shiroikuma-hogu`. Confirm a clean tree: `git status --porcelain` must be empty. If not, STOP
  and surface it — don't `reset --hard` someone's uncommitted work.
- Note the current branch (return to `custom` at the end).
- `git fetch upstream --tags` (and `git fetch origin --tags`). This creates `upstream/*` + tags if the
  clone lacks them.

### 2. Detect the new release

- Newest upstream release tag **by date** (Aegis uses clean `vX.Y.Z` tags; ignore `-beta`/`-rc` unless
  the user wants a prerelease):
  `git for-each-ref --sort=creatordate --format='%(creatordate:short) %(refname:short)' refs/tags | tail`
- Confirm the new `VERSION_NAME` / `VERSION_CODE` from upstream at that tag:
  `git show <tag>:app/build.gradle | grep -E 'versionCode|versionName'`.
- **Current base** = the upstream tag our stack sits on; derive it from the committed `VERSION_NAME` in
  `gradle.properties` (e.g. `3.4.2` → `v3.4.2`). If the newest tag is **not** newer than our base, report
  "already on the latest upstream release (vX.Y.Z), nothing to do" and STOP — no destructive ops.

### 3. Confirm before destructive ops (STOP/gate)

Summarize for the user and get a go-ahead before touching branches:
- old base `vOLD` → new release `vNEW` (+ its date);
- **capture the stack size now** — `OLD_COUNT=$(git rev-list --count vOLD..custom)` — and report it
  (step 7 compares against it to confirm no commit was silently dropped in the rebase);
- the plan: FF `master` to `vNEW`, back up `custom`, rebase the stack onto `vNEW`.

Proceed only on the user's go-ahead.

### 4. Fast-forward the mirror + back up custom

- `git checkout master && git merge --ff-only upstream/master` (FF only; if it can't FF, upstream rewrote
  history — STOP and discuss).
- Safety backup of the stack: `git branch custom-pre-vNEW custom` (e.g. `custom-pre-v3.5.0`). The version
  is the stable label (timestamps aren't available). `origin/custom` + the reflog are extra safety nets.

### 5. Rebase `custom` onto the new release

```
git checkout custom
git rebase --onto vNEW vOLD custom
```
Resolve conflicts so **every** customization in the table below survives. The conflict-prone files are
`app/build.gradle`, `app/src/main/AndroidManifest.xml`, and `app/src/main/res/values/strings.xml`.
- **Small** conflicts (context drift around one of our edits, an obvious re-application of a known edit) →
  resolve inline and `git rebase --continue`.
- **Significant** conflicts (upstream refactored/renamed/deleted a file we customize; the shape of an edit
  site changed; many commits conflict) → **STOP, do not improvise.** Bring the user a concrete plan via
  `AskUserQuestion` (resolve together / re-implement on the new base / defer) and act on their choice.
  If they want to bail: `git rebase --abort` restores `custom`; `master` stays FF'd (harmless).

### 6. Reset the version base + counter

Edit `gradle.properties`:
- Set `VERSION_NAME` / `VERSION_CODE` to the **new upstream** values (from step 2).
- **Reset `BUILD_NUMBER` to `1`** so the first build of the new upstream line is `+1`.

(`APP_ID` / `APP_NAMESPACE` do **not** change on an upstream bump — they are our fixed fork identity.)

### 7. Verify our customizations survived

| What | Expected value | Where |
| --- | --- | --- |
| Installed app ID | `shiroikuma.hogu` | `gradle.properties` → `APP_ID`; consumed as `applicationId` in `app/build.gradle` |
| Code namespace (UNCHANGED) | `com.beemdevelopment.aegis` | `gradle.properties` → `APP_NAMESPACE`; `namespace` in `app/build.gradle` |
| App label | `白い熊 防具` | `android:label` in `AndroidManifest.xml` + the `title` manifestPlaceholder (release) in `app/build.gradle` + `app_name` / `app_name_full` in `values/strings.xml` |
| FileProvider authority | `shiroikuma.hogu.fileprovider` (derived from `APP_ID`, **not** the namespace) | `fileProviderAuthority` in `app/build.gradle` |
| Signing config | release `signingConfig` reading `keystore.properties` → `~/.android-keystores/shiroikuma-hogu.jks` | `app/build.gradle` (upstream ships none — ours is additive) |
| Fork version logic + `buildRelease` task | `forkVersionName` / `forkVersionCode` + the `buildRelease` task | `app/build.gradle` |

Also confirm no commit was dropped: `git rev-list --count vNEW..custom` should equal `OLD_COUNT` from
step 3; skim `git log --oneline vNEW..custom`. The **FileProvider authority deriving from `APP_ID`** is
the load-bearing edit for side-by-side install — double-check it didn't revert to the namespace.

### 8. Build the new +1 (delegate to build-apk)

Invoke the **build-apk** skill (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew buildRelease <
/dev/null`). This is the first build of the new line (`<newVersion>+1`). build-apk bumps the counter,
stamps the version, copies the APK to `~/tmp`, and then **asks** before any `adb push`. Do not deploy on
your own.

### 9. Stop, test, confirm, push

Let the user test on-device (a new-upstream build deserves a real smoke test). **Only after they confirm**
("Push" / "good"):
- commit any rebased stack tip + the `gradle.properties` base/counter edits;
- `git push --force-with-lease origin custom` (rebase rewrote history); `master` is a fast-forward:
  `git push origin master`;
- post-push sync: `git fetch origin --tags && git pull --rebase origin custom`;
- once `custom` is confirmed pushed, the `custom-pre-vNEW` backup branch can be deleted (ask first).

## Notes

- Keep our changes a **small, legible layer** on top of upstream — prefer rebasing (linear history) over
  merging, so the customization set stays easy to audit and replay. **Never recreate `custom` from
  scratch** — rebase the stack; if it won't rebase cleanly, that's a "significant conflict" to discuss.
- If upstream restructures a file we customize, port our change to the new structure rather than forcing
  the old diff.
- `master` is **FF-only**. If it can't fast-forward, upstream rewrote history — that's a conversation,
  not a `--force`.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
