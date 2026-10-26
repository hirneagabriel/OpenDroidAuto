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
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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
    private static final int CLK_TCK = 100; // jiffies per second, fixed on Android kernels

    private static final String CSV_HEADER = "time,uptime_s,proc_cpu_pct,sys_cpu_pct,java_used_kb,java_max_kb,"
            + "native_alloc_kb,total_pss_kb,dalvik_pss_kb,native_pss_kb,other_pss_kb,sys_avail_kb,sys_low_memory,"
            + "video_fps,video_rendered_fps,video_kb_per_s,video_max_frame_kb,video_max_gap_ms,video_max_queued,"
            + "video_max_unacked,video_fps_limit,"
            + "threads,top_threads";

    private static ResourceMonitor instance_;

    private final Context ctx_;
    private final ActivityManager activityManager_;
    private final SimpleDateFormat timeFormat_ = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private HandlerThread thread_;
    private Handler handler_;
    private PrintWriter csv_;
    private File outputDir_;

    private long startUptime_;
    private long lastWallMs_;
    private long lastProcJiffies_;
    private long lastSysTotal_;
    private long lastSysIdle_;
    private Map<String, Long> lastThreadJiffies_ = new HashMap<>();

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

        if (Log.isInfo()) Log.i(TAG, "started, writing to " + dir);
    }

    private void stop(){
        if (thread_ == null){
            return;
        }

        handler_.removeCallbacksAndMessages(null);
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
            if (handler_ != null){
                handler_.postDelayed(this, INTERVAL_MS);
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
            top.append(t.getKey().substring(t.getKey().indexOf(':') + 1)).append(':').append(pct(t.getValue(), elapsedJiffies));
        }
        lastThreadJiffies_ = threadJiffies;
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
                + video.maxUnackedFrames + ","
                + video.frameRateLimit + ","
                + threadJiffies.size() + ","
                + top);
        csv.flush();
    }

    private static String pct(long jiffies, double elapsedJiffies){
        return String.format(Locale.US, "%.1f", 100.0 * jiffies / elapsedJiffies);
    }

    /** utime + stime from a /proc/.../stat file; the comm field may contain spaces, so parse after the last ')'. */
    private static long procJiffies(String statPath){
        String stat = readFirstLine(statPath);
        if (stat == null) return 0;
        String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        // fields after comm start at "state" (field 3); utime is field 14, stime field 15
        return Long.parseLong(f[11]) + Long.parseLong(f[12]);
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

    private static Map<String, Long> threadJiffies(){
        Map<String, Long> result = new HashMap<>();
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) return result;
        for (File task : tasks){
            String stat = readFirstLine(task.getPath() + "/stat");
            if (stat == null) continue;
            String name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'));
            result.put(task.getName() + ":" + name, procJiffies(task.getPath() + "/stat"));
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
