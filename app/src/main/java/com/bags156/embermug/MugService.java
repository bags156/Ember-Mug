package com.bags156.embermug;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.*;
import android.os.*;

/** The foreground service owns the connection independently of Activity lifetime. */
@SuppressLint("ApplySharedPref") // Durable shutdown intent must precede a heater-on command.
public final class MugService extends Service implements GattClient.Listener {
    public static final String PREFS="mug",CONNECT="connect",HEAT="heat",OFF="off",DISCONNECT="disconnect",SETTINGS="settings",LED="led";
    private static final String CONNECTION="connection",ALERTS="alerts";
    private final Handler main=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private GattClient client;
    private ConnectionPolicy policy;
    private String address="";
    private int retry,command;
    private boolean stopRequested,emptyOffAttempted;
    private final Runnable reconnect=() -> connectNow();
    private final Runnable shutdownRetry=() -> off(stopRequested);
    private final Runnable poll=new Runnable() {
        public void run() {
            if(!client.isReady()) return;
            client.read(EmberProtocol.STATE); client.read(EmberProtocol.CURRENT);
            client.read(EmberProtocol.TARGET); client.read(EmberProtocol.BATTERY);
            main.postDelayed(this,12_000);
        }
    };
    private final BroadcastReceiver bluetooth=new BroadcastReceiver() {
        public void onReceive(Context c,Intent intent) {
            int state=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR);
            if(state==BluetoothAdapter.STATE_OFF || state==BluetoothAdapter.STATE_TURNING_OFF) {
                client.close(); lost("Phone Bluetooth is off. Heater off must wait for reconnection.");
            } else if(state==BluetoothAdapter.STATE_ON && MugStore.running && !MugStore.connected) {
                main.removeCallbacks(reconnect); main.post(reconnect);
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate(); prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        policy=new ConnectionPolicy(prefs.getBoolean("pending_off",false)); client=new GattClient(this,this);
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CONNECTION,"Mug connection",NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(ALERTS,"Mug alerts",NotificationManager.IMPORTANCE_DEFAULT));
        if(Build.VERSION.SDK_INT>=33) registerReceiver(bluetooth,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetooth,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY; }
        String action=intent==null?null:intent.getAction();
        if(!MugStore.running && action!=null && !CONNECT.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if(!MugStore.running && prefs.getBoolean("session_active",false)) { policy.disconnected(offOnDisconnect()); persist(); }
        address=prefs.getString("address","");
        if(CONNECT.equals(action)) {
            String requested=intent.getStringExtra("address");
            if(requested==null || !BluetoothAdapter.checkBluetoothAddress(requested)) { stopSelf(); return START_NOT_STICKY; }
            if(!address.isEmpty() && !address.equals(requested) && policy.pendingOff()) {
                status("Reconnect the previous mug to confirm heater off before changing mugs.");
                if(!MugStore.running) { stopSelf(); return START_NOT_STICKY; }
                return START_STICKY;
            }
            address=requested;
            prefs.edit().putString("address",address).putBoolean("session_active",true).commit();
        }
        if(address.isEmpty() || (intent==null && !prefs.getBoolean("session_active",false))) { stopSelf(); return START_NOT_STICKY; }
        boolean wasRunning=MugStore.running; MugStore.running=true; MugStore.address=address;
        if(Build.VERSION.SDK_INT>=29) startForeground(1,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1,notification());
        if(!wasRunning || CONNECT.equals(action)) { stopRequested=false; main.removeCallbacks(reconnect); if(!MugStore.connected) connectNow(); }
        if(HEAT.equals(action)) heat(intent.getDoubleExtra("celsius",55));
        else if(LED.equals(action)) color(intent.getIntExtra("color",0xffeaa174));
        else if(OFF.equals(action)) off(false);
        else if(DISCONNECT.equals(action)) { stopRequested=true; if(offOnDisconnect() || policy.pendingOff()) off(true); else stopSession(); }
        else if(SETTINGS.equals(action)) {
            if(offOnDisconnect() && MugStore.target>0) { policy.arm(true); persist(); }
            if(!offOnDisconnect() && MugStore.connected && !MugStore.shuttingDown) { policy.disarm(); persist(); }
            MugStore.changed();
        }
        return START_STICKY;
    }
    private boolean offOnDisconnect() { return prefs.getBoolean("off_disconnect",true); }
    private boolean persist() { return prefs.edit().putBoolean("pending_off",policy.pendingOff()).commit(); }
    private void connectNow() {
        if(!MugStore.running) return;
        MugStore.connected=false; MugStore.busy=false; MugStore.clearTelemetry();
        status("Connecting to your mug…"); client.connect(address);
    }
    @Override public void ready() {
        retry=0; MugStore.connected=true; MugStore.clearTelemetry(); MugStore.supportsLed=client.supportsLed();
        getSystemService(NotificationManager.class).cancel(3);
        if(policy.pendingOff()) off(stopRequested); else { MugStore.shuttingDown=false; status("Connected • checking your mug"); }
        main.removeCallbacks(poll); main.post(poll);
        if(MugStore.supportsLed) client.read(EmberProtocol.LED);
    }
    @Override public void lost(String reason) {
        if(!MugStore.running) return;
        boolean wasConnected=MugStore.connected;
        command++; MugStore.connected=false; MugStore.busy=false; MugStore.clearTelemetry();
        policy.disconnected(offOnDisconnect()); persist(); MugStore.shuttingDown=policy.pendingOff();
        main.removeCallbacks(poll); main.removeCallbacks(shutdownRetry); main.removeCallbacks(reconnect);
        status(reason+(policy.pendingOff()?" Will send heater off on reconnection.":" Reconnecting…"));
        if(wasConnected) alert(3,"Mug disconnected","Heater state is unknown. Immediate shutoff is impossible without Bluetooth. "+(policy.pendingOff()?"Heater off is queued for reconnection.":"Automatic shutdown is disabled."));
        main.postDelayed(reconnect,ConnectionPolicy.retryDelay(retry++));
    }
    private void heat(double c) {
        if(!ConnectionPolicy.mayHeat(MugStore.connected,MugStore.busy||MugStore.shuttingDown,MugStore.liquid,SystemClock.elapsedRealtime()-MugStore.stateAt)) { status("Wait for a connected mug with a fresh liquid reading before heating."); return; }
        try { EmberProtocol.target(c); } catch(IllegalArgumentException e) { status(e.getMessage()); return; }
        final int token=++command; MugStore.busy=true; status("Checking liquid before requesting heat…");
        client.read(EmberProtocol.STATE,b -> {
            if(token!=command) return;
            if(b==null || !EmberProtocol.hasLiquid(MugStore.liquid)) { MugStore.busy=false; status("Heat not requested: the mug has not confirmed liquid."); return; }
            policy.arm(offOnDisconnect());
            if(!persist()) { MugStore.busy=false; status("Could not save shutdown policy. Heat was not requested."); return; }
            client.setTarget(c,(ok,message) -> {
                if(token!=command) return;
                MugStore.busy=false; if(ok) prefs.edit().putFloat("selected_c",(float)c).apply(); status(message);
                if(!ok) alert(4,"Heat command unconfirmed",message);
            });
        });
    }
    private void color(int color) {
        if(!MugStore.connected || MugStore.busy || MugStore.shuttingDown) { status("Wait until the mug is ready to change its LED."); return; }
        final int token=++command; MugStore.busy=true; status("Setting your mug’s LED color…");
        client.setColor(color,(ok,message) -> {
            if(token!=command) return;
            MugStore.busy=false; if(ok) prefs.edit().putInt("led_color",color).apply(); status(message);
        });
    }
    private void off(boolean disconnectAfter) {
        stopRequested=disconnectAfter; policy.arm(true); persist(); MugStore.shuttingDown=true;
        main.removeCallbacks(shutdownRetry);
        if(!MugStore.connected || !client.isReady()) { status("Heater off queued. Reconnecting to send and verify it."); return; }
        final int token=++command; MugStore.busy=true; status("Sending heater off…");
        client.setTarget(0,(ok,message) -> {
            if(token!=command) return;
            MugStore.busy=false;
            if(ok) {
                policy.confirmedOff(); persist(); MugStore.shuttingDown=false; getSystemService(NotificationManager.class).cancel(4);
                status(message); if(stopRequested) stopSession();
            } else {
                status(message+" Retrying heater off."); alert(4,"Heater off is unconfirmed",message+" The app will retry while connected."); main.postDelayed(shutdownRetry,15_000);
            }
        });
    }
    @Override public void data(int id,byte[] b) {
        try {
            if(id==EmberProtocol.CURRENT) MugStore.current=EmberProtocol.temperature(b);
            else if(id==EmberProtocol.TARGET) {
                MugStore.target=EmberProtocol.temperature(b);
                if(MugStore.target>0 && offOnDisconnect()) { policy.arm(true); persist(); }
            } else if(id==EmberProtocol.BATTERY) {
                int n=EmberProtocol.unsigned(b); MugStore.battery=n<=100?n:-1; MugStore.charging=b.length>1 && b[1]!=0;
            } else if(id==EmberProtocol.LED) MugStore.led=EmberProtocol.color(b);
            else if(id==EmberProtocol.STATE) {
                int previous=MugStore.liquid; MugStore.liquid=EmberProtocol.unsigned(b); MugStore.stateAt=SystemClock.elapsedRealtime();
                if(MugStore.liquid==1 && previous!=1 && prefs.getBoolean("notify_empty",true)) alert(2,"Your mug is empty","The mug reports no liquid. Refill before requesting heat.");
                if(MugStore.liquid!=1) emptyOffAttempted=false;
                if(previous==-1 && !MugStore.busy && !MugStore.shuttingDown) MugStore.status="Connected • "+EmberProtocol.stateLabel(MugStore.liquid);
            } else if(id==EmberProtocol.EVENTS) {
                int e=EmberProtocol.unsigned(b);
                if(e>=1 && e<=3) client.read(EmberProtocol.BATTERY);
                if(e==4) client.read(EmberProtocol.TARGET);
                if(e==5) client.read(EmberProtocol.CURRENT);
                if(e==7 || e==8) client.read(EmberProtocol.STATE);
                if(e==6) status("Mug needs official-app setup before it accepts writes.");
            }
            if(MugStore.liquid==1 && MugStore.target>0 && !emptyOffAttempted && !MugStore.shuttingDown && prefs.getBoolean("off_empty",true)) { emptyOffAttempted=true; off(false); }
            MugStore.changed(); updateNotification();
        } catch(IllegalArgumentException e) { status("Incomplete mug data. Waiting for another reading."); }
    }
    private void status(String message) { MugStore.status=message; MugStore.changed(); if(MugStore.running) updateNotification(); }
    private PendingIntent openApp() { return PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); }
    private PendingIntent action(String value,int code) { return PendingIntent.getService(this,code,new Intent(this,MugService.class).setAction(value),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); }
    private Notification notification() {
        return new Notification.Builder(this,CONNECTION).setSmallIcon(R.drawable.ic_mug).setContentTitle(MugStore.connected?"Ember Mug • connected":"Ember Mug • reconnecting")
            .setContentText(MugStore.status).setStyle(new Notification.BigTextStyle().bigText(MugStore.status)).setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"Heater off",action(OFF,1)).build()).addAction(new Notification.Action.Builder(null,"Disconnect",action(DISCONNECT,2)).build()).build();
    }
    private void updateNotification() { getSystemService(NotificationManager.class).notify(1,notification()); }
    private void alert(int id,String title,String message) {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return;
        getSystemService(NotificationManager.class).notify(id,new Notification.Builder(this,ALERTS).setSmallIcon(R.drawable.ic_mug).setContentTitle(title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(openApp()).setAutoCancel(true).build());
    }
    private void stopSession() {
        boolean confirmedOff=MugStore.target==0;
        prefs.edit().putBoolean("session_active",false).commit();
        MugStore.running=false; client.close(); main.removeCallbacksAndMessages(null);
        MugStore.connected=MugStore.busy=MugStore.shuttingDown=false; MugStore.clearTelemetry();
        MugStore.status=confirmedOff?"Disconnected • heater off confirmed":"Disconnected • heater state is no longer monitored";
        MugStore.changed(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null); client.close(); unregisterReceiver(bluetooth);
        MugStore.running=MugStore.connected=MugStore.busy=false; MugStore.clearTelemetry(); MugStore.changed(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
