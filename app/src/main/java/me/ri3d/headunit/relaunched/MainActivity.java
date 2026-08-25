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
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Iterator;
import java.util.Set;

import me.ri3d.headunit.relaunched.input.KeyInput;
import me.ri3d.headunit.relaunched.input.TouchInput;
import me.ri3d.headunit.relaunched.protocol.AndroidAutoSession;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.wifi.ServerScan;
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
        implements ServiceConnection, HeadUnitService.Listener, SurfaceHolder.Callback,
                   ServerScan.Listener {

    private static final String PREF_PHONE_IP = "phone_ip";
    private static final String PREF_EXIT_CLOSES = "aa_exit_closes_app";

    private SurfaceView surfaceView;
    private View overlay;
    private View settingsView;
    private View reconnectView;
    private TextView reconnectTitle;
    private TextView reconnectDetail;
    private TextView status;
    private TextView settingsStatus;
    private TextView dpiValue;
    private Button resolutionButton;
    private Button exitModeButton;
    private Button stopButton;
    private EditText editIp;
    private SharedPreferences prefs;

    private final TouchInput touchInput = new TouchInput();
    private final KeyInput keyInput = new KeyInput();

    private HeadUnitService service;
    private boolean bound;

    /** Everything that decides which of the three screens is up. */
    private final ScreenState screen = new ScreenState();

    /**
     * True while the phone holds video focus NATIVE, i.e. it has stopped drawing
     * and is waiting to be told it may start again. Hiding the panel has to
     * claim focus back, or the screen just stays black.
     *
     * Not part of ScreenState: this is what the *phone* is doing, and it
     * outlives whichever screen we happen to show.
     */
    private boolean phoneReleasedScreen;
    /** What Android Auto's own exit button does here: close, or show the panel. */
    private boolean exitClosesApp;

    /** Running network sweep, or null. */
    private ServerScan scan;
    /** Prefix it is sweeping, for the progress line. */
    private String scanPrefix;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A head unit's screen must never blank while projecting.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        surfaceView = (SurfaceView) findViewById(R.id.surface);
        overlay = findViewById(R.id.overlay);
        status = (TextView) findViewById(R.id.status);

        settingsView = findViewById(R.id.settings);
        settingsStatus = (TextView) findViewById(R.id.settings_status);
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(true); }
        });
        findViewById(R.id.btn_settings_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(false); }
        });
        findViewById(R.id.btn_bt_pair).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Straight out to the panel: what happens next is a status line,
                // and there is nothing left to set in here.
                showSettings(false);
                connect(HeadUnitService.MODE_WIFI, firstPairedPhone(), null);
            }
        });

        reconnectView = findViewById(R.id.reconnect);
        reconnectTitle = (TextView) findViewById(R.id.reconnect_title);
        reconnectDetail = (TextView) findViewById(R.id.reconnect_detail);
        reconnectView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // The retry runs on regardless; this only uncovers the panel so
                // Disconnect stays reachable while it does.
                screen.busyDismissed = true;
                render();
            }
        });

        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(touchInput);

        editIp = (EditText) findViewById(R.id.edit_ip);
        prefs = Settings.prefs(this);
        editIp.setText(prefs.getString(PREF_PHONE_IP, ""));

        // The service loads these too, but it may not exist yet -- bindService
        // below is asynchronous and the label has to be right now.
        Settings.load(this);
        dpiValue = (TextView) findViewById(R.id.dpi_value);
        findViewById(R.id.btn_dpi_down).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stepScale(-1); }
        });
        findViewById(R.id.btn_dpi_up).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stepScale(1); }
        });

        exitClosesApp = prefs.getBoolean(PREF_EXIT_CLOSES, false);
        exitModeButton = (Button) findViewById(R.id.btn_exit_mode);
        exitModeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                exitClosesApp = !exitClosesApp;
                prefs.edit().putBoolean(PREF_EXIT_CLOSES, exitClosesApp).commit();
                showExitMode();
            }
        });
        showExitMode();

        resolutionButton = (Button) findViewById(R.id.btn_resolution);
        resolutionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleResolution(); }
        });
        showResolution();
        showDpi();

        findViewById(R.id.btn_usb).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connect(HeadUnitService.MODE_USB, null, null);
            }
        });
        findViewById(R.id.btn_ip).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connectToTypedIp(); }
        });
        findViewById(R.id.btn_scan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startScan(); }
        });
        editIp.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                connectToTypedIp();
                return true;
            }
        });
        stopButton = (Button) findViewById(R.id.btn_stop);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stop(); }
        });
        findViewById(R.id.btn_cancel_reconnect).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stop(); }
        });

        requestRuntimePermissions();
        render();   // initial state comes from the same place every later one does

        Intent i = new Intent(this, HeadUnitService.class);
        startService(i);            // keeps running if the activity is destroyed
        bindService(i, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from a reverse or turn-signal camera. The Surface may or may not
        // have been destroyed on the way through -- when it was not, nothing
        // else tells the phone we need a keyframe, and the panel stays black
        // until Android Auto sends one on its own schedule.
        if (service != null) service.refreshVideo();
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
            setStatus("No paired Bluetooth phone — pair one first, or start the phone side manually");
            return null;
        }
        // ponytail: first paired device wins. Add a picker when you actually
        // have two phones paired to the same head unit.
        Iterator<BluetoothDevice> it = bonded.iterator();
        return it.next();
    }

    /**
     * Sweep the local /24 for the phone, then dial whatever answered. Reuses the
     * reconnect screen: same spinner, same Stop searching button, and here that
     * button means exactly what it says.
     */
    private void startScan() {
        if (scan != null) return;               // one sweep is enough
        ServerScan s = new ServerScan(this);
        // Whatever is in the box goes first: it is either the address that
        // worked last time or the one the user is about to try by hand.
        String typed = editIp.getText().toString().trim();
        scanPrefix = s.start(this, typed.length() == 0 ? null : typed);
        if (scanPrefix == null) {
            setStatus(getString(R.string.scan_offline));
            return;
        }
        scan = s;
        screen.scanning = true;
        screen.busyDismissed = false;           // the user just asked for this
        reconnectTitle.setText(R.string.scanning);
        render();
    }

    private void cancelScan() {
        screen.scanning = false;
        if (scan == null) return;
        scan.cancel();
        scan = null;
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

    /**
     * Nudge how large AA draws its UI. +1 is bigger, which is fewer dp across
     * the same pixels -- the fix for a cramped screen. See Settings.widthDp().
     */
    private void stepScale(int steps) {
        int before = Settings.widthDp();
        Settings.stepScale(this, steps);
        showDpi();
        if (Settings.widthDp() == before) return; // already at the limit
        renegotiate(Settings.videoDpi() + " dpi");
    }

    /**
     * Auto -> 480p -> 720p -> 1080p -> Auto. A cycling button rather than a
     * spinner: four choices, and a head unit touchscreen is not precise enough
     * for a dropdown you have to hit twice.
     */
    private void cycleResolution() {
        int[] choices = Settings.resolutionChoices();
        int at = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == Settings.resolutionMode()) { at = i; break; }
        }
        int before = Settings.videoWidth();
        Settings.setResolution(this, choices[(at + 1) % choices.length]);
        showResolution();
        showDpi();
        if (Settings.videoWidth() == before) return; // the cap held it in place
        renegotiate(Settings.name(Settings.videoResolution()));
    }

    /**
     * Both of these travel only in the service discovery response, so a live
     * session has to be rebuilt before the change is visible.
     */
    private void renegotiate(String what) {
        if (service == null || !service.isConnected()) return;
        screen.projecting = false;
        render();
        // Stays pinned across the reconnect, so you can keep stepping until it
        // looks right instead of holding BACK after every try.
        setStatus("reconnecting at " + what);
        service.reconnect();
    }

    private void showDpi() {
        dpiValue.setText(getString(R.string.dpi_format,
                Settings.videoDpi(), Settings.widthDp(), Settings.heightDp()));
    }

    private void showExitMode() {
        exitModeButton.setText(exitClosesApp
                ? R.string.exit_mode_close : R.string.exit_mode_panel);
    }

    /**
     * Android Auto's exit button, honoured the way that toggle says. Closing
     * means the whole app: leaving the service alive would have it reconnect on
     * the next USB attach with no activity and no surface to draw on.
     *
     * @return true when the app is on its way out and the caller should stop.
     */
    private boolean closeOnAaExit() {
        if (!exitClosesApp) return false;
        stop();
        stopService(new Intent(this, HeadUnitService.class));
        finish();
        return true;
    }

    private void showResolution() {
        String mode = (Settings.resolutionMode() == Config.RES_AUTO)
                ? getString(R.string.res_auto)
                : Settings.name(Settings.resolutionMode());
        // Say so when the panel cap overrode the request, rather than letting
        // the button look like it ignored the tap.
        resolutionButton.setText(getString(
                Settings.resolutionWasCapped() ? R.string.res_format_capped : R.string.res_format,
                mode, Settings.videoWidth(), Settings.videoHeight()));
    }

    private void connect(int mode, BluetoothDevice bt, String host) {
        if (service == null) { setStatus("service not ready"); return; }
        // A sweep still running would connect over its own result a few seconds
        // from now, on top of whatever this call is about to start.
        cancelScan();
        screen.connectRequested();
        service.connect(mode, bt, host);
        setChannels(true);
        render();
    }

    /**
     * End the session and whatever retry sits behind it. The same button twice:
     * from the panel it stops a live session, from the reconnect screen it stops
     * the search -- both are the user saying they meant this disconnect.
     */
    private void stop() {
        cancelScan();
        if (service != null) service.disconnect("stopped", false);
        screen.stopRequested();   // also covers the service not being bound yet
        render();
        setChannels(false);
    }

    /** The only code here that touches a View's visibility. */
    private void render() {
        ScreenState.Screen s = screen.current();
        showReconnect(s == ScreenState.Screen.BUSY);
        overlay.setVisibility(s == ScreenState.Screen.PANEL ? View.VISIBLE : View.GONE);
        settingsView.setVisibility(s == ScreenState.Screen.SETTINGS ? View.VISIBLE : View.GONE);

        // Gone rather than dimmed. It shares the header with the status line,
        // which is the one thing on this screen worth reading, and a control
        // that does nothing four fifths of the time should not be pushing that
        // onto a second line to sit there greyed out.
        stopButton.setVisibility(screen.canStop() ? View.VISIBLE : View.GONE);
    }

    private void showSettings(boolean open) {
        screen.settingsOpen = open;
        render();
    }

    /** @return true when a BACK press was spent closing settings. */
    private boolean closeSettings() {
        if (!screen.settingsOpen) return false;
        showSettings(false);
        return true;
    }

    /**
     * Fade in, then let the indeterminate ProgressBar and a slow pulse on the
     * label carry it -- a still "Reconnecting" on a car screen reads as a crash.
     * Hiding is instant: what replaces it is the video coming back.
     */
    private void showReconnect(boolean show) {
        if (show == (reconnectView.getVisibility() == View.VISIBLE)) return;
        if (!show) {
            reconnectTitle.clearAnimation();
            reconnectView.setVisibility(View.GONE);
            return;
        }
        reconnectView.setVisibility(View.VISIBLE);
        reconnectView.startAnimation(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in));
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.3f);
        pulse.setDuration(900);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        reconnectTitle.startAnimation(pulse);
    }

    /** Point the input listeners at the live session, or detach them. */
    private void setChannels(boolean attached) {
        AndroidAutoSession s = (service == null) ? null : service.session();
        touchInput.setChannel(attached && s != null ? s.channels().input : null);
        keyInput.setChannel(attached && s != null ? s.channels().input : null);
    }

    /**
     * The panel and the settings header carry the same line. Changing the
     * resolution rebuilds the session, and that has to be visible from the
     * screen you changed it on.
     */
    private void setStatus(String s) {
        status.setText(s);
        settingsStatus.setText(s);
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
        setStatus(service.isConnected() ? "Connected" : getString(R.string.status_idle));
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
        if ("authenticated".equals(state)) screen.projectionStarted();
        // Same words the panel would show, so a retry visibly progresses instead
        // of sitting on "phone disconnected" until the picture returns.
        reconnectDetail.setText(state);
        render();
    }

    @Override
    public void onEnded(String reason, boolean retrying) {
        if (AndroidAutoSession.REASON_SHUTDOWN.equals(reason) && closeOnAaExit()) return;
        phoneReleasedScreen = false;
        screen.projectionEnded(retrying);
        setStatus((retrying ? "reconnecting: " : "disconnected: ") + reason);
        reconnectTitle.setText(R.string.reconnecting);
        reconnectDetail.setText(reason);
        render();
        setChannels(false);
    }

    /**
     * Android Auto's exit-to-car button takes this route rather than shutting
     * the session down: it drops video focus to NATIVE and waits. Show our own
     * UI, which is the only thing a head unit can sensibly do with the screen.
     */
    @Override
    public void onVideoFocus(boolean projected) {
        // The other half of AA's exit button: it hands the screen back rather
        // than quitting, so the toggle has to be honoured here too.
        if (!projected && closeOnAaExit()) return;
        phoneReleasedScreen = !projected;
        screen.panelPinned = !projected;
        if (!projected) setStatus(getString(R.string.status_screen_returned));
        render();
    }

    // ---- ServerScan.Listener ----------------------------------------------

    @Override
    public void onScanProgress(int done, int total) {
        reconnectDetail.setText(getString(R.string.scan_progress, scanPrefix, done, total));
    }

    @Override
    public void onScanDone(String ip) {
        scan = null;    // finished on its own; nothing left to cancel
        screen.scanning = false;
        if (ip == null) {
            setStatus(getString(R.string.scan_none));
            render();
            return;
        }
        // Straight into the manual-IP path, which already saves the address.
        editIp.setText(ip);
        connectToTypedIp();
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

    /**
     * BACK is overloaded: a tap goes to Android Auto, holding it brings this
     * panel back over the video (otherwise there is no way to reach the scale
     * buttons or Disconnect once projection starts).
     *
     * That means AA must not see the press until we know which it was, so the
     * down is held here and replayed as a press+release from onKeyUp. Everything
     * else goes straight through.
     */
    private boolean backWasLongPress;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                // Required, together with returning true, for the framework to
                // deliver onKeyLongPress at all.
                event.startTracking();
                backWasLongPress = false;
            }
            return true;
        }
        if (keyInput.onKey(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // Only while projecting: idle, the panel is already up and a long BACK
        // should still leave the app the way it normally does. It would also
        // leave the pin set for a session the user never started by hand.
        // Not while settings are up: the pin would toggle invisibly behind them.
        if (keyCode == KeyEvent.KEYCODE_BACK && screen.projecting && !screen.settingsOpen) {
            backWasLongPress = true;
            screen.panelPinned = !screen.panelPinned;
            // Going back to Android Auto after it handed the screen over means
            // asking for it: it will not resume on its own.
            if (!screen.panelPinned && phoneReleasedScreen && service != null) {
                phoneReleasedScreen = false;
                setStatus("resuming Android Auto");
                service.resumeProjection();
            }
            render();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (backWasLongPress) return true;
            // Settings first, or BACK is swallowed by Android Auto and the only
            // way out of the screen is the on-screen button.
            if (closeSettings()) return true;
            // Short press: hand it to AA, or fall back to leaving the app when
            // there is no session to hand it to.
            if (keyInput.tap(keyCode)) return true;
            return super.onKeyUp(keyCode, event);
        }
        if (keyInput.onKey(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }
}
