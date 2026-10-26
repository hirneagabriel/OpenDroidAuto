package it.smg.hu.ui.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.USBManager;
import it.smg.hu.manager.WIFIManager;
import it.smg.hu.service.ODAService;
import it.smg.hu.ui.Fonts;
import it.smg.hu.ui.MainActivity;
import it.smg.hu.ui.PlayerActivity;
import it.smg.hu.ui.SettingsActivity;
import it.smg.libs.aasdk.usb.LibUsbDevice;
import it.smg.libs.common.Log;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    public static final String AUTOSTART = "enableAutostart";

    private View aaTile_;
    private ImageView aaLogo_;
    private TextView aaStatus_;
    private TextView txtUsbIconConnection_;
    private View wifiTile_;
    private TextView txtWifiIconConnection_;

    // what the Android Auto tile starts: USB when a phone is plugged in, else Wi-Fi (debug) when connected
    private String usbDeviceName_;
    private String wifiSsid_;

    private BroadcastReceiver localReceiver_;
    private LocalBroadcastManager localBroadcastManager_;

    private USBManager usbManager_;
    private WIFIManager WIFIManager_;

    private Settings settings_;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (Log.isDebug()) Log.d(TAG, "onCreateView");

        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.home_fragment, container, false);

        settings_ = Settings.instance();
        localBroadcastManager_ = LocalBroadcastManager.getInstance(getContext());

        usbManager_ = USBManager.instance();
        WIFIManager_ = WIFIManager.instance();

        if (usbManager_.aoapDevice() == null){
            usbManager_.searchForAoapDevice();
        }

        initUI(view);

        return Fonts.apply(view);
    }

    private void initUI(View view){
        aaTile_ = view.findViewById(R.id.aaTile);
        aaLogo_ = view.findViewById(R.id.aaLogo);
        aaStatus_ = view.findViewById(R.id.aaStatus);
        txtUsbIconConnection_ = view.findViewById(R.id.txtUsbIcon);
        wifiTile_ = view.findViewById(R.id.wifiTile);
        txtWifiIconConnection_ = view.findViewById(R.id.txtWifiIcon);

        aaTile_.setOnClickListener(c -> {
            if (usbDeviceName_ != null || ODAService.isSessionActive()) {
                if (Log.isInfo()) Log.i(TAG, "Starting in USB mode");
                start(ODAService.MODE_USB);
            } else if (wifiSsid_ != null) {
                if (Log.isInfo()) Log.i(TAG, "Starting in WiFi mode");
                start(ODAService.MODE_WIFI);
            }
        });

        view.findViewById(R.id.usbTile).setOnClickListener(v -> {
            new DeviceListDialog(getActivity()).show();
        });

        wifiTile_.setOnClickListener(c -> {
            if (wifiSsid_ != null) {
                if (Log.isInfo()) Log.i(TAG, "Starting in WiFi mode");
                start(ODAService.MODE_WIFI);
            }
        });

        view.findViewById(R.id.settingsTile).setOnClickListener(c -> {
            Intent i = new Intent(getContext(), SettingsActivity.class);
            startActivityForResult(i, MainActivity.SETTINGS_ACTIVITY_REQUEST);
        });

        view.findViewById(R.id.exitTile).setOnClickListener(c -> {
            // TODO: fix
            ((MainActivity) getActivity()).exitApp();
        });

        updateWifiVisibility();
        setWifi(null);
        initUsbDeviceIcon();
    }

    @Override
    public void onResume() {
        super.onResume();
        // the phone may have been unplugged, or a session started or ended, while we weren't listening
        initUsbDeviceIcon();
    }

    private void updateWifiVisibility(){
        wifiTile_.setVisibility(settings_.advanced.enableWiFi() ? View.VISIBLE : View.GONE);
    }

    private void initUsbDeviceIcon(){
        LibUsbDevice device = usbManager_.aoapDevice();
        if (Log.isDebug()) Log.d(TAG, "current aoap device " + device);
        setUsbDevice(device != null ? device.product : null);
    }

    private void setUsbDevice(String name){
        usbDeviceName_ = name;
        txtUsbIconConnection_.setText(name != null ? name : getString(R.string.home_usb));
        updateAaTile();
    }

    private void setWifi(String ssid){
        // Android 4.2+ reports the SSID in quotes
        if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        wifiSsid_ = ssid;
        txtWifiIconConnection_.setText(ssid != null ? ssid : getString(R.string.home_wifi));
        wifiTile_.setAlpha(ssid != null ? 1f : 0.5f);
        updateAaTile();
    }

    private void updateAaTile(){
        boolean wifi = wifiSsid_ != null && settings_.advanced.enableWiFi();
        boolean active = ODAService.isSessionActive();
        boolean ready = active || usbDeviceName_ != null || wifi;

        aaTile_.setBackgroundResource(ready ? R.drawable.tile_ready : R.drawable.tile_idle);
        aaLogo_.setAlpha(ready ? 1f : 0.45f);
        aaStatus_.setTextColor(ready ? 0xFFE2F0FF : getResources().getColor(R.color.oda_text_dim));
        if (active) {
            aaStatus_.setText(R.string.home_aa_active);
        } else if (usbDeviceName_ != null) {
            aaStatus_.setText(getString(R.string.home_aa_ready, usbDeviceName_));
        } else if (wifi) {
            aaStatus_.setText(getString(R.string.home_aa_ready_wifi, wifiSsid_));
        } else {
            aaStatus_.setText(R.string.home_aa_no_phone);
        }
    }

    public void start(String mode){
        Intent i = new Intent(getContext(), PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.putExtra("mode", mode);
        startActivity(i);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (Log.isDebug()) Log.d(TAG, "onActivityResult " + resultCode);
        if (requestCode == MainActivity.SETTINGS_ACTIVITY_REQUEST){

            updateWifiVisibility();
            initUsbDeviceIcon();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (Log.isDebug()) Log.d(TAG, "onStart");

        if (Log.isDebug()) Log.d(TAG, "Registering LocalReceiver");
        IntentFilter localFilter = new IntentFilter();
        localFilter.addAction(USBManager.ATTACH_AOAP_DEVICE);
        localFilter.addAction(USBManager.DETACH_AOAP_DEVICE);
        localFilter.addAction(WIFIManager.CONNECT_WIFI);
        localFilter.addAction(WIFIManager.DISCONNECT_WIFI);
        localReceiver_ = new LocalReceiver();
        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(getActivity());
        localBroadcastManager.registerReceiver(localReceiver_, localFilter);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (Log.isDebug()) Log.d(TAG, "onStop");
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(localReceiver_);
    }

    private class LocalReceiver extends BroadcastReceiver {
        private final static String TAG = "HomeFragment-LocalReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isDebug()) Log.d(TAG, "received action " + intent.getAction());
            if (USBManager.DETACH_AOAP_DEVICE.equalsIgnoreCase(intent.getAction())){
                setUsbDevice(null);
            } else if (USBManager.ATTACH_AOAP_DEVICE.equalsIgnoreCase(intent.getAction())){
                initUsbDeviceIcon();
            } else if (WIFIManager.CONNECT_WIFI.equalsIgnoreCase(intent.getAction())){
                setWifi(intent.getStringExtra(WIFIManager.EXTRA_SSID));
            } else if (WIFIManager.DISCONNECT_WIFI.equalsIgnoreCase(intent.getAction())){
                setWifi(null);
            }
        }
    }

}