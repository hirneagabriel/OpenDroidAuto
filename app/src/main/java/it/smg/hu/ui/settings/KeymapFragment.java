package it.smg.hu.ui.settings;

import static it.smg.hu.config.Settings.Keymap.KEYMAP_BTN_LEFT;
import static it.smg.hu.config.Settings.Keymap.KEYMAP_BTN_MINUS;
import static it.smg.hu.config.Settings.Keymap.KEYMAP_BTN_PLUS;
import static it.smg.hu.config.Settings.Keymap.KEYMAP_BTN_RIGHT;
import static it.smg.hu.config.Settings.Keymap.KEYMAP_BTN_SOURCE;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.hu.projection.InputDevice;
import it.smg.libs.common.Log;

public class KeymapFragment extends BaseSettingsFragment implements View.OnClickListener, View.OnLongClickListener, View.OnKeyListener {

    private static final String TAG = "KeymapFragment";

    private Button currentButton_;
    private Handler handler_;
    private Runnable unsetKeyListener_;
    private InputDevice.OnKeyHolder keyHolder_;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (Log.isVerbose()) Log.v(TAG, "onCreateView");
        View view = inflater.inflate(R.layout.keymap_fragment, container, false);

        settings = Settings.instance();
        handler_ = new Handler();
        if (settings.advanced.hondaIntegrationEnabled()){
            if (Log.isVerbose()) Log.v(TAG, "init hondaconnection and keyholder");
            HondaConnectManager.instance().initialize();

            try {
                Thread.sleep(500);  // ugly ack to sync steering wheel service connection
            } catch (InterruptedException ignored) {
            }

            HondaConnectManager.instance().requestAudioFocus();
            keyHolder_ = (InputDevice.OnKeyHolder) getActivity();
        }

        unsetKeyListener_ = () -> {
            if (Log.isVerbose()) Log.v(TAG, "unset keyListener");
            if (keyHolder_ != null) {
                keyHolder_.setOnKeyListener(null);
            }
            // nothing pressed in time: show the code it had before
            if (currentButton_ != null && getView() != null) {
                KeyMap c = (KeyMap) currentButton_.getTag();
                showCode(getView(), c, settings.keymap.key(c.keyName() + "_code"));
            }
            currentButton_ = null;
        };

        // the car sends the wheel buttons only through the Honda services, so there is nothing to learn without them
        boolean canLearn = keyHolder_ != null;
        if (!canLearn) {
            TextView hint = view.findViewById(R.id.keymap_hint);
            hint.setText(R.string.keymap_settings_hint_no_honda);
        }

        for (KeyMap c : KeyMap.values()){
            if (c.id() != -1){
                Button b = view.findViewById(c.id());

                int currentValue = settings.keymap.key(c.keyName() + "_code");
                if (currentValue == 0){
                    settings.keymap.key(c.keyName() + "_code", c.defaultValue());
                    currentValue = c.defaultValue();
                }
                showCode(view, c, currentValue);

                b.setEnabled(canLearn);
                b.setOnClickListener(this);
                b.setOnLongClickListener(this);
                b.setTag(c);
            }
        }

        Settings settings = Settings.instance();
        if (settings.keymap.key(Settings.Keymap.KEYMAP_BTN_LEFT) == 0){
            settings.keymap.key(Settings.Keymap.KEYMAP_BTN_LEFT, Settings.Keymap.KEYMAP_BTN_LEFT_DEFAULT_VALUE);
        }
        Spinner btnLeft = view.findViewById(R.id.btn_left);
        initSpinner(btnLeft, R.array.keymap_btn_labels, R.array.keymap_btn_values, settings.keymap, Settings.Keymap.KEYMAP_BTN_LEFT, Settings.Keymap.KEYMAP_BTN_LEFT_DEFAULT_VALUE);

        if (settings.keymap.key(KEYMAP_BTN_RIGHT) == 0){
            settings.keymap.key(Settings.Keymap.KEYMAP_BTN_RIGHT, Settings.Keymap.KEYMAP_BTN_RIGHT_DEFAULT_VALUE);
        }
        Spinner btnRight = view.findViewById(R.id.btn_right);
        initSpinner(btnRight, R.array.keymap_btn_labels, R.array.keymap_btn_values, settings.keymap, Settings.Keymap.KEYMAP_BTN_RIGHT, Settings.Keymap.KEYMAP_BTN_RIGHT_DEFAULT_VALUE);

        if (settings.keymap.key(Settings.Keymap.KEYMAP_BTN_SOURCE) == 0){
            settings.keymap.key(Settings.Keymap.KEYMAP_BTN_SOURCE, Settings.Keymap.KEYMAP_BTN_SOURCE_DEFAULT_VALUE);
        }
        Spinner btnSource = view.findViewById(R.id.btn_source);
        initSpinner(btnSource, R.array.keymap_btn_labels, R.array.keymap_btn_values, settings.keymap, Settings.Keymap.KEYMAP_BTN_SOURCE, Settings.Keymap.KEYMAP_BTN_SOURCE_DEFAULT_VALUE);

