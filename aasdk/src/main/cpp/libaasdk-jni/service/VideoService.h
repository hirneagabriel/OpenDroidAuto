#pragma once

#include <memory>
#include <chrono>
#include <boost/asio/steady_timer.hpp>
#include "channel/av/VideoServiceChannel.hpp"
#include "channel/av/IVideoServiceChannelEventHandler.hpp"
#include "projection/IVideoOutput.h"
#include "IService.hpp"
#include "IVideoEventHandler.h"
#include "IServiceEventHandler.h"

namespace service
{

class VideoService: public aasdk::channel::av::IVideoServiceChannelEventHandler, public IService, public std::enable_shared_from_this<VideoService>
{
public:
    typedef std::shared_ptr<VideoService> Pointer;

    VideoService(aasdk::io::ioService& ioService, aasdk::messenger::IMessenger::Pointer messenger, projection::IVideoOutput::Pointer videoOutput, IVideoEventHandler::Pointer eventHandler, IServiceEventHandler::Pointer serviceEventHandler);
    ~VideoService();

    void start() override;
    void stop() override;
    void fillFeatures(aasdk::proto::messages::ServiceDiscoveryResponse& response) override;
    void onChannelOpenRequest(const aasdk::proto::messages::ChannelOpenRequest& request) override;
    void onAVChannelSetupRequest(const aasdk::proto::messages::AVChannelSetupRequest& request) override;
    void onAVChannelStartIndication(const aasdk::proto::messages::AVChannelStartIndication& indication) override;
    void onAVChannelStopIndication(const aasdk::proto::messages::AVChannelStopIndication& indication) override;
    void onAVMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType timestamp, const aasdk::common::DataConstBuffer& buffer) override;
    void onAVMediaIndication(const aasdk::common::DataConstBuffer& buffer) override;
    void onVideoFocusRequest(const aasdk::proto::messages::VideoFocusRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;

private:
    using std::enable_shared_from_this<VideoService>::shared_from_this;
    void sendVideoFocusIndication();
    void sendVideoFocusReleaseIndication();
    void sendAck();
    void sendPendingAcks();
    void onAckTimer(const boost::system::error_code& error);

    aasdk::io::strand strand_;
    aasdk::channel::av::VideoServiceChannel::Pointer channel_;
    projection::IVideoOutput::Pointer videoOutput_;
    int32_t session_;
    service::IVideoEventHandler::Pointer eventHandler_;
    IServiceEventHandler::Pointer serviceEventHandler_;
    bool isRunning_;

    // ack pacing: with a frame rate limit, acks are spaced by ackInterval_ so the phone
    // (which waits for acks, see max_unacked) can't send frames faster than that
    boost::asio::steady_timer ackTimer_;
    std::chrono::steady_clock::duration ackInterval_;
    std::chrono::steady_clock::time_point nextAckTime_;
    uint32_t pendingAcks_;
    bool ackTimerArmed_;
};

}