package io.github.bags156.embermug.core;

/**
 * Persists the pending heater-off request so it survives disconnects, process death and reboots.
 *
 * <p>The request is "armed" whenever this mug is heating under this app's watch and the
 * "turn heater off when my phone disconnects" option is on. It is written <em>before</em> any heat
 * command, so a crash between the write and its confirmation still leaves the request in place.
 * It is cleared only when the mug reports a target of zero (heater off), or when the user
 * explicitly forgets it.
 */
public final class ShutdownStore {

    static final String KEY_ADDRESS = "pending_shutdown_address";
    static final String KEY_CREATED = "pending_shutdown_created";
    static final String KEY_ATTEMPTS = "pending_shutdown_attempts";

    private final KeyValueStore store;

    public ShutdownStore(KeyValueStore store) {
        this.store = store;
    }

    public PendingShutdown load() {
        String address = store.getString(KEY_ADDRESS);
        if (address == null || address.isEmpty()) {
            return null;
        }
        return new PendingShutdown(address, parseLong(store.getString(KEY_CREATED)),
                (int) parseLong(store.getString(KEY_ATTEMPTS)));
    }

    public boolean isPendingFor(String address) {
        PendingShutdown pending = load();
        return pending != null && pending.isFor(address);
    }

    /**
     * Records a pending shutdown for {@code address}. An existing request for the same mug is kept
     * as is (its timestamp and attempt count are preserved). Returns false, without changing
     * anything, if a request for a <em>different</em> mug is still outstanding.
     */
    public boolean arm(String address, long nowMs) {
        PendingShutdown existing = load();
        if (existing != null) {
            return existing.isFor(address);
        }
        store.putString(KEY_ATTEMPTS, "0");
        store.putString(KEY_CREATED, Long.toString(nowMs));
        store.putString(KEY_ADDRESS, address);
        return true;
    }

    /** Counts one delivery attempt. No-op without a pending request. */
    public void recordAttempt() {
        PendingShutdown existing = load();
        if (existing != null) {
            store.putString(KEY_ATTEMPTS, Integer.toString(existing.attempts + 1));
        }
    }

    /** Clears the request only if it belongs to {@code address}. */
    public void clear(String address) {
        PendingShutdown existing = load();
        if (existing != null && existing.isFor(address)) {
            forget();
        }
    }

    /** Clears any request unconditionally (user chose to dismiss it). */
    public void forget() {
        store.remove(KEY_ADDRESS);
        store.remove(KEY_CREATED);
        store.remove(KEY_ATTEMPTS);
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
