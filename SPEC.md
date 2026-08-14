# Focus — project specification

Read this whole file before changing code. It is the source of truth for what
this app must do. Where the existing code disagrees with this document, the
document wins.

---

## 1. What this is

An Android launcher that replaces the home screen on a personal phone. Its job
is to remove distraction without making the phone less useful. It is a
self-binding tool: the person installing it is deliberately making certain
things harder for their future self.

**Target device:** Samsung Galaxy M14 5G (SM-M146B), Exynos 1330, 4GB RAM,
Android 13/14 base, One UI. minSdk 33, targetSdk 35.

**Approach:** no root, no bootloader unlock, no custom ROM, and no factory
reset required. Enforcement is primarily an `AccessibilityService`, with
Device Owner (`DevicePolicyManager`) layered on top when it happens to be
provisioned over ADB, plus a local `VpnService` for sites. Do not propose
solutions that require root.

---

## 2. Defects fixed — do not reintroduce

These were the reasons the first build looked empty and blocked nothing. They
are all resolved; the list stays as a record of what to watch for.

1. **Settings is effectively invisible.** The link on the home screen is drawn
   at 14% opacity at the bottom of the screen against a near-black background.
   Make it clearly visible and reachable. Also add a long-press-anywhere
   gesture on the home screen as a second route in.
2. **Settings navigation may not be wired.** Verify that settings actually
   links to the app picker, blocklist, and reading screens. If those
   `NavLink` rows are absent or the file failed to patch, add them.
3. **No first-run setup.** There is no onboarding, so a fresh install looks
   like a blank launcher with no way to configure anything. Build the setup
   flow described in section 8.
4. **No permission state visibility.** If Device Owner is not provisioned, or
   usage access is not granted, the app silently does nothing. It must say so
   plainly, on screen, with the exact fix.
5. **Gradle wrapper is missing.** Generate it and commit it.
6. **Nothing has ever been compiled.** Expect import errors, Compose API
   signature mismatches, and unused-import warnings throughout. Fix them.
7. **Nothing was actually blocked.** Enforcement went only through Device
   Owner, which needs a factory reset to provision, so on a normal phone every
   rule was decorative. See section 5.
8. **The VPN was a black hole.** It routed `0.0.0.0/0` and wrote packets back
   to the TUN, discarding all traffic rather than filtering DNS.
9. **Loosening a restriction applied instantly**, bypassing the 24-hour gate
   that is the entire point of the tool. See section 6.
10. **The launcher stuttered.** Every home press called `recreate()` and
    reloaded every installed app's label on the main thread. See section 4.

---

## 3. Visual design — keep this

Two colours only. Every other tone is the foreground colour at reduced alpha.
The tokens live in `ui/Theme.kt`; use them, do not hardcode colours.

- Background (Ink): `#0F1214`
- Foreground (Bone): `#E7E2D8`
- Raised surfaces: Bone at 5% alpha; pressed at 9%
- Corner radius: 18dp for fields and cards, 14dp for rows
- Horizontal gutter: 24dp everywhere
- All interface text lowercase, letter-spacing +0.4sp
- No ripples. Press feedback is a surface tint change.
- No icons anywhere in the launcher. Text only.

Keep this restraint. Do not add accent colours, gradients, or app icons.

---

## 4. Home screen

- Clock at top: hour at full opacity, colon at 14%, minutes at 45%. Light
  weight, large. Ticks on the minute boundary.
- Date beneath in lowercase, small, muted.
- A single search field. Typing filters installed apps by label; show at most
  7 results. Empty query shows no app list at all — this is deliberate, there
  is no browsable grid.
- Each result row: app label on the left; on the right, `12m` if the app has
  remaining allowance today, or the reason it is unavailable.
- Tapping a result launches the app and clears the query.
- **With an empty query the space is the day, not a void:** today's agenda
  (section 10) and a month calendar, and beneath everything the user's own
  line (section 11).
- A clearly visible route into settings.
- Re-apply policy whenever the launcher returns to the foreground.

**Performance is a requirement, not a nicety.** This runs on a 4GB phone and
is the first thing touched after every unlock. Concretely: never resolve
launcher activities or call `loadLabel` on the main thread — `AppCatalog`
caches that for the process; never call `queryAndAggregateUsageStats` during
composition; never re-parse stored JSON per list row — read a `PolicySnapshot`
once and index it; and never call `recreate()` on a home press.

---

## 5. Restrictions

Each installed app can carry one rule:

- **none** — untouched
- **daily time limit** — N minutes of foreground time per day
- **blocked entirely**

Apps with no rule are never interfered with in any way. Phone, messages,
camera, browser, music, banking, transport, maps must all work exactly as they
did before. Restriction is strictly opt-in per package.

**Measurement:** `UsageStatsManager.queryAndAggregateUsageStats` from local
midnight. Requires the PACKAGE_USAGE_STATS special access permission.

