package it.smg.hu.projection;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceView;

import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import it.smg.libs.common.Log;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public class NativeVideoOutput extends VideoOutput implements Runnable, VideoStats.DecoderStats {

    private static final String TAG = "NativeVideoOutput";

    // how long the output thread waits for a decoded frame before rechecking running_
    private static final long DEQUEUE_TIMEOUT_US = 10000;

    private MediaCodec codec_;
    // shared between the protocol thread (write/stop) and the output thread
    private volatile boolean configured_;
    private volatile boolean running_;
    private Thread codecThread_;

    // for VideoStats: frames written to / rendered by the codec, and the peak backlog in between
    private final AtomicInteger written_ = new AtomicInteger();
    private final AtomicInteger rendered_ = new AtomicInteger();
    private final AtomicInteger renderedSinceStats_ = new AtomicInteger();
    private final AtomicInteger maxBacklog_ = new AtomicInteger();
    // arrival-to-screen: frame timestamp -> arrival time, and totals since the last stats call
    private final Map<Long, Long> arrivalMs_ = new HashMap<>();
    private long latencySumMs_;
    private int latencyCount_;
    private long latencyMaxMs_;

    public NativeVideoOutput(SurfaceView surfaceView){
        super(surfaceView);
    }

    @Override
    protected String tag() {
        return TAG;
    }

    @Keep
    @Override
    public boolean open() {
        return true;
    }

    @Keep
    @Override
    public boolean init() {
        try {
            Surface surface = surfaceView_.getHolder().getSurface();
            int width = surfaceView_.getWidth();
            int height = surfaceView_.getHeight();

            codecThread_ = new Thread(this);
            codecThread_.setName("NativeVideoOutput-thread");

            if (Log.isInfo()) Log.i(TAG, "create codec type= " + MediaFormat.MIMETYPE_VIDEO_AVC + ", width= " + width + ", height= " + height);
            codec_ = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps_);
//            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 655360);
            codec_.configure(format, surface, null, 0);
            configured_ = true;
            codec_.start();
            // set before the thread starts, otherwise it may see false and exit immediately
            running_ = true;
            codecThread_.start();
            VideoStats.instance().setDecoderStats(this);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Keep
    @Override
    public void write(long timestamp, ByteBuffer buf) {
        VideoStats.instance().onFrame(buf.limit());
        if (configured_ && running_) {
            int index = codec_.dequeueInputBuffer(3000000);
            if (index >= 0) {
                ByteBuffer buffer;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    buffer = codec_.getInputBuffers()[index];
                    buffer.clear();
                } else {
                    buffer = codec_.getInputBuffer(index);
                }
                if (buffer != null) {
                    buffer.put(buf);
                    buffer.flip();
                    synchronized (arrivalMs_) {
                        arrivalMs_.put(timestamp, SystemClock.uptimeMillis());
                        if (arrivalMs_.size() > 120) { // frames the codec never output
                            arrivalMs_.clear();
                        }
                    }
                    codec_.queueInputBuffer(index, 0, buf.limit(), timestamp, 0);
                    int backlog = written_.incrementAndGet() - rendered_.get();
                    if (backlog > maxBacklog_.get()) {
                        maxBacklog_.set(backlog);
                    }
                }
            }
        }
    }

    @Keep
    @Override
    public void stop() {
        if (Log.isInfo()) Log.i(TAG, "shutdown");
        VideoStats.instance().setDecoderStats(null);
        if (running_) {
            running_ = false;
            configured_ = false;
            try {
                codecThread_.join();
            } catch (InterruptedException ignored) {}

            codec_.flush();
            codec_.stop();
            codec_.release();
            codec_ = null;

        }
    }

    @Override
    public int[] takeDecoderStats() {
        int backlog = Math.max(0, written_.get() - rendered_.get());
        int latencyAvg, latencyMax;
        synchronized (arrivalMs_) {
            latencyAvg = latencyCount_ > 0 ? (int) (latencySumMs_ / latencyCount_) : 0;
            latencyMax = (int) latencyMaxMs_;
            latencySumMs_ = 0;
            latencyCount_ = 0;
            latencyMaxMs_ = 0;
        }
        return new int[]{renderedSinceStats_.getAndSet(0), Math.max(maxBacklog_.getAndSet(backlog), backlog), latencyAvg, latencyMax};
    }

    @Override
    public void run() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running_) {
            if (configured_) {
                // wait for a frame instead of spinning with a zero timeout
                int index = codec_.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (index >= 0) {
                    if (Log.isVerbose()) Log.v(TAG, "outputBufferIndex: " + index);
                    ByteBuffer buffer = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        buffer = codec_.getOutputBuffer(index);
                    } else {
                        buffer = codec_.getOutputBuffers()[index];
                    }
                    if (Log.isVerbose()) Log.v(TAG, "outputBuffer: " + buffer);

                    // setting true is telling system to render frame onto Surface
                    codec_.releaseOutputBuffer(index, true);
                    rendered_.incrementAndGet();
                    synchronized (arrivalMs_) {
                        Long arrival = arrivalMs_.remove(info.presentationTimeUs);
                        if (arrival != null) {
                            long latency = SystemClock.uptimeMillis() - arrival;
                            latencySumMs_ += latency;
                            latencyCount_++;
                            latencyMaxMs_ = Math.max(latencyMaxMs_, latency);
                        }
                    }
                    renderedSinceStats_.incrementAndGet();
                }
            }
        }
        if (Log.isVerbose()) Log.v(TAG, "thread closed");
    }
}
