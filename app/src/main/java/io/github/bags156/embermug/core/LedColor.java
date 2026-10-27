package io.github.bags156.embermug.core;

import java.util.Locale;

/**
 * LED color helpers. Colors are packed as 0xRRGGBB. On the wire the LED characteristic
 * (fc540014) is four bytes: red, green, blue, brightness (always written as 0xFF).
 */
public final class LedColor {

    private LedColor() {
    }

    public static final int[] PRESETS = {0xFFFFFF, 0xFF6A00, 0xFF0000, 0x00FF00, 0x0000FF, 0xA020F0};
    public static final String[] PRESET_NAMES = {"White", "Orange", "Red", "Green", "Blue", "Purple"};

    public static int rgb(int red, int green, int blue) {
        return (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    public static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int blue(int rgb) {
        return rgb & 0xFF;
    }

    public static byte[] encode(int rgb) {
        return new byte[] {(byte) red(rgb), (byte) green(rgb), (byte) blue(rgb), (byte) 0xFF};
    }

    /** Decodes the mug's LED value (RGB order) or returns {@code null} if malformed. */
    public static Integer decode(byte[] value) {
        if (value == null || value.length < 3) {
            return null;
        }
        return rgb(value[0] & 0xFF, value[1] & 0xFF, value[2] & 0xFF);
    }

    public static boolean sameColor(int a, int b) {
        return (a & 0xFFFFFF) == (b & 0xFFFFFF);
    }

    public static String hex(int rgb) {
        return String.format(Locale.US, "#%06X", rgb & 0xFFFFFF);
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }
}
