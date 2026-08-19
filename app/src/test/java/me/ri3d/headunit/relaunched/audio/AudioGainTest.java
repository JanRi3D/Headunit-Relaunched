package me.ri3d.headunit.relaunched.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The one bit of the audio path that is arithmetic rather than plumbing.
 * Getting the endianness or the sign wrong turns a quiet prompt into a loud
 * buzz, and that is not a thing you want to discover in a car.
 *
 * Run with: gradlew :app:testDebugUnitTest
 */
public class AudioGainTest {

    private static byte[] pcm(short... samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[i * 2] = (byte) samples[i];
            b[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        return b;
    }

    private static short sample(byte[] b, int i) {
        return (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
    }

    @Test public void scalesBothSigns() {
        byte[] b = pcm((short) 1000, (short) -1000, (short) 0);
        AudioOutput.applyGain(b, b.length, 2.0f);
        assertEquals(2000, sample(b, 0));
        assertEquals(-2000, sample(b, 1));
        assertEquals(0, sample(b, 2));
    }

    @Test public void clampsInsteadOfWrapping() {
        byte[] b = pcm((short) 20000, (short) -20000);
        AudioOutput.applyGain(b, b.length, 4.0f);
        assertEquals(32767, sample(b, 0));
        assertEquals(-32768, sample(b, 1));
    }

    @Test public void leavesTheTailBeyondLenAlone() {
        byte[] b = pcm((short) 100, (short) 100);
        AudioOutput.applyGain(b, 2, 3.0f); // first sample only
        assertEquals(300, sample(b, 0));
        assertEquals(100, sample(b, 1));
    }
}
