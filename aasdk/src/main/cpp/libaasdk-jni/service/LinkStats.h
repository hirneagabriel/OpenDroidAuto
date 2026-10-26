#pragma once

#include <cstdint>

namespace service
{

// Timing of the link to the phone, read periodically by ResourceMonitor (Runtime.takeLinkStats()):
// ping round trips and how long touch events take from the app until they are written to the transport.
namespace linkstats
{

void recordPing(int64_t roundTripUs);
void recordTouchSent(int64_t latencyUs);

// {ping count, ping avg us, ping max us, touch count, touch avg us, touch max us} since the previous call
void take(int64_t out[6]);

}

}
