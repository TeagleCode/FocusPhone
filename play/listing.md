# Play Store listing copy

> **Parked.** FocusPhone is not going on the Play Store — the $25
> registration was not worth it for an app distributed to a handful of
> friends. These notes are kept because the engineering they describe is
> already done and the account requirements will not have changed much if
> that decision is ever revisited.

Paste-ready. Character counts are against Google's limits.

---

## App name — 10 / 30

```
FocusPhone
```

The on-device label stays `Focus`, so the home screen is unchanged. Only the
store listing uses the longer name.

---

## Short description — 74 / 80

```
The apps you blocked stay blocked. A launcher that enforces your own rules.
```

---

## Full description — 2,847 / 4,000

```
FocusPhone is a home screen replacement that actually closes the apps you told
it to close.

Most screen-time tools show you a warning and let you tap past it. This one
sends you back to the home screen, every time, and tells you which rule you
just hit.

It is a self-binding tool. You are deliberately making things harder for your
future self, so the app is built around one rule: making a restriction
stricter is instant and free, and loosening one costs you 24 hours.


WHAT IT BLOCKS

• Blocked apps — close the moment they open.
• Daily time limits — counted to the second. When the allowance runs out, the
  app closes.
• In-app feeds — Reels, Shorts and For You pages get closed without blocking
  the rest of the app, so you keep your messages and lose the scroll.
• Unfinished days — leave your agenda incomplete and the apps you flagged as
  social are locked for the whole of the next day.


THE HOME SCREEN

A clock, your apps, and nothing else competing for attention. Below the search
bar sits your daily agenda, so unlocking your phone shows you what you meant
to do today. There is a month calendar, and a quote you write yourself,
displayed large at the bottom in the font, size and colour you choose.


THE GATE

Opening settings costs you a problem. Not a confirmation dialog — an actual
trigonometry or logic problem, and a wrong answer is spent, replaced by a
different question so you cannot guess at the same one twice.

You choose how many. Set it to five and opening settings becomes a decision
rather than a reflex.

Problems are generated rather than stored: around sixty templates across
trigonometry, algebra, sequences, number theory, geometry, statistics and
probability, adding up to more than 200,000 distinct problems.

The logic puzzles are solved, not scripted. Knights and knaves, race
orderings, bridge crossings, jug measuring, weighing puzzles. Each one is
built by generating random constraints and then checking every possible
arrangement, and it is only shown if exactly one arrangement works. The
deduction is real each time instead of a scenario you memorise on the third
showing.


THE EMERGENCY CODE

Optional, and off unless you turn it on. With a code set you can apply a
pending unlock immediately instead of waiting out the 24 hours.

It arms 24 hours after you set it, and that delay is the entire point. A code
you could set at the moment you wanted to bypass something would just be a
cancel button on the app. Armed in advance, it is a key cut before the
emergency rather than during it.


DAILY READING

Optional. Open an EPUB, read, then answer five comprehension questions about
what you actually read. Requires your own Anthropic API key.


HONEST LIMITS

• This is friction, not a prison. You can switch the accessibility service off
  and everything stops. That is deliberate — a tool you cannot escape is a
  tool you cannot trust with your phone. It works because reaching for that
  toggle is a conscious decision.
• A restricted app will visibly flash up for a fraction of a second before it
  closes.
• Section blocking depends on how an app lays out its screens, so it can break
  when Instagram or YouTube redesign. The detection hints are editable inside
  the app, so you can fix it yourself.
• Nothing without a rule is ever touched. Phone, messages, maps, camera and
  banking keep working no matter what, including when you fail a reading quiz
  or leave your whole agenda unfinished.


PRIVACY

No accounts, no analytics, no ads, no trackers, no third-party SDKs. Your
rules, times and agenda never leave the device. The app makes no network
requests at all unless you turn on reading quizzes with your own API key.

The accessibility service is scoped to the apps you select, and Android
enforces that scope. Apps you have not restricted are invisible to it.

Open source: github.com/TeagleCode/FocusPhone
```

---

## Categorisation

| Field | Value |
|---|---|
| App or game | App |
| Category | **Productivity** |
| Tags | Focus & goals, Habits, Home screen |

Personalization is the conventional category for a launcher, but the reason
someone installs this is behavioural, not cosmetic. Productivity puts it
next to the blockers it competes with.

---

## Contact details

| Field | Value |
|---|---|
| Email | `david.beridze.2011@gmail.com` |
| Website | `https://github.com/TeagleCode/FocusPhone` |
| Privacy policy | `https://teaglecode.github.io/FocusPhone/` |

The contact email is shown publicly on the listing. Swap in a dedicated
address if you would rather not publish your personal one — it also has to
match the address on the privacy policy page.

---

## Graphics

| Asset | File | Spec |
|---|---|---|
| App icon | `brand/icon-512.png` | 512×512 PNG ✓ |
| Feature graphic | `brand/banner.png` | 1024×500 PNG ✓ |
| Phone screenshots | `docs/screenshots/` | 2–8, min 320px, max 3840px ✓ |

Screenshots have to come off a real device — Play requires them to show the
actual app, and a launcher with no apps installed on an emulator would look
barren and misleading. Plan: home screen with agenda and quote, the block
notice, the settings gate mid-problem, the app picker with limits set, and
the pending-unlock countdown.
