package me.ri3d.headunit.relaunched.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.IOException;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.protocol.Messages;
import me.ri3d.headunit.relaunched.protocol.MessageWriter;
import me.ri3d.headunit.relaunched.protocol.Proto;
import me.ri3d.headunit.relaunched.util.Logger;

import static me.ri3d.headunit.relaunched.protocol.ProtocolConstants.*;

/**
 * Microphone channel (AV input). Without it "OK Google" and every voice command
 * silently does nothing, which is why it is here despite costing a thread.
 *
 * The thread only exists while the phone has the mic open -- it is created on
 * the open request and torn down on close.
 */
public final class MicChannel {

    /** ~64ms of 16kHz mono audio per message. Small enough to keep latency sane. */
    private static final int CHUNK_BYTES = 2048;

    private final MessageWriter writer;
    private final Proto.R reader = new Proto.R();

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private int session = -1;

    public MicChannel(MessageWriter writer) {
        this.writer = writer;
    }

    public void onMessage(int msgId, byte[] buf, int off, int len) throws IOException {
        switch (msgId) {
            case AV_SETUP_REQUEST: {
                Proto.W w = writer.begin();
                Messages.avSetupResponse(w);
                writer.end(CH_MIC, AV_SETUP_RESPONSE);
                Logger.i("mic: setup ok");
                break;
            }

            case AV_MIC_REQUEST: {
                boolean open = Messages.varintField(reader, buf, off, len, 1, 0) != 0;
                Logger.i("mic: " + (open ? "open" : "close") + " request");
                if (open) startCapture(); else stopCapture();

                Proto.W w = writer.begin();
                Messages.micResponse(w, session, open);
                writer.end(CH_MIC, AV_MIC_RESPONSE);
                break;
            }

            case AV_START_INDICATION:
                session = (int) Messages.varintField(reader, buf, off, len, 1, 0);
                Logger.i("mic: start, session " + session);
                break;

            case AV_STOP_INDICATION:
                stopCapture();
                break;

            case AV_MEDIA_ACK:
                break; // the phone acking our audio; nothing to do

            default:
                Logger.d("mic: unhandled msg 0x" + Integer.toHexString(msgId));
                break;
        }
    }

    private synchronized void startCapture() {
        if (running) return;
        int min = AudioRecord.getMinBufferSize(Config.MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { Logger.e("mic: unsupported capture format"); return; }

        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, Config.MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, CHUNK_BYTES * 4));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Logger.e("mic: AudioRecord init failed (RECORD_AUDIO permission?)");
                record.release();
                record = null;
                return;
            }
            record.startRecording();
        } catch (Throwable e) {
            Logger.e("mic: cannot open", e);
            record = null;
            return;
        }

        running = true;
        thread = new Thread(new Capture(), "hu-mic");
        thread.start();
    }

    private synchronized void stopCapture() {
        if (!running) return;
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
            try { r.release(); } catch (Throwable ignored) {}
        }
        Logger.i("mic: capture stopped");
    }

    private final class Capture implements Runnable {
        @Override public void run() {
            // 8 byte timestamp header + PCM, built in place so each chunk is a
            // single write with no intermediate copy.
            byte[] frame = new byte[8 + CHUNK_BYTES];
            while (running) {
                AudioRecord r = record;
                if (r == null) break;
                int n = r.read(frame, 8, CHUNK_BYTES);
                if (n <= 0) {
                    if (n < 0) Logger.w("mic: read error " + n);
                    continue;
                }
                long ts = System.nanoTime() / 1000L;
                for (int i = 0; i < 8; i++) frame[i] = (byte) (ts >>> (56 - 8 * i));
                try {
                    writer.send(CH_MIC, AV_MEDIA_WITH_TIMESTAMP, frame, 0, 8 + n);
                } catch (IOException e) {
                    Logger.w("mic: send failed, stopping");
                    break;
                }
            }
        }
    }

    public void release() {
        stopCapture();
    }
}
