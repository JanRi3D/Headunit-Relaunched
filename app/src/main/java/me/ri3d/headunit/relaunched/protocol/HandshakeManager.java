package me.ri3d.headunit.relaunched.protocol;

import java.io.IOException;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * The opening negotiation, in order:
 *
 *   1. VERSION_REQUEST  -> 4 raw bytes, major then minor, big endian
 *   2. VERSION_RESPONSE <- 6 raw bytes, major, minor, status
 *   3. SSL_HANDSHAKE    -> <-  TLS records ferried inside AA messages
 *   4. AUTH_COMPLETE    -> protobuf, still unencrypted
 *   ... everything after this point is encrypted.
 *
 * Steps 1-4 are all plaintext: they are what set encryption up.
 */
public final class HandshakeManager {

    private final MessageWriter writer;
    private final Ssl ssl;

    private final byte[] versionPayload = new byte[4];

    public HandshakeManager(MessageWriter writer, Ssl ssl) {
        this.writer = writer;
        this.ssl = ssl;
    }

    public void sendVersionRequest() throws IOException {
        Utils.putU16(versionPayload, 0, Config.PROTOCOL_MAJOR);
        Utils.putU16(versionPayload, 2, Config.PROTOCOL_MINOR);
        writer.sendPlain(CH_CONTROL, MSG_VERSION_REQUEST, versionPayload, 0, 4);
        Logger.i("handshake: sent version " + Config.PROTOCOL_MAJOR + "." + Config.PROTOCOL_MINOR);
    }

    /** @return true if the phone accepted our protocol version. */
    public boolean onVersionResponse(byte[] buf, int off, int len) {
        if (len < 6) {
            Logger.e("handshake: short version response (" + len + ")");
            return false;
        }
        int major  = Utils.u16(buf, off);
        int minor  = Utils.u16(buf, off + 2);
        int status = Utils.u16(buf, off + 4);
        Logger.i("handshake: phone speaks " + major + "." + minor + ", status " + status);
        if (status != 0) {
            Logger.e("handshake: version mismatch");
            return false;
        }
        return true;
    }

    /** Emits the ClientHello. */
    public void startSsl() throws IOException {
        byte[] out = ssl.handshake(null, 0, 0);
        if (out == null) throw new IOException("TLS produced no ClientHello");
        writer.sendPlain(CH_CONTROL, MSG_SSL_HANDSHAKE, out, 0, out.length);
    }

    /**
     * Feeds one SSL_HANDSHAKE message and sends our reply.
     *
     * @return true once the handshake is complete
     */
    public boolean onSslHandshake(byte[] buf, int off, int len) throws IOException {
        byte[] out = ssl.handshake(buf, off, len);
        if (out != null) {
            writer.sendPlain(CH_CONTROL, MSG_SSL_HANDSHAKE, out, 0, out.length);
        }
        return ssl.isHandshakeComplete();
    }

    /** Last plaintext message; after this both sides encrypt. */
    public void sendAuthComplete() throws IOException {
        Proto.W w = new Proto.W(16);
        Messages.authComplete(w);
        writer.sendPlain(CH_CONTROL, MSG_AUTH_COMPLETE, w.buf, 0, w.pos);
        Logger.i("handshake: auth complete");
    }
}
