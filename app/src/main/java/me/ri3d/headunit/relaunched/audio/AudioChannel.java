package me.ri3d.headunit.relaunched.audio;

import java.io.IOException;

import me.ri3d.headunit.relaunched.protocol.Messages;
import me.ri3d.headunit.relaunched.protocol.MessageWriter;
import me.ri3d.headunit.relaunched.protocol.Proto;
import me.ri3d.headunit.relaunched.util.Logger;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * One of the three audio streams (media / speech / system). Same message flow
 * as video, but the payload is raw 16-bit PCM instead of H.264.
 *
 * The AudioTrack is created on START_INDICATION and torn down on STOP, so a
 * head unit that only ever plays music never allocates the speech or system
 * tracks at all.
 */
public final class AudioChannel {

    private final int channelId;
    private final String name;
    private final MessageWriter writer;
    private final AudioOutput output;
    private final Proto.R reader = new Proto.R();

    private int session = -1;

    public AudioChannel(int channelId, String name, int sampleRate, int channels,
                        MessageWriter writer) {
        this.channelId = channelId;
        this.name = name;
        this.writer = writer;
        this.output = new AudioOutput(name, sampleRate, channels);
    }

    public void onMessage(int msgId, byte[] buf, int off, int len) throws IOException {
        switch (msgId) {
            case AV_MEDIA_WITH_TIMESTAMP:
                if (len < 8) return;
                // Timestamp is for A/V sync we do not attempt: AudioTrack paces
                // itself off the sample clock, which is what actually matters.
                output.offer(buf, off + 8, len - 8);
                ack();
                break;

            case AV_MEDIA_INDICATION:
                output.offer(buf, off, len);
                ack();
                break;

            case AV_SETUP_REQUEST: {
                Proto.W w = writer.begin();
                Messages.avSetupResponse(w);
                writer.end(channelId, AV_SETUP_RESPONSE);
                Logger.i("audio[" + name + "]: setup ok");
                break;
            }

            case AV_START_INDICATION:
                session = (int) Messages.varintField(reader, buf, off, len, 1, 0);
                Logger.i("audio[" + name + "]: start, session " + session);
                output.start();
                break;

            case AV_STOP_INDICATION:
                Logger.i("audio[" + name + "]: stop");
                output.stop();
                break;

            default:
                Logger.d("audio[" + name + "]: unhandled msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    private void ack() throws IOException {
        Proto.W w = writer.begin();
        Messages.avMediaAck(w, session);
        writer.end(channelId, AV_MEDIA_ACK);
    }

    public void release() {
        output.stop();
    }
}
