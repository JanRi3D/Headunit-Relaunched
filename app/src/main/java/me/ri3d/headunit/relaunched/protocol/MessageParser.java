package me.ri3d.headunit.relaunched.protocol;

import java.io.IOException;

import me.ri3d.headunit.relaunched.transport.Transport;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Reads frames off a Transport, decrypts them, reassembles fragmented messages
 * and hands complete messages to a Sink. Single threaded: only the session's
 * reader thread ever calls pump().
 *
 * Buffering matters here for USB: a bulk read must never ask for fewer bytes
 * than the endpoint's packet size, so we always issue large reads into our own
 * ring and slice frames out of it, rather than doing a 4-byte read for the
 * header.
 *
 * Fragmented messages are decrypted *per frame* and then concatenated, which is
 * what aasdk does -- decrypting the concatenation instead would fail.
 */
public final class MessageParser {

    public interface Sink {
        /**
         * @param buf valid only for the duration of the call. Copy anything you
         *            need to keep.
         */
        void onMessage(int channel, int msgId, byte[] buf, int off, int len) throws IOException;
    }

    /** Big enough for one max-size frame plus slack, small enough to not care about. */
    private static final int RX_CAP = 48 * 1024;
    /** Sanity bound: anything larger means we lost frame sync. */
    private static final int MAX_FRAME_LEN = 0x8000;

    private final Transport transport;

    private final byte[] rx = new byte[RX_CAP];
    private int rxPos, rxEnd;

    /** Per-frame decryption target. */
    private final byte[] plain = new byte[MAX_FRAME_LEN];

    /** Reassembly buffers, allocated lazily per channel and reused/grown. */
    private final byte[][] asmBuf = new byte[CH_COUNT][];
    private final int[] asmLen = new int[CH_COUNT];

    private Ssl ssl;

    public MessageParser(Transport transport) {
        this.transport = transport;
    }

    /** Called once the TLS handshake is done; frames flagged ENCRYPTED are then unwrapped. */
    public void setSsl(Ssl ssl) { this.ssl = ssl; }

    /**
     * Reads and dispatches exactly one frame.
     *
     * @return false when the link is gone
     */
    public boolean pump(Sink sink) throws IOException {
        if (rxPos == rxEnd) { rxPos = 0; rxEnd = 0; }

        if (!need(4)) return false;
        int channel = rx[rxPos] & 0xFF;
        int flags   = rx[rxPos + 1] & 0xFF;
        int len     = Utils.u16(rx, rxPos + 2);

        boolean first = (flags & FRAME_FIRST) != 0;
        boolean last  = (flags & FRAME_LAST) != 0;
        boolean encrypted = (flags & FRAME_ENCRYPTED) != 0;

        if (len > MAX_FRAME_LEN) {
            throw new IOException("frame length " + len + " out of range -- lost sync");
        }

        // A first-but-not-last frame carries a u32 total message size we can use
        // to size the reassembly buffer in one go.
        int headerLen = (first && !last) ? 8 : 4;
        if (!need(headerLen + len)) return false;

        int totalHint = (headerLen == 8) ? Utils.u32(rx, rxPos + 4) : len;
        int payloadOff = rxPos + headerLen;
        rxPos += headerLen + len;

        // A channel we never advertised. Consume the frame and carry on rather
        // than dropping the session -- we stay in sync, we just ignore it.
        if (channel >= CH_COUNT) {
            Logger.w("ignoring frame for unadvertised channel " + channel);
            return true;
        }

        byte[] src = rx;
        int srcOff = payloadOff;
        int srcLen = len;

        if (encrypted) {
            if (ssl == null) throw new IOException("encrypted frame before TLS is up");
            srcLen = ssl.decrypt(rx, payloadOff, len, plain, 0);
            src = plain;
            srcOff = 0;
            if (srcLen <= 0) {
                Logger.w("decrypt produced nothing for ch=" + channel + " len=" + len);
                return true;
            }
        }

        if (first && last) {
            // Whole message in one frame: dispatch straight out of the source
            // buffer, no reassembly copy at all.
            dispatch(sink, channel, src, srcOff, srcLen);
            return true;
        }

        // Fragmented: accumulate.
        if (first) asmLen[channel] = 0;
        int have = asmLen[channel];
        int wantCap = Math.max(have + srcLen, first ? totalHint : 0);
        asmBuf[channel] = Utils.grow(asmBuf[channel], have, wantCap);
        System.arraycopy(src, srcOff, asmBuf[channel], have, srcLen);
        asmLen[channel] = have + srcLen;

        if (last) {
            dispatch(sink, channel, asmBuf[channel], 0, asmLen[channel]);
            asmLen[channel] = 0;
        }
        return true;
    }

    private void dispatch(Sink sink, int channel, byte[] buf, int off, int len) throws IOException {
        if (len < 2) {
            Logger.w("runt message on channel " + channel + " len=" + len);
            return;
        }
        int msgId = Utils.u16(buf, off);
        sink.onMessage(channel, msgId, buf, off + 2, len - 2);
    }

    /** Ensures n contiguous bytes are available at rxPos. False once the link is gone. */
    private boolean need(int n) throws IOException {
        while (rxEnd - rxPos < n) {
            if (rx.length - rxPos < n) {
                // Not enough room even if we fill to the end: slide down.
                System.arraycopy(rx, rxPos, rx, 0, rxEnd - rxPos);
                rxEnd -= rxPos;
                rxPos = 0;
            }
            // <= rather than < 0: Transport.read blocks for bytes, so a zero
            // would be an adapter with no way of ever making progress, and
            // looping on it is a spin.
            int r = transport.read(rx, rxEnd, rx.length - rxEnd);
            if (r <= 0) return false;
            rxEnd += r;
        }
        return true;
    }
}
