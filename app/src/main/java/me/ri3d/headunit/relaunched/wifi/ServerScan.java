package me.ri3d.headunit.relaunched.wifi;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.util.Logger;

/**
 * Sweep the local /24 for a phone running "Start head unit server", so the IP
 * never has to be typed on a resistive touchscreen.
 *
 * A plain TCP connect to <ip>:5277 is the entire test. Everything fancier costs
 * more than it is worth on this hardware: ICMP needs root or forking
 * /system/bin/ping, /proc/net/arp is unreadable from API 29, and a UDP broadcast
 * has nothing on the phone listening for it.
 *
 * Two addresses are probed before the sweep: the one that worked last time, and
 * the DHCP gateway -- which *is* the phone whenever the phone is the hotspot.
 * Either answers in the first wave, before 254 cold ARP lookups have had the
 * chance to clog a cheap radio.
 */
public final class ServerScan {

    /**
     * Threads and timeout are the calibration knobs. 254 threads is what you
     * would write on a desktop and it would put a 1GB single-DIN unit into swap.
     *
     * The timeout is deliberately unhurried: on a weak radio with two dozen cold
     * ARP lookups in flight a phone can take most of a second to answer, and
     * 400ms was short enough to sweep straight past it. Only silent addresses
     * ever wait this long -- a host that is up refuses a closed port at once --
     * so the cost tracks how empty the subnet is, not how big it is.
     */
    private static final int THREADS = 24;
    private static final int PROBE_TIMEOUT_MS = 900;
    private static final int LAST_OCTET = 254;
    /** Report progress every N addresses instead of 254 times. */
    private static final int PROGRESS_EVERY = 8;

    /** Both callbacks arrive on the main thread. */
    public interface Listener {
        void onScanProgress(int done, int total);
        /** The first phone that answered, or null when nothing did. */
        void onScanDone(String ip);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final AtomicBoolean finished = new AtomicBoolean();

    private final AtomicInteger done = new AtomicInteger();
    /** Addresses that came back with something, i.e. a host is there. */
    private final AtomicInteger answered = new AtomicInteger();
    /** Addresses that said nothing at all until the timeout. */
    private final AtomicInteger silent = new AtomicInteger();
    /** First error seen, kept for the summary. Racy on purpose: it is a log line. */
    private volatile String firstMiss;

    private volatile ExecutorService pool;
    private volatile boolean stopped;

    public ServerScan(Listener listener) { this.listener = listener; }

    /**
     * Start sweeping. One scan per instance.
     *
     * @param preferred an address to try first -- whatever is in the IP box --
     *                  or null. It need not be on the swept subnet.
     * @return the prefix being swept, e.g. "192.168.1.", or null when there is
     *         no address to sweep from: no callback follows, nothing to wait on.
     */
    public String start(Context c, String preferred) {
        final String self = localIp(c);
        final String prefix = subnetOf(self);
        if (prefix == null) return null;

        ArrayList<String> targets = new ArrayList<String>(LAST_OCTET + 2);
        add(targets, preferred, self);
        add(targets, gateway(c), self);
        for (int i = 1; i <= LAST_OCTET; i++) add(targets, prefix + i, self);

        final int total = targets.size();
        Logger.i("scan: from " + self + ", sweeping " + prefix + "1-" + LAST_OCTET
                + " port " + Config.HEADUNIT_SERVER_PORT + ", " + total + " addresses");

        ExecutorService p = Executors.newFixedThreadPool(THREADS);
        pool = p;
        progress(0, total);
        for (int i = 0; i < total; i++) p.execute(probeTask(targets.get(i), total));
        p.shutdown();   // no more work comes in; the queued probes still run
        return prefix;
    }

    /** Stop a sweep in flight. No callback follows a cancel. */
    public void cancel() {
        stopped = true;
        finished.set(true);
        shutdown();
    }

