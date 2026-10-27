package io.github.bags156.embermug;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.UUID;

import io.github.bags156.embermug.core.EmberProtocol;

/**
 * One GATT connection to one mug. Not reusable: after it closes, the service creates a new one.
 *
 * <p>Threading: every Bluetooth callback is copied and posted to the main looper, and every method
 * here must be called on the main looper. Results from a stale {@link BluetoothGatt} or from this
 * object after {@link #close()} are discarded.
 *
 * <p>Operations run strictly one at a time. Each waits for its acknowledgement; if none arrives
 * within {@link #OP_TIMEOUT_MS} the whole connection is torn down rather than risk overlapping
 * operations, and the service reconnects.
 */
// Permissions: the service only creates connections after checking BLUETOOTH_CONNECT.
@SuppressLint("MissingPermission")
final class MugConnection {

    interface Listener {
        void onReady(MugConnection connection);

        void onDisconnected(MugConnection connection, int status, boolean wasReady);

        void onNotification(MugConnection connection, UUID characteristic, byte[] value);

        void onAuthenticationRequired(MugConnection connection);

        void onUnsupportedDevice(MugConnection connection);
    }

    interface ReadCallback {
        void done(boolean ok, byte[] value);
    }

    interface WriteCallback {
        void done(boolean ok);
    }

    private static final String TAG = "EmberMug";

    static final long OP_TIMEOUT_MS = 6_000;
    static final long CONNECT_TIMEOUT_MS = 35_000;
    static final long DISCOVERY_TIMEOUT_MS = 15_000;

    /** Synthetic status used when this side gives up. */
    static final int STATUS_LOCAL_TIMEOUT = -2;
    static final int STATUS_LOCAL_ERROR = -1;

    private static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int GATT_INSUFFICIENT_ENCRYPTION = 15;
    private static final int GATT_AUTH_FAIL = 137;

    private static final int KIND_READ = 0;
    private static final int KIND_WRITE = 1;
    private static final int KIND_NOTIFY = 2;

    private static final class Op {
        final int kind;
        final UUID uuid;
        final byte[] value;
        final ReadCallback readCallback;
        final WriteCallback writeCallback;

        Op(int kind, UUID uuid, byte[] value, ReadCallback readCallback, WriteCallback writeCallback) {
            this.kind = kind;
            this.uuid = uuid;
            this.value = value;
            this.readCallback = readCallback;
            this.writeCallback = writeCallback;
        }
    }

    private final Context context;
    private final BluetoothDevice device;
    private final Listener listener;
    private final Handler main;
    private final ArrayDeque<Op> queue = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattService service;
    private Op current;
    private boolean ready;
    private boolean closed;

    private final Runnable opTimeout = () -> {
        Log.w(TAG, "GATT operation timed out");
        teardown(STATUS_LOCAL_TIMEOUT);
    };
    private final Runnable connectTimeout = () -> {
        Log.w(TAG, "Connection attempt timed out");
        teardown(STATUS_LOCAL_TIMEOUT);
    };
    private final Runnable discoveryTimeout = () -> {
        Log.w(TAG, "Service discovery timed out");
        teardown(STATUS_LOCAL_TIMEOUT);
    };
    private final Runnable discover = () -> {
        if (!closed && gatt != null && !gatt.discoverServices()) {
            teardown(STATUS_LOCAL_ERROR);
        }
    };

