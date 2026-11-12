package io.github.bags156.embermug;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.bags156.embermug.core.EmberProtocol;
import io.github.bags156.embermug.core.Favorites;
import io.github.bags156.embermug.core.LedColor;
import io.github.bags156.embermug.core.MugState;
import io.github.bags156.embermug.core.PendingShutdown;
import io.github.bags156.embermug.core.ShutdownPolicy;
import io.github.bags156.embermug.core.ShutdownStore;
import io.github.bags156.embermug.core.TempUnit;
import io.github.bags156.embermug.core.Temperatures;

/** The only screen. Built in code with platform views; all mug work happens in EmberService. */
// Permissions: Bluetooth calls run only after ensureBluetooth() has confirmed the runtime grants.
@SuppressLint("MissingPermission")
public final class MainActivity extends Activity implements EmberService.StateListener {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private static final long SCAN_DURATION_MS = 15_000;
    private static final long APPLY_DEBOUNCE_MS = 800;
    private static final int ACCENT = 0xFFE8590C;

    private enum PendingAction { NONE, FIND, CONNECT }

    private final Handler main = new Handler(Looper.getMainLooper());
    private AppSettings settings;
    private ShutdownStore shutdowns;

    private EmberService service;
    private boolean bound;
    private MugState mug = new MugState();
    private PendingAction pendingAction = PendingAction.NONE;

    // Scanning
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private AlertDialog scanDialog;
    private TextView scanHint;
    private ArrayAdapter<String> scanAdapter;
    private final List<String[]> found = new ArrayList<>();

    // Views
    private ColorStateList secondaryText;
    private TextView statusText;
    private TextView mugNameText;
    private TextView currentTempText;
    private TextView targetText;
    private TextView liquidText;
    private TextView batteryText;
    private TextView liveText;
    private TextView selectedText;
    private TextView commandText;
    private TextView ledInfoText;
    private TextView pendingText;
    private LinearLayout pendingCard;
    private Button connectButton;
    private Button heatButton;
    private Button offButton;
    private Button unitF;
    private Button unitC;
    private final Button[] favoriteButtons = new Button[Favorites.COUNT];
    private SeekBar redBar;
    private SeekBar greenBar;
    private SeekBar blueBar;
    private View swatch;

