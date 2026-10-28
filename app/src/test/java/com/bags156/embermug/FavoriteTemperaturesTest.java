package com.bags156.embermug;
import org.junit.Test;
import static org.junit.Assert.*;
public class FavoriteTemperaturesTest {
    @Test public void threeValidDefaults() { for(int i=0;i<3;i++) { assertEquals(new double[]{125,135,140}[i],EmberProtocol.toF(FavoriteTemperatures.defaultC(i)),.01); EmberProtocol.target(FavoriteTemperatures.defaultC(i)); } assertThrows(IllegalArgumentException.class,() -> FavoriteTemperatures.defaultC(3)); }
    @Test public void unitChangePreservesFavorite() { double c=FavoriteTemperatures.parse("135",true); assertEquals(135,EmberProtocol.toF(c),.01); assertEquals(c,FavoriteTemperatures.parse("57.22",false),.001); assertEquals(120,EmberProtocol.toF(FavoriteTemperatures.parse("120",true)),.02); }
    @Test public void decimalCommaAccepted() { assertEquals(55.5,FavoriteTemperatures.parse(" 55,5 ",false),.001); }
    @Test public void invalidFavoritesRejected() { for(String s:new String[]{"","tea","0","NaN","Infinity","1000"}) assertThrows(IllegalArgumentException.class,() -> FavoriteTemperatures.parse(s,false)); }
}
