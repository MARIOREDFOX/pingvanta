# Pingvanta

A native Android uptime monitor (Kotlin, Jetpack Compose, Material 3, Room, WorkManager, OkHttp).
Add websites, TCP ports or hosts to ping; Pingvanta checks them from your phone in the background
and sends a notification, and optionally a loud alarm, whenever one changes between UP and DOWN.

## Features

- HTTP(S), TCP port and ICMP ping monitors, each with its own interval, timeout and expected HTTP status codes
- Notifications on separate "online" and "offline" channels, plus an optional alarm that rings on the alarm volume
- One alert per status change, never for repeated identical results
- Dashboard with overall uptime, per-monitor cards and latest activity
- Monitor detail with response-time chart, hourly availability, 30-day uptime bars, incidents and history
- 24-hour, 7-day and 30-day uptime, outage durations and total downtime
- Background monitoring through WorkManager, with a foreground service for intervals under 15 minutes
- Light and dark themes, CSV history export, JSON import/export of monitors
- Everything stays on the phone: no account, no server, no analytics

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| OS | Linux | Tested on CachyOS (Arch). Ubuntu, Debian and Fedora work the same way. |
| JDK | 17 to 23 | JDK 17 recommended. Newer JDKs (24+) are not supported by the bundled Gradle 8.11.1. |
| Android SDK | Platform 35, build-tools 34.0.0, platform-tools | Installed for you by the setup script. |
| Phone | Android 8.0 (API 26) or newer | |

Gradle itself does not need to be installed; the project ships the Gradle wrapper.

## Setup and build

### 1. Install a JDK

```bash
sudo pacman -S jdk17-openjdk        # Arch / CachyOS / Manjaro
sudo apt install openjdk-17-jdk     # Ubuntu / Debian
sudo dnf install java-17-openjdk-devel   # Fedora
```

It does not have to be your default Java. The setup script searches `/usr/lib/jvm` and uses a
suitable one for the build only.

### 2. Clone and build

```bash
git clone https://github.com/MARIOREDFOX/pingvanta.git
cd pingvanta
./scripts/setup-and-build.sh
```

The script:

1. picks a JDK between 17 and 23;
2. downloads the Android command-line tools into `~/Android/Sdk` if no SDK is found, accepts the
   licences and installs platform 35, build-tools 34.0.0 and platform-tools (about 1 to 2 GB);
3. writes `local.properties` with the SDK path;
4. runs `./gradlew assembleDebug` and saves the full output to `build.log`.

The first build downloads Gradle and all dependencies and takes 10 to 20 minutes. Later builds
take well under a minute.

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Building manually

If you already have the Android SDK (for example from Android Studio), point the project at it and
use Gradle directly:

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk     # only if your default Java is not 17 to 23
./gradlew assembleDebug
```

The project also opens in Android Studio as a normal Gradle project.

### 3. Install on your phone

With USB debugging enabled (Settings > About phone > tap "Build number" seven times, then
Developer options > USB debugging):

```bash
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone and open it, allowing "install unknown apps" for your file
manager when asked.

### 4. First run

1. Allow notifications when the app asks.
2. Tap **Add monitor**, enter a URL such as `https://www.google.com`, choose an interval and the
   notification and alarm options, then **Start monitoring**.
3. Open **Settings** and use **Test online**, **Test offline** and **Test alarm** to confirm that
   alerts are audible.
4. For intervals under 15 minutes, leave **Live monitoring** on and tap **Allow unrestricted
   battery use** so Android does not pause checks while the phone sleeps.

The first result of a new monitor is recorded as a baseline without an alert. You are alerted from
the next change onwards.

## Tests

```bash
./gradlew testDebugUnitTest          # JVM unit tests: checkers, status-code rules, alert logic, backup
./gradlew connectedDebugAndroidTest  # needs a connected phone or emulator: engine + Room, Compose UI
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `No JDK 17-23 found` | Install JDK 17 (step 1) and re-run the script. |
| `Unsupported class file major version` | Gradle is running on a JDK newer than 23. Use the script, or set `JAVA_HOME` to JDK 17. |
| `SDK location not found` | Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`, or run the script. |
| `adb: no devices` | Enable USB debugging, reconnect the cable and accept the prompt on the phone. |
| No alerts arrive | Check Settings > Notification permission, and use the test buttons. |
| Checks stop when the screen is off | Allow unrestricted battery use, and disable any vendor "battery saver" or auto-start restriction for Pingvanta. |
| 1-minute monitor is checked every 15 minutes | Live monitoring is off or was stopped by Android. Turn it on in Settings and open the app once. |

## How monitoring works

| Type | What counts as UP |
|------|-------------------|
| HTTP(S) | A real GET request returns a status code inside the monitor's expected list (default `200-399`). A 404 or 503 is DOWN unless you list it. DNS failure, timeout, refused connection and SSL errors are DOWN with the reason recorded. |
| TCP port | The TCP handshake to `host:port` completes within the timeout. |
| Ping (ICMP) | The system `ping` tool gets an echo reply. |

Rules that keep alerts trustworthy:

