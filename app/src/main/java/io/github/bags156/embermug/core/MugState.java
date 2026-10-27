package io.github.bags156.embermug.core;

/** Snapshot of what the app knows about the mug. Owned by the service; listeners get copies. */
public final class MugState {

    public enum Link {
        /** Service not running. */
        STOPPED("Not connected"),
        BLUETOOTH_OFF("Bluetooth is off"),
        CONNECTING("Connecting…"),
        WAITING_TO_RECONNECT("Connection lost. Reconnecting…"),
        SETTING_UP("Setting up…"),
        TURNING_OFF("Turning heater off (requested after a disconnect)…"),
        READY("Connected"),
        DISCONNECTING("Disconnecting…");

        public final String label;

        Link(String label) {
            this.label = label;
        }
    }

    public Link link = Link.STOPPED;
    public String address;
    public String name;

    public Integer currentCenti;
    public Integer targetCenti;
    public LiquidState liquidState;
    /** Elapsed-realtime timestamp of {@link #liquidState}; 0 when not fresh from this connection. */
    public long liquidReadAtMs;
    public Integer batteryPercent;
    public boolean charging;
    public Integer ledRgb;
    public boolean pushEventsActive;

    public int reconnectFailures;
    /** Last command result, shown to the user. */
    public String commandStatus;

    public boolean isReady() {
        return link == Link.READY;
    }

    public boolean heaterMaybeOn() {
        return targetCenti == null || targetCenti > 0;
    }

    public MugState copy() {
        MugState c = new MugState();
        c.link = link;
        c.address = address;
        c.name = name;
        c.currentCenti = currentCenti;
        c.targetCenti = targetCenti;
        c.liquidState = liquidState;
        c.liquidReadAtMs = liquidReadAtMs;
        c.batteryPercent = batteryPercent;
        c.charging = charging;
        c.ledRgb = ledRgb;
        c.pushEventsActive = pushEventsActive;
        c.reconnectFailures = reconnectFailures;
        c.commandStatus = commandStatus;
        return c;
    }
}
