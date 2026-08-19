package me.ri3d.headunit.relaunched.protocol;

import android.view.Surface;

import java.io.IOException;

import me.ri3d.headunit.relaunched.transport.Transport;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.video.VideoChannel;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * One Android Auto session over one Transport.
 *
 * Everything runs on this single reader thread: frames in, control replies out,
 * channel data handed to the decoders. The only work that leaves it is the
 * actual decoding (MediaCodec's own thread) and PCM playback (one thread per
 * active audio stream), because both of those block.
 *
 * Lifecycle: construct, start(), and the session runs until the phone
 * disconnects or stop() is called. Sessions are not reusable -- make a new one.
 */
public final class AndroidAutoSession implements MessageParser.Sink, Runnable {

    /**
     * End reason for a shutdown the phone asked for -- Android Auto's own exit
     * button. The service matches on it to decide not to reconnect, so it is a
     * constant rather than a message typed twice.
     */
    public static final String REASON_SHUTDOWN = "closed by Android Auto";

    public interface Listener {
        void onSessionState(String state);
        void onSessionEnded(String reason);
        /** The phone started or stopped drawing. False means our UI has the screen. */
        void onVideoFocus(boolean projected);
    }

    private final Transport transport;
    private final Ssl ssl;
    private final MessageParser parser;
    private final MessageWriter writer;
    private final HandshakeManager handshake;
    private final ChannelManager channels;
    private final Listener listener;

    private final Proto.R reader = new Proto.R();

    private Thread thread;
    private volatile boolean running;
    private volatile Surface pendingSurface;

    /** Overrides the reason run() would otherwise report. Set on a clean exit. */
    private volatile String endReason;

    public AndroidAutoSession(Transport transport, Ssl ssl, Listener listener) {
        this.transport = transport;
        this.ssl = ssl;
        this.listener = listener;
        this.parser = new MessageParser(transport);
        this.writer = new MessageWriter(transport);
        this.handshake = new HandshakeManager(writer, ssl);
        this.channels = new ChannelManager(writer);
        this.channels.video.setFocusListener(new VideoChannel.FocusListener() {
            @Override public void onVideoFocus(boolean projected) {
                if (AndroidAutoSession.this.listener != null) {
                    AndroidAutoSession.this.listener.onVideoFocus(projected);
                }
            }
        });
    }

    public ChannelManager channels() { return channels; }

    /** Safe to call before or after the session starts. */
    public void setSurface(Surface s) {
        pendingSurface = s;
        channels.setSurface(s);
    }

    public void start() {
        running = true;
        thread = new Thread(this, "hu-session");
        thread.start();
    }

    public void stop() {
        running = false;
        parser.stop();
        transport.close();   // unblocks a read in progress
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
        channels.release();
        ssl.close();
    }

    @Override
    public void run() {
        String reason = "ended";
        try {
            // Name the transport: "Wi-Fi" (listening) and "Wi-Fi 192.168.1.42"
            // (dialling) are very different things to be waiting on, and the
            // difference is otherwise invisible from the UI.
            state("connecting over " + transport.name());
            if (!transport.connect()) {
                reason = "no device";
                return;
            }
            state("handshake");
            handshake.sendVersionRequest();

            while (running) {
                if (!parser.pump(this)) {
                    reason = "phone disconnected";
                    break;
                }
            }
        } catch (IOException e) {
            reason = e.getMessage() == null ? e.toString() : e.getMessage();
            Logger.w("session: " + reason);
        } catch (Throwable e) {
            reason = "internal error: " + e;
            Logger.e("session: unexpected failure", e);
        } finally {
            running = false;
            // A clean shutdown beats whatever the read loop reported on its way
            // out: closing the transport underneath ourselves usually surfaces
            // as an IOException a moment later.
            if (endReason != null) reason = endReason;
            channels.release();
            transport.close();
            Logger.i("session: ended (" + reason + ")");
            if (listener != null) listener.onSessionEnded(reason);
        }
    }

    // =====================================================================
    // MessageParser.Sink
    // =====================================================================

    @Override
    public void onMessage(int channel, int msgId, byte[] buf, int off, int len) throws IOException {
        if (channel == CH_CONTROL) {
            onControl(msgId, buf, off, len);
            return;
        }
        // CHANNEL_OPEN_REQUEST arrives on the channel being opened, not on the
        // control channel, so it has to be caught before per-channel dispatch --
        // otherwise no channel ever opens and the phone just sits there.
        if (msgId == MSG_CHANNEL_OPEN_REQUEST) {
            openChannel(channel);
            return;
        }
        channels.onMessage(channel, msgId, buf, off, len);
    }

