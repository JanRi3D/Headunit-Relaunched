package me.ri3d.headunit.relaunched.protocol;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

import me.ri3d.headunit.relaunched.transport.Transport;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Everything the head unit sends, framed and written by one thread of its own.
 *
 * Callers hand over a finished message and return; they never touch the
 * transport. That is the whole point. The senders are the session thread (acks,
 * pongs), the mic thread (PCM), and the UI thread (touch, at 60Hz through a
 * drag) -- and the UI thread must not be the one waiting on a USB bulk transfer
 * that is allowed two seconds to fail. It used to be: begin() took a lock the
 * other two also held, and end() did the TLS wrap and the write inline.
 *
 * Having exactly one writing thread buys more than a responsive UI:
 *
 *  - Ssl.encrypt() has one caller, so wrap and unwrap are on separate threads
 *    by construction rather than by argument.
 *  - Transport.write() has one caller, which is what its "safe alongside a
 *    read" contract actually needs.
 *
 * Zero-allocation usage, unchanged:
 *
 *     Proto.W w = writer.begin();          // takes the build lock
 *     try { Messages.avMediaAck(w, session); }
 *     finally { writer.end(CH_VIDEO, AV_MEDIA_ACK); }   // queues + unlocks
 *
 * begin() leaves two bytes free at the head of the buffer so the message id can
 * be back-filled in place -- the message is then one contiguous run of bytes and
 * needs no gather step.
 */
public final class MessageWriter {

    /**
     * Queued messages. 64 is a couple of seconds of the busiest realistic mix
     * (touch drag + video acks + mic) and costs 32KB of slots. If the writer
     * falls a whole queue behind, the link is already failing and the session
     * is about to be torn down by the read side.
     */
    private static final int QUEUE_SLOTS = 64;

    /** One queued message. Slots are recycled; nothing is allocated per send. */
    private static final class Pending {
        final Proto.W w = new Proto.W(512);
        int channel, msgId;
        boolean encrypt;
    }

    private final Transport transport;

    private final Pending[] queue = new Pending[QUEUE_SLOTS];
    private int head, count;
    private final Object queueLock = new Object();

    /**
     * Serialises message *building* only. Held while bytes go into a slot and
     * released by end(); no I/O happens under it, so contention is bounded by
     * how long it takes to lay out a protobuf.
     */
    private final ReentrantLock buildLock = new ReentrantLock();
    /** Slot begin() handed out, or null when the queue was full. */
    private Pending building;
    /** Where a message goes when the queue is full: built, then thrown away. */
    private final Proto.W discard = new Proto.W(512);
    private int drops;

    // ---- writer thread only ------------------------------------------------

    /** Frame staging: header + (possibly encrypted) chunk, written in one go. */
    private final byte[] out = new byte[8 + MAX_FRAME_PAYLOAD + 256];
    private volatile Thread thread;
    private volatile boolean running;

    private volatile Ssl ssl;
    private volatile boolean encryptEnabled;

    public MessageWriter(Transport transport) {
        this.transport = transport;
        for (int i = 0; i < QUEUE_SLOTS; i++) queue[i] = new Pending();
    }

    public void start() {
        running = true;
        thread = new Thread(new Pump(), "hu-writer");
        thread.start();
    }

    /** Idempotent: the session stops the writer on both its exit paths. */
    public void stop() {
        running = false;
        synchronized (queueLock) { queueLock.notifyAll(); }
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        if (drops > 0) Logger.w("writer: dropped " + drops + " messages");
    }

    /** After AUTH_COMPLETE everything except the handshake itself is encrypted. */
    public void enableEncryption(Ssl ssl) {
        this.ssl = ssl;
        this.encryptEnabled = true;
    }

    public Proto.W begin() {
        buildLock.lock();
        building = claim();
        Proto.W b = (building != null) ? building.w : discard;
        b.reset();
        b.pos = 2; // reserve room for the message id
        return b;
    }

    /** Queues the message built since begin() and releases the build lock. */
    public void end(int channel, int msgId) {
        try {
            Pending p = building;
            building = null;
            if (p == null) { drops++; return; }
            Utils.putU16(p.w.buf, 0, msgId);
            p.channel = channel;
            p.msgId = msgId;
            // Snapshot here, not on the writer thread: AUTH_COMPLETE is queued
            // before enableEncryption() flips this and must still go out in the
            // clear when the writer gets to it.
            p.encrypt = encryptEnabled;
            commit();
        } finally {
            buildLock.unlock();
        }
    }

