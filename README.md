# Ticker

Ticker is a single-phone Android app. While an app you choose is in front, ordinary alerts go silent, stay in the notification shade, and scroll by on a thin strip that does not take taps. Calls and alarms still interrupt. Media stays allowed, so a video’s audio keeps playing.

The first install is from Android Studio onto a phone. There is no Play listing and the app has no network permission.

## Open and install

1. Install Android SDK platform 37 and open this folder in Android Studio.
2. Run the `app` configuration on the phone. `minSdk` is 29 and `targetSdk` is 37.
3. On the setup screen, grant notification access, Do Not Disturb access, display over other apps, and usage access. Each row opens the matching system screen.
4. Choose the apps that should arm Ticker, turn the switch on, and use Preview the strip before leaving setup.

`./gradlew assembleDebug` builds the debug APK.

The phone test, including the Galaxy Z Fold 8 inner and cover displays and One UI pop-ups, is in `PHONE-TEST.md`.
