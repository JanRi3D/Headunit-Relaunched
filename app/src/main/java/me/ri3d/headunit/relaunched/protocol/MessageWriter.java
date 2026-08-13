package me.ri3d.headunit.relaunched.protocol;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

import me.ri3d.headunit.relaunched.transport.Transport;
import me.ri3d.headunit.relaunched.util.Utils;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Serialises outgoing messages onto the transport. Safe to call from any thread
 * (reader thread sends acks and pongs, UI thread sends touch events, mic thread
 * sends audio).
 *
 * Zero-allocation usage:
 *
 *     Proto.W w = writer.begin();          // takes the lock
 *     try { Messages.avMediaAck(w, session); }
 *     finally { writer.end(CH_VIDEO, AV_MEDIA_ACK); }   // writes + unlocks
 *
 * begin() leaves two bytes free at the head of the buffer so the message id can
 * be back-filled in place -- the message is then one contiguous run of bytes and
 * needs no gather step.
 */
public final class MessageWriter {

    private final Transport transport;
    private final ReentrantLock lock = new ReentrantLock();

    /** Message being built: [msgId:2][protobuf...]. */
    private final Proto.W w = new Proto.W(8192);

    /** Frame staging: header + (possibly encrypted) chunk, written in one go. */
    private final byte[] out = new byte[8 + MAX_FRAME_PAYLOAD + 256];

    private Ssl ssl;
    private volatile boolean encryptEnabled;

    public MessageWriter(Transport transport) {
        this.transport = transport;
    }

    /** After AUTH_COMPLETE everything except the handshake itself is encrypted. */
    public void enableEncryption(Ssl ssl) {
        this.ssl = ssl;
        this.encryptEnabled = true;
    }

    public Proto.W begin() {
        lock.lock();
        w.reset();
        w.pos = 2; // reserve room for the message id
        return w;
    }

    /** Writes the message built since begin() and releases the lock. */
    public void end(int channel, int msgId) throws IOException {
        try {
            Utils.putU16(w.buf, 0, msgId);
            sendFrames(channel, msgId, encryptEnabled, w.buf, 0, w.pos);
        } finally {
            lock.unlock();
        }
    }

    /** Discards the message built since begin() and releases the lock. */
    public void abort() {
        lock.unlock();
    }

    /** Sends a message whose body is already in a caller-owned array (microphone PCM). */
    public void send(int channel, int msgId, byte[] body, int off, int len) throws IOException {
        sendInternal(channel, msgId, encryptEnabled, body, off, len);
    }

    /**
     * Never-encrypted variant, for the version exchange and the SSL handshake
     * itself -- those frames carry the bytes that set encryption up.
     */
    public void sendPlain(int channel, int msgId, byte[] body, int off, int len) throws IOException {
        sendInternal(channel, msgId, false, body, off, len);
    }

    /** Message with no body beyond the id. */
    public void sendEmpty(int channel, int msgId) throws IOException {
        send(channel, msgId, null, 0, 0);
    }

    private void sendInternal(int channel, int msgId, boolean encrypt,
                              byte[] body, int off, int len) throws IOException {
        lock.lock();
        try {
            w.reset();
            w.pos = 2;
            if (len > 0) {
                w.buf = Utils.grow(w.buf, 2, 2 + len);
                System.arraycopy(body, off, w.buf, 2, len);
                w.pos = 2 + len;
            }
            Utils.putU16(w.buf, 0, msgId);
            sendFrames(channel, msgId, encrypt, w.buf, 0, w.pos);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------

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
