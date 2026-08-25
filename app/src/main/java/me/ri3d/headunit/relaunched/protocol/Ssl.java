package me.ri3d.headunit.relaunched.protocol;

import android.util.Base64;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

import me.ri3d.headunit.relaunched.R;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;

/**
 * TLS for the Android Auto link.
 *
 * The head unit is the TLS *client* and authenticates with Google's head-unit
 * certificate + key. Records are not streamed over a socket -- each AA frame
 * carries whole TLS records -- so we need SSLEngine rather than SSLSocket, and
 * we drive wrap/unwrap by hand.
 *
 * API 16 notes, both of which bite in practice:
 *
 *  - java.nio.Buffer methods are cast to Buffer before calling. Compiling
 *    against a modern android.jar otherwise emits calls to the covariant
 *    ByteBuffer.clear()/flip()/position() overloads, which do not exist on old
 *    runtimes and blow up with NoSuchMethodError.
 *
 *  - TLSv1.2 exists on API 16 but is disabled by default, so we enable it
 *    explicitly. GCM cipher suites, however, only arrived around API 20. If a
 *    genuinely ancient unit fails the handshake, that is almost certainly why:
 *    swap this class for BouncyCastle's org.bouncycastle.tls.TlsClientProtocol,
 *    which is pure Java and does modern suites anywhere. The three methods
 *    below (handshake / encrypt / decrypt) are the entire surface to reimplement.
 */
public final class Ssl {

    private final SSLEngine engine;
    private final ByteBuffer netOut;   // ciphertext we produce
    private final ByteBuffer appIn;    // plaintext we recover
    private ByteBuffer netIn;          // ciphertext accumulated from the peer

    private boolean handshakeComplete;

    /**
     * Reusable wrappers so the steady-state encrypt/decrypt path allocates
     * nothing. One pair per direction, which is not tidiness: a single shared
     * pair hands one direction a ByteBuffer over the other's array, and
     * unwrapping a half-built plaintext message as if it were a TLS record is
     * exactly the
     *
     *     BAD_DECRYPT / DECRYPTION_FAILED_OR_BAD_RECORD_MAC
     *
     * the session then dies with.
     *
     * There is exactly one thread on each side -- MessageWriter's writer thread
     * wraps, the session thread unwraps -- and SSLEngine documents wrap and
     * unwrap as safe to run concurrently, so no lock is needed here. That used
     * to be an argument about who calls what; since outbound framing moved
     * behind one thread it is a property of the code.
     */
    private byte[] inArray, outArray;
    private ByteBuffer inBuf, outBuf;

    /**
     * Handshake wraps pass *no* source buffers, not one empty buffer.
     *
     * Passing ByteBuffer.allocate(0) segfaults Conscrypt on Dalvik: its JNI
     * layer takes the address of the backing array, and a zero-length heap
     * array gives it a bogus pointer (SIGSEGV at 0x00000001, right after
     * beginHandshake). An empty array means "no sources" and never reaches that
     * code. This is what open-headunit passes too.
     */
    private static final ByteBuffer[] NO_SOURCES = new ByteBuffer[0];

    private Ssl(SSLEngine engine) {
        this.engine = engine;
        int pkt = Math.max(engine.getSession().getPacketBufferSize(), 0x4800);
        int app = Math.max(engine.getSession().getApplicationBufferSize(), 0x4800);
        // Direct buffers: Conscrypt's native fast path expects them, and heap
        // destinations take a far less exercised JNI route on old Dalvik.
        netOut = ByteBuffer.allocateDirect(pkt);
        appIn  = ByteBuffer.allocateDirect(app);
        netIn  = ByteBuffer.allocateDirect(pkt);
    }

