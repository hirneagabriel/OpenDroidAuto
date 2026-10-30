#include <chrono>
#include "OMXSource.h"
#include "Log.h"

#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/openmax/OMX_IVCommon.h>
#include <media/stagefright/openmax/OMX_Video.h>
#include <cinttypes>
#include <sstream>

using namespace android;

// https://www.programmersought.com/article/87712558400/
// https://vec.io/posts/use-android-hardware-decoder-with-omxcodec-in-ndk
// https://stackoverflow.com/questions/9832503/android-include-native-stagefright-features-in-my-own-project
OMXSource::OMXSource(int width, int height, int fps):
        format_(nullptr), quitFlag_(false)
{

    int32_t bufferSize = (width * height * 3) / 2;
    if (Log::isVerbose()) Log_v("BufferSize: %d", bufferSize);

    format_ = new MetaData();
    format_->setInt32(kKeyWidth, width);
    format_->setInt32(kKeyHeight, height);
    format_->setInt32(kKeyStride, width);
    format_->setInt32(kKeySliceHeight, height);
    format_->setInt32(kKeyFrameRate, fps);
//    format_->setInt32(kKeyIFramesInterval, 1);
    format_->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
    format_->setInt32(kKeyVideoLevel, OMX_VIDEO_AVCLevel31);
    format_->setInt32(kKeyVideoProfile, OMX_VIDEO_AVCProfileBaseline);
    format_->setInt32(kKeyMaxInputSize, bufferSize);
    format_->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420Planar);
}

OMXSource::~OMXSource() {
    if (Log::isDebug()) Log_d("destructor");
    if (forwarder_.joinable()) {
        {
            std::unique_lock<std::mutex> l(mutex_);
            quitFlag_ = true;
            forwardCond_.notify_one();
        }
        if (forwarder_.get_id() != std::this_thread::get_id()) {
            forwarder_.join();
        } else {
            forwarder_.detach();
        }
    }
}

static int64_t steadyNowUs(){
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void OMXSource::onDecoded(int64_t timeUs){
    std::unique_lock<std::mutex> l(mutex_);
    auto it = frames_.find(timeUs);
    if (it == frames_.end() || it->second.takenUs == 0) {
        return;
    }
    // frames come out in order: anything older that hasn't come out was dropped by the decoder
    frames_.erase(frames_.begin(), it);
    it = frames_.begin();

    // this frame plus the newer ones the decoder already took but hasn't returned: how many frames it holds
    int64_t inDecoder = 0;
    for (const auto& f : frames_) {
        if (f.second.takenUs != 0 && f.second.decodedUs == 0) inDecoder++;
    }

    const int64_t nowUs = steadyNowUs();
    FrameTimes& t = it->second;
    t.decodedUs = nowUs;
    const int64_t decodeUs = nowUs - t.takenUs;
    stageCount_++;
    queueWaitSumUs_ += t.takenUs - t.arrivalUs;
    decodeSumUs_ += decodeUs;
    if (decodeUs > decodeMaxUs_) decodeMaxUs_ = decodeUs;
    inDecoderSum_ += inDecoder;
    if (inDecoder > inDecoderMax_) inDecoderMax_ = inDecoder;
}

void OMXSource::onRendered(int64_t timeUs){
    std::unique_lock<std::mutex> l(mutex_);
    auto it = frames_.find(timeUs);
    if (it == frames_.end()) {
        return;
    }
    const int64_t nowUs = steadyNowUs();
    const int64_t latencyUs = nowUs - it->second.arrivalUs;
    latencyCount_++;
    latencySumUs_ += latencyUs;
    if (latencyUs > latencyMaxUs_) {
        latencyMaxUs_ = latencyUs;
    }
    if (it->second.decodedUs != 0) {
        handoffCount_++;
        handoffSumUs_ += nowUs - it->second.decodedUs;
    }
    // frames come out in order: anything older was dropped by the decoder
    frames_.erase(frames_.begin(), ++it);
}

void OMXSource::takeStages(int64_t out[]){
    std::unique_lock<std::mutex> l(mutex_);
    out[0] = stageCount_ > 0 ? queueWaitSumUs_ / stageCount_ : 0;
    out[1] = stageCount_ > 0 ? decodeSumUs_ / stageCount_ : 0;
    out[2] = decodeMaxUs_;
    out[3] = handoffCount_ > 0 ? handoffSumUs_ / handoffCount_ : 0;
    out[4] = stageCount_ > 0 ? inDecoderSum_ * 10 / stageCount_ : 0;
    out[5] = inDecoderMax_;
    stageCount_ = 0;
    queueWaitSumUs_ = 0;
    decodeSumUs_ = 0;
    decodeMaxUs_ = 0;
    inDecoderSum_ = 0;
    inDecoderMax_ = 0;
    handoffCount_ = 0;
    handoffSumUs_ = 0;
}

void OMXSource::takeLatency(int64_t out[3]){
    std::unique_lock<std::mutex> l(mutex_);
    out[0] = latencyCount_;
    out[1] = latencyCount_ > 0 ? latencySumUs_ / latencyCount_ : 0;
    out[2] = latencyMaxUs_;
    latencyCount_ = 0;
    latencySumUs_ = 0;
    latencyMaxUs_ = 0;
}

int OMXSource::takeMaxQueued(){
    std::unique_lock<std::mutex> l(mutex_);
    int max = maxQueued_;
    maxQueued_ = (int) pbuffers_.size();
    return max;
}

int OMXSource::queuedFrames(){
    std::unique_lock<std::mutex> l(mutex_);
    return (int) pbuffers_.size();
}

void OMXSource::queueBuffer(MediaBuffer* buffer){
    if (Log::isVerbose()) Log_v("add buffer to queue");
    std::unique_lock<std::mutex> l(mutex_);

    if (!quitFlag_) {
        pbuffers_.push(buffer);
        int64_t timeUs;
        if (buffer->meta_data()->findInt64(kKeyTime, &timeUs)) {
            frames_[timeUs].arrivalUs = steadyNowUs();
            while (frames_.size() > 120) { // frames the decoder never output
                frames_.erase(frames_.begin());
            }
        }
        if ((int) pbuffers_.size() > maxQueued_) {
            maxQueued_ = (int) pbuffers_.size();
        }
        if (Log::isVerbose()) Log_v("queueBuffer new size %d", pbuffers_.size());
        cond_.notify_one();
        if (!parked_.empty()) {
            forwardCond_.notify_one();
        }
    }
}

sp<MetaData>  OMXSource::getFormat(){
    return format_;
}

MediaBuffer* OMXSource::nextBuffer(){
    if (Log::isVerbose()) Log_v("nextBuffer");
    std::unique_lock<std::mutex> l(mutex_);

    while (!quitFlag_) {
        if (pbuffers_.empty()){
            cond_.wait_until(l, std::chrono::steady_clock::now() + std::chrono::milliseconds(1000));
            continue;
        }

        if (Log::isVerbose()) Log_v("buffer size %d", pbuffers_.size());
        MediaBuffer *buffer = pbuffers_.front();
        pbuffers_.pop();
        if (claimed_ > 0) {
            claimed_--;
        }
        if (Log::isVerbose()) Log_v("found buffer %d", pbuffers_.size());
        // OMXCodec reads its input as soon as it has a free input buffer: the frame goes into the decoder now
        int64_t timeUs;
        if (buffer->meta_data()->findInt64(kKeyTime, &timeUs)) {
            auto it = frames_.find(timeUs);
            if (it != frames_.end()) it->second.takenUs = steadyNowUs();
        }
        return buffer;
    }

    return nullptr;
}

status_t OMXSource::read(MediaBuffer **buffer, const MediaSource::ReadOptions *options) {
    if (Log::isVerbose()) Log_v("read");

    MediaBuffer* mBuffer = nextBuffer();
    if (!mBuffer){
        return ERROR_END_OF_STREAM;
    }

    mBuffer->setObserver(this);
    mBuffer->add_ref();
    mBuffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);
    (*buffer) = mBuffer;

    return OK;

}