    /** While the mug is heating, a changed selection is sent after a short pause. */
    private final Runnable applySelection = () -> {
        if (service != null && mug.isReady() && mug.targetCenti != null && mug.targetCenti > 0) {
            service.heatNow(settings.selectedTarget());
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((EmberService.LocalBinder) binder).service();
            service.addListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            mug.link = MugState.Link.STOPPED;
            render();
        }
    };

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);
        shutdowns = new ShutdownStore(settings);
        Notifications.ensureChannels(this);
        TypedArray attrs = obtainStyledAttributes(new int[] {android.R.attr.textColorSecondary});
        secondaryText = attrs.getColorStateList(0);
        attrs.recycle();
        setContentView(buildUi());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bind();
        render();
    }

    @Override
    protected void onStop() {
        unbind();
        stopScan();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (scanDialog != null) {
            scanDialog.dismiss();
        }
        super.onDestroy();
    }

    private void bind() {
        if (!bound) {
            // Flag 0: observe the service while it runs, but never create or keep it alive.
            bound = bindService(new Intent(this, EmberService.class), serviceConnection, 0);
        }
    }

    private void unbind() {
        if (service != null) {
            service.removeListener(this);
            service = null;
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }

    @Override
    public void onMugState(MugState state) {
        mug = state;
        render();
    }

    @Override
    public void onServiceStopping() {
        service = null;
        mug.link = MugState.Link.STOPPED;
        render();
    }

    // ---- permissions and Bluetooth ----

    private void ensureBluetooth(PendingAction action) {
        pendingAction = action;
        String[] missing = BtPermissions.missing(this);
        if (missing.length > 0) {
            requestPermissions(missing, REQUEST_PERMISSIONS);
            return;
        }
        continuePendingAction();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            continuePendingAction();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                continuePendingAction();
            } else {
                pendingAction = PendingAction.NONE;
            }
        }
    }

    private void continuePendingAction() {
        if (pendingAction == PendingAction.NONE) {
            return;
        }
        if (!BtPermissions.hasBluetooth(this)) {
            pendingAction = PendingAction.NONE;
            showPermissionHelp();
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            pendingAction = PendingAction.NONE;
            toast("This phone doesn't support Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            return;
        }
        PendingAction action = pendingAction;
        pendingAction = PendingAction.NONE;
        if (action == PendingAction.FIND) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationServicesOn()) {
                showLocationHelp();
                return;
            }
            startScan();
        } else if (action == PendingAction.CONNECT) {
            String address = settings.deviceAddress();
            if (address == null) {
                startScan();
            } else {
                connect(address, settings.deviceName(), false);
            }
        }
    }

    private boolean locationServicesOn() {
        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void showPermissionHelp() {
        String which = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? "Nearby devices" : "Location";
        new AlertDialog.Builder(this)
                .setTitle("Bluetooth permission needed")
                .setMessage("To find and control your mug, allow " + which + " for this app. "
                        + "The app doesn't use your location and has no internet access.")
                .setPositiveButton("Open settings", (d, w) -> openAppSettings())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLocationHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Turn on Location")
                .setMessage("On Android 11 and older, phones only find Bluetooth devices while Location "
                        + "is on. The app doesn't read your location.")
                .setPositiveButton("Open settings", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager != null ? manager.getAdapter() : null;
    }

    // ---- scanning ----

    private void startScan() {
        BluetoothAdapter adapter = adapter();
        scanner = adapter != null ? adapter.getBluetoothLeScanner() : null;
        if (scanner == null) {
            toast("Bluetooth is still starting. Try again in a moment.");
            return;
        }
        stopScan();
        found.clear();
        scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (looksLikeEmber(device.getName())) {
                addFound(device.getAddress(), device.getName(), true);
            }
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scanHint = text(14, false);
        scanHint.setText("Searching… Put the mug in pairing mode (hold the button on its base until the "
                + "light flashes blue) and keep it next to the phone.");
        scanHint.setPadding(dp(24), dp(8), dp(24), dp(8));
        content.addView(scanHint);
        ListView list = new ListView(this);
        list.setAdapter(scanAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String[] mugInfo = found.get(position);
            if (scanDialog != null) {
                scanDialog.dismiss();
            }
            onMugChosen(mugInfo[0], mugInfo[1]);
        });
        content.addView(list);

        scanDialog = new AlertDialog.Builder(this)
                .setTitle("Find my mug")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> stopScan())
                .show();

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, scanSettings, scanCallback);
            scanning = true;
            main.postDelayed(scanTimeout, SCAN_DURATION_MS);
        } catch (RuntimeException e) {
            scanHint.setText("Couldn't start the Bluetooth search. Turn Bluetooth off and on, then try again.");
        }
    }

    private final Runnable scanTimeout = () -> {
        stopScan();
        if (scanHint != null) {
            scanHint.setText(found.isEmpty()
                    ? "No mug found. Put it in pairing mode (light flashing blue), keep it close, close "
                            + "other mug apps, and tap Find my mug again."
                    : "Search finished. Tap your mug.");
        }
    };

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // Bluetooth was turned off; the scan is already gone.
            }
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> handleScanResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                main.post(() -> handleScanResult(result));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> {
                scanning = false;
                if (scanHint != null) {
                    scanHint.setText("The Bluetooth search failed (error " + errorCode
                            + "). Turn Bluetooth off and on, then try again.");
                }
            });
        }
    };

    private void handleScanResult(ScanResult result) {
        if (!scanning) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        ScanRecord record = result.getScanRecord();
        String name = record != null && record.getDeviceName() != null ? record.getDeviceName() : device.getName();
        boolean ember = looksLikeEmber(name);
        if (!ember && record != null && record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) {
                if (EmberProtocol.SERVICE.equals(uuid.getUuid())) {
                    ember = true;
                    break;
                }
            }
        }
        if (ember) {
            addFound(device.getAddress(), name, false);
        }
    }

    private void addFound(String address, String name, boolean paired) {
        for (String[] existing : found) {
            if (existing[0].equalsIgnoreCase(address)) {
                return;
            }
        }
        found.add(new String[] {address, name});
        scanAdapter.add((name != null ? name : "Ember mug") + (paired ? " (paired)" : "") + "\n" + address);
        if (scanHint != null && scanning) {
            scanHint.setText("Tap your mug. Still searching…");
        }
    }

    private static boolean looksLikeEmber(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("ember");
    }

    // ---- connecting ----

    private void onMugChosen(String address, String name) {
        PendingShutdown pending = shutdowns.load();
        if (!ShutdownPolicy.blocksSelecting(pending, address)) {
            connect(address, name, false);
            return;
        }
        boolean previousIsLive = service != null && mug.isReady() && pending.isFor(mug.address);
        String previousName = pending.isFor(settings.deviceAddress()) && settings.deviceName() != null
                ? settings.deviceName() : "Your previous mug";
        String message = previousName + " has a heater-off request that hasn't been confirmed yet. "
                + (previousIsLive
                        ? "Tap Disconnect first to turn its heater off, then pick the new mug."
                        : "Reconnect to it so the app can turn its heater off, or forget the request if you "
                                + "already turned it off yourself.");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Turn the other mug off first")
                .setMessage(message)
                .setPositiveButton("Forget request and switch", (d, w) -> connect(address, name, true))
                .setNegativeButton("Cancel", null);
        if (!previousIsLive) {
            dialog.setNeutralButton("Reconnect previous", (d, w) -> startEmberService(pending.address, null));
        }
        dialog.show();
    }

    private void connect(String address, String name, boolean forgetPending) {
        settings.setDevice(address, name);
        render();
        if (service != null && mug.link != MugState.Link.STOPPED) {
            // A session is running: switch it in place.
            service.switchMug(address, name, forgetPending);
            return;
        }
        if (forgetPending) {
            shutdowns.forget();
        }
        startEmberService(address, name);
    }

    private void startEmberService(String address, String name) {
        Intent intent = new Intent(this, EmberService.class)
                .setAction(EmberService.ACTION_CONNECT)
                .putExtra(EmberService.EXTRA_ADDRESS, address)
                .putExtra(EmberService.EXTRA_NAME, name);
        try {
            startForegroundService(intent);
        } catch (RuntimeException e) {
            toast("Couldn't start the connection service.");
            return;
        }
        // Re-bind so this screen attaches even if a stopping service instance was reused.
        unbind();
        bind();
    }

    private void onConnectButton() {
        if (service != null && mug.link != MugState.Link.STOPPED) {
            service.userDisconnect();
        } else {
            ensureBluetooth(PendingAction.CONNECT);
        }
    }

    // ---- temperature ----

    private void stepSelection(int steps) {
        settings.setSelectedTarget(Temperatures.step(settings.selectedTarget(), settings.unit(), steps));
        render();
        main.removeCallbacks(applySelection);
        if (service != null && mug.isReady() && mug.targetCenti != null && mug.targetCenti > 0) {
            main.postDelayed(applySelection, APPLY_DEBOUNCE_MS);
        }
    }

    private void onFavoriteTapped(int index) {
        int target = settings.favorites().get(index);
        settings.setSelectedTarget(target);
        main.removeCallbacks(applySelection);
        render();
        if (service != null && mug.isReady()) {
            service.heatNow(target);
        } else {
            toast("Selected " + Temperatures.format(target, settings.unit()) + ". Connect to send it to the mug.");
        }
    }

    private boolean onFavoriteLongPressed(int index) {
        int selected = settings.selectedTarget();
        settings.setFavorites(settings.favorites().with(index, selected));
        render();
        toast("Saved " + Temperatures.format(selected, settings.unit()) + " as favorite " + (index + 1) + ".");
        return true;
    }

    private void setUnit(TempUnit unit) {
        settings.setUnit(unit);
        if (service != null) {
            service.onSettingsChanged();
        }
        render();
    }

    // ---- LED ----

    private int sliderColor() {
        return LedColor.rgb(redBar.getProgress(), greenBar.getProgress(), blueBar.getProgress());
    }

    private void showColor(int rgb) {
        redBar.setProgress(LedColor.red(rgb));
        greenBar.setProgress(LedColor.green(rgb));
        blueBar.setProgress(LedColor.blue(rgb));
        updateSwatch();
    }

    private void updateSwatch() {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(0xFF000000 | sliderColor());
        shape.setStroke(dp(1), 0x40808080);
        swatch.setBackground(shape);
    }

    private void applyColor(int rgb) {
        settings.setLedColor(rgb);
        if (service != null && mug.isReady()) {
            service.setLed(rgb);
        } else {
            toast("Color saved. Connect to send it to the mug.");
        }
    }

    // ---- rendering ----

    private void render() {
        if (statusText == null) {
            return;
        }
        TempUnit unit = settings.unit();
        boolean running = service != null && mug.link != MugState.Link.STOPPED;
        boolean ready = running && mug.isReady();
        String savedAddress = settings.deviceAddress();
        String savedName = settings.deviceName();

        if (running) {
            String status = mug.link.label;
            if (mug.link == MugState.Link.WAITING_TO_RECONNECT && mug.reconnectFailures > 0) {
                status += " (attempt " + (mug.reconnectFailures + 1) + ")";
            }
            statusText.setText(status);
        } else {
            statusText.setText(savedAddress == null ? "No mug yet. Tap Find my mug." : "Not connected");
        }

        mugNameText.setText(savedName != null ? savedName : (savedAddress != null ? savedAddress : "Ember Mug"));
        currentTempText.setText(mug.currentCenti != null ? Temperatures.format(mug.currentCenti, unit) : "--");
        currentTempText.setAlpha(ready ? 1f : 0.45f);
        if (mug.targetCenti == null) {
            targetText.setText("Target: --");
        } else if (mug.targetCenti == 0) {
            targetText.setText("Heater off");
        } else {
            targetText.setText("Target " + Temperatures.format(mug.targetCenti, unit));
        }
        liquidText.setText(mug.liquidState == null
                ? "Liquid: --"
                : "Liquid: " + mug.liquidState.label + (ready && mug.liquidReadAtMs > 0 ? "" : " (last known)"));
        batteryText.setText(mug.batteryPercent == null
                ? "Battery: --"
                : "Battery " + mug.batteryPercent + "%" + (mug.charging ? " · on the charging coaster" : ""));
        liveText.setText(ready
                ? (mug.pushEventsActive
                        ? "Live updates from the mug, plus a check every 12 seconds."
                        : "Checking the mug every 12 seconds.")
                : "");
        liveText.setVisibility(ready ? View.VISIBLE : View.GONE);

        connectButton.setText(running ? "Disconnect" : "Connect");
        connectButton.setEnabled(running || savedAddress != null);

        selectedText.setText(Temperatures.format(settings.selectedTarget(), unit));
        heatButton.setEnabled(ready);
        offButton.setEnabled(ready);
        styleToggle(unitF, unit == TempUnit.FAHRENHEIT);
        styleToggle(unitC, unit == TempUnit.CELSIUS);

        Favorites favorites = settings.favorites();
        for (int i = 0; i < Favorites.COUNT; i++) {
            favoriteButtons[i].setText("★ " + Temperatures.format(favorites.get(i), unit));
        }

        String command = mug.commandStatus;
        commandText.setText(command != null ? command : "");
        commandText.setVisibility(command != null ? View.VISIBLE : View.GONE);

        ledInfoText.setText(mug.ledRgb != null && running
                ? "The mug reports " + LedColor.hex(mug.ledRgb) + "."
                : "Connect to read the mug's current color.");

        PendingShutdown pending = shutdowns.load();
        boolean deliveredLive = pending != null && ready && pending.isFor(mug.address);
        if (pending != null && !deliveredLive) {
            String when = pending.createdAtMs > 0
                    ? " since " + DateFormat.getTimeFormat(this).format(new Date(pending.createdAtMs))
                    : "";
            pendingText.setText("Heater-off request waiting" + when + ". It's sent first the next time the "
                    + "mug connects" + (pending.attempts > 0 ? " (" + pending.attempts + " tries so far)" : "")
                    + ". You can also turn the mug off by hand.");
            pendingCard.setVisibility(View.VISIBLE);
        } else {
            pendingCard.setVisibility(View.GONE);
        }
    }

    private void styleToggle(Button button, boolean selected) {
        button.setBackgroundTintList(selected ? ColorStateList.valueOf(ACCENT) : null);
        button.setTextColor(selected ? Color.WHITE
                : secondaryText != null ? secondaryText.getDefaultColor() : Color.GRAY);
    }

    // ---- UI construction ----

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(26, true);
        title.setText("Ember Mug");
        column.addView(title);
        statusText = secondary(text(15, false));
        column.addView(statusText);

        // Pending heater-off banner
        pendingCard = card(column, "Heater-off pending");
        pendingText = text(14, false);
        pendingCard.addView(pendingText);
        Button forget = button("Forget request", v -> new AlertDialog.Builder(this)
                .setTitle("Forget the heater-off request?")
                .setMessage("Only do this if you've turned the mug off yourself or no longer use it. "
                        + "The app will stop trying to turn its heater off.")
                .setPositiveButton("Forget", (d, w) -> {
                    if (service != null) {
                        service.forgetPendingShutdown();
                    } else {
                        shutdowns.forget();
                    }
                    render();
                })
                .setNegativeButton("Keep", null)
                .show());
        pendingCard.addView(forget);

        // Drink
        LinearLayout drink = card(column, "Your drink");
        mugNameText = text(16, true);
        drink.addView(mugNameText);
        currentTempText = text(56, true);
        currentTempText.setTextColor(ACCENT);
        drink.addView(currentTempText);
        targetText = text(18, false);
        drink.addView(targetText);
        liquidText = secondary(text(14, false));
        drink.addView(liquidText);
        batteryText = secondary(text(14, false));
        drink.addView(batteryText);
        liveText = secondary(text(12, false));
        drink.addView(liveText);
        connectButton = button("Connect", v -> onConnectButton());
        Button find = button("Find my mug", v -> ensureBluetooth(PendingAction.FIND));
        drink.addView(row(connectButton, find));

        // Temperature
        LinearLayout temp = card(column, "Temperature");
        unitF = button("°F", v -> setUnit(TempUnit.FAHRENHEIT));
        unitC = button("°C", v -> setUnit(TempUnit.CELSIUS));
        temp.addView(row(unitF, unitC));
        Button minus = button("−", v -> stepSelection(-1));
        minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        Button plus = button("+", v -> stepSelection(1));
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        selectedText = text(34, true);
        selectedText.setGravity(Gravity.CENTER);
        temp.addView(row(minus, selectedText, plus));
        heatButton = button("Heat now", v -> {
            main.removeCallbacks(applySelection);
            if (service != null) {
                service.heatNow(settings.selectedTarget());
            }
        });
        heatButton.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        heatButton.setTextColor(Color.WHITE);
        offButton = button("Heater off", v -> {
            main.removeCallbacks(applySelection);
            if (service != null) {
                service.heaterOff("");
            }
        });
        temp.addView(row(heatButton, offButton));
        commandText = text(14, false);
        commandText.setPadding(0, dp(6), 0, 0);
        temp.addView(commandText);
        TextView tempHelp = secondary(text(12, false));
        tempHelp.setText("Heat now sends the selected target, even if the heater is off. The mug's firmware still "
                + "decides when to heat: it won't heat an empty mug or liquid that's already hotter. While it's "
                + "heating, changes here are sent automatically.");
        tempHelp.setPadding(0, dp(6), 0, 0);
        temp.addView(tempHelp);

        // Favorites
        LinearLayout favorites = card(column, "Favorites");
        for (int i = 0; i < Favorites.COUNT; i++) {
            final int index = i;
            Button favorite = button("", v -> onFavoriteTapped(index));
            favorite.setOnLongClickListener(v -> onFavoriteLongPressed(index));
            favoriteButtons[i] = favorite;
        }
        favorites.addView(row(favoriteButtons));
        TextView favoriteHelp = secondary(text(12, false));
        favoriteHelp.setText("Tap to heat to a favorite. Long-press to save the selected temperature there.");
        favorites.addView(favoriteHelp);

        // LED color
        LinearLayout led = card(column, "Mug light");
        swatch = new View(this);
        led.addView(swatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        redBar = slider(led, "Red");
        greenBar = slider(led, "Green");
        blueBar = slider(led, "Blue");
        Button[] presets = new Button[LedColor.PRESETS.length];
        for (int i = 0; i < presets.length; i++) {
            final int color = LedColor.PRESETS[i];
            Button preset = button(LedColor.PRESET_NAMES[i], v -> {
                showColor(color);
                applyColor(color);
            });
            preset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            preset.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | color));
            preset.setTextColor(isLight(color) ? Color.BLACK : Color.WHITE);
            presets[i] = preset;
        }
        led.addView(row(presets[0], presets[1], presets[2]));
        led.addView(row(presets[3], presets[4], presets[5]));
        led.addView(row(button("Apply color", v -> applyColor(sliderColor()))));
        ledInfoText = secondary(text(12, false));
        led.addView(ledInfoText);
        showColor(settings.ledColor());

        // Alerts and safety
        LinearLayout alerts = card(column, "Alerts and safety");
        alerts.addView(toggle("Notify me when the mug is empty or refilled", AppSettings.NOTIFY_EMPTY, null));
        alerts.addView(toggle("Turn the heater off when the mug is empty", AppSettings.OFF_WHEN_EMPTY, null));
        alerts.addView(toggle("Turn the heater off when my phone disconnects", AppSettings.OFF_ON_DISCONNECT,
                "Sent before you tap Disconnect. If the connection drops instead, the phone can't reach the "
                        + "mug, so the heater is turned off the moment it reconnects. Not a guaranteed shutoff."));
        alerts.addView(toggle("Notify me when my drink reaches the target", AppSettings.NOTIFY_TARGET, null));
        alerts.addView(toggle("Notify me when the mug disconnects", AppSettings.NOTIFY_DISCONNECT, null));
        alerts.addView(row(button("Background and battery settings", v -> openAppSettings())));
        TextView backgroundHelp = secondary(text(12, false));
        backgroundHelp.setText("The connection keeps running in the background with a status notification. "
                + "If your phone stops it, allow unrestricted battery use for this app.");
        alerts.addView(backgroundHelp);

        TextView footer = secondary(text(12, false));
        footer.setText("Independent app, not made by Ember. No account, no internet access.");
        footer.setPadding(0, dp(16), 0, 0);
        column.addView(footer);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            padForSystemBars(scroll);
        }
        return scroll;
    }

    /** Android 15+ draws apps edge to edge; keep content clear of the system bars. */
    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private static void padForSystemBars(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(16));
        background.setColor(0x18808080);
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        parent.addView(card, params);
        TextView title = text(13, true);
        title.setText(heading.toUpperCase(Locale.getDefault()));
        title.setTextColor(ACCENT);
        title.setLetterSpacing(0.08f);
        title.setPadding(0, 0, 0, dp(4));
        card.addView(title);
        return card;
    }

    private TextView text(int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView secondary(TextView view) {
        if (secondaryText != null) {
            view.setTextColor(secondaryText);
        }
        return view;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(onClick);
        return button;
    }

    private LinearLayout row(View... children) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (View child : children) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(child, params);
        }
        return row;
    }

    private SeekBar slider(LinearLayout parent, String label) {
        TextView name = secondary(text(13, false));
        name.setText(label);
        parent.addView(name);
        SeekBar bar = new SeekBar(this);
        bar.setMax(255);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (swatch != null && redBar != null && greenBar != null && blueBar != null) {
                    updateSwatch();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        parent.addView(bar);
        return bar;
    }

    private View toggle(String label, String key, String help) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toggle.setChecked(settings.flag(key));
        toggle.setOnCheckedChangeListener((view, checked) -> {
            settings.setFlag(key, checked);
            if (AppSettings.OFF_ON_DISCONNECT.equals(key) && !checked && service == null) {
                shutdowns.forget();
            }
            if (service != null) {
                service.onSettingsChanged();
            }
            render();
        });
        box.addView(toggle);
        if (help != null) {
            TextView helpText = secondary(text(12, false));
            helpText.setText(help);
            box.addView(helpText);
        }
        return box;
    }

    private static boolean isLight(int rgb) {
        return LedColor.red(rgb) * 299 + LedColor.green(rgb) * 587 + LedColor.blue(rgb) * 114 > 150_000;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
