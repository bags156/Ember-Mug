package com.bags156.embermug;
/** Persist the armed flag before sending heat so a restarted process shuts down first. */
public final class ConnectionPolicy {
    private boolean pending;
    public ConnectionPolicy(boolean saved) { pending=saved; }
    public boolean pendingOff() { return pending; }
    public void arm(boolean enabled) { if(enabled) pending=true; }
    public void disconnected(boolean enabled) { arm(enabled); }
    public void confirmedOff() { pending=false; }
    public void disarm() { pending=false; }
    public static boolean mayHeat(boolean connected,boolean busy,int liquid,long age) { return connected && !busy && EmberProtocol.hasLiquid(liquid) && age>=0 && age<=30_000; }
    public static long retryDelay(int attempt) { return Math.min(60_000,3000L*(1L<<Math.min(Math.max(0,attempt),5))); }
}
