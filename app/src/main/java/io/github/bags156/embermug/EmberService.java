package io.github.bags156.embermug;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.bags156.embermug.core.EmberProtocol;
import io.github.bags156.embermug.core.HeatGate;
import io.github.bags156.embermug.core.LedColor;
import io.github.bags156.embermug.core.LiquidState;
import io.github.bags156.embermug.core.MugState;
import io.github.bags156.embermug.core.PendingShutdown;
import io.github.bags156.embermug.core.ReconnectBackoff;
import io.github.bags156.embermug.core.ShutdownPolicy;
import io.github.bags156.embermug.core.ShutdownStore;
import io.github.bags156.embermug.core.Temperatures;

/**
 * Owns the mug connection for as long as the user wants it, independent of the screen.
 *
 * <p>Runs as a {@code connectedDevice} foreground service, started when the user connects and
 * stopped only when the user disconnects. All state lives on the main looper.
 */
// Permissions: Bluetooth is only touched after BtPermissions.canConnect() passes; a revoked
// runtime permission kills the process.
@SuppressLint("MissingPermission")
public final class EmberService extends Service implements MugConnection.Listener {

    private static final String TAG = "EmberMug";
    private static final String PREFIX = "io.github.bags156.embermug.action.";

    static final String ACTION_CONNECT = PREFIX + "CONNECT";
    static final String ACTION_DISCONNECT = PREFIX + "DISCONNECT";
    static final String ACTION_HEATER_OFF = PREFIX + "HEATER_OFF";
    static final String ACTION_HEAT_SELECTED = PREFIX + "HEAT_SELECTED";
    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_NAME = "name";

    static final long POLL_INTERVAL_MS = 12_000;
    private static final long STOP_TIMEOUT_MS = 8_000;
    private static final long NOTIFICATION_REFRESH_MS = 500;
    private static final int MAX_UNSUPPORTED_RETRIES = 3;

    /** Receives state on the main thread. */
    interface StateListener {
        void onMugState(MugState state);

        /** The service is about to stop; drop the reference to it. */
        void onServiceStopping();
    }

