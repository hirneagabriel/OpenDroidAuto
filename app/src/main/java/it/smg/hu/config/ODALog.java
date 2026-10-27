package it.smg.hu.config;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import it.smg.libs.common.ILog;

public class ODALog implements ILog {

    private static final String PACKAGE_NAME = "it.smg.hu";
    private static final String TAG = "ODALog";

    private final Settings settings_;
    // restarting logcat sleeps ~1.8 s; reload() and shutdown() are called from the UI, so do it off the main thread
    private final ExecutorService logcatExecutor_ = Executors.newSingleThreadExecutor(r -> new Thread(r, "ODALog"));
    private volatile Process logProcess_;
    // the car keeps writes to the stick in memory for up to ~30 s and loses them when it hangs or loses power (a
    // whole drive's log and stats were missing after the unit hung), so flush them while logging
    private static final long SYNC_INTERVAL_S = 1;
    private ScheduledExecutorService syncExecutor_;
    // waits on logcatExecutor_ for the log dir: after the car wakes up the app can start before the USB stick is mounted
    private static final long WAIT_FOR_LOG_DIR_MS = 120000;
    private int logLevel_;
    private boolean logProtocol_;

    public ODALog(){
        settings_ = Settings.instance();
        setLogLevel_(settings_.advanced.logLevel());
        setLogProtocol_(settings_.advanced.logProtocol());

        initProcessLog();
    }

    @Override
    public int logLevel_() {
        return logLevel_;
    }

    @Override
    public void setLogLevel_(int logLevel){
        logLevel_ = logLevel;
    }

    @Override
    public void setLogProtocol_(boolean logProtocol){
        logProtocol_ = logProtocol;
    }

    @Override
    public boolean logProtocol_(){
        return logProtocol_;
    }

    @Override
    public void reload() {
        logcatExecutor_.execute(this::initProcessLog);
    }

    @Override
    public void shutdown() {
        logcatExecutor_.execute(this::_deInit);
    }

    private void initProcessLog(){
        initProcessLog(true);
    }

    /** @param clear empty logcat's buffer first; false keeps what this run logged while waiting for the stick */
    private void initProcessLog(boolean clear){
        if (logProcess_ != null){
            _deInit();
        }
        killOrphanLogcats();

        if (settings_.advanced.enabledDebug()) {
            v_(TAG, "log enabled");
            if (isExternalStorageWritable()) {
                v_(TAG, "external storage writable");
                String extStoragePath = settings_.advanced.logDir();

                if (!extStoragePath.trim().isEmpty()) {
                    File extStorageDir = new File(extStoragePath);
                    if (!extStorageDir.exists() && clear) {
                        w_(TAG, "log dir " + extStorageDir + " not there yet, waiting for it");
                        logcatExecutor_.execute(() -> waitForLogDir(extStorageDir));
                    }
                    if (extStorageDir.exists()){
                        File logDirectory = new File(extStorageDir, "logs");
                        File logFile = new File(logDirectory, "logcat_" + System.currentTimeMillis() + ".txt");

                        // create log folder
                        if (!logDirectory.exists()) {
                            if (!logDirectory.mkdir()){
                                w_(TAG, "log directory " + logDirectory + " doesn't created");
                                return;
                            }
                        }

                        copyCrashReports(logDirectory);

                        // clear the previous logcat and then write the new one to the file
                        try {
                            v_(TAG, "start logcat process");
                            if (clear) {
                                Runtime.getRuntime().exec("logcat -c");
                                Thread.sleep(100);
                            }
                            String cmd = "logcat -v threadtime -f " + logFile + " " + PACKAGE_NAME + ":V";
                            logProcess_ = Runtime.getRuntime().exec(cmd);
                            Thread.sleep(200);
                            v_(TAG, "started log process with cmd: " + cmd);
                            startSyncing();
                        } catch (IOException e) {
                            e_(TAG, "error in start log process", e);
                            _deInit();
                        } catch (InterruptedException ignored) {
                            _deInit();
                        }
                    }
                }
            } else if (isExternalStorageReadable()) {
                w_(TAG, "log storage readonly");
            } else {
                w_(TAG, "log storage not accessible");
            }
        }
    }

