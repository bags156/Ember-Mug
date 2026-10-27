package io.github.bags156.embermug.core;

/**
 * Decides whether the app may request heat. Heating is only requested with a fresh reading that
 * says the mug has liquid in it; the service also re-reads the liquid state immediately before
 * writing a target and runs this check again on that reading.
 */
public final class HeatGate {

    private HeatGate() {
    }

    /** A liquid reading older than this is considered stale. */
    public static final long MAX_LIQUID_AGE_MS = 30_000;

    public enum Decision {
        ALLOWED("OK"),
        NOT_READY("The mug isn't connected."),
        SHUTDOWN_PENDING("Turning the heater off first (requested after a disconnect)."),
        NO_READING("Waiting for the mug's liquid reading."),
        STALE_READING("The liquid reading is out of date. Try again in a moment."),
        EMPTY("The mug reports it's empty. Add liquid first."),
        UNKNOWN_STATE("The mug hasn't detected liquid yet.");

        public final String message;

        Decision(String message) {
            this.message = message;
        }
    }

    /**
     * @param connected true when the mug is connected and its services are set up
     * @param shutdownPending true while a heater-off request is being delivered on this connection
     * @param state latest liquid reading, or null if none
     * @param readingAtMs elapsed-realtime timestamp of that reading (0 if none)
     * @param nowMs current elapsed-realtime timestamp
     */
    public static Decision check(boolean connected, boolean shutdownPending, LiquidState state,
            long readingAtMs, long nowMs) {
        if (!connected) {
            return Decision.NOT_READY;
        }
        if (shutdownPending) {
            return Decision.SHUTDOWN_PENDING;
        }
        if (state == null || readingAtMs <= 0) {
            return Decision.NO_READING;
        }
        long age = nowMs - readingAtMs;
        if (age < 0 || age > MAX_LIQUID_AGE_MS) {
            return Decision.STALE_READING;
        }
        if (state == LiquidState.EMPTY) {
            return Decision.EMPTY;
        }
        if (!state.hasLiquid()) {
            return Decision.UNKNOWN_STATE;
        }
        return Decision.ALLOWED;
    }
}
