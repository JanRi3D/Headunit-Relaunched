package me.ri3d.headunit.relaunched.protocol;

import me.ri3d.headunit.relaunched.util.Utils;

/**
 * Hand-rolled minimal protobuf codec.
 *
 * Why not protobuf-lite + the gradle protobuf plugin: the AA message set we
 * actually touch is ~20 messages of scalar fields. This is one file, zero
 * dependencies, zero codegen, and no runtime reflection -- which matters a lot
 * on a 1GB head unit. The tradeoff is that field numbers live in Messages.java
 * instead of a .proto file, so cross-check them against aasdk if a message ever
 * behaves oddly.
 */
public final class Proto {

    private Proto() {}

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_64BIT  = 1;
    public static final int WIRE_BYTES  = 2;
    public static final int WIRE_32BIT  = 5;

    // =====================================================================
    // Writer
    // =====================================================================

    /**
     * Append-only protobuf writer over a growable byte[].
     *
     * Nested messages use begin()/end() with a fixed 2-byte length varint that
     * is back-patched. A non-minimal varint is legal protobuf and every parser
     * accepts it -- it just caps a single nested message at 16383 bytes, which
     * is far more than any message we build.
     */
    public static final class W {
        public byte[] buf;
        public int pos;

        public W(int capacity) { buf = new byte[capacity]; }

        public W reset() { pos = 0; return this; }

        private void ensure(int n) {
            if (pos + n > buf.length) {
                int cap = buf.length;
                while (cap < pos + n) cap <<= 1;
                byte[] nb = new byte[cap];
                System.arraycopy(buf, 0, nb, 0, pos);
                buf = nb;
            }
        }

        public void varint(long v) {
            ensure(10);
            while ((v & ~0x7FL) != 0) {
                buf[pos++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            buf[pos++] = (byte) v;
        }

        public void tag(int field, int wire) { varint(((long) field << 3) | wire); }

        /** uint32/enum field. */
        public W u32(int field, int v) { tag(field, WIRE_VARINT); varint(v & 0xFFFFFFFFL); return this; }

        /** int32 field -- negatives sign-extend to 10 bytes, same as protobuf. */
        public W i32(int field, int v) { tag(field, WIRE_VARINT); varint(v); return this; }

        public W i64(int field, long v) { tag(field, WIRE_VARINT); varint(v); return this; }

        public W bool(int field, boolean v) { tag(field, WIRE_VARINT); varint(v ? 1 : 0); return this; }

        public W str(int field, String s) {
            byte[] d = Utils.utf8(s);
            return raw(field, d, 0, d.length);
        }

        public W raw(int field, byte[] d, int off, int len) {
            tag(field, WIRE_BYTES);
            varint(len);
            ensure(len);
            System.arraycopy(d, off, buf, pos, len);
            pos += len;
            return this;
        }

        /** Start a nested message. Pass the returned handle to end(). */
        public int begin(int field) {
            tag(field, WIRE_BYTES);
            ensure(2);
            pos += 2;
            return pos;
        }

        public void end(int handle) {
            int len = pos - handle;
            if (len > 0x3FFF) throw new IllegalStateException("nested message too big: " + len);
            buf[handle - 2] = (byte) ((len & 0x7F) | 0x80);
            buf[handle - 1] = (byte) ((len >>> 7) & 0x7F);
        }

        public byte[] toByteArray() {
            byte[] out = new byte[pos];
            System.arraycopy(buf, 0, out, 0, pos);
            return out;
        }
    }

    // =====================================================================
    // Reader
    // =====================================================================

    /**
     * Pull parser. Usage:
     *   r.set(buf, off, len);
     *   while (r.next()) {
     *       switch (r.field) {
     *           case 1: x = (int) r.varint(); break;
     *           default: r.skip();
     *       }
     *   }
     * Every case MUST consume its value or call skip(), otherwise the stream
     * desynchronises.
     */
    public static final class R {
        public byte[] buf;
        public int pos, end;
        public int field, wire;

        public R set(byte[] b, int off, int len) {
            buf = b; pos = off; end = off + len;
            return this;
        }

        public boolean next() {
            if (pos >= end) return false;
            int t = (int) varint();
            field = t >>> 3;
            wire = t & 7;
            return true;
        }

        public long varint() {
            long r = 0;
            int shift = 0;
            while (pos < end) {
                int b = buf[pos++] & 0xFF;
                r |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return r;
                shift += 7;
                if (shift > 63) break;
            }
            return r;
        }

        public int int32() { return (int) varint(); }

        public boolean bool() { return varint() != 0; }

        /** Length of a WIRE_BYTES field; leaves pos at the start of the data. */
        public int len() { return (int) varint(); }

        public String str() {
            int n = len();
            String s = Utils.str(buf, pos, n);
            pos += n;
            return s;
        }

        /** Returns a sub-reader positioned over a nested message and skips past it. */
        public R nested(R into) {
            int n = len();
            into.set(buf, pos, n);
            pos += n;
            return into;
        }

        public void skip() {
            switch (wire) {
                case WIRE_VARINT: varint(); break;
                case WIRE_64BIT:  pos += 8; break;
                case WIRE_BYTES: {
                    // NOT `pos += len()`. Java reads the left-hand `pos` before
                    // calling len(), so the bytes len() consumes for the length
                    // varint get thrown away and every skipped length-delimited
                    // field desyncs the reader by 1-2 bytes.
                    int n = len();
                    pos += n;
                    break;
                }
                case WIRE_32BIT:  pos += 4; break;
                default:          pos = end; break; // unknown wire type: bail out
            }
        }
    }
}
