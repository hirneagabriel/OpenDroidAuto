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

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

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

    private static final String MANUFACTURER = "Android";
    private static final String MODEL = "Android Auto";
    private static final String DESC = "Android Auto";
    private static final String VERSION = "2.0.1";
    private static final String URI = "https://forum.xda-developers.com/t/honda-connect-android-system.3179549";
    private static final String SERIAL = "HU-AAAAAA001";

    public static final String ACTION_USB_PERMISSION =  "it.smg.hu.USB_PERMISSION";
    public static final String DETACH_AOAP_DEVICE = "it.smg.hu.DETACH_AOAP_DEVICE";
    public static final String ATTACH_AOAP_DEVICE = "it.smg.hu.ATTACH_AOAP_DEVICE";

    private static final String TAG = "USBManager";

    private final Context ctx_;
    private final UsbManager usbManager_;
    private LibUsbDevice usbDevice_;
    private final LocalBroadcastManager localBroadcastManager_;

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
                if (usbDevice_ != null && usbDevice_.originalDevice().getDeviceName().equals(device.getDeviceName())) {
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

    public boolean checkDevice(UsbDevice device){
        if (Log.isInfo()) Log.i(TAG, "checkDevice");

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
            usbDevice_ = libUsb_.createDevice(device, usbManager_);

            if (Log.isDebug()) Log.d(TAG, "send local broadcast ATTACH_AOAP_DEVICE");
            Intent aoapDeviceIntent = new Intent(ATTACH_AOAP_DEVICE);
            aoapDeviceIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
            localBroadcastManager_.sendBroadcast(aoapDeviceIntent);
            ConnectionWidget.refresh(ctx_);

            if (isKnownDevice() && !ODAService.isSessionActive()) {
                if (Log.isInfo()) Log.i(TAG, "known phone, starting Android Auto");
                Intent i = new Intent(ctx_, PlayerActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                i.putExtra("mode", ODAService.MODE_USB);
                ctx_.startActivity(i);
            }
        }

        return isAoap;
    }

    /** Whether the current accessory is a phone that has run Android Auto here before. */
    public boolean isKnownDevice(){
        return usbDevice_ != null && Settings.instance().connectivity.isKnownDevice(deviceId(usbDevice_));
    }

    public LibUsbDevice aoapDevice(){
        // the detach broadcast can be missed (seen on the car), so check the phone is still plugged in
        HashMap<String, UsbDevice> attached = usbDevice_ != null ? usbManager_.getDeviceList() : null;
        if (attached != null && !attached.containsKey(usbDevice_.originalDevice().getDeviceName())) {
            if (Log.isInfo()) Log.i(TAG, "aoap device no longer attached");
            usbDevice_ = null;
            localBroadcastManager_.sendBroadcast(new Intent(DETACH_AOAP_DEVICE));
            ConnectionWidget.refresh(ctx_);
        }
        return usbDevice_;
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

    public void searchForAoapDevice(){
        if (usbManager_.getDeviceList() != null) {
            for(UsbDevice dev : usbManager_.getDeviceList().values()){
                if (checkAOAPDevice(dev) && usbManager_.hasPermission(dev)){
                    usbDevice_ = libUsb_.createDevice(dev, usbManager_);
                    return;
                }
            }
        }
    }

    public boolean requestAOAP(UsbDevice device){
        UsbDeviceConnection usbConnection = usbManager_.openDevice(device);
        if (usbConnection != null){
            try {
                if (Log.isDebug()) Log.d(TAG, "switch to OAP");

                byte[] buffer = new byte[2];
                int len = usbConnection.controlTransfer(UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, 10000);
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
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, 10000);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Manufacter, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Model " + MODEL);
                buffer = (MODEL + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 1, buffer, buffer.length, 10000);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Model, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Desc " + DESC);
                buffer = (DESC + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 2, buffer, buffer.length, 10000);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Desc, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Version " + VERSION);
                buffer = (VERSION + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, 10000);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Version, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send URI " + URI);
                buffer = (URI + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, 10000);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Manufacter, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Send Serial " + SERIAL);
                buffer = (SERIAL + "\0").getBytes();
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, 0, buffer, buffer.length, 10000);
                if (len != buffer.length) {
                    Log.e(TAG, "Error sending Serial, len= " + len + " expected= " + buffer.length);
                    return false;
                }

                if (Log.isDebug()) Log.d(TAG, "Sending acc start");           // Send accessory start request. Device should re-enumerate as an accessory.
                len = usbConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, null, 0, 10000);
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
