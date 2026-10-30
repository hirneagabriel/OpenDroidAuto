package it.smg.hu.manager;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.smg.hu.config.Settings;
import it.smg.hu.service.ODAService;
import it.smg.hu.ui.PlayerActivity;
import it.smg.hu.widget.ConnectionWidget;
import it.smg.libs.common.Log;
import it.smg.libs.aasdk.usb.LibUsb;
import it.smg.libs.aasdk.usb.LibUsbDevice;


public class USBManager {

    private static final int USB_PID_ACC         = 0x2D00;      // Accessory                  100
    private static final int USB_PID_ACC_ADB     = 0x2D01;      // Accessory + ADB            110
    private static final int USB_VID_GOO         = 0x18D1;

    private static final int ACC_REQ_GET_PROTOCOL = 51;
    private static final int ACC_REQ_SEND_STRING  = 52;
    private static final int ACC_REQ_START        = 53;

    // Version, URI and serial are sent at index 0 (manufacturer), not 3/4/5 as the AOA spec says, so the phone
    // ends up seeing manufacturer "HU-AAAAAA001". The spec-correct identity (2.6.7) made the phone stop answering
    // after the switch (USB_TRANSFER timeout on the version request); this is the identity it has always accepted.
    private static final String MANUFACTURER = "Android";
    private static final String MODEL = "Android Auto";
    private static final String DESC = "Android Auto";
    private static final String VERSION = "2.0.1";
    private static final String URI = "https://forum.xda-developers.com/t/honda-connect-android-system.3179549";
    private static final String SERIAL = "HU-AAAAAA001";

    // a phone answers the accessory requests in milliseconds; one that doesn't is retried on the next attach
    private static final int AOAP_TIMEOUT_MS = 2000;

    public static final String ACTION_USB_PERMISSION =  "it.smg.hu.USB_PERMISSION";
    public static final String DETACH_AOAP_DEVICE = "it.smg.hu.DETACH_AOAP_DEVICE";
    public static final String ATTACH_AOAP_DEVICE = "it.smg.hu.ATTACH_AOAP_DEVICE";

    private static final String TAG = "USBManager";

    private final Context ctx_;
    private final UsbManager usbManager_;
    // set on the USB thread, read from the UI and the widget
    private volatile LibUsbDevice usbDevice_;
    // switching a phone to accessory mode and opening it block on USB control transfers: keep them off the main thread
    private final ExecutorService usbExecutor_ = Executors.newSingleThreadExecutor(r -> new Thread(r, "ODA USB"));
    private final LocalBroadcastManager localBroadcastManager_;
    // SystemClock.elapsedRealtime() until which a known phone is not started automatically
    private volatile long autoStartPausedUntil_;
    // accessories this process saw attach: only these may be started (added on the USB thread, read from the UI)
    private final java.util.Set<String> seenAccessories_ = Collections.synchronizedSet(new HashSet<>());
    // leftover accessories already reset (USB thread only): the device lingers in the list for a moment afterwards
    private final java.util.Set<String> resetAccessories_ = new HashSet<>();
    // SystemClock.elapsedRealtime() of this process's last switch to accessory mode: the phone attaching right after
    // it is ours, even before its attach intent has been checked
    private volatile long lastAoapRequest_;
    private static final long AOAP_ATTACH_WINDOW_MS = 5000;
    // how long after start an attach intent that launched the app gets to be handled before a leftover is reset
    private static final long STALE_ACCESSORY_CHECK_DELAY_MS = 1500;

    private static USBManager instance_;
    private LibUsb libUsb_;

    public static void init(Context ctx){
        instance_ = new USBManager(ctx);
    }

    public static USBManager instance(){
        return instance_;
    }

