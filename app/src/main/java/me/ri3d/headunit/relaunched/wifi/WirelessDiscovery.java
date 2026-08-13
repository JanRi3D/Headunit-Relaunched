package me.ri3d.headunit.relaunched.wifi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.protocol.Proto;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;

/**
 * Bluetooth side of Wireless Android Auto.
 *
 * Before the phone will ever open a TCP socket it has to be *told* where to go,
 * and that happens over Bluetooth RFCOMM on a Google-defined UUID. The exchange
 * is a tiny length-prefixed protobuf protocol, unrelated to the AA protocol
 * itself:
 *
 *   HU    -> phone : WifiStartRequest  { ip, port }
 *   phone -> HU    : WifiInfoRequest
 *   HU    -> phone : WifiInfoResponse  { ssid, key, bssid, security, ap_type }
 *   phone joins the AP, opens TCP <ip>:<port>, and the AA protocol starts there
 *   phone -> HU    : WifiConnectStatus { status }
 *
 * Framing on RFCOMM: u16 payloadLength, u16 messageId, payload (big endian).
 *
 * This is the least-verified part of the project. USB is the well-trodden path;
 * if wireless misbehaves, log every frame here first.
 */
public final class WirelessDiscovery {

    /** Google's "Android Auto wireless" RFCOMM service UUID. */
    public static final UUID AA_WIRELESS_UUID =
            UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");

    private static final int MSG_WIFI_START_REQUEST   = 1;
    private static final int MSG_WIFI_INFO_REQUEST    = 2;
    private static final int MSG_WIFI_INFO_RESPONSE   = 3;
    private static final int MSG_WIFI_VERSION_REQUEST = 4;
    private static final int MSG_WIFI_VERSION_RESPONSE= 5;
    private static final int MSG_WIFI_CONNECT_STATUS  = 6;

    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket socket;
    private volatile boolean stopped;

    /**
     * Blocks until a phone completes the handshake, or until stop() is called.
     * Run this on the connect thread while the TCP server socket is already
     * listening -- the phone can connect the instant we send WifiStartRequest.
     */
    public boolean handshakeAsServer(String localIp, int port) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Logger.w("BT: adapter unavailable, skipping wireless handshake");
            return false;
        }
        try {
            server = adapter.listenUsingRfcommWithServiceRecord("AndroidAuto", AA_WIRELESS_UUID);
            Logger.i("BT: waiting for phone on RFCOMM");
            BluetoothSocket s = server.accept();
            socket = s;
            Logger.i("BT: phone connected: " + s.getRemoteDevice().getName());
            return exchange(s, localIp, port);
        } catch (Exception e) {
            // Exception, not IOException: API 31+ throws SecurityException from
            // every Bluetooth call without BLUETOOTH_CONNECT, and a missing
            // permission should degrade to "no wireless", not kill the session.
            if (!stopped) Logger.w("BT: server handshake failed: " + e);
            return false;
        } finally {
            Utils.closeQuietly(server);
            server = null;
        }
    }

    /**
     * Head-unit-initiated variant: connect out to an already paired phone. Some
     * phones only accept this direction, so the UI offers both.
     */
    public boolean handshakeAsClient(BluetoothDevice dev, String localIp, int port) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) adapter.cancelDiscovery();
        try {
            BluetoothSocket s = dev.createRfcommSocketToServiceRecord(AA_WIRELESS_UUID);
            socket = s;
            s.connect();
            Logger.i("BT: connected out to " + dev.getName());
            return exchange(s, localIp, port);
        } catch (Exception e) {
            Logger.w("BT: client handshake failed: " + e);
            return false;
        }
    }

    /** Paired devices, so the UI can offer a phone to connect to. Null if unavailable. */
    public static Set<BluetoothDevice> bondedDevices() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a == null ? null : a.getBondedDevices();
        } catch (Exception e) {
            Logger.w("BT: cannot list paired devices: " + e);
            return null;
        }
    }

    private boolean exchange(BluetoothSocket s, String localIp, int port) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        Proto.W w = new Proto.W(256);
        w.str(1, localIp).u32(2, port);
        send(out, MSG_WIFI_START_REQUEST, w);
        Logger.i("BT: sent WifiStartRequest " + localIp + ":" + port);

        byte[] header = new byte[4];
        byte[] payload = new byte[1024];

        while (!stopped) {
            if (!readFully(in, header, 0, 4)) return false;
            int len = Utils.u16(header, 0);
            int id  = Utils.u16(header, 2);
            if (len > payload.length) payload = new byte[len];
            if (len > 0 && !readFully(in, payload, 0, len)) return false;

            Logger.d("BT: rx id=" + id + " len=" + len);
            switch (id) {
                case MSG_WIFI_INFO_REQUEST:
                    w.reset();
                    w.str(1, Config.WIFI_SSID);
                    w.str(2, Config.WIFI_PASSWORD);
                    w.str(3, Config.WIFI_BSSID);
                    w.u32(4, Config.WIFI_SECURITY_MODE);
                    w.u32(5, 0); // AccessPointType.STATIC
                    send(out, MSG_WIFI_INFO_RESPONSE, w);
                    break;

                case MSG_WIFI_VERSION_REQUEST:
                    w.reset();
                    w.u32(1, 1).u32(2, 0);
                    send(out, MSG_WIFI_VERSION_RESPONSE, w);
                    break;

                case MSG_WIFI_CONNECT_STATUS:
                    // The phone reports it joined the AP; TCP connect follows.
                    Logger.i("BT: phone reports wifi connected, handing over to TCP");
                    return true;

                default:
                    Logger.d("BT: ignoring message " + id);
                    break;
            }
        }
        return false;
    }

    private static void send(OutputStream out, int msgId, Proto.W w) throws IOException {
        byte[] head = new byte[4];
        Utils.putU16(head, 0, w.pos);
        Utils.putU16(head, 2, msgId);
        out.write(head);
        out.write(w.buf, 0, w.pos);
        out.flush();
    }

    private static boolean readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(b, off, len);
            if (n < 0) return false;
            off += n; len -= n;
        }
        return true;
    }

    public void stop() {
        stopped = true;
        Utils.closeQuietly(server);
        Utils.closeQuietly(socket);
    }

    /** First non-loopback IPv4 address -- the address we hand the phone. */
    public static String localIpv4() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
                 e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (ni.isLoopback()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress addr = a.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Logger.w("no local IPv4: " + e);
        }
        return null;
    }
}
