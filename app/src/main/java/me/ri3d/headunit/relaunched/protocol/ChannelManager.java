package me.ri3d.headunit.relaunched.protocol;

import android.view.Surface;

import java.io.IOException;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.audio.AudioChannel;
import me.ri3d.headunit.relaunched.audio.MicChannel;
import me.ri3d.headunit.relaunched.input.InputChannel;
import me.ri3d.headunit.relaunched.sensor.SensorChannel;
import me.ri3d.headunit.relaunched.util.Logger;
import me.ri3d.headunit.relaunched.video.VideoChannel;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Owns the non-control channels and routes messages to them. Keeping this apart
 * from AndroidAutoSession means the session only deals with the handshake and
 * the control channel, and adding a channel touches one switch.
 */
public final class ChannelManager {

    public final VideoChannel video;
    public final AudioChannel mediaAudio;
    public final AudioChannel speechAudio;
    public final AudioChannel systemAudio;
    public final MicChannel mic;
    public final InputChannel input;
    public final SensorChannel sensor;

    public ChannelManager(MessageWriter writer) {
        video       = new VideoChannel(writer);
        mediaAudio  = new AudioChannel(CH_MEDIA_AUDIO,  "media",
                Config.MEDIA_SAMPLE_RATE,  Config.MEDIA_CHANNELS,
                false, Config.MEDIA_GAIN,  writer);
        speechAudio = new AudioChannel(CH_SPEECH_AUDIO, "speech",
                Config.SPEECH_SAMPLE_RATE, Config.SPEECH_CHANNELS,
                true,  Config.SPEECH_GAIN, writer);
        systemAudio = new AudioChannel(CH_SYSTEM_AUDIO, "system",
                Config.SYSTEM_SAMPLE_RATE, Config.SYSTEM_CHANNELS,
                true,  Config.SYSTEM_GAIN, writer);
        // Which of the two carries Google Maps depends on the AA build, so
        // both duck. Neither ever plays while the other does.
        speechAudio.duckWhilePlaying(mediaAudio.output());
        systemAudio.duckWhilePlaying(mediaAudio.output());
        mic         = new MicChannel(writer);
        input       = new InputChannel(writer);
        sensor      = new SensorChannel(writer);
    }

    public void setSurface(Surface s) {
        video.setSurface(s);
    }

    public void onMessage(int channel, int msgId, byte[] buf, int off, int len) throws IOException {
        // Every non-control message, before dispatch. Media data is frequent, so
        // this is gated on the log level -- but when the phone goes quiet it is
        // the difference between "it sent nothing" and "we ignored it".
        if (Logger.isDebug() && msgId != AV_MEDIA_WITH_TIMESTAMP && msgId != AV_MEDIA_INDICATION) {
            Logger.d("rx ch=" + channel + " msg=0x" + Integer.toHexString(msgId) + " len=" + len);
        }

        switch (channel) {
            case CH_VIDEO:        video.onMessage(msgId, buf, off, len); break;
            case CH_MEDIA_AUDIO:  mediaAudio.onMessage(msgId, buf, off, len); break;
            case CH_SPEECH_AUDIO: speechAudio.onMessage(msgId, buf, off, len); break;
            case CH_SYSTEM_AUDIO: systemAudio.onMessage(msgId, buf, off, len); break;
            case CH_MIC:          mic.onMessage(msgId, buf, off, len); break;
            case CH_INPUT:        input.onMessage(msgId, buf, off, len); break;
            case CH_SENSOR:       sensor.onMessage(msgId, buf, off, len); break;
            default:
                Logger.w("no handler for channel " + channel + " msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    public void release() {
        video.release();
        mediaAudio.release();
        speechAudio.release();
        systemAudio.release();
        mic.release();
    }
}