    final class LocalBinder extends Binder {
        EmberService service() {
            return EmberService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<StateListener> listeners = new ArrayList<>();
    private final MugState state = new MugState();

    private AppSettings settings;
    private ShutdownStore shutdowns;
    private BluetoothDevice device;
    private MugConnection connection;

    private boolean foreground;
    private boolean stopping;
    private boolean finished;
    private int lastStartId;
    private boolean receiverRegistered;
    private boolean notificationRefreshPending;

    private int offAttemptsThisConnection;
    private boolean heatInFlight;
    private boolean offInFlight;
    private boolean ledInFlight;
    private Integer queuedLed;
    /** Target value we already sent a "ready to drink" alert for. */
    private Integer notifiedTargetFor;

    private final Runnable poll = this::pollNow;
    private final Runnable reconnect = this::reconnectNow;
    private final Runnable notificationRefresh = () -> {
        notificationRefreshPending = false;
        if (foreground && !finished) {
            getSystemService(NotificationManager.class)
                    .notify(Notifications.ID_SERVICE, buildServiceNotification());
        }
    };

    // ---- lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        shutdowns = new ShutdownStore(settings);
        Notifications.ensureChannels(this);
        registerSystemReceiver();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent != null ? intent.getAction() : null;
        if (action == null || ACTION_CONNECT.equals(action)) {
            if (finished) {
                // A new connect request arrived while the previous session was shutting down.
                finished = false;
                stopping = false;
            }
            String address = intent != null ? intent.getStringExtra(EXTRA_ADDRESS) : null;
            String name = intent != null ? intent.getStringExtra(EXTRA_NAME) : null;
            if (address == null) {
                // Restarted by Android after it stopped the process: resume only if the user had
                // left the connection running.
                address = settings.serviceShouldRun() ? settings.deviceAddress() : null;
                name = settings.deviceName();
            }
            // startForegroundService() requires startForeground() even if we stop right away.
            if (!enterForeground()) {
                return START_NOT_STICKY;
            }
            if (address == null) {
                finishStop(null, null);
                return START_NOT_STICKY;
            }
            connectTo(address, name);
            return START_STICKY;
        }

        boolean running = foreground && !stopping;
        switch (action) {
            case ACTION_DISCONNECT:
                if (running) {
                    userDisconnect();
                }
                break;
            case ACTION_HEATER_OFF:
                if (running) {
                    heaterOff("");
                }
                break;
            case ACTION_HEAT_SELECTED:
                if (running) {
                    Notifications.cancel(this, Notifications.ID_LIQUID);
                    heatNow(settings.selectedTarget());
                }
                break;
            default:
                break;
        }
        if (!foreground) {
            // Started from a stale notification while no session is running.
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (!finished) {
            finished = true;
            stopping = true;
            closeConnection();
        }
        for (StateListener listener : new ArrayList<>(listeners)) {
            listener.onServiceStopping();
        }
        listeners.clear();
        main.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(systemReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private boolean enterForeground() {
        if (foreground) {
            refreshNotification();
            return true;
        }
        try {
            Notification notification = buildServiceNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifications.ID_SERVICE, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(Notifications.ID_SERVICE, notification);
            }
            foreground = true;
            return true;
        } catch (RuntimeException e) {
            // Missing Bluetooth permission, or Android refused a background start.
            Log.w(TAG, "Could not start the foreground service", e);
            stopSelf();
            return false;
        }
    }

    // ---- API for the activity (main thread) ----

    void addListener(StateListener listener) {
        if (finished) {
            // This instance is shutting down; tell the caller not to use it.
            listener.onMugState(state.copy());
            listener.onServiceStopping();
            return;
        }
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        listener.onMugState(state.copy());
    }

    void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    MugState currentState() {
        return state.copy();
    }

    /** Called after the user changes a setting that affects the service. */
    void onSettingsChanged() {
        if (device != null) {
            String address = device.getAddress();
            if (!settings.flag(AppSettings.OFF_ON_DISCONNECT)) {
                shutdowns.clear(address);
            } else if (state.isReady() && ShutdownPolicy.shouldArm(true, state.targetCenti)) {
                shutdowns.arm(address, System.currentTimeMillis());
            }
        }
        publish();
    }

    /** Requests heat to {@code centi}. Checks liquid twice: cached, then a fresh read. */
    void heatNow(int centi) {
        final int target = Temperatures.clampTarget(centi);
        final MugConnection c = connection;
        long now = SystemClock.elapsedRealtime();
        HeatGate.Decision decision = HeatGate.check(c != null && isConnected(), isDeliveringShutdown(),
                state.liquidState, state.liquidReadAtMs, now);
        if (decision != HeatGate.Decision.ALLOWED) {
            setStatus(decision.message);
            return;
        }
        if (heatInFlight) {
            setStatus("Already sending a temperature to the mug…");
            return;
        }
        heatInFlight = true;
        setStatus("Checking for liquid…");
        c.read(EmberProtocol.LIQUID_STATE, (ok, value) -> {
            if (c != connection) {
                return;
            }
            LiquidState fresh = ok ? LiquidState.fromBytes(value) : null;
            if (fresh != null) {
                onLiquid(fresh);
            }
            long checkedAt = SystemClock.elapsedRealtime();
            HeatGate.Decision recheck = HeatGate.check(isConnected(), isDeliveringShutdown(), fresh,
                    fresh != null ? checkedAt : 0, checkedAt);
            if (recheck != HeatGate.Decision.ALLOWED) {
                heatInFlight = false;
                setStatus("Not heating. " + recheck.message);
                return;
            }
            if (settings.flag(AppSettings.OFF_ON_DISCONNECT)) {
                // Persist the shutdown request before the heat command leaves the phone.
                shutdowns.arm(device.getAddress(), System.currentTimeMillis());
            }
            final String label = Temperatures.format(target, settings.unit());
            setStatus("Sending " + label + "…");
            c.write(EmberProtocol.TARGET_TEMP, EmberProtocol.encodeTemperature(target), written ->
                    c.read(EmberProtocol.TARGET_TEMP, (readOk, readValue) -> {
                        if (c != connection) {
                            return;
                        }
                        heatInFlight = false;
                        Integer reported = readOk ? EmberProtocol.decodeTemperature(readValue) : null;
                        if (reported != null) {
                            onTarget(reported);
                        }
                        if (Temperatures.confirms(target, reported)) {
                            setStatus("The mug confirmed " + label
                                    + ". It heats while it has liquid below that temperature.");
                        } else {
                            setStatus("The mug didn't confirm " + label + describe(reported));
                        }
                        readLiquid(c);
                        readCurrent(c);
                    }));
        });
    }

    /** Writes a zero target and reads it back. {@code reason} is appended to the status text. */
    void heaterOff(String reason) {
        final MugConnection c = connection;
        if (c == null || !state.isReady()) {
            setStatus("Connect to the mug first.");
            return;
        }
        if (offInFlight) {
            return;
        }
        offInFlight = true;
        setStatus("Turning the heater off…");
        c.write(EmberProtocol.TARGET_TEMP, EmberProtocol.heaterOff(), ok ->
                c.read(EmberProtocol.TARGET_TEMP, (readOk, value) -> {
                    if (c != connection) {
                        return;
                    }
                    offInFlight = false;
                    Integer reported = readOk ? EmberProtocol.decodeTemperature(value) : null;
                    if (reported != null) {
                        onTarget(reported);
                    }
                    if (ShutdownPolicy.confirmsOff(reported)) {
                        setStatus("Heater off" + reason + ". Confirmed by the mug.");
                    } else {
                        setStatus("The mug didn't confirm heater off" + describe(reported));
                    }
                }));
    }

    void setLed(int rgb) {
        final MugConnection c = connection;
        if (c == null || !state.isReady()) {
            setStatus("Connect to the mug to change its light.");
            return;
        }
        if (ledInFlight) {
            queuedLed = rgb;
            return;
        }
        ledInFlight = true;
        setStatus("Setting the light to " + LedColor.hex(rgb) + "…");
        c.write(EmberProtocol.LED, LedColor.encode(rgb), ok ->
                c.read(EmberProtocol.LED, (readOk, value) -> {
                    if (c != connection) {
                        return;
                    }
                    ledInFlight = false;
                    Integer reported = readOk ? LedColor.decode(value) : null;
                    if (reported != null) {
                        state.ledRgb = reported;
                    }
                    if (reported != null && LedColor.sameColor(reported, rgb)) {
                        setStatus("Light set to " + LedColor.hex(rgb)
                                + ". The mug may briefly show status colors over it.");
                    } else {
                        setStatus("The mug didn't confirm the light color.");
                    }
                    if (queuedLed != null) {
                        int next = queuedLed;
                        queuedLed = null;
                        setLed(next);
                    }
                }));
    }

    /** User chose to drop an undelivered heater-off request (e.g. they turned the mug off by hand). */
    void forgetPendingShutdown() {
        shutdowns.forget();
        if (state.isReady() && device != null
                && ShutdownPolicy.shouldArm(settings.flag(AppSettings.OFF_ON_DISCONNECT), state.targetCenti)) {
            shutdowns.arm(device.getAddress(), System.currentTimeMillis());
        }
        publish();
    }

    /**
     * Switches the running session to another mug. With {@code forgetPending}, an undelivered
     * heater-off request is dropped first (the user confirmed this); otherwise such a request
     * redirects the connection back to its own mug.
     */
    void switchMug(String address, String name, boolean forgetPending) {
        if (forgetPending) {
            shutdowns.forget();
        }
        connectTo(address, name);
    }

    /** Intentional disconnect: turn the heater off first (if enabled), then stop the service. */
    void userDisconnect() {
        if (stopping) {
            return;
        }
        stopping = true;
        settings.setServiceShouldRun(false);
        main.removeCallbacks(reconnect);
        main.removeCallbacks(poll);
        final MugConnection c = connection;
        boolean needsOff = settings.flag(AppSettings.OFF_ON_DISCONNECT) && c != null
                && state.isReady() && state.heaterMaybeOn();
        if (!needsOff) {
            finishStop(null, null);
            return;
        }
        state.link = MugState.Link.DISCONNECTING;
        setStatus("Turning the heater off before disconnecting…");
        final Runnable giveUp = () -> finishStop(false, null);
        main.postDelayed(giveUp, STOP_TIMEOUT_MS);
        c.write(EmberProtocol.TARGET_TEMP, EmberProtocol.heaterOff(), ok ->
                c.read(EmberProtocol.TARGET_TEMP, (readOk, value) -> {
                    main.removeCallbacks(giveUp);
                    Integer reported = readOk ? EmberProtocol.decodeTemperature(value) : null;
                    if (reported != null) {
                        state.targetCenti = reported;
                    }
                    boolean confirmed = ShutdownPolicy.confirmsOff(reported);
                    if (confirmed) {
                        shutdowns.clear(c.device().getAddress());
                    }
                    finishStop(confirmed, null);
                }));
    }

    // ---- connection management ----

    private void connectTo(String address, String name) {
        if (stopping) {
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            finishStop(null, adapter == null ? "This phone has no Bluetooth." : "Invalid mug address.");
            return;
        }
        PendingShutdown pending = shutdowns.load();
        if (ShutdownPolicy.blocksSelecting(pending, address)) {
            // Never abandon an undelivered heater-off request: go back to that mug first.
            address = pending.address;
            name = null;
            state.commandStatus = "Reconnecting to the previous mug first to turn its heater off.";
        }
        settings.setServiceShouldRun(true);
        if (device != null && device.getAddress().equalsIgnoreCase(address)
                && (connection != null || state.link == MugState.Link.WAITING_TO_RECONNECT)) {
            publish();
            return;
        }
        closeConnection();
        main.removeCallbacks(reconnect);
        device = adapter.getRemoteDevice(address);
        resetReadings();
        state.address = address;
        state.name = name != null ? name : "Ember Mug";
        state.reconnectFailures = 0;
        if (!adapter.isEnabled()) {
            state.link = MugState.Link.BLUETOOTH_OFF;
            publish();
            return;
        }
        openConnection(false);
    }

    private void openConnection(boolean autoConnect) {
        if (!BtPermissions.canConnect(this)) {
            finishStop(null, "Bluetooth permission is missing. Open the app to grant it.");
            return;
        }
        MugConnection c = new MugConnection(this, device, this, main);
        connection = c;
        state.link = MugState.Link.CONNECTING;
        publish();
        if (!c.connect(autoConnect)) {
            connection = null;
            state.reconnectFailures++;
            scheduleReconnect();
        }
    }

    private void reconnectNow() {
        if (stopping || connection != null || device == null) {
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            state.link = MugState.Link.BLUETOOTH_OFF;
            publish();
            return;
        }
        openConnection(ReconnectBackoff.useAutoConnect(state.reconnectFailures));
    }

    private void scheduleReconnect() {
        main.removeCallbacks(reconnect);
        if (stopping) {
            return;
        }
        state.link = MugState.Link.WAITING_TO_RECONNECT;
        publish();
        main.postDelayed(reconnect, ReconnectBackoff.delayMs(state.reconnectFailures));
    }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        resetLiveFlags();
    }

    private void resetLiveFlags() {
        main.removeCallbacks(poll);
        heatInFlight = false;
        offInFlight = false;
        ledInFlight = false;
        queuedLed = null;
        state.liquidReadAtMs = 0;
        state.pushEventsActive = false;
    }

    private void resetReadings() {
        state.currentCenti = null;
        state.targetCenti = null;
        state.liquidState = null;
        state.liquidReadAtMs = 0;
        state.batteryPercent = null;
        state.charging = false;
        state.ledRgb = null;
        state.pushEventsActive = false;
        notifiedTargetFor = null;
    }

    @Override
    public void onReady(MugConnection c) {
        if (c != connection) {
            return;
        }
        offAttemptsThisConnection = 0;
        state.reconnectFailures = 0;
        Notifications.cancel(this, Notifications.ID_CONNECTION);
        if (ShutdownPolicy.mustSendOffFirst(shutdowns.load(), device.getAddress())) {
            sendPendingOff(c);
        } else {
            startSession(c);
        }
    }

    /** Delivers a persisted heater-off request before anything else on this connection. */
    private void sendPendingOff(final MugConnection c) {
        state.link = MugState.Link.TURNING_OFF;
        publish();
        offAttemptsThisConnection++;
        shutdowns.recordAttempt();
        c.write(EmberProtocol.TARGET_TEMP, EmberProtocol.heaterOff(), ok ->
                c.read(EmberProtocol.TARGET_TEMP, (readOk, value) -> {
                    if (c != connection) {
                        return;
                    }
                    Integer reported = readOk ? EmberProtocol.decodeTemperature(value) : null;
                    if (reported != null) {
                        state.targetCenti = reported;
                    }
                    boolean confirmed = ShutdownPolicy.confirmsOff(reported);
                    switch (ShutdownPolicy.afterAttempt(confirmed, offAttemptsThisConnection)) {
                        case CLEAR:
                            shutdowns.clear(device.getAddress());
                            state.commandStatus = "Heater turned off after the connection was lost. "
                                    + "Tap Heat now to warm it again.";
                            if (settings.flag(AppSettings.NOTIFY_DISCONNECT)) {
                                Notifications.alert(this, Notifications.ID_CONNECTION, "Heater turned off",
                                        "Your mug reconnected and its heater was turned off, as set for "
                                                + "when your phone disconnects. Open the app to heat it again.",
                                        null);
                            }
                            startSession(c);
                            break;
                        case RETRY:
                            setStatus("Heater off not confirmed yet. Trying again…");
                            main.postDelayed(() -> {
                                if (c == connection && c.isReady() && !stopping) {
                                    sendPendingOff(c);
                                }
                            }, ShutdownPolicy.RETRY_DELAY_MS);
                            break;
                        case RECONNECT:
                        default:
                            state.commandStatus = "The mug didn't confirm heater off. Reconnecting to try again.";
                            closeConnection();
                            state.reconnectFailures++;
                            scheduleReconnect();
                            break;
                    }
                }));
    }

    private void startSession(final MugConnection c) {
        state.link = MugState.Link.READY;
        publish();
        c.enableNotifications(EmberProtocol.PUSH_EVENTS, ok -> {
            if (c == connection) {
                state.pushEventsActive = ok;
                publish();
            }
        });
        readTarget(c);
        readCurrent(c);
        readLiquid(c);
        readBattery(c);
        readLed(c);
        schedulePoll();
    }

    private void schedulePoll() {
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_INTERVAL_MS);
    }

