# SUNMI Printer Bridge

Turns a **SUNMI V2 (Android 7.1)** into a Bluetooth thermal printer that a
**phone running the third-party ticketing app** can print to — without
modifying that app. Built for a fleet of these running unattended, 24/7,
reporting into [sunmi-fleet-dashboard](../sunmi-fleet-dashboard).

```
Phone (third-party app)  --Bluetooth SPP / ESC-POS-->  [ this app on the SUNMI ]  --SUNMI SDK-->  built-in printer
                                                                |
                                                                +--HTTPS heartbeat--> fleet dashboard
```

The bridge itself is a transparent byte pipe: it advertises the standard SPP
UUID so the phone sees the SUNMI as an ordinary ESC/POS Bluetooth printer, and
forwards every byte it receives straight to the internal printer via
`SunmiPrinterService.sendRAWData()`. Paper width (58/80 mm) is decided by the
phone app's own formatting — the bridge doesn't touch it.

Beyond that core pipe, the app is set up to survive being deployed to a lot of
devices with nobody watching them: it auto-requests the OS permissions it
needs, restarts itself if killed, and phones home so you can see the whole
fleet's status from one dashboard instead of visiting every unit.

---

## 1. Prerequisites (build machine — no Android Studio)

- **JDK 17** (`java -version` → 17)
- **Android command-line tools** + these packages via `sdkmanager`:
  ```bash
  sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
  ```
- Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK dir, or add
  `sdk.dir=/absolute/path/to/Android/sdk` to `local.properties` (see below).
