package it.smg.hu.ui.main;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;

import it.smg.hu.R;
import it.smg.hu.manager.USBManager;
import it.smg.hu.ui.Fonts;
import it.smg.libs.aasdk.usb.LibUsbDevice;
import it.smg.libs.common.Log;

/** Lists the plugged-in USB devices; tapping one switches it to Android Auto. */
public class DeviceListDialog extends Dialog {

    private static final String TAG = "DeviceListDialog";

    protected DeviceListDialog(Context context) {
        super(context, R.style.OdaDialog);

        setContentView(R.layout.usb_devices_dialog);
        findViewById(R.id.usb_devices_close).setOnClickListener(v -> dismiss());

        USBManager usbManager = USBManager.instance();
        LibUsbDevice currentAOAP = usbManager.aoapDevice();
        List<LibUsbDevice> devices = usbManager.connectedDevices();

        ViewGroup list = findViewById(R.id.usb_devices_list);
        findViewById(R.id.usb_devices_empty).setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (LibUsbDevice d : devices){
            View row = inflater.inflate(R.layout.usb_device_row, list, false);
            ((TextView) row.findViewById(R.id.usb_device_name)).setText(d.product);
            ((TextView) row.findViewById(R.id.usb_device_maker)).setText(d.manufacter);

            // connectedDevices() makes new objects, so compare the USB device behind them
            boolean inUse = currentAOAP != null
                    && currentAOAP.originalDevice().getDeviceName().equals(d.originalDevice().getDeviceName());
            row.findViewById(R.id.usb_device_in_use).setVisibility(inUse ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> {
                if (inUse) {
                    if (Log.isDebug()) Log.d(TAG, "the device selected is already the current device");
                    return;
                }
                if (Log.isDebug()) Log.d(TAG, "selected device: " + d);
                usbManager.checkDevice(d.originalDevice());
                dismiss();
            });
            list.addView(row);
        }

        Fonts.apply(findViewById(android.R.id.content));
    }
}