    private void pollNow() {
        MugConnection c = connection;
        if (c == null || !state.isReady()) {
            return;
        }
        // Skip a round if the mug is slow to answer, so reads never pile up.
        if (c.pendingOperations() <= 2) {
            readCurrent(c);
            readTarget(c);
            readLiquid(c);
            readBattery(c);
        }
        schedulePoll();
    }

    @Override
    public void onDisconnected(MugConnection c, int status, boolean wasReady) {
        if (c != connection) {
            return;
        }
        boolean sessionWasLive = state.link == MugState.Link.READY;
        connection = null;
        resetLiveFlags();
        if (stopping) {
            return;
        }
        if (sessionWasLive) {
            onUnexpectedDisconnect();
        } else {
            state.reconnectFailures++;
        }
        scheduleReconnect();
    }

    private void onUnexpectedDisconnect() {
        boolean pending = device != null && shutdowns.isPendingFor(device.getAddress());
        boolean heating = state.targetCenti != null && state.targetCenti > 0;
        String text;
        if (pending) {
            text = "Its heater will be turned off as soon as it reconnects. "
                    + "You can also turn it off on the mug.";
        } else if (heating) {
            text = "The heater may still be on. Reconnecting…";
        } else {
            text = "Reconnecting…";
        }
        state.commandStatus = "Connection lost. " + text;
        if (settings.flag(AppSettings.NOTIFY_DISCONNECT)) {
            Notifications.alert(this, Notifications.ID_CONNECTION, state.name + " disconnected", text, null);
        }
    }

