package it.smg.hu.widget;

import it.smg.hu.R;

/** One-cell version of {@link ConnectionWidget}: just the logo and the status. */
public class ConnectionWidgetSmall extends ConnectionWidget {

    @Override
    protected int layout() {
        return R.layout.widget_connection_small;
    }
}
