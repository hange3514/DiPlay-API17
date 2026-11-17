package local.headunit.diplay;
import android.content.*;
public class AutoBootReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){String action=i==null?"":i.getAction();if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;AutoUsbService.startAtBoot(c,action);}
}