    @Override
    public void onNotification(MugConnection c, UUID characteristic, byte[] value) {
        if (c != connection || !state.isReady() || !EmberProtocol.PUSH_EVENTS.equals(characteristic)) {
            return;
        }
        switch (EmberProtocol.decodeEvent(value)) {
            case EmberProtocol.EVENT_BATTERY_CHANGED:
                readBattery(c);
                break;
            case EmberProtocol.EVENT_CHARGER_CONNECTED:
                state.charging = true;
                publish();
                readBattery(c);
                break;
            case EmberProtocol.EVENT_CHARGER_DISCONNECTED:
                state.charging = false;
                publish();
                readBattery(c);
                break;
            case EmberProtocol.EVENT_TARGET_CHANGED:
                readTarget(c);
                break;
            case EmberProtocol.EVENT_DRINK_TEMP_CHANGED:
                readCurrent(c);
                break;
            case EmberProtocol.EVENT_LIQUID_LEVEL_CHANGED:
            case EmberProtocol.EVENT_LIQUID_STATE_CHANGED:
                readLiquid(c);
                break;
            default:
                break;
        }
    }

    @Override
    public void onAuthenticationRequired(MugConnection c) {
        if (c != connection || device == null) {
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            setStatus("Pairing with the mug. Accept the pairing request if Android shows one.");
            if (!device.createBond()) {
                setStatus("Couldn't start pairing. Put the mug in pairing mode and try again.");
            }
        }
    }