    private void openChannel(int channel) throws IOException {
        Logger.i("control: open channel " + channel);
        Proto.W w = writer.begin();
        Messages.channelOpenResponse(w, STATUS_OK);
        writer.end(channel, MSG_CHANNEL_OPEN_RESPONSE);

        // The phone will not set up video until it believes the car is safe to
        // project onto, and it never asks us for that: the driving status has to
        // follow the sensor channel's open response unprompted. Without it the
        // phone opens every channel and then goes silent forever.
        if (channel == CH_SENSOR) {
            channels.sensor.sendDrivingStatus(0); // 0 = unrestricted
            Logger.i("sensor: sent initial driving status (unrestricted)");
        } else if (channel == CH_VIDEO) {
            channels.video.claimFocus();
        }
    }

    private void onControl(int msgId, byte[] buf, int off, int len) throws IOException {
        switch (msgId) {
            case MSG_VERSION_RESPONSE:
                if (!handshake.onVersionResponse(buf, off, len)) {
                    throw new IOException("protocol version rejected");
                }
                state("TLS handshake");
                handshake.startSsl();
                break;

            case MSG_SSL_HANDSHAKE:
                if (handshake.onSslHandshake(buf, off, len)) {
                    handshake.sendAuthComplete();
                    // Order matters: both directions must switch to encrypted
                    // only after AUTH_COMPLETE has gone out in the clear.
                    writer.enableEncryption(ssl);
                    parser.setSsl(ssl);
                    state("authenticated");
                }
                break;

            case MSG_SERVICE_DISCOVERY_REQUEST: {
                String device = Messages.stringField(reader, buf, off, len, 4);
                Logger.i("control: service discovery from " + device);
                Proto.W w = writer.begin();
                Messages.serviceDiscoveryResponse(w);
                writer.end(CH_CONTROL, MSG_SERVICE_DISCOVERY_RESPONSE);
                state("negotiating channels");
                break;
            }

            case MSG_CHANNEL_OPEN_REQUEST: {
                // Fallback path: some stacks send this on channel 0 with the
                // target in service_id instead of on the channel itself.
                int serviceId = (int) Messages.varintField(reader, buf, off, len, 2, -1);
                openChannel(serviceId >= 0 ? serviceId : CH_CONTROL);
                break;
            }

            case MSG_PING_REQUEST: {
                long ts = Messages.varintField(reader, buf, off, len, 1, 0);
                Proto.W w = writer.begin();
                Messages.pingResponse(w, ts);
                writer.end(CH_CONTROL, MSG_PING_RESPONSE);
                break;
            }

            case MSG_AUDIO_FOCUS_REQUEST: {
                int type = (int) Messages.varintField(reader, buf, off, len, 1, 0);
                // We have no other audio sources competing, so the phone always
                // gets what it asks for -- but it has to be told it got the
                // thing it asked for. See audioFocusState().
                int grantedState = audioFocusState(type);
                Proto.W w = writer.begin();
                Messages.audioFocusResponse(w, grantedState);
                writer.end(CH_CONTROL, MSG_AUDIO_FOCUS_RESPONSE);
                Logger.i("control: audio focus " + type + " -> " + grantedState);
                break;
            }

            case MSG_NAV_FOCUS_REQUEST: {
                int type = (int) Messages.varintField(reader, buf, off, len, 1, 1);
                Proto.W w = writer.begin();
                Messages.navFocusResponse(w, type);
                writer.end(CH_CONTROL, MSG_NAV_FOCUS_RESPONSE);
                break;
            }

            case MSG_SHUTDOWN_REQUEST: {
                // This is Android Auto's exit button. ShutdownReason 1 = QUIT.
                long why = Messages.varintField(reader, buf, off, len, 1, 0);
                Logger.i("control: phone requested shutdown, reason " + why);
                Proto.W w = writer.begin();
                Messages.shutdownResponse(w);
                writer.end(CH_CONTROL, MSG_SHUTDOWN_RESPONSE);
                // Say why, so the service does not helpfully reconnect three
                // seconds later and put Android Auto straight back on screen.
                endReason = REASON_SHUTDOWN;
                running = false;
                break;
            }

            case MSG_SHUTDOWN_RESPONSE:
                endReason = REASON_SHUTDOWN;
                running = false;
                break;

            case MSG_VOICE_SESSION_REQUEST:
                // Informational: the phone is telling us voice input started or
                // stopped. The mic channel does the actual work.
                break;

            default:
                Logger.d("control: unhandled msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    private void state(String s) {
        Logger.i("session: " + s);
        if (listener != null) listener.onSessionState(s);
    }
}
