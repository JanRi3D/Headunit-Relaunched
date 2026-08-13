package me.ri3d.headunit.relaunched;

import me.ri3d.headunit.relaunched.protocol.ProtocolConstants;

/**
 * Every tunable in one place. Edit here, not in the channel classes.
 *
 * On weak hardware the two knobs that matter most are VIDEO_RESOLUTION and
 * VIDEO_FPS: 800x480@30 is roughly a quarter of the decode+scanout cost of
 * 1280x720@60 and is what most single-DIN units can actually sustain.
 */
public final class Config {

    private Config() {}

    // ---- identity shown on the phone --------------------------------------
    public static final String HEAD_UNIT_NAME  = "Headunit Relaunched";
    public static final String CAR_MODEL       = "Universal";
    public static final String CAR_YEAR        = "2020";
    public static final String CAR_SERIAL      = "HU-0000001";
    public static final String HU_MANUFACTURER = "ri3d";
    public static final String HU_MODEL        = "Relaunched";
    public static final String SW_BUILD        = "1";
    public static final String SW_VERSION      = "1.0";
    public static final boolean LEFT_HAND_DRIVE = true;

    // ---- video -------------------------------------------------------------
    public static final int VIDEO_RESOLUTION = ProtocolConstants.RES_800x480;
    public static final int VIDEO_FPS        = ProtocolConstants.FPS_30;
    public static final int VIDEO_WIDTH      = 800;
    public static final int VIDEO_HEIGHT     = 480;
    public static final int VIDEO_DPI        = 140;
    public static final int VIDEO_MARGIN_W   = 0;
    public static final int VIDEO_MARGIN_H   = 0;

    /**
     * Extra decoder input latency budget in microseconds. dequeueInputBuffer
     * blocks at most this long before we drop the frame; dropping beats
     * stalling the reader thread (which would also stall audio).
     */
    public static final int VIDEO_INPUT_TIMEOUT_US  = 20000;
    public static final int VIDEO_OUTPUT_TIMEOUT_US = 20000;

    /**
     * Largest access unit we can hand the decoder, i.e. MediaCodec's
     * KEY_MAX_INPUT_SIZE. The default is 64KB, which is smaller than a real
     * 800x480 keyframe (66-80KB observed), so keyframes got dropped and the
     * picture froze between them. 256KB leaves headroom at 720p too; the codec
     * allocates a handful of these, so this costs ~1-2MB.
     */
    public static final int VIDEO_MAX_AU_BYTES = 256 * 1024;

    // ---- audio -------------------------------------------------------------
    public static final int MEDIA_SAMPLE_RATE  = 48000;
    public static final int MEDIA_CHANNELS     = 2;
    public static final int SPEECH_SAMPLE_RATE = 16000;
    public static final int SPEECH_CHANNELS    = 1;
    public static final int SYSTEM_SAMPLE_RATE = 16000;
    public static final int SYSTEM_CHANNELS    = 1;

    /** Microphone the phone records from for voice commands. */
    public static final int MIC_SAMPLE_RATE = 16000;
    public static final boolean ENABLE_MIC  = true;

    /**
     * Slots in each audio ring. 8 x ~4KB is ~32KB per active stream and gives
     * roughly 80ms of slack at 48kHz stereo -- enough to ride out a GC pause
     * without adding audible latency.
     */
    public static final int AUDIO_RING_SLOTS = 8;
    public static final int AUDIO_SLOT_BYTES = 8192;

    /**
     * AudioTrack buffer, in slot-sized units. 4 slots is ~170ms at 48kHz stereo.
     * Raise it if the log shows "obtainBuffer timed out"; lower it if audio lags
     * behind video noticeably.
     */
    public static final int AUDIO_BUFFER_SLOTS = 4;

    // ---- transport ---------------------------------------------------------
    /**
     * Port we listen on when the phone dials us -- the normal wireless AA flow,
     * where the Bluetooth handshake tells the phone where to go. Fixed by Google.
     */
    public static final int WIFI_PORT = 5288;

    /**
     * Port the *phone* listens on when you enable "Start head unit server" in
     * Android Auto's developer settings. That is the listener the Desktop Head
     * Unit normally reaches over an ADB forward; over Wi-Fi we can just dial it
     * directly, which is what the manual-IP flow does. No Bluetooth involved.
     */
    public static final int HEADUNIT_SERVER_PORT = 5277;

    /** How long to wait for a manual-IP TCP connect before giving up and retrying. */
    public static final int WIFI_CONNECT_TIMEOUT_MS = 5000;

    /**
     * Credentials of the AP the phone must join. Bring up the hotspot yourself
     * (Settings, or your ROM's tethering API) and mirror it here -- reading the
     * live softAP config needs reflection that breaks on every other ROM, and a
     * head unit's hotspot never changes anyway.
     */
    public static final String WIFI_SSID     = "HeadunitAP";
    public static final String WIFI_PASSWORD = "headunit1234";
    public static final String WIFI_BSSID    = "00:00:00:00:00:00";
    /** SecurityMode: 8 = WPA2_PERSONAL, 1 = OPEN. */
    public static final int WIFI_SECURITY_MODE = 8;

    /** USB bulk read timeout, ms. Also the shutdown responsiveness bound. */
    public static final int USB_READ_TIMEOUT_MS  = 1000;
    public static final int USB_WRITE_TIMEOUT_MS = 2000;

    // ---- protocol ----------------------------------------------------------
    public static final int PROTOCOL_MAJOR = 1;
    public static final int PROTOCOL_MINOR = 1;
}