    private USBManager(Context ctx){
        ctx_ = ctx;
        usbManager_ = (UsbManager) ctx_.getSystemService(Context.USB_SERVICE);
        localBroadcastManager_ = LocalBroadcastManager.getInstance(ctx_);
        LibUsb.init();
        libUsb_ = LibUsb.instance();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ctx_.registerReceiver(usbReceiver_, filter);

        new Handler(Looper.getMainLooper()).postDelayed(
                () -> usbExecutor_.execute(this::resetStaleAccessory), STALE_ACCESSORY_CHECK_DELAY_MS);
    }

    /**
     * A phone already in accessory mode when the app starts is left from before: the car woke from standby, or killed
     * the app (another source, the power button) during a session. Its attach was never delivered to this process,
     * so nothing started it (it waited for a tap on the car), and its first session always failed (SSL_READ, the
     * phone still in the old one). Resetting it makes it attach again like a fresh plug-in, which auto-starts it.
     */
    private void resetStaleAccessory(){
        UsbDevice accessory = attachedAccessory();
        if (accessory == null || seenAccessories_.contains(accessory.getDeviceName()) || ODAService.isSessionActive()
                || resetAccessories_.contains(accessory.getDeviceName())
                || SystemClock.elapsedRealtime() - lastAoapRequest_ < AOAP_ATTACH_WINDOW_MS) {
            return;
        }
        resetAccessories_.add(accessory.getDeviceName());
        if (Log.isInfo()) Log.i(TAG, "phone " + accessory.getDeviceName() + " still in accessory mode from before, resetting it");
        libUsb_.createDevice(accessory, usbManager_).reset();
    }

