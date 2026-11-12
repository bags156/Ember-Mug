package io.github.bags156.embermug.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LedColorTest {

    @Test
    public void encodesRedGreenBlueThenFullBrightness() {
        assertArrayEquals(new byte[] {0x12, 0x34, 0x56, (byte) 0xFF}, LedColor.encode(0x123456));
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x00, 0x00, (byte) 0xFF}, LedColor.encode(0xFF0000));
        assertArrayEquals(new byte[] {0x00, 0x00, (byte) 0xFF, (byte) 0xFF}, LedColor.encode(0x0000FF));
    }

    @Test
    public void decodesRgbOrderAndIgnoresBrightness() {
        assertEquals(Integer.valueOf(0x123456), LedColor.decode(new byte[] {0x12, 0x34, 0x56, 0x00}));
        assertEquals(Integer.valueOf(0x00FF00), LedColor.decode(new byte[] {0x00, (byte) 0xFF, 0x00}));
    }

    @Test
    public void malformedLedValuesDecodeToNull() {
        assertNull(LedColor.decode(null));
        assertNull(LedColor.decode(new byte[0]));
        assertNull(LedColor.decode(new byte[] {1, 2}));
    }

    @Test
    public void channelsClampAndSplit() {
        assertEquals(0xFF0080, LedColor.rgb(300, -5, 128));
        assertEquals(0x12, LedColor.red(0x123456));
        assertEquals(0x34, LedColor.green(0x123456));
        assertEquals(0x56, LedColor.blue(0x123456));
    }

    @Test
    public void comparisonIgnoresAlphaBits() {
        assertTrue(LedColor.sameColor(0xFF123456, 0x123456));
        assertFalse(LedColor.sameColor(0x123456, 0x123457));
        assertEquals("#FF6A00", LedColor.hex(0xFFFF6A00));
    }

    @Test
    public void everyPresetHasAName() {
        assertEquals(LedColor.PRESETS.length, LedColor.PRESET_NAMES.length);
    }
}
