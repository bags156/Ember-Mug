package com.bags156.embermug;
public final class FavoriteTemperatures {
    private FavoriteTemperatures() {}
    public static double parse(String s,boolean f) {
        double v=Double.parseDouble(s.trim().replace(',','.'));
        if(!Double.isFinite(v)) throw new IllegalArgumentException("Enter a temperature");
        double c=Math.round((f?EmberProtocol.toC(v):v)*100)/100.0;
        EmberProtocol.target(c);
        if(c==0) throw new IllegalArgumentException("A favorite must be a heating temperature");
        return c;
    }
    public static double defaultC(int slot) {
        if(slot<0 || slot>2) throw new IllegalArgumentException("There are three favorite slots");
        return EmberProtocol.toC(new double[]{125,135,140}[slot]);
    }
}
