# Focus — Device Owner setup

Everyday setup lives in [README.md](README.md). This file covers the one thing
that cannot be done from inside the app: **Device Owner**.

You do not need this. The accessibility guard described in the README blocks
apps on an ordinary phone with no reset and no ADB. Device Owner is a second,
stronger layer for people who want it.

---

## What it buys you

| | Accessibility guard | Device Owner |
|---|---|---|
| Blocked app opens | closes a fraction of a second later | never launches at all |
| Setup cost | one toggle | factory reset |
| Turning it off | two taps in Settings | `adb` command |

With Device Owner, `DevicePolicyManager.setPackagesSuspended` makes Android
itself refuse to start the app, and it shows a system dialog explaining why.
There is no window where the app flashes up.

Both layers run together if both are available.

---

## The catch

Device Owner can only be provisioned on a device with **zero accounts
configured**. Not "no Google account" — no accounts of any kind, including
Samsung, WhatsApp and anything else that registers one.

In practice that means a factory reset, and skipping every sign-in during
setup. You can add all your accounts back afterwards; provisioning only checks
at the moment you run the command.

To see whether your device is eligible:

```sh
adb shell dumpsys account | grep -c "Account {"
```

Anything other than `0` and the command below will fail.

---

## Provisioning

1. Factory reset the phone and skip **every** account sign-in.
2. Enable Developer options: **Settings → About phone → Software information**,
   tap **Build number** seven times.
3. **Settings → Developer options → USB debugging → on.**
4. Plug into a computer and accept the *Allow USB debugging* prompt. Confirm
   it worked:

   ```sh
   adb devices        # should list your device as "device", not "unauthorized"
   ```

5. Install and provision:

   ```sh
   adb install -r FocusPhone-v0.1.apk
   adb shell dpm set-device-owner com.focus.launcher/.policy.FocusDeviceAdminReceiver
   ```

   Success looks like:

   ```
   Active admin set to component {com.focus.launcher/...FocusDeviceAdminReceiver}
   ```

6. Sign back into your accounts.

Verify at any time with:

```sh
adb shell dumpsys device_policy | grep -i "device owner"
```

The app's setup screen also reports it live.

---

## If it fails

**"not allowed to set the device owner because there are already some
accounts"** — an account still exists. Remove every one under
*Settings → Accounts*, then retry. Some vendor accounts are stubborn enough
that another reset is quicker.

**Some banking apps refuse to run on a device with a Device Owner**, since
they treat it as a managed-device signal. If you hit that, remove it:

```sh
adb shell dpm remove-active-admin com.focus.launcher/.policy.FocusDeviceAdminReceiver
```

and rely on the accessibility guard, which is softer but has no such problem.

---

## Installing over ADB is worth it either way

Even without Device Owner, `adb install` avoids Android's *restricted
settings* lockout — the one that greys out the accessibility toggle for apps
installed from a file. If you have a cable handy, it saves you a detour.
