package io.github.bags156.embermug.core;

/** Minimal persistent string store, implemented by SharedPreferences in the app. */
public interface KeyValueStore {

    String getString(String key);

    /** Must be durable when it returns (the app uses a synchronous commit). */
    void putString(String key, String value);

    void remove(String key);
}