    private Runnable probeTask(final String ip, final int total) {
        return new Runnable() {
            @Override public void run() {
                if (!stopped && probe(ip)) {
                    finish(ip, total);
                    return;
                }
                int n = done.incrementAndGet();
                // Last one home and still nothing: say so, rather than leaving a
                // spinner up for a phone that will never answer.
                if (n >= total) finish(null, total);
                else if (n % PROGRESS_EVERY == 0) progress(n, total);
            }
        };
    }

    /** True when something accepted a connection on the head unit server port. */
    private boolean probe(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, Config.HEADUNIT_SERVER_PORT),
                    PROBE_TIMEOUT_MS);
            return true;
        } catch (SocketTimeoutException e) {
            silent.incrementAndGet();
            return false;
        } catch (Throwable t) {
            // Something answered for that address, just not what we wanted --
            // normally a refusal from a live host. Every address failing this
            // way instead means the route is wrong, which is why the first one
            // is kept for the summary.
            answered.incrementAndGet();
            if (firstMiss == null) firstMiss = ip + " " + t;
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void finish(final String ip, int total) {
        if (!finished.compareAndSet(false, true)) return;  // first answer wins
        stopped = true;
        shutdown();
        // The one line worth having in logcat when a sweep comes back empty: no
        // address answering at all means nothing on that subnet was reached, and
        // the address logged at the start is then the one to doubt.
        Logger.i("scan: " + (ip != null ? "found " + ip : "nothing found")
                + " -- " + done.get() + "/" + total + " probed, " + answered.get()
                + " answered, " + silent.get() + " silent"
                + (firstMiss != null ? ", first miss " + firstMiss : ""));
        main.post(new Runnable() {
            @Override public void run() { listener.onScanDone(ip); }
        });
    }

    private void shutdown() {
        ExecutorService p = pool;
        pool = null;
        // Drops the queued probes. The couple already inside connect() finish on
        // their own timeout -- interrupting a socket connect is not portable --
        // but the stopped flag means none of them reports anything.
        if (p != null) p.shutdownNow();
    }

    private void progress(final int n, final int total) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!finished.get()) listener.onScanProgress(n, total);
            }
        });
    }

    /** Skips nulls, ourselves, and anything already queued. */
    private static void add(ArrayList<String> targets, String ip, String self) {
        if (ip == null || ip.equals(self) || targets.contains(ip)) return;
        targets.add(ip);
    }

    /**
     * Wi-Fi address first: half these units carry a 4G modem, and
     * {@link WirelessDiscovery#localIpv4()} would happily hand back rmnet0's
     * address, which shares a subnet with nothing.
     */
    private static String localIp(Context c) {
        try {
            WifiManager wm = wifi(c);
            int ip = (wm == null || wm.getConnectionInfo() == null)
                    ? 0 : wm.getConnectionInfo().getIpAddress();
            if (ip != 0) return ipv4(ip);
        } catch (Throwable t) {
            Logger.w("scan: no Wi-Fi address (" + t + ")");
        }
        return WirelessDiscovery.localIpv4();
    }

    /** The phone itself whenever the phone is the hotspot. */
    private static String gateway(Context c) {
        try {
            WifiManager wm = wifi(c);
            DhcpInfo d = (wm == null) ? null : wm.getDhcpInfo();
            if (d != null && d.gateway != 0) return ipv4(d.gateway);
        } catch (Throwable ignored) {}
        return null;
    }

    private static WifiManager wifi(Context c) {
        return (WifiManager) c.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    /** WifiManager reports its addresses little-endian. Yes, really. */
    static String ipv4(int wifiIp) {
        return (wifiIp & 0xFF) + "." + ((wifiIp >> 8) & 0xFF) + "."
                + ((wifiIp >> 16) & 0xFF) + "." + ((wifiIp >> 24) & 0xFF);
    }

    /**
     * "192.168.1.42" -> "192.168.1.", or null if that is not an address.
     *
     * ponytail: /24 only. A head unit sits on a phone hotspot or a home router,
     * and both hand out /24s; sweeping a real /16 would take twenty minutes.
     */
    static String subnetOf(String ip) {
        int dot = (ip == null) ? -1 : ip.lastIndexOf('.');
        return (dot < 0) ? null : ip.substring(0, dot + 1);
    }
}
