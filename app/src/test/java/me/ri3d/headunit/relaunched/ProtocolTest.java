package me.ri3d.headunit.relaunched;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;

import me.ri3d.headunit.relaunched.protocol.MessageParser;
import me.ri3d.headunit.relaunched.protocol.MessageWriter;
import me.ri3d.headunit.relaunched.protocol.Messages;
import me.ri3d.headunit.relaunched.protocol.Proto;
import me.ri3d.headunit.relaunched.transport.Transport;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the two pieces that are genuinely easy to get wrong and impossible to
 * debug on a head unit: the hand-rolled protobuf codec (especially the
 * back-patched nested length) and frame fragmentation / reassembly.
 *
 * Run with: gradlew :app:testDebugUnitTest
 */
public class ProtocolTest {

    // =====================================================================
    // protobuf codec
    // =====================================================================

    @Test
    public void scalarRoundTrip() {
        Proto.W w = new Proto.W(64);
        w.u32(1, 4242);
        w.i64(2, -1234567890123L);
        w.bool(3, true);
        w.str(4, "Headunit");
        w.i32(5, -7);

        Proto.R r = new Proto.R().set(w.buf, 0, w.pos);
        int seen = 0;
        while (r.next()) {
            switch (r.field) {
                case 1: assertEquals(4242, (int) r.varint()); seen++; break;
                case 2: assertEquals(-1234567890123L, r.varint()); seen++; break;
                case 3: assertTrue(r.bool()); seen++; break;
                case 4: assertEquals("Headunit", r.str()); seen++; break;
                case 5: assertEquals(-7, r.int32()); seen++; break;
                default: r.skip();
            }
        }
        assertEquals(5, seen);
    }

    /**
     * begin()/end() writes a deliberately non-minimal 2-byte length varint. If
     * that back-patch is ever wrong, every nested message silently desyncs --
     * which on a real head unit shows up as "the phone ignores us".
     */
    @Test
    public void nestedMessageLengthIsBackPatched() {
        Proto.W w = new Proto.W(64);
        w.u32(1, 9);
        int h = w.begin(2);
        w.u32(1, 800);
        w.u32(2, 480);
        w.end(h);
        w.u32(3, 5);

        Proto.R r = new Proto.R().set(w.buf, 0, w.pos);
        boolean sawNested = false;
        int after = -1;
        while (r.next()) {
            if (r.field == 2 && r.wire == Proto.WIRE_BYTES) {
                Proto.R sub = r.nested(new Proto.R());
                assertTrue(sub.next());
                assertEquals(800, (int) sub.varint());
                assertTrue(sub.next());
                assertEquals(480, (int) sub.varint());
                assertTrue("nested reader overran", !sub.next());
                sawNested = true;
            } else if (r.field == 3) {
                after = (int) r.varint();
            } else {
                r.skip();
            }
        }
        assertTrue(sawNested);
        assertEquals("field after the nested message was lost", 5, after);
    }

    /**
     * A nested message longer than 127 bytes is the only case that exercises the
     * *second* byte of the back-patched length varint. Everything we build today
     * happens to be shorter, so without this the high byte is never checked.
     */
    @Test
    public void nestedMessageOver127BytesRoundTrips() {
        Proto.W w = new Proto.W(64);
        int h = w.begin(1);
        for (int i = 0; i < 100; i++) w.u32(1, 0xFFFF); // 3 bytes each => 300 bytes
        w.end(h);
        w.u32(2, 0x2A);

        Proto.R r = new Proto.R().set(w.buf, 0, w.pos);
        assertTrue(r.next());
        assertEquals(1, r.field);
        Proto.R sub = r.nested(new Proto.R());
        int count = 0;
        while (sub.next()) { assertEquals(0xFFFF, (int) sub.varint()); count++; }
        assertEquals(100, count);

        assertTrue("trailing field lost: nested length high byte is wrong", r.next());
        assertEquals(2, r.field);
        assertEquals(0x2A, (int) r.varint());
    }

