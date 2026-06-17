# NFC Check-In Lite

Open-source Android app for simple student check-in with NFC student ID cards.

The first version is intentionally local-first:

- Scan an NFC card with an Android phone.
- Record visible card metadata: UID, NFC technologies, NDEF text or URI data when available.
- Register a visible card UID to a student name and student ID.
- Check students in by tapping their card again.
- Export `students.csv`, `checkins.csv`, and `scans.csv` through Android sharing.

The app does not try to bypass protected card storage or decrypt private sectors. If a student card only exposes a stable UID, the app uses that UID as the registration key.

## Requirements

- Android phone with NFC, such as Samsung S23 FE.
- For local builds: Android Studio with Android SDK installed, plus JDK 17.
- For cloud builds: a GitHub repository with Actions enabled.

## Build

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration on your phone.

If you prefer terminal builds after installing Android Studio and JDK:

```bash
./gradlew assembleDebug
```

## Build Without Android Studio

You can let GitHub build the APK for you:

1. Create a GitHub repository.
2. Push this project to the `main` branch.
3. Open the repository on GitHub.
4. Go to **Actions**.
5. Open **Build Android Debug APK**.
6. Download the `nfc-check-in-lite-debug-apk` artifact.
7. Copy `app-debug.apk` to your Android phone and install it.

On the phone, Android may ask you to allow installing apps from your browser or file manager.

## First Test

1. Install the app on your NFC phone.
2. Open the app and tap a student ID card.
3. If the card is unregistered, enter the student ID and name.
4. Tap **Save Student**.
5. Tap the card again to check in.

## What "Unregistered Card" Means

If the app shows technologies such as `IsoDep`, `MifareClassic`, `NdefFormatable`, or `NfcA`, the phone is detecting the card. Many university cards do not expose the student ID or name as readable NDEF data, so the app uses the visible UID instead.

For this kind of card, register it once by entering the student ID and name. After that, tapping the same card should check the student in automatically.

Do not format university ID cards, even if Android reports `NdefFormatable`.

## Data Files

The app keeps private local files inside Android app storage:

- `students.json`
- `checkins.csv`
- `scans.csv`

Use **Export CSV** in the app to share copies of the CSV files.

## Notes About Card Compatibility

Student cards vary. Some expose only a UID. Some expose NDEF data. Some use protected storage that Android can detect but not read without official keys. A few secure cards may randomize the visible UID, which makes UID-based check-in unreliable.
