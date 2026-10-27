# Hardware testing checklist

The unit tests cover protocol encoding and the safety rules, but only a real mug and phone can show whether Bluetooth behaves as expected. Work through this list on each phone/mug combination you rely on. Note the phone model, Android version and mug model with your results.

To capture logs, connect the phone with USB debugging on and run:

```sh
adb logcat -s EmberMug
```

## Setup

- [ ] First launch asks for Nearby devices (Android 12+) or Location (Android 8–11), and notifications (Android 13+).
- [ ] Denying Bluetooth permission shows the explanation with an **Open settings** button; nothing crashes.
- [ ] With Bluetooth off, **Find my mug** asks to turn it on.
- [ ] Android 8–11 with Location off: the app asks you to turn Location on.
- [ ] A mug in pairing mode appears in **Find my mug** within 15 seconds. Already-paired mugs are listed as "(paired)".
- [ ] Selecting the mug connects. If Android shows a pairing prompt, accepting it leads to a working connection.

## Readings

- [ ] Drink temperature, target, liquid state and battery appear within a few seconds of connecting.
- [ ] Putting the mug on / taking it off the coaster updates "on the charging coaster" quickly (push events) or within 12 seconds (polling).
- [ ] The status line under the readings says "Live updates from the mug" (push events active). If it says "Checking every 12 seconds", note it: push events failed to enable on this firmware.
- [ ] Switching °F / °C changes every displayed temperature; the selected target does not drift after switching back and forth several times.

## Temperature control

- [ ] With liquid in the mug: **Heat now** shows "The mug confirmed …", and the mug starts heating if the drink is below the target.
- [ ] With the heater off (target shows "Heater off"): **Heat now** turns heating back on.
- [ ] With an empty mug: **Heat now** is refused with "The mug reports it's empty".
- [ ] **Heater off** shows "Heater off. Confirmed by the mug." and the target reads "Heater off".
- [ ] While heating, − / + changes are sent after a short pause and confirmed.
- [ ] Each favorite heats to its temperature when tapped. Long-pressing saves the selected temperature; the new value survives an app restart.

## LED

- [ ] Each preset changes the mug's light and the app reports the color back.
- [ ] Custom slider colors apply with **Apply color**. Red, green and blue are not swapped.

## Background and notifications

- [ ] Lock the phone for 10+ minutes. The connection notification stays, and readings are current when you unlock.
- [ ] Swipe the app away from recents. The connection keeps running; the notification's **Heater off** and **Disconnect** actions work.
- [ ] Drink until empty: an "empty" notification appears and (with the option on) the heater is turned off and confirmed.
- [ ] Refill: a "Liquid detected" notification appears; its **Heat to …** action heats the mug.
- [ ] Heating to a target: a "Ready to drink" notification appears once.

## Disconnect safety

- [ ] With the mug heating, tap **Disconnect**. The app sends heater off, confirms it, then disconnects. The mug stops maintaining temperature.
- [ ] With the mug heating, walk out of range (or turn phone Bluetooth off). A "disconnected" notification says the heater will be turned off on reconnect. The "Heater-off pending" card appears.
- [ ] Come back in range (or turn Bluetooth on). The app reconnects, the status shows "Turning heater off…", then "Heater turned off after the connection was lost". The mug's target is zero and it does **not** start heating again on its own.
- [ ] Repeat the out-of-range test, but force-stop the app while away. Reopen it and tap **Connect**: the heater-off is delivered first.
- [ ] With a heater-off pending for mug A, try selecting mug B: the app refuses and offers to reconnect to A or forget the request.
- [ ] Turning off "Turn the heater off when my phone disconnects" clears any pending request, and disconnecting leaves the heater as it was.

## Robustness

- [ ] Toggle phone Bluetooth off and on while connected. The app shows "Bluetooth is off", then reconnects.
- [ ] Open the official Ember app while connected. Note how both apps behave (expect one to lose the connection).
- [ ] Leave the mug out of range for an hour, then return. The app reconnects without user action.
- [ ] Reboot the phone. The app does not start by itself; opening it and tapping **Connect** works.