    /** The real ServiceDiscoveryResponse must survive a parse with all channels intact. */
    @Test
    public void serviceDiscoveryResponseParsesBack() {
        Proto.W w = new Proto.W(1024);
        Messages.serviceDiscoveryResponse(w);

        ArrayList<Integer> channelIds = new ArrayList<Integer>();
        String headUnitName = null;
        final boolean[] sawDriverPosition = new boolean[1];

        StringBuilder seen = new StringBuilder();
        Proto.R r = new Proto.R().set(w.buf, 0, w.pos);
        while (r.next()) {
            seen.append(r.field).append('/').append(r.wire).append(' ');
            if (r.field == 1 && r.wire == Proto.WIRE_BYTES) {
                Proto.R ch = r.nested(new Proto.R());
                int id = -1;
                while (ch.next()) {
                    if (ch.field == 1 && ch.wire == Proto.WIRE_VARINT) {
                        id = (int) ch.varint();
                        channelIds.add(id);
                    } else if (ch.field == 3 && ch.wire == Proto.WIRE_BYTES && id == CH_VIDEO) {
                        checkVideoSink(ch.nested(new Proto.R()));
                    } else {
                        ch.skip();
                    }
                }
            } else if (r.field == 2 && r.wire == Proto.WIRE_BYTES) {
                headUnitName = r.str();
            } else if (r.field == 6 && r.wire == Proto.WIRE_VARINT) {
                sawDriverPosition[0] = true;
                assertEquals("left-hand drive is DriverPosition 0",
                        DRIVER_POSITION_LEFT, (int) r.varint());
            } else {
                r.skip();
            }
        }

        assertEquals(Config.HEAD_UNIT_NAME, headUnitName);
        assertTrue("driver_position missing; top-level fields seen = " + seen,
                sawDriverPosition[0]);
        assertTrue("input channel missing", channelIds.contains(CH_INPUT));
        assertTrue("sensor channel missing", channelIds.contains(CH_SENSOR));
        assertTrue("video channel missing", channelIds.contains(CH_VIDEO));
        assertTrue("media audio channel missing", channelIds.contains(CH_MEDIA_AUDIO));
        assertTrue("speech audio channel missing", channelIds.contains(CH_SPEECH_AUDIO));
        assertTrue("system audio channel missing", channelIds.contains(CH_SYSTEM_AUDIO));
    }

    /**
     * The video sink descriptor decides whether the phone ever asks to set video
     * up. Every value here was wrong at some point and the only symptom was the
     * phone opening all channels and then going silent, so they are pinned.
     */
    private static void checkVideoSink(Proto.R sink) {
        int availableType = -1, audioType = -1, fps = -1, resolution = -1;
        while (sink.next()) {
            if (sink.field == 1 && sink.wire == Proto.WIRE_VARINT) {
                availableType = (int) sink.varint();
            } else if (sink.field == 2 && sink.wire == Proto.WIRE_VARINT) {
                audioType = (int) sink.varint();
            } else if (sink.field == 4 && sink.wire == Proto.WIRE_BYTES) {
                Proto.R cfg = sink.nested(new Proto.R());
                while (cfg.next()) {
                    if (cfg.field == 1 && cfg.wire == Proto.WIRE_VARINT) resolution = (int) cfg.varint();
                    else if (cfg.field == 2 && cfg.wire == Proto.WIRE_VARINT) fps = (int) cfg.varint();
                    else cfg.skip();
                }
            } else {
                sink.skip();
            }
        }
        assertEquals("video sink must advertise H.264", CODEC_VIDEO_H264_BP, availableType);
        assertEquals("a video sink declares no audio stream type", AUDIO_TYPE_NONE, audioType);
        assertEquals("800x480", RES_800x480, resolution);
        // The enum is _60=1, _30=2. If this ever reads 1 while Config says 30,
        // someone "fixed" the ordering back to the intuitive one.
        assertEquals("Config asks for 30fps, which is enum value 2", FPS_30, fps);
        assertEquals(2, FPS_30);
    }

    /**
     * Density must be read from Settings, not from Config. Both hold 140 by
     * default, so a regression to the compile-time constant would look fine in
     * every other test while making the Smaller/Bigger buttons do nothing.
     */
    @Test
    public void videoDensityFollowsSettings() {
        int saved = Settings.widthDp();
        try {
            Settings.applyWidthDp(640);
            assertEquals(800 * 160 / 640, densityInDiscoveryResponse());
            Settings.applyWidthDp(1066);
            assertEquals(800 * 160 / 1066, densityInDiscoveryResponse());
            // And the range is enforced on the way in, not at the buttons.
            Settings.applyWidthDp(Config.MAX_WIDTH_DP + 500);
            assertEquals(800 * 160 / Config.MAX_WIDTH_DP, densityInDiscoveryResponse());
        } finally {
            Settings.applyWidthDp(saved);
        }
    }

