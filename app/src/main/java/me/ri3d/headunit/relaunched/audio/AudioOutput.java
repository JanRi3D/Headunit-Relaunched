package me.ri3d.headunit.relaunched.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.util.Logger;

/**
 * One PCM sink: an AudioTrack plus a fixed ring of reusable buffers and one
 * thread that drains it.
 *
 * The ring exists because AudioTrack.write() blocks when the track is full.
 * Writing straight from the session reader thread would work, but the moment
 * audio backed up it would also stall video, since both arrive on that thread.
 *
 * Nothing is allocated after start(): the slots are recycled forever.
 */
public final class AudioOutput {

    private final String name;
    private final int sampleRate;
    private final int channels;
    /** Prompt stream (guidance / notifications) rather than music. */
    private final boolean speech;
    /** Trim, applied in software above 1.0 because AudioTrack maxes out there. */
    private final float gain;

    private final byte[][] slots;
    private final int[] slotLen;
    /** One slot is always held back so the consumer's in-flight buffer cannot be recycled under it. */
    private final int capacity;

    private int head, count;
    private final Object lock = new Object();

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private int drops;

    /** Ducking state. Only ever used on the media sink, driven by the prompt channels. */
    private Handler duckHandler;
    private boolean ducked;
    private final Runnable unduck = new Runnable() {
        @Override public void run() {
            synchronized (lock) {
                if (!ducked) return;
                ducked = false;
                applyVolume();
            }
        }
    };

    public AudioOutput(String name, int sampleRate, int channels, boolean speech, float gain) {
        this.name = name;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.speech = speech;
        this.gain = gain;
        this.slots = new byte[Config.AUDIO_RING_SLOTS][Config.AUDIO_SLOT_BYTES];
        this.slotLen = new int[Config.AUDIO_RING_SLOTS];
        this.capacity = Config.AUDIO_RING_SLOTS - 1;
    }

    public boolean isRunning() { return running; }

