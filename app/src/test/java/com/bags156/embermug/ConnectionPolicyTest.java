package com.bags156.embermug;
import org.junit.Test;
import static org.junit.Assert.*;
public class ConnectionPolicyTest {
    @Test public void pendingOffSurvivesRestart() { ConnectionPolicy p=new ConnectionPolicy(false); p.arm(true); ConnectionPolicy restored=new ConnectionPolicy(p.pendingOff()); assertTrue(restored.pendingOff()); restored.disconnected(false); assertTrue(restored.pendingOff()); restored.confirmedOff(); assertFalse(restored.pendingOff()); }
    @Test public void disconnectQueuesOff() { ConnectionPolicy p=new ConnectionPolicy(false); p.disconnected(true); assertTrue(p.pendingOff()); p.disconnected(true); assertTrue(p.pendingOff()); }
    @Test public void canDisableForActiveSession() { ConnectionPolicy p=new ConnectionPolicy(false); p.arm(false); p.disconnected(false); assertFalse(p.pendingOff()); p.arm(true); p.disarm(); assertFalse(p.pendingOff()); }
    @Test public void invalidTelemetryBlocksHeating() { assertTrue(ConnectionPolicy.mayHeat(true,false,3,1000)); assertFalse(ConnectionPolicy.mayHeat(true,false,1,1000)); assertFalse(ConnectionPolicy.mayHeat(true,false,-1,1000)); assertFalse(ConnectionPolicy.mayHeat(false,false,5,1000)); assertFalse(ConnectionPolicy.mayHeat(true,true,5,1000)); assertFalse(ConnectionPolicy.mayHeat(true,false,5,30001)); assertFalse(ConnectionPolicy.mayHeat(true,false,5,-1)); }
    @Test public void backoffIsBounded() { assertEquals(3000,ConnectionPolicy.retryDelay(0)); assertEquals(6000,ConnectionPolicy.retryDelay(1)); assertEquals(60000,ConnectionPolicy.retryDelay(Integer.MAX_VALUE)); }
}