    private synchronized void startSyncing(){
        if (syncExecutor_ != null) return;
        final Process shell;
        try {
            // one shell fed "sync" each second: cheaper than starting a process from the app every time, and it
            // exits by itself (end of input) when the app is killed, unlike logcat
            shell = Runtime.getRuntime().exec("sh");
        } catch (IOException e) {
            w_(TAG, "can't start syncing the log", e);
            return;
        }
        final java.io.OutputStream commands = shell.getOutputStream();
        syncExecutor_ = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "ODALog sync"));
        syncExecutor_.scheduleWithFixedDelay(() -> {
            if (logProcess_ == null) return;
            try {
                commands.write("sync\n".getBytes());
                commands.flush();
            } catch (IOException ignored) {}
        }, SYNC_INTERVAL_S, SYNC_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void waitForLogDir(File dir){
        long deadline = System.currentTimeMillis() + WAIT_FOR_LOG_DIR_MS;
        try {
            while (!dir.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            return;
        }
        if (dir.exists() && logProcess_ == null) {
            i_(TAG, "log dir " + dir + " appeared, starting the log");
            initProcessLog(false);
        }
    }

    /**
     * Copies the system's crash reports to the stick, once each: the car's logcat is wiped when it restarts, but
     * these survive it. traces.txt holds the thread dumps of the last ANR or system watchdog (a hung system shows
     * as no sound, a black screen, then the boot logo).
     */
    private void copyCrashReports(File logDirectory){
        copyOnce(new File("/data/anr/traces.txt"), logDirectory, "anr_traces_");
        copyOnce(new File("/proc/last_kmsg"), logDirectory, "last_kmsg_");
    }

    private void copyOnce(File source, File logDirectory, String prefix){
        if (!source.canRead()) return;
        // /proc files have no meaningful time or size: name those by today's first copy only
        long stamp = source.lastModified();
        File target = new File(logDirectory, prefix + (stamp > 0 ? stamp : System.currentTimeMillis() / 86400000L) + ".txt");
        if (target.exists()) return;
        try (java.io.InputStream in = new java.io.FileInputStream(source);
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            i_(TAG, "copied " + source + " to " + target);
        } catch (IOException e) {
            w_(TAG, "can't copy " + source, e);
        }
    }

    /**
     * Kills the logcat processes earlier runs of the app left behind. logcat is a child process: when the app is
     * killed (the car force-stops it when another source starts, or it crashes) it keeps running and writing its
     * file, and every new run adds one more (9 at once on the car). They run as our uid, so we may kill them.
     */
    private void killOrphanLogcats(){
        int myUid = android.os.Process.myUid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;

        for (File proc : procs) {
            String name = proc.getName();
            if (!Character.isDigit(name.charAt(0))) continue;
            int pid = Integer.parseInt(name);
            if (pid == android.os.Process.myPid()) continue;

            String cmdline = readFirstLine(new File(proc, "cmdline"));
            if (cmdline == null || !cmdline.contains("logcat ") || !cmdline.contains("/logs/logcat_")) continue;
            if (uidOf(proc) != myUid) continue;

            w_(TAG, "killing logcat " + pid + " left by an earlier run");
            android.os.Process.killProcess(pid);
        }
    }

    private static int uidOf(File proc){
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(proc, "status")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    return Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                }
            }
        } catch (IOException | RuntimeException ignored) {}
        return -1;
    }

    private static String readFirstLine(File file){
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            // cmdline separates the arguments with NULs
            return line != null ? line.replace('\0', ' ') : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    /* Checks if external storage is available for read and write */
    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    /* Checks if external storage is available to at least read */
    private boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    private void _deInit(){
        if (logProcess_ != null){
            try {
                v_(TAG, "Destroy log process");
                Thread.sleep(1500);
                logProcess_.destroy();
                v_(TAG, "Log process destroyed");
                logProcess_ = null;
            } catch (InterruptedException ignored) {}
        }
    }

    @Override
    public boolean isVerbose_(){
        return logLevel_ <= Log.VERBOSE;
    }

    @Override
    public boolean isDebug_(){
        return logLevel_ <= Log.DEBUG;
    }

    @Override
    public boolean isInfo_(){
        return logLevel_ <= Log.INFO;
    }

    @Override
    public boolean isWarn_(){
        return logLevel_ <= Log.WARN;
    }

    @Override
    public boolean isError_(){
        return logLevel_ <= Log.ERROR;
    }

    private void log(int level, String tag, String message){
        log(level, tag, message, null);
    }

    private void log(int level, String tag, String message, Throwable t){
        doLog(level, tag, message, t);
    }

    private void doLog(int level, String tag, String message, Throwable t){
        switch (level){
            case Log.VERBOSE:
                Log.v("ODA/" + tag, message, t);
                break;
            case Log.DEBUG:
                Log.d("ODA/" + tag, message, t);
                break;
            case Log.WARN:
                Log.w("ODA/" + tag, message, t);
                break;
            case Log.ERROR:
                Log.e("ODA/" + tag, message, t);
                break;
            case Log.INFO:
            default:
                Log.i("ODA/" + tag, message, t);
                break;
        }
    }

    @Override
    public void v_(String tag, byte[] message){
        if (isVerbose_()) {
            String msg = bytesToHex(message);
            log(Log.VERBOSE, tag, msg);
        }
    }

    @Override
    public void v_(String tag, ByteBuffer buffer){
        if (isVerbose_()) {
            byte[] b = new byte[buffer.limit()];
            buffer.asReadOnlyBuffer().get(b);
            String msg = bytesToHex(b);
            log(Log.VERBOSE, tag, msg);
        }
    }

    @Override
    public void v_(String tag, String message){
        if (isVerbose_()) {
            log(Log.VERBOSE, tag, message);
        }
    }

    @Override
    public void v_(String tag, String message, Throwable t){
        if (isVerbose_()) {
            log(Log.VERBOSE, tag, message, t);
        }
    }

    @Override
    public void d_(String tag, String message){
        if (isDebug_()) {
            log(Log.DEBUG, tag, message);
        }
    }

    @Override
    public void d_(String tag, String message, Throwable t){
        if (isDebug_()) {
            log(Log.DEBUG, tag, message, t);
        }
    }

    @Override
    public void i_(String tag, String message){
        if (isInfo_()) {
            log(Log.INFO, tag, message);
        }
    }

    @Override
    public void i_(String tag, String message, Throwable t){
        if (isInfo_()) {
            log(Log.INFO, tag, message, t);
        }
    }

    @Override
    public void w_(String tag, String message){
        if (isWarn_()) {
            log(Log.WARN, tag, message);
        }
    }

    @Override
    public void w_(String tag, String message, Throwable t){
        if (isWarn_()) {
            log(Log.WARN, tag, message, t);
        }
    }

    @Override
    public void e_(String tag, String message){
        if (isError_()) {
            log(Log.ERROR, tag, message);
        }
    }

    @Override
    public void e_(String tag, String message, Throwable t){
        if (isError_()) {
            log(Log.ERROR, tag, message, t);
        }
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[(bytes.length * 2) + bytes.length];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 3] = HEX_ARRAY[v >>> 4];
            hexChars[j * 3 + 1] = HEX_ARRAY[v & 0x0F];
            hexChars[j * 3 + 2] = ' ';
        }
        return "[ " + new String(hexChars) + "]";
    }
}