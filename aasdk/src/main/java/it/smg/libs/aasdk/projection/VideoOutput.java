package it.smg.libs.aasdk.projection;

import androidx.annotation.Keep;

public abstract class VideoOutput implements IVideoOutput {

    protected int fps_;
    protected int resolution_;
    protected int dpi_;
    protected Rect margin_;
    protected int maxUnackedFrames_ = 1;
    protected int frameRateLimit_ = 0;

    public VideoOutput(){
        handle_ = nativeSetup();
    }

    @Keep
    @Override
    public int getVideoFPS() {
        return fps_;
    }

    @Keep
    @Override
    public int getVideoResolution() {
        return resolution_;
    }

    @Keep
    @Override
    public int getScreenDPI() {
        return dpi_;
    }

    @Keep
    @Override
    public Rect getVideoMargins() {
        return margin_;
    }

    @Keep
    @Override
    public int getMaxUnackedFrames() {
        return maxUnackedFrames_;
    }

    @Keep
    @Override
    public int getFrameRateLimit() {
        return frameRateLimit_;
    }

    @Override
    public void delete(){
        nativeDelete();
    }

    static {
        nativeInit();
    }

    private native long nativeSetup();
    private native void nativeDelete();
    private static native void nativeInit();

    @Keep
    private long handle_;
}
