package io.github.bags156.embermug;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Runtime permissions needed for Bluetooth, which differ before and after Android 12. */
final class BtPermissions {

    private BtPermissions() {
    }

    static final String SCAN = "android.permission.BLUETOOTH_SCAN";
    static final String CONNECT = "android.permission.BLUETOOTH_CONNECT";
    static final String FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    static final String COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION";
    static final String NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    /** Bluetooth permissions the app cannot work without. */
    static String[] bluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[] {SCAN, CONNECT};
        }
        // Android 8-11 only report BLE scan results to apps with location permission.
        return new String[] {FINE_LOCATION, COARSE_LOCATION};
    }

    /** Bluetooth permissions plus the notification permission on Android 13+, minus granted ones. */
    static String[] missing(Context context) {
        List<String> missing = new ArrayList<>();
        for (String permission : bluetooth()) {
            if (!granted(context, permission)) {
                missing.add(permission);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context, NOTIFICATIONS)) {
            missing.add(NOTIFICATIONS);
        }
        return missing.toArray(new String[0]);
    }

    static boolean hasBluetooth(Context context) {
        for (String permission : bluetooth()) {
            if (!granted(context, permission)) {
                return false;
            }
        }
        return true;
    }

    static boolean canConnect(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, CONNECT);
    }

    static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
