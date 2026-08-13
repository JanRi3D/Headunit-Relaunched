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
     * Reads *up to* len bytes, like InputStream.read.
     *
     * @return >0 bytes read, 0 on timeout with the link still alive, -1 on EOF/close.
     */
    int read(byte[] buf, int off, int len) throws IOException;

    /** Writes all len bytes or throws. Must be safe to call while another thread reads. */
    void write(byte[] buf, int off, int len) throws IOException;

    /** Idempotent, and safe to call from another thread to unblock read(). */
    void close();

    /** For logs and the UI. */
    String name();
}
