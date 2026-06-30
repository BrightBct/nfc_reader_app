# NFC Check-In for GitHub Pages

This branch adds a static browser version of the check-in tool. It is designed for USB NFC readers that behave like a keyboard and type the card UID into the focused input.

## Deploy

1. Push this branch to GitHub.
2. Open the repository settings.
3. Go to Pages.
4. Choose `Deploy from a branch`.
5. Select `codex/github-pages-web` and `/root`.

## Use

1. Open the GitHub Pages URL.
2. Import a CSV roster.
3. Confirm the ID, name, and group columns.
4. Select a group.
5. Keep the scan box focused.
6. Tap a card on the USB reader.
7. Link unknown UIDs to people once.

Data stays in the browser's local storage. The app exports separate CSV files for linked people, successful check-ins, and raw scans.

This web app does not talk to PC/SC readers directly. If the reader does not type the UID into a normal text field, this static GitHub Pages version will not be enough by itself.

Some USB readers output a four-byte card UID as a decimal number instead of hex bytes. For example, the Android app may show `4F:D0:4B:E8` while a USB reader types `3897282639`. The web app converts that decimal reader output back to `4F:D0:4B:E8` before saving or checking in.
