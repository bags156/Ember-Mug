package com.bags156.embermug;
import org.junit.Test;
import static org.junit.Assert.*;
public class EmberProtocolTest {
    @Test public void documentedLittleEndian() {
        assertArrayEquals(new byte[]{(byte)0xae,0x15},EmberProtocol.target(55.5));
        assertEquals(55.5,EmberProtocol.temperature(new byte[]{(byte)0xae,0x15}),.001);
        assertEquals("fc543622-236c-4c94-8fa9-944a3e5353fa",EmberProtocol.SERVICE.toString());
    }
    @Test public void offMustBeConfirmedZero() { assertArrayEquals(new byte[]{0,0},EmberProtocol.target(0)); assertTrue(EmberProtocol.matchesTarget(0,0)); assertFalse(EmberProtocol.matchesTarget(0,55)); assertFalse(EmberProtocol.matchesTarget(0,Double.NaN)); }
    @Test public void fahrenheitWireRoundtrip() { double c=EmberProtocol.toC(135); assertEquals(135,EmberProtocol.toF(c),.001); assertEquals(c,EmberProtocol.temperature(EmberProtocol.target(c)),.01); EmberProtocol.target(EmberProtocol.toC(120)); }
    @Test public void badTargetsRejected() { for(double c:new double[]{-1,1,48,64,100,Double.NaN,Double.POSITIVE_INFINITY}) assertThrows(IllegalArgumentException.class,() -> EmberProtocol.target(c)); }
    @Test public void malformedPacketsRejected() { assertThrows(IllegalArgumentException.class,() -> EmberProtocol.temperature(new byte[]{0})); assertThrows(IllegalArgumentException.class,() -> EmberProtocol.temperature(null)); assertThrows(IllegalArgumentException.class,() -> EmberProtocol.unsigned(new byte[0])); assertThrows(IllegalArgumentException.class,() -> EmberProtocol.color(new byte[3])); }
    @Test public void ledOrderIsRgb() { assertArrayEquals(new byte[]{(byte)0xbf,(byte)255,0,(byte)255},EmberProtocol.color(0xffbfff00)); assertEquals(0xffbfff00,EmberProtocol.color(new byte[]{(byte)0xbf,(byte)255,0,(byte)255})); }
    @Test public void emptyAndFillingBlockHeat() { for(int s:new int[]{-1,0,1,2,8,255}) assertFalse(EmberProtocol.hasLiquid(s)); for(int s=3;s<=7;s++) assertTrue(EmberProtocol.hasLiquid(s)); }
}
