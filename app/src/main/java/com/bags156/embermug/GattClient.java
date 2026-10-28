package com.bags156.embermug;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.*;
import java.util.*;

/** One acknowledged operation at a time. A timeout closes the entire connection. */
@SuppressLint("MissingPermission")
final class GattClient {
    interface Listener { void ready(); void data(int id,byte[] value); void lost(String reason); }
    interface Result { void done(boolean ok,String message); }
    interface ReadResult { void done(byte[] value); }
    private static final int READ=0,WRITE=1,SUBSCRIBE=2;
    private record Op(int id,int kind,byte[] value,Result result,ReadResult reader) {}
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Deque<Op> queue=new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattService service;
    private Op active;
    private Runnable deadline;
    GattClient(Context context,Listener listener) { this.context=context; this.listener=listener; }
    void connect(String address) {
        close();
        try {
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null || !adapter.isEnabled()) { listener.lost("Bluetooth is off. Turn it on to reconnect."); return; }
            gatt=adapter.getRemoteDevice(address).connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null) { listener.lost("Android could not start Bluetooth."); return; }
            timeout(30_000,"Mug did not respond. Check range, power and pairing mode.");
        } catch(SecurityException | IllegalArgumentException e) { fail("Bluetooth permission or device address is unavailable."); }
    }
    boolean isReady() { return gatt!=null && service!=null; }
    boolean supportsLed() {
        BluetoothGattCharacteristic c=service==null?null:service.getCharacteristic(EmberProtocol.uuid(EmberProtocol.LED));
        return c!=null && (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0;
    }
    void read(int id) { read(id,null); }
    void read(int id,ReadResult reader) {
        if(!isReady()) { if(reader!=null) reader.done(null); return; }
        if(reader==null && ((active!=null && active.id==id && active.kind==READ) || queue.stream().anyMatch(o -> o.id==id && o.kind==READ))) return;
        queue.addLast(new Op(id,READ,null,null,reader)); drain();
    }
    void setTarget(double c,Result done) {
        final byte[] value;
        try { value=EmberProtocol.target(c); } catch(IllegalArgumentException e) { done.done(false,e.getMessage()); return; }
        if(!isReady()) { done.done(false,"Mug disconnected; heater state is unconfirmed."); return; }
        if(c==0) queue.removeIf(o -> o.kind==WRITE && o.id==EmberProtocol.TARGET);
        queue.addFirst(new Op(EmberProtocol.TARGET,WRITE,value,(ok,message) -> {
            if(!ok) { done.done(false,message); return; }
            queue.addFirst(new Op(EmberProtocol.TARGET,READ,null,null,b -> {
                boolean verified=false;
                try { verified=EmberProtocol.matchesTarget(c,EmberProtocol.temperature(b)); } catch(IllegalArgumentException ignored) {}
                done.done(verified,verified?(c==0?"Heater off • confirmed by mug":"Target confirmed by mug"):
                    "Mug did not confirm the target. Heater state is unconfirmed. Check official-app setup.");
            }));
        },null)); drain();
    }
    void setColor(int color,Result done) {
        if(!isReady() || !supportsLed()) { done.done(false,"LED control is unavailable on this mug."); return; }
        byte[] value=EmberProtocol.color(color);
        queue.addLast(new Op(EmberProtocol.LED,WRITE,value,(ok,message) -> {
            if(!ok) { done.done(false,message); return; }
            queue.addFirst(new Op(EmberProtocol.LED,READ,null,null,b -> {
                boolean verified=b!=null && b.length>=4 && b[0]==value[0] && b[1]==value[1] && b[2]==value[2];
                done.done(verified,verified?"LED color confirmed by mug":"Mug did not confirm the LED color.");
            }));
        },null)); drain();
    }
    private void subscribe() {
        BluetoothGattCharacteristic c=service.getCharacteristic(EmberProtocol.uuid(EmberProtocol.EVENTS));
        if(c!=null && c.getDescriptor(EmberProtocol.CCCD)!=null) queue.addLast(new Op(EmberProtocol.EVENTS,SUBSCRIBE,null,null,null));
        drain();
    }
    @SuppressWarnings("deprecation")
    private void drain() {
        if(active!=null || !isReady() || queue.isEmpty()) return;
        active=queue.removeFirst();
        BluetoothGattCharacteristic c=service.getCharacteristic(EmberProtocol.uuid(active.id));
        if(c==null) { finish(false,null,"Control is unsupported by this mug."); return; }
        boolean accepted;
        try {
            if(active.kind==READ) accepted=gatt.readCharacteristic(c);
            else if(active.kind==WRITE) {
                if((c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0) { finish(false,null,"Mug does not advertise acknowledged writes."); return; }
                if(Build.VERSION.SDK_INT>=33) accepted=gatt.writeCharacteristic(c,active.value,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS;
                else { c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); c.setValue(active.value); accepted=gatt.writeCharacteristic(c); }
            } else {
                BluetoothGattDescriptor descriptor=c.getDescriptor(EmberProtocol.CCCD);
                byte[] enable=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                accepted=gatt.setCharacteristicNotification(c,true);
                if(accepted && Build.VERSION.SDK_INT>=33) accepted=gatt.writeDescriptor(descriptor,enable)==BluetoothStatusCodes.SUCCESS;
                else if(accepted) { descriptor.setValue(enable); accepted=gatt.writeDescriptor(descriptor); }
            }
        } catch(SecurityException e) { fail("Bluetooth permission was removed. Reopen the app to grant it."); return; }
        if(!accepted) { finish(false,null,"Android rejected the Bluetooth operation."); return; }
        timeout(15_000,"Bluetooth operation timed out; heater state is unconfirmed.");
    }
    private void finish(boolean ok,byte[] value,String message) {
        cancelDeadline(); Op done=active; active=null;
        if(done!=null) {
            if(ok && done.kind==READ && value!=null) listener.data(done.id,value);
            if(done.reader!=null) done.reader.done(ok?value:null);
            if(done.result!=null) done.result.done(ok,message);
        }
        drain();
    }
    private void timeout(long ms,String message) { cancelDeadline(); deadline=() -> fail(message); main.postDelayed(deadline,ms); }
    private void cancelDeadline() { if(deadline!=null) main.removeCallbacks(deadline); deadline=null; }
    private void fail(String message) { close(); listener.lost(message); }
    void close() {
        cancelDeadline(); queue.clear(); active=null; service=null;
        BluetoothGatt old=gatt; gatt=null;
        if(old!=null) try { old.disconnect(); old.close(); } catch(SecurityException ignored) {}
    }
    private void post(BluetoothGatt source,Runnable r) { main.post(() -> { if(source==gatt) r.run(); }); }
    private boolean expects(BluetoothGattCharacteristic c,int kind) { return active!=null && active.kind==kind && c.getUuid().equals(EmberProtocol.uuid(active.id)); }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source,int status,int state) {
            post(source,() -> {
                if(status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) {
                    try { if(!source.discoverServices()) fail("Could not discover mug controls."); } catch(SecurityException e) { fail("Bluetooth permission is required."); }
                } else if(state==BluetoothProfile.STATE_DISCONNECTED || status!=BluetoothGatt.GATT_SUCCESS) fail("Mug disconnected (Bluetooth "+status+").");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt source,int status) {
            post(source,() -> {
                cancelDeadline(); service=source.getService(EmberProtocol.SERVICE);
                if(status!=BluetoothGatt.GATT_SUCCESS || service==null || service.getCharacteristic(EmberProtocol.uuid(EmberProtocol.TARGET))==null || service.getCharacteristic(EmberProtocol.uuid(EmberProtocol.STATE))==null) {
                    fail("Compatible Ember Mug service not found. This version targets Mug / Mug 2."); return;
                }
                listener.ready(); subscribe();
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] value,int status) {
            byte[] copy=Arrays.copyOf(value,value.length);
            post(source,() -> { if(expects(c,READ)) finish(status==BluetoothGatt.GATT_SUCCESS,copy,"Mug rejected the read ("+status+")."); });
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic c,int status) {
            if(Build.VERSION.SDK_INT<33) onCharacteristicRead(source,c,c.getValue()==null?new byte[0]:c.getValue(),status);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt source,BluetoothGattCharacteristic c,int status) {
            post(source,() -> { if(expects(c,WRITE)) finish(status==BluetoothGatt.GATT_SUCCESS,null,"Mug rejected the write ("+status+"). Check pairing and official-app setup."); });
        }
        @Override public void onDescriptorWrite(BluetoothGatt source,BluetoothGattDescriptor d,int status) {
            post(source,() -> { if(active!=null && active.kind==SUBSCRIBE && d.getUuid().equals(EmberProtocol.CCCD)) finish(status==BluetoothGatt.GATT_SUCCESS,null,""); });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] value) {
            if(!c.getUuid().equals(EmberProtocol.uuid(EmberProtocol.EVENTS))) return;
            byte[] copy=Arrays.copyOf(value,value.length); post(source,() -> listener.data(EmberProtocol.EVENTS,copy));
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c) {
            if(Build.VERSION.SDK_INT<33) onCharacteristicChanged(source,c,c.getValue()==null?new byte[0]:c.getValue());
        }
    };
}
