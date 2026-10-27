package io.github.bags156.embermug.core;

/**
 * Reconnection schedule. The first few attempts are fast direct connections with a doubling delay.
 * After that the app switches to Android's low-power background ("auto") connection, which waits
 * for the mug to come back into range, and retries it at most once a minute if it fails.
 */
public final class ReconnectBackoff {

    private ReconnectBackoff() {
    }

    private static final long[] DIRECT_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000};

    /** Upper bound for any delay. */
    public static final long MAX_DELAY_MS = 60_000;

    /** Delay before the next attempt, given the number of consecutive failed attempts so far. */
    public static long delayMs(int consecutiveFailures) {
        if (consecutiveFailures < 0) {
            return DIRECT_DELAYS_MS[0];
        }
        if (consecutiveFailures < DIRECT_DELAYS_MS.length) {
            return DIRECT_DELAYS_MS[consecutiveFailures];
        }
        return MAX_DELAY_MS;
    }

    /** Whether the next attempt should use a background auto-connection. */
    public static boolean useAutoConnect(int consecutiveFailures) {
        return consecutiveFailures >= DIRECT_DELAYS_MS.length;
    }
}