**Enforcement — two independent layers.** Either one alone is sufficient; the
app is honest on the home screen when neither is available.

1. **Accessibility guard (`FocusGuardService`) — the primary layer.** Needs
   only the accessibility toggle, so it works on an ordinary phone. It sees a
   restricted package come to the foreground and immediately performs
   `GLOBAL_ACTION_HOME`, then records a note the launcher displays explaining
   what was closed and why. This is how every app blocker on the Play Store
   works, and it is what makes the app do anything at all on a device that was
   never factory reset. Do not remove it in favour of Device Owner alone.
2. **Device Owner suspension — optional and stronger.**
   `DevicePolicyManager.setPackagesSuspended`, applied on top when it happens
   to be provisioned. Suspended apps never launch at all, so there is no
   window where the app flashes up. Do not use `setApplicationHidden` — a
   vanished app is more confusing than a blocked one.

**Measurement, second by second.** The guard keeps its own ledger
(`UsageLedger`) of foreground time, marked on window changes and on a tick
scheduled to fire exactly when the allowance runs out. `UsageStatsManager` is
merged in by taking the larger value per package, so the system's figures
cover stretches when the guard was off and the ledger covers the OEM builds
that only flush `totalTimeInForeground` once an app leaves the foreground.
Usage access is therefore recommended rather than required.

**Timing:** the guard reacts within a second. The periodic `WorkManager` job
every 15 minutes is the backstop, not the mechanism. Also re-apply on boot via
a `BOOT_COMPLETED` receiver, because suspension does not survive a restart.

**Release:** at local midnight, suspension lifts automatically for time-limited
apps. Fully blocked apps stay blocked.

---

## 6. The unlock gate — this is the core mechanic

Getting into settings, and every change made inside it, is deliberately
expensive. Implement exactly this:

**Entering settings** requires solving one challenge, generated locally:

- Roughly half trigonometry at senior-secondary level: law of cosines, law of
  sines, solving `sin x = k` on `[0°, 360°)`, period and amplitude of
  `a·sin(bx) + c`, exact values in non-first quadrants. Numeric answers
  accepted within ±0.02.
- Roughly half logic riddles with a single unambiguous word or number answer.
- A wrong answer discards the question and generates a different one. The same
  question must never be retryable.
- Show the hint only after a failed attempt.

**Adding or tightening a restriction** applies immediately. No gate beyond
entry — making things stricter should be frictionless.

**Loosening or removing any restriction** goes through this flow, without
exception:

1. User requests the unlock for exactly one app.
2. The request is recorded but has **no effect**.
3. A 24-hour timer starts. During it, the app stays blocked. The settings
   screen shows the remaining time.
4. After 24 hours the request does not auto-apply. It waits for an explicit
   confirmation tap.
5. Only after confirmation does the restriction lift.

**Only one unlock request may be pending at a time.** Requesting a second one
while a request is outstanding must be refused with a clear message.

Cancelling a pending request is always allowed and takes effect immediately —
backing out of a relaxation is not a relaxation.

Removing a blocked domain follows the same delayed path.

---

## 7. Website and in-app section blocking

### Websites

A local `VpnService` acting as a DNS filter. Parse outbound UDP/53 packets,
read the QNAME, drop the packet if it matches the blocklist. Matching covers
the domain and all subdomains.

- Works across every browser and app, since it operates below them.
- Android permits only one active VPN, so this cannot coexist with a
  commercial VPN. Say so in the UI.
- The blocklist editor allows adding freely; removal goes through the 24-hour
  delayed flow from section 6.
- Ship a starter list the user can extend. Adult sites are the primary target.

### In-app sections

An `AccessibilityService` that closes short-form video feeds — Instagram
Reels, YouTube Shorts, TikTok's For You feed — by detecting the feed on screen
and performing a global BACK.

**Scoping is a hard requirement.** Set `AccessibilityServiceInfo.packageNames`
at runtime to exactly the packages the user has enabled for section blocking,
and nothing else. The service must never receive events from banking apps,
messaging, or anything unlisted. Update the scope via `setServiceInfo()`
whenever the list changes. If the list is empty, scope it to a sentinel
package that matches nothing — an empty array means "all packages" and is
unacceptable here.

Detection uses view-id substrings and visible-text hints, both user-editable,
because vendors reshuffle their layouts regularly. When detection breaks, the
user must be able to fix the hints without a rebuild. Debounce actions to
avoid firing repeatedly on content-changed events.

---

## 8. First-run setup

The app currently gives a new user nothing to do. Build a setup flow that runs
when no configuration exists, and remains reachable from settings afterward.
It must show live status for each item and refuse to pretend things work when
they do not:

1. **Set as home screen** — link to the system chooser.
2. **Usage access** — deep-link to the settings page; show granted/not granted.
   Without this, time limits silently do nothing.
