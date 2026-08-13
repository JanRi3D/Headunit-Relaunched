package me.ri3d.headunit.relaunched.transport;

import android.bluetooth.BluetoothDevice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;
import me.ri3d.headunit.relaunched.wifi.WirelessDiscovery;

/**
 * Wireless Android Auto over TCP, in either direction.
 *
 * DIAL mode (host != null) -- what the manual-IP box in the UI uses.
 *   The phone is the server. Enable Android Auto developer settings, tap
 *   "Start head unit server", and the phone listens on 5277; we just connect to
 *   it. No Bluetooth, no hotspot credentials, no handshake to get wrong. This is
 *   the same listener the Desktop Head Unit talks to over an ADB forward.
 *
 * LISTEN mode (host == null) -- the production wireless AA flow.
 *   We are the server on 5288 and the phone dials us, but only after the
 *   Bluetooth RFCOMM handshake has told it where to go. The socket goes up
 *   first so the phone cannot beat us to it.
 */
public final class WifiTransport implements Transport {

    private final String host;              // null => listen
    private final int port;
    private final BluetoothDevice btDevice; // only meaningful when listening

    private ServerSocket server;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean closed;

    private final WirelessDiscovery discovery = new WirelessDiscovery();

    /** Dial the phone's head unit server directly. */
    public WifiTransport(String host, int port) {
        this.host = host;
        this.port = port;
        this.btDevice = null;
    }

    /** Listen for the phone. @param btDevice paired phone to nudge over RFCOMM, or null. */
    public WifiTransport(BluetoothDevice btDevice) {
        this.host = null;
        this.port = Config.WIFI_PORT;
        this.btDevice = btDevice;
    }

    @Override public String name() {
        return host != null
                ? ("Wi-Fi -> " + host + ":" + port)
                : ("Wi-Fi, waiting for phone on port " + port);
    }

    @Override
    public boolean connect() throws IOException {
        return host != null ? dial() : listen();
    }

    private boolean dial() throws IOException {
        Logger.i("WiFi: dialling " + host + ":" + port);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), Config.WIFI_CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            Utils.closeQuietly(s);
            // Almost always "head unit server not started on the phone" rather
            // than a broken network, so say so instead of just surfacing ECONNREFUSED.
            throw new IOException("cannot reach " + host + ":" + port
                    + " -- is 'Start head unit server' running in Android Auto"
                    + " developer settings, and is the phone on this network?");
        }
        adopt(s);
        return true;
    }

    private boolean listen() throws IOException {
        String ip = WirelessDiscovery.localIpv4();
        if (ip == null) throw new IOException("no local IPv4 address -- is the hotspot up?");

        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 1);
        Logger.i("WiFi: listening on " + ip + ":" + port);

        // Tell the phone where to connect. Failure is not fatal: the phone may
        // already know this head unit and dial in on its own.
        if (btDevice != null) {
            discovery.handshakeAsClient(btDevice, ip, port);
        } else {
            discovery.handshakeAsServer(ip, port);
        }

        Socket s = server.accept();
        Logger.i("WiFi: phone connected from " + s.getInetAddress());
        adopt(s);

        Utils.closeQuietly(server);
        server = null;
        return true;
    }

    private void adopt(Socket s) throws IOException {
        s.setTcpNoDelay(true);   // AA frames are small and latency sensitive
        s.setSoTimeout(0);       // block forever; close() unblocks us
        s.setReceiveBufferSize(64 * 1024);
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        closed = false;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        InputStream i = in;
        if (closed || i == null) return -1;
        return i.read(buf, off, len);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        OutputStream o = out;
        if (closed || o == null) throw new IOException("wifi closed");
        o.write(buf, off, len);
        // No flush(): a raw socket OutputStream is unbuffered, and TCP_NODELAY
        // already pushes the segment out.
    }

    @Override
    public void close() {
        closed = true;
        discovery.stop();
        Utils.closeQuietly(in);
        Utils.closeQuietly(out);
        Utils.closeQuietly(socket);
        Utils.closeQuietly(server);
        in = null; out = null; socket = null; server = null;
    }
}