- Internet access (first build downloads Gradle 8.5, the Android Gradle Plugin
  from Google's Maven, and `com.sunmi:printerlibrary` from Maven Central).

The Gradle wrapper is bundled, so you do **not** need Gradle installed.

## 2. Configure `local.properties`

Not committed to git — create it in the project root:

```properties
sdk.dir=/absolute/path/to/Android/sdk

# Fleet dashboard heartbeat — omit both to build with heartbeat reporting
# disabled (the app still works standalone, it just won't phone home).
HEARTBEAT_URL=https://your-dashboard.example/api/heartbeat
HEARTBEAT_SECRET=same-value-as-the-dashboard's-HEARTBEAT_SECRET-env-var

# Release signing — omit to fall back to the Android debug key (fine for
# one-off test installs, NOT fine for a fleet you intend to update later).
RELEASE_STORE_FILE=keystore/release.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=sunmibridge
RELEASE_KEY_PASSWORD=...
```

### About the release keystore

`keystore/release.keystore` (gitignored) is the signing key for every release
build. **Generate it once and keep it — losing it, or building future
releases with a different key, means every already-deployed SUNMI can't
install the update and needs a manual uninstall + reinstall instead**, wiping
whatever local state it had. Back the keystore file and its passwords up
somewhere durable (password manager, secrets vault), not just on this build
machine.

To generate one:

```bash
keytool -genkeypair -v -keystore keystore/release.keystore -alias sunmibridge \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
```

## 3. Build

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk — debug-signed
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk — release-signed
```

If `com.sunmi:printerlibrary:1.0.23` ever fails to resolve, drop to `1.0.18`
in `app/build.gradle.kts` — the API used here is stable across those versions.

Bump `versionCode`/`versionName` in `app/build.gradle.kts` on every release
you intend to push to already-deployed devices.

## 4. Install on the SUNMI V2

Any of:

- **ADB** (USB debugging on): `adb install -r app-release.apk`
- **USB drive**: copy the APK across and tap it (allow "unknown sources")
- **LAN download**: host the APK and open the URL in the SUNMI browser

## 5. Operator runbook

On the **SUNMI**:

1. Open **SUNMI Printer Bridge**.
2. Tap **Test Print** → confirms the printer path works on its own (no
   Bluetooth involved). If this prints, the SUNMI side is good.
3. Tap **Start**. This now does several things automatically:
   - Starts the Bluetooth SPP server (status reaches **LISTENING**).
   - Requests **discoverable mode** — the OS will show a one-time "Allow this
     app to make your device discoverable?" prompt; tap **Yes**.
   - Requests exemption from **battery optimization** — another one-time OS
     prompt ("Let app stay connected in the background?"); tap **Yes**. This
     matters a lot for a 24/7 kiosk device — without it, the OS can suspend or
     kill the bridge in the background.
   - Starts reporting a heartbeat to the fleet dashboard, if configured at
     build time (see §2).
4. The Bluetooth name defaults to a per-device-unique `InnerPrinter-XXXX`
   (derived from the device's Android ID) so a fleet of SUNMIs doesn't all
   advertise the same name. Tap **Set Name** to apply it (or to set a
   different name if the phone app expects one specifically). **Note:**
   renaming only affects phones that pair *after* the rename — a phone
   already paired under the old name keeps showing it until it forgets and
   re-pairs the device.

On the **phone**:

5. Pair with the SUNMI in Android **Settings → Bluetooth** (one time, using
   whatever name is currently set — see the note above).
6. Open the third-party app → printer settings → scan / select the SUNMI.
7. Print a ticket. It comes out of the SUNMI. Done.

Auto-start on boot is enabled, so after a reboot the SUNMI returns to
**LISTENING** (and reporting heartbeats) with no manual step.

## 6. Fleet resilience

Three independent layers keep an unattended unit printing:

1. **`START_STICKY`** — Android's own service-restart mechanism; the fastest
   path back after the OS kills the process.
2. **`WatchdogReceiver`** — a self-rescheduling alarm that checks every 5
   minutes whether the bridge is running and restarts it if not. A backstop
   for cases where `START_STICKY` gets suppressed (some OEM battery managers
   do this even with the optimization exemption granted).
3. **Battery-optimization exemption** (§5, step 3) — reduces how often either
   of the above needs to kick in at all.

None of this survives the operator tapping **Stop** — that's treated as
deliberate and cancels the watchdog too. Tap **Start** again to re-arm
everything.

## 7. Fleet monitoring (heartbeat)

If `HEARTBEAT_URL`/`HEARTBEAT_SECRET` were set at build time (§2),
`HeartbeatSender` POSTs this device's live status every 60s:

```json
{
  "deviceId": "<Android ID>",
  "name": "<current Bluetooth name>",
  "serverState": "LISTENING | STOPPED | STARTING | CONNECTED | ERROR",
  "printerReady": true,
  "appVersion": "1.1"
}
```

It runs for the whole lifetime of the bridge service — independent of
whether the operator has tapped Start — so the dashboard can tell "device
alive but bridge stopped" apart from "device unreachable." Each tick reports
the *current* state; there's no local persistence for missed heartbeats
while offline — a device that reconnects after a Wi-Fi drop just resumes
reporting on the next tick, and the dashboard's "last seen" reflects that
naturally.

## 8. Verifying end-to-end / troubleshooting

- Watch the on-screen **Log**, or from the build machine:
  `adb logcat -s SunmiBtBridge`
- **Test Print works, phone can't connect** → check the SUNMI is paired and
  discoverable; some ESC/POS apps only list already-paired devices.
- **Phone connects, nothing prints** → confirm the app uses Bluetooth *Classic*
  (SPP), not BLE. The log will show `Phone connected` and `bytes relayed`; if
  bytes arrive but nothing prints, re-run Test Print to isolate the printer.
- **Printer not ready** → the SUNMI printer service binds a moment after start;
  incoming bytes are buffered and flushed once it's ready.
- **Device not showing on the fleet dashboard** → confirm `HEARTBEAT_URL`/
  `HEARTBEAT_SECRET` were set at build time (an unconfigured build silently
  no-ops heartbeat reporting — check the log for "Heartbeat disabled"), and
  that the SUNMI has internet access.

## 9. Project map

```
app/src/main/java/com/bs/sunmibridge/
  MainActivity.kt      control panel (status, log, buttons)
  BridgeService.kt     foreground service; wires SPP -> printer; buffering; BT name;
                        auto-discoverable/battery-exemption requests; watchdog scheduling
  SppServer.kt         RFCOMM SPP server ("fake printer" the phone connects to)
  PrinterClient.kt     binds SUNMI SunmiPrinterService; printRaw() + testPrint()
  HeartbeatSender.kt   periodic status POST to the fleet dashboard
  WatchdogReceiver.kt  self-rescheduling alarm; restarts the bridge if it died
  BootReceiver.kt      auto-start on boot
  BridgeBus.kt         tiny status/log bus for the UI
app/src/main/AndroidManifest.xml
```

## 10. Notes / limits

- One phone connection at a time (expected for a printer).
- Requires the phone app to speak **Bluetooth Classic SPP + ESC/POS**. If it
  turns out to use BLE or a model-locked SDK, this design won't apply — fall
  back to an external Bluetooth printer the app officially supports.
- Runs on the SUNMI (needs the SUNMI printer service); it won't print on a
  non-SUNMI device.
- No access control on who can connect and print — any paired phone (or,
  during the discoverable window, any nearby phone) can send print jobs. Fine
  for a single trusted ticketing app; worth revisiting before wider exposure.
- `HEARTBEAT_SECRET` is compiled into the APK (`BuildConfig`) and recoverable
  by anyone with the file. It stops casual/accidental heartbeat spam, not a
  determined attacker with the APK in hand.
