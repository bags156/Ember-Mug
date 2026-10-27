# Ember Mug for Android

A small, native Android controller for **Ember Mug / Ember Mug 2**. No account, cloud connection, analytics or Internet permission. Android 8.0+ (API 26); targets Android 16 (API 36).

**Status: initial implementation; not yet tested against a physical mug.** Successful builds and unit tests do not establish Bluetooth compatibility with every phone, mug or firmware revision. This is an independent project, not affiliated with Ember.

## What it does

- Shows the drink temperature, the mug's target, liquid state, battery and whether it's on the charging coaster.
- Select a target in **Fahrenheit or Celsius** (1 °F or 0.5 °C steps, 122–144 °F / 50–62.5 °C). Switching the display unit never changes the stored target.
- **Three editable favorites**, initially 125 °F, 135 °F and 140 °F. Tap one to select it and, when connected with a fresh reading that the mug has liquid, send it to the mug. Otherwise it only changes the selection. Long-press a favorite to save the currently selected temperature into it.
- **Heat now** sends the selected target, including when the heater is off. **Heater off** writes a zero target. While the mug is heating, changing the selection with − / + is sent automatically after a short pause. Every command is read back from the mug before the app reports it as confirmed.
- **LED color**: RGB sliders, six presets and an Apply button. The app reads the color back after writing it. Firmware status indications (charging, low battery, pairing) may temporarily override it.
- **Background connection**: a user-started `connectedDevice` foreground service keeps the mug connected after you switch apps or lock the screen. It subscribes to the mug's push events and also polls temperature, target, liquid state and battery every 12 seconds as a fallback. The status notification has **Heater off** and **Disconnect** actions.
- **Notifications** (each can be turned off): mug empty; liquid detected again (with a "Heat to …" action); drink reached the target; mug disconnected; heater-off results.
- **Turn heater off when the mug is empty** (on by default).
- **Turn heater off when the phone disconnects** (on by default). See the limits below.
- Reconnects automatically: quick direct attempts with a doubling delay, then Android's low-power background connection that waits for the mug to come back into range. No delay exceeds one minute.

## Two firmware / connection limits

**Force heater on:** the known Bluetooth protocol exposes a temperature target, not a direct heater switch. Heat now is an immediate target request, subject to firmware decisions, liquid detection and thermal limits. It cannot bypass an empty sensor, liquid that's already hotter than the target, or other firmware protections. The app never claims the heater is running just because a target write succeeded.

**Off when disconnected:** when you tap Disconnect, the app sends heater off and waits for the mug to confirm it before closing the connection. After an *unexpected* disconnect (out of range, Bluetooth off), the phone cannot reach the mug, so it cannot send anything at that moment. Instead:

1. Whenever the mug is heating with this option on, the app keeps a heater-off request on disk. It is written **before** each heat command, so it survives a crash, the app being stopped by Android, or a reboot.
2. When the connection drops, the app alerts you and keeps trying to reconnect.
3. On the next connection to that mug, the **first** thing it does is send heater off and read it back. It retries up to three times per connection, then reconnects and tries again. It never turns heating back on by itself.
4. The request is cleared only when the mug reports a target of zero, or if you choose **Forget request** (for example after turning the mug off by hand).

Until that reconnection happens, the mug keeps maintaining its last target on its own. A phone shutdown, force-stop, Bluetooth being off, revoked permissions, Android stopping the service, or the mug staying out of range can all delay or prevent delivery. **This is not a guaranteed shutoff mechanism.** Physical power-off on the mug always works.

## Install

1. Open the repository's **Actions → Build Android APK** workflow.
2. Open a successful run and download the **Ember-Mug-debug** artifact (GitHub sign-in may be required).
3. Unzip it, copy `app-debug.apk` to the phone and open it. Allow installation from that source if Android asks.
4. Debug builds are for personal testing. CI runners may produce different debug signing keys between runs; if an update reports a signature mismatch, uninstall the previous debug build first (this erases its settings). Use a consistent private signing key for ongoing releases. No signing keys belong in this repository.

## Connect

1. If the mug is new or factory-reset, complete initial setup in the official Ember app first. Some firmware requires this before accepting writes. This app does not change authentication keys or install firmware.
2. Close other mug controllers, including the official Ember app, to reduce competing connections.
3. Put the mug in Bluetooth pairing mode (light flashing blue), keep it near the phone and tap **Find my mug**. Select it from the list. Android may show a pairing prompt.
4. Allow Nearby devices and notifications. On Android 8–11, Bluetooth scanning requires Location permission and the phone's Location switch to be on. The app uses no location data.
5. Add liquid and wait for the mug's liquid reading. Pick a target or a favorite. Heat now is only sent with a reading from the last 30 seconds that says the mug has liquid in it, and the app reads the liquid state again immediately before the write.
6. Leave the connection running for background monitoring. If your phone stops it, allow unrestricted battery use for the app (the **Background and battery settings** button opens the right page). Swiping the app away from recents does not stop the connection; force-stopping it does.

The app remembers the selected mug, favorites, display unit, selected temperature, LED color and alert preferences. A pending heater-off request is tied to its mug: the app won't switch to another mug until that request is delivered, unless you choose to forget it. The app does not start itself at boot or restore a heating target on its own. If Android restarts the service after stopping it, the app reconnects only if you had left the connection running, and any pending heater-off request is delivered first.

## Build and verify

Install JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Open the project in Android Studio or use:

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same checks and uploads the APK and reports. Unit tests cover byte encoding, Fahrenheit conversion and rounding, LED channel order, malformed data, favorites, stale/empty heat prevention, persisted shutdown requests, retry limits and reconnect backoff. Hardware checks are in [docs/HARDWARE_TESTING.md](docs/HARDWARE_TESTING.md).

## Implementation

Plain Java 17 with Android platform views, no third-party runtime dependencies. The UI is built in code (`MainActivity`); all Bluetooth work is owned by `EmberService` and serialized on the main looper. Each connection (`MugConnection`) runs one acknowledged GATT operation at a time; a timeout discards the connection instead of risking overlapping operations. Callbacks from stale connections are discarded. Push notifications are optional, with polling as the fallback. Protocol and safety rules live in plain-Java classes under `core/` so they can be unit tested without a device.

Supported GATT service: `fc543622-236c-4c94-8fa9-944a3e5353fa`.

| Characteristic | Use |
|---|---|
| `fc540002…` | Current drink temperature (read) |
| `fc540003…` | Target temperature (read/write). Unsigned little-endian hundredths of °C, regardless of display unit. `00 00` = heater off |
| `fc540007…` | Battery: percent, charging flag |
| `fc540008…` | Liquid state: 1 empty, 2 filling, 3 cold, 4 cooling, 5 heating, 6 at target, 7 warm |
| `fc540012…` | Push events (notify) |
| `fc540014…` | LED: red, green, blue, brightness (written as `FF`) |

Travel Mug, Tumbler and Cup variants have not been validated and are not claimed as supported.

Protocol references (used for interoperability; this app is an original implementation):

- [orlopau/ember-mug](https://github.com/orlopau/ember-mug), especially target temperature, push event and liquid state documentation.
- [sopelj/python-ember-mug](https://github.com/sopelj/python-ember-mug), for maintained characteristics, liquid-state codes and RGB ordering. The earlier documentation's channel table has a green/blue ordering inconsistency; the maintained implementation uses RGB.
- [Android: communicate in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background).
- [Android: connected-device foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
