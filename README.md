# EV Insight Lab -Tube

YouTube on the Android Auto screen, for parked use and passengers. While the car is moving the car screen goes audio-only. Made by Sheran Rajaguru, based on [AA Browser](https://github.com/kododake/AABrowser) (GPL-3.0).

- **Download page (share this link):** https://sheranr.github.io/ev-insight-lab-tube/
- **Direct APK:** https://github.com/sheranr/ev-insight-lab-tube/releases/latest/download/EV-Insight-Lab-Tube.apk

## How updates reach people

1. You push a higher `versionCode` to `main`. GitHub Actions builds and signs the APK, publishes it as a GitHub Release, and rewrites `docs/ota/version.json`.
2. On launch (at most every 3 hours) and from ⚙ → *Check for updates*, the app reads `version.json`. If there's a newer `versionCode`, it offers **Update now**, downloads the APK, checks its SHA-256 and installs it with Android's package installer. Settings and bookmarks are kept.
3. The download page always serves the latest release.

No Play Store and no cost: GitHub Pages and Releases are free for public repos.

## Publishing an update

1. Make the change in `app/`. Every push to `main` runs **Build check**. Failed build logs land on the `ci-logs` branch.
2. In `app/build.gradle.kts` raise `versionCode` by 1 and set `versionName` (e.g. `11` / `1.0.1`).
3. Write what changed in `release-notes.txt`. This is shown in the in-app update dialog.
4. Commit and push to `main`. The release workflow sees the higher `versionCode` and publishes it:
   ```sh
   git commit -am "v1.0.1" && git push origin main
   ```
5. About 10 minutes later the release is live and phones see the update.

To force everyone below a version to update (no "Later" button), run `tools/write_ota.py` with `--min-supported <versionCode>` and push.

## Signing: keep this safe

- `signing/release.jks.enc` is the release key, encrypted. The password is in the GitHub secret `SIGNING_PASSWORD`, and Sheran keeps an offline backup of the key and password.
- Every update **must** be signed with this key, or Android refuses to install it over the existing app. If the key or password is lost, everyone has to uninstall and reinstall.
- Package name: `lk.sheran.evinsightlab.tube`. Certificate SHA-256: `FF:A1:CF:A3:E2:93:3F:1D:6E:DD:0F:FB:7D:A4:CF:F1:F2:70:0F:27:06:AD:88:78:D3:98:E3:E2:94:C4:46:2E`

## Car integration and limits

- The app uses AA Browser's unofficial Android Auto technique: users enable *Unknown sources* in Android Auto developer settings. It's not a Google-approved video app, and an Android Auto update could break it.
- Drive-safe lock (`app/src/main/java/com/kododake/aabrowser/ev/DriveSafeMonitor.kt`): on the car screen, GPS speed ≥ 8 km/h covers the picture. It uncovers only after a fresh fix shows < 3 km/h and the car has been stopped for 60 s. With no location permission, or Location off, the car screen stays covered. The phone's own screen is never locked.
- `distractionOptimized` is set (required: without it Android Auto greys out the icon). So Android Auto does not block the app while moving, and DriveSafeMonitor is the safety layer.
- **CarPlay / iPhone:** not possible. Apple doesn't allow third-party video apps on CarPlay or installing apps from a link.

## Build locally

JDK 21, Android SDK (compileSdk 37). `bash gradlew :app:assembleDebug`.

## License

GPL-3.0-or-later. See `LICENSE` and `UPSTREAM_README.md`. Upstream base: kododake/AABrowser @ 092954e.
