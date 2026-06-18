# NFC Check-In

Open-source Android app for general NFC card check-in. It can be used for
classes, laboratories, workplaces, events, clubs, or other attendance needs.

The app is local-first:

- Scan NFC cards with an Android phone.
- Record visible card metadata: UID, NFC technologies, and readable NDEF data.
- Import a private CSV list of people.
- Filter people by group, section, department, team, class, room, or unit.
- Link each visible card UID to a person once.
- Check linked people in automatically on future taps.
- Export `people.csv`, `checkins.csv`, and `scans.csv`.

The app does not bypass protected card storage or decrypt private sectors. If a
card exposes only a stable UID, that UID is used as the local registration key.

## CSV Format

Choose any comma-separated CSV containing an ID column and a name column.
Column names are matched without regard to capitalization, spaces, hyphens, or
underscores.

Recognized ID headers include:

- `ID`, `Person ID`, `Student ID`, `Employee ID`, `Staff ID`
- `Member ID`, `User ID`, `Code`, `Number`

Recognized name headers include:

- `Name`, `Full Name`, `Person Name`
- `Student Name`, `Employee Name`, `Staff Name`
- `Name (EN)`, `Name (TH)`

Optional nickname headers include `Nickname`, `Preferred Name`, and
`Display Name`.

Optional group headers include:

- `Group`, `Section`, `Department`, `Team`
- `Class`, `Room`, `Unit`, `Division`

If no group column exists, everyone is placed in the `All` group.

Example:

```csv
ID,Name,Department
EMP001,Alice Rivera,Design
EMP002,Jordan Lee,Engineering
```

## First Use

1. Copy the private CSV to the phone or Google Drive.
2. Install and open the app.
3. Tap **Import CSV** and select the file.
4. Select a group when the file contains groups.
5. Tap an NFC card.
6. Search for and select the person once to link the card.
7. Future taps check that person in automatically.

## Build Without Android Studio

Push the project to GitHub and open **Actions**. The
**Build Android Debug APK** workflow produces an installable APK artifact.

For a local build with Android SDK and JDK 17:

```bash
./gradlew assembleDebug
```

## Private Data

The repository ignores everything inside `data/` except `.gitkeep`, so private
CSV files are not committed to GitHub.

The app keeps imported lists, linked cards, check-ins, and scans in private
Android app storage. Use **Export CSV** to share copies.

The included optional splitter can create one CSV per value in a `Section`
column:

```bash
python3 scripts/split_students_by_section.py "data/your-file.csv"
```

## Card Compatibility

Cards vary. Some expose only a UID, some expose NDEF data, and some use
protected storage. A secure card may randomize its visible UID, making
UID-based check-in unreliable.

Do not format official ID or access cards, even if Android reports
`NdefFormatable`.
