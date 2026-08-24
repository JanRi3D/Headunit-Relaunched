package me.ri3d.headunit.relaunched.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;

import me.ri3d.headunit.relaunched.Config;
import me.ri3d.headunit.relaunched.Settings;
import me.ri3d.headunit.relaunched.util.Logger;

/**
 * H.264 decode straight to a Surface.
 *
 * There is no Bitmap, no YUV conversion and no pixel ever touching Java: the
 * decoder writes into a graphics buffer and releaseOutputBuffer(idx, true)
 * hands it to SurfaceFlinger. On a weak head unit this is the difference
 * between 30fps and a slideshow.
 *
 * Threading: feed() runs on the session reader thread, and one drain thread
 * blocks in dequeueOutputBuffer. MediaCodec explicitly allows input and output
 * from different threads. That is the only extra thread video costs us.
 */
public final class VideoDecoder {

    private static final String MIME = "video/avc";

    private final Object lock = new Object();

    // volatile: written under `lock` by start/stop (UI thread), read without it
    // by feed() on the reader thread and by the drain thread.
    private volatile MediaCodec codec;
    private volatile ByteBuffer[] inputBuffers;
    private Thread drainThread;
    private volatile boolean running;

    /**
     * SPS/PPS seen in the stream. Cached so we can restart the decoder when the
     * Surface is recreated (activity resume) without waiting for the phone to
     * send another parameter set.
     */
    private byte[] csd;

    private long lastPts;
    /** Written by the drain thread, read by feed() on the session thread. */
    private volatile int rendered;

    public boolean isRunning() { return running; }

    public void start(Surface surface, int width, int height) {
        synchronized (lock) {
            stopLocked();
            try {
                MediaFormat fmt = MediaFormat.createVideoFormat(MIME, width, height);
                // MediaCodec sizes input buffers at 64KB by default, but Android
                // Auto's keyframes at 800x480 run 66-80KB. Every one of those
                // would be dropped, and a dropped keyframe means corruption or a
                // freeze until the next one -- which reads as "video is slow".
                // Scales with resolution: a 1080p keyframe is far bigger again.
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Settings.maxAccessUnitBytes());
                // Feeding csd-0 up front lets the decoder allocate before the
                // first frame instead of stalling on it.
                if (csd != null) {
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                }
                MediaCodec c = MediaCodec.createDecoderByType(MIME);
                c.configure(fmt, surface, null, 0);
                c.start();
                codec = c;
                inputBuffers = c.getInputBuffers();
                running = true;
                lastPts = 0;
                rendered = 0;

                drainThread = new Thread(new Drainer(), "hu-video");
                drainThread.setPriority(Thread.NORM_PRIORITY + 2);
                drainThread.start();
                Logger.i("video: decoder started " + width + "x" + height);
            } catch (Exception e) {
                Logger.e("video: decoder start failed", e);
                stopLocked();
            }
        }
    }

    public void stop() {
        synchronized (lock) { stopLocked(); }
    }

    private void stopLocked() {
        running = false;
        MediaCodec c = codec;
        codec = null;
        inputBuffers = null;
        Thread t = drainThread;
        drainThread = null;

        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        if (c != null) {
            try { c.stop(); } catch (Throwable ignored) {}
            try { c.release(); } catch (Throwable ignored) {}
            Logger.i("video: decoder stopped");
        }
    }

    /**
     * Queues one access unit. Drops the frame rather than blocking if the
     * decoder is behind -- stalling here would also stall audio, because both
     * arrive on the same reader thread.
     */
    public void feed(byte[] data, int off, int len, long ptsUs) {
        if (!running) return;
        rememberParameterSets(data, off, len);

        MediaCodec c = codec;
        ByteBuffer[] bufs = inputBuffers;
        if (c == null || bufs == null) return;

        try {
            int idx = c.dequeueInputBuffer(rendered == 0
                    ? Config.VIDEO_STARTUP_INPUT_TIMEOUT_US
                    : Config.VIDEO_INPUT_TIMEOUT_US);
            if (idx < 0) {
                Logger.d("video: input starved, dropping " + len + " bytes");
                return;
            }
            ByteBuffer b = bufs[idx];
            b.clear();
            if (b.capacity() < len) {
                Logger.w("video: AU " + len + " > input buffer " + b.capacity() + ", dropping");
                c.queueInputBuffer(idx, 0, 0, 0, 0);
                return;
            }
            b.put(data, off, len);

            // Presentation timestamps from the phone are microseconds. They can
            // repeat or go backwards on reconnect; keep them monotonic so the
            // decoder does not reorder or stall.
            if (ptsUs <= lastPts) ptsUs = lastPts + 1;
            lastPts = ptsUs;

            c.queueInputBuffer(idx, 0, len, ptsUs, 0);
        } catch (IllegalStateException e) {
            // Codec was stopped underneath us. Normal during shutdown.
            Logger.d("video: feed on stopped codec");
        } catch (Throwable e) {
            Logger.e("video: feed failed", e);
        }
    }

    /**
     * Keeps the newest SPS+PPS pair around for decoder restarts. Scans Annex-B
     * start codes; the cost is a byte walk over the first frames only, since we
     * stop looking once we have a set and the stream keeps resending the same one.
     */
    private void rememberParameterSets(byte[] d, int off, int len) {
        int end = off + len;
        int spsStart = -1, spsEnd = -1, ppsStart = -1, ppsEnd = -1;

        for (int i = off; i + 4 < end; i++) {
            if (d[i] != 0 || d[i + 1] != 0) continue;
            int nalOff;
            if (d[i + 2] == 1) nalOff = i + 3;
            else if (d[i + 2] == 0 && d[i + 3] == 1) nalOff = i + 4;
            else continue;
            if (nalOff >= end) break;

            int type = d[nalOff] & 0x1F;
            if (type == 7) { spsStart = i; spsEnd = -1; }
            else if (type == 8) { if (spsStart >= 0 && spsEnd < 0) spsEnd = i; ppsStart = i; }
            else if (ppsStart >= 0 && ppsEnd < 0) { ppsEnd = i; }
        }
        if (spsStart >= 0 && ppsStart > spsStart) {
            if (ppsEnd < 0) ppsEnd = end;
            byte[] set = new byte[ppsEnd - spsStart];
            System.arraycopy(d, spsStart, set, 0, set.length);
            csd = set;
        }
    }

    private final class Drainer implements Runnable {
        @Override public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                MediaCodec c = codec;
                if (c == null) break;
                try {
                    int idx = c.dequeueOutputBuffer(info, Config.VIDEO_OUTPUT_TIMEOUT_US);
                    if (idx >= 0) {
                        // true = render this buffer to the Surface
                        c.releaseOutputBuffer(idx, true);
                        // Distinguishes "we never decoded anything" from "we
                        // rendered fine and the display did not show it".
                        if (rendered++ == 0) Logger.i("video: first frame rendered");
                        else if (rendered % 100 == 0) Logger.i("video: " + rendered + " frames rendered");
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Logger.i("video: format " + c.getOutputFormat());
                    }
                    // INFO_TRY_AGAIN_LATER: dequeue already blocked for us, no spin.
                } catch (IllegalStateException e) {
                    break; // stopped underneath us
                } catch (Throwable e) {
                    // A codec reclaimed by the platform while we were in the
                    // background lands here. Say so, or isRunning() keeps
                    // claiming a decoder that has not drawn anything in minutes
                    // and nothing ever rebuilds it.
                    Logger.e("video: drain failed, decoder is done", e);
                    running = false;
                    break;
                }
            }
        }
    }
}
