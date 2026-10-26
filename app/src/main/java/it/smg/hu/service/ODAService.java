package it.smg.hu.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.Keep;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.hu.manager.USBManager;
import it.smg.hu.manager.WIFIManager;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.ui.notification.NotificationFactory;
import it.smg.hu.widget.ConnectionWidget;
import it.smg.libs.aasdk.tcp.TCPConnectException;
import it.smg.libs.common.Log;
import it.smg.libs.aasdk.service.AndroidAutoEntity;
import it.smg.libs.aasdk.service.IAndroidAutoEntityEventHandler;
import it.smg.libs.aasdk.tcp.TCPEndpoint;
import it.smg.libs.aasdk.usb.LibUsbDevice;

public class ODAService extends Service implements IAndroidAutoEntityEventHandler {

    public static final String START_ACTION = "it.smg.hu.service.ODAService.START_ACTION";
    public static final String STOP_ACTION = "it.smg.hu.service.ODAService.STOP_ACTION";
    public static final String STOP_VIDEO_INDICATION = "it.smg.hu.service.ODAService.STOP_VIDEO_INDICATION";
    public static final String FORCE_CLOSE_ACTION = "it.smg.hu.service.ODAService.FORCE_CLOSE_ACTION";

    public static final String EXTRA_START_MODE = "startMode";
    public static final String MODE_USB = "modeUSB";
    public static final String MODE_WIFI = "modeWifi";

    private static final String TAG = "ODAService";

    private final IBinder mBinder = new ServiceBinder();

    private LocalBroadcastManager localBroadcastManager_;
    private NotificationFactory notificationFactory_;
    private AndroidAutoEntity androidAutoEntity_;

    private  USBManager usbManager_;
    private  WIFIManager wifiManager_;

    private Thread startThread_;

    private Handler mainHandler_;

    private volatile boolean isRunning_;

    // sessions are torn down here, never on the protocol's own worker threads (errors and quit requests arrive on
    // those): stopping joins the workers and deletes their io_service, which a worker can't do to itself
    private final ExecutorService stopExecutor_ = Executors.newSingleThreadExecutor(r -> new Thread(r, "ODA stop"));

    // for the widget and USB auto-start, which have no binding to the service
    private static volatile boolean sessionActive_;

    public static boolean isSessionActive(){
        return sessionActive_;
    }

    public ODAService() {}

    @Override
    public void onCreate() {
        if (Log.isInfo()) Log.i(TAG, "create");
        super.onCreate();

        NotificationFactory.init(getApplicationContext());
        notificationFactory_ = NotificationFactory.instance();

        usbManager_ = USBManager.instance();
        wifiManager_ = WIFIManager.instance();
        mainHandler_ = new Handler(Looper.getMainLooper());

        localBroadcastManager_ = LocalBroadcastManager.getInstance(this);
    }

    public void startUsb(SurfaceView surfaceView, InputDevice.OnKeyHolder keyHolder){
        startThread_ = new Thread(() -> {
            Looper.prepare();

            if (usbManager_.aoapDevice() != null) {
                isRunning_ = true;

                if (Log.isVerbose()) Log.v(TAG, "aoap device available, start in usb mode");
                try {
                    LibUsbDevice device = usbManager_.aoapDevice();
                    if (device.open()) {
                        if (Log.isInfo()) Log.i(TAG, "device opened");
                        androidAutoEntity_ = AndroidAutoEntityFactory.create(this, device, surfaceView, keyHolder);
                        androidAutoEntity_.start(this);
                        Settings.instance().connectivity.rememberDevice(USBManager.deviceId(device));
                        setSessionActive(true);
                    } else {
                        Log.e(TAG, "Error in open usb device");
                        onAndroidAutoQuitOnError("USB OPEN DEVICE", -1);
                        return;
                    }
                } catch (Exception e){
                    Log.e(TAG, "error", e);
                    onAndroidAutoQuitOnError("USB GENERIC ERROR", -1);
//                    onAndroidAutoQuit();
                    return;
                }
            } else {
                // nothing to start: close the (still black) Android Auto screen instead of leaving it open
                if (Log.isWarn()) Log.w(TAG, "no aoap device, nothing to start");
                mainHandler_.post(() -> Toast.makeText(this, R.string.start_no_phone, Toast.LENGTH_LONG).show());
                localBroadcastManager_.sendBroadcast(new Intent(ODAService.STOP_ACTION));
                return;
            }
            if (Log.isInfo()) Log.i(TAG, "start usb thead completed");
        });
        startThread_.start();
    }

