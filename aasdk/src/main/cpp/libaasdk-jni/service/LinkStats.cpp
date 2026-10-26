#include <mutex>
#include "service/LinkStats.h"

namespace service
{
namespace linkstats
{

namespace
{

struct Timing {
    int64_t count = 0;
    int64_t sumUs = 0;
    int64_t maxUs = 0;

    void add(int64_t us) {
        count++;
        sumUs += us;
        if (us > maxUs) {
            maxUs = us;
        }
    }
};

std::mutex mutex;
Timing ping;
Timing touch;

}

void recordPing(int64_t roundTripUs)
{
    std::lock_guard<std::mutex> lock(mutex);
    ping.add(roundTripUs);
}

void recordTouchSent(int64_t latencyUs)
{
    std::lock_guard<std::mutex> lock(mutex);
    touch.add(latencyUs);
}

void take(int64_t out[6])
{
    std::lock_guard<std::mutex> lock(mutex);
    out[0] = ping.count;
    out[1] = ping.count > 0 ? ping.sumUs / ping.count : 0;
    out[2] = ping.maxUs;
    out[3] = touch.count;
    out[4] = touch.count > 0 ? touch.sumUs / touch.count : 0;
    out[5] = touch.maxUs;
    ping = Timing();
    touch = Timing();
}

}
}
