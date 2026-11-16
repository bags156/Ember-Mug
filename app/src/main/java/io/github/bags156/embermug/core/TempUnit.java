package io.github.bags156.embermug.core;

/** Display unit. The mug itself always works in Celsius. */
public enum TempUnit {
    FAHRENHEIT("°F"),
    CELSIUS("°C");

    public final String symbol;

    TempUnit(String symbol) {
        this.symbol = symbol;
    }

    public static TempUnit fromName(String name) {
        return CELSIUS.name().equals(name) ? CELSIUS : FAHRENHEIT;
    }
}
