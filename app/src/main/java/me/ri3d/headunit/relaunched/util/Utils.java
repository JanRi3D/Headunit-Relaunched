package me.ri3d.headunit.relaunched.util;

import java.io.UnsupportedEncodingException;

public final class Utils {

    private Utils() {}

    public static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always present
        }
    }

    public static String str(byte[] b, int off, int len) {
        try {
            return new String(b, off, len, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /** Big-endian uint16 read. */
    public static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** Big-endian uint16 write. */
    public static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    /** Big-endian uint32 read (as signed int; AA sizes never exceed 2^31). */
    public static int u32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static long u64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    /**
     * Index a producer may fill next in a `slots`-long ring, or -1 when it is
     * full and the incoming item has to be dropped.
     *
     * One slot is always left free, and the producer never reclaims one by
     * advancing head -- together those are what let a consumer take slot
     * `head`, release the lock and spend a long time in it (an AudioTrack write,
     * a USB bulk transfer) without the producer overwriting the buffer
     * underneath it. Dropping the *oldest* instead looks kinder and is not: it
     * advances head, and after enough drops head wraps onto the slot the
     * consumer is still reading from. Dropping the newest keeps head moving
     * only under the consumer, and the collision becomes unreachable rather
     * than unlikely.
     *
     * @param head  index the consumer will take next
     * @param count items currently queued
     */
    public static int ringSlot(int head, int count, int slots) {
        if (count >= slots - 1) return -1;
        return (head + count) % slots;
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) {}
        }
    }

    /*
     * Socket and ServerSocket only started implementing Closeable in API 19.
     * Without these overloads the compiler emits a cast to Closeable and the
     * call dies with NoSuchMethodError on API 16 -- both have had their own
     * close() since API 1, so calling it directly is all that is needed.
     * Overload resolution happens at compile time, so callers need no change.
     */

    public static void closeQuietly(java.net.Socket s) {
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    public static void closeQuietly(java.net.ServerSocket s) {
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** Grow a byte[] to at least `need`, preserving `used` bytes. Returns the same array if big enough. */
    public static byte[] grow(byte[] buf, int used, int need) {
        if (buf != null && buf.length >= need) return buf;
        int cap = buf == null ? 8192 : buf.length;
        while (cap < need) cap <<= 1;
        byte[] nb = new byte[cap];
        if (buf != null && used > 0) System.arraycopy(buf, 0, nb, 0, used);
        return nb;
    }
}