- **One alert per change.** A notification or alarm is raised only when the stored status actually
  flips. Repeated checks with the same result never notify again.
- **The first result is a baseline.** A new monitor going from UNKNOWN to UP or DOWN is recorded
  without an alert; the next change alerts.
- **A failure is confirmed once.** When an UP monitor fails, it is re-checked after 3 seconds before
  being declared DOWN.
- **Phone offline is not "site down".** If the phone has no validated internet connection, checks
  are skipped, nothing is marked DOWN, and everything is re-checked when connectivity returns.

## Background execution: what Android allows

- **WorkManager is the baseline.** Each monitor has one uniquely named periodic job, so duplicates
  cannot occur, and the jobs survive app restarts and reboots.
- **WorkManager cannot run more often than every 15 minutes**, and Android may delay runs further
  (Doze, battery saver, app standby buckets). A 1-minute interval is therefore *not* achievable
  with WorkManager alone.
- **Intervals under 15 minutes use "Live monitoring"**: a foreground service with an ongoing
  notification that performs the checks on a 10-second tick. It runs only while a monitor with a
  short interval exists and the switch in Settings is on. Timing is approximate (a 1-minute monitor
  is checked roughly every 60 to 70 seconds).
- **Deep sleep still applies.** Unless Pingvanta is exempt from battery optimisation (button in
  Settings), Android can suspend network access and the service while the phone is idle. Some
  manufacturers (Xiaomi, Oppo, Vivo, Samsung, Huawei...) add their own app killers; if checks stop,
  also allow auto-start / "no restrictions" for Pingvanta in the vendor's battery settings.
- Android only lets an app start a foreground service from the foreground, after boot, or when it is
  exempt from battery optimisation. If the system stops the live service, WorkManager keeps
  checking every 15 minutes and the service resumes the next time the app is opened.

## Alerts

- Separate notification channels for "back online" and "went offline". Android owns the sound,
  vibration and importance of a channel once it exists, so Settings links straight to each
  channel's system page to change them.
- The alarm is a separate foreground service that plays on the **alarm audio stream**: it follows
  the Alarm volume and sounds even in silent mode (Do Not Disturb can still block alarms if
  configured to). It rings for 30 seconds, or with "Repeat until acknowledged" until you tap
  Acknowledge (hard stop after 15 minutes).
- To ring while the app is closed, the alarm is launched through an exact `AlarmManager` alarm,
  which is one of the cases where Android permits a background service start. If that is refused,
  Pingvanta falls back to a notification on an alarm-sound channel.

## Permissions

| Permission | Why |
|------------|-----|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Run checks; detect when the phone itself is offline. |
| `POST_NOTIFICATIONS` | Status notifications (asked on first launch on Android 13+). |
| `VIBRATE` | Alarm vibration. |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` (≤ Android 12) | Start the audible alarm from the background. |
| `RECEIVE_BOOT_COMPLETED` | Restore schedules and the live service after a reboot. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Live monitoring for intervals under 15 minutes. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The ringing alarm. |
| `WAKE_LOCK` | Keep the CPU awake while live monitoring is on. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Offer the battery-optimisation exemption from Settings. |

`USE_EXACT_ALARM` and the special-use foreground service are fine for a personally installed APK;
publishing on Google Play would require declaring and justifying both.

## Known limitations

- Checks run from the phone, so they measure reachability from the phone's network. An outage of
  your own Wi-Fi or mobile data pauses monitoring instead of reporting every site as down.
- ICMP ping relies on the device's `ping` binary. Most phones allow it; where it is blocked the app
  falls back to `InetAddress.isReachable`, which is less accurate. Many hosts drop ICMP entirely,
  so prefer HTTP or TCP monitors where possible.
- Individual check results are kept for 32 days (enough for the 30-day figures). Status changes are
  kept until the monitor is deleted.
- Cleartext `http://` URLs are allowed so that plain-HTTP sites can be monitored.

## Project layout

```
app/src/main/java/app/pingvanta/
  PingvantaApp.kt, MainActivity.kt   application, dependency container, activity
  data/        Room entities and DAOs, repository, settings (DataStore), JSON/CSV backup
  monitor/     HTTP / TCP / ping checkers, MonitorEngine (transition detection), connectivity
  background/  WorkManager scheduling and workers, live monitoring service, boot receiver
  alert/       notification channels, Notifier, alarm launcher / receiver / service
  ui/          Compose screens, view models, charts, theme
app/src/test/         JVM unit tests
app/src/androidTest/  instrumented engine and Compose UI tests
```

## Contributing

Issues and pull requests are welcome. Before opening a pull request, please make sure the project
builds (`./gradlew assembleDebug`) and the unit tests pass (`./gradlew testDebugUnitTest`).

## License

Copyright (C) 2026 MARIOREDFOX

Pingvanta is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3.

Pingvanta is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[LICENSE](LICENSE) file for the full text.

Third-party components: AndroidX, Jetpack Compose, Room, WorkManager, Kotlin, kotlinx.coroutines and
OkHttp are licensed under the Apache License 2.0. Some icon shapes are from Google's Material
Icons (Apache License 2.0).
