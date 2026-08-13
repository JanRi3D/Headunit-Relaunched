package me.ri3d.headunit.relaunched.util;

import java.io.UnsupportedEncodingException;

public final class Utils {

    private Utils() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

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

    /** Hex dump for protocol debugging. Only call under Logger.isDebug(). */
    public static String hex(byte[] b, int off, int len) {
        if (len > 64) len = 64;
        char[] out = new char[len * 3];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            out[i * 3] = HEX[v >>> 4];
            out[i * 3 + 1] = HEX[v & 0xF];
            out[i * 3 + 2] = ' ';
        }
        return new String(out);
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
