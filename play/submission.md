# Publishing FocusPhone to Google Play

Everything on the engineering side is done. What is left is account setup and
form-filling, most of which only you can do.

The Play Console moves its menus around regularly, so match these on the
**question being asked**, not on the exact menu path.

---

## 1. The account, and the part that will cost you two weeks

Register at [play.google.com/console](https://play.google.com/console).

- **$25**, one time, non-refundable.
- **Identity verification** — a government ID and an address. Takes anywhere
  from a few hours to a few days.

Then the constraint that catches everyone:

> **A personal developer account opened after 13 November 2023 cannot publish
> to production until it has run a closed test with at least 12 testers who
> stayed opted in for 14 continuous days.**

Twelve real Google accounts, opted in the whole time. The counter resets if you
drop below twelve. After the 14 days you *apply* for production access, and
that application is itself reviewed.

So the realistic timeline is **three to four weeks**, and the single most useful
thing you can do today is line up twelve people. Your friends who already have
the sideloaded APK are the obvious candidates — you need their Gmail addresses.

Registering as an **organisation** skips the 12-tester requirement, but needs a
D-U-N-S number for a real registered business. Not worth it unless you have one.

---

## 2. Upload

The file to upload:

```
app/build/outputs/bundle/playRelease/app-play-release.aab
```

Rebuild it any time with:

```sh
JAVA_HOME=/home/david/tools/jdk21 ./gradlew bundlePlayRelease
```

Accept **Play App Signing** when offered — it is mandatory for new apps.
Google holds the distribution key; `keystore/upload.jks` stays your upload key.

---

## 3. Store listing

All the copy is in [listing.md](listing.md). Graphics:

| Asset | Status |
|---|---|
| `icon-512.png` | ready |
| `feature-graphic-1024x500.png` | ready |
| Phone screenshots (2–8) | **still needed — plug in your phone** |

---

## 4. Declarations, with the answers

### Data safety

| Question | Answer |
|---|---|
| Does your app collect or share required user data types? | **Yes** — see below |
| Is all data encrypted in transit? | **Yes** (HTTPS) |
| Can users request data deletion? | **Yes** — uninstalling removes everything |

Declare exactly one data type:

| Field | Answer |
|---|---|
| Data type | **Files and docs** |
| Collected | Yes |
| Shared | No |
| Processed ephemerally | Yes |
| Required or optional | **Optional** |
| Purpose | **App functionality** |

This covers the reading-quiz feature, which sends a passage of the EPUB you
picked to `api.anthropic.com` to generate comprehension questions. Nothing else
in the app touches the network at all.

You could argue it is not "collection" — the data goes to Anthropic under the
user's own API key and never reaches you, and it is processed ephemerally.
Declare it anyway. Under-declaring data safety is an enforcement action;
over-declaring costs you nothing.

Everything else — your rules, times, agenda, quote, emergency-code hash — is
device-local and is **not** declared, because Play only counts data that leaves
the device.

### The rest of App content

| Question | Answer |
|---|---|
| Privacy policy | `https://teaglecode.github.io/FocusPhone/` |
| Ads | **No ads** |
| App access | All functionality available without special access |
| Content rating | Complete the IARC questionnaire — answer no to everything; result **Everyone / PEGI 3** |
| Target audience | **18 and over** |
| Advertising ID | **Not used** |
| News / government / financial / health app | No to all |
| Data deletion | No account; uninstall removes all data |

Pick 18+ for target audience. Including any band under 13 pulls the app into
the Families policy programme, which brings a large amount of extra compliance
for no benefit here.

### Sensitive permissions

Nothing to declare. The app now ships with **no** permission that requires a
declaration form:

| Permission | Status |
|---|---|
| `QUERY_ALL_PACKAGES` | **removed** — the `<queries>` element already covers a launcher |
| `VpnService` | **removed** from the Play build |
| `FOREGROUND_SERVICE_SPECIAL_USE` | **removed** with it |
| `PACKAGE_USAGE_STATS` | no declaration form; user grants it in system settings |
| `INTERNET`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` | normal permissions |

---

## 5. The accessibility service — the real risk

This is where an app like this gets rejected. There is no tick-box for it; it
is judged by a human against the Accessibility API policy, which requires that
non-accessibility use be disclosed to the user and serve a clear, user-facing
purpose.

Three things already answer that, and they are in the build:

1. **A prominent in-app disclosure.** Tapping the accessibility step in setup
   now opens a dedicated screen — *"before you turn this on"* — that spells out
   what the service reads, what it cannot see, that content is discarded after
   the block decision, and that nothing leaves the device. You have to tap
   *"i understand"* before it hands you to system settings. Google requires
   this to be in the app, not only in the privacy policy.
2. **The store listing says so**, in its own PRIVACY paragraph.
3. **The privacy policy has a dedicated section** on it.

**If Google emails asking you to justify the accessibility use**, reply with
something close to this:

> FocusPhone is a home screen replacement that blocks apps the user has
> explicitly chosen to restrict. The AccessibilityService API is used for three
> things, all of them user-facing and all configured by the user: closing an
> app the user has blocked, counting time spent in an app the user has given a
> daily limit, and detecting short-form video feeds inside apps where the user
> asked for that section to be blocked.
>
> Android provides no other API that can detect the foreground app and return
> the user to the home screen, which is why the accessibility API is required
> for the app's core function.
>
> The service is scoped at runtime via
> `AccessibilityServiceInfo.packageNames` to only the packages the user has
> restricted, so the system itself prevents it from receiving events from any
> other app. Screen content is evaluated in memory to make a block decision and
> then discarded — never stored, never logged, never transmitted. The app has
> no analytics, no ads, no third-party SDKs and no server.
>
> Users are shown a full-screen disclosure describing all of this, and must
> accept it before the app will open accessibility settings.

Do not set `android:isAccessibilityTool="true"`. It is for tools built for
users with disabilities, and claiming it falsely is its own violation. It is
correctly absent here.

---

## 6. Likely outcomes

- **Approved first time.** Plausible. The permission set is now small and the
  disclosure is thorough.
- **Asked to justify accessibility.** The most likely bump. Reply with §5 above.
- **Rejected under Device and Network Abuse.** Possible for a launcher that
  closes other apps. The counter-argument is that every block corresponds to a
  rule the user set on themselves, and that the service can be switched off in
  two taps — say so, and point at the "what this does not do" section that is
  visible in the app's own setup screen.

First reviews usually land within a few days, though a first submission from a
new account can take up to seven.

---

## 7. Before you submit — the one thing still unverified

`QUERY_ALL_PACKAGES` was removed because a launcher does not need it: the
`<queries>` element declaring `ACTION_MAIN` + `CATEGORY_LAUNCHER` already grants
visibility of every app with a launcher icon, which is every app this launcher
can show or block. That is the documented replacement for the permission.

It builds clean, but **it has not been run on a phone yet**. Install the new
build and confirm the app list still fills, then upload:

```sh
JAVA_HOME=/home/david/tools/jdk21 ./gradlew installPlayRelease
```

If the list is empty — it should not be — put the permission back in
`app/src/main/AndroidManifest.xml` and declare it as a launcher use case.

---

## 8. After it is live

`versionCode` must increase on every upload. It is `5` now; the next one is `6`.

Keep `keystore/upload.jks` and `keystore.properties` backed up somewhere that
is not this laptop. They are deliberately not in git. Losing them is
recoverable — Google can reset an upload key — but it is a support round-trip
you do not want mid-release.
