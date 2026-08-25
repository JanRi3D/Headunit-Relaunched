package me.ri3d.headunit.relaunched.protocol;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.ri3d.headunit.relaunched.transport.Transport;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The writer owns the only thread that touches the transport, and the point of
 * that is what the first test asserts: a sender does not wait on a link that is
 * not moving. Touch events come off the UI thread at 60Hz through a drag and a
 * USB bulk write is allowed two seconds to fail; those two facts used to meet
 * on the same thread.
 *
 * Run with: gradlew :app:testDebugUnitTest
 */
public class MessageWriterTest {

    /** Records what reached the wire, and can be held shut on demand. */
    private static final class Pipe implements Transport {
        private final CountDownLatch gate;
        private final ByteArrayOutputStream wire = new ByteArrayOutputStream();

        Pipe(CountDownLatch gate) { this.gate = gate; }

        @Override public boolean connect() { return true; }
        @Override public String name() { return "pipe"; }
        @Override public void close() {}
        @Override public int read(byte[] b, int off, int len) { return -1; }

        @Override public void write(byte[] b, int off, int len) {
            if (gate != null) {
                try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
            synchronized (wire) { wire.write(b, off, len); }
        }

        synchronized byte[] bytes() {
            synchronized (wire) { return wire.toByteArray(); }
        }
    }

    /** One frame off the wire, header decoded. */
    private static final class Frame {
        int channel, flags, msgId;
        byte[] payload;
    }

    /** Frames only -- nothing here fragments, so one frame is one message. */
    private static List<Frame> framesOf(byte[] wire) {
        List<Frame> out = new ArrayList<Frame>();
        int p = 0;
        while (p + 4 <= wire.length) {
            Frame f = new Frame();
            f.channel = wire[p] & 0xFF;
            f.flags = wire[p + 1] & 0xFF;
            int len = ((wire[p + 2] & 0xFF) << 8) | (wire[p + 3] & 0xFF);
            int headerLen = ((f.flags & FRAME_FIRST) != 0 && (f.flags & FRAME_LAST) == 0) ? 8 : 4;
            if (p + headerLen + len > wire.length) break;
            int body = p + headerLen;
            f.msgId = ((wire[body] & 0xFF) << 8) | (wire[body + 1] & 0xFF);
            f.payload = new byte[len - 2];
            System.arraycopy(wire, body + 2, f.payload, 0, f.payload.length);
            out.add(f);
            p = body + len;
        }
        return out;
    }

    private static byte[] body(int marker, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) marker;
        return b;
    }

    /**
     * The reason the writer thread exists. With the transport wedged, twenty
     * sends must still return at once; the old design took a lock and did the
     * write inline, so this blocked for as long as the link did.
     */
    @Test
    public void sendersDoNotWaitForTheTransport() {
        CountDownLatch gate = new CountDownLatch(1);
        Pipe pipe = new Pipe(gate);
        MessageWriter writer = new MessageWriter(pipe);
        writer.start();
        try {
            long startNs = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                writer.send(CH_INPUT, IN_EVENT_INDICATION, body(i, 32), 0, 32);
            }
            long ms = (System.nanoTime() - startNs) / 1000000L;
            assertTrue("senders waited " + ms + "ms on a transport that never moved", ms < 500);
        } finally {
            gate.countDown();
            writer.stop();
        }
    }

    /** A stream of frames the phone can follow means strict FIFO, from any thread. */
    @Test
    public void messagesReachTheWireInOrder() {
        Pipe pipe = new Pipe(null);
        MessageWriter writer = new MessageWriter(pipe);
        writer.start();
        for (int i = 0; i < 30; i++) {
            writer.send(CH_INPUT, IN_EVENT_INDICATION, body(i, 16), 0, 16);
        }
        writer.stop();   // drains what is queued, then joins

        List<Frame> frames = framesOf(pipe.bytes());
        assertEquals(30, frames.size());
        for (int i = 0; i < 30; i++) {
            assertEquals("frame " + i + " out of order", (byte) i, frames.get(i).payload[0]);
            // And whole: a slot recycled under the writer would show up here as
            // one message carrying another's marker halfway through.
            for (byte b : frames.get(i).payload) {
                assertEquals("frame " + i + " was overwritten mid-flight", (byte) i, b);
            }
        }
    }

    /**
     * The version exchange and the SSL handshake carry the bytes that set
     * encryption up, so they must never be flagged encrypted -- whatever the
     * writer's own state happens to be by the time it gets to them.
     */
    @Test
    public void handshakeMessagesAreNeverFlaggedEncrypted() {
        Pipe pipe = new Pipe(null);
        MessageWriter writer = new MessageWriter(pipe);
        writer.start();
        writer.sendPlain(CH_CONTROL, MSG_VERSION_REQUEST, body(1, 4), 0, 4);
        writer.sendPlain(CH_CONTROL, MSG_SSL_HANDSHAKE, body(2, 64), 0, 64);
        writer.stop();

        List<Frame> frames = framesOf(pipe.bytes());
        assertEquals(2, frames.size());
        for (Frame f : frames) {
            assertFalse("handshake frame went out flagged ENCRYPTED",
                    (f.flags & FRAME_ENCRYPTED) != 0);
        }
    }

    /**
     * Overflow drops the newest, not the oldest. Dropping the oldest advances
     * the queue head, and a head that moves under the producer eventually wraps
     * onto the message the writer is still reading -- see RingSlotTest.
     *
     * So the survivors have gaps where messages were refused, but they arrive in
     * order, the oldest is never thrown away to make room, and no message
     * carries another's bytes.
     */
    @Test
    public void overflowDropsTheNewestAndCorruptsNothing() {
        CountDownLatch gate = new CountDownLatch(1);
        Pipe pipe = new Pipe(gate);
        MessageWriter writer = new MessageWriter(pipe);
        writer.start();
        try {
            for (int i = 0; i < 250; i++) {
                writer.send(CH_VIDEO, AV_MEDIA_ACK, body(i, 64), 0, 64);
            }
        } finally {
            gate.countDown();
            writer.stop();
        }

        List<Frame> frames = framesOf(pipe.bytes());
        assertTrue("nothing survived the overflow", frames.size() > 1);
        assertTrue("more survived than the queue can hold: " + frames.size(),
                frames.size() < 250);

        int previous = -1;
        for (int i = 0; i < frames.size(); i++) {
            byte[] payload = frames.get(i).payload;
            int marker = payload[0] & 0xFF;
            assertTrue("frame " + i + " went out of order (" + previous + " then " + marker + ")",
                    marker > previous);
            previous = marker;
            // A slot recycled under the writer shows up as one message carrying
            // another's bytes partway through.
            for (byte b : payload) {
                assertEquals("frame " + i + " was overwritten mid-flight",
                        (byte) marker, b);
            }
        }
        assertEquals("the oldest queued message must never be the one dropped",
                0, frames.get(0).payload[0] & 0xFF);
    }
}
