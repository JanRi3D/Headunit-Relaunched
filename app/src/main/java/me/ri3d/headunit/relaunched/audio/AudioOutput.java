package me.ri3d.headunit.relaunched.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

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

    public AudioOutput(String name, int sampleRate, int channels) {
        this.name = name;
        this.sampleRate = sampleRate;
        this.channels = channels;
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
            int bufSize = Math.max(min, Config.AUDIO_SLOT_BYTES * Config.AUDIO_BUFFER_SLOTS);
            try {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, chanCfg,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    Logger.e("audio[" + name + "]: AudioTrack init failed");
                    track.release();
                    track = null;
                    return;
                }
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
                    + " buf=" + bufSize);
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            if (!running) return;
            running = false;
            t = thread;
            thread = null;
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
                    t.write(slots[i], 0, slotLen[i]);
                } catch (Throwable e) {
                    Logger.e("audio[" + name + "]: write failed", e);
                    return;
                }
            }
        }
    }
}
