package it.smg.hu.projection;

import android.os.SystemClock;

/**
 * Counts the video frames received from the phone, for ResourceMonitor: frame rate, data rate,
 * largest frame and longest gap between frames show how smoothly video arrives (e.g. while
 * panning the map or during screen transitions); rendered frames and the decoder's input
 * backlog show whether the head unit keeps up with it.
 */
public class VideoStats {

    private static final VideoStats instance_ = new VideoStats();

    public static VideoStats instance(){
        return instance_;
    }

    /** Implemented by the video output that owns the decoder. */
    public interface DecoderStats {
        /** {frames rendered, peak frames waiting for the decoder, arrival-to-screen avg ms, max ms}
         *  since the previous call, or null. */
        int[] takeDecoderStats();
    }

    public static class Snapshot {
        public final int frames;
        public final long bytes;
        public final int maxFrameBytes;
        public final long maxGapMs;
        public final int renderedFrames; // -1 when no decoder reports
        public final int maxQueued;      // -1 when no decoder reports
        public final int latencyAvgMs;   // -1 when no decoder reports
        public final int latencyMaxMs;   // -1 when no decoder reports

        private Snapshot(int frames, long bytes, int maxFrameBytes, long maxGapMs,
                         int renderedFrames, int maxQueued, int latencyAvgMs, int latencyMaxMs){
            this.frames = frames;
            this.bytes = bytes;
            this.maxFrameBytes = maxFrameBytes;
            this.maxGapMs = maxGapMs;
            this.renderedFrames = renderedFrames;
            this.maxQueued = maxQueued;
            this.latencyAvgMs = latencyAvgMs;
            this.latencyMaxMs = latencyMaxMs;
        }
    }

    private DecoderStats decoderStats_;
    private int frames_;
    private long bytes_;
    private int maxFrameBytes_;
    private long maxGapMs_;
    private long lastFrameMs_;

    private VideoStats(){}

    /** Called when a video output is created for a new connection. */
    public synchronized void newSession(){
        lastFrameMs_ = 0;
    }

    public synchronized void setDecoderStats(DecoderStats decoderStats){
        decoderStats_ = decoderStats;
    }

    public void onFrame(int size){
        InputStats.instance().onVideoFrame();
        countFrame(size);
    }

    private synchronized void countFrame(int size){
        long now = SystemClock.elapsedRealtime();
        if (lastFrameMs_ != 0){
            maxGapMs_ = Math.max(maxGapMs_, now - lastFrameMs_);
        }
        lastFrameMs_ = now;
        frames_++;
        bytes_ += size;
        maxFrameBytes_ = Math.max(maxFrameBytes_, size);
    }

    /** Returns the counts since the previous snapshot and resets them. */
    public Snapshot snapshot(){
        DecoderStats decoderStats;
        synchronized (this) {
            decoderStats = decoderStats_;
        }
        // outside our lock: the decoder's owner locks itself and calls setDecoderStats() on stop
        int[] decoder = decoderStats != null ? decoderStats.takeDecoderStats() : null;

        synchronized (this) {
            Snapshot s = new Snapshot(frames_, bytes_, maxFrameBytes_, maxGapMs_,
                    decoder != null ? decoder[0] : -1, decoder != null ? decoder[1] : -1,
                    decoder != null ? decoder[2] : -1, decoder != null ? decoder[3] : -1);
            frames_ = 0;
            bytes_ = 0;
            maxFrameBytes_ = 0;
            maxGapMs_ = 0;
            return s;
        }
    }
}
