package it.smg.hu.projection;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import it.smg.libs.common.Log;

public class AudioCodec implements IAudioCodec {
    private static final String TAG = "AudioCodec";

    // how long the playback thread waits for data before rechecking running_
    private static final long POLL_TIMEOUT_MS = 100;
    // queued audio beyond this is dropped (oldest first) so latency can't grow without bound
    private static final int MAX_QUEUED_MS = 1000;
    // how long stop() waits for the playback thread before stopping the AudioTrack under it
    private static final long STOP_WAIT_MS = 500;

    // bytes dropped by all streams because playback fell behind, for ResourceMonitor
    public static final AtomicLong droppedBytes = new AtomicLong();

    private final String name_;
    // the current playback thread's track; stop() uses it to unblock a write() the car's audio routing stalled
    private volatile AudioTrack audioTrack_;
    private final int streamType_;
    private final int sampleRate_;
    private final int channelConfig_;
    private final int sampleSize_;
    // the current playback thread's flag; each thread has its own, so one left stuck in write() by stop()
    // can't keep playing into the next stream
    private AtomicBoolean running_;

    private final BlockingQueue<byte[]> queue_;
    private final AtomicInteger queuedBytes_;
    private final int maxQueuedBytes_;
    private Thread codecThread_;

    public AudioCodec(String name, int streamType, int sampleRate, int channelConfig, int sampleSize){
        name_ = name;
        streamType_ = streamType;
        sampleRate_ = sampleRate;
        channelConfig_ = channelConfig;
        sampleSize_ = sampleSize;
        running_ = new AtomicBoolean(false);

        queue_ = new LinkedBlockingQueue<>();
        queuedBytes_ = new AtomicInteger(0);
        maxQueuedBytes_ = sampleRate * channelConfig * (sampleSize / 8) * MAX_QUEUED_MS / 1000;
    }

    private static int channels2num(int channels){
        switch (channels){
            case 1:
                return AudioFormat.CHANNEL_OUT_MONO;
            case 2:
                return AudioFormat.CHANNEL_OUT_STEREO;
            case 4:
                return AudioFormat.CHANNEL_OUT_QUAD;
            case 6:
                return AudioFormat.CHANNEL_OUT_5POINT1;
            case 8:
                return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
            default:
                return -1;
        }
    }

    public static int sampleSizeFromInt(int sampleSize){
        switch (sampleSize){
            case 16:
                return AudioFormat.ENCODING_PCM_16BIT;
            case 8:
                return AudioFormat.ENCODING_PCM_8BIT;
            default:
                return -1;
        }
    }

    @Override
    public void write(ByteBuffer buffer, long timestamp) {
        if (Log.isVerbose()) Log.v(TAG, "buffer size: " + buffer.limit());
        final int size = buffer.limit();
        final byte[] data = new byte[size];
        buffer.get(data);

        queue_.add(data);
        queuedBytes_.addAndGet(size);

        // playback can't keep up: drop the oldest audio instead of falling further behind
        while (queuedBytes_.get() > maxQueuedBytes_) {
            byte[] dropped = queue_.poll();
            if (dropped == null) {
                break;
            }
            queuedBytes_.addAndGet(-dropped.length);
            droppedBytes.addAndGet(dropped.length);
            if (Log.isDebug()) Log.d(TAG + "_" + name_, "audio queue full, dropped " + dropped.length + " bytes");
        }
    }

    @Override
    public void start() {
        if (Log.isInfo()) Log.i(TAG, "Start");

        if (running_.get()) {
            return;
        }
        // set before the thread starts, otherwise it may see false and exit immediately
        final AtomicBoolean running = new AtomicBoolean(true);
        running_ = running;

        codecThread_ = new Thread(() -> play(running));
        codecThread_.setName(name_);
        codecThread_.start();
    }

    @Override
    public void stop() {
        if (Log.isInfo()) Log.i(TAG, "Stop");
        if (running_.get()) {
            running_.set(false);

            if (codecThread_ != null){
                try {
                    codecThread_.join(STOP_WAIT_MS);
                    if (codecThread_.isAlive()) {
                        // seen on the car: after the car muted our stream (e.g. back to its home screen) write()
                        // blocks for good, and this join used to hang the whole disconnect. Stopping the track
                        // makes the blocked write() return.
                        if (Log.isWarn()) Log.w(TAG + "_" + name_, "playback thread stuck in write, stopping the audio track");
                        AudioTrack track = audioTrack_;
                        if (track != null) {
                            try {
                                track.pause();
                                track.flush();
                                track.stop();
                            } catch (IllegalStateException ignored) {}
                        }
                        codecThread_.join(STOP_WAIT_MS);
                    }
                    if (codecThread_.isAlive()) {
                        // don't hold up the disconnect any longer; the thread ends once write() returns
                        if (Log.isWarn()) Log.w(TAG + "_" + name_, "playback thread still stuck, leaving it");
                    } else if (Log.isDebug()) {
                        Log.d(TAG + "_" + codecThread_.getName(), "thread joined");
                    }
                } catch (InterruptedException ignored) {}
                codecThread_ = null;
            }

            queue_.clear();
            queuedBytes_.set(0);
            if (Log.isDebug()) Log.d(TAG, "queue empty");
        }
    }

    @Override
    public void delete() {
    }

    private void play(AtomicBoolean running) {
        AudioTrack track;
        try {
            int bufferSize_ = AudioTrack.getMinBufferSize(sampleRate_, channels2num(channelConfig_), sampleSizeFromInt(sampleSize_));
            if (Log.isInfo()) Log.i(TAG, "Buffer size " + bufferSize_ + "*2");
            track = new AudioTrack(streamType_, sampleRate_, channels2num(channelConfig_), sampleSizeFromInt(sampleSize_), bufferSize_ * 3, AudioTrack.MODE_STREAM);
        } catch (Exception e) {
            Log.e(TAG, "error in audiotrack creation", e);
            running.set(false);
            return;
        }
        audioTrack_ = track;
        if (Log.isInfo()) Log.i(TAG, "initialized");

        if (Log.isInfo()) Log.i(TAG, "starting audiotrack");
        track.play();

        if (Log.isVerbose()) Log.v(TAG + "_" + name_, "running thread");
        while (running.get()) {
            byte[] data;
            try {
                // block until data arrives instead of spinning on an empty queue
                data = queue_.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (data != null && running.get()){
                queuedBytes_.addAndGet(-data.length);
                track.write(data, 0, data.length);
            }
        }

        if (Log.isInfo()) Log.i(TAG, "stop audiotrack");
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.flush();
                track.stop();
            }
        } catch (IllegalStateException ignored) {}
        track.release();
        if (audioTrack_ == track) {
            audioTrack_ = null;
        }

        if (Log.isInfo()) Log.i(TAG, "audiotrack released");
        if (Log.isVerbose()) Log.v(TAG + "_" + name_, "thread ended");
    }
}