    /**
     * The scale is stored in dp, so the same setting has to survive a resolution
     * change. Storing dpi instead was a real bug: auto-detection moving 800 ->
     * 1280 left the old number in place and AA came out half size.
     */
    @Test
    public void scaleSurvivesResolutionChange() {
        int savedMode = Settings.resolutionMode(), savedDp = Settings.widthDp();
        int pw = Settings.panelWidth(), ph = Settings.panelHeight();
        try {
            Settings.applyPanelSize(1920, 1080); // a panel that permits anything

            Settings.applyResolution(RES_800x480);
            Settings.applyWidthDp(Config.defaultWidthDp());
            assertEquals(Config.VIDEO_DPI, Settings.videoDpi());
            int layoutAt480p = Settings.widthDp();

            Settings.applyResolution(RES_1920x1080);
            assertEquals(1920, Settings.videoWidth());
            assertEquals(1080, Settings.videoHeight());
            assertEquals("the layout AA sees must not change with resolution",
                    layoutAt480p, Settings.widthDp());
            assertEquals("so the density has to scale with the stream instead",
                    Config.VIDEO_DPI * 1920 / 800, Settings.videoDpi());
            assertEquals(Config.VIDEO_DPI * 1920 / 800, densityInDiscoveryResponse());
        } finally {
            Settings.applyPanelSize(pw, ph);
            Settings.applyResolution(savedMode);
            Settings.applyWidthDp(savedDp);
        }
    }

    /**
     * Asking for more pixels than the panel can show makes the display scaler
     * downscale every frame, which is what stalls video on MediaTek units. The
     * cap applies to a manual choice too, not just to AUTO.
     */
    @Test
    public void resolutionIsCappedToThePanel() {
        assertEquals(RES_800x480, Settings.autoResolution(800, 480));
        assertEquals(RES_1280x720, Settings.autoResolution(1024, 600));
        assertEquals(RES_1280x720, Settings.autoResolution(1280, 720));
        assertEquals(RES_1920x1080, Settings.autoResolution(1920, 1080));
        // Unmeasured panel: assume the floor rather than overshoot.
        assertEquals(RES_800x480, Settings.autoResolution(0, 0));

        int savedMode = Settings.resolutionMode(), savedDp = Settings.widthDp();
        int pw = Settings.panelWidth(), ph = Settings.panelHeight();
        try {
            Settings.applyPanelSize(800, 480);
            Settings.applyResolution(RES_1920x1080);
            assertEquals("manual 1080p must come back down to the panel",
                    RES_800x480, Settings.videoResolution());
            assertEquals("but the request itself is remembered",
                    RES_1920x1080, Settings.resolutionMode());
            assertTrue("and the cap has to be visible to the UI",
                    Settings.resolutionWasCapped());
            assertEquals(RES_800x480, resolutionInDiscoveryResponse());

            // A panel that can take it is left alone.
            Settings.applyPanelSize(1920, 1080);
            Settings.applyResolution(RES_1920x1080);
            assertEquals(RES_1920x1080, Settings.videoResolution());
            assertFalse(Settings.resolutionWasCapped());
            assertEquals(RES_1920x1080, resolutionInDiscoveryResponse());
            assertEquals(1920, touchScreenWidthInDiscoveryResponse());
        } finally {
            Settings.applyPanelSize(pw, ph);
            Settings.applyResolution(savedMode);
            Settings.applyWidthDp(savedDp);
        }
    }

    private static int densityInDiscoveryResponse() { return videoConfig(5); }
    private static int resolutionInDiscoveryResponse() { return videoConfig(1); }

    /**
     * video channel -> media_sink_service (3) -> video_configs (4) -> field.
     * 1 = codec_resolution, 2 = frame_rate, 5 = density.
     */
    private static int videoConfig(int field) {
        return channelSubField(CH_VIDEO, 3, 4, field);
    }

    /** input channel -> input_source_service (4) -> touch_screen_config (2). */
    private static int touchScreenWidthInDiscoveryResponse() {
        return channelSubField(CH_INPUT, 4, 2, 1);
    }

