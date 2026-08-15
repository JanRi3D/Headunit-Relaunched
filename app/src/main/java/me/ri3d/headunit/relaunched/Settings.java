package me.ri3d.headunit.relaunched;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import me.ri3d.headunit.relaunched.protocol.ProtocolConstants;
import me.ri3d.headunit.relaunched.util.Logger;

/**
 * The settings you can change without a rebuild, as opposed to the compile-time
 * defaults in {@link Config}.
 *
 * Plain statics on purpose. The code that reads these runs on the session
 * thread and has no Context, and threading a settings object down through
 * Messages.serviceDiscoveryResponse() would touch half the protocol package.
 * {@link #load} runs before any session exists, so the volatiles are all the
 * synchronisation there is to do.
 */
public final class Settings {

    /** Same prefs file the activity keeps the phone IP in. */
    public static final String PREFS_NAME = "headunit";

    private static final String KEY_WIDTH_DP = "video_width_dp";
    private static final String KEY_RESOLUTION = "video_resolution";
    /** Superseded by KEY_WIDTH_DP; still read once, to carry old installs over. */
    private static final String KEY_VIDEO_DPI = "video_dpi";

    /** What the user picked: Config.RES_AUTO or a VideoCodecResolutionType. */
    private static volatile int resolutionMode = Config.VIDEO_RESOLUTION;
    /** What that came out as after auto-detection and the panel cap. */
    private static volatile int resolution = ProtocolConstants.RES_800x480;
    private static volatile int videoWidth = 800;
    private static volatile int videoHeight = 480;

    /**
     * UI scale, held as the layout width AA ends up with rather than as a
     * density.
     *
     * dp is the resolution-independent half of px = dp * dpi/160: 914dp looks
     * the same whether the stream is 800 or 1920 pixels wide, while a saved dpi
     * silently means something different at every resolution. Storing dpi was a
     * bug -- auto-detection moving 800 -> 1280 left the old number in place and
     * the UI came out half size.
     */
    private static volatile int widthDp = Config.defaultWidthDp();

    /** Physical panel, normalised to landscape. Zero until load() has run. */
    private static volatile int panelWidth;
    private static volatile int panelHeight;

    private Settings() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Pull saved values into the statics. Called from both the service and the
     * activity because either one can be the first to start -- plugging a phone
     * in wakes the service with no UI in sight.
     */
    public static void load(Context c) {
        measurePanel(c);
        SharedPreferences p = prefs(c);
        applyResolution(p.getInt(KEY_RESOLUTION, Config.VIDEO_RESOLUTION));

        int dp = p.getInt(KEY_WIDTH_DP, 0);
        if (dp <= 0) {
            // Carry over a density saved by an older build. It was always
            // chosen against an 800-wide stream, since that was the only one.
            int oldDpi = p.getInt(KEY_VIDEO_DPI, 0);
            dp = (oldDpi > 0) ? 800 * 160 / oldDpi : Config.defaultWidthDp();
        }
        applyWidthDp(dp);
    }

    // =====================================================================
    // Resolution
    // =====================================================================

    /** VideoCodecResolutionType actually in force. */
    public static int videoResolution() { return resolution; }

    /** What the user asked for: Config.RES_AUTO or a resolution type. */
    public static int resolutionMode() { return resolutionMode; }

    public static int videoWidth()  { return videoWidth; }
    public static int videoHeight() { return videoHeight; }

    public static int panelWidth()  { return panelWidth; }
    public static int panelHeight() { return panelHeight; }

    /** True when the panel cap pulled the request down. Worth saying out loud. */
    public static boolean resolutionWasCapped() {
        return resolutionMode != Config.RES_AUTO && resolutionMode != resolution;
    }

    /**
     * Change resolution and persist it. The UI scale needs no adjustment: it is
     * stored in dp, so the same layout comes out at the new pixel count.
     */
    public static void setResolution(Context c, int mode) {
        applyResolution(mode);
        prefs(c).edit().putInt(KEY_RESOLUTION, resolutionMode).commit();
    }

    /** In-memory half of setResolution(). */
    static void applyResolution(int mode) {
        resolutionMode = mode;

        int chosen = (mode == Config.RES_AUTO)
                ? autoResolution(panelWidth, panelHeight)
                : mode;

        // Never ask the phone for more pixels than the panel can show. Scaling
        // e.g. 1080p down to an 800x480 screen every frame is work the display
        // pipeline on these units cannot absorb -- open-headunit tracked video
        // stalls on MediaTek's MDP to exactly this, so the cap applies to a
        // manual choice too, not just to AUTO.
        int ceiling = autoResolution(panelWidth, panelHeight);
        if (pixelsOf(chosen) > pixelsOf(ceiling)) {
            Logger.i("video: " + name(chosen) + " capped to " + name(ceiling)
                    + " for a " + panelWidth + "x" + panelHeight + " panel");
            chosen = ceiling;
        }

        resolution  = chosen;
        videoWidth  = widthOf(chosen);
        videoHeight = heightOf(chosen);
    }

