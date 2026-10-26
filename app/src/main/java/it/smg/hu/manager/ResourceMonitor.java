package it.smg.hu.manager;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.smg.hu.config.Settings;
import it.smg.hu.projection.AudioCodec;
import it.smg.hu.projection.InputStats;
import it.smg.hu.projection.VideoStats;
import it.smg.libs.common.Log;

/**
 * Periodically writes the app's CPU and memory usage to a CSV file, so it can be measured
 * on the head unit (no adb there): files go to <log dir>/stats on the USB stick (the log dir
 * is created when the stick is mounted), or to <external storage>/ODA/stats without a stick
 * (e.g. on the emulator). Mounting a stick later switches to it.
 *
 * CPU percentages are relative to one core (a process using two full cores reads 200).
 */
public class ResourceMonitor {

    private static final String TAG = "ResourceMonitor";

    private static final long INTERVAL_MS = 5000;
    private static final int TOP_THREADS = 5;
    private static final int TOP_PROCESSES = 5;
    private static final long LAG_PROBE_MS = 100;
    private static final int CLK_TCK = 100; // jiffies per second, fixed on Android kernels

    private static final String CSV_HEADER = "time,uptime_s,proc_cpu_pct,sys_cpu_pct,java_used_kb,java_max_kb,"
            + "native_alloc_kb,total_pss_kb,dalvik_pss_kb,native_pss_kb,other_pss_kb,sys_avail_kb,sys_low_memory,"
            + "video_fps,video_rendered_fps,video_kb_per_s,video_max_frame_kb,video_max_gap_ms,video_max_queued,"
            + "video_latency_avg_ms,video_latency_max_ms,"
            + "touch_events,touch_moves,touch_downs,touch_history,touch_age_avg_ms,touch_age_max_ms,touch_handoff_max_ms,"
            + "touch_sent,touch_send_avg_ms,touch_send_max_ms,touch_to_frame_n,touch_to_frame_avg_ms,touch_to_frame_max_ms,"
            + "touch_no_response,ping_n,ping_avg_ms,ping_max_ms,main_lag_max_ms,cpu_online,cpu_mhz,audio_dropped_kb,"
            + "threads,top_threads,top_processes";

    private static ResourceMonitor instance_;

    private final Context ctx_;
    private final ActivityManager activityManager_;
    private final SimpleDateFormat timeFormat_ = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private HandlerThread thread_;
    // reused for every /proc stat file read on the sampling thread (stat lines are well under 1 KB)
    private final byte[] statBuf_ = new byte[1024];
    private static final Charset US_ASCII = Charset.forName("US-ASCII");
    private Handler handler_;
    private PrintWriter csv_;
    private File outputDir_;

    private long startUptime_;
    private long lastWallMs_;
    private long lastProcJiffies_;
    private long lastSysTotal_;
    private long lastSysIdle_;
    private Map<String, Long> lastThreadJiffies_ = new HashMap<>();
    private Map<String, Long> lastProcessJiffies_ = new HashMap<>();