    public void start() {
        synchronized (lock) {
            if (running) return;
            int chanCfg = (channels == 1)
                    ? AudioFormat.CHANNEL_OUT_MONO
                    : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(sampleRate, chanCfg, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                Logger.e("audio[" + name + "]: unsupported format " + sampleRate + "/" + channels);
                return;
            }
            // Headroom for a scheduling hiccup without adding audible latency.
            // Too small and AudioFlinger logs "obtainBuffer timed out (is the
            // CPU pegged?)" and the audio breaks up on slow hardware.
            //
            // Sized in milliseconds, not bytes: a byte count tuned against
            // 48kHz stereo is six times as long at 16kHz mono, which put a
            // whole second of buffer on the one stream -- guidance -- where
            // lag is least tolerable.
            int bufSize = Math.max(min, sampleRate * channels * 2 * Config.AUDIO_BUFFER_MS / 1000);
            try {
                track = newTrack(chanCfg, bufSize);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    Logger.e("audio[" + name + "]: AudioTrack init failed");
                    track.release();
                    track = null;
                    return;
                }
                ducked = false;
                applyVolume();
                track.play();
            } catch (Throwable e) {
                Logger.e("audio[" + name + "]: AudioTrack failed", e);
                return;
            }

            head = 0; count = 0; drops = 0;
            running = true;
            thread = new Thread(new Pump(), "hu-audio-" + name);
            thread.setPriority(Thread.NORM_PRIORITY + 2);
            thread.start();
            Logger.i("audio[" + name + "]: started " + sampleRate + "Hz x" + channels
                    + " buf=" + bufSize + " gain=" + gain);
        }
    }

    /**
     * Tell the system what the stream is for. Guidance announced as
     * CONTENT_TYPE_MUSIC gets whatever loudness curve the head unit voices
     * music with, which is a good part of why navigation prompts come out
     * duller than a media app on the same speakers.
     *
     * The legacy stream stays MUSIC on purpose: USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
     * maps there anyway, and pinning it keeps prompts on the volume knob the
     * user actually turns. open-headunit puts speech on STREAM_VOICE_CALL
     * instead, which on many units routes through the telephony path and comes
     * out narrowband -- which is why it is an opt-in setting there, not the
     * default.
     */
    private AudioTrack newTrack(int chanCfg, int bufSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(speech ? AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                                     : AudioAttributes.USAGE_MEDIA)
                    .setContentType(speech ? AudioAttributes.CONTENT_TYPE_SPEECH
                                           : AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(chanCfg)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            return new AudioTrack(attrs, fmt, bufSize, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
        return new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, chanCfg,
                AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
    }

    /**
     * Pull this sink down while a prompt plays on another one, and put it back
     * shortly after that prompt stops feeding.
     *
     * We answer every AudioFocusRequest with GAIN, so the phone believes the
     * head unit is handling the mix and leaves its own media stream at full
     * level. Nothing ducks unless we do it, and an unducked media stream is
     * what buries the navigation voice.
     */
    public void duck() {
        if (!running) return;
        synchronized (lock) {
            if (!running) return;
            if (duckHandler == null) duckHandler = new Handler(Looper.getMainLooper());
            if (!ducked) {
                ducked = true;
                applyVolume();
            }
            // Re-armed on every packet: the phone stops sending PCM well before
            // it sends STOP, and while it thinks we hold focus it sometimes
            // never sends STOP at all.
            duckHandler.removeCallbacks(unduck);
            duckHandler.postDelayed(unduck, Config.DUCK_RELEASE_MS);
        }
    }

    /** Caller holds lock. Software gain is constant, so ducking rides on the hardware volume. */
    private void applyVolume() {
        AudioTrack t = track;
        if (t == null) return;
        float v = Math.min(gain, 1.0f) * (ducked ? Config.DUCK_FACTOR : 1.0f);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                t.setVolume(v);
            } else {
                t.setStereoVolume(v, v);
            }
        } catch (Throwable e) {
            Logger.w("audio[" + name + "]: setVolume failed");
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            if (!running) return;
            running = false;
            t = thread;
            thread = null;
            if (duckHandler != null) duckHandler.removeCallbacks(unduck);
            ducked = false;
            lock.notifyAll();
        }
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        synchronized (lock) {
            if (track != null) {
                try { track.pause(); track.flush(); track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
                track = null;
            }
        }
        if (drops > 0) Logger.w("audio[" + name + "]: dropped " + drops + " buffers");
        Logger.i("audio[" + name + "]: stopped");
    }

    /** Copies PCM into the ring. Never blocks; drops the oldest buffer if full. */
    public void offer(byte[] src, int off, int len) {
        if (!running || len <= 0) return;
        if (len > Config.AUDIO_SLOT_BYTES) {
            // Split oversized payloads rather than truncating them.
            int done = 0;
            while (done < len) {
                int chunk = Math.min(Config.AUDIO_SLOT_BYTES, len - done);
                offerOne(src, off + done, chunk);
                done += chunk;
            }
            return;
        }
        offerOne(src, off, len);
    }

    private void offerOne(byte[] src, int off, int len) {
        synchronized (lock) {
            if (!running) return;
            if (count == capacity) {
                head = (head + 1) % slots.length; // drop oldest: keeps latency bounded
                count--;
                drops++;
            }
            int i = (head + count) % slots.length;
            System.arraycopy(src, off, slots[i], 0, len);
            slotLen[i] = len;
            count++;
            lock.notify();
        }
    }

    /**
     * Scale 16-bit little-endian PCM in place, clamping rather than wrapping.
     * Only used above 1.0: AudioTrack.setVolume() cannot amplify, and the
     * guidance stream arrives from the phone at a lower level than media does,
     * so matching them needs real headroom rather than a volume call.
     */
    static void applyGain(byte[] b, int len, float gain) {
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (int) (((short) ((b[i] & 0xFF) | (b[i + 1] << 8))) * gain);
            if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
            b[i] = (byte) s;
            b[i + 1] = (byte) (s >> 8);
        }
    }

    private final class Pump implements Runnable {
        @Override public void run() {
            while (true) {
                int i;
                synchronized (lock) {
                    while (running && count == 0) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (!running) return;
                    i = head;
                    head = (head + 1) % slots.length;
                    count--;
                }
                AudioTrack t = track;
                if (t == null) return;
                try {
                    if (gain > 1.0f) applyGain(slots[i], slotLen[i], gain);
                    t.write(slots[i], 0, slotLen[i]);
                } catch (Throwable e) {
                    Logger.e("audio[" + name + "]: write failed", e);
                    return;
                }
            }
        }
    }
}
