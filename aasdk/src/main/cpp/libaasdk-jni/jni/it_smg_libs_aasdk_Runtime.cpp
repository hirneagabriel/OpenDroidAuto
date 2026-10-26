#include "service/LinkStats.h"
#include <Log.h>
#include <thread>
#include <google/protobuf/stubs/common.h>
#include "it_smg_libs_aasdk_Runtime.h"
#include "Backtrace.h"

jfieldID JRuntime::handleId = nullptr;
JRuntime::Pointer JRuntime::instance_ = nullptr;

JRuntime::JRuntime(JNIEnv *env, jobject androidApp) : JNIBase(env, androidApp, "JRuntime"),
                                                      i(0){

    if (instance_ != nullptr){
        if(Log::isInfo()) Log_i("JRuntime already instanciated, delete it");
        delete instance_;
    }

    instance_ = this;
}

JRuntime::Pointer JRuntime::getJRuntime(JNIEnv *env, jobject jruntime) {
    return (JRuntime::Pointer)env->GetLongField(jruntime, JRuntime::handleId);;
}

JRuntime::~JRuntime() {
    if(Log::isDebug()) Log_d("all done");
    instance_ = nullptr;
}

void JRuntime::stopIOServiceWorkers() {
    if(Log::isDebug()) Log_d("stopping IOService Workers");

    if (!ioService_.stopped()) {
        if(Log::isDebug()) Log_d("stop ioService");
        work_.reset();
        ioService_.stop();
    }

    // wait for every worker to leave run(): this JRuntime (and its io_service) is deleted right after, and
    // workers that kept running used to leak and run on in the deleted io_service. ODAService stops sessions
    // on its own thread, never on a worker, so this can't join itself; if it ever is, skip that one.
    const auto self = std::this_thread::get_id();
    for (auto& worker : threadPool_) {
        if (!worker.joinable()) continue;
        if (worker.get_id() == self) {
            if(Log::isWarn()) Log_w("stopping IOService Workers from a worker thread, not joining it");
            worker.detach();
        } else {
            worker.join();
        }
    }
    threadPool_.clear();
    if(Log::isDebug()) Log_d("all IOService Workers stopped");

    ioService_.restart();
}

void JRuntime::startIOServiceWorkers(int threads) {
    if(Log::isDebug()) Log_d("starting IOService Workers");

    work_ = std::make_unique<boost::asio::io_service::work>(ioService_);

    auto ioServiceWorker = [this]() {
        // Attach jni thread
        JNIEnv* env;
        JNIBase::javaAttachThread("IOService Worker", &env);

        {
            std::unique_lock<std::mutex> lock(m);
            ++i;
            if(Log::isVerbose()) Log_v("i= %d", i);
            v.notify_all();
        }

        if(Log::isVerbose()) Log_v("start ioService");
        ioService_.run();
        if(Log::isVerbose()) Log_v("ioService stopped");

        // Detach thread
        JNIBase::javaDetachThread();

        {
            std::unique_lock<std::mutex> lock(m);
            --i;
            if(Log::isVerbose()) Log_v("i= %d", i);
            v.notify_all();
        }

        if(Log::isInfo()) Log_i("ioservice worker thread complete");
    };

    if(Log::isInfo()) Log_i("Starting %d threads", threads);

    for (int w = 1; w <= threads; w++) {
        threadPool_.emplace_back(ioServiceWorker);
    }

    std::unique_lock<std::mutex> lock(m);
    v.wait(lock, [&] { return i == threads; });

    if(Log::isInfo()) Log_i("all ioservice thread started");
}

aasdk::io::ioService &JRuntime::ioService() {
    return instance_->ioService_;
}

////////////////////////////////////////////////////////////////////////////////////////////////////

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_Runtime_nativeInit(JNIEnv *env, jclass clazz) {
    GOOGLE_PROTOBUF_VERIFY_VERSION;

    Backtrace::init();

    jclass _class = env->FindClass("it/smg/libs/aasdk/Runtime");
    JRuntime::handleId = env->GetFieldID(_class, "handle_", "J");

    env->DeleteLocalRef(_class);
    _class = nullptr;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_it_smg_libs_aasdk_Runtime_nativeSetup(JNIEnv *env, jobject thiz) {
    auto jruntime = new JRuntime(env, thiz);
    return (jlong)((size_t)jruntime);
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_Runtime_nativeDelete(JNIEnv *env, jobject thiz) {
    auto jRuntime = JRuntime::getJRuntime(env, thiz);
    delete jRuntime;
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_Runtime_nativeStartIOServiceWorker(JNIEnv *env, jobject thiz, jint threads) {
    auto jRuntime = JRuntime::getJRuntime(env, thiz);
    jRuntime->startIOServiceWorkers(threads);
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_Runtime_nativeStopIOServiceWorker(JNIEnv *env, jobject thiz) {
    auto jRuntime = JRuntime::getJRuntime(env, thiz);
    jRuntime->stopIOServiceWorkers();
}

//extern "C"
//JNIEXPORT void JNICALL
//Java_it_smg_libs_aasdk_Runtime_nativeCrash(JNIEnv *env, jclass clazz) {
//    abort();
//}


extern "C"
JNIEXPORT jlongArray JNICALL
Java_it_smg_libs_aasdk_Runtime_nativeTakeLinkStats(JNIEnv *env, jclass clazz) {
    int64_t values[6];
    service::linkstats::take(values);

    jlong jvalues[6];
    for (int i = 0; i < 6; i++) {
        jvalues[i] = (jlong) values[i];
    }
    jlongArray result = env->NewLongArray(6);
    env->SetLongArrayRegion(result, 0, 6, jvalues);
    return result;
}
