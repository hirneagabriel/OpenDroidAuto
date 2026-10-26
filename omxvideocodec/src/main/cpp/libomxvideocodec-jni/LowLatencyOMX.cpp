#include "LowLatencyOMX.h"
#include <Log.h>
#include <string.h>

namespace {

// Layouts as built by the head unit's ACodec::setupCodecForVideoTelephony (libstagefright.so)
struct NvxParamUseLowBuffer {
    OMX_U32 nSize;
    OMX_VERSIONTYPE nVersion;
    OMX_U32 nPortIndex;
    OMX_BOOL bUseLowBuffer;
};

struct NvxParamH264DisableDpb {
    OMX_U32 nSize;
    OMX_VERSIONTYPE nVersion;
    OMX_BOOL bDisableDPB;
};

template<class T>
void initParams(T* params) {
    memset(params, 0, sizeof(T));
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
}

}

status_t LowLatencyOMX::allocateNode(const char* name, const sp<IOMXObserver>& observer, node_id* node) {
    status_t err = omx_->allocateNode(name, observer, node);
    if (err == OK && name != nullptr && strcmp(name, "OMX.Nvidia.h264.decode") == 0) {
        enableLowLatency(*node);
    }
    return err;
}

void LowLatencyOMX::enableLowLatency(node_id node) {
    NvxParamUseLowBuffer lowBuffer;
    initParams(&lowBuffer);
    lowBuffer.nPortIndex = 0;
    lowBuffer.bUseLowBuffer = OMX_TRUE;
    setNvidiaParameter(node, "OMX.Nvidia.index.param.uselowbuffer", &lowBuffer, sizeof(lowBuffer));

    NvxParamH264DisableDpb disableDpb;
    initParams(&disableDpb);
    disableDpb.bDisableDPB = OMX_TRUE;
    setNvidiaParameter(node, "OMX.Nvidia.index.param.h264disabledpb", &disableDpb, sizeof(disableDpb));
}

// unlike ACodec, which aborts on failure, just log: the decoder still works without it
void LowLatencyOMX::setNvidiaParameter(node_id node, const char* name, const void* params, size_t size) {
    OMX_INDEXTYPE index;
    status_t err = omx_->getExtensionIndex(node, name, &index);
    if (err != OK) {
        if (Log::isWarn()) Log_w("%s not supported (%d)", name, err);
        return;
    }
    err = omx_->setParameter(node, index, params, size);
    if (err != OK) {
        if (Log::isWarn()) Log_w("%s failed (%d)", name, err);
    } else {
        if (Log::isInfo()) Log_i("%s enabled", name);
    }
}
