package me.ri3d.headunit.relaunched.protocol;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.Settings;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Builders and parsers for the AA protobuf messages this head unit uses.
 *
 * Field numbers come from aasdk's .proto files. Builders write into a caller
 * supplied Proto.W so the hot paths (media ack, touch events) allocate nothing.
 */
public final class Messages {

    private Messages() {}

    // =====================================================================
    // Control channel
    // =====================================================================

    /** AuthCompleteIndication { status = OK }. */
    public static void authComplete(Proto.W w) {
        w.u32(1, STATUS_OK);
    }

    /** ChannelOpenResponse { status }. */
    public static void channelOpenResponse(Proto.W w, int status) {
        w.u32(1, status);
    }

    /** PingResponse { timestamp }. */
    public static void pingResponse(Proto.W w, long timestampNanos) {
        w.i64(1, timestampNanos);
    }

    /** AudioFocusResponse { audio_focus_state }. */
    public static void audioFocusResponse(Proto.W w, int state) {
        w.u32(1, state);
    }

    /** NavigationFocusResponse { focus_type }. */
    public static void navFocusResponse(Proto.W w, int type) {
        w.u32(1, type);
    }

    /** ShutdownResponse -- empty message, but it must still be sent. */
    public static void shutdownResponse(Proto.W w) {
        // no fields
    }

    /**
     * ServiceDiscoveryResponse: everything the phone needs to decide what this
     * head unit can do. If a channel is missing here the phone never opens it.
     */
    public static void serviceDiscoveryResponse(Proto.W w) {
        // --- input channel ---
        int ch = w.begin(1);
        {
            w.u32(1, CH_INPUT);
            int svc = w.begin(4); // input_source_service
            {
                // keycodes_supported (repeated uint32, written unpacked)
                int[] keys = {
                        BTN_HOME, BTN_BACK, BTN_CALL, BTN_END_CALL,
                        BTN_UP, BTN_DOWN, BTN_LEFT, BTN_RIGHT, BTN_ENTER,
                        BTN_MICROPHONE, BTN_PLAY_PAUSE, BTN_STOP, BTN_NEXT, BTN_PREV,
                        BTN_SCROLL_WHEEL
                };
                for (int i = 0; i < keys.length; i++) w.u32(1, keys[i]);

                int touch = w.begin(2); // touch_screen_config
                {
                    // Must match the video stream size, not the panel: touch
                    // coordinates are sent in stream pixels.
                    w.u32(1, Settings.videoWidth());
                    w.u32(2, Settings.videoHeight());
                }
                w.end(touch);
            }
            w.end(svc);
        }
        w.end(ch);

        // --- sensor channel ---
        ch = w.begin(1);
        {
            w.u32(1, CH_SENSOR);
            int svc = w.begin(2); // sensor_source_service
            {
                int s = w.begin(1); w.u32(1, SENSOR_DRIVING_STATUS); w.end(s);
                s = w.begin(1);     w.u32(1, SENSOR_NIGHT_DATA);     w.end(s);
            }
            w.end(svc);
        }
        w.end(ch);

        // --- video channel ---
        ch = w.begin(1);
        {
            w.u32(1, CH_VIDEO);
            int av = w.begin(3); // media_sink_service
            {
                w.u32(1, CODEC_VIDEO_H264_BP); // available_type
                // A video sink declares NO audio stream type. Claiming MEDIA
                // here makes the phone treat channel 3 as an audio sink and it
                // never asks to set video up.
                w.u32(2, AUDIO_TYPE_NONE);
                int vc = w.begin(4);           // video_configs
                {
                    w.u32(1, Settings.videoResolution()); // codec_resolution
                    w.u32(2, Config.VIDEO_FPS);        // frame_rate
                    w.u32(3, Config.VIDEO_MARGIN_W);
                    w.u32(4, Config.VIDEO_MARGIN_H);
                    // Runtime-adjustable, so read it here rather than at class
                    // init: this is the one place the phone ever asks for it.
                    w.u32(5, Settings.videoDpi());     // density
                    w.u32(10, CODEC_VIDEO_H264_BP);    // video_codec_type
                }
                w.end(vc);
                w.bool(5, true); // available_while_in_call
            }
            w.end(av);
        }
        w.end(ch);

        // --- audio channels ---
        audioChannel(w, CH_MEDIA_AUDIO,  AUDIO_TYPE_MEDIA,
                Config.MEDIA_SAMPLE_RATE,  Config.MEDIA_CHANNELS);
        audioChannel(w, CH_SPEECH_AUDIO, AUDIO_TYPE_SPEECH,
                Config.SPEECH_SAMPLE_RATE, Config.SPEECH_CHANNELS);
        audioChannel(w, CH_SYSTEM_AUDIO, AUDIO_TYPE_SYSTEM,
                Config.SYSTEM_SAMPLE_RATE, Config.SYSTEM_CHANNELS);

        // --- microphone (AV input) ---
        if (Config.ENABLE_MIC) {
            ch = w.begin(1);
            {
                w.u32(1, CH_MIC);
                int avin = w.begin(5); // media_source_service
                {
                    w.u32(1, CODEC_AUDIO_PCM); // type
                    int ac = w.begin(2);       // audio_config
                    {
                        w.u32(1, Config.MIC_SAMPLE_RATE);
                        w.u32(2, 16); // bit depth
                        w.u32(3, 1);  // mono
                    }
                    w.end(ac);
                    w.bool(3, true); // available_while_in_call
                }
                w.end(avin);
            }
            w.end(ch);
        }

        // --- head unit identity ---
        w.str(2, Config.HEAD_UNIT_NAME);   // make
        w.str(3, Config.CAR_MODEL);        // model
        w.str(4, Config.CAR_YEAR);         // year
        w.str(5, Config.CAR_SERIAL);       // vehicle_id
        // driver_position is a DriverPosition enum, not a boolean.
        w.u32(6, Config.LEFT_HAND_DRIVE ? DRIVER_POSITION_LEFT : DRIVER_POSITION_RIGHT);
        w.str(7, Config.HU_MANUFACTURER);  // head_unit_make
        w.str(8, Config.HU_MODEL);         // head_unit_model
        w.str(9, Config.SW_BUILD);
        w.str(10, Config.SW_VERSION);
        w.bool(11, false); // can_play_native_media_during_vr
        w.bool(12, false); // hide_projected_clock
    }