3. **Device Owner** — cannot be granted from inside the app. Show the exact
   ADB command and explain it requires a device with no accounts configured:
   `adb shell dpm set-device-owner com.focus.launcher/.policy.FocusDeviceAdminReceiver`
   Show clearly whether it is currently active.
4. **Accessibility service** — optional; only needed for section blocking.
5. **VPN consent** — optional; only needed for site blocking.
6. **Anthropic API key** — optional; only needed for reading quizzes. Stored
   on-device only, never logged, never displayed back in full after saving.
7. **Choose apps to restrict** — hand off to the app picker.

If Device Owner is not active, the home screen must display a persistent,
honest banner: restrictions cannot be enforced. Do not fail silently.

---

## 9. Daily reading

- User picks a local EPUB file via the storage access framework. EPUBs are ZIP
  archives of XHTML; extract entries, strip tags, keep sections over ~800
  characters as chapters.
- DRM-protected files from commercial stores will not parse. Detect this and
  say so plainly rather than showing an empty list.
- Also support a **paste-text** source, for books that only exist as PDFs or
  awkward files. Structure this as a pluggable source interface so a PDF
  source can be added later without touching the quiz path.
- After reading, generate 5 multiple-choice comprehension questions from the
  chapter via the Anthropic API (`claude-sonnet-4-6`), asking about specific
  events, names, and arguments rather than themes. Request JSON only; strip
  code fences defensively before parsing.
- Passing is 50% or better.

**Failure consequence — read this carefully.** Failing the quiz restricts, for
the following day only, the apps that already carry a rule. Time-limited apps
get zero allowance; blocked apps stay blocked.

**It must never restrict anything else.** Phone, messages, maps, banking,
transport, camera, and any app without a rule remain fully available at all
times, regardless of quiz results. There must be no code path by which a
missed quiz can leave the phone unusable in an emergency. Treat this as a
safety requirement, not a preference.

---

## 10. Daily agenda

A todo list that is part of the enforcement, not an accessory.

- Two kinds of item: a **daily** task that reappears every morning, and a
  **today only** one-off. Both store their creation date, so a task added
  today can never count retroactively against a day that has already ended.
- Today's list sits on the home screen under the search field, with the tasks
  tappable to complete. A month calendar underneath marks each day: a filled
  dot when that day's list was finished, hollow when it was not, nothing when
  there was no list. Tapping a day shows it; past days are read only, because
  retroactively ticking yesterday would erase the consequence.

**Failure consequence.** If any of a day's tasks are left unfinished, the apps
flagged as social are blocked for the whole of the next day. This is derived
from stored state rather than written at midnight, so it cannot get stuck on
from a missed job, and it lifts by itself.

**Scope, and this is the safety requirement again.** Only packages the user
has explicitly flagged as social are ever affected. The flag is opt-in per
package; the seeded defaults are feed-shaped apps only and deliberately
exclude messengers. Phone, messages, maps, banking and transport can never be
reached by this penalty.

**Removing the social flag is a relaxation** and goes through the same 24-hour
delayed path as section 6. An instant unflag would be a trivial way out of the
lockout, which would make the whole mechanic decorative.

---

## 11. The user’s line

A quote the user writes for themselves, shown large at the bottom of the home
screen. This is the one place the two-colour rule is relaxed, deliberately,
because the line belongs to the user rather than to the app: colour (a muted
palette plus a hex field), size, and typeface are all theirs to set. Leaving
the text empty hides it entirely.

---

## 12. Honest constraints to surface in the UI

Do not oversell the enforcement. The app should be upfront that:

- Device Owner can be removed by a factory reset from recovery. This is
  high-friction, not impossible. The design goal is friction, not a prison.
- `WorkManager`'s 15-minute floor means a time limit can overrun by up to that
  long.
- Section-blocking hints break when apps redesign their feeds.
- Some banking apps refuse to run on a device with a Device Owner. If that
  happens, the fallback is to drop Device Owner and use accessibility-based
  blocking, which is softer.

---

## 13. Build and verification

- Generate and commit the Gradle wrapper.
- `./gradlew assembleDebug` must succeed cleanly.
- Play Protect warns on unsigned debug APKs; this is expected. Set up a
  release keystore and `signingConfigs` so release builds install without the
  warning.
- Before declaring done, verify on device:
  - settings is reachable in under three taps from home
  - the challenge appears and a wrong answer produces a different question
  - the app picker lists installed apps and saves a rule
  - a rule with a 1-minute limit actually suspends the app within 15 minutes
  - the blocklist screen accepts a domain and the VPN starts
  - the reading screen opens a real EPUB and lists chapters
  - with Device Owner absent, the app says so instead of failing silently

Work in vertical slices: get one screen fully working on device before moving
to the next. Do not batch-write everything and hope.