    public void startWifi(SurfaceView surfaceView, InputDevice.OnKeyHolder keyHolder){
        startThread_ = new Thread(() -> {
            Looper.prepare();

            try {
                String ipAddress = wifiManager_.getIpAddress();
                if (ipAddress != null) {
                    isRunning_ = true;

//                    if (Log.isInfo()) Log.i(TAG, "Connect to ip " + ipAddress);
                    TCPEndpoint tcpEndpoint = new TCPEndpoint(ipAddress);
                    androidAutoEntity_ = AndroidAutoEntityFactory.create(this, tcpEndpoint, surfaceView, keyHolder);
                    androidAutoEntity_.start(this);
                    setSessionActive(true);
                }
                if (Log.isInfo()) Log.i(TAG, "start wifi thead completed");
            } catch (TCPConnectException e){
                Log.e(TAG, "TCP Connection error", e);
                stop();
            }
        });
        startThread_.start();
    }

    public void shutdown(){
        androidAutoEntity_.shutdown();
    }

    public void releaseFocus(){
        if (androidAutoEntity_ != null) {
            androidAutoEntity_.releaseFocus();
        }
    }

    public void gainFocus(){
        androidAutoEntity_.gainFocus();
    }

    /** Ends the session, asynchronously on the stop thread. */
    public void stop(){
        stopExecutor_.execute(this::stopNow);
    }

    private synchronized void stopNow(){
        if (!isRunning_) {
            if (Log.isInfo()) Log.i(TAG, "service not running, already stopped?");
            return;
        }

        isRunning_ = false;

        if (Log.isInfo()) Log.i(TAG, "Stop");

        if (Settings.instance().advanced.hondaIntegrationEnabled()){
            HondaConnectManager.instance().endAudioBinding();
        }

        if (androidAutoEntity_ != null) {
            androidAutoEntity_.stop();
        }

        if (androidAutoEntity_ != null) {
            androidAutoEntity_.delete();
            androidAutoEntity_ = null;
        }

        startThread_ = null;
        setSessionActive(false);

        Intent stopIntent = new Intent(ODAService.STOP_ACTION);
        localBroadcastManager_.sendBroadcast(stopIntent);

        Intent service = new Intent(this, ODAService.class);
        stopService(service);
    }

    private void setSessionActive(boolean active){
        sessionActive_ = active;
        mainHandler_.post(() -> ConnectionWidget.refresh(this));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void onDestroy() {
        if (Log.isDebug()) Log.d(TAG, "onDestroy");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (Log.isDebug()) Log.d(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Log.isDebug()) Log.d(TAG, "Received start id " + startId + ": " + intent);

        Notification notification = notificationFactory_.create();
        startForeground(123456, notification);

//        return START_NOT_STICKY;
        return START_STICKY;
    }

    @Keep
    @Override
    public void onAndroidAutoQuit() {
        // no error: the phone asked to stop, or stopped answering pings (the native log above says which)
        if (Log.isInfo()) Log.i(TAG, "Android Auto ended by the protocol, not by a USB or Wi-Fi error");
        stop();
    }

    @Keep
    @Override
    public void onAndroidAutoQuitOnError(String error, int nativeErrorCode){
        Log.e(TAG, "closing with error " + error + "(" + nativeErrorCode + ")");

        mainHandler_.post(() -> {
            Toast.makeText(this, "Closed due to " + error + " error", Toast.LENGTH_LONG).show();
        });

        stop();
    }

    @Keep
    @Override
    public void onAVChannelStopIndication() {
        if (Log.isInfo()) Log.i(TAG, "stop video indication");
        Intent stopIntent = new Intent(ODAService.STOP_VIDEO_INDICATION);
        localBroadcastManager_.sendBroadcast(stopIntent);
    }

    public class ServiceBinder extends Binder {
        public ODAService getService() {
            return ODAService.this;
        }
    }

}
