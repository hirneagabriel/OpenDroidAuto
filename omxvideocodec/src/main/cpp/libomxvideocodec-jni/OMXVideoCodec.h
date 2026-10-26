#pragma once

#include <sys/types.h>
#include "OMXSource.h"
#include "OMXDecoder.h"
#include <thread>
#include <android/looper.h>

struct ScreenSize {
    int height = 0;
    int width = 0;
};

class OMXVideoCodec {
public:
    typedef OMXVideoCodec* Pointer;

    OMXVideoCodec();

    status_t init(int fps, bool lowLatency);
    void shutdown();
    void setNativeWindow(ANativeWindow* nativeWindow, int height, int width);

    OMXSource::Pointer source();
    OMXDecoder::Pointer decoder();
    void queueBuffer(common::DataConstBuffer& b, int64_t timestamp);

    void setSps(common::DataConstBuffer &b);
    // since the previous call: frames rendered, peak input queue length, arrival-to-screen avg/max (us)
    void takeStats(uint32_t& rendered, int& maxQueued, int64_t& latencyAvgUs, int64_t& latencyMaxUs);

    ~OMXVideoCodec();
private:
    void init_omxDecoderThread();

    OMXSource::Pointer omxSource_;
    OMXDecoder::Pointer omxDecoder_;

    std::thread omxDecoderThread_;
    std::mutex codecMutex_;
    std::condition_variable omxDecoderCond_;

//    bool omxDecoderThreadRunning_;
    bool decoderThreadInitialized_;
    bool decoderThreadQuitFlag_;
    sp<ANativeWindow> nativeWindow_;
    ScreenSize screenSize_;
    int fps_;
    bool lowLatency_ = false;
};