    // main thread stalls (touch events are handled there): a probe posted every LAG_PROBE_MS
    // records how late it runs
    private final Handler mainHandler_ = new Handler(Looper.getMainLooper());
    private long lagProbeDueMs_;
    private volatile long mainLagMaxMs_;
    private final Runnable lagProbe_ = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            long lag = now - lagProbeDueMs_;
            if (lag > mainLagMaxMs_){
                mainLagMaxMs_ = lag;
            }
            lagProbeDueMs_ = now + LAG_PROBE_MS;
            mainHandler_.postAtTime(this, lagProbeDueMs_);
        }
    };

    private ResourceMonitor(Context ctx){
        ctx_ = ctx;
        activityManager_ = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);

        IntentFilter mountFilter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        mountFilter.addDataScheme("file");
        ctx.registerReceiver(mountReceiver_, mountFilter);
    }

    private final BroadcastReceiver mountReceiver_ = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isInfo()) Log.i(TAG, "media mounted: " + intent.getData());
            switchToLogDir();
        }
    };

    /** Restarts on the USB stick if it was mounted after sampling started on the device storage. */
    private synchronized void switchToLogDir(){
        if (thread_ == null || isLogDirOutput()){
            return;
        }
        File dir = outputDir();
        if (dir != null && !dir.equals(outputDir_)){
            stop();
            start();
        }
    }

    private boolean isLogDirOutput(){
        File logDir = new File(Settings.instance().advanced.logDir());
        return outputDir_ != null && logDir.equals(outputDir_.getParentFile());
    }

    public static void init(Context ctx){
        if (instance_ == null){
            instance_ = new ResourceMonitor(ctx);
        }
        instance_.update();
    }

    public static ResourceMonitor instance(){
        return instance_;
    }

    /** Starts or stops sampling according to the current setting. */
    public synchronized void update(){
        if (Settings.instance().advanced.resourceMonitorEnabled()){
            start();
        } else {
            stop();
        }
    }

    private void start(){
        if (thread_ != null){
            return;
        }

        File dir = outputDir();
        if (dir == null){
            Log.w(TAG, "no writable directory, resource monitor not started");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            writeDeviceInfo(new File(dir, "device_" + stamp + ".txt"));
            csv_ = new PrintWriter(new FileWriter(new File(dir, "stats_" + stamp + ".csv")));
            csv_.println(CSV_HEADER);
            csv_.flush();
            outputDir_ = dir;
        } catch (IOException e){
            Log.e(TAG, "error creating stats files in " + dir, e);
            closeCsv();
            return;
        }

        startUptime_ = SystemClock.elapsedRealtime();
        lastWallMs_ = 0;
        lastThreadJiffies_.clear();

        thread_ = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread_.start();
        handler_ = new Handler(thread_.getLooper());
        handler_.post(sampler_);
        lagProbeDueMs_ = SystemClock.uptimeMillis() + LAG_PROBE_MS;
        mainHandler_.postAtTime(lagProbe_, lagProbeDueMs_);

        if (Log.isInfo()) Log.i(TAG, "started, writing to " + dir);
    }

    private void stop(){
        if (thread_ == null){
            return;
        }

        handler_.removeCallbacksAndMessages(null);
        mainHandler_.removeCallbacks(lagProbe_);
        // quitSafely() is API 18, so close and quit from the sampling thread itself; close this
        // run's writer, not csv_, which a following start() may already have replaced
        final HandlerThread thread = thread_;
        final PrintWriter csv = csv_;
        handler_.post(() -> {
            csv.close();
            thread.quit();
        });
        thread_ = null;
        handler_ = null;
        csv_ = null;
        outputDir_ = null;

        if (Log.isInfo()) Log.i(TAG, "stopped");
    }

    private void closeCsv(){
        if (csv_ != null){
            csv_.close();
            csv_ = null;
        }
    }

    private File outputDir(){
        // the log dir is on the USB stick; create it only when the stick is really mounted
        // (the empty mount point exists without one), otherwise use the device storage
        File logDir = new File(Settings.instance().advanced.logDir());
        File stats = writableDir(new File(logDir, "stats"), logDir.isDirectory() || isMountPoint(logDir.getParentFile()));
        if (stats == null){
            if (Log.isWarn()) Log.w(TAG, "log dir " + logDir + " not available (USB stick not mounted?), using device storage");
            stats = writableDir(new File(new File(Environment.getExternalStorageDirectory(), "ODA"), "stats"), true);
        }
        return stats;
    }

    private static File writableDir(File dir, boolean allowCreate){
        if ((dir.isDirectory() || (allowCreate && dir.mkdirs())) && dir.canWrite()){
            return dir;
        }
        return null;
    }

    /** Whether dir is listed in /proc/mounts (directly or through a symlink). */
    private static boolean isMountPoint(File dir){
        if (dir == null){
            return false;
        }
        String path;
        try {
            path = dir.getCanonicalPath();
        } catch (IOException e){
            return false;
        }
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = in.readLine()) != null){
                String[] f = line.split(" ");
                if (f.length > 1 && (f[1].equals(path) || f[1].equals(dir.getPath()))){
                    return true;
                }
            }
        } catch (IOException e){
            Log.e(TAG, "error reading /proc/mounts", e);
        } finally {
            closeQuietly(in);
        }
        return false;
    }

    private final Runnable sampler_ = new Runnable() {
        @Override
        public void run() {
            try {
                sample();
            } catch (Exception e){
                Log.e(TAG, "sample error", e);
            }
            // only re-post on this sampling thread's own handler: after a restart handler_ belongs
            // to the new thread, and re-posting there would run two sampling loops into one file
            Handler handler = handler_;
            if (handler != null && handler.getLooper() == Looper.myLooper()){
                handler.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    private void sample(){
        // local copy: stop() may replace csv_ while this run finishes on the sampling thread
        PrintWriter csv = csv_;
        if (csv == null){
            return;
        }

        long nowMs = SystemClock.elapsedRealtime();
        double elapsedJiffies = (nowMs - lastWallMs_) * CLK_TCK / 1000.0;
        boolean first = lastWallMs_ == 0;
        double elapsedS = (nowMs - lastWallMs_) / 1000.0;

        // process CPU
        long procJiffies = procJiffies("/proc/self/stat");
        String procCpu = first ? "" : pct(procJiffies - lastProcJiffies_, elapsedJiffies);
        lastProcJiffies_ = procJiffies;

        // system CPU (all cores)
        String sysCpu = "";
        long[] sys = sysCpuJiffies();
        if (sys != null){
            if (!first && sys[0] > lastSysTotal_){
                long busy = (sys[0] - lastSysTotal_) - (sys[1] - lastSysIdle_);
                sysCpu = String.format(Locale.US, "%.1f", 100.0 * busy / (sys[0] - lastSysTotal_));
            }
            lastSysTotal_ = sys[0];
            lastSysIdle_ = sys[1];
        }

        // per-thread CPU, busiest first
        Map<String, Long> threadJiffies = threadJiffies();
        List<Map.Entry<String, Long>> deltas = new ArrayList<>();
        for (Map.Entry<String, Long> t : threadJiffies.entrySet()){
            Long last = lastThreadJiffies_.get(t.getKey());
            if (last != null && t.getValue() > last){
                deltas.add(new AbstractMap.SimpleEntry<>(t.getKey(), t.getValue() - last));
            }
        }
        Collections.sort(deltas, (a, b) -> b.getValue().compareTo(a.getValue()));
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(TOP_THREADS, deltas.size()); i++){
            Map.Entry<String, Long> t = deltas.get(i);
            if (top.length() > 0) top.append('|');
            // key is "tid:name"; report the name only
            top.append(clean(t.getKey().substring(t.getKey().indexOf(':') + 1))).append(':').append(pct(t.getValue(), elapsedJiffies));
        }
        lastThreadJiffies_ = threadJiffies;

        // busiest processes system-wide (others' /proc entries are readable on Android 4.x)
        Map<String, Long> processJiffies = processJiffies();
        List<Map.Entry<String, Long>> processDeltas = new ArrayList<>();
        for (Map.Entry<String, Long> p : processJiffies.entrySet()){
            Long last = lastProcessJiffies_.get(p.getKey());
            if (last != null && p.getValue() > last){
                processDeltas.add(new AbstractMap.SimpleEntry<>(p.getKey(), p.getValue() - last));
            }
        }
        Collections.sort(processDeltas, (a, b) -> b.getValue().compareTo(a.getValue()));
        StringBuilder topProcesses = new StringBuilder();
        for (int i = 0; i < Math.min(TOP_PROCESSES, processDeltas.size()); i++){
            Map.Entry<String, Long> p = processDeltas.get(i);
            if (topProcesses.length() > 0) topProcesses.append('|');
            topProcesses.append(clean(p.getKey().substring(p.getKey().indexOf(':') + 1))).append(':').append(pct(p.getValue(), elapsedJiffies));
        }
        lastProcessJiffies_ = processJiffies;
        lastWallMs_ = nowMs;

        // memory
        Runtime rt = Runtime.getRuntime();
        long javaUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024;
        long javaMaxKb = rt.maxMemory() / 1024;
        long nativeAllocKb = Debug.getNativeHeapAllocatedSize() / 1024;

        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);

        ActivityManager.MemoryInfo sysMem = new ActivityManager.MemoryInfo();
        activityManager_.getMemoryInfo(sysMem);

        // video frames received since the previous sample
        VideoStats.Snapshot video = VideoStats.instance().snapshot();
        String videoCols = first ? ",,,,,," : String.format(Locale.US, "%.1f,%s,%.1f,%d,%d,%s,",
                video.frames / elapsedS,
                video.renderedFrames >= 0 ? String.format(Locale.US, "%.1f", video.renderedFrames / elapsedS) : "",
                video.bytes / 1024.0 / elapsedS, video.maxFrameBytes / 1024, video.maxGapMs,
                video.maxQueued >= 0 ? String.valueOf(video.maxQueued) : "");

        String latencyCols = first || video.latencyAvgMs < 0 ? ",," : video.latencyAvgMs + "," + video.latencyMaxMs + ",";

        InputStats.Snapshot touch = InputStats.instance().snapshot();
        String touchCols = touch.events + "," + touch.moves + "," + touch.downs + "," + touch.historySamples + ","
                + touch.ageAvgMs + "," + touch.ageMaxMs + "," + String.format(Locale.US, "%.1f", touch.handOffMaxUs / 1000.0) + ",";

        // touch sending and ping round trips, from the native side
        String linkCols = ",,,,,,";
        try {
            long[] link = it.smg.libs.aasdk.Runtime.takeLinkStats();
            linkCols = link[3] + "," + String.format(Locale.US, "%.1f,%.1f,", link[4] / 1000.0, link[5] / 1000.0)
                    + touch.responses + "," + touch.responseAvgMs + "," + touch.responseMaxMs + ","
                    + touch.noResponse + ","
                    + link[0] + "," + String.format(Locale.US, "%.1f,%.1f,", link[1] / 1000.0, link[2] / 1000.0);
        } catch (Throwable e){
            linkCols = ",,," + touch.responses + "," + touch.responseAvgMs + "," + touch.responseMaxMs + "," + touch.noResponse + ",,,,";
        }

        long mainLag = mainLagMaxMs_;
        mainLagMaxMs_ = 0;

        csv.println(timeFormat_.format(new Date()) + ","
                + (nowMs - startUptime_) / 1000 + ","
                + procCpu + ","
                + sysCpu + ","
                + javaUsedKb + ","
                + javaMaxKb + ","
                + nativeAllocKb + ","
                + mi.getTotalPss() + ","
                + mi.dalvikPss + ","
                + mi.nativePss + ","
                + mi.otherPss + ","
                + sysMem.availMem / 1024 + ","
                + sysMem.lowMemory + ","
                + videoCols
                + latencyCols
                + touchCols
                + linkCols
                + mainLag + ","
                + clean(readFirstLine("/sys/devices/system/cpu/online")) + ","
                + cpuMhz() + ","
                + AudioCodec.droppedBytes.getAndSet(0) / 1024 + ","
                + threadJiffies.size() + ","
                + top + ","
                + topProcesses);
        csv.flush();
    }

    /** Keeps names and values from breaking the CSV. */
    private static String clean(String s){
        return s == null ? "" : s.replace(',', ';').replace('|', '/').trim();
    }

    /** Current clock of each core in MHz, e.g. "900/900/-/-" ("-" = offline). */
    private static String cpuMhz(){
        StringBuilder sb = new StringBuilder();
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < Math.max(cores, 4); i++){
            String khz = readFirstLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (khz == null && i >= cores){
                break;
            }
            if (sb.length() > 0) sb.append('/');
            try {
                sb.append(khz != null ? String.valueOf(Long.parseLong(khz.trim()) / 1000) : "-");
            } catch (NumberFormatException e){
                sb.append('?');
            }
        }
        return sb.toString();
    }

    /** utime + stime per process, keyed by "pid:name". */
    private Map<String, Long> processJiffies(){
        Map<String, Long> result = new HashMap<>();
        String[] pids = new File("/proc").list();
        if (pids == null) return result;
        for (String pid : pids){
            if (pid.isEmpty() || !Character.isDigit(pid.charAt(0))) continue;
            putStat(result, pid, "/proc/" + pid + "/stat");
        }
        return result;
    }

    private static String pct(long jiffies, double elapsedJiffies){
        return String.format(Locale.US, "%.1f", 100.0 * jiffies / elapsedJiffies);
    }

    /** utime + stime from a /proc/.../stat file, or 0 when it can't be read. */
    private long procJiffies(String statPath){
        int len = readStat(statPath);
        return len > 0 ? statJiffies(len) : 0;
    }

    /**
     * Reads a /proc stat file once into statBuf_ and adds "id:name" -> utime + stime. With ~200 processes and their
     * threads read every sample, this avoids the per-file reader buffers and split() that used to make ~10 MB of
     * garbage per sample on the car.
     */
    private void putStat(Map<String, Long> result, String id, String statPath){
        int len = readStat(statPath);
        if (len <= 0) return;
        int open = indexOf(len, (byte) '(', true);
        int close = indexOf(len, (byte) ')', false);
        if (open < 0 || close <= open) return;
        long jiffies = statJiffies(len);
        if (jiffies >= 0){
            result.put(id + ":" + new String(statBuf_, open + 1, close - open - 1, US_ASCII), jiffies);
        }
    }

    /** Reads up to statBuf_.length bytes of a small /proc file; returns the length, or -1. Sampling thread only. */
    private int readStat(String path){
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            int len = 0;
            int n;
            while (len < statBuf_.length && (n = in.read(statBuf_, len, statBuf_.length - len)) > 0){
                len += n;
            }
            return len;
        } catch (IOException e){
            return -1;
        } finally {
            if (in != null){
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    private int indexOf(int len, byte b, boolean first){
        if (first){
            for (int i = 0; i < len; i++) if (statBuf_[i] == b) return i;
        } else {
            for (int i = len - 1; i >= 0; i--) if (statBuf_[i] == b) return i;
        }
        return -1;
    }

    /**
     * utime + stime (fields 14 and 15) from the stat line in statBuf_. The comm field may contain spaces, so count
     * fields from after the last ')': "state" is the first, utime the 12th. Returns -1 if the line is malformed.
     */
    private long statJiffies(int len){
        int i = indexOf(len, (byte) ')', false);
        if (i < 0) return -1;
        i += 2; // ") " -> start of state
        for (int field = 0; field < 11; field++){
            while (i < len && statBuf_[i] != ' ') i++;
            i++;
        }
        long utime = 0;
        while (i < len && statBuf_[i] >= '0' && statBuf_[i] <= '9') utime = utime * 10 + (statBuf_[i++] - '0');
        i++;
        long stime = 0;
        while (i < len && statBuf_[i] >= '0' && statBuf_[i] <= '9') stime = stime * 10 + (statBuf_[i++] - '0');
        return i <= len ? utime + stime : -1;
    }

    /** {total, idle} jiffies from the aggregate cpu line of /proc/stat. */
    private static long[] sysCpuJiffies(){
        String line = readFirstLine("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) return null;
        String[] f = line.trim().split("\\s+");
        long total = 0;
        for (int i = 1; i < f.length; i++){
            total += Long.parseLong(f[i]);
        }
        long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0); // idle + iowait
        return new long[]{total, idle};
    }

    private Map<String, Long> threadJiffies(){
        Map<String, Long> result = new HashMap<>();
        String[] tids = new File("/proc/self/task").list();
        if (tids == null) return result;
        for (String tid : tids){
            putStat(result, tid, "/proc/self/task/" + tid + "/stat");
        }
        return result;
    }

    private void writeDeviceInfo(File file) throws IOException {
        PrintWriter out = new PrintWriter(new FileWriter(file));
        try {
            out.println("model: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ", " + Build.HARDWARE + ")");
            out.println("android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            out.println("abi: " + Build.CPU_ABI + " " + Build.CPU_ABI2);
            out.println("cores: " + Runtime.getRuntime().availableProcessors());
            String maxFreq = readFirstLine("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
            out.println("cpu0 max freq khz: " + maxFreq);

            DisplayMetrics dm = ctx_.getResources().getDisplayMetrics();
            out.println("display: " + dm.widthPixels + "x" + dm.heightPixels + " density " + dm.densityDpi + " dpi");

            out.println("memory class mb: " + activityManager_.getMemoryClass() + " (large " + activityManager_.getLargeMemoryClass() + ")");
            out.println("java max heap kb: " + Runtime.getRuntime().maxMemory() / 1024);

            out.println();
            out.println("--- /proc/meminfo");
            copyFile("/proc/meminfo", out, 6);
            out.println();
            out.println("--- /proc/cpuinfo");
            copyFile("/proc/cpuinfo", out, Integer.MAX_VALUE);
        } finally {
            out.close();
        }
    }

    private static void copyFile(String path, PrintWriter out, int maxLines){
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(path));
            String line;
            for (int i = 0; i < maxLines && (line = in.readLine()) != null; i++){
                out.println(line);
            }
        } catch (IOException e){
            out.println("unreadable: " + e.getMessage());
        } finally {
            closeQuietly(in);
        }
    }

    private static String readFirstLine(String path){
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(path));
            return in.readLine();
        } catch (IOException e){
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(BufferedReader in){
        if (in != null){
            try {
                in.close();
            } catch (IOException ignored) {}
        }
    }
}