        if (settings.keymap.key(Settings.Keymap.KEYMAP_BTN_PLUS) == 0){
            settings.keymap.key(Settings.Keymap.KEYMAP_BTN_PLUS, Settings.Keymap.KEYMAP_BTN_PLUS_DEFAULT_VALUE);
        }
        Spinner btnPlus = view.findViewById(R.id.btn_plus);
        initSpinner(btnPlus, R.array.keymap_btn_labels, R.array.keymap_btn_values, settings.keymap, Settings.Keymap.KEYMAP_BTN_PLUS, Settings.Keymap.KEYMAP_BTN_PLUS_DEFAULT_VALUE);

        if (settings.keymap.key(Settings.Keymap.KEYMAP_BTN_MINUS) == 0){
            settings.keymap.key(Settings.Keymap.KEYMAP_BTN_MINUS, Settings.Keymap.KEYMAP_BTN_MINUS_DEFAULT_VALUE);
        }
        Spinner btnMinus = view.findViewById(R.id.btn_minus);
        initSpinner(btnMinus, R.array.keymap_btn_labels, R.array.keymap_btn_values, settings.keymap, Settings.Keymap.KEYMAP_BTN_MINUS, Settings.Keymap.KEYMAP_BTN_MINUS_DEFAULT_VALUE);

        return view;
    }

    @Override
    public void onClick(View v) {
        Button b = (Button)v;
        if (currentButton_ != null && currentButton_ != b) {
            // was learning another button: put its code back
            KeyMap previous = (KeyMap) currentButton_.getTag();
            showCode(getView(), previous, settings.keymap.key(previous.keyName() + "_code"));
        }
        currentButton_ = b;
        KeyMap c = (KeyMap) b.getTag();

        if (keyHolder_ != null) {
            TextView value = getView().findViewById(c.valueId());
            value.setText(R.string.keymap_settings_code_waiting);
            keyHolder_.setOnKeyListener(this);

            handler_.removeCallbacks(unsetKeyListener_);
            handler_.postDelayed(unsetKeyListener_, 5000);
        }

    }

    @Override
    public void onStop() {
        if (Log.isVerbose()) Log.v(TAG, "onStop");
        if (settings.advanced.hondaIntegrationEnabled()){
            HondaConnectManager.instance().endAudioBinding();
        }

        super.onStop();
    }

    @Override
    public boolean onLongClick(View v) {
        Button b = (Button)v;
        KeyMap c = (KeyMap) b.getTag();
        if (Log.isDebug()) Log.d(TAG, "Reset " + c.keyName() + "_code button");
        settings.keymap.key(c.keyName() + "_code", -1);
        showCode(getView(), c, -1);

        return true;
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && currentButton_ != null){
            KeyMap c = (KeyMap) currentButton_.getTag();
            if (Log.isDebug()) Log.d(TAG, "Set " + c.keyName() + "_code button to code " + keyCode);
            settings.keymap.key(c.keyName() + "_code", keyCode);
        }

        handler_.removeCallbacks(unsetKeyListener_);
        unsetKeyListener_.run();

        return true;
    }

    private void showCode(View view, KeyMap c, int code){
        TextView value = view.findViewById(c.valueId());
        value.setText(code == -1 ? getString(R.string.keymap_settings_code_none) : getString(R.string.keymap_settings_code, code));
    }

    @Override
    protected String tag() {
        return TAG;
    }

    public enum KeyMap {
        LEFT(KEYMAP_BTN_LEFT, R.id.keycode_left, R.id.keycode_left_value, -65532, Settings.Keymap.KEYMAP_BTN_LEFT_DEFAULT_VALUE),
        RIGHT(KEYMAP_BTN_RIGHT, R.id.keycode_right, R.id.keycode_right_value, -65533, Settings.Keymap.KEYMAP_BTN_RIGHT_DEFAULT_VALUE),
        SOURCE(KEYMAP_BTN_SOURCE, R.id.keycode_source, R.id.keycode_source_value, -65531, Settings.Keymap.KEYMAP_BTN_SOURCE_DEFAULT_VALUE),
        PLUS(KEYMAP_BTN_PLUS, R.id.keycode_plus, R.id.keycode_plus_value, -65535, Settings.Keymap.KEYMAP_BTN_PLUS_DEFAULT_VALUE),
        MINUS(KEYMAP_BTN_MINUS, R.id.keycode_min, R.id.keycode_min_value, -65534, Settings.Keymap.KEYMAP_BTN_MINUS_DEFAULT_VALUE);

        private final String keyName_;
        private final int id_;
        private final int valueId_;
        private final int defaultValue_;;
        private final int defaultAction_;;

        KeyMap(String keyName, int id, int valueId, int defaultValue, int defaultAction){
            keyName_ = keyName;
            id_ = id;
            valueId_ = valueId;
            defaultValue_ = defaultValue;
            defaultAction_ = defaultAction;
        }

        public String keyName(){
            return keyName_;
        }

        public int id(){
            return id_;
        }

        /** The text under the button's name that shows its learned key code. */
        public int valueId(){
            return valueId_;
        }

        public int defaultValue(){
            return defaultValue_;
        }

        public int defaultAction(){
            return defaultAction_;
        }

    }

}