    /**
     * Builds the engine from the head-unit certificate and key in res/raw.
     *
     * @param certPem   X.509 certificate, PEM
     * @param keyPkcs8  matching private key, PKCS#8 PEM ("BEGIN PRIVATE KEY")
     */
    public static Ssl create(InputStream certPem, InputStream keyPkcs8) throws Exception {
        X509Certificate cert = (X509Certificate)
                CertificateFactory.getInstance("X.509").generateCertificate(certPem);
        PrivateKey key = readPkcs8(keyPkcs8, conscrypt());

        SSLContext ctx = newContext();
        // The phone's certificate is not something we can chain to a public CA,
        // and Google's protocol does not expect us to verify it.
        ctx.init(new KeyManager[]{ new SingleKeyManager(cert, key) },
                 new TrustManager[]{ TRUST_ALL }, null);

        SSLEngine e = ctx.createSSLEngine();
        e.setUseClientMode(true);

        // Conscrypt already defaults to TLS 1.2 + GCM, which is the entire
        // reason it is here -- leave its defaults alone. The widening below is
        // only for the platform provider on API 16, where TLS 1.2 ships
        // disabled. (Forcing the full supported set also switches TLS 1.3 on,
        // which changes client-certificate timing for no benefit here.)
        boolean usingConscrypt = ctx.getProvider() != null
                && ctx.getProvider().getName().contains("Conscrypt");
        if (!usingConscrypt) {
            enableEverythingUsable(e);
        }

        Logger.i("SSL: client cert " + cert.getSubjectDN()
                + " (issuer " + cert.getIssuerDN() + ")");
        // If the handshake fails, this line is the first thing to look at: no
        // GCM/ECDHE suite here means Conscrypt did not load and the phone will
        // refuse us with "communication error 7".
        int gcm = 0;
        String[] enabled = e.getEnabledCipherSuites();
        for (int i = 0; i < enabled.length; i++) {
            if (enabled[i].contains("GCM")) gcm++;
        }
        Logger.i("SSL: engine=" + e.getClass().getName()
                + " protocols=" + join(e.getEnabledProtocols())
                + " suites=" + enabled.length + " (" + gcm + " GCM)");
        if (gcm == 0) {
            Logger.e("SSL: no GCM cipher suites -- Android Auto will reject this handshake");
        }
        return new Ssl(e);
    }

