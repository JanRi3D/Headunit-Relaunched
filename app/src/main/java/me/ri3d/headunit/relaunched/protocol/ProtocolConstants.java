package me.ri3d.headunit.relaunched.protocol;

/**
 * Wire constants for the Android Auto (AA) head-unit protocol.
 *
 * Values follow aasdk / OpenAuto. Frame layout on the wire:
 *
 *   u8  channelId
 *   u8  flags            (FIRST|LAST|CONTROL|ENCRYPTED)
 *   u16 payloadLength    big endian
 *  [u32 totalMessageSize] only present when FIRST is set and LAST is not
 *   u8[payloadLength] payload
 *
 * After decryption, a *complete* message payload starts with a big-endian u16
 * message id, followed by protobuf (or raw bytes for version + SSL handshake).
 */
public final class ProtocolConstants {

    private ProtocolConstants() {}

    // ---- frame flags -------------------------------------------------------
    public static final int FRAME_FIRST     = 1 << 0;
    public static final int FRAME_LAST      = 1 << 1;
    /** FIRST|LAST: a whole message in one frame. */
    public static final int FRAME_BULK      = FRAME_FIRST | FRAME_LAST;
    public static final int FRAME_CONTROL   = 1 << 2;
    public static final int FRAME_ENCRYPTED = 1 << 3;

    /** aasdk fragments at 0x4000; the phone does the same. */
    public static final int MAX_FRAME_PAYLOAD = 0x4000;

    /**
     * Highest control-channel message id. FRAME_CONTROL marks a message whose id
     * belongs to this control range but which is being delivered on a *media*
     * channel -- in practice CHANNEL_OPEN_RESPONSE, sent on the channel being
     * opened rather than on channel 0.
     *
     * Get this wrong and there is no error anywhere: the phone requests every
     * channel, silently discards the responses, and the session sits idle
     * forever after service discovery.
     */
    public static final int MAX_CONTROL_MSG_ID = 26;

    /** True when a frame carrying msgId on this channel must set FRAME_CONTROL. */
    public static boolean needsControlFlag(int channel, int msgId) {
        return channel != CH_CONTROL && msgId >= 1 && msgId <= MAX_CONTROL_MSG_ID;
    }

    // ---- channel ids -------------------------------------------------------
    // We pick these ourselves and announce them in ServiceDiscoveryResponse.
    public static final int CH_CONTROL      = 0;
    public static final int CH_INPUT        = 1;
    public static final int CH_SENSOR       = 2;
    public static final int CH_VIDEO        = 3;
    public static final int CH_MEDIA_AUDIO  = 4;
    public static final int CH_SPEECH_AUDIO = 5;
    public static final int CH_SYSTEM_AUDIO = 6;
    public static final int CH_MIC          = 7;   // AV input (microphone)
    public static final int CH_COUNT        = 8;

    // ---- control channel message ids --------------------------------------
    public static final int MSG_VERSION_REQUEST            = 0x0001;
    public static final int MSG_VERSION_RESPONSE           = 0x0002;
    public static final int MSG_SSL_HANDSHAKE              = 0x0003;
    public static final int MSG_AUTH_COMPLETE              = 0x0004;
    public static final int MSG_SERVICE_DISCOVERY_REQUEST  = 0x0005;
    public static final int MSG_SERVICE_DISCOVERY_RESPONSE = 0x0006;
    public static final int MSG_CHANNEL_OPEN_REQUEST       = 0x0007;
    public static final int MSG_CHANNEL_OPEN_RESPONSE      = 0x0008;
    public static final int MSG_PING_REQUEST               = 0x000B;
    public static final int MSG_PING_RESPONSE              = 0x000C;
    public static final int MSG_NAV_FOCUS_REQUEST          = 0x000D;
    public static final int MSG_NAV_FOCUS_RESPONSE         = 0x000E;
    public static final int MSG_SHUTDOWN_REQUEST           = 0x000F;
    public static final int MSG_SHUTDOWN_RESPONSE          = 0x0010;
    public static final int MSG_VOICE_SESSION_REQUEST      = 0x0011;
    public static final int MSG_AUDIO_FOCUS_REQUEST        = 0x0012;
    public static final int MSG_AUDIO_FOCUS_RESPONSE       = 0x0013;

    // ---- A/V channel message ids ------------------------------------------
    public static final int AV_MEDIA_WITH_TIMESTAMP = 0x0000; // u64 ts + payload
    public static final int AV_MEDIA_INDICATION     = 0x0001; // payload only
    public static final int AV_SETUP_REQUEST        = 0x8000;
    public static final int AV_START_INDICATION     = 0x8001;
    public static final int AV_STOP_INDICATION      = 0x8002;
    public static final int AV_SETUP_RESPONSE       = 0x8003;
    public static final int AV_MEDIA_ACK            = 0x8004;
    public static final int AV_MIC_REQUEST          = 0x8005;
    public static final int AV_MIC_RESPONSE         = 0x8006;
    public static final int AV_VIDEO_FOCUS_REQUEST  = 0x8007;
    public static final int AV_VIDEO_FOCUS_IND      = 0x8008;

    // ---- input channel message ids ----------------------------------------
    public static final int IN_EVENT_INDICATION = 0x8001;
    public static final int IN_BINDING_REQUEST  = 0x8002;
    public static final int IN_BINDING_RESPONSE = 0x8003;

    // ---- sensor channel message ids ---------------------------------------
    public static final int SENSOR_START_REQUEST  = 0x8001;
    public static final int SENSOR_START_RESPONSE = 0x8002;
    public static final int SENSOR_EVENT          = 0x8003;

