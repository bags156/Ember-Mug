package io.github.bags156.embermug.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class FavoritesTest {

    @Test
    public void defaultsAre125_135_140Fahrenheit() {
        Favorites favorites = Favorites.defaults();
        assertEquals(125, Temperatures.roundedFahrenheit(favorites.get(0)));
        assertEquals(135, Temperatures.roundedFahrenheit(favorites.get(1)));
        assertEquals(140, Temperatures.roundedFahrenheit(favorites.get(2)));
        assertEquals("5167,5722,6000", favorites.serialize());
    }

    @Test
    public void parseRoundTrips() {
        Favorites favorites = Favorites.parse("5000,6250,5500");
        assertEquals(5000, favorites.get(0));
        assertEquals(6250, favorites.get(1));
        assertEquals(5500, favorites.get(2));
        assertEquals(favorites, Favorites.parse(favorites.serialize()));
    }

    @Test
    public void badStoredSlotsFallBackToDefaults() {
        assertEquals(Favorites.defaults(), Favorites.parse(null));
        assertEquals(Favorites.defaults(), Favorites.parse(""));
        Favorites mixed = Favorites.parse("abc,9999,5100");
        assertEquals(Favorites.defaultFor(0), mixed.get(0));
        assertEquals(Favorites.defaultFor(1), mixed.get(1));
        assertEquals(5100, mixed.get(2));
        Favorites partial = Favorites.parse("5100");
        assertEquals(5100, partial.get(0));
        assertEquals(Favorites.defaultFor(1), partial.get(1));
        assertEquals(Favorites.defaultFor(2), partial.get(2));
    }

    @Test
    public void withReplacesOneSlotAndLeavesOriginalUnchanged() {
        Favorites original = Favorites.defaults();
        Favorites edited = original.with(1, 5500);
        assertEquals(5500, edited.get(1));
        assertEquals(original.get(0), edited.get(0));
        assertEquals(original.get(2), edited.get(2));
        assertEquals(Favorites.defaultFor(1), original.get(1));
    }

    @Test
    public void withRejectsInvalidInput() {
        try {
            Favorites.defaults().with(0, Temperatures.MAX_TARGET_CENTI + 1);
            fail("out-of-range target accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            Favorites.defaults().with(Favorites.COUNT, 5500);
            fail("bad slot accepted");
        } catch (IndexOutOfBoundsException expected) {
            // ok
        }
    }
}
