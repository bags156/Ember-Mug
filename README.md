# Ember Mug for Android

A small, native Android controller for **Ember Mug / Ember Mug 2**. No account, cloud connection, analytics or Internet permission. Android 8.0+ (API 26); targets Android 16 (API 36).

**Status: initial implementation; not yet tested against a physical mug.** Successful builds do not establish Bluetooth compatibility with every phone, mug or firmware revision. This is an independent project, not affiliated with Ember.

## What it does

- Read current liquid temperature, mug target, battery/coaster state and liquid state.
- Select a target in Fahrenheit or Celsius. Display-unit changes preserve the underlying target.
- Save **three editable temperature favorites**, initially 125°F, 135°F and 140°F. Tap one to select it and request heat when connected, idle and reporting liquid. Otherwise it only changes the selection.
- **Heat now** reapplies the chosen target, including when the mug is idle. **Heater off** writes a zero target. Commands are read back before the app claims confirmation.
- Select the mug's **LED color** using RGB sliders or six color presets; read back the mug's RGB value after writing. Firmware status indications may temporarily override the selected color.
- Keep a user-started Bluetooth connection in a `connectedDevice` foreground service after switching apps or locking the screen. Receive mug push events and poll state/temperature/battery every 12 seconds as a fallback.
- Notify when the mug reports empty; optionally request heater off when empty (enabled by default).
- Reconnect automatically with bounded backoff and show a disconnect alert.
- Request and verify heater off **before an intentional disconnect** (enabled by default).
- Persist a shutdown request before heating and after an unexpected disconnect when automatic shutoff is enabled. **Send and verify heater off first on reconnection**, with no automatic return to heating. Retry unconfirmed shutdowns while the connection service runs.

## Two firmware / connection limits

**Force heater on:** the known Bluetooth protocol exposes a temperature target, not a verified direct heater override. Heat now is an immediate target request, subject to firmware decisions, liquid detection and thermal limits. It cannot bypass an empty sensor, a temperature already above target, or other firmware protections. The UI does not claim the heater is energized merely because a target write succeeded.

**Off when disconnected:** after an unexpected radio disconnect, the phone cannot transmit an immediate off command. This app alerts you and queues shutdown for the next connection. A phone shutdown, force-stop, Bluetooth being off, revoked permissions, Android stopping the service or a mug outside range can prevent delivery. **This is not a guaranteed shutoff mechanism.** The app never labels an undelivered off command as confirmed. The mug can continue maintaining its last target while disconnected. Physical power-off remains available on the mug.

## Install

1. Open the repository's **Actions → Build Android APK** workflow.
2. Open a successful run and download the **Ember-Mug-debug** artifact (GitHub sign-in may be required).
3. Unzip it, copy `app-debug.apk` to the phone and open it. Allow installation from that source if Android asks.
4. Debug builds are for personal testing. CI runners may produce different debug signing keys between runs; if an update reports a signature mismatch, uninstall the previous debug build first (this erases its settings). Use a consistent private signing key for ongoing releases. No signing keys belong in this repository.

## Connect

1. If the mug is new or factory-reset, complete initial setup in the official Ember app first. Some firmware requires this before accepting writes. This app does not change authentication keys or install firmware.
2. Close other mug controllers, including the official Ember app, to reduce competing connections.
3. Put the mug in Bluetooth pairing mode (flashing blue), keep it near the phone and tap **Find my mug**. Select its advertised name/address. Android may show a pairing prompt.
4. Allow Nearby devices and notifications. On Android 8–11, BLE discovery requires Location permission and typically the phone's Location switch to be on. The app uses no location data.
5. Add liquid and wait for the mug's liquid-state reading. Select a target or edit the three favorites. Heat now is available only with a fresh, nonempty liquid reading; the app checks the state again immediately before the write.
6. Leave the service running for background monitoring. If your phone restricts it, review its app battery/background settings. Swiping the activity away is different from force-stopping the app or stopping its foreground service in Android.

The app remembers the selected mug, favorites, display units, selected temperature and alert preferences. A pending shutdown is tied to the previous mug: reconnect and confirm it before selecting another mug. It does not start itself at boot, automatically restore a heating target, or run after a force-stop without user interaction.

## Build and verify

Install JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Open the project in Android Studio or use:

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same checks and uploads the APK and reports. Protocol and policy tests cover byte encoding, Fahrenheit conversion, LED channel order, malformed data, favorites, stale/empty heat prevention, persisted shutdown requests and retry limits. Hardware checks are in [docs/HARDWARE_TESTING.md](docs/HARDWARE_TESTING.md).

## Implementation

Plain Java 17 with Android platform views, no third-party runtime dependencies. All Bluetooth work is owned by the service and serialized on the main looper; callbacks and connection instances are checked to discard stale results. The GATT queue allows one acknowledged operation at a time. A timeout discards the connection instead of risking overlap. Optional push notifications have polling fallback. Data and service state are separate from Activity lifetime; no Activity is retained in the service.

Supported GATT service: `fc543622-236c-4c94-8fa9-944a3e5353fa`. Temperature is unsigned little-endian hundredths of Celsius on the wire, regardless of the display setting. Heater off is the two-byte value `00 00`. LED is characteristic `fc540014…`, with red, green, blue and full brightness bytes. Travel Mug, Tumbler and Cup variants have not been validated and are not claimed as supported.

Protocol references (used for interoperability; this app is an original implementation):

- [orlopau/ember-mug](https://github.com/orlopau/ember-mug), especially target temperature and liquid state documentation.
- [sopelj/python-ember-mug](https://github.com/sopelj/python-ember-mug), for maintained characteristics, liquid-state codes and RGB ordering. The earlier documentation's channel table has a green/blue ordering inconsistency; the maintained implementation uses RGB.
- [Android: communicate in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background).
- [Android: connected-device foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
