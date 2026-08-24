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

    /** Not a wire value: "work the resolution out from the panel at startup". */
    public static final int RES_AUTO = 0;

    /**
     * Starting resolution: RES_AUTO, or a fixed VideoCodecResolutionType. Only
     * the default -- the Resolution button overrides it at runtime and the
     * choice is kept in SharedPreferences. See {@link Settings}.
     */
    public static final int VIDEO_RESOLUTION = RES_AUTO;
    public static final int VIDEO_FPS        = ProtocolConstants.FPS_30;
    public static final int VIDEO_MARGIN_W   = 0;
    public static final int VIDEO_MARGIN_H   = 0;

    /**
     * Starting UI density in dpi, quoted against an 800-pixel-wide stream.
     * Higher draws AA's UI larger, because the same pixels become fewer dp
     * (px = dp * dpi/160). At 800x480: 120 -> 1066x640 dp, 140 -> 914x548,
     * 160 -> 800x480, 200 -> 640x384.
     *
     * The setting is actually stored as the resulting dp width, which means the
     * same thing at every resolution -- see {@link Settings}. This constant is
     * only the starting point, so it stays in the units you would look up.
     */
    public static final int VIDEO_DPI = 140;

    /** Config.VIDEO_DPI expressed the way Settings holds it. */
    public static int defaultWidthDp() { return 800 * 160 / VIDEO_DPI; }

    /** One press of Smaller/Bigger. Resolution-independent, being in dp. */
    public static final int WIDTH_DP_STEP = 64;

    /**
     * The scale range. Below ~480dp AA runs out of room to lay out in and
     * starts refusing or mislaying things; past ~1280dp everything is tiny.
     */
    public static final int MIN_WIDTH_DP = 480;
    public static final int MAX_WIDTH_DP = 1280;

    /**
     * Extra decoder input latency budget in microseconds. dequeueInputBuffer
     * blocks at most this long before we drop the frame; dropping beats
     * stalling the reader thread (which would also stall audio).
     */
    public static final int VIDEO_INPUT_TIMEOUT_US  = 20000;
    /**
     * The same budget, before the first frame of a stream has come out. What
     * arrives then is the keyframe every later frame references, and a codec
     * that has just started is exactly when input buffers are scarce -- dropping
     * it there costs seconds of black waiting for Android Auto to send another.
     * Worth blocking the reader thread once for.
     */
    public static final int VIDEO_STARTUP_INPUT_TIMEOUT_US = 250000;
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

    /**
     * How many media messages the phone may have in flight before it has to
     * wait for our ack. This was 1, which sounds tidy and is not: it caps the
     * whole stream at one message per round trip, so on any link with real
     * latency the phone cannot hand over a guidance prompt as fast as it is
     * speaking it, and the announcement ends where the audio ran out rather
     * than where the sentence did.
     *
     * Counted in messages, not frames -- a video keyframe is a dozen of them.
     * open-headunit runs 16 over USB and 30 wireless for audio, 12-16 video.
     */
    public static final int AV_MAX_UNACKED = 16;

    /** Microphone the phone records from for voice commands. */
    public static final int MIC_SAMPLE_RATE = 16000;
    public static final boolean ENABLE_MIC  = true;

    /**
     * MediaRecorder.AudioSource for the microphone. VOICE_RECOGNITION is the
     * source the platform tunes for speech recognisers, and unlike
     * VOICE_COMMUNICATION it does not band-limit for telephony.
     *
     * It also does not echo-cancel. If the head unit's speakers sit close
     * enough to the mic that Assistant hears its own prompt and stops
     * listening the moment it starts, switch to VOICE_COMMUNICATION (7), which
     * on most hardware brings the platform AEC with it.
     */
    public static final int MIC_SOURCE = 6; // MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** Attach echo cancellation / noise suppression / AGC where the device has them. */
    public static final boolean MIC_EFFECTS = true;

    /**
     * Slots in each audio ring. 8 x ~4KB is ~32KB per active stream and gives
     * roughly 80ms of slack at 48kHz stereo -- enough to ride out a GC pause
     * without adding audible latency.
     */
    public static final int AUDIO_RING_SLOTS = 8;
    public static final int AUDIO_SLOT_BYTES = 8192;

    /**
     * AudioTrack buffer, in milliseconds of audio. Raise it if the log shows
     * "obtainBuffer timed out"; lower it if audio lags behind video noticeably.
     */
    public static final int AUDIO_BUFFER_MS = 170;

    /**
     * Per-stream trim. Above 1.0 the PCM is amplified in software (clipped at
     * full scale), because AudioTrack volume only ever attenuates.
     *
     * Google's guidance PCM comes off the phone quieter than media does, and
     * how much quieter it ends up depends on the amplifier after us -- so this
     * is a knob, not a constant anyone can pick correctly in advance. Raise
     * SPEECH_GAIN a step at a time until navigation matches music; past ~1.6
     * loud prompts start to clip.
     */
    public static final float MEDIA_GAIN  = 1.0f;
    public static final float SPEECH_GAIN = 1.3f;
    public static final float SYSTEM_GAIN = 1.3f;

    /**
     * How far the media stream drops while a prompt plays, and how long after
     * the last prompt packet it comes back up. The phone will not duck for us:
     * we grant it audio focus unconditionally, which tells it the head unit is
     * mixing.
     */
    public static final float DUCK_FACTOR = 0.35f;
    public static final int DUCK_RELEASE_MS = 1200;

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