    @Override
    public void onUnsupportedDevice(MugConnection c) {
        if (c != connection) {
            return;
        }
        connection = null;
        resetLiveFlags();
        state.reconnectFailures++;
        boolean pending = shutdowns.isPendingFor(device.getAddress());
        if (!pending && state.reconnectFailures >= MAX_UNSUPPORTED_RETRIES) {
            settings.setServiceShouldRun(false);
            finishStop(null, "This device doesn't look like an Ember Mug (no Ember service found).");
            return;
        }
        scheduleReconnect();
    }

    // ---- readings ----

    private void readTarget(final MugConnection c) {
        c.read(EmberProtocol.TARGET_TEMP, (ok, value) -> {
            Integer target = ok ? EmberProtocol.decodeTemperature(value) : null;
            if (c == connection && target != null) {
                onTarget(target);
            }
        });
    }

    private void readCurrent(final MugConnection c) {
        c.read(EmberProtocol.CURRENT_TEMP, (ok, value) -> {
            Integer current = ok ? EmberProtocol.decodeTemperature(value) : null;
            if (c == connection && current != null) {
                state.currentCenti = current;
                publish();
            }
        });
    }

    private void readLiquid(final MugConnection c) {
        c.read(EmberProtocol.LIQUID_STATE, (ok, value) -> {
            LiquidState liquid = ok ? LiquidState.fromBytes(value) : null;
            if (c == connection && liquid != null) {
                onLiquid(liquid);
            }
        });
    }

