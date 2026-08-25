package me.ri3d.headunit.relaunched.transport;

import java.io.IOException;

/**
 * Byte pipe to the phone. The AA protocol layer knows nothing beyond this, so
 * USB and Wi-Fi feed the exact same AndroidAutoSession.
 *
 * Deviation from a plain read(byte[]): off/len are passed explicitly so the
 * frame reader can append straight into its own buffer instead of allocating a
 * temporary array and copying for every frame.
 */
public interface Transport {

    /** Blocks until the phone is connected. False means "not available right now". */
    boolean connect() throws IOException;

    /**
     * Reads *up to* len bytes, blocking until at least one arrives.
     *
     * Two outcomes only, and deliberately so. There is no "timed out, try
     * again": a bulk IN that gives up part-filled discards the bytes it had,
     * and resuming from there desyncs the frame stream for good -- a length out
     * of range, or a BAD_RECORD_MAC once the gap lands inside a TLS record. An
     * adapter that cannot deliver bytes has lost the link, and the session is
     * cheaper to rebuild than the byte stream is to resync.
     *
     * @return >0 bytes read, -1 on EOF, error, or close.
     */
    int read(byte[] buf, int off, int len) throws IOException;

    /**
     * Writes all len bytes or throws. Called from the writer thread only, but
     * always concurrently with a read, so the two directions must not share
     * mutable state.
     */
    void write(byte[] buf, int off, int len) throws IOException;

    /** Idempotent, and safe to call from another thread to unblock read(). */
    void close();

    /** For logs and the UI. */
    String name();
}
