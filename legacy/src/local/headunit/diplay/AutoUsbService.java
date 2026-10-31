package local.headunit.diplay;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import org.json.JSONObject;

/** Idle watcher only enumerates descriptors; USB ownership belongs exclusively to WiredService. */
public class AutoUsbService extends Service {
 static final String PREFS="auto_connect";
 static volatile AutoUsbService live;static long maintenanceUntil;
 final Handler handler=new Handler();final AutoConnectPolicy policy=new AutoConnectPolicy();boolean destroyed;
 int lastCount=-1;long absentSince=-1;
 static boolean enabled(Context c){return c.getSharedPreferences(PREFS,0).getBoolean("enabled",true);}
 static void start(Context c){if(enabled(c))c.startService(new Intent(c,AutoUsbService.class));}
 static void onMain(final Runnable action){AutoUsbService s=live;if(s==null)return;if(Looper.myLooper()==Looper.getMainLooper())action.run();else s.handler.post(action);}
 static boolean canConnect(Context c){return enabled(c)&&SystemClock.elapsedRealtime()>=maintenanceUntil&&(live==null||!live.policy.isPaused());}
 static void beginMaintenance(Context c){maintenanceUntil=SystemClock.elapsedRealtime()+10*60*1000;}
 static void endMaintenance(Context c){maintenanceUntil=0;}
 static void pause(Context c){final AutoUsbService s=live;if(s!=null)onMain(new Runnable(){public void run(){s.policy.pause();s.log("paused_until_unplug");}});}
 static void resume(Context c){final AutoUsbService s=live;if(s!=null)onMain(new Runnable(){public void run(){s.policy.manualResume();}});}
 static void setEnabled(Context c,boolean enabled){c.getSharedPreferences(PREFS,0).edit().putBoolean("enabled",enabled).commit();if(enabled)start(c);else c.stopService(new Intent(c,AutoUsbService.class));}
 void log(String text){try{MainActivity.record(this,"AUTO_USB",new JSONObject().put("detail",text));}catch(Exception ignored){}}
 final BroadcastReceiver events=new BroadcastReceiver(){public void onReceive(Context c,Intent i){String action=i.getAction();if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)||UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){UsbDevice device=(UsbDevice)i.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(device!=null&&device.getVendorId()==0x05ac)UsbPresenceLog.recordTransition(c,UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)?"attached":"detached",device);}if("local.headunit.diplay.STATUS".equals(action)&&"VIDEO_READY".equals(i.getStringExtra("stage")))policy.ready(SystemClock.elapsedRealtime());schedule(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)?0:300);}};
 final Runnable tick=new Runnable(){public void run(){if(destroyed)return;if(!enabled(AutoUsbService.this)){stopSelf();return;}long delay=2000;try{
  UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);int count=0;
  for(UsbDevice d:manager.getDeviceList().values())if(d.getVendorId()==0x05ac)count++;
  if(count!=lastCount){log("apple_usb_count="+count);lastCount=count;}
  long now=SystemClock.elapsedRealtime();boolean busy=WiredService.isBusy();
  if(count==0){if(absentSince<0)absentSince=now;if(busy&&WiredService.hasUsbLink()&&now-absentSince>2000){log("usb_detached_stop_session");WiredService.stop(AutoUsbService.this);}}else absentSince=-1;
  boolean occupied=busy||MainActivity.interactionBusy()||now<maintenanceUntil;
  if(policy.poll(now,count==1,occupied)){
   log("launch_projection");
   try{startActivity(new Intent(AutoUsbService.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("auto_connect",true));}
   catch(RuntimeException e){policy.launchFailed(now);log("launch_failed="+e.getClass().getSimpleName());}
  }
  delay=policy.nextPollDelay(now,count==1,occupied);
 }catch(Exception e){log("scan_failed="+e.getClass().getSimpleName());}finally{schedule(delay);}}};
 void schedule(long delay){if(!destroyed){handler.removeCallbacks(tick);handler.postDelayed(tick,delay);}}
 public void onCreate(){super.onCreate();VerifiedMfiCache.warm(this);live=this;IntentFilter f=new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);f.addAction("local.headunit.diplay.STATUS");registerReceiver(events,f);
  PendingIntent open=PendingIntent.getActivity(this,1703,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
  startForeground(1703,new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("DiPlay自动连接").setContentText("后台等待iPhone USB连接").setContentIntent(open).setOngoing(true).build());log("watcher_started");}
 public int onStartCommand(Intent i,int flags,int id){if(!enabled(this)){stopSelf();return START_NOT_STICKY;}schedule(200);return START_STICKY;}
 public IBinder onBind(Intent i){return null;}
 public void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);unregisterReceiver(events);if(live==this)live=null;stopForeground(true);log("watcher_stopped");super.onDestroy();}
}