    private void readBattery(final MugConnection c) {
        c.read(EmberProtocol.BATTERY, (ok, value) -> {
            EmberProtocol.BatteryReading battery = ok ? EmberProtocol.decodeBattery(value) : null;
            if (c == connection && battery != null) {
                state.batteryPercent = battery.percent;
                state.charging = battery.charging;
                publish();
            }
        });
    }

    private void readLed(final MugConnection c) {
        c.read(EmberProtocol.LED, (ok, value) -> {
            Integer rgb = ok ? LedColor.decode(value) : null;
            if (c == connection && rgb != null) {
                state.ledRgb = rgb;
                publish();
            }
        });
    }

    private void onTarget(int target) {
        Integer previous = state.targetCenti;
        state.targetCenti = target;
        if (previous == null || previous != target) {
            notifiedTargetFor = null;
        }
        String address = device.getAddress();
        if (target == 0) {
            shutdowns.clear(address);
        } else if (ShutdownPolicy.shouldArm(settings.flag(AppSettings.OFF_ON_DISCONNECT), target)) {
            shutdowns.arm(address, System.currentTimeMillis());
        }
        publish();
    }

    private void onLiquid(LiquidState liquid) {
        LiquidState previous = state.liquidState;
        // Transitions only count within one connection; the first reading after connecting is a baseline.
        boolean hadFreshReading = state.liquidReadAtMs > 0 && previous != null;
        state.liquidState = liquid;
        state.liquidReadAtMs = SystemClock.elapsedRealtime();
        boolean heating = state.targetCenti != null && state.targetCenti > 0;

        if (liquid == LiquidState.EMPTY) {
            notifiedTargetFor = null;
            boolean turnOff = heating && settings.flag(AppSettings.OFF_WHEN_EMPTY);
            if (hadFreshReading && previous.hasLiquid() && settings.flag(AppSettings.NOTIFY_EMPTY)) {
                Notifications.alert(this, Notifications.ID_LIQUID, "Your mug is empty",
                        turnOff ? "Turning the heater off." : "Refill it to keep enjoying a warm drink.",
                        null);
            }
            if (turnOff) {
                heaterOff(" because the mug is empty");
            }
        } else if (liquid.hasLiquid()) {
            if (hadFreshReading && previous == LiquidState.EMPTY && settings.flag(AppSettings.NOTIFY_EMPTY)) {
                if (heating) {
                    Notifications.cancel(this, Notifications.ID_LIQUID);
                } else {
                    String label = Temperatures.format(settings.selectedTarget(), settings.unit());
                    Notifications.alert(this, Notifications.ID_LIQUID, "Liquid detected",
                            "Heat it to " + label + "?",
                            Notifications.heatAction(this, "Heat to " + label));
                }
            }
            if (liquid == LiquidState.AT_TARGET && hadFreshReading && previous != LiquidState.AT_TARGET
                    && heating && !state.targetCenti.equals(notifiedTargetFor)
                    && settings.flag(AppSettings.NOTIFY_TARGET)) {
                notifiedTargetFor = state.targetCenti;
                Notifications.alert(this, Notifications.ID_TARGET, "Ready to drink",
                        "Your drink reached " + Temperatures.format(state.targetCenti, settings.unit()) + ".",
                        null);
            }
        }
        publish();
    }

