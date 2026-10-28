package com.bags156.embermug;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
/** Main-looper state. The service never retains an Activity. */
public final class MugStore {
    private MugStore() {}
    public static boolean running,connected,busy,shuttingDown,supportsLed,charging;
    public static String status="Your next sip, just right.",address="";
    public static double current=Double.NaN,target=Double.NaN;
    public static int liquid=-1,battery=-1;
    public static Integer led;
    public static long stateAt;
    private static final Set<Runnable> observers=new CopyOnWriteArraySet<>();
    public static void watch(Runnable r) { observers.add(r); r.run(); }
    public static void unwatch(Runnable r) { observers.remove(r); }
    public static void changed() { for(Runnable r:observers) r.run(); }
    public static void clearTelemetry() { current=target=Double.NaN; liquid=battery=-1; stateAt=0; charging=supportsLed=false; led=null; }
}
