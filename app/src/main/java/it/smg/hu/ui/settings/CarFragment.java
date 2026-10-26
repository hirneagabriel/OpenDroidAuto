package it.smg.hu.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;

public class CarFragment extends BaseSettingsFragment {

    private static final String TAG = "CarFragment";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.car_fragment, container, false);

        settings = Settings.instance();

        CheckBox playMediaDuringVR = view.findViewById(R.id.play_media_during_vr);
        initCheckBox(playMediaDuringVR, settings.car, Settings.Car.CAR_SETTINGS_PLAY_MEDIA_DURING_VR, Settings.Car.CAR_SETTINGS_PLAY_MEDIA_DURING_VR_DEFAULT_VALUE);

        CheckBox hideClock = view.findViewById(R.id.hide_clock);
        initCheckBox(hideClock, settings.car, Settings.Car.CAR_SETTINGS_HIDE_CLOCK, Settings.Car.CAR_SETTINGS_HIDE_CLOCK_DEFAULT_VALUE);

        CheckBox leftDrive = view.findViewById(R.id.left_drive);
        initCheckBox(leftDrive, settings.car, Settings.Car.CAR_SETTINGS_LEFT_HAND_DRIVE, Settings.Car.CAR_SETTINGS_LEFT_HAND_DRIVE_DEFAULT_VALUE);

        CheckBox enableHondaIntegration = view.findViewById(R.id.enable_hondaintegration);
        initCheckBox(enableHondaIntegration, settings.advanced, Settings.Advanced.ADVANCED_ENABLE_HONDA_INTEGRATION, Settings.Advanced.ADVANCED_ENABLE_HONDA_INTEGRATION_DEFAULT_VALUE, () -> {
            if (Settings.instance().advanced.hondaIntegrationEnabled()) {
                HondaConnectManager.init(getActivity().getApplicationContext());
            }
            return null;
        });

        CheckBox enableHondaMicVr = view.findViewById(R.id.enable_hondamicvr);
        initCheckBox(enableHondaMicVr, settings.advanced, Settings.Advanced.ADVANCED_ENABLE_HONDA_MIC_VR, Settings.Advanced.ADVANCED_ENABLE_HONDA_MIC_VR_DEFAULT_VALUE);

        Spinner micSource = view.findViewById(R.id.mic_source);
        initSpinner(micSource, R.array.mic_source_labels, R.array.mic_source_values, settings.audio, Settings.Audio.MIC_SOURCE, Settings.Audio.MIC_SOURCE_DEFAULT_VALUE);

        Spinner swMode = view.findViewById(R.id.sw_mode);
        initSpinner(swMode, R.array.swmode_labels, R.array.swmode, settings.advanced, Settings.Advanced.ADVANCED_SW_MODE, Settings.Advanced.ADVANCED_SW_MODE_DEFAULT_VALUE, () -> {
            EditText swIdx =  view.findViewById(R.id.steeringwheel_idx);
            switch (settings.advanced.swMode()){
                case HondaConnectManager.SWMode.SW_SERVICE:
                    swIdx.setEnabled(true);
                    break;
//                case HondaConnectManager.SWMode.MODEMGR_KEY_CALLBACK:
//                    swIdx.setEnabled(false);
//                    break;
                default:
                    break;
            }

            return null;
        });

        EditText modeMgrAudioIdx = view.findViewById(R.id.modemgraudio_idx);
        initEditText(modeMgrAudioIdx, settings.advanced, Settings.Advanced.ADVANCED_MODEMGRAUDIO_IDX, Settings.Advanced.ADVANCED_MODEMGRAUDIO_IDX_DEFAULT_VALUE);

        EditText steeringWheelIdx = view.findViewById(R.id.steeringwheel_idx);
        initEditText(steeringWheelIdx, settings.advanced, Settings.Advanced.ADVANCED_SW_IDX, Settings.Advanced.ADVANCED_SW_IDX_DEFAULT_VALUE);

        return view;

    }

    @Override
    protected String tag() {
        return TAG;
    }
}
