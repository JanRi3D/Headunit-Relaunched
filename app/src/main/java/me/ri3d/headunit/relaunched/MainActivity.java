package me.ri3d.headunit.relaunched;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Iterator;
import java.util.Set;

import me.ri3d.headunit.relaunched.input.KeyInput;
import me.ri3d.headunit.relaunched.input.TouchInput;
import me.ri3d.headunit.relaunched.protocol.AndroidAutoSession;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.wifi.WirelessDiscovery;

/**
 * The screen. A SurfaceView the decoder renders straight into, plus a small
 * overlay that disappears once projection starts.
 *
 * Deliberately plain android.app.Activity: no AppCompat, no AndroidX, no
 * fragments. On API 16 those pull in megabytes and a support-library theme
 * engine for a UI that is one SurfaceView and three buttons.
 */
public final class MainActivity extends Activity
        implements ServiceConnection, HeadUnitService.Listener, SurfaceHolder.Callback {

    private static final String PREF_PHONE_IP = "phone_ip";

    private SurfaceView surfaceView;
    private View overlay;
    private TextView status;
    private EditText editIp;
    private SharedPreferences prefs;

    private final TouchInput touchInput = new TouchInput();
    private final KeyInput keyInput = new KeyInput();

    private HeadUnitService service;
    private boolean bound;
    /** True from "authenticated" until the session ends; hides the overlay. */
    private boolean projecting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A head unit's screen must never blank while projecting.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        surfaceView = (SurfaceView) findViewById(R.id.surface);
        overlay = findViewById(R.id.overlay);
        status = (TextView) findViewById(R.id.status);

        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(touchInput);

        editIp = (EditText) findViewById(R.id.edit_ip);
        prefs = getSharedPreferences("headunit", MODE_PRIVATE);
        editIp.setText(prefs.getString(PREF_PHONE_IP, ""));

        findViewById(R.id.btn_usb).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connect(HeadUnitService.MODE_USB, null, null);
            }
        });
        findViewById(R.id.btn_wifi).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connect(HeadUnitService.MODE_WIFI, firstPairedPhone(), null);
            }
        });
        findViewById(R.id.btn_ip).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connectToTypedIp(); }
        });
        editIp.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                connectToTypedIp();
                return true;
            }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (service != null) service.disconnect();
                setChannels(false);
            }
        });

        requestRuntimePermissions();

        Intent i = new Intent(this, HeadUnitService.class);
        startService(i);            // keeps running if the activity is destroyed
        bindService(i, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            if (service != null) service.setListener(null);
            unbindService(this);
            bound = false;
        }
        super.onDestroy();
    }

    /**
     * API 16 grants everything at install time; only 23+ needs this. Isolated
     * here so nothing else in the app has to think about it.
     */
    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            String[] perms = {
                    "android.permission.RECORD_AUDIO",
                    "android.permission.ACCESS_FINE_LOCATION", // Bluetooth scan on 23..30
                    "android.permission.BLUETOOTH_CONNECT",    // 31+
            };
            requestPermissions(perms, 1);
        } catch (Throwable e) {
            Logger.w("permission request failed: " + e);
        }
    }

    private BluetoothDevice firstPairedPhone() {
        Set<BluetoothDevice> bonded = WirelessDiscovery.bondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            setStatus("No paired Bluetooth phone -- pair one first, or start the phone side manually");
            return null;
        }
        // ponytail: first paired device wins. Add a picker when you actually
        // have two phones paired to the same head unit.
        Iterator<BluetoothDevice> it = bonded.iterator();
        return it.next();
    }

    /** Dial the phone's head unit server at whatever is in the IP box. */
    private void connectToTypedIp() {
        String ip = editIp.getText().toString().trim();
        if (ip.length() == 0) {
            setStatus("Enter the phone's IP first");
            return;
        }
        // Remember it: a head unit that forgets the phone every reboot is useless.
        prefs.edit().putString(PREF_PHONE_IP, ip).commit();
        connect(HeadUnitService.MODE_WIFI_DIAL, null, ip);
    }

    private void connect(int mode, BluetoothDevice bt, String host) {
        if (service == null) { setStatus("service not ready"); return; }
        service.connect(mode, bt, host);
        setChannels(true);
    }

    /** Point the input listeners at the live session, or detach them. */
    private void setChannels(boolean attached) {
        AndroidAutoSession s = (service == null) ? null : service.session();
        touchInput.setChannel(attached && s != null ? s.channels().input : null);
        keyInput.setChannel(attached && s != null ? s.channels().input : null);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    // ---- ServiceConnection -------------------------------------------------

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((HeadUnitService.LocalBinder) binder).get();
        service.setListener(this);
        bound = true;
        SurfaceHolder h = surfaceView.getHolder();
        if (h.getSurface() != null && h.getSurface().isValid()) {
            service.setSurface(h.getSurface());
        }
        setStatus(service.isConnected() ? "connected" : "idle -- plug in a phone or tap a button");
        setChannels(service.isConnected());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        bound = false;
        setChannels(false);
    }

    // ---- HeadUnitService.Listener -----------------------------------------

    @Override
    public void onState(String state) {
        setStatus(state);
        setChannels(true);
        // Latch, don't compare: "authenticated" is followed by "negotiating
        // channels", so testing the current state put the overlay straight back
        // over the video. Once the session is up it stays hidden until it drops.
        if ("authenticated".equals(state)) projecting = true;
        overlay.setVisibility(projecting ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onEnded(String reason) {
        projecting = false;
        setStatus("disconnected: " + reason);
        overlay.setVisibility(View.VISIBLE);
        setChannels(false);
    }

    // ---- SurfaceHolder.Callback -------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (service != null) service.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The decoder output is scaled by SurfaceFlinger; nothing to do here.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (service != null) service.setSurface(null);
    }

    // ---- hardware keys -----------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyInput.onKey(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyInput.onKey(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }
}
