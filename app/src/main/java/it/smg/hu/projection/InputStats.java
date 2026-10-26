package it.smg.hu.projection;

import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * Counts touch input for ResourceMonitor: how often the touchscreen reports, how old events are
 * when the app gets them, how long handing them to the native side takes, and the response time:
 * from a touch on a still screen (e.g. starting a pan or tapping a button) to the first video frame
 * from the phone, i.e. the delay the user feels minus the head unit's own decode/display time.
 * While the screen is already moving the next frame says nothing about the touch, so those
 * touches aren't timed.
 */
public class InputStats {

    // a touch with no new frame within this time got no visible response (e.g. a tap on nothing)
    private static final long NO_RESPONSE_MS = 1000;
    // the screen counts as still when no frame arrived for this long
    private static final long STILL_MS = 150;

    private static final InputStats instance_ = new InputStats();

    public static InputStats instance(){
        return instance_;
    }

    public static class Snapshot {
        public final int events;
        public final int moves;
        public final int downs;
        public final int historySamples;
        public final long ageAvgMs;
        public final long ageMaxMs;
        public final long handOffMaxUs;
        public final int responses;
        public final long responseAvgMs;
        public final long responseMaxMs;
        public final int noResponse;

        private Snapshot(int events, int moves, int downs, int historySamples, long ageAvgMs, long ageMaxMs,
                         long handOffMaxUs, int responses, long responseAvgMs, long responseMaxMs, int noResponse){
            this.events = events;
            this.moves = moves;
            this.downs = downs;
            this.historySamples = historySamples;
            this.ageAvgMs = ageAvgMs;
            this.ageMaxMs = ageMaxMs;
            this.handOffMaxUs = handOffMaxUs;
            this.responses = responses;
            this.responseAvgMs = responseAvgMs;
            this.responseMaxMs = responseMaxMs;
            this.noResponse = noResponse;
        }
    }

    private int events_;
    private int moves_;
    private int downs_;
    private int historySamples_;
    private long ageSumMs_;
    private long ageMaxMs_;
    private long handOffMaxUs_;
    private int responses_;
    private long responseSumMs_;
    private long responseMaxMs_;
    private int noResponse_;
    // touch on a still screen not yet followed by a video frame, 0 if none
    private long pendingTouchMs_;
    private long lastFrameMs_;

    private InputStats(){}

    /** A touch event received from the touchscreen and passed on in handOffNs. */
    public synchronized void onTouch(MotionEvent event, long handOffNs){
        long now = SystemClock.uptimeMillis();
        events_++;
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_MOVE:
                moves_++;
                break;
            case MotionEvent.ACTION_DOWN:
                downs_++;
                break;
            default:
                break;
        }
        historySamples_ += event.getHistorySize();

        // event time is uptimeMillis when the touchscreen reported it
        long age = now - event.getEventTime();
        ageSumMs_ += age;
        ageMaxMs_ = Math.max(ageMaxMs_, age);
        handOffMaxUs_ = Math.max(handOffMaxUs_, handOffNs / 1000);

        expirePendingTouch(now);
        if (pendingTouchMs_ == 0 && now - lastFrameMs_ > STILL_MS){
            pendingTouchMs_ = now;
        }
    }

    /** A video frame arrived from the phone. */
    public synchronized void onVideoFrame(){
        long now = SystemClock.uptimeMillis();
        lastFrameMs_ = now;
        expirePendingTouch(now);
        if (pendingTouchMs_ != 0){
            long response = now - pendingTouchMs_;
            responses_++;
            responseSumMs_ += response;
            responseMaxMs_ = Math.max(responseMaxMs_, response);
            pendingTouchMs_ = 0;
        }
    }

    private void expirePendingTouch(long now){
        if (pendingTouchMs_ != 0 && now - pendingTouchMs_ > NO_RESPONSE_MS){
            noResponse_++;
            pendingTouchMs_ = 0;
        }
    }

    /** Returns the counts since the previous snapshot and resets them. */
    public synchronized Snapshot snapshot(){
        Snapshot s = new Snapshot(events_, moves_, downs_, historySamples_,
                events_ > 0 ? ageSumMs_ / events_ : 0, ageMaxMs_, handOffMaxUs_,
                responses_, responses_ > 0 ? responseSumMs_ / responses_ : 0, responseMaxMs_, noResponse_);
        events_ = 0;
        moves_ = 0;
        downs_ = 0;
        historySamples_ = 0;
        ageSumMs_ = 0;
        ageMaxMs_ = 0;
        handOffMaxUs_ = 0;
        responses_ = 0;
        responseSumMs_ = 0;
        responseMaxMs_ = 0;
        noResponse_ = 0;
        return s;
    }
}
