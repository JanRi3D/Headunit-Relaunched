package me.ri3d.headunit.relaunched.audio;

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
 * tracks at all. The prompt streams are the exception -- see AV_STOP_INDICATION.
 */
public final class AudioChannel {

    private final int channelId;
    private final String name;
    private final boolean speech;
    private final MessageWriter writer;
    private final AudioOutput output;
    private final Proto.R reader = new Proto.R();

    /** Sink to pull down while this channel is talking. Null on the media channel itself. */
    private AudioOutput duckTarget;

    private int session = -1;

    public AudioChannel(int channelId, String name, int sampleRate, int channels,
                        boolean speech, float gain, MessageWriter writer) {
        this.channelId = channelId;
        this.name = name;
        this.speech = speech;
        this.writer = writer;
        this.output = new AudioOutput(name, sampleRate, channels, speech, gain);
    }

    public AudioOutput output() { return output; }

    /** Duck {@code media} for as long as this channel keeps feeding PCM. */
    public void duckWhilePlaying(AudioOutput media) {
        this.duckTarget = media;
    }

    public void onMessage(int msgId, byte[] buf, int off, int len) {
        switch (msgId) {
            case AV_MEDIA_WITH_TIMESTAMP:
                if (len < 8) return;
                // Timestamp is for A/V sync we do not attempt: AudioTrack paces
                // itself off the sample clock, which is what actually matters.
                output.offer(buf, off + 8, len - 8);
                if (duckTarget != null) duckTarget.duck();
                ack();
                break;

            case AV_MEDIA_INDICATION:
                output.offer(buf, off, len);
                if (duckTarget != null) duckTarget.duck();
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
                // Prompt streams stop and start again for every single sentence.
                // Tearing the track down each time throws away whatever of the
                // sentence is still buffered (stop() flushes) and makes the next
                // one start cold, so the tail and the first syllable both go
                // missing. Keep them until the session ends -- an idle
                // AudioTrack costs a buffer and nothing else. open-headunit
                // does the same thing whenever it holds static audio focus.
                if (!speech) output.stop();
                break;

            default:
                Logger.d("audio[" + name + "]: unhandled msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    private void ack() {
        Proto.W w = writer.begin();
        Messages.avMediaAck(w, session);
        writer.end(channelId, AV_MEDIA_ACK);
    }

    public void release() {
        output.stop();
    }
}