    private static void audioChannel(Proto.W w, int channelId, int audioType,
                                     int sampleRate, int channels) {
        int ch = w.begin(1);
        {
            w.u32(1, channelId);
            int av = w.begin(3); // media_sink_service
            {
                w.u32(1, CODEC_AUDIO_PCM); // available_type
                w.u32(2, audioType);
                int ac = w.begin(3); // audio_configs
                {
                    w.u32(1, sampleRate);
                    w.u32(2, 16);
                    w.u32(3, channels);
                }
                w.end(ac);
                w.bool(5, true);
            }
            w.end(av);
        }
        w.end(ch);
    }

    // =====================================================================
    // A/V channels
    // =====================================================================

    /** AVChannelSetupResponse { media_status, max_unacked, configs }. */
    public static void avSetupResponse(Proto.W w) {
        w.u32(1, SETUP_STATUS_OK);
        w.u32(2, 1);  // max_unacked -- we ack every frame
        w.u32(3, 0);  // configs: index 0, the only one we advertised
    }

    /** AVMediaAckIndication { session, value }. Sent for every media frame. */
    public static void avMediaAck(Proto.W w, int session) {
        w.i32(1, session);
        w.u32(2, 1);
    }

    /** VideoFocusIndication { focus_mode, unrequested }. */
    public static void videoFocusIndication(Proto.W w, int mode, boolean unrequested) {
        w.u32(1, mode);
        w.bool(2, unrequested);
    }

    /** AVInputOpenResponse { session, value } -- reply to a microphone request. */
    public static void micResponse(Proto.W w, int session, boolean open) {
        w.i32(1, session);
        w.u32(2, open ? 0 : 1);
    }

    // =====================================================================
    // Input channel
    // =====================================================================

    /** BindingResponse { status }. */
    public static void bindingResponse(Proto.W w, int status) {
        w.u32(1, status);
    }

    /** InputEventIndication { timestamp, touch_event { pointer_data, touch_action } }. */
    public static void touchEvent(Proto.W w, long timestampNanos, int action, int x, int y) {
        w.i64(1, timestampNanos);
        int te = w.begin(3); // touch_event
        {
            int p = w.begin(1); // pointer_data
            {
                w.u32(1, x);
                w.u32(2, y);
                w.u32(3, 0); // pointer_id -- single touch only
            }
            w.end(p);
            w.u32(2, 0);      // action_index
            w.u32(3, action); // touch_action
        }
        w.end(te);
    }

    /** InputEventIndication { timestamp, button_event { data { ... } } }. */
    public static void buttonEvent(Proto.W w, long timestampNanos, int scanCode, boolean pressed) {
        w.i64(1, timestampNanos);
        int be = w.begin(4); // button_event
        {
            int d = w.begin(1); // repeated data
            {
                w.u32(1, scanCode);
                w.bool(2, pressed);
                w.i32(3, 0);      // meta
                w.bool(4, false); // long_press
            }
            w.end(d);
        }
        w.end(be);
    }

    /** InputEventIndication { timestamp, relative_event { data { delta, ... } } } for a rotary encoder. */
    public static void scrollEvent(Proto.W w, long timestampNanos, int delta) {
        w.i64(1, timestampNanos);
        int re = w.begin(6); // relative_event
        {
            int d = w.begin(1);
            {
                w.i32(1, delta);
                w.i32(2, 0);
            }
            w.end(d);
        }
        w.end(re);
    }

    // =====================================================================
    // Sensor channel
    // =====================================================================

    /** SensorStartResponse { status }. */
    public static void sensorStartResponse(Proto.W w, int status) {
        w.u32(1, status);
    }

    /**
     * SensorEventIndication { driving_status { status } }.
     * status 0 means "unrestricted" -- with anything else the phone locks the
     * UI down as if the car were moving.
     */
    public static void drivingStatus(Proto.W w, int status) {
        int d = w.begin(13);
        w.u32(1, status);
        w.end(d);
    }

    /** SensorEventIndication { night_mode { is_night } }. */
    public static void nightMode(Proto.W w, boolean isNight) {
        int d = w.begin(10);
        w.bool(1, isNight);
        w.end(d);
    }

    // =====================================================================
    // Parsing helpers
    // =====================================================================

    /**
     * Reads the first varint field with the given number. Most requests we care
     * about carry exactly one interesting scalar, so this covers them all
     * without a class per message.
     */
    public static long varintField(Proto.R r, byte[] buf, int off, int len, int field, long def) {
        r.set(buf, off, len);
        while (r.next()) {
            if (r.field == field && r.wire == Proto.WIRE_VARINT) return r.varint();
            r.skip();
        }
        return def;
    }

    /** Reads the first string field with the given number, or null. */
    public static String stringField(Proto.R r, byte[] buf, int off, int len, int field) {
        r.set(buf, off, len);
        while (r.next()) {
            if (r.field == field && r.wire == Proto.WIRE_BYTES) return r.str();
            r.skip();
        }
        return null;
    }
}
