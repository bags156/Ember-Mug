package com.bags156.embermug;
import java.util.Locale;
import java.util.UUID;

public final class EmberProtocol {
    private EmberProtocol() {}
    public static final UUID SERVICE=uuid(0x3622), CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final int CURRENT=2, TARGET=3, BATTERY=7, STATE=8, EVENTS=18, LED=20;
    public static UUID uuid(int id) { return UUID.fromString(String.format(Locale.ROOT,"fc54%04x-236c-4c94-8fa9-944a3e5353fa",id)); }
    public static byte[] target(double c) {
        if(!Double.isFinite(c) || (c!=0 && (c<48.88 || c>63))) throw new IllegalArgumentException("Choose 120–145°F (about 49–63°C), or heater off.");
        int n=(int)Math.round(c*100); return new byte[]{(byte)n,(byte)(n>>>8)};
    }
    public static int unsigned(byte[] b) {
        if(b==null || b.length==0) throw new IllegalArgumentException("Missing mug data");
        return b[0]&255;
    }
    public static double temperature(byte[] b) {
        if(b==null || b.length<2) throw new IllegalArgumentException("Incomplete temperature data");
        return ((b[0]&255)|((b[1]&255)<<8))/100.0;
    }
    public static double toF(double c) { return c*9/5+32; }
    public static double toC(double f) { return (f-32)*5/9; }
    public static boolean hasLiquid(int state) { return state>=3 && state<=7; }
    public static String stateLabel(int s) { return switch(s) {
        case 0 -> "Standby"; case 1 -> "Empty"; case 2 -> "Filling"; case 3 -> "Cold • heater idle";
        case 4 -> "Cooling"; case 5 -> "Heating"; case 6 -> "Ready to sip"; case 7 -> "Warm • heater idle"; default -> "Waiting for mug";
    }; }
    public static boolean matchesTarget(double expected,double actual) { return Double.isFinite(actual) && Math.abs(expected-actual)<=.02; }
    public static byte[] color(int argb) { return new byte[]{(byte)(argb>>>16),(byte)(argb>>>8),(byte)argb,(byte)255}; }
    public static int color(byte[] b) {
        if(b==null || b.length<4) throw new IllegalArgumentException("Incomplete LED data");
        return 0xff000000|((b[0]&255)<<16)|((b[1]&255)<<8)|(b[2]&255);
    }
}
