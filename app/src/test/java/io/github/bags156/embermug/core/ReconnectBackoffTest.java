package io.github.bags156.embermug.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReconnectBackoffTest {

    @Test
    public void delaysGrowAndAreBounded() {
        long previous = 0;
        for (int failures = 0; failures < 50; failures++) {
            long delay = ReconnectBackoff.delayMs(failures);
            assertTrue("non-decreasing at " + failures, delay >= previous);
            assertTrue("bounded at " + failures, delay <= ReconnectBackoff.MAX_DELAY_MS);
            previous = delay;
        }
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayMs(Integer.MAX_VALUE));
        assertEquals(1_000L, ReconnectBackoff.delayMs(0));
        assertEquals(1_000L, ReconnectBackoff.delayMs(-3));
    }

    @Test
    public void switchesToBackgroundConnectAfterDirectAttempts() {
        assertFalse(ReconnectBackoff.useAutoConnect(0));
        assertFalse(ReconnectBackoff.useAutoConnect(5));
        assertTrue(ReconnectBackoff.useAutoConnect(6));
        assertTrue(ReconnectBackoff.useAutoConnect(100));
    }
}
