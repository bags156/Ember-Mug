package io.github.bags156.embermug.core;

import java.util.Locale;

/**
 * Temperature conversion, formatting and target limits.
 *
 * <p>Everything is stored in centi-Celsius (hundredths of a degree), the mug's native unit.
 * Fahrenheit targets use whole degrees and Celsius targets use half degrees. Switching the display
 * unit never changes a stored value; only stepping or choosing a new target snaps to that unit's
 * grid.
 */
public final class Temperatures {

    private Temperatures() {
    }

    /** Lowest target sent to the mug: 50.0 °C (122 °F). */
    public static final int MIN_TARGET_CENTI = 5000;
    /** Highest target sent to the mug: 62.5 °C (144.5 °F). */
    public static final int MAX_TARGET_CENTI = 6250;

    /** Whole-degree Fahrenheit limits that stay inside the Celsius limits. */
    public static final int MIN_TARGET_F = 122;
    public static final int MAX_TARGET_F = 144;

    /** Celsius step size in centi-degrees (0.5 °C). */
    public static final int CELSIUS_STEP_CENTI = 50;

    /** Read-back tolerance when confirming a target write (0.05 °C). */
    public static final int CONFIRM_TOLERANCE_CENTI = 5;

    public static int fahrenheitToCenti(double fahrenheit) {
        return (int) Math.round((fahrenheit - 32.0) * 500.0 / 9.0);
    }

    public static double centiToFahrenheit(int centi) {
        return centi * 9.0 / 500.0 + 32.0;
    }

    public static int roundedFahrenheit(int centi) {
        return (int) Math.round(centiToFahrenheit(centi));
    }

    public static boolean isValidTarget(int centi) {
        return centi >= MIN_TARGET_CENTI && centi <= MAX_TARGET_CENTI;
    }

    public static int clampTarget(int centi) {
        return Math.max(MIN_TARGET_CENTI, Math.min(MAX_TARGET_CENTI, centi));
    }

    /** True when a value read back from the mug confirms the requested target. */
    public static boolean confirms(int requestedCenti, Integer readBackCenti) {
        return readBackCenti != null
                && Math.abs(readBackCenti - requestedCenti) <= CONFIRM_TOLERANCE_CENTI;
    }

    /** Snaps a target onto the grid of the given unit, staying within the target limits. */
    public static int snap(int centi, TempUnit unit) {
        if (unit == TempUnit.FAHRENHEIT) {
            int f = clampF(roundedFahrenheit(centi));
            return fahrenheitToCenti(f);
        }
        int steps = Math.round(centi / (float) CELSIUS_STEP_CENTI);
        return clampTarget(steps * CELSIUS_STEP_CENTI);
    }

    /** Moves a target by {@code steps} increments of the unit (1 °F or 0.5 °C), clamped. */
    public static int step(int centi, TempUnit unit, int steps) {
        if (unit == TempUnit.FAHRENHEIT) {
            int f = clampF(roundedFahrenheit(centi) + steps);
            return fahrenheitToCenti(f);
        }
        int snapped = snap(centi, TempUnit.CELSIUS);
        return clampTarget(snapped + steps * CELSIUS_STEP_CENTI);
    }

    /** Formats any temperature (target or reading), e.g. "135°F" or "57.5°C". */
    public static String format(int centi, TempUnit unit) {
        if (unit == TempUnit.FAHRENHEIT) {
            return roundedFahrenheit(centi) + unit.symbol;
        }
        return String.format(Locale.US, "%.1f%s", centi / 100.0, unit.symbol);
    }

    private static int clampF(int f) {
        return Math.max(MIN_TARGET_F, Math.min(MAX_TARGET_F, f));
    }
}
