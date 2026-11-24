package local.headunit.diplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import java.util.UUID;

/** Starts the projection activity after the head unit finishes booting. */
public final class BootAutoStartReceiver extends BroadcastReceiver {
    static final String PREFS = "boot_autostart_state";
    static final String LAUNCH_TOKEN_KEY = "launchToken";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        String token = UUID.randomUUID().toString();
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        state.edit().putString("stage", "receiver_received")
                .putString("errorType", "")
                .putString(LAUNCH_TOKEN_KEY, token)
                .putLong("elapsedMs", SystemClock.elapsedRealtime()).apply();
        Intent launch = new Intent(context, MainActivity.class);
        launch.putExtra("auto_connect", true);
        launch.putExtra("boot_launch", true);
        launch.putExtra("boot_launch_token", token);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launch);
            state.edit().putString("stage", "activity_launch_requested").apply();
        } catch (RuntimeException failure) {
            state.edit().putString("stage", "activity_launch_failed")
                    .putString("errorType", failure.getClass().getSimpleName())
                    .remove(LAUNCH_TOKEN_KEY).apply();
            Log.e("DiPlayBoot", "Boot activity launch failed", failure);
        }
    }
}
