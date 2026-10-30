#include "it_smg_libs_videocodec_OMXVideoCodec.h"
#include <Log.h>

jfieldID JOMXVideoCodec::handleId = nullptr;

JOMXVideoCodec::JOMXVideoCodec(JNIEnv *env, jobject jvideocodec) :
        JNIBase(env, jvideocodec, "JOMXVideoCodec"), omxVideoCodec_(new OMXVideoCodec()){

    initJavaMethods();
}

void JOMXVideoCodec::initJavaMethods() {
}

JOMXVideoCodec::Pointer JOMXVideoCodec::getJOMXVideoCodec(JNIEnv *env, jobject jvideocodec) {
    return (JOMXVideoCodec::Pointer)env->GetLongField(jvideocodec, JOMXVideoCodec::handleId);
}

void JOMXVideoCodec::setNativeWindow(ANativeWindow* nativeWindow, int height, int width){
    omxVideoCodec_->setNativeWindow(nativeWindow, height, width);
}

status_t JOMXVideoCodec::init(int fps, bool lowLatency, bool releaseEarly){
    return omxVideoCodec_->init(fps, lowLatency, releaseEarly);
}

void JOMXVideoCodec::shutdown(){
    omxVideoCodec_->shutdown();
    if (Log::isVerbose()) Log_v("shutdown complete");
}

void JOMXVideoCodec::queueBuffer(common::DataConstBuffer &b, int64_t timestamp) {
    omxVideoCodec_->queueBuffer(b, timestamp);
}

void JOMXVideoCodec::setSps(common::DataConstBuffer &b){
    omxVideoCodec_->setSps(b);
}

void JOMXVideoCodec::takeStats(uint32_t& rendered, int& maxQueued, int64_t& latencyAvgUs, int64_t& latencyMaxUs) {
    omxVideoCodec_->takeStats(rendered, maxQueued, latencyAvgUs, latencyMaxUs);
}

void JOMXVideoCodec::takeStages(int64_t out[]) {
    omxVideoCodec_->takeStages(out);
}

int JOMXVideoCodec::queuedFrames() {
    return omxVideoCodec_->queuedFrames();
}

JOMXVideoCodec::~JOMXVideoCodec() {
    if (Log::isVerbose()) Log_v("delete omxVideoCodec_");
    delete omxVideoCodec_;
}

////////////////////////////////////////////////////////////////////////////////////////////////////

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeInit(JNIEnv *env, jclass clazz) {
    jclass _class = env->FindClass("it/smg/libs/omxvideocodec/OMXVideoCodec");
    JOMXVideoCodec::handleId = env->GetFieldID(_class, "handle_", "J");

    env->DeleteLocalRef(_class);
    _class = nullptr;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeSetup(JNIEnv* env, jobject thiz) {
    auto jOMXVideoCodec = new JOMXVideoCodec(env, thiz);
    return (jlong)((size_t)jOMXVideoCodec);
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeSurfaceInit(JNIEnv *env, jobject thiz, jobject surface, jint width, jint height) {
    JOMXVideoCodec::Pointer jOMXVideoCodec = JOMXVideoCodec::getJOMXVideoCodec(env, thiz);

    ANativeWindow* nativeWindow = ANativeWindow_fromSurface(env, surface);
    jOMXVideoCodec->setNativeWindow(nativeWindow, height, width);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeDecoderInit(JNIEnv *env, jobject thiz, jint fps, jboolean lowLatency, jboolean releaseEarly) {
    JOMXVideoCodec::Pointer jOMXVideoCodec = JOMXVideoCodec::getJOMXVideoCodec(env, thiz);
    status_t ret = jOMXVideoCodec->init(fps, lowLatency == JNI_TRUE, releaseEarly == JNI_TRUE);

    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeDelete(JNIEnv *env, jobject thiz) {
    JOMXVideoCodec::Pointer jOMXVideoCodec = JOMXVideoCodec::getJOMXVideoCodec(env, thiz);
    jOMXVideoCodec->shutdown();
    delete jOMXVideoCodec;
    if (Log::isVerbose()) Log_v("deleted");
}


extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeConsume(JNIEnv *env, jobject thiz, jobject buf, jint len, jlong t) {
    JOMXVideoCodec::Pointer jOMXVideoCodec = JOMXVideoCodec::getJOMXVideoCodec(env, thiz);

    auto *bufferData = (jbyte *) env->GetDirectBufferAddress(buf);
    common::DataConstBuffer buffer(bufferData, len);
    auto timestamp = static_cast<int64_t>(t);

    jOMXVideoCodec->queueBuffer(buffer, timestamp);
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeSetSps(JNIEnv *env, jobject thiz, jobject buf, jint len) {
    JOMXVideoCodec::Pointer jOMXVideoCodec = JOMXVideoCodec::getJOMXVideoCodec(env, thiz);

    auto *bufferData = (jbyte *) env->GetDirectBufferAddress(buf);
    common::DataConstBuffer buffer(bufferData, len);

    jOMXVideoCodec->setSps(buffer);
}


extern "C"
JNIEXPORT jintArray JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeTakeStats(JNIEnv *env, jobject thiz) {
    JOMXVideoCodec::Pointer jOMXVideoCodec = JOMXVideoCodec::getJOMXVideoCodec(env, thiz);

    uint32_t rendered = 0;
    int maxQueued = 0;
    int64_t latencyAvgUs = 0;
    int64_t latencyMaxUs = 0;
    jOMXVideoCodec->takeStats(rendered, maxQueued, latencyAvgUs, latencyMaxUs);
    int64_t stages[OMXSource::cStageCount];
    jOMXVideoCodec->takeStages(stages);

    // {rendered, peak queued, latency avg ms, max ms, queue wait avg us, decode avg us, decode max us,
    //  hand-off avg us, frames in decoder avg x10, max}
    jint values[4 + OMXSource::cStageCount] = {(jint) rendered, (jint) maxQueued, (jint) (latencyAvgUs / 1000), (jint) (latencyMaxUs / 1000)};
    for (int i = 0; i < OMXSource::cStageCount; i++) values[4 + i] = (jint) stages[i];
    jintArray result = env->NewIntArray(4 + OMXSource::cStageCount);
    env->SetIntArrayRegion(result, 0, 4 + OMXSource::cStageCount, values);
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_it_smg_libs_omxvideocodec_OMXVideoCodec_nativeQueuedFrames(JNIEnv *env, jobject thiz) {
    JOMXVideoCodec::Pointer jOMXVideoCodec = JOMXVideoCodec::getJOMXVideoCodec(env, thiz);
    return jOMXVideoCodec->queuedFrames();
}