    /**
     * Digs one varint out of the discovery response, three levels down:
     * channel[channelId].service.config.field.
     */
    private static int channelSubField(int channelId, int service, int config, int field) {
        Proto.W w = new Proto.W(1024);
        Messages.serviceDiscoveryResponse(w);

        Proto.R r = new Proto.R().set(w.buf, 0, w.pos);
        while (r.next()) {
            if (r.field != 1 || r.wire != Proto.WIRE_BYTES) { r.skip(); continue; }
            Proto.R ch = r.nested(new Proto.R());
            int id = -1;
            while (ch.next()) {
                if (ch.field == 1 && ch.wire == Proto.WIRE_VARINT) {
                    id = (int) ch.varint();
                } else if (ch.field == service && ch.wire == Proto.WIRE_BYTES && id == channelId) {
                    Proto.R svc = ch.nested(new Proto.R());
                    while (svc.next()) {
                        if (svc.field != config || svc.wire != Proto.WIRE_BYTES) { svc.skip(); continue; }
                        Proto.R cfg = svc.nested(new Proto.R());
                        while (cfg.next()) {
                            if (cfg.field == field && cfg.wire == Proto.WIRE_VARINT) {
                                return (int) cfg.varint();
                            }
                            cfg.skip();
                        }
                    }
                } else {
                    ch.skip();
                }
            }
        }
        return -1; // field not present at all
    }

    // =====================================================================
    // framing
    // =====================================================================

    /** In-memory pipe so MessageWriter output can be fed straight back to MessageParser. */
    private static final class Loop implements Transport {
        byte[] buf = new byte[1 << 20];
        int end, pos;

        @Override public boolean connect() { return true; }
        @Override public String name() { return "loop"; }
        @Override public void close() {}

        @Override public int read(byte[] b, int off, int len) {
            if (pos >= end) return -1;
            int n = Math.min(len, end - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override public void write(byte[] b, int off, int len) {
            System.arraycopy(b, off, buf, end, len);
            end += len;
        }
    }

    private static final class Captured {
        int channel, msgId;
        byte[] payload;
    }

    private Captured roundTrip(int channel, int msgId, byte[] body) throws IOException {
        Loop loop = new Loop();
        MessageWriter writer = new MessageWriter(loop);
        writer.send(channel, msgId, body, 0, body.length);

        final Captured got = new Captured();
        MessageParser parser = new MessageParser(loop);
        // Keep pumping until the pipe is drained: a fragmented message spans
        // several frames and only the last one dispatches.
        while (parser.pump(new MessageParser.Sink() {
            @Override public void onMessage(int ch, int id, byte[] b, int off, int len) {
                got.channel = ch;
                got.msgId = id;
                got.payload = new byte[len];
                System.arraycopy(b, off, got.payload, 0, len);
            }
        })) { /* drain */ }
        return got;
    }

    @Test
    public void singleFrameMessageRoundTrips() throws Exception {
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;

        Captured got = roundTrip(CH_CONTROL, MSG_PING_REQUEST, body);
        assertEquals(CH_CONTROL, got.channel);
        assertEquals(MSG_PING_REQUEST, got.msgId);
        assertArrayEquals(body, got.payload);
    }

    /**
     * A keyframe is comfortably larger than MAX_FRAME_PAYLOAD, so this is the
     * path every single video I-frame takes.
     */
    @Test
    public void fragmentedMessageReassembles() throws Exception {
        byte[] body = new byte[MAX_FRAME_PAYLOAD * 2 + 1234];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31);

        Captured got = roundTrip(CH_VIDEO, AV_MEDIA_INDICATION, body);
        assertEquals(CH_VIDEO, got.channel);
        assertEquals(AV_MEDIA_INDICATION, got.msgId);
        assertEquals(body.length, got.payload.length);
        assertArrayEquals(body, got.payload);
    }

    /** Exactly one byte over the fragmentation threshold: the classic off-by-one. */
    @Test
    public void messageOneByteOverFrameLimitReassembles() throws Exception {
        byte[] body = new byte[MAX_FRAME_PAYLOAD - 2 + 1]; // +2 msg id header = limit + 1
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i ^ 0x5A);

        Captured got = roundTrip(CH_MEDIA_AUDIO, AV_MEDIA_INDICATION, body);
        assertArrayEquals(body, got.payload);
    }
}
