# skyalert for Android

A native Android app that notifies you when an aircraft that is rare in Skycards enters your Skycards radar, so you can open the game and catch it.

- **Native and light.** A plain Java Android app with no third-party libraries, no ads, no analytics, and no accounts.
- **Accurate rarity.** It uses the same rarity scores the game shows, from Skycards' public model list, refreshed daily.
- **Live, tidy notifications.** Distance and direction update while the aircraft is in range, and the alert clears itself when it leaves. Tap to open Skycards, or tap Map to see it on adsb.lol.
- **New card alerts.** Paste the type codes you own, or import `skycards_user.json`, and get told about models you don't have yet.
- **Set and forget.** Quiet hours, a watch list, an ignore list, a fixed home location option, and auto-start after reboot.
- **Hands off your account.** It never logs in to Skycards or touches your account.

## First run on your phone

1. Open skyalert and tap **Start watching**. Allow notifications and location.
2. Android then asks to let skyalert ignore battery optimization. Allow it, or alerts can stall when the screen is off.
3. Tap **Show what's in range** to see every aircraft in your radar, sorted by rarity, with `*` marking anything that would alert. Adjust **Minimum rarity** until the stars look right. The default of 8.0 is roughly every Ultra card.
4. Optional. Tap **Allow auto-start after reboot** and choose **Allow all the time**. Without it, skyalert shows a tap-to-resume reminder after a reboot instead of restarting on its own. Using a fixed location also avoids needing this.

## Notification settings

skyalert creates three notification channels you can tune in Android's settings.

| Channel | Default | Used for |
|---|---|---|
| Rare aircraft | Heads-up, sound, vibration | A rare aircraft enters your radar |
| Live updates and quiet alerts | Silent | Distance updates, re-entries within 45 minutes, quiet hours |
| Running status | Minimized | The ongoing "skyalert is watching" notification, with a Stop button |

## Privacy

Your position is rounded to about 1 km before it is sent to the traffic API, and the precise distance is computed on the phone. The only network requests go to adsb.lol, adsb.fi, and the Skycards model list. Nothing else leaves your phone.

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