    /**
     * Largest resolution a panel this size warrants. Mirrors open-headunit's
     * landscape ladder: exactly-480p panels stay at 480p, anything past 720p
     * goes to 1080p, and the broad middle (1024x600 and friends) takes 720p.
     *
     * A zero panel size means we have not measured yet -- assume the smallest.
     */
    static int autoResolution(int w, int h) {
        if (w <= 0 || h <= 0) return ProtocolConstants.RES_800x480;
        if (w <= 800 && h <= 480) return ProtocolConstants.RES_800x480;
        if (w > 1280 || h > 720)  return ProtocolConstants.RES_1920x1080;
        return ProtocolConstants.RES_1280x720;
    }

    public static int widthOf(int res) {
        switch (res) {
            case ProtocolConstants.RES_1920x1080: return 1920;
            case ProtocolConstants.RES_1280x720:  return 1280;
            default:                              return 800;
        }
    }

    public static int heightOf(int res) {
        switch (res) {
            case ProtocolConstants.RES_1920x1080: return 1080;
            case ProtocolConstants.RES_1280x720:  return 720;
            default:                              return 480;
        }
    }

    public static String name(int res) {
        switch (res) {
            case ProtocolConstants.RES_1920x1080: return "1080p";
            case ProtocolConstants.RES_1280x720:  return "720p";
            default:                              return "480p";
        }
    }

    private static int pixelsOf(int res) { return widthOf(res) * heightOf(res); }

    /** The three we offer, in the order the Resolution button cycles them. */
    public static int[] resolutionChoices() {
        return new int[]{
                Config.RES_AUTO,
                ProtocolConstants.RES_800x480,
                ProtocolConstants.RES_1280x720,
                ProtocolConstants.RES_1920x1080,
        };
    }

    /**
     * Panel size in pixels, normalised so width is the long side -- a head unit
     * is landscape even when the ROM reports otherwise, and the resolution
     * ladder is written for landscape.
     */
    private static void measurePanel(Context c) {
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            Point p = new Point();
            if (Build.VERSION.SDK_INT >= 17) {
                // The panel itself. getSize() subtracts the navigation bar,
                // which would talk us out of a resolution the screen can show.
                d.getRealSize(p);
            } else {
                d.getSize(p); // API 13+; the usable area is all API 16 offers
            }
            applyPanelSize(p.x, p.y);
            Logger.i("video: panel measured at " + panelWidth + "x" + panelHeight);
        } catch (Throwable t) {
            // A head unit ROM with a broken WindowManager is not worth dying
            // for; 480p is the safe floor and every panel can show it.
            Logger.w("video: cannot measure panel (" + t + "), assuming 800x480");
            applyPanelSize(800, 480);
        }
    }

    /** In-memory half of measurePanel(). Normalises to landscape. */
    static void applyPanelSize(int w, int h) {
        panelWidth  = Math.max(w, h);
        panelHeight = Math.min(w, h);
    }

    // =====================================================================
    // Density
    // =====================================================================

    /**
     * Density we advertise in the service discovery response.
     *
     * Android lays out in dp and converts with px = dp * dpi/160, so on a fixed
     * stream size a *higher* dpi means fewer dp across the screen, which means
     * AA draws everything larger. That is the knob for "the UI looks cramped",
     * and it is derived rather than stored -- see widthDp.
     */
    public static int videoDpi() { return videoWidth * 160 / widthDp; }

    /** Layout size AA will actually work with. Width is the stored setting. */
    public static int widthDp()  { return widthDp; }
    public static int heightDp() { return widthDp * videoHeight / videoWidth; }

    /**
     * Step the scale. Positive makes AA's UI bigger, which means *fewer* dp --
     * the sign flip lives here so the buttons can read the way they behave.
     */
    public static void stepScale(Context c, int steps) {
        setWidthDp(c, widthDp - steps * Config.WIDTH_DP_STEP);
    }

    /** Persists immediately: a head unit gets its power cut, not shut down. */
    public static void setWidthDp(Context c, int dp) {
        prefs(c).edit().putInt(KEY_WIDTH_DP, applyWidthDp(dp)).commit();
    }

    /** In-memory half of the setter above. Returns the clamped value. */
    static int applyWidthDp(int dp) {
        widthDp = clampWidthDp(dp);
        return widthDp;
    }

    public static int clampWidthDp(int dp) {
        if (dp < Config.MIN_WIDTH_DP) return Config.MIN_WIDTH_DP;
        if (dp > Config.MAX_WIDTH_DP) return Config.MAX_WIDTH_DP;
        return dp;
    }

    /**
     * MediaCodec input buffer size. The 64KB default is smaller than a real
     * keyframe even at 480p; bigger streams need proportionally more, and the
     * codec allocates a handful of these so it is not free.
     */
    public static int maxAccessUnitBytes() {
        return Math.max(Config.VIDEO_MAX_AU_BYTES, videoWidth * videoHeight / 4);
    }
}
