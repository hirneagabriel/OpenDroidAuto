#pragma once

#include <media/IOMX.h>
#include "OMXSource.h"

using namespace android;

/** The NVIDIA decoder's observer with early release on: parks EMPTY_BUFFER_DONE in the source (see OMXSource). */
class EarlyReleaseObserver : public BnOMXObserver {
public:
    EarlyReleaseObserver(const sp<IOMXObserver>& codecObserver, const sp<OMXSource>& source)
        : codecObserver_(codecObserver), source_(source) {}

    void onMessage(const omx_message& msg) override {
        if (msg.type == omx_message::EMPTY_BUFFER_DONE && source_->parkEmptyDone(msg)) {
            return;
        }
        codecObserver_->onMessage(msg);
    }

private:
    sp<IOMXObserver> codecObserver_;
    sp<OMXSource> source_;
};

/**
 * IOMX that forwards everything to the real one, and right after OMXCodec allocates the NVIDIA H.264
 * decoder switches it to low-latency mode, like the head unit's ACodec::setupCodecForVideoTelephony
 * does: low-buffer mode and no DPB, plus full-frame input (each buffer is one complete frame). Without it the decoder keeps ~14 frames and only releases the
 * last frames of an animation when newer ones arrive (the phone's stream already signals
 * max_num_reorder_frames=0, max_dec_frame_buffering=1, which the decoder ignores).
 */
class LowLatencyOMX : public IOMX {
public:
    // lowLatency: NVIDIA low-buffer / no-DPB / full-frame mode; source != null: early release (see OMXSource)
    LowLatencyOMX(const sp<IOMX>& omx, bool lowLatency, const sp<OMXSource>& source)
        : omx_(omx), lowLatency_(lowLatency), source_(source) {}

    bool livesLocally(pid_t pid) override { return omx_->livesLocally(pid); }
    status_t listNodes(List<ComponentInfo>* list) override { return omx_->listNodes(list); }
    status_t allocateNode(const char* name, const sp<IOMXObserver>& observer, node_id* node) override;
    status_t freeNode(node_id node) override { return omx_->freeNode(node); }
    status_t sendCommand(node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param) override {
        return omx_->sendCommand(node, cmd, param);
    }
    status_t getParameter(node_id node, OMX_INDEXTYPE index, void* params, size_t size) override {
        return omx_->getParameter(node, index, params, size);
    }
    status_t setParameter(node_id node, OMX_INDEXTYPE index, const void* params, size_t size) override {
        return omx_->setParameter(node, index, params, size);
    }
    status_t getConfig(node_id node, OMX_INDEXTYPE index, void* params, size_t size) override {
        return omx_->getConfig(node, index, params, size);
    }
    status_t setConfig(node_id node, OMX_INDEXTYPE index, const void* params, size_t size) override {
        return omx_->setConfig(node, index, params, size);
    }
    status_t getState(node_id node, OMX_STATETYPE* state) override { return omx_->getState(node, state); }
    status_t storeMetaDataInBuffers(node_id node, OMX_U32 port_index, OMX_BOOL enable) override {
        return omx_->storeMetaDataInBuffers(node, port_index, enable);
    }
    status_t enableGraphicBuffers(node_id node, OMX_U32 port_index, OMX_BOOL enable) override {
        return omx_->enableGraphicBuffers(node, port_index, enable);
    }
    status_t getGraphicBufferUsage(node_id node, OMX_U32 port_index, OMX_U32* usage) override {
        return omx_->getGraphicBufferUsage(node, port_index, usage);
    }
    status_t useBuffer(node_id node, OMX_U32 port_index, const sp<IMemory>& params, buffer_id* buffer) override {
        return omx_->useBuffer(node, port_index, params, buffer);
    }
    status_t useGraphicBuffer(node_id node, OMX_U32 port_index, const sp<GraphicBuffer>& graphicBuffer, buffer_id* buffer) override {
        return omx_->useGraphicBuffer(node, port_index, graphicBuffer, buffer);
    }
    status_t allocateBuffer(node_id node, OMX_U32 port_index, size_t size, buffer_id* buffer, void** buffer_data) override {
        return omx_->allocateBuffer(node, port_index, size, buffer, buffer_data);
    }
    status_t allocateBufferWithBackup(node_id node, OMX_U32 port_index, const sp<IMemory>& params, buffer_id* buffer) override {
        return omx_->allocateBufferWithBackup(node, port_index, params, buffer);
    }
    status_t freeBuffer(node_id node, OMX_U32 port_index, buffer_id buffer) override {
        return omx_->freeBuffer(node, port_index, buffer);
    }
    status_t fillBuffer(node_id node, buffer_id buffer) override { return omx_->fillBuffer(node, buffer); }
    status_t emptyBuffer(node_id node, buffer_id buffer, OMX_U32 range_offset, OMX_U32 range_length,
                         OMX_U32 flags, OMX_TICKS timestamp) override {
        return omx_->emptyBuffer(node, buffer, range_offset, range_length, flags, timestamp);
    }
    status_t getExtensionIndex(node_id node, const char* parameter_name, OMX_INDEXTYPE* index) override {
        return omx_->getExtensionIndex(node, parameter_name, index);
    }

protected:
    IBinder* onAsBinder() override { return omx_->asBinder().get(); }

private:
    void enableLowLatency(node_id node);
    void setNvidiaParameter(node_id node, const char* name, const void* params, size_t size);

    sp<IOMX> omx_;
    bool lowLatency_;
    sp<OMXSource> source_;
};
