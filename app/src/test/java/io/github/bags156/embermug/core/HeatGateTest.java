package io.github.bags156.embermug.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeatGateTest {

    private static final long NOW = 1_000_000L;

    private static HeatGate.Decision check(boolean ready, boolean pending, LiquidState state, long readAt) {
        return HeatGate.check(ready, pending, state, readAt, NOW);
    }

    @Test
    public void requiresAConnection() {
        assertEquals(HeatGate.Decision.NOT_READY, check(false, false, LiquidState.HEATING, NOW));
        assertEquals(HeatGate.Decision.NOT_READY, check(false, true, LiquidState.HEATING, NOW));
    }

    @Test
    public void pendingShutdownBlocksHeat() {
        assertEquals(HeatGate.Decision.SHUTDOWN_PENDING, check(true, true, LiquidState.HEATING, NOW));
    }

    @Test
    public void requiresAReading() {
        assertEquals(HeatGate.Decision.NO_READING, check(true, false, null, NOW));
        assertEquals(HeatGate.Decision.NO_READING, check(true, false, LiquidState.HEATING, 0));
    }

    @Test
    public void staleReadingsAreRejected() {
        long limit = HeatGate.MAX_LIQUID_AGE_MS;
        assertEquals(HeatGate.Decision.ALLOWED, check(true, false, LiquidState.COLD, NOW - limit));
        assertEquals(HeatGate.Decision.STALE_READING, check(true, false, LiquidState.COLD, NOW - limit - 1));
        assertEquals(HeatGate.Decision.STALE_READING, check(true, false, LiquidState.COLD, NOW + 5));
    }

    @Test
    public void emptyAndUnknownAreRejected() {
        assertEquals(HeatGate.Decision.EMPTY, check(true, false, LiquidState.EMPTY, NOW));
        assertEquals(HeatGate.Decision.UNKNOWN_STATE, check(true, false, LiquidState.UNKNOWN, NOW));
    }

    @Test
    public void anyLiquidStateAllowsHeat() {
        LiquidState[] liquid = {
            LiquidState.FILLING, LiquidState.COLD, LiquidState.COOLING,
            LiquidState.HEATING, LiquidState.AT_TARGET, LiquidState.WARM,
        };
        for (LiquidState state : liquid) {
            assertEquals(state.name(), HeatGate.Decision.ALLOWED, check(true, false, state, NOW - 1000));
        }
    }
}
