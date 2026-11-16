package io.github.bags156.embermug.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PollPolicyTest {

    @Test
    public void pollsFastOnlyWhileTheAppIsVisible() {
        assertEquals(PollPolicy.VISIBLE_MS, PollPolicy.intervalMs(true, true));
        assertEquals(PollPolicy.VISIBLE_MS, PollPolicy.intervalMs(true, false));
    }

    @Test
    public void backgroundPollingBacksOff() {
        assertEquals(PollPolicy.BACKGROUND_MS, PollPolicy.intervalMs(false, false));
        assertEquals(PollPolicy.BACKGROUND_WITH_PUSH_MS, PollPolicy.intervalMs(false, true));
        assertTrue(PollPolicy.BACKGROUND_MS > PollPolicy.VISIBLE_MS);
        assertTrue(PollPolicy.BACKGROUND_WITH_PUSH_MS > PollPolicy.BACKGROUND_MS);
    }
}
