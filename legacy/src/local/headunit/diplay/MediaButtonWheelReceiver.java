package local.headunit.diplay;
import android.content.*;import android.view.KeyEvent;
/**
 * Manifest-declared media button owner, registered by WiredService only while a CarPlay
 * session runs. On API 17 the most recently registered receiver wins, so this takes the
 * wheel track keys away from OEM/third-party music apps that stole them; when DiPlay is
 * not connected it never registers and their behavior is unchanged.
 */
public final class MediaButtonWheelReceiver extends BroadcastReceiver {
 public void onReceive(Context context,Intent intent){
  if(intent==null||!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction()))return;
  KeyEvent e=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
  if(e==null||e.getAction()!=KeyEvent.ACTION_UP)return;
  WiredService.sendWheelKey(e.getKeyCode(),"media_button_receiver");
 }
}
