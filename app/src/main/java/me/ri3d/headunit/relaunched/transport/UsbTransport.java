package me.ri3d.headunit.relaunched.transport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.usb.UsbAoa;
import me.ri3d.headunit.relaunched.util.Logger;

/**
 * USB transport: head unit is the USB host, phone is an AOA accessory.
 *
 * connect() finds a phone, flips it into accessory mode if needed, waits for it
 * to come back on the bus, and claims the bulk endpoints.
 */
public final class UsbTransport implements Transport {

    private static final String ACTION_PERMISSION = "me.ri3d.headunit.relaunched.USB_PERMISSION";

    /** Re-enumeration after the AOA start command. Phones take 0.5-2s. */
    private static final int REENUM_TIMEOUT_MS = 8000;
    private static final int REENUM_POLL_MS    = 200;

    private final Context ctx;
    private final UsbManager usb;
    /** Device from the ATTACHED broadcast, tried first. May be null. */
    private final UsbDevice preferred;

    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface iface;
    private UsbEndpoint epIn, epOut;

    private volatile boolean closed;

    /**
     * API 16/17 have no bulkTransfer(ep, buf, offset, len, timeout), so those
     * releases bounce through one scratch buffer rather than allocating per
     * call. Null on API 18+, where the offset overload is used directly.
     */
    private final byte[] scratch =
            (Build.VERSION.SDK_INT >= 18) ? null : new byte[64 * 1024];

    public UsbTransport(Context ctx) {
        this(ctx, null);
    }

    /** @param preferred device from the ATTACHED broadcast, or null to scan. */
    public UsbTransport(Context ctx, UsbDevice preferred) {
        this.ctx = ctx.getApplicationContext();
        this.usb = (UsbManager) this.ctx.getSystemService(Context.USB_SERVICE);
        this.preferred = preferred;
    }

    @Override public String name() { return "USB"; }

    @Override
    public boolean connect() throws IOException {
        if (usb == null) throw new IOException("no USB host support on this device");

        UsbDevice dev = UsbAoa.findAccessory(usb);
        if (dev == null) {
            // Nothing in accessory mode yet -- try to flip a phone over. A head
            // unit's bus carries several permanent peripherals, so try every
            // candidate rather than assuming the first one is the phone. The
            // device from the ATTACHED broadcast goes first: it is by definition
            // the thing the user just plugged in.
            List<UsbDevice> candidates = UsbAoa.findCandidates(usb);
            if (preferred != null) {
                candidates.remove(preferred);
                candidates.add(0, preferred);
            }
            if (candidates.isEmpty()) {
                Logger.i("USB: no device attached");
                return false;
            }

            boolean started = false;
            for (int i = 0; i < candidates.size() && !started; i++) {
                UsbDevice candidate = candidates.get(i);
                Logger.i("USB: trying " + candidate.getDeviceName() + " "
                        + hex4(candidate.getVendorId()) + ":" + hex4(candidate.getProductId())
                        + " (" + (i + 1) + "/" + candidates.size() + ")");

                if (!requestPermission(candidate)) {
                    Logger.w("USB: permission denied, skipping");
                    continue;
                }
                UsbDeviceConnection c = usb.openDevice(candidate);
                if (c == null) {
                    Logger.w("USB: openDevice failed, skipping");
                    continue;
                }
                try {
                    started = UsbAoa.startAccessoryMode(c);
                } finally {
                    c.close(); // on success the device is about to disappear
                }
            }
            if (!started) {
                Logger.w("USB: no attached device speaks AOA");
                return false;
            }

            dev = waitForAccessory();
            if (dev == null) throw new IOException("phone did not re-enumerate in accessory mode");
        }

        if (!requestPermission(dev)) throw new IOException("USB permission denied for accessory");

        iface = UsbAoa.bulkInterface(dev);
        if (iface == null) throw new IOException("no bulk interface on accessory");

        conn = usb.openDevice(dev);
        if (conn == null) throw new IOException("openDevice(accessory) failed");
        if (!conn.claimInterface(iface, true)) {
            conn.close();
            conn = null;
            throw new IOException("claimInterface failed");
        }

        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep; else epOut = ep;
        }
        if (epIn == null || epOut == null) {
            close();
            throw new IOException("missing bulk endpoints");
        }

        device = dev;
        closed = false;
        Logger.i("USB: connected, maxPacket in=" + epIn.getMaxPacketSize()
                + " out=" + epOut.getMaxPacketSize());
        return true;
    }

    private static String hex4(int v) { return String.format("%04X", v); }

    /**
     * Polls the device list instead of listening for ATTACHED broadcasts: this
     * runs once per connection, is bounded, and avoids receiver lifecycle
     * juggling on a thread that is about to block on USB anyway.
     */
    private UsbDevice waitForAccessory() {
        long deadline = System.currentTimeMillis() + REENUM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !closed) {
            try { Thread.sleep(REENUM_POLL_MS); } catch (InterruptedException e) { return null; }
            UsbDevice d = UsbAoa.findAccessory(usb);
            if (d != null) {
                Logger.i("USB: accessory appeared as " + hex4(d.getProductId()));
                return d;
            }
        }
        return null;
    }

    private boolean requestPermission(UsbDevice dev) {
        if (usb.hasPermission(dev)) return true;

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] granted = new boolean[1];

        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                granted[0] = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                latch.countDown();
            }
        };
        ctx.registerReceiver(rx, new IntentFilter(ACTION_PERMISSION));
        try {
            // API 31+ rejects a PendingIntent that declares neither mutability.
            int flags = (Build.VERSION.SDK_INT >= 23) ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx, 0, new Intent(ACTION_PERMISSION), flags);
            usb.requestPermission(dev, pi);
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try { ctx.unregisterReceiver(rx); } catch (Exception ignored) {}
        }
        return granted[0];
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        UsbDeviceConnection c = conn;
        if (closed || c == null) return -1;

        int n;
        if (Build.VERSION.SDK_INT >= 18) {
            n = c.bulkTransfer(epIn, buf, off, len, Config.USB_READ_TIMEOUT_MS);
        } else {
            int cap = Math.min(len, scratch.length);
            n = c.bulkTransfer(epIn, scratch, cap, Config.USB_READ_TIMEOUT_MS);
            if (n > 0) System.arraycopy(scratch, 0, buf, off, n);
        }

        if (n < 0) {
            // bulkTransfer cannot distinguish timeout from error. Treat it as a
            // timeout while we are still open; the caller just tries again.
            return closed ? -1 : 0;
        }
        return n;
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        UsbDeviceConnection c = conn;
        if (closed || c == null) throw new IOException("USB closed");

        int sent = 0;
        while (sent < len) {
            int n;
            if (Build.VERSION.SDK_INT >= 18) {
                n = c.bulkTransfer(epOut, buf, off + sent, len - sent, Config.USB_WRITE_TIMEOUT_MS);
            } else {
                int chunk = Math.min(len - sent, scratch.length);
                System.arraycopy(buf, off + sent, scratch, 0, chunk);
                n = c.bulkTransfer(epOut, scratch, chunk, Config.USB_WRITE_TIMEOUT_MS);
            }
            if (n <= 0) throw new IOException("USB write failed (" + n + ")");
            sent += n;
        }
    }

    @Override
    public void close() {
        closed = true;
        UsbDeviceConnection c = conn;
        conn = null;
        if (c != null) {
            try { if (iface != null) c.releaseInterface(iface); } catch (Exception ignored) {}
            try { c.close(); } catch (Exception ignored) {}
        }
        iface = null; epIn = null; epOut = null; device = null;
    }
}
