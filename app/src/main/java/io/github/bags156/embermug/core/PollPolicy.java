package io.github.bags156.embermug.core;

/**
 * How often the service re-reads the mug. Every poll wakes the phone's radio and the mug's, so the
 * fast rate is reserved for when someone is looking at the app. In the background the mug's push
 * events carry the changes and the poll is only a safety net.
 */
public final class PollPolicy {

    private PollPolicy() {
    }

    /** The app is on screen. */
    public static final long VISIBLE_MS = 12_000;

    /** In the background, and the mug's push events could not be enabled. */
    public static final long BACKGROUND_MS = 60_000;

    /** In the background with push events working. */
    public static final long BACKGROUND_WITH_PUSH_MS = 5 * 60_000;

    public static long intervalMs(boolean appVisible, boolean pushEventsActive) {
        if (appVisible) {
            return VISIBLE_MS;
        }
        return pushEventsActive ? BACKGROUND_WITH_PUSH_MS : BACKGROUND_MS;
    }
}
