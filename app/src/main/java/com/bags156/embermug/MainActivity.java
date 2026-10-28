package com.bags156.embermug;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

@SuppressLint({"MissingPermission","SetTextI18n"})
public final class MainActivity extends Activity {
    private static final int BG=0xff151916,CARD=0xff222820,INK=0xfff8f2e6,MUTED=0xffa2ad9b,ACCENT=0xffeaa174;
    private SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TemperatureDial dial;
    private TextView connection,status,target,battery,liquid,permissionNote;
    private Button saved,scan,heat,off,disconnect,led;
    private final Button[] favorites=new Button[3];
    private SeekBar slider;
    private boolean fahrenheit,scanAfterPermissions;
    private double selected=55;
    private BluetoothLeScanner scanner;
    private AlertDialog scanDialog;
    private final List<String> addresses=new ArrayList<>(),labels=new ArrayList<>();
    private ArrayAdapter<String> scanAdapter;
    private final Runnable refresh=this::render,scanTimeout=this::finishScan;
    private final Runnable tick=new Runnable() { public void run() { render(); main.postDelayed(this,5000); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs=getSharedPreferences(MugService.PREFS,MODE_PRIVATE);
        fahrenheit=prefs.getBoolean("fahrenheit",true); selected=state==null?prefs.getFloat("selected_c",55):state.getDouble("selected",55);
        selected=Math.max(EmberProtocol.toC(120),Math.min(63,selected)); build(); render();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putDouble("selected",selected); super.onSaveInstanceState(state); }
    @Override protected void onResume() { super.onResume(); MugStore.watch(refresh); main.post(tick); }
    @Override protected void onPause() { MugStore.unwatch(refresh); main.removeCallbacks(tick); stopScan(); if(scanDialog!=null) scanDialog.dismiss(); super.onPause(); }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size,int color) { TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(2),1); return v; }
    private GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams fill() { return new LinearLayout.LayoutParams(-1,-2); }
    private void gap(LinearLayout parent,int size) { parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(size))); }
    private LinearLayout card(LinearLayout parent) { LinearLayout c=column(); c.setPadding(dp(20),dp(18),dp(20),dp(18)); c.setBackground(shape(CARD,22)); parent.addView(c,fill()); return c; }
    private Button button(String label,boolean primary,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setMinHeight(dp(52));
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); b.setTextColor(primary?BG:INK);
        b.setBackgroundTintList(ColorStateList.valueOf(primary?ACCENT:0xff354031)); b.setOnClickListener(v -> action.run()); return b;
    }
    private void build() {
        ScrollView scroll=new ScrollView(this); scroll.setTag("mainScroll"); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root=column(); root.setPadding(dp(22),dp(20),dp(22),dp(28)); scroll.addView(root);
        if(Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        scroll.setOnApplyWindowInsetsListener((v,insets) -> {
            if(Build.VERSION.SDK_INT>=30) { Insets e=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); v.setPadding(e.left,e.top,e.right,e.bottom); }
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header=row(); TextView brand=text("ember mug",30,INK); brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1)); Button help=button("?",false,this::showHelp); help.setContentDescription("Setup and connection help"); header.addView(help,new LinearLayout.LayoutParams(dp(52),dp(52))); root.addView(header);
        root.addView(text("A little warmth. Your way.",14,MUTED)); gap(root,20);
        connection=text("",11,MUTED); connection.setLetterSpacing(.12f); root.addView(connection);
        dial=new TemperatureDial(this); root.addView(dial,new LinearLayout.LayoutParams(-1,dp(280)));
        LinearLayout stats=row(); battery=text("BATTERY\n—",14,INK); liquid=text("LIQUID\n—",14,INK);
        for(TextView v:new TextView[]{battery,liquid}) { v.setPadding(dp(18),dp(15),dp(12),dp(15)); v.setBackground(shape(CARD,18)); }
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,-2,1); half.setMarginEnd(dp(10)); stats.addView(battery,half); stats.addView(liquid,new LinearLayout.LayoutParams(0,-2,1)); root.addView(stats); gap(root,16);
        LinearLayout temp=card(root),title=row(); title.addView(text("YOUR TEMPERATURE",11,MUTED),new LinearLayout.LayoutParams(0,-2,1));
        Switch units=new Switch(this); units.setTag("units"); units.setText("°F  "); units.setTextColor(MUTED); units.setChecked(fahrenheit); units.setContentDescription("Display Fahrenheit instead of Celsius");
        units.setOnCheckedChangeListener((b,checked) -> { fahrenheit=checked; prefs.edit().putBoolean("fahrenheit",checked).apply(); configureSlider(); render(); }); title.addView(units); temp.addView(title);
        target=text("",29,INK); target.setTag("selectedTarget"); temp.addView(target); slider=new SeekBar(this); configureSlider(); slider.setProgressTintList(ColorStateList.valueOf(ACCENT)); slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int n,boolean user) { if(user) { selected=fahrenheit?EmberProtocol.toC(120+n/10.0):49+n/10.0; prefs.edit().putFloat("selected_c",(float)selected).apply(); render(); } }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {}
        }); temp.addView(slider,fill());
        LinearLayout presets=row();
        for(int i=0;i<3;i++) {
            final int slot=i; favorites[i]=button("",false,() -> {
                selected=favorite(slot); configureSlider(); prefs.edit().putFloat("selected_c",(float)selected).apply();
                if(ConnectionPolicy.mayHeat(MugStore.connected,MugStore.busy||MugStore.shuttingDown,MugStore.liquid,SystemClock.elapsedRealtime()-MugStore.stateAt)) send(MugService.HEAT);
                render();
            }); favorites[i].setTag("favorite"+i); favorites[i].setTextSize(13); presets.addView(favorites[i],new LinearLayout.LayoutParams(0,-2,1));
        }
        temp.addView(presets); Button edit=button("Edit my 3 favorites",false,this::editFavorites); edit.setTag("editFavorites"); temp.addView(edit,fill());
        temp.addView(text("Tap a favorite to select it and request heat when connected and reporting liquid.",12,MUTED)); gap(temp,10);
        heat=button("Heat now",true,() -> send(MugService.HEAT)); heat.setTag("heat"); temp.addView(heat,fill());
        off=button("Turn heater off",false,() -> send(MugService.OFF)); temp.addView(off,fill());
        temp.addView(text("Heat now reapplies your target. The mug controls the heater and retains its liquid and thermal protections.",12,MUTED)); gap(root,16);
        LinearLayout connectivity=card(root); connectivity.addView(text("CONNECTION",11,MUTED)); gap(connectivity,8);
        status=text("",14,INK); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); connectivity.addView(status); gap(connectivity,8);
        saved=button("Connect to saved mug",true,() -> { if(ensurePermissions(false)) connectSaved(); }); connectivity.addView(saved,fill());
        scan=button("Find my mug",false,() -> { if(ensurePermissions(true)) beginScan(); }); connectivity.addView(scan,fill());
        disconnect=button("Disconnect",false,() -> send(MugService.DISCONNECT)); connectivity.addView(disconnect,fill());
        connectivity.addView(text("The connection stays active when you leave this screen. Android shows a persistent notification while the service runs.",12,MUTED)); gap(root,16);
        LinearLayout colors=card(root); colors.addView(text("MAKE IT YOURS",11,MUTED)); gap(colors,8);
        led=button("Choose LED color",false,this::chooseColor); led.setTag("chooseLed"); colors.addView(led,fill());
        colors.addView(text("Pick any RGB color. Firmware can temporarily show charging, heating or pairing colors.",12,MUTED)); gap(root,16);
        LinearLayout settings=card(root); settings.addView(text("LOOKING AFTER YOUR MUG",11,MUTED)); gap(settings,10);
        setting(settings,"Empty-mug notification","notify_empty"); setting(settings,"Turn heater off when empty","off_empty"); setting(settings,"Heater off on disconnect","off_disconnect");
        settings.addView(text("An intentional disconnect sends heater off first. If the link drops, the app alerts you and sends heater off after reconnection. Immediate shutoff after signal loss cannot be guaranteed.",12,MUTED)); gap(settings,12);
        permissionNote=text("",12,ACCENT); settings.addView(permissionNote);
        settings.addView(button("Notification settings",false,() -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))),fill());
        gap(root,20); TextView footer=text("LOCAL CONTROL  ·  NO ACCOUNT  ·  NO CLOUD",10,MUTED); footer.setGravity(Gravity.CENTER); footer.setLetterSpacing(.08f); root.addView(footer); setContentView(scroll);
    }
    private void setting(LinearLayout parent,String label,String key) {
        Switch v=new Switch(this); v.setText(label); v.setTextColor(INK); v.setTextSize(14); v.setMinHeight(dp(52)); v.setChecked(prefs.getBoolean(key,true));
        v.setOnCheckedChangeListener((b,checked) -> { prefs.edit().putBoolean(key,checked).apply(); if(MugStore.running) send(MugService.SETTINGS); }); parent.addView(v,fill());
    }
    private double favorite(int i) { return prefs.getFloat("favorite_"+i,(float)FavoriteTemperatures.defaultC(i)); }
    private String temp(double c) { return String.format(Locale.getDefault(),"%.1f°%s",fahrenheit?EmberProtocol.toF(c):c,fahrenheit?"F":"C"); }
    private void configureSlider() {
        if(slider==null) return; slider.setMax(fahrenheit?250:140); slider.setProgress((int)Math.round((fahrenheit?EmberProtocol.toF(selected)-120:selected-49)*10));
        slider.setContentDescription(fahrenheit?"Target temperature from 120 to 145 degrees Fahrenheit":"Target temperature from 49 to 63 degrees Celsius");
    }
    private void render() {
        if(dial==null) return;
        connection.setText(MugStore.connected?"●  CONNECTED":MugStore.running?"○  RECONNECTING":"○  DISCONNECTED"); connection.setTextColor(MugStore.connected?ACCENT:MUTED);
        boolean fresh=MugStore.connected && SystemClock.elapsedRealtime()-MugStore.stateAt<=30_000;
        dial.update(fresh?MugStore.current:Double.NaN,fahrenheit,fresh?EmberProtocol.stateLabel(MugStore.liquid):"Waiting for mug"); target.setText(temp(selected));
        String confirmed=Double.isNaN(MugStore.target)?"":MugStore.target==0?"\nMug target: heater off":"\nMug target: "+temp(MugStore.target); status.setText(MugStore.status+confirmed);
        battery.setText("BATTERY\n"+(MugStore.battery<0?"—":MugStore.battery+"%"+(MugStore.charging?" · on coaster":""))); liquid.setText("LIQUID\n"+(fresh?EmberProtocol.stateLabel(MugStore.liquid):"—"));
        heat.setEnabled(ConnectionPolicy.mayHeat(MugStore.connected,MugStore.busy||MugStore.shuttingDown,MugStore.liquid,SystemClock.elapsedRealtime()-MugStore.stateAt)); heat.setAlpha(heat.isEnabled()?1f:.45f); heat.setText(MugStore.busy?"Checking mug…":"Heat now · "+temp(selected));
        off.setEnabled(MugStore.running); off.setText(MugStore.shuttingDown?"Retry heater off":"Turn heater off");
        saved.setVisibility(!MugStore.running && !prefs.getString("address","").isEmpty()?View.VISIBLE:View.GONE); scan.setVisibility(MugStore.running?View.GONE:View.VISIBLE); disconnect.setVisibility(MugStore.running?View.VISIBLE:View.GONE);
        disconnect.setText(prefs.getBoolean("off_disconnect",true)?"Heater off & disconnect":"Disconnect");
        for(int i=0;i<3;i++) if(favorites[i]!=null) { favorites[i].setText(temp(favorite(i))); favorites[i].setContentDescription("Favorite "+(i+1)+", "+temp(favorite(i))); }
        if(led!=null) { led.setEnabled(MugStore.connected && MugStore.supportsLed && !MugStore.busy && !MugStore.shuttingDown); led.setText(MugStore.led==null?"Choose LED color":"LED · "+String.format(Locale.ROOT,"#%06X",MugStore.led&0xffffff)); }
        permissionNote.setText(getSystemService(NotificationManager.class).areNotificationsEnabled()?"Alerts are enabled.":"Notifications are blocked. Enable them for empty-mug and connection alerts.");
    }
    private void editFavorites() {
        LinearLayout fields=column(); fields.setPadding(dp(24),dp(8),dp(24),dp(4)); EditText[] inputs=new EditText[3];
        for(int i=0;i<3;i++) {
            fields.addView(text("Favorite "+(i+1)+(fahrenheit?" · °F":" · °C"),14,MUTED));
            inputs[i]=new EditText(this); inputs[i].setTag("favoriteInput"+i); inputs[i].setTextColor(INK); inputs[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            inputs[i].setText(String.format(Locale.ROOT,"%.1f",fahrenheit?EmberProtocol.toF(favorite(i)):favorite(i))); fields.addView(inputs[i],fill());
        }
        fields.addView(text(fahrenheit?"Choose 120–145°F.":"Choose about 49–63°C.",12,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Your three favorites").setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double[] values=new double[3];
            for(int i=0;i<3;i++) try {
                double display=Double.parseDouble(inputs[i].getText().toString().trim().replace(',','.'));
                if(!Double.isFinite(display) || display<(fahrenheit?120:48.88) || display>(fahrenheit?145:63)) throw new IllegalArgumentException();
                values[i]=FavoriteTemperatures.parse(inputs[i].getText().toString(),fahrenheit);
            } catch(IllegalArgumentException e) { inputs[i].setError(fahrenheit?"Use 120–145°F":"Use about 49–63°C"); return; }
            SharedPreferences.Editor editor=prefs.edit(); for(int i=0;i<3;i++) editor.putFloat("favorite_"+i,(float)values[i]); editor.apply(); render(); dialog.dismiss();
        })); dialog.show();
    }
    private void chooseColor() {
        LinearLayout panel=column(); panel.setPadding(dp(24),dp(8),dp(24),dp(8)); int initial=MugStore.led==null?prefs.getInt("led_color",ACCENT):MugStore.led;
        int[] rgb={Color.red(initial),Color.green(initial),Color.blue(initial)}; TextView preview=text("",20,INK); preview.setGravity(Gravity.CENTER); preview.setPadding(dp(12),dp(22),dp(12),dp(22)); panel.addView(preview,fill());
        Runnable update=() -> { int color=Color.rgb(rgb[0],rgb[1],rgb[2]); preview.setBackground(shape(color,16)); preview.setTextColor(rgb[0]*299+rgb[1]*587+rgb[2]*114>145000?BG:Color.WHITE); preview.setText(String.format(Locale.ROOT,"#%02X%02X%02X",rgb[0],rgb[1],rgb[2])); };
        SeekBar[] bars=new SeekBar[3]; String[] names={"Red","Green","Blue"};
        for(int i=0;i<3;i++) {
            final int ch=i; panel.addView(text(names[i],13,MUTED)); bars[i]=new SeekBar(this); bars[i].setMax(255); bars[i].setProgress(rgb[i]); bars[i].setContentDescription(names[i]+" LED intensity");
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar b,int v,boolean user) { rgb[ch]=v; update.run(); } public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {} }); panel.addView(bars[i],fill());
        }
        int[] palette={0xffeaa174,0xffff4646,0xff59ce8f,0xff66aaff,0xffbc8cff,0xffffffff}; String[] names2={"Amber","Red","Green","Blue","Purple","White"};
        for(int r=0;r<2;r++) { LinearLayout colors=row(); for(int j=0;j<3;j++) { final int index=r*3+j,color=palette[index]; Button swatch=button(names2[index],false,() -> { bars[0].setProgress(Color.red(color)); bars[1].setProgress(Color.green(color)); bars[2].setProgress(Color.blue(color)); }); swatch.setTextSize(12); colors.addView(swatch,new LinearLayout.LayoutParams(0,-2,1)); } panel.addView(colors); }
        update.run(); new AlertDialog.Builder(this).setTitle("Your mug. Your color.").setView(panel).setNegativeButton("Cancel",null).setPositiveButton("Set LED",(d,w) -> {
            if(!MugStore.connected) { toast("Reconnect your mug before changing its LED."); return; }
            try { startService(new Intent(this,MugService.class).setAction(MugService.LED).putExtra("color",Color.rgb(rgb[0],rgb[1],rgb[2]))); } catch(SecurityException | IllegalStateException e) { toast("Reconnect your mug and try again."); }
        }).show();
    }
    private boolean ensurePermissions(boolean forScan) {
        scanAfterPermissions=forScan; List<String> needed=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31) { for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) needed.add(p); }
        else if(forScan && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("asked_notifications",false)) { needed.add(Manifest.permission.POST_NOTIFICATIONS); prefs.edit().putBoolean("asked_notifications",true).apply(); }
        if(needed.isEmpty()) return true; requestPermissions(needed.toArray(new String[0]),100); return false;
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants); if(request!=100) return;
        if(grants.length!=permissions.length || grants.length==0) { toast("Permission request was cancelled. Tap connect to try again."); return; }
        for(int i=0;i<permissions.length;i++) if(!permissions[i].equals(Manifest.permission.POST_NOTIFICATIONS) && grants[i]!=PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle("Bluetooth access needed").setMessage("Allow Nearby devices (or Location on Android 8–11) in app settings to connect.").setPositiveButton("App settings",(d,w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())))).setNegativeButton("Close",null).show(); return;
        }
        if(scanAfterPermissions) beginScan(); else connectSaved();
    }
    private BluetoothAdapter adapter() { BluetoothManager m=getSystemService(BluetoothManager.class); return m==null?null:m.getAdapter(); }
    private boolean bluetoothReady() {
        BluetoothAdapter a=adapter(); if(a==null) { toast("This phone does not support Bluetooth LE."); return false; }
        if(!a.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),101); return false; } return true;
    }
    @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data); if(request==101 && result==RESULT_OK) { if(scanAfterPermissions) beginScan(); else connectSaved(); } }
    private void connectSaved() { if(bluetoothReady()) startConnection(prefs.getString("address","")); }
    private void startConnection(String address) {
        if(!BluetoothAdapter.checkBluetoothAddress(address)) { toast("Find your mug first."); return; }
        try { startForegroundService(new Intent(this,MugService.class).setAction(MugService.CONNECT).putExtra("address",address)); } catch(SecurityException | IllegalStateException e) { toast("Could not start the connection. Check Bluetooth permissions."); }
    }
    private void send(String action) {
        if(!MugStore.running) return;
        try { startService(new Intent(this,MugService.class).setAction(action).putExtra("celsius",selected)); } catch(SecurityException | IllegalStateException e) { toast("Reconnect your mug and try again."); }
    }
    private void beginScan() {
        if(!bluetoothReady()) return; stopScan(); addresses.clear(); labels.clear(); scanAdapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels);
        scanDialog=new AlertDialog.Builder(this).setTitle("Looking for your Ember…").setAdapter(scanAdapter,(d,which) -> { String address=addresses.get(which); stopScan(); startConnection(address); }).setNegativeButton("Cancel",(d,w) -> stopScan()).create(); scanDialog.setOnCancelListener(d -> stopScan()); scanDialog.show();
        try { scanner=adapter().getBluetoothLeScanner(); if(scanner==null) { finishScan(); return; } scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback); main.postDelayed(scanTimeout,12000); }
        catch(SecurityException | IllegalStateException e) { stopScan(); scanDialog.dismiss(); toast("Scan failed. Check Bluetooth and Nearby devices permissions."); }
    }
    private final ScanCallback scanCallback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult r) { main.post(() -> found(r)); }
        @Override public void onBatchScanResults(List<ScanResult> results) { main.post(() -> { for(ScanResult r:results) found(r); }); }
        @Override public void onScanFailed(int error) { main.post(() -> { stopScan(); if(scanDialog!=null) scanDialog.setTitle("Scan failed ("+error+"). Retry shortly."); }); }
    };
    private void found(ScanResult result) {
        if(scanner==null || scanDialog==null || !scanDialog.isShowing()) return;
        try {
            ScanRecord record=result.getScanRecord(); String name=record==null?null:record.getDeviceName(); if(name==null) name=result.getDevice().getName();
            boolean ember=name!=null && name.toLowerCase(Locale.ROOT).contains("ember");
            if(record!=null) ember|=record.getManufacturerSpecificData(0x03c1)!=null || (record.getServiceUuids()!=null && record.getServiceUuids().contains(new ParcelUuid(EmberProtocol.SERVICE)));
            if(!ember) return; String address=result.getDevice().getAddress(); if(addresses.contains(address)) return;
            addresses.add(address); labels.add((name==null?"Ember Mug":name)+"\n"+address); scanAdapter.notifyDataSetChanged(); scanDialog.setTitle("Choose your Ember Mug");
        } catch(SecurityException e) { stopScan(); toast("Bluetooth permission was removed."); }
    }
    private void stopScan() { main.removeCallbacks(scanTimeout); BluetoothLeScanner old=scanner; scanner=null; if(old!=null) try { old.stopScan(scanCallback); } catch(SecurityException | IllegalStateException ignored) {} }
    private void finishScan() {
        stopScan(); if(scanDialog!=null && scanDialog.isShowing() && addresses.isEmpty()) { scanDialog.dismiss(); new AlertDialog.Builder(this).setTitle("No Ember found").setMessage("Keep the mug close and in pairing mode (flashing blue). Close other Ember apps. On Android 8–11 enable phone Location. This app targets Ember Mug / Mug 2.").setPositiveButton("Try again",(d,w) -> { if(ensurePermissions(true)) beginScan(); }).setNegativeButton("Close",null).show(); }
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("Make yourself at home").setMessage("1. If the mug is new or reset, finish setup once in the official Ember app. Some firmware requires it before accepting writes.\n\n2. Close other controllers. Put the mug in pairing mode (flashing blue), then tap Find my mug.\n\n3. Allow Nearby devices and notifications. Start the connection while this app is open.\n\nHeat now requests your target. It cannot bypass firmware, empty detection or thermal protections.\n\nImmediate heater off cannot be sent after Bluetooth loss. Keep the connection service running so it can reconnect and send heater off. Phone shutdown, app force-stop or Android stopping the service can prevent delivery.\n\nVersion 0.1 • intended for Ember Mug / Mug 2; real-mug verification is required.").setPositiveButton("Got it",null).show();
    }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}