    // ---- enums -------------------------------------------------------------
    public static final int STATUS_OK = 0;

    /**
     * MediaCodecType -- the "available_type" of a media sink/source. Audio PCM
     * and video H.264 happen to share the numbers aasdk uses for its
     * AVStreamType, which is why this looked like a stream type for years.
     */
    public static final int CODEC_AUDIO_PCM      = 1;
    public static final int CODEC_AUDIO_AAC_LC   = 2;
    public static final int CODEC_VIDEO_H264_BP  = 3;

    /** AudioStreamType. A video sink must declare NONE, not a real audio type. */
    public static final int AUDIO_TYPE_NONE   = 0;
    public static final int AUDIO_TYPE_SPEECH = 1;
    public static final int AUDIO_TYPE_SYSTEM = 2;
    public static final int AUDIO_TYPE_MEDIA  = 3;

    /**
     * VideoCodecResolutionType, whole. Only the first three are offered: 4 and 5
     * want HEVC in practice, and 6-9 are for portrait panels, which a head unit
     * is not. They are listed anyway because a wire enum is easier to trust when
     * you can see all of it.
     */
    public static final int RES_800x480   = 1;
    public static final int RES_1280x720  = 2;
    public static final int RES_1920x1080 = 3;
    public static final int RES_2560x1440 = 4;
    public static final int RES_3840x2160 = 5;
    public static final int RES_720x1280  = 6;
    public static final int RES_1080x1920 = 7;
    public static final int RES_1440x2560 = 8;
    public static final int RES_2160x3840 = 9;

    /**
     * VideoFrameRateType. Note the ordering: 60 comes first, 30 second. Getting
     * these the intuitive way round silently advertises 60fps.
     */
    public static final int FPS_60 = 1;
    public static final int FPS_30 = 2;

    /** DriverPosition -- an enum, not a left-hand-drive boolean. */
    public static final int DRIVER_POSITION_LEFT  = 0;
    public static final int DRIVER_POSITION_RIGHT = 1;

    /** MessageStatus, the shared status enum. Negative values are all failures. */
    public static final int STATUS_INTERNAL_ERROR = -7;

    /** AVChannelSetupStatus */
    public static final int SETUP_STATUS_OK = 2;

    public static final int VIDEO_FOCUS_PROJECTED = 1;
    public static final int VIDEO_FOCUS_NATIVE    = 2;

    /** AudioFocusStateType -- what we answer an audio focus request with. */
    public static final int AUDIO_FOCUS_STATE_GAIN           = 1;
    public static final int AUDIO_FOCUS_STATE_GAIN_TRANSIENT = 2;
    public static final int AUDIO_FOCUS_STATE_LOSS           = 3;
    public static final int AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY = 7;

    /** AudioFocusType -- what the phone asks for. */
    public static final int AUDIO_FOCUS_REQ_GAIN                    = 1;
    public static final int AUDIO_FOCUS_REQ_GAIN_TRANSIENT          = 2;
    public static final int AUDIO_FOCUS_REQ_GAIN_TRANSIENT_MAY_DUCK = 3;
    public static final int AUDIO_FOCUS_REQ_RELEASE                 = 4;

    /**
     * The state to answer an AudioFocusRequestNotification with.
     *
     * It has to be the state matching what was asked for, not merely "yes".
     * Assistant asks for GAIN_TRANSIENT and Google Maps guidance asks for
     * GAIN_TRANSIENT_MAY_DUCK; answering either with a permanent STATE_GAIN
     * reads on the phone as its transient request having been superseded, and
     * it winds the session down again -- "OK Google" opens and closes, an
     * announcement stops mid-sentence. Granting is not the point; granting the
     * same thing that was requested is.
     */
    public static int audioFocusState(int requestType) {
        switch (requestType) {
            case AUDIO_FOCUS_REQ_GAIN_TRANSIENT:
                return AUDIO_FOCUS_STATE_GAIN_TRANSIENT;
            case AUDIO_FOCUS_REQ_GAIN_TRANSIENT_MAY_DUCK:
                return AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY;
            case AUDIO_FOCUS_REQ_RELEASE:
                return AUDIO_FOCUS_STATE_LOSS;
            default:
                return AUDIO_FOCUS_STATE_GAIN;
        }
    }

    /** SensorType */
    public static final int SENSOR_NIGHT_DATA     = 10;
    public static final int SENSOR_DRIVING_STATUS = 13;

    /** TouchAction */
    public static final int TOUCH_PRESS   = 0;
    public static final int TOUCH_RELEASE = 1;
    public static final int TOUCH_DRAG    = 2;

    // ---- ButtonCode (subset that matters for a head unit) -----------------
    public static final int BTN_HOME        = 3;
    public static final int BTN_BACK        = 4;
    public static final int BTN_CALL        = 5;
    public static final int BTN_END_CALL    = 6;
    public static final int BTN_UP          = 19;
    public static final int BTN_DOWN        = 20;
    public static final int BTN_LEFT        = 21;
    public static final int BTN_RIGHT       = 22;
    public static final int BTN_ENTER       = 23;
    public static final int BTN_MICROPHONE  = 84;
    public static final int BTN_PLAY_PAUSE  = 85;
    public static final int BTN_STOP        = 86;
    public static final int BTN_NEXT        = 87;
    public static final int BTN_PREV        = 88;
    public static final int BTN_SCROLL_WHEEL = 65536;
}
