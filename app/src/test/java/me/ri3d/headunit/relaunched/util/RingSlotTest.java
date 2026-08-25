package me.ri3d.headunit.relaunched.util;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The ring rule behind both the audio sink and the outbound message queue.
 *
 * Both have a consumer that takes a slot, leaves the lock, and then spends a
 * long time inside it -- an AudioTrack write, a USB bulk transfer. Nothing may
 * hand that slot back to the producer in the meantime. The old audio ring
 * dropped its *oldest* buffer when full, which advances head, and after enough
 * drops head wraps onto the slot being read. It made a burst of garbled PCM
 * exactly when audio was already struggling.
 *
 * Run with: gradlew :app:testDebugUnitTest
 */
public class RingSlotTest {

    /** One slot always stays free, so a full ring reports "drop this one". */
    @Test
    public void fullRingDropsTheIncomingItem() {
        assertEquals(0, Utils.ringSlot(0, 0, 8));
        assertEquals(6, Utils.ringSlot(0, 6, 8));
        assertEquals(-1, Utils.ringSlot(0, 7, 8));  // 7 of 8 is full
        assertEquals(-1, Utils.ringSlot(3, 7, 8));
        assertEquals(2, Utils.ringSlot(4, 6, 8));   // wraps
    }

    /**
     * The exact sequence the old audio ring broke on: fill it, let the Pump take
     * one and block, then keep offering.
     */
    @Test
    public void producerNeverReachesTheSlotTheConsumerIsReading() {
        final int slots = 8;
        int head = 0, count = 0;

        while (Utils.ringSlot(head, count, slots) >= 0) count++;   // fill

        int inFlight = head;                                        // consumer takes it
        head = (head + 1) % slots;
        count--;

        for (int offer = 0; offer < 100; offer++) {
            int i = Utils.ringSlot(head, count, slots);
            if (i < 0) continue;                                    // full: drop, head stays put
            assertTrue("offer " + offer + " landed on the in-flight slot " + i,
                    i != inFlight);
            count++;
        }
    }

    /** Same rule under a randomised interleaving, which is how it actually runs. */
    @Test
    public void survivesRandomInterleaving() {
        final int slots = 8;
        Random rnd = new Random(1);
        int head = 0, count = 0, inFlight = -1, delivered = 0;

        for (int step = 0; step < 200000; step++) {
            if (rnd.nextInt(10) < 6) {
                int i = Utils.ringSlot(head, count, slots);
                if (i < 0) continue;                    // full: the newest is dropped
                assertTrue("producer overwrote the slot being consumed",
                        i != inFlight);
                count++;
            } else if (inFlight >= 0) {
                inFlight = -1;                          // the long write finished
                delivered++;
            } else if (count > 0) {
                inFlight = head;                        // take one and go blocking
                head = (head + 1) % slots;
                count--;
            }
        }
        assertTrue("the ring never moved anything", delivered > 1000);
    }
}
