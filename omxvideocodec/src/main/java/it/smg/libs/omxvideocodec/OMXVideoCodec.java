package it.smg.libs.omxvideocodec;

import android.view.Surface;

import androidx.annotation.Keep;

import java.nio.ByteBuffer;

import it.smg.libs.common.Log;

public class OMXVideoCodec {

    private static final String TAG = "OMXVideoCodec";

    private static native void nativeInit();
    private native long nativeSetup();
    private native void nativeSurfaceInit(Object surface, int width, int height);
    private native int[] nativeTakeStats();
    private native int nativeQueuedFrames();
    private native boolean nativeDecoderInit(int fps, boolean lowLatency, boolean releaseEarly);
    private native void nativeDelete();
    private native void nativeConsume(ByteBuffer buf, int len, long t);
    private native void nativeSetSps(ByteBuffer buf, int len);

    @Keep
    private long handle_ = 0;

    private Surface surfaceView_;
    private int width_;
    private int height_;
    private int fps_;
    private boolean lowLatency_;
    private boolean releaseEarly_;

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("common");
        System.loadLibrary("omxvideocodec-jni");

        nativeInit();
    }

    public OMXVideoCodec(int fps){
        handle_ = nativeSetup();
        fps_ = fps;
    }

    public void setSurface(Surface surface, int width, int height){
        surfaceView_ = surface;
        width_ = width;
        height_ = height;
        nativeSurfaceInit (surface, width_, height_);
    }

    /** Switch the NVIDIA decoder to low-latency mode (frames shown as soon as decoded); call before init(). */
    public void setLowLatency(boolean lowLatency){
        lowLatency_ = lowLatency;
    }

    /**
     * Experimental: don't let the decoder hold decoded frames until newer input arrives (it holds ~7 otherwise,
     * so the picture runs 7 frames behind); call before init().
     */
    public void setReleaseEarly(boolean releaseEarly){
        releaseEarly_ = releaseEarly;
    }

    public boolean init() {
        return nativeDecoderInit(fps_, lowLatency_, releaseEarly_);
    }

    public void shutdown() {
        nativeDelete();
        if (Log.isVerbose()) Log.v(TAG, "Native deleted");
        handle_ = 0;
    }

    public void mediaDecode(long timestamp, ByteBuffer buf, int len) {
        if (Log.isVerbose()) Log.v(TAG, "mediaDecode");
        if (isSps(buf)) {
            nativeSetSps(buf, len);
        }
        nativeConsume(buf, len, timestamp);
    }

    private boolean isSps(ByteBuffer buf) {
        boolean sps = (buf.get(4) & 0x1f ) == 7;
        return sps;
    }

    /** {frames rendered, peak decoder input queue length, arrival-to-screen avg ms, max ms} since the previous call. */
    /** Frames received from the phone that are still waiting for the decoder. */
    public int queuedFrames(){
        return nativeQueuedFrames();
    }

    public int[] takeStats(){
        return nativeTakeStats();
    }
}
