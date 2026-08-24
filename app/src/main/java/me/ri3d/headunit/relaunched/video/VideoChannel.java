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

    // volatile: the UI thread writes `surface` from the SurfaceView callbacks
    // while the session thread writes `started` from AV_START_INDICATION, and
    // each side reads the other's field to decide whether to start the decoder.
    // Without this a surface that comes back at the wrong moment is simply not
    // noticed, and the panel stays black with a perfectly healthy session.
    private volatile Surface surface;
    /** -1 until AV_START_INDICATION; the video channel is live from then on. */
    private volatile int session = -1;
    private volatile boolean started;
    /**
     * Set when the *phone* asks for NATIVE, i.e. Android Auto's exit-to-car
     * button. The screen is the user's then, not ours to take back, and only an
     * explicit claimFocus() clears it. Deliberately not set by our own NATIVE
     * indication below -- that one is temporary and we do reclaim it.
     */
    private volatile boolean handedBack;
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
        if (s != null) {
            refresh();
            return;
        }
        decoder.stop();
        // Our screen went away: a reverse or turn-signal camera in front of us.
        // That is precisely what NATIVE focus means, and saying it is what makes
        // taking the screen back an actual transition. An indication that only
        // repeats the state Android Auto is already in changes nothing over
        // there -- no restart, no keyframe -- and we sat black through every one.
        if (handedBack || session < 0) return;
        try {
            Logger.i("video: screen taken by our own UI, releasing focus");
            sendFocus(VIDEO_FOCUS_NATIVE, true);
        } catch (IOException e) {
            Logger.w("video: cannot release focus: " + e);
        }
    }

    /**
     * The screen came back to us -- from a reverse or turn-signal camera, or any
     * other app that was in front of us for a moment.
     *
     * Android Auto sends a keyframe when it is *told* it has the screen, not on
     * a timer, and everything between two keyframes is undecodable on its own.
     * So whatever we do to the codec here, the picture only returns once we ask:
     * without this it is black until AA happens to send its next one, which is
     * the "it comes back eventually" you can sit through at a junction.
     *
     * Restarting the codec is conditional because the Surface often survives the
     * interruption intact, and a needless restart costs its own black flicker.
     * A codec that died while we were in the background does need one.
     */
    public void refresh() {
        Surface s = surface;
        // Nothing to draw on yet: surfaceCreated() runs after onResume() and
        // comes back through here, so the ask is not lost, only deferred.
        if (s == null || session < 0) return;
        // Not ours to ask for: after AA's exit-to-car button the phone handed
        // the screen back on purpose, and claiming it would drag AA over the
        // user's own UI.
        if (handedBack) return;

        if (started && !decoder.isRunning()) {
            Logger.i("video: decoder gone, restarting it");
            decoder.start(s, Settings.videoWidth(), Settings.videoHeight());
        }
        try {
            claimFocus();
        } catch (IOException e) {
            Logger.w("video: cannot ask for a keyframe: " + e);
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
                // The user leaving Android Auto, as opposed to us stepping
                // aside for a camera. Only this stops us reclaiming the screen.
                handedBack = (mode == VIDEO_FOCUS_NATIVE);
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
        handedBack = false;
        sendFocus(VIDEO_FOCUS_PROJECTED, true);
    }

    private void sendFocus(int mode, boolean unrequested) throws IOException {
        Proto.W w = writer.begin();
        Messages.videoFocusIndication(w, mode, unrequested);
        writer.end(CH_VIDEO, AV_VIDEO_FOCUS_IND);
    }

    public void release() {
        started = false;
        session = -1;
        decoder.stop();
    }
}
