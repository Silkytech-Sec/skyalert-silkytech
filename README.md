# skyalert for Android

A native Android app that notifies you when an aircraft that is rare in Skycards enters your Skycards radar, so you can open the game and catch it.

- **Native and light.** A plain Java Android app with no third-party libraries, no ads, no analytics, and no accounts.
- **Accurate rarity.** It uses the same rarity scores the game shows, from Skycards' public model list, refreshed daily.
- **Live, tidy notifications.** Distance and direction update while the aircraft is in range, and the alert clears itself when it leaves. Tap to open Skycards, or tap Map to see it on adsb.lol.
- **New card alerts.** Paste the type codes you own, or import `skycards_user.json`, and get told about models you don't have yet.
- **Set and forget.** Quiet hours, a watch list, an ignore list, a fixed home location option, and auto-start after reboot.
- **Hands off your account.** It never logs in to Skycards or touches your account.

## Build it (no Android Studio needed)

GitHub builds the APK for you on every push.

1. Create a **private** repository on GitHub named `skyalert`. Keep it private, because it contains your signing key (see below).
2. Push this folder to it. From a terminal in this folder, run the following.
   ```sh
   git init && git add . && git commit -m "skyalert"
   git branch -M main
   git remote add origin https://github.com/YOUR-USERNAME/skyalert.git
   git push -u origin main
   ```
   With the GitHub CLI installed, `gh repo create skyalert --private --source . --push` does the same in one step.
3. Open the repository's **Actions** tab and wait for **Build skyalert APK** to go green. It takes about four minutes.
4. On your phone, open the repository's **Releases** page, download the `.apk`, and install it. Android will ask you to allow installs from your browser the first time.

Every later push produces a new release that installs over the old one and keeps your settings.

### Automatic updates (optional)

[Obtainium](https://obtainium.imranr.dev) on F-Droid can watch your repository's releases and update the app for you. Private repositories need a GitHub personal access token, which you enter in Obtainium's settings.

### Building locally instead

Install Android Studio, open this folder, let it sync, then choose **Build, Build App Bundle(s) / APK(s), Build APK(s)**. You can also install Gradle 8.11.1 and JDK 17 and run `gradle assembleRelease`. The APK lands in `app/build/outputs/apk/release/`.

## First run on your phone

1. Open skyalert and tap **Start watching**. Allow notifications and location.
2. Android then asks to let skyalert ignore battery optimization. Allow it, or alerts can stall when the screen is off.
3. Tap **Show what's in range** to see every aircraft in your radar, sorted by rarity, with `*` marking anything that would alert. Adjust **Minimum rarity** until the stars look right. The default of 8.0 is roughly every Ultra card.
4. Optional. Tap **Allow auto-start after reboot** and choose **Allow all the time**. Without it, skyalert shows a tap-to-resume reminder after a reboot instead of restarting on its own. Using a fixed location also avoids needing this.

If you were running the Termux version, stop it with `~/skyalert/stop.sh` and delete `~/.termux/boot/skyalert` so you don't get double alerts.

## Notification settings

skyalert creates three notification channels you can tune in Android's settings.

| Channel | Default | Used for |
|---|---|---|
| Rare aircraft | Heads-up, sound, vibration | A rare aircraft enters your radar |
| Live updates and quiet alerts | Silent | Distance updates, re-entries within 45 minutes, quiet hours |
| Running status | Minimized | The ongoing "skyalert is watching" notification, with a Stop button |

## Privacy

Your position is rounded to about 1 km before it is sent to the traffic API, and the precise distance is computed on the phone. The only network requests go to adsb.lol, adsb.fi, and the Skycards model list. Nothing else leaves your phone.

## About the signing key

`app/skyalert.keystore` is a key generated just for you. Android only installs an update if it is signed with the same key as the installed app, so keep this file. Anyone holding it could sign an app that installs as an update over yours, which is why the repository should stay private.

If you'd rather keep the repository public, store the key as a secret instead.

1. Run `base64 -i app/skyalert.keystore | pbcopy` on your Mac.
2. Add the copied text as a repository secret named `SKYALERT_KEYSTORE_B64`, under Settings, Secrets and variables, Actions.
3. Delete the keystore file from the repository.

The workflow uses the secret automatically when it's present.

## Limitations

- **Data coverage.** Skycards uses Flightradar24 data and skyalert uses community ADS-B networks. Coverage overlaps heavily but not perfectly. Aircraft flagged PIA or LADD are skipped because Flightradar24 usually hides them.
- **Type codes.** Matching is by ICAO type code. Aircraft that broadcast no type code are ignored.
- **Not affiliated.** skyalert is not affiliated with Skycards or Flightradar24. Live traffic comes from [adsb.lol](https://adsb.lol) (ODbL) and [adsb.fi](https://adsb.fi), credited per their terms for personal, non-commercial use.

## Project layout

```
app/src/main/java/app/skyalert/core/   Platform independent logic (tested on a plain JVM)
  Json, ModelDb, Traffic, Evaluator, Tracker, CollectionParser, QuietHours, Geo
app/src/main/java/app/skyalert/        Android layer
  MainActivity      settings screen, preview, permissions
  WatcherService    foreground service, polling loop, wake lock
  LocationWatcher   low-power location (passive + network, GPS optional)
  Notifier          notification channels and alerts
  BootReceiver      restart after reboot or update
.github/workflows/build.yml            builds and publishes the signed APK
```
