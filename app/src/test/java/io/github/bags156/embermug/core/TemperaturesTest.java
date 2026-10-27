package io.github.bags156.embermug.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TemperaturesTest {

    private static final TempUnit F = TempUnit.FAHRENHEIT;
    private static final TempUnit C = TempUnit.CELSIUS;

    @Test
    public void fahrenheitConversion() {
        assertEquals(5000, Temperatures.fahrenheitToCenti(122));
        assertEquals(5167, Temperatures.fahrenheitToCenti(125));
        assertEquals(5722, Temperatures.fahrenheitToCenti(135));
        assertEquals(6000, Temperatures.fahrenheitToCenti(140));
        assertEquals(6222, Temperatures.fahrenheitToCenti(144));
        assertEquals(212.0, Temperatures.centiToFahrenheit(10000), 1e-9);
        assertEquals(32.0, Temperatures.centiToFahrenheit(0), 1e-9);
    }

    @Test
    public void everyFahrenheitTargetRoundTripsAndIsValid() {
        for (int f = Temperatures.MIN_TARGET_F; f <= Temperatures.MAX_TARGET_F; f++) {
            int centi = Temperatures.fahrenheitToCenti(f);
            assertEquals(f, Temperatures.roundedFahrenheit(centi));
            assertTrue("valid " + f, Temperatures.isValidTarget(centi));
        }
        assertFalse(Temperatures.isValidTarget(Temperatures.fahrenheitToCenti(Temperatures.MAX_TARGET_F + 1)));
        assertFalse(Temperatures.isValidTarget(Temperatures.fahrenheitToCenti(Temperatures.MIN_TARGET_F - 1)));
    }

    @Test
    public void formatting() {
        assertEquals("135°F", Temperatures.format(5722, F));
        assertEquals("57.2°C", Temperatures.format(5722, C));
        assertEquals("57.5°C", Temperatures.format(5750, C));
        assertEquals("22.3°C", Temperatures.format(2230, C));
        assertEquals("72°F", Temperatures.format(2230, F));
    }

    @Test
    public void fahrenheitSteppingIsWholeDegreesAndClamped() {
        assertEquals(Temperatures.fahrenheitToCenti(136), Temperatures.step(5722, F, 1));
        assertEquals(Temperatures.fahrenheitToCenti(134), Temperatures.step(5722, F, -1));
        int max = Temperatures.fahrenheitToCenti(Temperatures.MAX_TARGET_F);
        int min = Temperatures.fahrenheitToCenti(Temperatures.MIN_TARGET_F);
        assertEquals(max, Temperatures.step(max, F, 1));
        assertEquals(min, Temperatures.step(min, F, -1));
    }

    @Test
    public void celsiusSteppingIsHalfDegreesAndClamped() {
        assertEquals(5750, Temperatures.step(5722, C, 1)); // snaps to 57.0 then +0.5
        assertEquals(5650, Temperatures.step(5722, C, -1));
        assertEquals(Temperatures.MAX_TARGET_CENTI, Temperatures.step(Temperatures.MAX_TARGET_CENTI, C, 1));
        assertEquals(Temperatures.MIN_TARGET_CENTI, Temperatures.step(Temperatures.MIN_TARGET_CENTI, C, -1));
    }

    @Test
    public void snappingStaysInRange() {
        assertEquals(5700, Temperatures.snap(5722, C));
        assertEquals(Temperatures.MAX_TARGET_CENTI, Temperatures.snap(9000, C));
        assertEquals(Temperatures.MIN_TARGET_CENTI, Temperatures.snap(100, C));
        assertEquals(Temperatures.fahrenheitToCenti(Temperatures.MAX_TARGET_F), Temperatures.snap(9000, F));
        assertEquals(Temperatures.fahrenheitToCenti(Temperatures.MIN_TARGET_F), Temperatures.snap(100, F));
    }

    @Test
    public void readBackConfirmation() {
        assertTrue(Temperatures.confirms(5722, 5722));
        assertTrue(Temperatures.confirms(5722, 5726));
        assertFalse(Temperatures.confirms(5722, 5730));
        assertFalse(Temperatures.confirms(5722, 0));
        assertFalse(Temperatures.confirms(5722, null));
    }

    @Test
    public void unitNamesParseSafely() {
        assertEquals(C, TempUnit.fromName("CELSIUS"));
        assertEquals(F, TempUnit.fromName("FAHRENHEIT"));
        assertEquals(F, TempUnit.fromName(null));
        assertEquals(F, TempUnit.fromName("kelvin"));
    }
}
