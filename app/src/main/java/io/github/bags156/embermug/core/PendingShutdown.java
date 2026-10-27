package io.github.bags156.embermug.core;

/** A persisted request to turn a specific mug's heater off the next time it connects. */
public final class PendingShutdown {

    public final String address;
    /** Wall-clock time the request was recorded, for display. */
    public final long createdAtMs;
    /** Delivery attempts so far, across connections. */
    public final int attempts;

    public PendingShutdown(String address, long createdAtMs, int attempts) {
        this.address = address;
        this.createdAtMs = createdAtMs;
        this.attempts = attempts;
    }

    public boolean isFor(String otherAddress) {
        return otherAddress != null && address.equalsIgnoreCase(otherAddress);
    }
}