    /**
     * Strips the PEM armour and decodes the DER body.
     *
     * The key MUST be built by the same provider that will use it. Conscrypt
     * bundles its own BoringSSL, entirely separate from the platform's OpenSSL;
     * a key from the platform KeyFactory carries a native handle Conscrypt does
     * not own, and signing the client CertificateVerify with it dereferences a
     * foreign pointer:
     *
     *     SIGSEGV at 00000001 in RSA_sign_pss_mgf1 <- EVP_DigestSignFinal
     *                                              <- SSL_do_handshake
     *
     * open-headunit sidesteps this by calling Security.insertProviderAt, which
     * makes Conscrypt the default for everything. Asking for the provider
     * explicitly is the same fix without changing the JCA setup process-wide.
     */
    private static PrivateKey readPkcs8(InputStream in, Provider provider) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) raw.write(chunk, 0, n);

        String pem = Utils.str(raw.toByteArray(), 0, raw.size())
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();

        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);

        KeyFactory kf = (provider != null)
                ? KeyFactory.getInstance("RSA", provider)
                : KeyFactory.getInstance("RSA");
        PrivateKey key = kf.generatePrivate(spec);
        Logger.i("SSL: private key " + key.getClass().getName());
        return key;
    }

    /**
     * A KeyManager holding exactly one identity, which it hands over
     * unconditionally.
     *
     * The unconditional part is the whole point. A stock KeyManager only offers
     * a client certificate when the server's CertificateRequest lists an issuer
     * it recognises; Android Auto's request does not line up, so the default
     * implementation returns no alias, we send no certificate, and the phone
     * drops the connection mid-handshake with nothing useful in the log.
     * Forcing the alias is what open-headunit does too.
     */
    private static final class SingleKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "aa_hu";

        private final X509Certificate[] chain;
        private final PrivateKey key;

        SingleKeyManager(X509Certificate cert, PrivateKey key) {
            this.chain = new X509Certificate[]{ cert };
            this.key = key;
        }

        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket s) {
            return ALIAS;
        }
        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine e) {
            return ALIAS; // the path SSLEngine actually takes
        }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ ALIAS };
        }
        @Override public X509Certificate[] getCertificateChain(String alias) { return chain; }
        @Override public PrivateKey getPrivateKey(String alias) { return key; }

        // Server side is never used: we are always the TLS client.
        @Override public String chooseServerAlias(String k, Principal[] i, Socket s) { return null; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
    }

    /** Conscrypt provider, resolved once. Null if it could not be loaded. */
    private static Provider conscrypt;
    private static boolean conscryptTried;

    private static synchronized Provider conscrypt() {
        if (!conscryptTried) {
            conscryptTried = true;
            try {
                conscrypt = Conscrypt.newProvider();
                Conscrypt.Version v = Conscrypt.version();
                Logger.i("SSL: using Conscrypt " + v.major() + "." + v.minor() + "." + v.patch());
            } catch (Throwable t) {
                // Should not happen, but a missing .so for this ABI would land
                // here and the platform provider is better than nothing.
                Logger.e("SSL: Conscrypt unavailable, falling back to platform TLS", t);
            }
        }
        return conscrypt;
    }

    /**
     * Builds the TLS context, preferring Conscrypt.
     *
     * On API 16 the platform provider offers TLS 1.2 but no ECDHE/GCM cipher
     * suites, and current Android Auto will not negotiate anything else -- the
     * phone aborts with "communication error 7". Conscrypt supplies the modern
     * suites. It is passed as a Provider object rather than registered via
     * Security.insertProviderAt, so nothing else in the process changes.
     */
    private static SSLContext newContext() throws Exception {
        Provider p = conscrypt();
        if (p != null) {
            try {
                return SSLContext.getInstance("TLS", p);
            } catch (Exception e) {
                Logger.w("SSL: Conscrypt TLS context failed, falling back: " + e);
            }
        }
        try {
            return SSLContext.getInstance("TLSv1.2");
        } catch (Exception e) {
            Logger.w("SSL: no TLSv1.2 context, falling back to TLS");
            return SSLContext.getInstance("TLS");
        }
    }

    /**
     * Turn on every protocol and cipher suite the device actually supports,
     * minus the useless ones. Old Androids ship with a conservative *enabled*
     * set even when the *supported* set is fine, and AA will refuse anything
     * below TLS 1.2 on current phones.
     */
    private static void enableEverythingUsable(SSLEngine e) {
        ArrayList<String> protos = new ArrayList<String>();
        String[] supported = e.getSupportedProtocols();
        for (int i = 0; i < supported.length; i++) {
            String p = supported[i];
            if (p.startsWith("SSL")) continue; // SSLv3 and friends: no
            protos.add(p);
        }
        if (!protos.isEmpty()) {
            e.setEnabledProtocols(protos.toArray(new String[protos.size()]));
        }

        ArrayList<String> suites = new ArrayList<String>();
        String[] all = e.getSupportedCipherSuites();
        for (int i = 0; i < all.length; i++) {
            String s = all[i];
            if (s.contains("_anon_") || s.contains("NULL") || s.contains("EXPORT")
                    || s.contains("_DES_") || s.startsWith("TLS_EMPTY")) continue;
            suites.add(s);
        }
        if (!suites.isEmpty()) {
            e.setEnabledCipherSuites(suites.toArray(new String[suites.size()]));
        }
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.toString();
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] c, String t) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String t) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    public boolean isHandshakeComplete() { return handshakeComplete; }

    public String cipherSuite() { return engine.getSession().getCipherSuite(); }

    /**
     * Drives one step of the handshake.
     *
     * @param data peer handshake bytes from an SSL_HANDSHAKE message, or null
     *             to kick off the ClientHello
     * @return bytes to put in the next SSL_HANDSHAKE message, or null if we are
     *         waiting on the peer
     */
    public byte[] handshake(byte[] data, int off, int len) throws SSLException {
        if (data != null && len > 0) {
            ensureNetInCapacity(len);
            netIn.put(data, off, len);
        } else {
            engine.beginHandshake();
        }

        ByteArrayOutputStream pending = new ByteArrayOutputStream(2048);

        // Bounded so a misbehaving peer can never spin us forever.
        for (int guard = 0; guard < 64; guard++) {
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();

            if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) task.run();
                continue;
            }

            if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                ((Buffer) netOut).clear();
                SSLEngineResult r = engine.wrap(NO_SOURCES, netOut);
                ((Buffer) netOut).flip();
                if (netOut.hasRemaining()) {
                    byte[] tmp = new byte[netOut.remaining()];
                    netOut.get(tmp);
                    pending.write(tmp, 0, tmp.length);
                }
                if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                    handshakeComplete = true;
                }
                if (r.getStatus() == SSLEngineResult.Status.CLOSED) break;
                continue;
            }

            if (hs == SSLEngineResult.HandshakeStatus.FINISHED
                    || hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                handshakeComplete = true;
                break;
            }

            // NEED_UNWRAP (and NEED_UNWRAP_AGAIN on newer runtimes, which we
            // deliberately do not name -- the constant is absent on API 16).
            //
            // Nothing buffered from the peer yet. This happens on the very
            // first call: we wrap the ClientHello, the status flips straight to
            // NEED_UNWRAP, and there is obviously no reply yet. Calling unwrap
            // here would hand Conscrypt a zero-remaining buffer, which its JNI
            // layer dereferences -- SIGSEGV at 0x00000001, immediately after
            // "session: TLS handshake". The platform provider merely returned
            // BUFFER_UNDERFLOW, which is why this stayed hidden until Conscrypt
            // was introduced.
            if (netIn.position() == 0) break;

            ((Buffer) netIn).flip();
            SSLEngineResult r;
            try {
                ((Buffer) appIn).clear();
                r = engine.unwrap(netIn, appIn);
            } finally {
                netIn.compact();
            }
            if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                handshakeComplete = true;
            }
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                break; // need another SSL_HANDSHAKE message from the phone
            }
            if (r.getStatus() == SSLEngineResult.Status.CLOSED) break;
        }

        if (handshakeComplete) Logger.i("SSL: handshake done, suite=" + cipherSuite());
        return pending.size() == 0 ? null : pending.toByteArray();
    }

    private void ensureNetInCapacity(int extra) {
        if (netIn.remaining() >= extra) return;
        ByteBuffer bigger = ByteBuffer.allocateDirect(netIn.position() + extra + 4096);
        ((Buffer) netIn).flip();
        bigger.put(netIn);
        netIn = bigger;
    }

    /**
     * Encrypts one AA message payload. dst must have room for len plus TLS
     * overhead (~64 bytes is plenty for one record).
     *
     * @return bytes written to dst
     */
    public int encrypt(byte[] src, int off, int len, byte[] dst, int dstOff) throws SSLException {
        ByteBuffer s = reuseOut(src, off, len);
        int total = 0;
        while (s.hasRemaining()) {
            ((Buffer) netOut).clear();
            SSLEngineResult r = engine.wrap(s, netOut);
            ((Buffer) netOut).flip();
            int n = netOut.remaining();
            // The caller's slack is a comment upstream; make it a check here.
            // A cipher suite with fatter records than today's GCM would
            // otherwise write past the frame buffer instead of failing.
            if (dstOff + total + n > dst.length) {
                throw new SSLException("ciphertext " + (dstOff + total + n)
                        + " exceeds the " + dst.length + " byte frame buffer");
            }
            netOut.get(dst, dstOff + total, n);
            total += n;
            if (r.bytesConsumed() == 0 && n == 0) break; // no progress: bail
        }
        return total;
    }

    /**
     * Decrypts one AA frame payload. AA encrypts per frame, so a frame always
     * holds whole records and this never needs to buffer across calls.
     *
     * @return bytes written to dst
     */
    public int decrypt(byte[] src, int off, int len, byte[] dst, int dstOff) throws SSLException {
        ByteBuffer s = reuseIn(src, off, len);
        int total = 0;
        while (s.hasRemaining()) {
            ((Buffer) appIn).clear();
            SSLEngineResult r = engine.unwrap(s, appIn);
            ((Buffer) appIn).flip();
            int n = appIn.remaining();
            if (n > 0) {
                // Peer-controlled sizes reach this one. Fail the frame rather
                // than the array bounds.
                if (dstOff + total + n > dst.length) {
                    throw new SSLException("plaintext " + (dstOff + total + n)
                            + " exceeds the " + dst.length + " byte frame buffer");
                }
                appIn.get(dst, dstOff + total, n);
                total += n;
            }
            SSLEngineResult.Status st = r.getStatus();
            if (st == SSLEngineResult.Status.BUFFER_UNDERFLOW
                    || st == SSLEngineResult.Status.CLOSED) break;
            if (r.bytesConsumed() == 0 && n == 0) break;
        }
        return total;
    }

    /** Outgoing: cached wrapper over the writer's message buffer. */
    private ByteBuffer reuseOut(byte[] array, int off, int len) {
        if (array != outArray) {
            outArray = array;
            outBuf = ByteBuffer.wrap(array);
        }
        return window(outBuf, off, len);
    }

    /** Incoming: cached wrapper over the parser's receive buffer. */
    private ByteBuffer reuseIn(byte[] array, int off, int len) {
        if (array != inArray) {
            inArray = array;
            inBuf = ByteBuffer.wrap(array);
        }
        return window(inBuf, off, len);
    }

    /** Re-target a cached ByteBuffer at part of its array, no allocation. */
    private static ByteBuffer window(ByteBuffer b, int off, int len) {
        Buffer x = b; // cast for API 16, as everywhere else in this class
        x.clear();
        x.limit(off + len);
        x.position(off);
        return b;
    }

    public void close() {
        try { engine.closeOutbound(); } catch (Throwable ignored) {}
    }

    /** Loads res/raw/cert + res/raw/privkey. Returns null with a clear log on failure. */
    public static Ssl fromResources(android.content.Context ctx) {
        InputStream cert = null, key = null;
        try {
            cert = ctx.getResources().openRawResource(R.raw.cert);
            key = ctx.getResources().openRawResource(R.raw.privkey);
            return create(cert, key);
        } catch (Exception e) {
            Logger.e("SSL: cannot build TLS identity from res/raw/cert + res/raw/privkey", e);
            return null;
        } finally {
            Utils.closeQuietly(cert);
            Utils.closeQuietly(key);
        }
    }
}
