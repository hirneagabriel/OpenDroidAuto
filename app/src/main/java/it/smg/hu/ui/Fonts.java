package it.smg.hu.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The app's typeface, Barlow Semi Condensed (assets/fonts, OFL): condensed like the head unit's own UI, so labels
 * fit the 800 px screen. API 15 has no XML fonts, so pages call {@link #apply} on what they inflate; bold text gets
 * the SemiBold weight.
 */
public final class Fonts {

    private static Typeface regular_;
    private static Typeface semiBold_;

    private Fonts() {}

    private static void load(Context context) {
        if (regular_ == null) {
            regular_ = Typeface.createFromAsset(context.getAssets(), "fonts/BarlowSemiCondensed-Regular.ttf");
            semiBold_ = Typeface.createFromAsset(context.getAssets(), "fonts/BarlowSemiCondensed-SemiBold.ttf");
        }
    }

    /** Sets the typeface on every TextView (buttons, checkboxes and fields included) under view. */
    public static <T extends View> T apply(T view) {
        load(view.getContext().getApplicationContext());
        applyTo(view);
        return view;
    }

    private static void applyTo(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface current = text.getTypeface();
            text.setTypeface(current != null && current.isBold() ? semiBold_ : regular_);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i));
            }
        }
    }
}
