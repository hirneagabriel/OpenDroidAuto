#pragma once

#include <atomic>

#include <utils/Errors.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/MediaSource.h>
#include <OMXSource.h>

using namespace android;

class OMXDecoder {
public:
    typedef OMXDecoder* Pointer;

    explicit OMXDecoder();

    virtual status_t read();
    void setNativeWindow(sp<ANativeWindow> &nativeWindow);
    void setSource(OMXSource *source);
    // lowLatency: switch the NVIDIA decoder to low-buffer / no-DPB mode (see LowLatencyOMX);
    // releaseEarly: don't let a waiting read() hold back decoded frames (experimental, see OMXSource)
    status_t  init(bool lowLatency, bool releaseEarly);
    int32_t frameRate() const;
    void stop();
    ~OMXDecoder();
    status_t getStatus() const;
    // frames handed to the screen since the previous call
    uint32_t takeRenderedFrames();
protected:


private:
    OMXClient* client_;
    sp<MediaSource> decoder_;
    sp<ANativeWindow> nativeWindow_;
    OMXSource::Pointer source_;
    std::mutex mutex_;
    int32_t frameRate_;

    status_t status_;
    std::atomic<uint32_t> renderedFrames_{0};
};
