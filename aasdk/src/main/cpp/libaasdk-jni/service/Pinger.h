#pragma once

#include "IPinger.h"

namespace service
{

class Pinger: public IPinger, public std::enable_shared_from_this<Pinger>
{
public:
    Pinger(aasdk::io::ioService& ioService, time_t duration);

    void ping(Promise::Pointer promise) override;
    void pong() override;
    void cancel() override;

private:
    using std::enable_shared_from_this<Pinger>::shared_from_this;

    void onTimerExceeded(const boost::system::error_code& error);

    aasdk::io::strand strand_;
    boost::asio::deadline_timer timer_;
    // pings the phone may leave unanswered before the session is closed (at 5 s each, ~20 s of silence)
    static constexpr int64_t cMaxMissedPongs = 4;

    time_t duration_;
    bool cancelled_;
    Promise::Pointer promise_;
    int64_t pingsCount_;
    int64_t pongsCount_;
};

}
