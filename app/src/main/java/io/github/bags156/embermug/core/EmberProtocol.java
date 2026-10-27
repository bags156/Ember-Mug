package io.github.bags156.embermug.core;

import java.util.Locale;
import java.util.UUID;

/**
 * Ember Mug Bluetooth LE protocol: UUIDs and byte encoding.
 *
 * <p>Temperatures travel on the wire as unsigned little-endian hundredths of a degree Celsius
 * ("centi-Celsius"), regardless of the unit shown in the app. A target of {@code 00 00} turns the
 * heater off. All decoders return {@code null} (or -1 for events) for malformed data instead of
 * throwing, so a bad packet can never crash the connection service.
 */
public final class EmberProtocol {

    private EmberProtocol() {
    }

    public static final UUID SERVICE = UUID.fromString("fc543622-236c-4c94-8fa9-944a3e5353fa");
    public static final UUID CURRENT_TEMP = ember(0x0002);
    public static final UUID TARGET_TEMP = ember(0x0003);
    public static final UUID BATTERY = ember(0x0007);
    public static final UUID LIQUID_STATE = ember(0x0008);
    public static final UUID PUSH_EVENTS = ember(0x0012);
    public static final UUID LED = ember(0x0014);

    /** Standard Client Characteristic Configuration descriptor, used to enable notifications. */
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Push event codes sent on {@link #PUSH_EVENTS}. */
    public static final int EVENT_BATTERY_CHANGED = 1;
    public static final int EVENT_CHARGER_CONNECTED = 2;
    public static final int EVENT_CHARGER_DISCONNECTED = 3;
    public static final int EVENT_TARGET_CHANGED = 4;
    public static final int EVENT_DRINK_TEMP_CHANGED = 5;
    public static final int EVENT_LIQUID_LEVEL_CHANGED = 7;
    public static final int EVENT_LIQUID_STATE_CHANGED = 8;

    /** Highest temperature reading accepted as plausible (120 °C). Anything above is malformed. */
    public static final int MAX_PLAUSIBLE_CENTI = 12_000;

    static UUID ember(int shortId) {
        return UUID.fromString(String.format(Locale.US, "fc54%04x-236c-4c94-8fa9-944a3e5353fa", shortId));
    }

    /** Encodes a target temperature. {@code 0} means heater off. */
    public static byte[] encodeTemperature(int centiCelsius) {
        if (centiCelsius < 0 || centiCelsius > 0xFFFF) {
            throw new IllegalArgumentException("Temperature out of range: " + centiCelsius);
        }
        return new byte[] {(byte) (centiCelsius & 0xFF), (byte) ((centiCelsius >> 8) & 0xFF)};
    }

    /** The two-byte "heater off" target. */
    public static byte[] heaterOff() {
        return new byte[] {0, 0};
    }

    /** Decodes a current or target temperature, or {@code null} if the value is malformed. */
    public static Integer decodeTemperature(byte[] value) {
        if (value == null || value.length < 2) {
            return null;
        }
        int centi = (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
        if (centi > MAX_PLAUSIBLE_CENTI) {
            return null;
        }
        return centi;
    }

    /** Decodes the battery characteristic, or {@code null} if malformed. */
    public static BatteryReading decodeBattery(byte[] value) {
        if (value == null || value.length < 2) {
            return null;
        }
        int percent = value[0] & 0xFF;
        if (percent > 100) {
            return null;
        }
        return new BatteryReading(percent, value[1] == 1);
    }

    /** Decodes a push event code, or -1 if malformed. */
    public static int decodeEvent(byte[] value) {
        if (value == null || value.length < 1) {
            return -1;
        }
        return value[0] & 0xFF;
    }

    /** Battery percentage and whether the mug is on its charging coaster. */
    public static final class BatteryReading {
        public final int percent;
        public final boolean charging;

        public BatteryReading(int percent, boolean charging) {
            this.percent = percent;
            this.charging = charging;
        }
    }
}