    private final BroadcastReceiver usbReceiver_ = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) {
                return;
            }

            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (Log.isInfo()) Log.i(TAG, "permission granted for " + device.getDeviceName());
                    checkDevice(device);
                } else {
                    if (Log.isWarn()) Log.w(TAG, "permission denied for " + device.getDeviceName());
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                // the phone's first enumeration also detaches when it switches to accessory mode; only the accessory matters
                LibUsbDevice current = usbDevice_;
                if (current != null && current.originalDevice().getDeviceName().equals(device.getDeviceName())) {
                    if (Log.isInfo()) Log.i(TAG, "aoap device detached");
                    usbDevice_ = null;
                    localBroadcastManager_.sendBroadcast(new Intent(DETACH_AOAP_DEVICE));
                    ConnectionWidget.refresh(ctx_);
                }
            }
        }
    };

    /** The phone's identity for remembering it: its USB serial, or manufacturer and model when it has none. */
    public static String deviceId(LibUsbDevice device){
        if (device.serial != null && !device.serial.isEmpty() && !"Unknown".equals(device.serial)) {
            return device.serial;
        }
        return device.manufacter + "/" + device.product;
    }

    /** Checks a newly attached device on the USB thread: asks for permission, or switches it to Android Auto. */
    public void checkDevice(UsbDevice device){
        usbExecutor_.execute(() -> checkDeviceNow(device));
    }

    private boolean checkDeviceNow(UsbDevice device){
        if (Log.isInfo()) Log.i(TAG, "checkDevice");

        // ending a session resets the phone's USB, which queues attach intents for devices that are gone by the time
        // they are checked; asking permission for one of those put the system's USB dialog on the car's screen
        if (!usbManager_.getDeviceList().containsKey(device.getDeviceName())) {
            if (Log.isInfo()) Log.i(TAG, "device " + device.getDeviceName() + " is already gone, ignoring it");
            return false;
        }

        // launched by USB_DEVICE_ATTACHED the system has already granted it; otherwise ask, and continue in usbReceiver_
        boolean deviceHasPermission = usbManager_.hasPermission(device);
        if (Log.isInfo()) Log.i(TAG, "deviceHasPermission " + deviceHasPermission);
        if (!deviceHasPermission) {
            if (Log.isDebug()) Log.d(TAG, "Request permission");
            Intent i = new Intent(ACTION_USB_PERMISSION);
            PendingIntent permissionIntent = PendingIntent.getBroadcast(ctx_, 0, i, PendingIntent.FLAG_ONE_SHOT);
            usbManager_.requestPermission(device, permissionIntent);
            return false;
        }

        boolean isAoap = checkAOAPDevice(device);
        if (!isAoap) {
            if (Log.isInfo()) Log.i(TAG, "Request aoap");
            requestAOAP(device);
        } else {
            if (Log.isDebug()) Log.d(TAG, "Device is aoap");
            seenAccessories_.add(device.getDeviceName());
            usbDevice_ = libUsb_.createDevice(device, usbManager_);

            if (Log.isDebug()) Log.d(TAG, "send local broadcast ATTACH_AOAP_DEVICE");
            Intent aoapDeviceIntent = new Intent(ATTACH_AOAP_DEVICE);
            aoapDeviceIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
            localBroadcastManager_.sendBroadcast(aoapDeviceIntent);
            ConnectionWidget.refresh(ctx_);

            if (isKnownDevice() && !ODAService.isSessionActive() && SystemClock.elapsedRealtime() < autoStartPausedUntil_) {
                if (Log.isInfo()) Log.i(TAG, "known phone, but Android Auto was just exited: not starting it");
            } else if (isKnownDevice() && !ODAService.isSessionActive()) {
                if (Log.isInfo()) Log.i(TAG, "known phone, starting Android Auto");
                Intent i = new Intent(ctx_, PlayerActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                i.putExtra("mode", ODAService.MODE_USB);
                ctx_.startActivity(i);
            }
        }

        return isAoap;
    }

    /**
     * Stops known phones from starting Android Auto by themselves for a while. Ending a session resets the phone's
     * USB, and it re-attaches within ~3 s; after an Exit that must not start it again. A later replug still does.
     */
    public void pauseAutoStart(long ms){
        autoStartPausedUntil_ = SystemClock.elapsedRealtime() + ms;
    }

    /** Whether the current accessory is a phone that has run Android Auto here before. */
    public boolean isKnownDevice(){
        LibUsbDevice current = usbDevice_;
        return current != null && Settings.instance().connectivity.isKnownDevice(deviceId(current));
    }

    /**
     * The phone's USB link speed from sysfs, matched by bus and device number ("480" Mbit/s is high speed, "12"
     * full speed), or null if it can't be read. The car's port speed caps how much video the phone can send.
     */
    public static String linkSpeed(UsbDevice device){
        // device names are /dev/bus/usb/<bus>/<device>
        String[] parts = device.getDeviceName().split("/");
        if (parts.length < 2) return null;
        try {
            int bus = Integer.parseInt(parts[parts.length - 2]);
            int dev = Integer.parseInt(parts[parts.length - 1]);
            File[] devices = new File("/sys/bus/usb/devices").listFiles();
            if (devices == null) return null;
            for (File d : devices) {
                String busnum = readSysfs(new File(d, "busnum"));
                String devnum = readSysfs(new File(d, "devnum"));
                if (busnum != null && devnum != null && Integer.parseInt(busnum) == bus && Integer.parseInt(devnum) == dev) {
                    String speed = readSysfs(new File(d, "speed"));
                    String version = readSysfs(new File(d, "version"));
                    return speed + " Mbit/s (USB " + (version != null ? version : "?") + ", " + d.getName() + ")";
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "can't read the USB link speed", e);
        }
        return null;
    }

    private static String readSysfs(File file){
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public LibUsbDevice aoapDevice(){
        // the detach broadcast can be missed (seen on the car), so check the phone is still plugged in.
        // Read the field once: the USB thread can replace or clear it meanwhile.
        LibUsbDevice current = usbDevice_;
        if (current != null && !usbManager_.getDeviceList().containsKey(current.originalDevice().getDeviceName())) {
            if (Log.isInfo()) Log.i(TAG, "aoap device no longer attached");
            usbDevice_ = null;
            localBroadcastManager_.sendBroadcast(new Intent(DETACH_AOAP_DEVICE));
            ConnectionWidget.refresh(ctx_);
            return null;
        }
        return current;
    }

    private boolean checkAOAPDevice(UsbDevice device){
        if (device.getVendorId() == USB_VID_GOO && (device.getProductId() == USB_PID_ACC || device.getProductId() == USB_PID_ACC_ADB)) {
            if (Log.isInfo()) Log.i(TAG, "Found aop device");
            return true;
        }
        if (Log.isInfo()) Log.i(TAG, "Device no aop");
        return false;
    }

    public List<LibUsbDevice> connectedDevices(){
        if (usbManager_.getDeviceList() != null) {
            List<LibUsbDevice> attachedDevices = new ArrayList<>();
            for(UsbDevice dev : usbManager_.getDeviceList().values()){
                LibUsbDevice usbDevice = libUsb_.createDevice(dev, usbManager_);
                attachedDevices.add(usbDevice);
            }
            return attachedDevices;
        }
        return Collections.EMPTY_LIST;
    }

    /**
     * Picks up a phone already in accessory mode, for the home page and the widget. Only one this process switched
     * itself counts: any other is still in an old session (the car woke up, or the app was killed during a
     * session), and starting Android Auto on it failed with SSL_READ. That one is reset instead, so it re-attaches
     * and starts like a fresh plug-in.
     */
    public void searchForAoapDevice(){
        UsbDevice dev = attachedAccessory();
        if (dev == null) {
            return;
        }
        if (seenAccessories_.contains(dev.getDeviceName())) {
            usbDevice_ = libUsb_.createDevice(dev, usbManager_);
        } else {
            usbExecutor_.execute(this::resetStaleAccessory);
        }
    }

    /** A phone already in accessory mode that we may open, or null. */
    private UsbDevice attachedAccessory(){
        if (usbManager_.getDeviceList() != null) {
            for(UsbDevice dev : usbManager_.getDeviceList().values()){
                if (checkAOAPDevice(dev) && usbManager_.hasPermission(dev)){
                    return dev;
                }
            }
        }
        return null;
    }

    public boolean requestAOAP(UsbDevice device){
        lastAoapRequest_ = SystemClock.elapsedRealtime();
        UsbDeviceConnection usbConnection = usbManager_.openDevice(device);
        if (usbConnection != null){
            try {
                if (Log.isDebug()) Log.d(TAG, "switch to OAP");

                byte[] buffer = new byte[2];
                int len = usbConnection.controlTransfer(UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, AOAP_TIMEOUT_MS);
                if (len != 2) {
                    Log.e(TAG, "Error controlTransfer GET_PROTOCOL len: " + len);
                    return false;
                }
                int accVer = (buffer[1] << 8) | buffer[0];
                if (Log.isDebug()) Log.d(TAG, "accessory version: " + accVer);
                if (accVer < 1) {                                                  // If error or version too low...
                    Log.e(TAG, "No supported accessory");
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Manufacter " + MANUFACTURER);
                buffer = (MANUFACTURER + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, AOAP_TIMEOUT_MS);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Manufacter, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Model " + MODEL);
                buffer = (MODEL + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 1, buffer, buffer.length, AOAP_TIMEOUT_MS);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Model, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Desc " + DESC);
                buffer = (DESC + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 2, buffer, buffer.length, AOAP_TIMEOUT_MS);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Desc, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Version " + VERSION);
                buffer = (VERSION + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, AOAP_TIMEOUT_MS);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Version, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send URI " + URI);
                buffer = (URI + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, AOAP_TIMEOUT_MS);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Manufacter, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Serial " + SERIAL);
                buffer = (SERIAL + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, AOAP_TIMEOUT_MS);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Serial, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Sending acc start");           // Send accessory start request. Device should re-enumerate as an accessory.
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, null, 0, AOAP_TIMEOUT_MS);
                if (len != 0) {
                    Log.e(TAG, "Error in accessory start request");
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "switchOAP everything OK");
                return true;
            } finally {
                if (Log.isDebug()) Log.d(TAG, "Closing connection");
                usbConnection.close();
            }
        }
        return false;
    }

}
