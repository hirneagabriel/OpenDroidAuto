#pragma once

#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/RefBase.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/IOMX.h>
#include <deque>
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
    // called by the decoder when the frame with this timestamp came out of the decoder
    void onDecoded(int64_t timeUs);
    // called by the decoder when the frame with this timestamp was handed to the screen
    void onRendered(int64_t timeUs);
    // arrival-to-screen time since the previous call: {count, avg us, max us}
    void takeLatency(int64_t out[3]);
    // where frames waited since the previous call, see cStageCount
    void takeStages(int64_t out[]);
    // {queue wait avg us, decode avg us, decode max us, hand-off avg us, frames in decoder avg x10, max}
    static constexpr int cStageCount = 6;

    /*
     * "Show frames as soon as decoded" (experimental). OMXCodec reads its next input from us inside the
     * decoder's EMPTY_BUFFER_DONE callback, and the decoder's callbacks reach OMXCodec one at a time (one-way
     * binder calls to one observer). While read() waits for the phone's next frame, the FILL_BUFFER_DONE of an
     * already decoded frame queues behind it, so each frame comes out only after ~7 newer ones arrive. With
     * this on, LowLatencyOMX's observer asks parkEmptyDone() first: with no frame waiting, the message is
     * parked (the callback returns at once) and released to OMXCodec from our own thread when a frame arrives.
     */
    void setEmptyDoneObserver(const sp<IOMXObserver>& codecObserver);
    // binder callback thread: true = parked, don't pass it on now
    bool parkEmptyDone(const omx_message& msg);

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
    // steady-clock times of each stage, per frame timestamp: arrived from the phone, taken by the decoder
    // (OMXCodec reads its input from us), decoded (came out of the decoder); 0 = not yet
    struct FrameTimes {
        int64_t arrivalUs = 0;
        int64_t takenUs = 0;
        int64_t decodedUs = 0;
    };
    std::map<int64_t, FrameTimes> frames_;
    int64_t stageCount_ = 0;
    int64_t queueWaitSumUs_ = 0;
    int64_t decodeSumUs_ = 0;
    int64_t decodeMaxUs_ = 0;
    int64_t inDecoderSum_ = 0;
    int64_t inDecoderMax_ = 0;
    int64_t handoffCount_ = 0;
    int64_t handoffSumUs_ = 0;
    int64_t latencyCount_ = 0;
    int64_t latencySumUs_ = 0;
    int64_t latencyMaxUs_ = 0;

    MediaBuffer* nextBuffer();
    int unclaimedFrames() const { return (int) pbuffers_.size() - claimed_; }
    void forwardParked();

    sp<IOMXObserver> codecObserver_;     // OMXCodec's own observer, set when early release is on
    std::deque<omx_message> parked_;     // EMPTY_BUFFER_DONE waiting for a frame
    int claimed_ = 0;                    // queued frames already promised to a passed-on EMPTY_BUFFER_DONE
    std::thread forwarder_;
    std::condition_variable forwardCond_;
};