void OMXSource::signalBufferReturned(MediaBuffer *buffer) {
    if (Log::isVerbose()) Log_v("signalBufferReturned: %p", buffer);
    buffer->setObserver(nullptr);
    buffer->release();
}

status_t OMXSource::start(MetaData *params){
    if (Log::isInfo()) Log_i("start");
    return OK;
}

status_t OMXSource::stop() {
    if (Log::isDebug()) Log_d("stopping");
    std::unique_lock<std::mutex> l(mutex_);

    if (!quitFlag_) {
        if (Log::isInfo()) Log_i("stop");
        quitFlag_ = true;
        if (Log::isVerbose()) Log_v("quit flag to true");
        if (Log::isDebug()) Log_d("delete pbuffer: %d", pbuffers_.size());
        while (!pbuffers_.empty()) {
            MediaBuffer* buffer = pbuffers_.front();
            pbuffers_.pop();
            buffer->release();
        }
        cond_.notify_one();
        forwardCond_.notify_one();
    }
    l.unlock();
    // the forwarder releases everything still parked (OMXCodec waits for those buffers when it stops);
    // OMXCodec calls stop() again later, holding its lock, by when the forwarder is gone
    if (forwarder_.joinable() && forwarder_.get_id() != std::this_thread::get_id()) {
        forwarder_.join();
    }
    if (Log::isDebug()) Log_d("stopped");
    return OK;
}

void OMXSource::setEmptyDoneObserver(const sp<IOMXObserver>& codecObserver){
    std::unique_lock<std::mutex> l(mutex_);
    codecObserver_ = codecObserver;
    if (!forwarder_.joinable()) {
        forwarder_ = std::thread(&OMXSource::forwardParked, this);
    }
}

bool OMXSource::parkEmptyDone(const omx_message& msg){
    std::unique_lock<std::mutex> l(mutex_);
    if (quitFlag_) {
        return false;
    }
    if (unclaimedFrames() > 0) {
        // OMXCodec's read() will find this frame at once: pass the message on now
        claimed_++;
        return false;
    }
    parked_.push_back(msg);
    return true;
}

// own thread: OMXCodec may hold its lock while it waits in read() (its first input buffers), so passing a
// parked message on from the thread that queues the phone's frames could deadlock
void OMXSource::forwardParked(){
    std::unique_lock<std::mutex> l(mutex_);
    while (true) {
        forwardCond_.wait(l, [this]() { return quitFlag_ || (!parked_.empty() && unclaimedFrames() > 0); });
        if (parked_.empty()) {
            if (quitFlag_) break;
            continue;
        }
        omx_message msg = parked_.front();
        parked_.pop_front();
        if (!quitFlag_) {
            claimed_++;
        }
        sp<IOMXObserver> observer = codecObserver_;
        l.unlock();
        if (observer != nullptr) {
            observer->onMessage(msg);
        }
        l.lock();
    }
    if (Log::isDebug()) Log_d("forwarder ends");
}