    /** Queues a message whose body is already in a caller-owned array (microphone PCM). */
    public void send(int channel, int msgId, byte[] body, int off, int len) {
        queueBody(channel, msgId, encryptEnabled, body, off, len);
    }

    /**
     * Never-encrypted variant, for the version exchange and the SSL handshake
     * itself -- those frames carry the bytes that set encryption up.
     */
    public void sendPlain(int channel, int msgId, byte[] body, int off, int len) {
        queueBody(channel, msgId, false, body, off, len);
    }

    private void queueBody(int channel, int msgId, boolean encrypt,
                           byte[] body, int off, int len) {
        buildLock.lock();
        try {
            Pending p = claim();
            if (p == null) { drops++; return; }
            Proto.W b = p.w;
            b.reset();
            b.pos = 2;
            if (len > 0) {
                b.buf = Utils.grow(b.buf, 2, 2 + len);
                System.arraycopy(body, off, b.buf, 2, len);
                b.pos = 2 + len;
            }
            Utils.putU16(b.buf, 0, msgId);
            p.channel = channel;
            p.msgId = msgId;
            p.encrypt = encrypt;
            commit();
        } finally {
            buildLock.unlock();
        }
    }

    // ------------------------------------------------------------------

    /** Caller holds buildLock. Null when the queue is full. */
    private Pending claim() {
        synchronized (queueLock) {
            int i = Utils.ringSlot(head, count, QUEUE_SLOTS);
            return (i < 0) ? null : queue[i];
        }
    }

    /** Caller holds buildLock and has filled the slot claim() returned. */
    private void commit() {
        synchronized (queueLock) {
            count++;
            queueLock.notify();
        }
    }

    private final class Pump implements Runnable {
        @Override public void run() {
            while (true) {
                Pending p;
                synchronized (queueLock) {
                    while (running && count == 0) {
                        try { queueLock.wait(); } catch (InterruptedException e) { return; }
                    }
                    // Stopped: finish what is already queued and go. Costs
                    // nothing at teardown -- the transport is closed by then and
                    // the first write fails out of the loop -- and it means
                    // "queued" and "sent" cannot diverge for a caller that
                    // stops the writer straight after sending.
                    if (count == 0) return;
                    p = queue[head];
                    head = (head + 1) % QUEUE_SLOTS;
                    count--;
                }
                try {
                    sendFrames(p.channel, p.msgId, p.encrypt, p.w.buf, 0, p.w.pos);
                } catch (Throwable e) {
                    // Nothing upstream can act on this -- the senders are long
                    // gone. Drop the link instead: closing the transport ends
                    // the blocked read, and the session comes back in three
                    // seconds rather than half-talking to a phone that stopped
                    // hearing us.
                    if (running) Logger.w("writer: " + e + " -- dropping the link");
                    running = false;
                    transport.close();
                    return;
                }
            }
        }
    }

    private void sendFrames(int channel, int msgId, boolean encrypt,
                            byte[] msg, int msgOff, int msgLen) throws IOException {
        boolean control = needsControlFlag(channel, msgId);
        int sent = 0;
        while (sent < msgLen) {
            int chunk = Math.min(msgLen - sent, MAX_FRAME_PAYLOAD);
            boolean first = (sent == 0);
            boolean last  = (sent + chunk >= msgLen);

            int flags = (first ? FRAME_FIRST : 0)
                      | (last ? FRAME_LAST : 0)
                      | (encrypt ? FRAME_ENCRYPTED : 0)
                      | (control ? FRAME_CONTROL : 0);

            int headerLen = (first && !last) ? 8 : 4;

            int bodyLen;
            if (encrypt) {
                bodyLen = ssl.encrypt(msg, msgOff + sent, chunk, out, headerLen);
            } else {
                System.arraycopy(msg, msgOff + sent, out, headerLen, chunk);
                bodyLen = chunk;
            }

            out[0] = (byte) channel;
            out[1] = (byte) flags;
            Utils.putU16(out, 2, bodyLen);
            if (headerLen == 8) {
                // Total size hint for the peer's reassembly buffer. aasdk sends
                // the plaintext remainder here; it is only ever a hint.
                Utils.putU32(out, 4, msgLen - sent);
            }

            transport.write(out, 0, headerLen + bodyLen);
            sent += chunk;
        }
    }
}
