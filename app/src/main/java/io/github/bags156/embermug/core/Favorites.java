package io.github.bags156.embermug.core;

import java.util.Arrays;

/** Three editable favorite targets, stored as centi-Celsius. Immutable. */
public final class Favorites {

    public static final int COUNT = 3;

    /** 125 °F, 135 °F and 140 °F. */
    private static final int[] DEFAULTS = {
        Temperatures.fahrenheitToCenti(125),
        Temperatures.fahrenheitToCenti(135),
        Temperatures.fahrenheitToCenti(140),
    };

    private final int[] slots;

    private Favorites(int[] slots) {
        this.slots = slots;
    }

    public static Favorites defaults() {
        return new Favorites(DEFAULTS.clone());
    }

    public static int defaultFor(int index) {
        return DEFAULTS[index];
    }

    public int get(int index) {
        return slots[index];
    }

    /** Returns a copy with one slot replaced. Rejects targets outside the mug's limits. */
    public Favorites with(int index, int centi) {
        if (index < 0 || index >= COUNT) {
            throw new IndexOutOfBoundsException("Favorite slot " + index);
        }
        if (!Temperatures.isValidTarget(centi)) {
            throw new IllegalArgumentException("Target out of range: " + centi);
        }
        int[] copy = slots.clone();
        copy[index] = centi;
        return new Favorites(copy);
    }

    public String serialize() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < COUNT; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(slots[i]);
        }
        return out.toString();
    }

    /** Parses stored favorites. Missing, malformed or out-of-range slots fall back to defaults. */
    public static Favorites parse(String stored) {
        int[] result = DEFAULTS.clone();
        if (stored == null || stored.isEmpty()) {
            return new Favorites(result);
        }
        String[] parts = stored.split(",", -1);
        for (int i = 0; i < COUNT && i < parts.length; i++) {
            try {
                int value = Integer.parseInt(parts[i].trim());
                if (Temperatures.isValidTarget(value)) {
                    result[i] = value;
                }
            } catch (NumberFormatException ignored) {
                // keep the default for this slot
            }
        }
        return new Favorites(result);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Favorites && Arrays.equals(slots, ((Favorites) other).slots);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(slots);
    }
}
