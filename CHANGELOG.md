# Changelog

## 1.0.0 (2026-11-03)
- Moved to github.com/sheranr/ev-insight-lab-tube with a download page and automatic releases.
- Drive-safe lock: on the Android Auto screen the picture is covered while the car moves (GPS), audio continues; video returns after 60 s stopped. Fails closed without location permission or with Location off. Phone screen is never locked.
- Self-update: reads docs/ota/version.json, downloads the APK from GitHub Releases, checks SHA-256, installs through Android's PackageInstaller.
- ⚙ menu: hide/show picture, check for updates, share download link, about & safety.
- Built-in version check now points at this repo instead of upstream AABrowser.
- Release builds (R8) signed with a permanent release key. Users of 0.x must uninstall once (0.x used a lost debug key).

## 0.4 (2026-10-03, built with ChatGPT)
- Compact floating Back / YouTube / Reload / ⚙ buttons; manual "hide picture" cover.
## 0.3 — custom launcher icon. 0.2 — renamed app, YouTube home, telemetry removed, background resource errors silenced.
