#pragma once

#include <JNIBase.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "OMXVideoCodec.h"

class JOMXVideoCodec : JNIBase {
public:
    typedef JOMXVideoCodec* Pointer;

    JOMXVideoCodec(JNIEnv *env, jobject jvideocodec);

    static jfieldID handleId;
    static JOMXVideoCodec::Pointer getJOMXVideoCodec(JNIEnv* env, jobject jvideocodec);

    void setNativeWindow(ANativeWindow* nativeWindow, int height, int width);
    status_t init(int fps, bool lowLatency, bool releaseEarly);
    void shutdown();
    void queueBuffer(common::DataConstBuffer &b, int64_t timestamp);
    void setSps(common::DataConstBuffer &b);
    void takeStats(uint32_t& rendered, int& maxQueued, int64_t& latencyAvgUs, int64_t& latencyMaxUs);
    void takeStages(int64_t out[]);
    int queuedFrames();

    ~JOMXVideoCodec();
protected:
    void initJavaMethods() override;

private:
    OMXVideoCodec::Pointer omxVideoCodec_;
};

