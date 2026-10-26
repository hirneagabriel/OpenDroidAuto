package it.smg.hu.ui.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.fragment.app.Fragment;

import java.util.concurrent.Callable;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.ui.Fonts;
import it.smg.libs.common.Log;

public abstract class BaseSettingsFragment extends Fragment {
    protected Settings settings;

    protected abstract String tag();

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Fonts.apply(view);
    }

    /** Adapter for a settings dropdown, in the app's typeface. */
    protected ArrayAdapter<CharSequence> createAdapter(int data){
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(getContext(), R.layout.spinner_item,
                getResources().getTextArray(data)) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return Fonts.apply(super.getView(position, convertView, parent));
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return Fonts.apply(super.getDropDownView(position, convertView, parent));
            }
        };
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        return adapter;
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, String defaultValue){
        initEditText(editText, base, settingsKey, defaultValue, null);
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, int defaultValue){
        initEditText(editText, base, settingsKey, defaultValue, null);
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, int defaultValue, Callable<Void> custonCheck){
        editText.setText(String.valueOf(base.get(settingsKey, defaultValue)));

        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE){
                try {
                    base.set(settingsKey, Integer.parseInt(v.getText().toString().trim()));
                } catch (NumberFormatException e) {
                    // empty or not a number: keep the saved value
                    v.setText(String.valueOf(base.get(settingsKey, defaultValue)));
                }

                InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }

                return true;
            }
            return false;
        });
    }

    protected void initEditText(EditText editText, Settings.Base base, String settingsKey, String defaultValue, Callable<Void> custonCheck){
        editText.setText(base.get(settingsKey, defaultValue));
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE){
                base.set(settingsKey, v.getText().toString());

                InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }

                return true;
            }
            return false;
        });
    }

    protected void initCheckBox(CheckBox checkBox, Settings.Base base, String settingsKey, boolean defaultValue){
        initCheckBox(checkBox, base, settingsKey, defaultValue, null);
    }

    protected void initCheckBox(CheckBox checkBox, Settings.Base base, String settingsKey, boolean defaultValue, Callable<Void> custonCheck){
        // rows with a hint hold a bare switch; tapping anywhere on the row flips it
        View row = (View) checkBox.getParent();
        if ("toggle_row".equals(row.getTag())) {
            row.setOnClickListener(v -> checkBox.toggle());
        }
        checkBox.setChecked(base.get(settingsKey, defaultValue));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            base.set(settingsKey, isChecked);
            if (custonCheck != null){
                try {
                    custonCheck.call();
                } catch (Exception e) {
                    Log.e(tag(), "errore in custom check", e);
                }
            }
        });
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, String defaultValue){
        initSpinner(spinner, data, dataValue, base, settingsKey, defaultValue, null);
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, int defaultValue){
        initSpinner(spinner, data, dataValue, base, settingsKey, defaultValue, null);
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, int defaultValue, Callable<Void> custonCheck){
        ArrayAdapter<CharSequence> adapter = createAdapter(data);
        spinner.setAdapter(adapter);

        int value = base.get(settingsKey, defaultValue);
        int[] elements = getContext().getResources().getIntArray(dataValue);

        for (int i = 0; i < elements.length; i++){
            int e = elements[i];
            if (value == e){
                spinner.setSelection(i);
                break;
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                base.set(settingsKey, elements[position]);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                base.set(settingsKey, defaultValue);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }
        });
    }

    protected void initSpinner(Spinner spinner, int data, int dataValue, Settings.Base base, String settingsKey, String defaultValue, Callable<Void> custonCheck){
        ArrayAdapter<CharSequence> adapter = createAdapter(data);
        spinner.setAdapter(adapter);

        String value = base.get(settingsKey, defaultValue);
        String[] elements = getContext().getResources().getStringArray(dataValue);

        for (int i = 0; i < elements.length; i++){
            String e = elements[i];
            if (value.equalsIgnoreCase(e)){
                spinner.setSelection(i);
                break;
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                base.set(settingsKey, elements[position]);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                base.set(settingsKey, defaultValue);
                if (custonCheck != null){
                    try {
                        custonCheck.call();
                    } catch (Exception e) {
                        Log.e(tag(), "errore in custom check", e);
                    }
                }
            }
        });
    }
}
