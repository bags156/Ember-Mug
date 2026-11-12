package io.github.bags156.embermug.core;

/** Rules for delivering a pending heater-off request. */
public final class ShutdownPolicy {

    private ShutdownPolicy() {
    }

    /** Heater-off attempts per connection before the connection is dropped and re-established. */
    public static final int MAX_ATTEMPTS_PER_CONNECTION = 3;

    /** Delay between attempts on the same connection. */
    public static final long RETRY_DELAY_MS = 1500;

    public enum AfterAttempt {
        /** The mug confirmed the heater is off: clear the request and continue normally. */
        CLEAR,
        /** Not confirmed: try again on this connection. */
        RETRY,
        /** Not confirmed after the per-connection limit: reconnect and try again. */
        RECONNECT
    }

    /** True when a request must be delivered before anything else on this connection. */
    public static boolean mustSendOffFirst(PendingShutdown pending, String connectedAddress) {
        return pending != null && pending.isFor(connectedAddress);
    }

    /** True when choosing {@code newAddress} would abandon an undelivered request for another mug. */
    public static boolean blocksSelecting(PendingShutdown pending, String newAddress) {
        return pending != null && !pending.isFor(newAddress);
    }

    /** The mug confirms "off" only by reading back a target of exactly zero. */
    public static boolean confirmsOff(Integer readBackTarget) {
        return readBackTarget != null && readBackTarget == 0;
    }

    public static AfterAttempt afterAttempt(boolean confirmedOff, int attemptsThisConnection) {
        if (confirmedOff) {
            return AfterAttempt.CLEAR;
        }
        return attemptsThisConnection < MAX_ATTEMPTS_PER_CONNECTION
                ? AfterAttempt.RETRY
                : AfterAttempt.RECONNECT;
    }

    /** Arm a request whenever the option is on and the mug has a non-zero target. */
    public static boolean shouldArm(boolean offOnDisconnectEnabled, Integer targetCenti) {
        return offOnDisconnectEnabled && targetCenti != null && targetCenti > 0;
    }
}