    // ---- stopping ----

    /**
     * Ends the session. {@code offConfirmed} is true/false when a heater-off was attempted before
     * stopping, or null when none was needed or possible. {@code message} explains an error stop.
     */
    private void finishStop(Boolean offConfirmed, String message) {
        if (finished) {
            return;
        }
        finished = true;
        stopping = true;
        main.removeCallbacks(poll);
        main.removeCallbacks(reconnect);
        main.removeCallbacks(notificationRefresh);
        notificationRefreshPending = false;
        if (connection != null) {
            connection.close();
            connection = null;
        }
        String address = device != null ? device.getAddress() : null;
        if (Boolean.FALSE.equals(offConfirmed) && address != null
                && settings.flag(AppSettings.OFF_ON_DISCONNECT)) {
            shutdowns.arm(address, System.currentTimeMillis());
        }
        boolean pending = address != null && shutdowns.isPendingFor(address);
        if (pending) {
            state.commandStatus = "Disconnected. The heater-off request is still pending.";
            Notifications.alert(this, Notifications.ID_CONNECTION, "Heater off not confirmed",
                    "Your mug may still be heating. Its heater will be turned off the next time this app "
                            + "connects to it. You can also turn it off on the mug.",
                    null);
        } else if (message != null) {
            state.commandStatus = message;
        } else if (Boolean.TRUE.equals(offConfirmed)) {
            state.commandStatus = "Disconnected. Heater off confirmed by the mug.";
        } else {
            state.commandStatus = "Disconnected.";
        }
        state.link = MugState.Link.STOPPED;
        state.liquidReadAtMs = 0;
        state.pushEventsActive = false;
        MugState snapshot = state.copy();
        for (StateListener listener : new ArrayList<>(listeners)) {
            listener.onMugState(snapshot);
            listener.onServiceStopping();
        }
        listeners.clear();
        if (foreground) {
            foreground = false;
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }
        // Only stops if no newer start request (e.g. a fresh Connect) has arrived meanwhile.
        stopSelf(lastStartId);
    }

