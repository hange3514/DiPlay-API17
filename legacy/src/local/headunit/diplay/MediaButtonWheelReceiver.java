package local.headunit.diplay;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import org.json.JSONObject;
/**
 * Manifest-declared media button owner, registered by WiredService only while a CarPlay
 * session runs. On API 17 the most recently registered receiver wins, so this takes the
 * wheel track keys away from OEM/third-party music apps that stole them; when DiPlay is
 * not connected it never registers and their behavior is unchanged.
 */
public final class MediaButtonWheelReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null) return;
        int key = event.getKeyCode();
        int action = event.getAction();
        if (WiredService.isVideoReady() && WheelKeyPolicy.androidAction(key) != 0 &&
                (action == KeyEvent.ACTION_UP ||
                 (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0))) {
            try {
                MainActivity.record(context, "WHEEL_KEY", new JSONObject()
                    .put("keyPath", "media_button_receiver")
                    .put("keyDecision", "receiver_delivered")
                    .put("keyCode", key)
                    .put("keyAction", action));
            } catch (Exception ignored) { }
        }
        if (action == KeyEvent.ACTION_UP) WiredService.sendWheelKey(key, "media_button_receiver");
    }
}
