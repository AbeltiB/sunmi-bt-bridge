# SUNMI Printer Bridge

Turns a **SUNMI V2 (Android 7.1)** into a Bluetooth thermal printer that a
**phone running the third-party ticketing app** can print to — without
modifying that app.

```
Phone (third-party app)  --Bluetooth SPP / ESC-POS-->  [ this app on the SUNMI ]  --SUNMI SDK-->  built-in printer
```

The app is a transparent byte pipe: it advertises the standard SPP UUID so the
phone sees the SUNMI as an ordinary ESC/POS Bluetooth printer, and forwards
every byte it receives straight to the internal printer via
`SunmiPrinterService.sendRAWData()`. Paper width (58/80 mm) is decided by the
phone app's own formatting — the bridge doesn't touch it.

---

## 1. Prerequisites (build machine — no Android Studio)

- **JDK 17** (`java -version` → 17)
- **Android command-line tools** + these packages via `sdkmanager`:
  ```bash
  sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
  ```
- Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK dir, or create
  `local.properties` in the project root:
  ```properties
  sdk.dir=/absolute/path/to/Android/sdk
  ```
- Internet access (first build downloads Gradle 8.5, the Android Gradle Plugin
  from Google's Maven, and `com.sunmi:printerlibrary` from Maven Central).

The Gradle wrapper is bundled, so you do **not** need Gradle installed.

## 2. Build

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

If `com.sunmi:printerlibrary:1.0.23` ever fails to resolve, drop to `1.0.18`
in `app/build.gradle.kts` — the API used here is stable across those versions.

## 3. Install on the SUNMI V2

Any of:

- **ADB** (USB debugging on): `adb install -r app-debug.apk`
- **USB drive**: copy the APK across and tap it (allow "unknown sources")
- **LAN download**: host the APK and open the URL in the SUNMI browser

## 4. Operator runbook

On the **SUNMI**:

1. Open **SUNMI Printer Bridge**.
2. Tap **Test Print** → confirms the printer path works on its own (no Bluetooth
   involved). If this prints, the SUNMI side is good.
3. Tap **Set Name** (defaults to `InnerPrinter`) — this renames the SUNMI's
   Bluetooth radio so the phone app recognises it. Change it if your app expects
   a different name.
4. Tap **Start** → status should reach **LISTENING**.
5. Tap **Make Discoverable** (or pair manually from Android Settings as a
   fail-safe).

On the **phone**:

6. Pair with the SUNMI in Android **Settings → Bluetooth** (one time).
7. Open the third-party app → printer settings → scan / select the SUNMI
   (it appears under the name you set in step 3).
8. Print a ticket. It comes out of the SUNMI. Done.

Auto-start on boot is enabled, so after a reboot the SUNMI returns to
**LISTENING** with no manual step.

## 5. Verifying end-to-end / troubleshooting

- Watch the on-screen **Log**, or from the build machine:
  `adb logcat -s SunmiBtBridge`
- **Test Print works, phone can't connect** → check the SUNMI is paired and
  discoverable; some ESC/POS apps only list already-paired devices.
- **Phone connects, nothing prints** → confirm the app uses Bluetooth *Classic*
  (SPP), not BLE. The log will show `Phone connected` and `bytes relayed`; if
  bytes arrive but nothing prints, re-run Test Print to isolate the printer.
- **Printer not ready** → the SUNMI printer service binds a moment after start;
  incoming bytes are buffered and flushed once it's ready.

## 6. Project map

```
app/src/main/java/com/bs/sunmibridge/
  MainActivity.kt   control panel (status, log, buttons)
  BridgeService.kt  foreground service; wires SPP -> printer; buffering; BT name
  SppServer.kt      RFCOMM SPP server ("fake printer" the phone connects to)
  PrinterClient.kt  binds SUNMI SunmiPrinterService; printRaw() + testPrint()
  BootReceiver.kt   auto-start on boot
  BridgeBus.kt      tiny status/log bus for the UI
app/src/main/AndroidManifest.xml
```

## 7. Notes / limits

- One phone connection at a time (expected for a printer).
- Requires the phone app to speak **Bluetooth Classic SPP + ESC/POS**. If it
  turns out to use BLE or a model-locked SDK, this design won't apply — fall
  back to an external Bluetooth printer the app officially supports.
- Runs on the SUNMI (needs the SUNMI printer service); it won't print on a
  non-SUNMI device.
