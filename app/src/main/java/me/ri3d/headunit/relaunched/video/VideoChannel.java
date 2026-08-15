package me.ri3d.headunit.relaunched.video;

import android.view.Surface;

import java.io.IOException;

import me.ri3d.headunit.relaunched.Settings;
import me.ri3d.headunit.relaunched.protocol.Messages;
import me.ri3d.headunit.relaunched.protocol.MessageWriter;
import me.ri3d.headunit.relaunched.protocol.Proto;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.util.Utils;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Video channel: negotiates the stream, then pumps H.264 access units into the
 * decoder and acknowledges each one.
 *
 * The phone will not send a second frame until the previous one is acked
 * (max_unacked = 1 in our setup response), so a missed ack looks exactly like a
 * frozen picture.
 */
public final class VideoChannel {

    /** Told when the phone takes the screen or hands it back. */
    public interface FocusListener {
        void onVideoFocus(boolean projected);
    }

    private final MessageWriter writer;
    private final VideoDecoder decoder = new VideoDecoder();
    private final Proto.R reader = new Proto.R();

    private Surface surface;
    private int session = -1;
    private boolean started;
    private FocusListener focusListener;

    public VideoChannel(MessageWriter writer) {
        this.writer = writer;
    }

    public void setFocusListener(FocusListener l) { focusListener = l; }

    /**
     * Called from the UI thread when the SurfaceView appears or disappears.
     * Passing null tears the decoder down; on API < 23 there is no
     * setOutputSurface, so a new Surface means a new codec instance.
     */
    public void setSurface(Surface s) {
        surface = s;
        if (s == null) {
            decoder.stop();
        } else if (started) {
            decoder.start(s, Settings.videoWidth(), Settings.videoHeight());
        }
    }

    public void onMessage(int msgId, byte[] buf, int off, int len) throws IOException {
        switch (msgId) {
            case AV_MEDIA_WITH_TIMESTAMP: {
                // u64 microsecond timestamp, then the access unit
                if (len < 8) return;
                long pts = Utils.u64(buf, off);
                decoder.feed(buf, off + 8, len - 8, pts);
                ack();
                break;
            }
            case AV_MEDIA_INDICATION: {
                decoder.feed(buf, off, len, 0);
                ack();
                break;
            }
            case AV_SETUP_REQUEST: {
                int configIndex = (int) Messages.varintField(reader, buf, off, len, 1, 0);
                Logger.i("video: setup request, config " + configIndex);
                Proto.W w = writer.begin();
                Messages.avSetupResponse(w);
                writer.end(CH_VIDEO, AV_SETUP_RESPONSE);
                // Claim the screen straight away; without this the phone keeps
                // the stream parked and nothing is ever sent.
                sendFocus(VIDEO_FOCUS_PROJECTED, true);
                break;
            }
            case AV_START_INDICATION: {
                session = (int) Messages.varintField(reader, buf, off, len, 1, 0);
                Logger.i("video: start, session " + session);
                started = true;
                if (surface != null) {
                    decoder.start(surface, Settings.videoWidth(), Settings.videoHeight());
                }
                break;
            }
            case AV_STOP_INDICATION: {
                Logger.i("video: stop");
                started = false;
                decoder.stop();
                break;
            }
            case AV_VIDEO_FOCUS_REQUEST: {
                int mode = (int) Messages.varintField(reader, buf, off, len, 2, VIDEO_FOCUS_PROJECTED);
                Logger.i("video: focus request mode=" + mode
                        + (mode == VIDEO_FOCUS_NATIVE ? " (NATIVE -- screen handed back)" : ""));
                sendFocus(mode, false);
                // NATIVE is how Android Auto's exit-to-car button leaves the
                // session up but stops drawing. Granting it and saying nothing
                // else left the last decoded frame frozen on screen forever.
                if (focusListener != null) {
                    focusListener.onVideoFocus(mode != VIDEO_FOCUS_NATIVE);
                }
                break;
            }
            default:
                Logger.d("video: unhandled msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    private void ack() throws IOException {
        Proto.W w = writer.begin();
        Messages.avMediaAck(w, session);
        writer.end(CH_VIDEO, AV_MEDIA_ACK);
    }

    /**
     * Tell the phone we own the screen, without being asked.
     *
     * A head unit always has the display, and the phone waits to be told so
     * before it starts projecting -- it does not probe for it. Sending this once
     * the video channel is open is what actually triggers the setup/start
     * exchange; without it the phone opens every channel and then sits idle.
     */
    public void claimFocus() throws IOException {
        Logger.i("video: claiming video focus (projected)");
        sendFocus(VIDEO_FOCUS_PROJECTED, true);
    }

    private void sendFocus(int mode, boolean unrequested) throws IOException {
        Proto.W w = writer.begin();
        Messages.videoFocusIndication(w, mode, unrequested);
        writer.end(CH_VIDEO, AV_VIDEO_FOCUS_IND);
    }

    public void release() {
        started = false;
        decoder.stop();
    }
}
