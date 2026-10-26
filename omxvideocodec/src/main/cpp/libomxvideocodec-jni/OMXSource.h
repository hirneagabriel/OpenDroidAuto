#pragma once

#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/RefBase.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <queue>
#include <map>
#include <thread>
#include <Data.hpp>

using namespace android;

class OMXSource : public MediaSource, public MediaBufferObserver {
public:
    typedef OMXSource* Pointer;
    static constexpr common::Data::size_type cChunkSize = 38406 * 4;

    OMXSource(int width, int height, int fps);

    status_t read(MediaBuffer **buffer, const MediaSource::ReadOptions *options) override;
    status_t start(MetaData *params) override;
    status_t stop() override;
    sp<MetaData> getFormat() override;
    void queueBuffer(MediaBuffer* buffer);
    // longest the input queue got since the previous call (frames waiting for the decoder)
    int takeMaxQueued();
    // called by the decoder when the frame with this timestamp was handed to the screen
    void onRendered(int64_t timeUs);
    // arrival-to-screen time since the previous call: {count, avg us, max us}
    void takeLatency(int64_t out[3]);

    status_t pause() override {
        return ERROR_UNSUPPORTED;
    }

    status_t setBuffers(const Vector<MediaBuffer *> &buffers) override {
        return ERROR_UNSUPPORTED;
    }

    void signalBufferReturned(MediaBuffer *buffer) override;

    ~OMXSource() override;
protected:


private:
    sp<MetaData> format_;
    std::queue<MediaBuffer*> pbuffers_;
    std::mutex mutex_;
    std::condition_variable cond_;

    bool quitFlag_;
    int maxQueued_ = 0;
    std::map<int64_t, int64_t> arrivalUs_; // frame timestamp -> arrival (steady clock)
    int64_t latencyCount_ = 0;
    int64_t latencySumUs_ = 0;
    int64_t latencyMaxUs_ = 0;

    MediaBuffer* nextBuffer();
};
