package it.smg.hu.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import it.smg.hu.R;
import it.smg.hu.config.Settings;

public class ConnectivityFragment extends BaseSettingsFragment {

    private static final String TAG = "ConnectivityFragment";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.conn_fragment, container, false);

        settings = Settings.instance();

        CheckBox enableAd2p = view.findViewById(R.id.enable_ad2p);
        initCheckBox(enableAd2p, settings.connectivity, Settings.Connectivity.CONN_ENABLED_AD2P, Settings.Connectivity.CONN_ENABLED_AD2P_DEFAULT_VALUE);

        CheckBox enableHfp = view.findViewById(R.id.enable_hfp);
        initCheckBox(enableHfp, settings.connectivity, Settings.Connectivity.CONN_ENABLED_HFP, Settings.Connectivity.CONN_ENABLED_HFP_DEFAULT_VALUE);

        CheckBox enableWifi = view.findViewById(R.id.enable_wifi);
        initCheckBox(enableWifi, settings.advanced, Settings.Advanced.ADVANCED_ENABLE_WIFI, Settings.Advanced.ADVANCED_ENABLE_WIFI_DEFAULT_VALUE);

        Button forgetDevices = view.findViewById(R.id.forget_devices);
        TextView forgetDevicesSummary = view.findViewById(R.id.forget_devices_summary);
        updateForgetDevices(forgetDevices, forgetDevicesSummary);
        forgetDevices.setOnClickListener(v -> {
            settings.connectivity.forgetDevices();
            updateForgetDevices(forgetDevices, forgetDevicesSummary);
        });

        return view;

    }

    private void updateForgetDevices(Button button, TextView summary){
        int count = settings.connectivity.knownDeviceCount();
        summary.setText(count == 0
                ? getString(R.string.conn_settings_known_devices_none)
                : getResources().getQuantityString(R.plurals.conn_settings_known_devices_summary, count, count));
        button.setEnabled(count > 0);
    }

    @Override
    protected String tag() {
        return TAG;
    }
}
