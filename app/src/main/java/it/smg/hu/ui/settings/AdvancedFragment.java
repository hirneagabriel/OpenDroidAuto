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
import it.smg.hu.manager.ResourceMonitor;
import it.smg.libs.common.Log;

public class AdvancedFragment extends BaseSettingsFragment {

    private static final String TAG = "AdvancedFragment";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.advanced_fragment, container, false);

        settings = Settings.instance();

        CheckBox enableDebug = view.findViewById(R.id.enable_debug);
        initCheckBox(enableDebug, settings.advanced, Settings.Advanced.ADVANCED_ENABLED_DEBUG, Settings.Advanced.ADVANCED_ENABLED_DEBUG_DEFAULT_VALUE, () -> {
            Log.reload();
            return null;
        });

        EditText logDir = view.findViewById(R.id.log_dir);
        initEditText(logDir, settings.advanced, Settings.Advanced.ADVANCED_LOG_DIR, Settings.Advanced.ADVANCED_LOG_DIR_DEFAULT_VALUE, () -> {
            Log.reload();
            return null;
        });

        CheckBox resourceMonitor = view.findViewById(R.id.resource_monitor);
        initCheckBox(resourceMonitor, settings.advanced, Settings.Advanced.ADVANCED_RESOURCE_MONITOR, Settings.Advanced.ADVANCED_RESOURCE_MONITOR_DEFAULT_VALUE, () -> {
            ResourceMonitor.instance().update();
            return null;
        });

        Spinner logLevel = view.findViewById(R.id.loglevel);
        initSpinner(logLevel, R.array.loglevel_labels, R.array.loglevel_values, settings.advanced, Settings.Advanced.ADVANCED_LOG_LEVEL, Settings.Advanced.ADVANCED_LOG_LEVEL_DEFAULT_VALUE, () -> {
            Log.setLogLevel(settings.advanced.logLevel());
            return null;
        });

        CheckBox logProtocol = view.findViewById(R.id.logProtocol);
        initCheckBox(logProtocol, settings.advanced, Settings.Advanced.ADVANCED_LOG_PROTOCOL, Settings.Advanced.ADVANCED_LOG_PROTOCOL_DEFAULT_VALUE, () -> {
            Log.setLogProtocol(settings.advanced.logProtocol());
            return null;
        });

        EditText huName = view.findViewById(R.id.hu_name);
        initEditText(huName, settings.car, Settings.Car.CAR_SETTINGS_HU_NAME, Settings.Car.CAR_SETTINGS_HU_NAME_DEFAULT_VALUE, null);

        EditText carModel = view.findViewById(R.id.car_model);
        initEditText(carModel, settings.car, Settings.Car.CAR_SETTINGS_MODEL, Settings.Car.CAR_SETTINGS_MODEL_DEFAULT_VALUE, null);

        EditText carYear = view.findViewById(R.id.car_year);
        initEditText(carYear, settings.car, Settings.Car.CAR_SETTINGS_YEAR, Settings.Car.CAR_SETTINGS_YEAR_DEFAULT_VALUE, null);

        EditText huMaker = view.findViewById(R.id.hu_make);
        initEditText(huMaker, settings.car, Settings.Car.CAR_SETTINGS_HU_MAKE, Settings.Car.CAR_SETTINGS_HU_MAKE_DEFAULT_VALUE, null);

        EditText huModel = view.findViewById(R.id.hu_model);
        initEditText(huModel, settings.car, Settings.Car.CAR_SETTINGS_HU_MODEL, Settings.Car.CAR_SETTINGS_HU_MODEL_DEFAULT_VALUE, null);

        EditText swBuild = view.findViewById(R.id.sw_build);
        initEditText(swBuild, settings.car, Settings.Car.CAR_SETTINGS_SW_BUILD, Settings.Car.CAR_SETTINGS_SW_BUILD_DEFAULT_VALUE, null);

        EditText swVersion = view.findViewById(R.id.sw_version);
        initEditText(swVersion, settings.car, Settings.Car.CAR_SETTINGS_SW_VERSION, Settings.Car.CAR_SETTINGS_SW_VERSION_DEFAULT_VALUE, null);

        EditText threads = view.findViewById(R.id.num_threads);
        initEditText(threads, settings.advanced, Settings.Advanced.ADVANCED_THREADS_NUM, Settings.Advanced.ADVANCED_THREADS_NUM_DEFAULT_VALUE);

        return view;
    }

    @Override
    protected String tag() {
        return TAG;
    }
}
