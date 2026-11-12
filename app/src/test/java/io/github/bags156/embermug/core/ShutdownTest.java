package io.github.bags156.embermug.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ShutdownTest {

    private static final String MUG = "AA:BB:CC:DD:EE:01";
    private static final String OTHER_MUG = "AA:BB:CC:DD:EE:02";

    /** In-memory stand-in for SharedPreferences. */
    static final class MemoryStore implements KeyValueStore {
        final Map<String, String> values = new HashMap<>();

        @Override
        public String getString(String key) {
            return values.get(key);
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }

    private MemoryStore memory;
    private ShutdownStore store;

    @Before
    public void setUp() {
        memory = new MemoryStore();
        store = new ShutdownStore(memory);
    }

    @Test
    public void startsWithNothingPending() {
        assertNull(store.load());
        assertFalse(store.isPendingFor(MUG));
    }

    @Test
    public void armedRequestIsPersisted() {
        assertTrue(store.arm(MUG, 1234L));
        // A fresh store over the same storage (e.g. after process death) sees it.
        PendingShutdown reloaded = new ShutdownStore(memory).load();
        assertNotNull(reloaded);
        assertEquals(MUG, reloaded.address);
        assertEquals(1234L, reloaded.createdAtMs);
        assertEquals(0, reloaded.attempts);
        assertTrue(store.isPendingFor(MUG.toLowerCase()));
    }

    @Test
    public void rearmingTheSameMugKeepsTheOriginalRequest() {
        store.arm(MUG, 1000L);
        store.recordAttempt();
        assertTrue(store.arm(MUG, 2000L));
        assertEquals(1000L, store.load().createdAtMs);
        assertEquals(1, store.load().attempts);
    }

    @Test
    public void cannotArmASecondMugWhileOneIsPending() {
        store.arm(MUG, 1000L);
        assertFalse(store.arm(OTHER_MUG, 2000L));
        assertEquals(MUG, store.load().address);
    }

    @Test
    public void attemptsAccumulateAcrossReloads() {
        store.arm(MUG, 1000L);
        store.recordAttempt();
        new ShutdownStore(memory).recordAttempt();
        assertEquals(2, store.load().attempts);
    }

    @Test
    public void recordAttemptWithoutRequestDoesNothing() {
        store.recordAttempt();
        assertNull(store.load());
    }

    @Test
    public void clearOnlyAffectsTheMatchingMug() {
        store.arm(MUG, 1000L);
        store.clear(OTHER_MUG);
        assertNotNull(store.load());
        store.clear(MUG.toLowerCase());
        assertNull(store.load());
    }

    @Test
    public void forgetClearsEverything() {
        store.arm(MUG, 1000L);
        store.recordAttempt();
        store.forget();
        assertNull(store.load());
        assertTrue(memory.values.isEmpty());
    }

    @Test
    public void corruptCountersReadAsZero() {
        memory.putString(ShutdownStore.KEY_ADDRESS, MUG);
        memory.putString(ShutdownStore.KEY_ATTEMPTS, "lots");
        memory.putString(ShutdownStore.KEY_CREATED, "yesterday");
        PendingShutdown pending = store.load();
        assertEquals(0, pending.attempts);
        assertEquals(0L, pending.createdAtMs);
    }

    @Test
    public void pendingRequestIsSentFirstOnlyToItsOwnMug() {
        PendingShutdown pending = new PendingShutdown(MUG, 0, 0);
        assertTrue(ShutdownPolicy.mustSendOffFirst(pending, MUG));
        assertFalse(ShutdownPolicy.mustSendOffFirst(pending, OTHER_MUG));
        assertFalse(ShutdownPolicy.mustSendOffFirst(null, MUG));
    }

    @Test
    public void pendingRequestBlocksSelectingAnotherMug() {
        PendingShutdown pending = new PendingShutdown(MUG, 0, 0);
        assertTrue(ShutdownPolicy.blocksSelecting(pending, OTHER_MUG));
        assertFalse(ShutdownPolicy.blocksSelecting(pending, MUG));
        assertFalse(ShutdownPolicy.blocksSelecting(null, OTHER_MUG));
    }

    @Test
    public void onlyAZeroReadBackConfirmsOff() {
        assertTrue(ShutdownPolicy.confirmsOff(0));
        assertFalse(ShutdownPolicy.confirmsOff(null));
        assertFalse(ShutdownPolicy.confirmsOff(5722));
    }

    @Test
    public void retriesAreLimitedPerConnection() {
        assertEquals(ShutdownPolicy.AfterAttempt.CLEAR, ShutdownPolicy.afterAttempt(true, 1));
        assertEquals(ShutdownPolicy.AfterAttempt.CLEAR, ShutdownPolicy.afterAttempt(true, 99));
        for (int attempt = 1; attempt < ShutdownPolicy.MAX_ATTEMPTS_PER_CONNECTION; attempt++) {
            assertEquals(ShutdownPolicy.AfterAttempt.RETRY, ShutdownPolicy.afterAttempt(false, attempt));
        }
        assertEquals(ShutdownPolicy.AfterAttempt.RECONNECT,
                ShutdownPolicy.afterAttempt(false, ShutdownPolicy.MAX_ATTEMPTS_PER_CONNECTION));
        assertEquals(ShutdownPolicy.AfterAttempt.RECONNECT,
                ShutdownPolicy.afterAttempt(false, ShutdownPolicy.MAX_ATTEMPTS_PER_CONNECTION + 1));
    }

    @Test
    public void armsOnlyWhenEnabledAndHeating() {
        assertTrue(ShutdownPolicy.shouldArm(true, 5722));
        assertFalse(ShutdownPolicy.shouldArm(false, 5722));
        assertFalse(ShutdownPolicy.shouldArm(true, 0));
        assertFalse(ShutdownPolicy.shouldArm(true, null));
    }
}
