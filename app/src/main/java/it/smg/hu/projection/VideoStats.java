package it.smg.hu.projection;

import android.os.SystemClock;

/**
 * Counts the video frames received from the phone, for ResourceMonitor: frame rate, data rate,
 * largest frame and longest gap between frames show how smoothly video arrives (e.g. while
 * panning the map or during screen transitions).
 */
public class VideoStats {

    private static final VideoStats instance_ = new VideoStats();

    public static VideoStats instance(){
        return instance_;
    }

    public static class Snapshot {
        public final int frames;
        public final long bytes;
        public final int maxFrameBytes;
        public final long maxGapMs;
        public final int maxUnackedFrames;

        private Snapshot(int frames, long bytes, int maxFrameBytes, long maxGapMs, int maxUnackedFrames){
            this.frames = frames;
            this.bytes = bytes;
            this.maxFrameBytes = maxFrameBytes;
            this.maxGapMs = maxGapMs;
            this.maxUnackedFrames = maxUnackedFrames;
        }
    }

    private int maxUnackedFrames_;
    private int frames_;
    private long bytes_;
    private int maxFrameBytes_;
    private long maxGapMs_;
    private long lastFrameMs_;

    private VideoStats(){}

    /** Called when a video output is created for a new connection. */
    public synchronized void newSession(int maxUnackedFrames){
        maxUnackedFrames_ = maxUnackedFrames;
        lastFrameMs_ = 0;
    }

    public synchronized void onFrame(int size){
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
    public synchronized Snapshot snapshot(){
        Snapshot s = new Snapshot(frames_, bytes_, maxFrameBytes_, maxGapMs_, maxUnackedFrames_);
        frames_ = 0;
        bytes_ = 0;
        maxFrameBytes_ = 0;
        maxGapMs_ = 0;
        return s;
    }
}
