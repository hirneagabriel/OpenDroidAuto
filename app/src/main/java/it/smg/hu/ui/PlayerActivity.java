package it.smg.hu.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.service.ODAService;
import it.smg.hu.ui.notification.AppBadge;
import it.smg.hu.ui.notification.NotificationFactory;
import it.smg.libs.common.Log;

public class PlayerActivity extends Activity implements ServiceConnection, SurfaceHolder.Callback, InputDevice.OnKeyHolder {

    private final static String TAG = "PlayerActivity";
    private ODAService odaService_;

    private LocalBroadcastManager localBroadcastManager_;
    private BroadcastReceiver localReceiver_;

    private SurfaceView surfaceView_;
    private View.OnKeyListener keyListener_;

    private String startMode_;
    private boolean isRunning_ = false;
    private boolean isServiceBound_ = false;

    private static boolean isActive_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Log.isDebug()) Log.d(TAG, "onCreate");

        localBroadcastManager_ = LocalBroadcastManager.getInstance(this);

        if (savedInstanceState != null){
            startMode_ = savedInstanceState.getString("startMode");
            isRunning_ = savedInstanceState.getBoolean("isRunning");
        } else {
            Bundle b = getIntent().getExtras();
            startMode_ = b.getString("mode");
        }

        if (Log.isDebug()) Log.d(TAG, "start mode: " + startMode_);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_player);

        if (Settings.instance().advanced.hondaIntegrationEnabled()){
            HondaConnectManager.instance().initialize();
        }

        surfaceView_ = findViewById(R.id.surfaceView);

        // registered for the whole life of the screen, not just while visible: in the background (Back) it must
        // still hear that the session ended, or it comes back later over a session that is gone
        initReceivers();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (startMode_ == null) {
            if (Log.isDebug()) Log.d(TAG, "startMode_");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (Log.isDebug()) Log.d(TAG, "onSaveInstanceState");
        super.onSaveInstanceState(outState);
        outState.putSerializable("startMode", startMode_);
        outState.putBoolean("isRunning", isRunning_);
    }

    /** Back goes to the app's home page and keeps the session running; the Android Auto tile comes back to it. */
    @Override
    public void onBackPressed() {
        if (Log.isInfo()) Log.i(TAG, "Back pressed, Android Auto keeps running in the background");

        Intent home = new Intent(this, MainActivity.class);
        home.setAction(Intent.ACTION_MAIN);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }

    /** A long press on Back ends Android Auto. */
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (Log.isInfo()) Log.i(TAG, "Back button long pressed, ending Android Auto");

            if (Settings.instance().advanced.hondaIntegrationEnabled()){
                HondaConnectManager.instance().endAudioBinding();
            }

            if (odaService_ != null) {
                odaService_.quit();
            }
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Log.isDebug()) Log.d(TAG, "onResume");

        Intent odaServiceIntent = new Intent(this, ODAService.class);
        startService(odaServiceIntent);
        bindService(odaServiceIntent, this, BIND_AUTO_CREATE | BIND_ABOVE_CLIENT | BIND_IMPORTANT);

        if (Settings.instance().advanced.hondaIntegrationEnabled()){
            HondaConnectManager.instance().initAudioBinding();
        }

        NotificationFactory.instance().dismissAll();
        AppBadge.instance().dismiss();

        isActive_ = true;
    }

    @Override
    protected void onPause() {
        if (Log.isDebug()) Log.d(TAG, "onPause");
        super.onPause();
        odaService_.releaseFocus();

        if (isServiceBound_) {
            unbindService(this);
        }

        if (Settings.instance().advanced.hondaIntegrationEnabled()){
            HondaConnectManager.instance().sendToBackground();
        }

        AppBadge.instance().show();

        isActive_ = false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Log.isDebug()) Log.d(TAG, "onKeyDown: " + keyCode);

        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK && keyListener_ != null){
            return keyListener_.onKey(surfaceView_, keyCode, event);
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (Log.isDebug()) Log.d(TAG, "onKeyUp: " + keyCode);

        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK && keyListener_ != null){
            return keyListener_.onKey(surfaceView_, keyCode, event);
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Log.isDebug()) Log.d(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Log.isDebug()) Log.d(TAG, "onDestroy");
        localBroadcastManager_.unregisterReceiver(localReceiver_);
    }

    private void initReceivers(){
        if (Log.isDebug()) Log.d(TAG, "Registering LocalReceiver");
        IntentFilter localFilter = new IntentFilter();
        localFilter.addAction(ODAService.STOP_ACTION);
        localFilter.addAction(ODAService.STOP_VIDEO_INDICATION);
        localReceiver_ = new LocalReceiver();
        localBroadcastManager_.registerReceiver(localReceiver_, localFilter);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
        odaService_.releaseFocus();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (Log.isDebug()) Log.d(TAG, "onServiceConnected ODAService");
        ODAService.ServiceBinder binder = (ODAService.ServiceBinder) service;
        odaService_ = binder.getService();

        isServiceBound_ = true;

        if (isRunning_ && !ODAService.isSessionActive()) {
            // the session ended while this screen was in the background (exit from the home page, phone unplugged)
            if (Log.isInfo()) Log.i(TAG, "session ended while in the background, closing");
            finish();
        } else if (isRunning_){
            odaService_.gainFocus();
        } else if (ODAService.isSessionActive()) {
            // the session was started by a screen that is gone (destroyed by the system): its video and touch are
            // bound to that screen's surface, so end it; the phone re-attaches and the known-phone auto-start reconnects
            if (Log.isWarn()) Log.w(TAG, "a session is already running without its screen, restarting it");
            odaService_.quit();
        } else {
            start();
        }
    }

    private void start(){

        if (Settings.instance().advanced.hondaIntegrationEnabled()){
            HondaConnectManager.instance().adjustPermission();
        }

        switch (startMode_){
            case ODAService.MODE_USB:
                odaService_.startUsb(surfaceView_, this);
                break;
            case ODAService.MODE_WIFI:
                odaService_.startWifi(surfaceView_, this);
                break;
            default:
                if (Log.isWarn()) Log.w(TAG, "unknown startMode: " + startMode_);
                return;
        }

        isRunning_ = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (Log.isDebug()) Log.d(TAG, "onServiceDisconnected");
        odaService_ = null;
        isServiceBound_ = false;
    }

    @Override
    public void setOnKeyListener(View.OnKeyListener listener) {
        keyListener_ = listener;
    }

    private class LocalReceiver extends BroadcastReceiver {
        private final static String TAG = "LocalReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isDebug()) Log.d(TAG, "received action " + intent.getAction());
            if (ODAService.STOP_ACTION.equalsIgnoreCase(intent.getAction())){
                finish();

            } else if (ODAService.STOP_VIDEO_INDICATION.equalsIgnoreCase(intent.getAction()) && isActive_){
                moveTaskToBack(true);
            }
        }
    }

    public static boolean isActive(){
        return isActive_;
    }
}
