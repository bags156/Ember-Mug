package io.github.bags156.embermug;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.bags156.embermug.core.Favorites;
import io.github.bags156.embermug.core.KeyValueStore;
import io.github.bags156.embermug.core.LedColor;
import io.github.bags156.embermug.core.TempUnit;
import io.github.bags156.embermug.core.Temperatures;

/** App preferences, kept in one private SharedPreferences file. */
final class AppSettings implements KeyValueStore {

    static final String NOTIFY_EMPTY = "notify_empty";
    static final String OFF_WHEN_EMPTY = "off_when_empty";
    static final String OFF_ON_DISCONNECT = "off_on_disconnect";
    static final String NOTIFY_TARGET = "notify_target";
    static final String NOTIFY_DISCONNECT = "notify_disconnect";

    private static final String DEVICE_ADDRESS = "device_address";
    private static final String DEVICE_NAME = "device_name";
    private static final String UNIT = "unit";
    private static final String SELECTED_TARGET = "selected_target";
    private static final String FAVORITES = "favorites";
    private static final String LED = "led_rgb";
    private static final String SERVICE_SHOULD_RUN = "service_should_run";

    private final SharedPreferences prefs;

    AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("ember_mug", Context.MODE_PRIVATE);
    }

    // KeyValueStore: synchronous commits, because the pending heater-off request must be on disk
    // before the app sends a heat command.

    @Override
    public String getString(String key) {
        return prefs.getString(key, null);
    }

    @Override
    public void putString(String key, String value) {
        prefs.edit().putString(key, value).commit();
    }

    @Override
    public void remove(String key) {
        prefs.edit().remove(key).commit();
    }

    String deviceAddress() {
        return prefs.getString(DEVICE_ADDRESS, null);
    }

    String deviceName() {
        return prefs.getString(DEVICE_NAME, null);
    }

    void setDevice(String address, String name) {
        prefs.edit().putString(DEVICE_ADDRESS, address).putString(DEVICE_NAME, name).apply();
    }

    TempUnit unit() {
        return TempUnit.fromName(prefs.getString(UNIT, null));
    }

    void setUnit(TempUnit unit) {
        prefs.edit().putString(UNIT, unit.name()).apply();
    }

    int selectedTarget() {
        int value = prefs.getInt(SELECTED_TARGET, Favorites.defaultFor(1));
        return Temperatures.isValidTarget(value) ? value : Favorites.defaultFor(1);
    }

    void setSelectedTarget(int centi) {
        prefs.edit().putInt(SELECTED_TARGET, Temperatures.clampTarget(centi)).apply();
    }

    Favorites favorites() {
        return Favorites.parse(prefs.getString(FAVORITES, null));
    }

    void setFavorites(Favorites favorites) {
        prefs.edit().putString(FAVORITES, favorites.serialize()).apply();
    }

    int ledColor() {
        return prefs.getInt(LED, LedColor.PRESETS[1]);
    }

    void setLedColor(int rgb) {
        prefs.edit().putInt(LED, rgb & 0xFFFFFF).apply();
    }

    boolean flag(String key) {
        // Every alert and safety option defaults to on.
        return prefs.getBoolean(key, true);
    }

    void setFlag(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    boolean serviceShouldRun() {
        return prefs.getBoolean(SERVICE_SHOULD_RUN, false);
    }

    void setServiceShouldRun(boolean value) {
        prefs.edit().putBoolean(SERVICE_SHOULD_RUN, value).commit();
    }
}
