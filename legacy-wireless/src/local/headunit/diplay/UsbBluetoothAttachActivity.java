package local.headunit.diplay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import org.json.JSONObject;

/** Android's default-handler entry point for supported USB Bluetooth adapters. */
public final class UsbBluetoothAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent launch = getIntent();
            if (launch == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(launch.getAction())) return;

            Object supplied = launch.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (!(supplied instanceof UsbDevice)) return;
            UsbDevice candidate = (UsbDevice) supplied;
            if (!UsbBluetoothProbe.hasUsbAttachHandler(candidate.getVendorId(), candidate.getProductId())) return;

            UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
            final UsbDevice device = MainActivity.currentUsbDevice(manager, candidate);
            if (device == null || !manager.hasPermission(device)) return;

            final Context app = getApplicationContext();
            MainActivity.onUsbBluetoothSystemGrant(app);
            AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                public void run() {
                    try {
                        DiagnosticLog.record(app, "USB_BT_PERMISSION", new JSONObject()
                            .put("stage", "system_attach")
                            .put("deviceVid", device.getVendorId())
                            .put("devicePid", device.getProductId())
                            .put("granted", true));
                    } catch (Exception error) {
                        android.util.Log.w("DiPlayUsb", "Could not record USB attach", error);
                    }
                }
            });
        } catch (Exception error) {
            android.util.Log.w("DiPlayUsb", "Could not inspect USB attach", error);
        } finally {
            finish();
        }
    }
}
