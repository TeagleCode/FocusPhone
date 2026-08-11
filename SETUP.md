# Focus — setup

## 1. Build
Open this folder in Android Studio (or run Claude Code in VS Code here).
`./gradlew assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Install
    adb install -r app-debug.apk

## 3. Make it the home screen
Press home, choose Focus, select Always.

## 4. Device Owner  (REQUIRED for hard blocking)
Only possible on a device with **no accounts configured**.
Factory reset, skip Google sign-in entirely, enable USB debugging, then:

    adb shell dpm set-device-owner com.focus.launcher/.policy.FocusDeviceAdminReceiver

If this fails with "not allowed to set the device owner because there are
already some accounts", an account still exists. Remove it and retry.

Add accounts AFTER provisioning — that works fine.

## 5. Grant usage access
Settings > Apps > Special access > Usage access > Focus > allow.
Without this, time limits cannot be measured.

## 6. Section blocking (optional)
Settings > Accessibility > Focus > enable.
The service is scoped to only the packages listed in the app's own settings.

## 7. Site blocking (optional)
Enable from settings; Android will show a VPN consent dialog.
Note: only one VPN can be active at a time.

## Known TODO (next session)
- ReadingActivity UI (EPUB picker, chapter view, quiz flow) — logic exists,
  screen not yet written
- App picker UI in settings for choosing which packages get rules
- Domain list editor UI
- Boot receiver to re-apply policy after restart
- Periodic WorkManager job to re-evaluate limits while apps are open

## Building without a laptop

Push this folder to a GitHub repo. `.github/workflows/build.yml` builds the
APK on every push to `main`, and you can also trigger it by hand from the
Actions tab. Download the artifact from your phone browser and install it.

Nothing else is needed — no SDK, no laptop.