    // ---- helpers ----

    private boolean isConnected() {
        return connection != null && connection.isReady()
                && (state.link == MugState.Link.READY || state.link == MugState.Link.TURNING_OFF);
    }

    private boolean isDeliveringShutdown() {
        return state.link == MugState.Link.TURNING_OFF;
    }

    private String describe(Integer reported) {
        if (reported == null) {
            return ".";
        }
        return reported == 0
                ? " (it reports the heater is off)."
                : " (it reports " + Temperatures.format(reported, settings.unit()) + ").";
    }

    private void setStatus(String status) {
        state.commandStatus = status;
        publish();
    }

    private void publish() {
        MugState snapshot = state.copy();
        for (StateListener listener : new ArrayList<>(listeners)) {
            listener.onMugState(snapshot);
        }
        refreshNotification();
    }

    private void refreshNotification() {
        if (foreground && !finished && !notificationRefreshPending) {
            notificationRefreshPending = true;
            main.postDelayed(notificationRefresh, NOTIFICATION_REFRESH_MS);
        }
    }

    private Notification buildServiceNotification() {
        return Notifications.service(this, state, settings.unit());
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager != null ? manager.getAdapter() : null;
    }

    private void registerSystemReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(systemReceiver, filter);
        }
        receiverRegistered = true;
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (finished || stopping || device == null) {
                return;
            }
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (adapterState == BluetoothAdapter.STATE_TURNING_OFF || adapterState == BluetoothAdapter.STATE_OFF) {
                    boolean live = state.isReady();
                    main.removeCallbacks(reconnect);
                    closeConnection();
                    if (live) {
                        onUnexpectedDisconnect();
                    }
                    state.link = MugState.Link.BLUETOOTH_OFF;
                    publish();
                } else if (adapterState == BluetoothAdapter.STATE_ON && connection == null) {
                    state.reconnectFailures = 0;
                    main.removeCallbacks(reconnect);
                    reconnectNow();
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice bonded = deviceExtra(intent);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (bonded != null && bondState == BluetoothDevice.BOND_BONDED
                        && bonded.getAddress().equalsIgnoreCase(device.getAddress())) {
                    MugConnection c = connection;
                    if (c != null && state.isReady()) {
                        // The live link is now encrypted; refresh everything on it.
                        setStatus("Paired with the mug.");
                        readTarget(c);
                        readCurrent(c);
                        readLiquid(c);
                        readBattery(c);
                        readLed(c);
                    } else {
                        // Not set up yet: start over so setup runs on the encrypted link.
                        closeConnection();
                        state.reconnectFailures = 0;
                        main.removeCallbacks(reconnect);
                        reconnectNow();
                    }
                }
            }
        }
    };
}
