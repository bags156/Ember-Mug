package io.github.bags156.embermug.core;

/** Liquid state reported by the mug (characteristic fc540008). */
public enum LiquidState {
    UNKNOWN(0, "Unknown"),
    EMPTY(1, "Empty"),
    FILLING(2, "Filling"),
    COLD(3, "Cold (heater off)"),
    COOLING(4, "Cooling to target"),
    HEATING(5, "Heating"),
    AT_TARGET(6, "At target temperature"),
    WARM(7, "Warm (heater off)");

    public final int code;
    public final String label;

    LiquidState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** True when the mug reports liquid in it. Unknown and empty both count as "no". */
    public boolean hasLiquid() {
        return code >= FILLING.code;
    }

    /**
     * Decodes the characteristic. Returns {@code null} for missing data and {@link #UNKNOWN} for
     * codes this app does not recognise (treated as "not safe to heat").
     */
    public static LiquidState fromBytes(byte[] value) {
        if (value == null || value.length < 1) {
            return null;
        }
        int code = value[0] & 0xFF;
        for (LiquidState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
