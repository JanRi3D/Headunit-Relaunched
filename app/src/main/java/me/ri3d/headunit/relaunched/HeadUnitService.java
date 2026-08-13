package me.ri3d.headunit.relaunched;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Surface;

import me.ri3d.headunit.relaunched.protocol.AndroidAutoSession;
import me.ri3d.headunit.relaunched.protocol.Ssl;
import me.ri3d.headunit.relaunched.transport.Transport;
import me.ri3d.headunit.relaunched.transport.UsbTransport;
import me.ri3d.headunit.relaunched.transport.WifiTransport;
import me.ri3d.headunit.relaunched.util.Logger;

/**
 * Owns the connection so it survives the activity being recreated (rotation,
 * the launcher coming back, the screen blanking).
 *
 * It is a foreground service purely to keep the process from being killed while
 * projecting -- there is no background work here. When nothing is connected it
 * holds no wakelock and does nothing at all.
 */
public final class HeadUnitService extends Service {

    public static final int MODE_USB = 0;
    /** Listen on 5288 and let the phone dial us (Bluetooth handshake flow). */
    public static final int MODE_WIFI = 1;
    /** Dial the phone's head unit server at a typed IP. */
    public static final int MODE_WIFI_DIAL = 2;

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "headunit";
    private static final long RETRY_DELAY_MS = 3000;

    /** Callback for the activity. Always delivered on the main thread. */
    public interface Listener {
        void onState(String state);
        void onEnded(String reason);
    }

    public final class LocalBinder extends Binder {
        public HeadUnitService get() { return HeadUnitService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;
    private AndroidAutoSession session;
    private Surface surface;

    private int mode = MODE_USB;
    private BluetoothDevice btDevice;
    private UsbDevice usbDevice;
    private String host;
    private volatile boolean wantConnected;

    /**
     * Bumped on every connect/disconnect. A session's callbacks carry the
     * generation they were created under and are dropped if it has moved on.
     *
     * Without this, switching modes wrecks the new connection: stopping the old
     * session fires onSessionEnded *after* the replacement is already in
     * `session`, so the stale callback nulls the live reference and its 3s retry
     * timer then opens a second connection on top of the working one.
     */
    private int generation;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Plugging a phone in should just work, without touching the UI. */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // Remember exactly which device appeared -- on a head unit the
                // bus is full of internal peripherals and scanning finds those
                // first.
                usbDevice = (UsbDevice) i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Logger.i("service: USB attached"
                        + (usbDevice != null ? " " + usbDevice.getDeviceName() : ""));
                if (session == null) connect(MODE_USB, null, null);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Logger.i("service: USB detached");
                if (mode == MODE_USB) disconnect();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, f);
        Logger.i("service: created");
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        wantConnected = false;
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        stopSession();
        releaseLocks();
        super.onDestroy();
    }

    // =====================================================================
    // API for the activity
    // =====================================================================

    public void setListener(Listener l) { listener = l; }

    public boolean isConnected() { return session != null; }

    /** Live session, or null. The activity uses it to reach the input channel. */
    public AndroidAutoSession session() { return session; }

    /** The SurfaceView's surface, or null when it goes away. */
    public void setSurface(Surface s) {
        surface = s;
        AndroidAutoSession sess = session;
        if (sess != null) sess.setSurface(s);
    }

    /**
     * @param bt   paired phone for the MODE_WIFI Bluetooth handshake, else null
     * @param host phone IP for MODE_WIFI_DIAL, else null
     */
    public void connect(int mode, BluetoothDevice bt, String host) {
        // Retire the previous session's callbacks before tearing it down, so its
        // shutdown cannot reach in and clobber the one we are about to build.
        final int gen = ++generation;
        stopSession();
        this.mode = mode;
        this.btDevice = bt;
        this.host = host;
        this.wantConnected = true;

        Ssl ssl = Ssl.fromResources(this);
        if (ssl == null) {
            notifyEnded("cannot load TLS identity from res/raw");
            return;
        }

        Transport t;
        if (mode == MODE_WIFI_DIAL) {
            t = new WifiTransport(host, Config.HEADUNIT_SERVER_PORT);
        } else if (mode == MODE_WIFI) {
            t = new WifiTransport(bt);
        } else {
            t = new UsbTransport(this, usbDevice);
        }

        AndroidAutoSession s = new AndroidAutoSession(t, ssl, listenerFor(gen));
        s.setSurface(surface);
        session = s;

        acquireLocks();
        startForegroundCompat("Connecting over " + t.name());
        s.start();
    }

    public void disconnect() {
        generation++;  // cancel any pending retry from the session we are killing
        wantConnected = false;
        stopSession();
        releaseLocks();
        stopForegroundCompat();
    }

    private void stopSession() {
        AndroidAutoSession s = session;
        session = null;
        if (s != null) s.stop();
    }

    // =====================================================================
    // AndroidAutoSession.Listener -- called on the session thread
    // =====================================================================

    /** Session callbacks, tagged with the generation that created them. */
    private AndroidAutoSession.Listener listenerFor(final int gen) {
        return new AndroidAutoSession.Listener() {
            @Override public void onSessionState(final String state) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) return;
                        updateNotification(state);
                        if (listener != null) listener.onState(state);
                    }
                });
            }

            @Override public void onSessionEnded(final String reason) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) return; // superseded; not our business
                        session = null;
                        notifyEnded(reason);
                        if (!wantConnected) {
                            releaseLocks();
                            stopForegroundCompat();
                            return;
                        }
                        // Phone unplugged and replugged, or a transient error:
                        // try again rather than making the user tap anything.
                        main.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (gen != generation) return;
                                if (wantConnected && session == null) {
                                    connect(mode, btDevice, host);
                                }
                            }
                        }, RETRY_DELAY_MS);
                    }
                });
            }
        };
    }

    private void notifyEnded(String reason) {
        updateNotification("Disconnected: " + reason);
        if (listener != null) listener.onEnded(reason);
    }

    // =====================================================================
    // Locks and notification
    // =====================================================================

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hu:session");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();

        if (mode == MODE_WIFI) {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager)
                        getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hu:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    private void startForegroundCompat(String text) {
        startForeground(NOTIFICATION_ID, buildNotification(text));
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        ensureChannel();
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("Android Auto")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true);

        // build() exists from API 16; getNotification() is the older spelling.
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Head unit", NotificationManager.IMPORTANCE_LOW));
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        stopForeground(true);
    }
}
