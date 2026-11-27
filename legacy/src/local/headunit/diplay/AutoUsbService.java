package local.headunit.diplay;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Idle watcher only enumerates descriptors; USB ownership belongs exclusively to WiredService. */
public class AutoUsbService extends Service {
 static final String PREFS="auto_connect";
 private static final ExecutorService DIAGNOSTICS=Executors.newSingleThreadExecutor();
 static volatile AutoUsbService live;static long maintenanceUntil;
 final Handler handler=new Handler();final AutoConnectPolicy policy=new AutoConnectPolicy();boolean destroyed;
 int lastCount=-1;long absentSince=-1;Boolean lastBusyState=null;long lastRetryDeadline=-1;int connectAttempt=0;
 UsbDevice observedPhone;
 static boolean enabled(Context c){return c.getSharedPreferences(PREFS,0).getBoolean("enabled",true);}
 static boolean autoReconnectEnabled(Context c){return c.getSharedPreferences(PREFS,0).getBoolean("auto_reconnect_enabled",false);}
 static void setAutoReconnectEnabled(Context c,boolean enabled){c.getSharedPreferences(PREFS,0).edit().putBoolean("auto_reconnect_enabled",enabled).commit();if(enabled){clearCrashBlock(c);final AutoUsbService s=live;if(s!=null)onMain(new Runnable(){public void run(){s.policy.manualResume();s.state("auto_reconnect_enabled",s.countAppleDevices(),WiredService.isBusy(),SystemClock.elapsedRealtime(),null);s.schedule(0);}});else start(c);}try{recordDiagnostic(c,"AUTO_USB_STATE",new JSONObject().put("decision",enabled?"auto_reconnect_enabled":"auto_reconnect_disabled").put("paused",blockedAfterCrash(c)));}catch(Exception ignored){}}
 static boolean blockedAfterCrash(Context c){return c.getSharedPreferences(PREFS,0).getBoolean("blocked_after_process_exit",false);}
 static void blockAfterUnexpectedExit(Context c){c.getSharedPreferences(PREFS,0).edit().putBoolean("blocked_after_process_exit",true).commit();}
 static void clearCrashBlock(Context c){c.getSharedPreferences(PREFS,0).edit().remove("blocked_after_process_exit").commit();}
 static void start(Context c){if(enabled(c))c.startService(new Intent(c,AutoUsbService.class));}
 static void onMain(final Runnable action){AutoUsbService s=live;if(s==null)return;if(Looper.myLooper()==Looper.getMainLooper())action.run();else s.handler.post(action);}
 static boolean canConnect(Context c){return enabled(c)&&!blockedAfterCrash(c)&&SystemClock.elapsedRealtime()>=maintenanceUntil&&(live==null||!live.policy.isPaused());}
 static void beginMaintenance(Context c){maintenanceUntil=SystemClock.elapsedRealtime()+10*60*1000;}
 static void endMaintenance(Context c){maintenanceUntil=0;}
 static void adoptUsbEnumeration(final UsbDevice previous,final UsbDevice replacement){final AutoUsbService s=live;if(s!=null)onMain(new Runnable(){public void run(){
  if(s.observedPhone==null||UsbAttachActivity.sameDevice(s.observedPhone,previous))s.observedPhone=replacement;
 }});}
 static void pause(Context c){pauseForDevice(c,null);}
 static void pauseForDevice(Context c,final UsbDevice denied){final AutoUsbService s=live;if(s!=null)onMain(new Runnable(){public void run(){
  List<UsbDevice> phones;
  try{phones=s.appleDevices();}catch(RuntimeException ignored){if(denied==null)s.policy.pause();return;}
  if(denied!=null){
   boolean present=false;
   for(UsbDevice phone:phones)if(UsbAttachActivity.sameDevice(denied,phone)){present=true;break;}
   if(!present)return;
   s.observedPhone=denied;
  }else if(phones.size()==1)s.observedPhone=phones.get(0);
  s.policy.pause();s.state("manual_pause",phones.size(),WiredService.isBusy(),SystemClock.elapsedRealtime(),null);s.log("paused_until_unplug");
 }});}
 static void resume(Context c){resume(c,"manual_resume");}
 static void resume(Context c,final String reason){clearCrashBlock(c);final AutoUsbService s=live;if(s!=null)onMain(new Runnable(){public void run(){s.policy.manualResume();s.state(reason,s.countAppleDevices(),WiredService.isBusy(),SystemClock.elapsedRealtime(),null);s.schedule(0);}});else try{recordDiagnostic(c,"AUTO_USB_STATE",new JSONObject().put("decision",reason).put("paused",false));}catch(Exception ignored){}}
 static void startAtBoot(Context c,String action){boolean wasPaused=blockedAfterCrash(c);boolean allowed=enabled(c);try{if(allowed){resume(c,"boot_resume");start(c);}recordDiagnostic(c,"AUTO_BOOT",new JSONObject().put("stage",Intent.ACTION_BOOT_COMPLETED.equals(action)?"boot_completed":"package_replaced").put("decision",allowed?"watcher_start_requested":"watcher_disabled").put("paused",wasPaused));}catch(Throwable failure){try{recordDiagnostic(c,"AUTO_BOOT",new JSONObject().put("stage",Intent.ACTION_BOOT_COMPLETED.equals(action)?"boot_completed":"package_replaced").put("decision","watcher_start_failed").put("paused",wasPaused).put("errorType",failure.getClass().getSimpleName()));}catch(Exception ignored){}}}
 static void setEnabled(Context c,boolean enabled){c.getSharedPreferences(PREFS,0).edit().putBoolean("enabled",enabled).commit();if(enabled)start(c);else c.stopService(new Intent(c,AutoUsbService.class));}
 static void recordDiagnostic(final Context c,final String name,final JSONObject details){try{final Context app=c.getApplicationContext();DIAGNOSTICS.execute(new Runnable(){public void run(){try{MainActivity.record(app,name,details);}catch(Exception ignored){}}});}catch(RuntimeException ignored){}}
 void log(String text){try{recordDiagnostic(this,"AUTO_USB",new JSONObject().put("detail",text));}catch(Exception ignored){}}
 List<UsbDevice> appleDevices(){List<UsbDevice> phones=new ArrayList<UsbDevice>();for(UsbDevice d:((UsbManager)getSystemService(USB_SERVICE)).getDeviceList().values())if(d.getVendorId()==0x05ac)phones.add(d);return phones;}
 int countAppleDevices(){try{return appleDevices().size();}catch(Exception ignored){return 0;}}
 void state(String decision,int appleCount,boolean busy,long now,String errorType){try{MainActivity activity=MainActivity.active;JSONObject row=new JSONObject().put("decision",decision).put("appleDevicesPresent",Math.max(0,appleCount)).put("serviceBusy",busy).put("activityBusy",MainActivity.interactionBusy()).put("activityConnecting",activity!=null&&activity.connecting).put("activityConnectPending",activity!=null&&activity.connectPending).put("usbPermissionPending",WiredService.awaitingPermission()).put("paused",policy.isPaused()).put("retryCount",policy.retryCount()).put("retryInMs",policy.retryInMs(now)).put("launchPending",policy.launchPending(now)).put("connectAttempt",connectAttempt).put("elapsedMs",now);if(errorType!=null&&!errorType.isEmpty()){String safe=errorType.replaceAll("[^A-Za-z0-9_]","");if(safe.length()>48)safe=safe.substring(0,48);row.put("errorType",safe.isEmpty()?"Exception":safe);}recordDiagnostic(this,"AUTO_USB_STATE",row);}catch(Exception ignored){}}
 final BroadcastReceiver events=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
  String action=i.getAction();
  boolean attached=UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action);
  if(attached||UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
   UsbDevice device=(UsbDevice)i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
   if(device!=null&&device.getVendorId()==0x05ac){
    UsbManager usb=(UsbManager)getSystemService(USB_SERVICE);
    if(!attached&&!UsbAttachActivity.sameDevice(device,usb.getDeviceList().get(device.getDeviceName())))WiredService.forgetUsbEnumeration(device);
    UsbPresenceLog.recordTransition(c,attached?"attached":"detached",device);
    state(attached?"usb_attached":"usb_detached",countAppleDevices(),WiredService.isBusy(),SystemClock.elapsedRealtime(),null);
   }
  }
  if("local.headunit.diplay.STATUS".equals(action)&&"VIDEO_READY".equals(i.getStringExtra("stage"))&&
   WiredService.isVideoReady()&&WiredService.currentConnectionId().equals(i.getStringExtra("connection_id"))){
   long now=SystemClock.elapsedRealtime();policy.ready(now);
   state("video_ready",countAppleDevices(),WiredService.isBusy(),now,null);
  }
  schedule(attached?0:300);
 }};
 final Runnable tick=new Runnable(){public void run(){if(destroyed)return;if(!enabled(AutoUsbService.this)){stopSelf();return;}long delay=2000;int count=0;boolean busy=WiredService.isBusy();try{
  List<UsbDevice> phones=appleDevices();count=phones.size();
  UsbDevice currentPhone=count==1?phones.get(0):null;
  long now=SystemClock.elapsedRealtime();busy=WiredService.isBusy();
  if(count==1&&!UsbAttachActivity.sameDevice(observedPhone,currentPhone)){
   if(WiredService.tracksUsbDevice(currentPhone))observedPhone=currentPhone;
   else if(!busy){
    observedPhone=currentPhone;
    policy.deviceReplaced(now);
    state("usb_device_replaced",count,false,now,null);
   }
  }
  if(count!=lastCount){log("apple_usb_count="+count);state("usb_count_changed",count,busy,now,null);lastCount=count;}
  if(lastBusyState==null||lastBusyState.booleanValue()!=busy){state(lastBusyState==null?"watcher_state":busy?"session_busy":"session_idle",count,busy,now,null);lastBusyState=busy;}
  if(count==0){
   if(absentSince<0)absentSince=now;
   if(!busy&&blockedAfterCrash(AutoUsbService.this)&&now-absentSince>=5000){clearCrashBlock(AutoUsbService.this);policy.manualResume();state("crash_block_cleared_after_unplug",count,busy,now,null);log("crash_block_cleared_after_5s_without_iphone_usb");}
   if(busy&&now-absentSince>=2000&&WiredService.releaseMissingUsbTarget(phones))state("usb_absent_stop_session",count,true,now,null);
  }else{
   absentSince=-1;
   if(busy&&WiredService.releaseMissingUsbTarget(phones))state("usb_target_missing",count,true,now,null);
  }
  boolean occupied=count>1||busy||UsbAttachActivity.permissionFlowActive()||WiredService.awaitingPermission()||MainActivity.interactionBusy()||now<maintenanceUntil;
  boolean crashBlocked=blockedAfterCrash(AutoUsbService.this);
  if(policy.poll(now,count>0,occupied,autoReconnectEnabled(AutoUsbService.this),crashBlocked,busy)){
   connectAttempt++;state("auto_reconnect_launch",count,busy,now,null);log("launch_projection");
   try{startActivity(new Intent(AutoUsbService.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("auto_connect",true));}
   catch(RuntimeException e){policy.launchFailed(now);state("launch_failed",count,busy,now,e.getClass().getSimpleName());log("launch_failed="+e.getClass().getSimpleName());}
  }
  long retryIn=policy.retryInMs(now);long retryDeadline=retryIn>0?now+retryIn:-1;
  if(retryDeadline>0&&retryDeadline!=lastRetryDeadline){state("retry_wait",count,busy,now,null);lastRetryDeadline=retryDeadline;}else if(retryDeadline<0)lastRetryDeadline=-1;
  delay=policy.nextPollDelay(now,count>0,occupied);
 }catch(Exception e){state("watcher_scan_error",count,busy,SystemClock.elapsedRealtime(),e.getClass().getSimpleName());log("scan_failed="+e.getClass().getSimpleName());}finally{schedule(delay);}}};
 void schedule(long delay){if(!destroyed){handler.removeCallbacks(tick);handler.postDelayed(tick,delay);}}
 public void onCreate(){super.onCreate();VerifiedMfiCache.warm(this);ConnectionPrewarm.warm(this);live=this;if(blockedAfterCrash(this))policy.pause();IntentFilter f=new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);f.addAction("local.headunit.diplay.STATUS");registerReceiver(events,f);
  PendingIntent open=PendingIntent.getActivity(this,1703,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
  startForeground(1703,new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("DiPlay\u81ea\u52a8\u8fde\u63a5").setContentText(blockedAfterCrash(this)?"\u4e0a\u6b21\u8fde\u63a5\u5f02\u5e38\u9000\u51fa\uff0c\u5df2\u6682\u505c\u81ea\u52a8\u91cd\u8fde":"\u540e\u53f0\u7b49\u5f85iPhone USB\u8fde\u63a5").setContentIntent(open).setOngoing(true).build());state(blockedAfterCrash(this)?"watcher_paused_after_process_exit":"watcher_started",countAppleDevices(),WiredService.isBusy(),SystemClock.elapsedRealtime(),null);log(blockedAfterCrash(this)?"watcher_paused_after_process_exit":"watcher_started");}
 public int onStartCommand(Intent i,int flags,int id){if(!enabled(this)){stopSelf();return START_NOT_STICKY;}schedule(200);return START_STICKY;}
 public IBinder onBind(Intent i){return null;}
 public void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);unregisterReceiver(events);if(live==this)live=null;stopForeground(true);state("watcher_stopped",countAppleDevices(),WiredService.isBusy(),SystemClock.elapsedRealtime(),null);log("watcher_stopped");super.onDestroy();}
}
