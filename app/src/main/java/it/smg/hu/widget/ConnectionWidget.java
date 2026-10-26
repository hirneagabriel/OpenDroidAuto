package it.smg.hu.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import it.smg.hu.R;
import it.smg.hu.manager.USBManager;
import it.smg.hu.service.ODAService;
import it.smg.hu.ui.MainActivity;
import it.smg.hu.ui.PlayerActivity;
import it.smg.libs.aasdk.usb.LibUsbDevice;
import it.smg.libs.common.Log;

/**
 * Home screen tile: grey while no phone is connected (tap opens the app), coloured when a phone is ready or
 * Android Auto is running (tap opens Android Auto).
 */
public class ConnectionWidget extends AppWidgetProvider {

    private static final String TAG = "ConnectionWidget";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        if (Log.isDebug()) Log.d(TAG, "onUpdate");

        USBManager usbManager = USBManager.instance();
        if (usbManager.aoapDevice() == null) {
            usbManager.searchForAoapDevice();
        }
        manager.updateAppWidget(appWidgetIds, createViews(context, layout()));
    }

    /** The tile's layout; {@link ConnectionWidgetSmall} swaps in the one-cell version. */
    protected int layout() {
        return R.layout.widget_connection;
    }

    /** Redraws every placed widget, both sizes, with the current connection state. */
    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        refresh(context, manager, ConnectionWidget.class, R.layout.widget_connection);
        refresh(context, manager, ConnectionWidgetSmall.class, R.layout.widget_connection_small);
    }

    private static void refresh(Context context, AppWidgetManager manager, Class<?> provider, int layout) {
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
        if (ids != null && ids.length > 0) {
            manager.updateAppWidget(ids, createViews(context, layout));
        }
    }

    private static RemoteViews createViews(Context context, int layout) {
        // the small layout has no title; RemoteViews skips actions on views a layout doesn't have
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);

        LibUsbDevice device = USBManager.instance().aoapDevice();
        boolean active = ODAService.isSessionActive();
        boolean ready = active || device != null;

        String status;
        if (active) {
            status = context.getString(R.string.widget_connection_active);
        } else if (device != null) {
            status = device.product;
        } else {
            status = context.getString(R.string.widget_connection_no_phone);
        }

        views.setImageViewResource(R.id.widget_connection_background, ready ? R.drawable.tile_ready : R.drawable.tile_idle);
        views.setInt(R.id.widget_connection_logo, "setAlpha", ready ? 255 : 110);
        views.setTextViewText(R.id.widget_connection_status, status);
        views.setTextColor(R.id.widget_connection_title, ready ? 0xFFFFFFFF : 0xFFB0B0B0);
        views.setTextColor(R.id.widget_connection_status, ready ? 0xFFE2F0FF : 0xFF909090);

        Intent intent;
        if (ready) {
            intent = new Intent(context, PlayerActivity.class);
            intent.putExtra("mode", ODAService.MODE_USB);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        } else {
            intent = new Intent(context, MainActivity.class);
            intent.setAction(Intent.ACTION_MAIN);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, ready ? 1 : 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_connection, pendingIntent);

        return views;
    }
}