    MugConnection(Context context, BluetoothDevice device, Listener listener, Handler main) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.listener = listener;
        this.main = main;
    }

    BluetoothDevice device() {
        return device;
    }

    boolean isReady() {
        return ready && !closed;
    }

    int pendingOperations() {
        return queue.size() + (current != null ? 1 : 0);
    }

    /**
     * Starts connecting. A direct connection times out after {@link #CONNECT_TIMEOUT_MS}; an
     * auto-connection waits in the background until the mug comes into range.
     */
    boolean connect(boolean autoConnect) {
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            closed = true;
            return false;
        }
        if (!autoConnect) {
            main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        }
        return true;
    }

    void read(UUID characteristic, ReadCallback callback) {
        enqueue(new Op(KIND_READ, characteristic, null, callback, null));
    }

    void write(UUID characteristic, byte[] value, WriteCallback callback) {
        enqueue(new Op(KIND_WRITE, characteristic, value.clone(), null, callback));
    }

    void enableNotifications(UUID characteristic, WriteCallback callback) {
        enqueue(new Op(KIND_NOTIFY, characteristic, null, null, callback));
    }

    /** Closes without notifying the listener. Queued operations are dropped. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        ready = false;
        main.removeCallbacks(opTimeout);
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(discoveryTimeout);
        main.removeCallbacks(discover);
        queue.clear();
        current = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (RuntimeException e) {
                Log.w(TAG, "disconnect failed", e);
            }
            gatt.close();
        }
    }

    private void teardown(int status) {
        if (closed) {
            return;
        }
        boolean wasReady = ready;
        close();
        listener.onDisconnected(this, status, wasReady);
    }

    // ---- operation queue ----

    private void enqueue(Op op) {
        if (closed || !ready) {
            return;
        }
        queue.add(op);
        drive();
    }

    private void drive() {
        while (!closed && current == null && !queue.isEmpty()) {
            Op op = queue.poll();
            current = op;
            if (start(op)) {
                main.postDelayed(opTimeout, OP_TIMEOUT_MS);
                return;
            }
            Log.w(TAG, "Could not start GATT operation on " + op.uuid);
            current = null;
            deliver(op, false, null);
        }
    }

    private boolean start(Op op) {
        BluetoothGattCharacteristic ch = service.getCharacteristic(op.uuid);
        if (ch == null) {
            return false;
        }
        switch (op.kind) {
            case KIND_READ:
                return gatt.readCharacteristic(ch);
            case KIND_WRITE:
                return writeCharacteristic(ch, op.value);
            case KIND_NOTIFY:
                return enableNotify(ch);
            default:
                return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean writeCharacteristic(BluetoothGattCharacteristic ch, byte[] value) {
        int props = ch.getProperties();
        int writeType = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                && (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS;
        }
        ch.setWriteType(writeType);
        ch.setValue(value);
        return gatt.writeCharacteristic(ch);
    }

    @SuppressWarnings("deprecation")
    private boolean enableNotify(BluetoothGattCharacteristic ch) {
        BluetoothGattDescriptor cccd = ch.getDescriptor(EmberProtocol.CCCD);
        if (cccd == null || !gatt.setCharacteristicNotification(ch, true)) {
            return false;
        }
        byte[] value = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS;
        }
        cccd.setValue(value);
        return gatt.writeDescriptor(cccd);
    }

    private void complete(int kind, UUID uuid, boolean ok, byte[] value, int status) {
        Op op = current;
        if (op == null || op.kind != kind || !op.uuid.equals(uuid)) {
            Log.w(TAG, "Ignoring unexpected GATT result for " + uuid);
            return;
        }
        main.removeCallbacks(opTimeout);
        current = null;
        deliver(op, ok, value);
        if (!ok && isAuthFailure(status)) {
            listener.onAuthenticationRequired(this);
        }
        drive();
    }

    private static void deliver(Op op, boolean ok, byte[] value) {
        if (op.readCallback != null) {
            op.readCallback.done(ok, value);
        } else if (op.writeCallback != null) {
            op.writeCallback.done(ok);
        }
    }

    private static boolean isAuthFailure(int status) {
        return status == GATT_INSUFFICIENT_AUTHENTICATION
                || status == GATT_INSUFFICIENT_ENCRYPTION
                || status == GATT_AUTH_FAIL;
    }

    private boolean isStale(BluetoothGatt g) {
        return closed || g != gatt;
    }

    // ---- connection events (main thread) ----

    private void onConnectionState(BluetoothGatt g, int status, int newState) {
        if (isStale(g)) {
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            main.removeCallbacks(connectTimeout);
            // Bonded devices may send a service-changed indication right after connecting; give
            // the stack a moment before discovery.
            long delay = device.getBondState() == BluetoothDevice.BOND_BONDED ? 1_000 : 300;
            main.postDelayed(discover, delay);
            main.postDelayed(discoveryTimeout, delay + DISCOVERY_TIMEOUT_MS);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "Disconnected, status " + status);
            teardown(status);
        }
    }

    private void onServicesDiscovered(BluetoothGatt g, int status) {
        if (isStale(g)) {
            return;
        }
        main.removeCallbacks(discoveryTimeout);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            teardown(status);
            return;
        }
        service = g.getService(EmberProtocol.SERVICE);
        if (service == null) {
            Log.w(TAG, "Ember service not found on " + device.getAddress());
            close();
            listener.onUnsupportedDevice(this);
            return;
        }
        ready = true;
        listener.onReady(this);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> onConnectionState(g, status, newState));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> MugConnection.this.onServicesDiscovered(g, status));
        }

        // Android 13+ delivers the value directly.
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value,
                int status) {
            byte[] data = copy(value);
            UUID uuid = ch.getUuid();
            main.post(() -> {
                if (!isStale(g)) {
                    complete(KIND_READ, uuid, status == BluetoothGatt.GATT_SUCCESS, data, status);
                }
            });
        }

        // Android 12 and older.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            byte[] data = copy(ch.getValue());
            UUID uuid = ch.getUuid();
            main.post(() -> {
                if (!isStale(g)) {
                    complete(KIND_READ, uuid, status == BluetoothGatt.GATT_SUCCESS, data, status);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            UUID uuid = ch.getUuid();
            main.post(() -> {
                if (!isStale(g)) {
                    complete(KIND_WRITE, uuid, status == BluetoothGatt.GATT_SUCCESS, null, status);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            UUID uuid = descriptor.getCharacteristic().getUuid();
            main.post(() -> {
                if (!isStale(g)) {
                    complete(KIND_NOTIFY, uuid, status == BluetoothGatt.GATT_SUCCESS, null, status);
                }
            });
        }

        // Android 13+.
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            byte[] data = copy(value);
            UUID uuid = ch.getUuid();
            main.post(() -> {
                if (!isStale(g) && ready) {
                    listener.onNotification(MugConnection.this, uuid, data);
                }
            });
        }

        // Android 12 and older.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] data = copy(ch.getValue());
            UUID uuid = ch.getUuid();
            main.post(() -> {
                if (!isStale(g) && ready) {
                    listener.onNotification(MugConnection.this, uuid, data);
                }
            });
        }
    };
}
