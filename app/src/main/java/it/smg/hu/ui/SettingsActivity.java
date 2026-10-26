package it.smg.hu.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.ui.settings.AdvancedFragment;
import it.smg.hu.ui.settings.CarFragment;
import it.smg.hu.ui.settings.ConnectivityFragment;
import it.smg.hu.ui.settings.KeymapFragment;
import it.smg.hu.ui.settings.VideoFragment;
import it.smg.libs.common.Log;

public class SettingsActivity extends FragmentActivity implements InputDevice.OnKeyHolder {

    private static final String TAG = "SettingsActivity";
    private View.OnKeyListener keyListener_;
    private Settings settings_;
    private View[] tabs_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings_ = Settings.instance();

        setContentView(R.layout.activity_settings);

        tabs_ = new View[] {
                findViewById(R.id.conn_settings),
                findViewById(R.id.video_settings),
                findViewById(R.id.car_settings),
                findViewById(R.id.keymap_settings),
                findViewById(R.id.advanced_settings),
        };
        for (int i = 0; i < tabs_.length; i++) {
            final int tab = i;
            tabs_[i].setOnClickListener(listener -> showTab(tab));
        }

        TextView title = findViewById(R.id.title_text);
        title.setText(R.string.settings);
        Fonts.apply(findViewById(android.R.id.content));

        View back = findViewById(R.id.title_back);
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(v -> onBackPressed());

        showTab(0);
    }

    private void showTab(int tab){
        Fragment fragment;
        switch (tab) {
            case 1: fragment = new VideoFragment(); break;
            case 2: fragment = new CarFragment(); break;
            case 3: fragment = new KeymapFragment(); break;
            case 4: fragment = new AdvancedFragment(); break;
            default: fragment = new ConnectivityFragment(); break;
        }
        getSupportFragmentManager().beginTransaction().replace(R.id.main_content, fragment).commit();
        findViewById(R.id.settings_scroll).scrollTo(0, 0);

        for (int i = 0; i < tabs_.length; i++) {
            tabs_[i].setSelected(i == tab);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Log.isDebug()) Log.d(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Log.isDebug()) Log.d(TAG, "onPause");
    }

    @Override
    public void onBackPressed() {
        Intent i = new Intent();
        setResult(Activity.RESULT_OK, i);
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Log.isDebug()) Log.d(TAG, "onKeyDown: " + keyCode);
        return keyListener_ != null ? keyListener_.onKey(null, keyCode, event) : super.onKeyDown(keyCode, event);
    }

    @Override
    public void setOnKeyListener(View.OnKeyListener listener) {
        keyListener_ = listener;
    }

}
