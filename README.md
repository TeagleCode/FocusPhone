# Focus

An Android launcher that replaces your home screen and actually stops you
opening the apps you told it to stop you opening.

It is a self-binding tool. You are deliberately making things harder for your
future self, so getting *into* the settings costs you a trigonometry problem,
and loosening any restriction takes 24 hours. Making a restriction stricter is
instant and free.

- **Blocked apps** close the moment they open, and you land back on the home
  screen with a note saying why.
- **Daily time limits** are counted to the second. When the allowance runs
  out, the app closes.
- **In-app feeds** — Reels, Shorts, TikTok's For You — get closed without
  blocking the rest of the app.
- **Websites** are blocked by a local DNS filter, so it works in every browser
  and app at once.
- **A daily agenda** sits on the home screen. Leave a day's tasks unfinished
  and the apps you flagged as social are locked for the whole of the next day.
- **Daily reading** — open an EPUB, answer five comprehension questions about
  what you actually read. Fail, and restricted apps get no allowance tomorrow.

---

## Install

Download the APK from the [latest release](../../releases/latest) and open it
on your phone.

**Android will warn you that the file is unsafe.** That is expected for
anything installed outside the Play Store. Tap through it (*More details* →
*Install anyway*, or *Install without scanning*).

### Two builds

| Flavour | Where | Site blocking |
|---|---|---|
| `full` | the APK on this page | **yes** |
| `play` | Google Play | no |

Google requires `VpnService` to be an app's core purpose, and the DNS site
filter is a secondary feature here, so the Play build ships without it rather
than arguing the point through review. The flavour drops the service from the
manifest entirely, not just the button — R8 leaves no trace of it in the
binary. Everything else is identical.

The two are signed with different keys, so you cannot update from one to the
other; switching means uninstalling first, which clears your rules.

---

## Permissions — read this, or the app will do nothing

Focus installs cleanly, opens, looks finished, and blocks **absolutely
nothing** until you complete step 2. This is the single thing everybody gets
wrong. The app's own setup screen tracks all of it live, and tells you exactly
what is missing.

### 1. Make it your home screen

Press the home button and choose **Focus**, then **Always**.

This is not cosmetic. Blocking an app works by sending you home, and home is
where the explanation appears.

### 2. Turn on the accessibility service — this is what does the blocking

**Settings → Accessibility → Installed apps → Focus → turn on.**

> **If the toggle is greyed out and unpressable, this is the bit that catches
> everyone.** Android restricts accessibility for apps installed from a file.
> To lift it:
>
> **Settings → Apps → Focus → ⋮ (three dots, top right) → Allow restricted
> settings.**
>
> Then go back and the toggle will work.

The service only ever sees the apps you have restricted or flagged. Banking,
messaging and everything else you have not opted in is invisible to it — the
system enforces that list, not the app.

### 3. Grant usage access — recommended

**Settings → Apps → Special access → Usage access → Focus → Allow.**

Time limits work without this, because Focus keeps its own record. Granting it
makes them more accurate: it lets Focus notice when you switch to an app it
does not watch, and recover time that passed while the service was off.

### 4. Optional extras

| Feature | What it needs |
|---|---|
| Website blocking | *GitHub build only.* Tap **start site filter** in *sites and in-app sections*, and allow the VPN prompt. Android only permits one VPN, so this cannot run alongside a commercial VPN. |
| Reading quizzes | An [Anthropic API key](https://console.anthropic.com/) pasted into settings. Stored on your device only, never sent anywhere but Anthropic, never shown back in full. |
| Hard blocking | Device Owner — see [SETUP.md](SETUP.md). Requires a factory reset. |

### 5. Now actually set some rules

**settings → choose apps.** Nothing is restricted by default.

---

## The gate

Getting into settings costs you a problem. A wrong answer is spent — it is
replaced by a different question, so you cannot sit and guess at the same one.

**Choose how many.** *settings → problems to enter settings*, anywhere from 1
to 10. Set it to 5 and opening settings is a genuine decision rather than a
reflex.

Problems are generated, not stored. Around sixty templates across
trigonometry, algebra, sequences, number theory, geometry, statistics and
logic, each covering hundreds or thousands of variations: law of cosines,
simultaneous equations, quadratics, logarithms, arithmetic and geometric
series, GCD and LCM, circle and solid geometry, coordinate geometry, compound
interest, probability and combinations. More than **200,000 distinct
problems** — a figure a unit test asserts, so the app cannot claim a pool it
does not have.

**The logic puzzles are solved, not scripted.** Knights and knaves, race
orderings, bridge crossings, jug measuring, weighing puzzles, pigeonhole
problems. The knight and ordering puzzles are built by generating random
constraints and then brute-forcing every possible arrangement: a puzzle is
only ever shown if exactly one arrangement satisfies it. So the deduction is
real each time rather than a scenario you learn the answer to on the third
showing — over **30,000 distinct logic puzzles**, against the eleven a written
list would have given you.

That approach costs about **48 KB** in the APK. The same number of problems
written out as text would be roughly 30 MB.

---

## The emergency code

Optional, and off unless you turn it on. With a code set, you can apply a
pending unlock immediately instead of waiting out the 24 hours — for when you
genuinely need an app now.

**It arms 24 hours after you set it.** That delay is the whole point. A code
you could set at the moment you wanted to bypass something would not be an
emergency key, it would be a cancel button on the entire app. Armed in
advance, it is a key cut before the emergency rather than during it.

Removing it takes effect at once, because giving up an escape hatch is a
tightening. The code is stored only as a salted hash.

*settings → emergency code.*

---

## What this does not do

Being honest about this matters more than sounding impressive.

- **This is friction, not a prison.** You can turn the accessibility service
  off in two taps and everything stops. That is deliberate: a tool you cannot
  escape is a tool you cannot trust with your phone. It works because
  reaching for that toggle is a decision you have to make consciously.
- **A restricted app will visibly flash up** for a fraction of a second before
  closing. Only Device Owner can stop a launch outright.
- **Section blocking breaks** when Instagram or YouTube redesign their feeds.
  The detection hints are editable inside the app so you can fix it yourself
  without waiting for a new build.
- **Time is counted while the service is running.** Turn it off and the
  minutes that pass are only recovered if usage access is granted.
- **Nothing without a rule is ever touched.** Phone, messages, maps, camera,
  banking and transport keep working no matter what — including when you fail
  a reading quiz or leave your whole agenda unfinished. There is no code path
  by which this app can leave your phone unusable in an emergency.

---

## Build from source

Requires JDK 17 or 21 and the Android SDK.

```sh
./gradlew assembleFullRelease   # APK with site blocking, for sideloading
./gradlew bundlePlayRelease     # AAB without it, for the Play Console
./gradlew testPlayDebugUnitTest # the challenge generator tests
```

Outputs land in `app/build/outputs/`. Release builds are signed with the upload
key described in `keystore.properties`, which is not in git; without it the
build falls back to the debug key, so an unsigned upload fails at the Console
rather than shipping.

Build the debug variant if you are developing, but **install a release build on
a real phone** — Compose without R8 and its baseline profile is several times
slower, which on a mid-range device is the difference between a launcher that
feels instant and one that visibly stutters.

[SPEC.md](SPEC.md) is the source of truth for behaviour. Read it before
changing anything. [play/submission.md](play/submission.md) covers publishing.
