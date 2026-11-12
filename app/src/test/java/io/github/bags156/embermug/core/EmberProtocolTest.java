package io.github.bags156.embermug.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class EmberProtocolTest {

    @Test
    public void characteristicUuidsMatchTheEmberService() {
        assertEquals("fc543622-236c-4c94-8fa9-944a3e5353fa", EmberProtocol.SERVICE.toString());
        assertEquals("fc540002-236c-4c94-8fa9-944a3e5353fa", EmberProtocol.CURRENT_TEMP.toString());
        assertEquals("fc540003-236c-4c94-8fa9-944a3e5353fa", EmberProtocol.TARGET_TEMP.toString());
        assertEquals("fc540007-236c-4c94-8fa9-944a3e5353fa", EmberProtocol.BATTERY.toString());
        assertEquals("fc540008-236c-4c94-8fa9-944a3e5353fa", EmberProtocol.LIQUID_STATE.toString());
        assertEquals("fc540012-236c-4c94-8fa9-944a3e5353fa", EmberProtocol.PUSH_EVENTS.toString());
        assertEquals("fc540014-236c-4c94-8fa9-944a3e5353fa", EmberProtocol.LED.toString());
    }

    @Test
    public void temperatureIsLittleEndianCentiCelsius() {
        // 57.22 °C (135 °F) = 5722 = 0x165A
        assertArrayEquals(new byte[] {0x5A, 0x16}, EmberProtocol.encodeTemperature(5722));
        assertEquals(Integer.valueOf(5722), EmberProtocol.decodeTemperature(new byte[] {0x5A, 0x16}));
        // 100.00 °C = 10000 = 0x2710
        assertEquals(Integer.valueOf(10000), EmberProtocol.decodeTemperature(new byte[] {0x10, 0x27}));
    }

    @Test
    public void encodeDecodeRoundTripsEveryValidTarget() {
        for (int centi = Temperatures.MIN_TARGET_CENTI; centi <= Temperatures.MAX_TARGET_CENTI; centi++) {
            assertEquals(Integer.valueOf(centi),
                    EmberProtocol.decodeTemperature(EmberProtocol.encodeTemperature(centi)));
        }
    }

    @Test
    public void heaterOffIsTwoZeroBytes() {
        assertArrayEquals(new byte[] {0, 0}, EmberProtocol.heaterOff());
        assertArrayEquals(EmberProtocol.heaterOff(), EmberProtocol.encodeTemperature(0));
        assertEquals(Integer.valueOf(0), EmberProtocol.decodeTemperature(EmberProtocol.heaterOff()));
    }

    @Test
    public void encodeRejectsOutOfRangeValues() {
        try {
            EmberProtocol.encodeTemperature(-1);
            fail("negative accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            EmberProtocol.encodeTemperature(0x10000);
            fail("overflow accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void malformedTemperaturesDecodeToNull() {
        assertNull(EmberProtocol.decodeTemperature(null));
        assertNull(EmberProtocol.decodeTemperature(new byte[0]));
        assertNull(EmberProtocol.decodeTemperature(new byte[] {0x5A}));
        assertNull(EmberProtocol.decodeTemperature(new byte[] {(byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    public void extraTemperatureBytesAreIgnored() {
        assertEquals(Integer.valueOf(5722), EmberProtocol.decodeTemperature(new byte[] {0x5A, 0x16, 0x01}));
    }

    @Test
    public void batteryDecoding() {
        EmberProtocol.BatteryReading charging = EmberProtocol.decodeBattery(new byte[] {80, 1});
        assertEquals(80, charging.percent);
        assertTrue(charging.charging);

        EmberProtocol.BatteryReading notCharging = EmberProtocol.decodeBattery(new byte[] {55, 0, 9, 9});
        assertEquals(55, notCharging.percent);
        assertFalse(notCharging.charging);

        assertNull(EmberProtocol.decodeBattery(null));
        assertNull(EmberProtocol.decodeBattery(new byte[] {50}));
        assertNull(EmberProtocol.decodeBattery(new byte[] {101, 0}));
        assertNull(EmberProtocol.decodeBattery(new byte[] {(byte) 0xFF, 0}));
    }

    @Test
    public void pushEventDecoding() {
        assertEquals(EmberProtocol.EVENT_LIQUID_STATE_CHANGED, EmberProtocol.decodeEvent(new byte[] {8}));
        assertEquals(255, EmberProtocol.decodeEvent(new byte[] {(byte) 0xFF}));
        assertEquals(-1, EmberProtocol.decodeEvent(new byte[0]));
        assertEquals(-1, EmberProtocol.decodeEvent(null));
    }

    @Test
    public void liquidStateDecoding() {
        assertEquals(LiquidState.EMPTY, LiquidState.fromBytes(new byte[] {1}));
        assertEquals(LiquidState.HEATING, LiquidState.fromBytes(new byte[] {5}));
        assertEquals(LiquidState.AT_TARGET, LiquidState.fromBytes(new byte[] {6}));
        assertEquals(LiquidState.WARM, LiquidState.fromBytes(new byte[] {7}));
        assertEquals(LiquidState.UNKNOWN, LiquidState.fromBytes(new byte[] {42}));
        assertNull(LiquidState.fromBytes(new byte[0]));
        assertNull(LiquidState.fromBytes(null));
    }

    @Test
    public void onlyRealLiquidStatesCountAsLiquid() {
        assertFalse(LiquidState.UNKNOWN.hasLiquid());
        assertFalse(LiquidState.EMPTY.hasLiquid());
        assertTrue(LiquidState.FILLING.hasLiquid());
        assertTrue(LiquidState.COLD.hasLiquid());
        assertTrue(LiquidState.COOLING.hasLiquid());
        assertTrue(LiquidState.HEATING.hasLiquid());
        assertTrue(LiquidState.AT_TARGET.hasLiquid());
        assertTrue(LiquidState.WARM.hasLiquid());
    }
}
