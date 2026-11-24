package local.headunit.diplay;
import android.app.*;import android.os.*;import android.content.*;import android.hardware.usb.*;import android.view.*;import android.widget.*;
import android.graphics.drawable.*;
import com.shilapi.xcertplay.airplay.CarPlayMediaButton;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;

/** API17 external USB Bluetooth plus Wi-Fi CarPlay connection panel. */
public class MainActivity extends Activity {
 static final String PREF_BLUETOOTH_NAME="bluetooth_name",PREF_BT_EIR_PROFILE="bluetooth_eir_profile";static final String DEFAULT_BLUETOOTH_NAME="SG24";static final long CONNECT_QUEUE_TIMEOUT_MS=20000;
 static boolean isValidBluetoothName(String value){if(value==null)return false;String name=value.trim();if(name.length()==0)return false;int bytes=0;for(int i=0;i<name.length();i++){char c=name.charAt(i);if(Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff))return false;bytes+=c<=0x7f?1:c<=0x7ff?2:3;if(bytes>32)return false;}return true;}
 static String bluetoothName(Context context){String value=context.getSharedPreferences("hybrid_settings",MODE_PRIVATE).getString(PREF_BLUETOOTH_NAME,DEFAULT_BLUETOOTH_NAME);if("DiPlay Hybrid".equals(value))value=DEFAULT_BLUETOOTH_NAME;return isValidBluetoothName(value)?value.trim():DEFAULT_BLUETOOTH_NAME;}
 static int bluetoothEirProfile(Context context){return context.getSharedPreferences("hybrid_settings",MODE_PRIVATE).getInt(PREF_BT_EIR_PROFILE,0)==1?1:0;}
 static final String PREF_USB_BT_PEER = "usb_bt_remembered_peer";
 static final String EXTRA_RESELECT_PHONE = "reselect_phone";

 private static final class UsbBtPeerChoice {
  private static final int PENDING = -2;
  final CountDownLatch finished = new CountDownLatch(1);
  final java.util.concurrent.atomic.AtomicInteger selection =
      new java.util.concurrent.atomic.AtomicInteger(PENDING);
  AlertDialog dialog;

  void complete(int value) {
   if (selection.compareAndSet(PENDING, value)) finished.countDown();
  }
 }

 private UsbBtPeerChoice usbBtPeerChoice;

 // The service supplies a stable snapshot and waits on its connection worker.
 static int chooseUsbBluetoothPeer(String[] labels, long timeoutMillis, Callable<Boolean> cancelled)
     throws InterruptedException {
  final MainActivity owner = active;
  if (owner == null || owner.closed || labels.length == 0 || timeoutMillis <= 0
      || Looper.myLooper() == Looper.getMainLooper() || selectionCancelled(cancelled)) return -1;
  final UsbBtPeerChoice request = new UsbBtPeerChoice();
  final String[] options = labels.clone();
  for (int i = 0; i < options.length; i++) {
   String label = options[i] == null ? "" : options[i].replaceAll(
       "(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}|[\\p{Cc}\\p{Cf}]", "").trim();
   if (label.length() > 80) label = label.substring(0, 80);
   options[i] = label.isEmpty() ? "\u65e0\u540d\u79f0\u8bbe\u5907 " + (i + 1) : label;
  }
  final long startedAt = SystemClock.elapsedRealtime();
  owner.runOnUiThread(new Runnable() { public void run() {
   if (active != owner || owner.closed || !owner.resumed || owner.isFinishing()
       || !owner.connecting || owner.usbBtPeerChoice != null
       || request.selection.get() != UsbBtPeerChoice.PENDING) {
    request.complete(-1);
    return;
   }
   owner.usbBtPeerChoice = request;
   try {
    request.dialog = new AlertDialog.Builder(owner)
        .setTitle("\u9009\u62e9\u8981\u8fde\u63a5\u7684 iPhone")
        .setItems(options, new DialogInterface.OnClickListener() {
         public void onClick(DialogInterface dialog, int index) { request.complete(index); }
        })
        .setNegativeButton("\u53d6\u6d88", null)
        .create();
    request.dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
     public void onDismiss(DialogInterface dialog) {
      if (owner.usbBtPeerChoice == request) owner.usbBtPeerChoice = null;
      request.complete(-1);
     }
    });
    request.dialog.setCanceledOnTouchOutside(false);
    request.dialog.show();
   } catch (WindowManager.BadTokenException unavailable) {
    owner.usbBtPeerChoice = null;
    request.complete(-1);
   }
  }});
  try {
   while (!owner.closed && active == owner && !selectionCancelled(cancelled)) {
    long remaining = timeoutMillis - (SystemClock.elapsedRealtime() - startedAt);
    if (remaining <= 0) break;
    if (request.finished.await(Math.min(remaining, 200L), TimeUnit.MILLISECONDS)) {
     return selectionCancelled(cancelled) ? -1 : request.selection.get();
    }
   }
   return -1;
  } finally {
   request.complete(-1);
   owner.runOnUiThread(new Runnable() { public void run() {
    if (owner.usbBtPeerChoice == request) owner.cancelUsbBtPeerChoice();
   }});
  }
 }

 private static boolean selectionCancelled(Callable<Boolean> cancelled) {
  if (cancelled == null) return false;
  try { return Boolean.TRUE.equals(cancelled.call()); }
  catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return true; }
  catch (Exception unavailable) { return true; }
 }

 private void cancelUsbBtPeerChoice() {
  UsbBtPeerChoice request = usbBtPeerChoice;
  if (request == null) return;
  usbBtPeerChoice = null;
  request.complete(-1);
  if (request.dialog != null) request.dialog.dismiss();
 }
 static volatile MainActivity active;final Handler uiHandler=new Handler();boolean resumed,connectPending,permissionPending,maintenance,startupBuilding=true,uiBuilt,portReceiverRegistered,splashFrameLogged;String connectionId="";long requestStartedAt,vpnPromptAt,vpnWaitMs,connectPendingSince;
 private static final String USB_BT_PERMISSION_ACTION="local.headunit.diplay.USB_BT_PERMISSION";
 private static final long BOOT_USB_BT_POLL_MS=1200L;
 private static final long BOOT_AUTO_CONNECT_WINDOW_MS=180000L;
 private static final long BOOT_AUTO_RETRY_DELAY_MS=3000L;
 private static final long RUNTIME_RECONNECT_POLL_MS=100L;
 private static final long RUNTIME_RECONNECT_COOLDOWN_MS=90000L;
 // Only read and updated by the Activity main thread.
 private static long lastRuntimeReconnectAt=-1L;
 private static final String USB_ATTACH_TOKEN_KEY="usbAttachToken";
 private static final String USB_ATTACH_STOPPED_KEY="usbAttachStopped";
 private boolean canReselectPhone, canReviewWifi, reselectPhoneForConnection;
 private enum WifiPurpose {
  SETTINGS, CONNECT, RESELECT_PHONE;
  boolean connecting() { return this != SETTINGS; }
 }
 private int wifiSetupRequest;
 private static final String WIFI_REVIEW_FAILURE = "IOException: WIFI_CHECK_SETTINGS ";
 private static final String WIFI_OPEN_FAILURE = "IOException: WIFI_OPEN_FALLBACK ";
 private static final String P2P_HOTSPOT_ACTIVE_FAILURE = "IOException: P2P_SYSTEM_HOTSPOT_ACTIVE ";
 private static final String P2P_CONNECTION_FAILURE = "IOException: P2P_CONNECTION_FAILED ";
 private String pendingHotspotPrompt;
 private WifiPurpose hotspotPromptPurpose, hotspotSettingsReturnPurpose;
 private WifiPurpose currentWifiPurpose = WifiPurpose.CONNECT;
 private boolean leftForHotspotSettings;
 private AlertDialog hotspotPrompt;
 private enum UsbBtPermissionOperation { CONNECT, CANCELLED }
 private UsbDevice pendingUsbBtDevice, authorizedUsbBtDevice;
 private String runtimeReconnectPreviousId;
 private long runtimeReconnectQueuedAt;
 private UsbBtPermissionOperation pendingUsbBtOperation;
 private PendingIntent usbBtPermissionIntent;
 private BroadcastReceiver usbBtPermissionReceiver;
 private boolean autoConnectArmed,autoConnectRetryUsed,bootUsbBtGrantWaiting,wirelessPrewarmScheduled;
 private long autoConnectDeadlineAt,autoConnectNotBefore,pendingUsbBtPermissionAt,lastUsbBtScanLoggedAt;
 private String autoConnectWaitReason="";
 private final Set<String> recordedAutoConnectWaits=new HashSet<String>();
 private int usbBtScanAttempt,lastUsbBtCandidateCount=-1,lastUsbBtDeviceCount=-1;
 static boolean interactionBusy(){MainActivity a=active;return a!=null&&!a.closed&&(a.startupBuilding||a.busy||a.maintenance||a.permissionPending||a.connectPending||a.connecting||UsbPermissionGate.permissionFlowActive());}
 static boolean isUsbPermissionPending(){MainActivity a=active;return a!=null&&!a.closed&&a.permissionPending;}
 final ExecutorService worker=Executors.newSingleThreadExecutor();TextView status;SurfaceView preview;volatile boolean closed;volatile NetworkDeadline updateRequest;boolean busy;ScrollView controls;boolean connecting;boolean projectionMode,videoReady;ConnectionProgressView connectionProgress;View videoBlank;TextView operationStatus;LinearLayout actionRow;int actionCount;StartupSplashView startupSplash;long activityStartedAt;boolean firstFrameLogged;String uiStage="CONNECT_START",probeAddress;
 public void onCreate(Bundle state){
  super.onCreate(state);
  active=this;
  startupBuilding=true;
  activityStartedAt=SystemClock.elapsedRealtime();
  boolean bootAuthorized=consumeBootLaunch(getIntent());
  boolean autoLaunch=bootAuthorized||consumeUsbAttachLaunch(getIntent());
  if(autoLaunch)armBootAutoConnection(activityStartedAt);
  worker.execute(new Runnable(){public void run(){
   try{
    SharedPreferences oldPrefs=getSharedPreferences("hybrid_settings",MODE_PRIVATE);
    if(oldPrefs.contains("hotspot_ssid")||oldPrefs.contains("hotspot_passphrase")){
     oldPrefs.edit().remove("hotspot_ssid").remove("hotspot_passphrase").apply();
    }
    DiagnosticLog.prepare(MainActivity.this);
   }catch(Exception error){
    android.util.Log.w("DiPlayStartup","Diagnostic storage warmup failed",error);
   }
  }});
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  startupSplash=new StartupSplashView(this);
  setContentView(startupSplash);
  recordStartup("splash_attached");
  recordBootLaunch(bootAuthorized,autoLaunch);
  final StartupSplashView firstSplash=startupSplash;
  firstSplash.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
   public boolean onPreDraw(){
    if(!splashFrameLogged){
     splashFrameLogged=true;
     recordStartup("splash_first_pre_draw");
     scheduleWirelessPrewarm();
     android.view.ViewTreeObserver tree=firstSplash.getViewTreeObserver();
     if(tree.isAlive())tree.removeOnPreDrawListener(this);
    }
    return true;
   }
  });
  uiHandler.postDelayed(new Runnable(){public void run(){
   if(!closed)buildMainContent();
  }},32);
 }
 private void scheduleWirelessPrewarm(){
  if(wirelessPrewarmScheduled)return;
  wirelessPrewarmScheduled=true;
  uiHandler.post(new Runnable(){public void run(){
   if(!closed)ConnectionPrewarm.warmWireless(getApplicationContext());
  }});
 }
 private void buildMainContent(){  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(22),dp(22),dp(18));root.setBackgroundDrawable(panelBackground());
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setOrientation(0);
  ImageView logo=new ImageView(this);logo.setImageBitmap(startupSplash.logoBitmap());logo.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams logoLp=new LinearLayout.LayoutParams(dp(50),dp(50));logoLp.rightMargin=dp(13);header.addView(logo,logoLp);
  LinearLayout brand=new LinearLayout(this);brand.setOrientation(1);TextView title=new TextView(this);title.setText("DiPlay");title.setTextSize(25);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));title.setTextColor(0xfff2f7fc);brand.addView(title);TextView subtitle=new TextView(this);subtitle.setText("CM390 / CM591 \u84dd\u7259 + \u8f66\u673a Wi-Fi \u65e0\u7ebf CarPlay");subtitle.setTextSize(12);subtitle.setTextColor(0xff91a7bb);brand.addView(subtitle);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
  TextView versionBadge=new TextView(this);String version="";try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}int versionSeparator=version.indexOf('-');versionBadge.setText(versionSeparator>0?version.substring(0,versionSeparator):version);versionBadge.setTextSize(10);versionBadge.setTextColor(0xff9ecfe0);versionBadge.setPadding(dp(9),dp(6),dp(9),dp(6));GradientDrawable badge=new GradientDrawable();badge.setColor(0xff132638);badge.setCornerRadius(dp(20));badge.setStroke(dp(1),0xff284457);versionBadge.setBackgroundDrawable(badge);header.addView(versionBadge);root.addView(header);
  TextView section=new TextView(this);section.setText("\u8fde\u63a5\u72b6\u6001");section.setTextSize(12);section.setTextColor(0xff7e9ab0);section.setPadding(0,dp(25),0,dp(8));root.addView(section);
  LinearLayout statusCard=new LinearLayout(this);statusCard.setOrientation(1);statusCard.setPadding(dp(16),dp(15),dp(16),dp(15));GradientDrawable cardBg=new GradientDrawable();cardBg.setColor(0xff111e2d);cardBg.setCornerRadius(dp(16));cardBg.setStroke(dp(1),0xff263c50);statusCard.setBackgroundDrawable(cardBg);
  LinearLayout statusLine=new LinearLayout(this);statusLine.setGravity(Gravity.CENTER_VERTICAL);View statusDot=new View(this);GradientDrawable dotBg=new GradientDrawable();dotBg.setColor(0xff38c7df);dotBg.setShape(GradientDrawable.OVAL);statusDot.setBackgroundDrawable(dotBg);LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(dp(9),dp(9));dotLp.rightMargin=dp(10);statusLine.addView(statusDot,dotLp);
  status=new TextView(this);status.setTextSize(16);status.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));status.setTextColor(0xffe6f0f8);status.setText("\u7b49\u5f85 iPhone");statusLine.addView(status);statusCard.addView(statusLine);
  boolean wifiDirect = LegacyCarHotspot.usesWifiDirect(this);
  operationStatus=new TextView(this);operationStatus.setTextSize(12);operationStatus.setTextColor(0xff91a8ba);operationStatus.setPadding(dp(19),dp(5),0,0);operationStatus.setText(wifiDirect?"投屏使用车机 Wi-Fi Direct 局域网。":"通过 CM390 或 CM591 连接 iPhone，投屏使用车载热点。");operationStatus.setVisibility(View.VISIBLE);statusCard.addView(operationStatus);root.addView(statusCard);
   button(root,"\u8fde\u63a5",new Runnable(){public void run(){requestManualConnection();}});
  button(root,"\u505c\u6b62",new Runnable(){public void run(){stopConnection();}});
  if (!wifiDirect) button(root,"车载热点",new Runnable(){public void run(){showHotspotPassphraseDialog();}});
  button(root,"在线更新",new Runnable(){public void run(){CompatibilityTools.checkForUpdate(MainActivity.this);}});
  button(root,"提交日志",new Runnable(){public void run(){CompatibilityTools.open(MainActivity.this);}});
  TextView footer=new TextView(this);footer.setText("\u6295\u5c4f\u65f6\u6309\u8fd4\u56de\u952e\u6253\u5f00\u63a7\u5236\u9762\u677f");footer.setTextSize(11);footer.setTextColor(0xff71869b);footer.setPadding(0,dp(18),0,dp(4));root.addView(footer);
  preview=new ProjectionSurface(this);preview.getHolder().setFixedSize(ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT);controls=new ScrollView(this);controls.setFillViewport(true);controls.setClipToPadding(false);controls.addView(root);FrameLayout outer=new FrameLayout(this);outer.setBackgroundColor(0xff050a12);outer.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));videoBlank=new View(this);videoBlank.setBackgroundColor(0xff050a12);videoBlank.setClickable(false);outer.addView(videoBlank,new FrameLayout.LayoutParams(-1,-1));connectionProgress=new ConnectionProgressView(this);connectionProgress.setVisibility(View.GONE);outer.addView(connectionProgress,new FrameLayout.LayoutParams(-1,-1));
  controls.setBackgroundDrawable(panelBackground());int screenWidth=getResources().getDisplayMetrics().widthPixels;int panelWidth=Math.max(dp(1),Math.min(screenWidth,Math.min(dp(420),Math.max(dp(320),screenWidth*44/100))));outer.addView(controls,new FrameLayout.LayoutParams(panelWidth,-1,Gravity.LEFT));ViewGroup splashParent=(ViewGroup)startupSplash.getParent();if(splashParent!=null)splashParent.removeView(startupSplash);outer.addView(startupSplash,new FrameLayout.LayoutParams(-1,-1));setContentView(outer);recordStartup("content_attached");
  outer.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
   public boolean onPreDraw(){
    if(!firstFrameLogged){
     firstFrameLogged=true;
     recordStartup("content_first_pre_draw");
     scheduleWirelessPrewarm();
     android.view.ViewTreeObserver tree=outer.getViewTreeObserver();
     if(tree.isAlive())tree.removeOnPreDrawListener(this);
     if(startupSplash!=null&&startupSplash.getVisibility()==View.VISIBLE){
      startupSplash.animate().cancel();
      startupSplash.animate().alpha(0f).setDuration(140).withEndAction(new Runnable(){public void run(){
       if(startupSplash!=null){
        startupSplash.setVisibility(View.GONE);
        startupSplash.setClickable(false);
       }
      }});
     }
    }
    return true;
   }
  });
  registerReceiver(portStatus,new IntentFilter("local.headunit.diplay.STATUS"));portReceiverRegistered=true;preview.setOnTouchListener(new View.OnTouchListener(){public boolean onTouch(View v,MotionEvent e){WiredService.touch(e.getActionMasked(),e.getX()/Math.max(1,preview.getWidth()),e.getY()/Math.max(1,preview.getHeight()));return true;}});
  preview.getHolder().addCallback(new SurfaceHolder.Callback(){
   public void surfaceCreated(SurfaceHolder holder){onProjectionSurfaceAvailable();}
   public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){onProjectionSurfaceAvailable();}
   public void surfaceDestroyed(SurfaceHolder holder){WiredService.detachSurface(MainActivity.this);lifecycle("surface_destroyed");}
  });
  uiBuilt=true;startupBuilding=false;if(resumed){restoreConnection();refreshVideoBlank();lifecycle("resume");}if(connectPending)scheduleConnection(0);

 }

 private boolean isProjectionSurfaceReady(){
  return preview!=null&&preview.getHolder().getSurface().isValid();
 }
 private void onProjectionSurfaceAvailable(){
  if(closed||!resumed||!isProjectionSurfaceReady())return;
  WiredService.attachSurface(this,preview.getHolder().getSurface());
  if(connectPending)scheduleConnection(0);
 }
 private boolean waitForProjectionDialog(){
  if(resumed&&isProjectionSurfaceReady())return false;
  connectPending=true;
  recordBootWait(resumed?"surface_not_ready":"activity_background");
  // Surface creation or activity resume wakes the request. Preserve the boot deadline.
  if(resumed&&autoConnectArmed)scheduleConnection(Math.max(0,autoConnectDeadlineAt-SystemClock.elapsedRealtime()));
  return true;
 }

 private void recordStartup(final String stage){final long elapsed=Math.max(0,SystemClock.elapsedRealtime()-activityStartedAt);try{worker.execute(new Runnable(){public void run(){try{record(MainActivity.this,"APP_STARTUP",new JSONObject().put("stage",stage).put("elapsedMs",elapsed));}catch(Exception ignored){}}});}catch(RuntimeException ignored){}}
 private boolean consumeBootLaunch(Intent launch) {
  if(launch==null||!launch.getBooleanExtra("boot_launch",false)||!launch.getBooleanExtra("auto_connect",false))return false;
  SharedPreferences state=getSharedPreferences(BootAutoStartReceiver.PREFS,MODE_PRIVATE);
  String token=launch.getStringExtra("boot_launch_token");
  if(token==null||token.length()!=36||!token.equals(state.getString(BootAutoStartReceiver.LAUNCH_TOKEN_KEY,"")))return false;
  state.edit().remove(BootAutoStartReceiver.LAUNCH_TOKEN_KEY).remove(USB_ATTACH_STOPPED_KEY).apply();
  return true;
 }
 private boolean consumeUsbAttachLaunch(Intent launch) {
  if(launch==null)return false;
  String token=launch.getStringExtra("usb_attach_token");
  if(token==null||token.length()!=36)return false;
  SharedPreferences state=getSharedPreferences(BootAutoStartReceiver.PREFS,MODE_PRIVATE);
  if(state.getBoolean(USB_ATTACH_STOPPED_KEY,false)
      ||!token.equals(state.getString(USB_ATTACH_TOKEN_KEY,"")))return false;
  state.edit().remove(USB_ATTACH_TOKEN_KEY).apply();
  return true;
 }
 private void armBootAutoConnection(long now){
  clearHotspotRecovery();
  reselectPhoneForConnection=false;
  connectPending=true;
  connectPendingSince=now;
  autoConnectArmed=true;
  autoConnectDeadlineAt=now+BOOT_AUTO_CONNECT_WINDOW_MS;
   autoConnectNotBefore=0;
   autoConnectRetryUsed=false;
   bootUsbBtGrantWaiting=false;
   resetBootWaitEvidence();
 }
 private void clearBootAutoConnection() {
  autoConnectArmed=false;
  autoConnectNotBefore=0;
  autoConnectRetryUsed=false;
  bootUsbBtGrantWaiting=false;
  resetBootWaitEvidence();
 }
 private void resetBootWaitEvidence(){
  autoConnectWaitReason="";
  recordedAutoConnectWaits.clear();
 }
 private void recordBootWait(String reason){
  if(!autoConnectArmed)return;
  autoConnectWaitReason=reason;
  if(recordedAutoConnectWaits.add(reason))recordAutoConnectDecision("queue_wait",reason,null,0);
 }
 private void recordAutoConnectDecision(String stage,String reason,String errorType,long retryDelayMs){
  if(!autoConnectArmed)return;
  long now=SystemClock.elapsedRealtime();
  try{
   JSONObject row=new JSONObject().put("stage",stage).put("reason",reason)
       .put("autoConnectRequested",true).put("paused",!resumed)
       .put("surfaceValid",preview!=null&&preview.getHolder().getSurface().isValid())
       .put("serviceBusy",WiredService.isBusy()).put("connectPending",connectPending)
       .put("usbPermissionPending",permissionPending)
       .put("elapsedMs",Math.max(0,now-(autoConnectDeadlineAt-BOOT_AUTO_CONNECT_WINDOW_MS)))
       .put("retryCount",autoConnectRetryUsed?1:0);
   if(errorType!=null)row.put("errorType",errorType);
   if(retryDelayMs>0)row.put("retryInMs",retryDelayMs);
   record(this,"BOOT_AUTOSTART",row);
  }catch(Exception ignored){}
 }
 private void recordBootLaunch(final boolean bootLaunch,final boolean autoConnect){
  final long capturedAt=SystemClock.elapsedRealtime();
  final long activityElapsed=Math.max(0,capturedAt-activityStartedAt);
  final boolean isConnecting=connecting;
  final boolean isConnectPending=connectPending;
  final boolean isUsbPermissionPending=permissionPending;
  // A durable boot record must not hold up the Wi-Fi settings and manual connection worker.
  try{AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable(){public void run(){
   try{
    SharedPreferences state=getSharedPreferences(BootAutoStartReceiver.PREFS,MODE_PRIVATE);
    long recordedAt=state.getLong("elapsedMs",-1);
    long receiverAge=recordedAt>=0?capturedAt-recordedAt:-1;
    boolean withinWindow=receiverAge>=0&&receiverAge<=86400000L;
    String origin=bootLaunch?"boot_receiver":autoConnect?"usb_attach":"ordinary_activity";
    JSONObject row=new JSONObject().put("stage","activity_entry").put("origin",origin)
        .put("bootLaunch",bootLaunch).put("autoConnectRequested",autoConnect)
        .put("receiverAttempted",withinWindow).put("connecting",isConnecting)
        .put("connectPending",isConnectPending).put("usbPermissionPending",isUsbPermissionPending)
        .put("sdk",Build.VERSION.SDK_INT).put("elapsedMs",activityElapsed);
    if(withinWindow){
     String stage=state.getString("stage","unknown");
     String error=state.getString("errorType","");
     row.put("receiverStage",stage.matches("[A-Za-z0-9_]{1,48}")?stage:"unknown")
         .put("receiverAgeMs",receiverAge);
     if(error.matches("[A-Za-z0-9_]{1,48}"))row.put("receiverErrorType",error);
    }
    DiagnosticLog.recordImmediately(MainActivity.this,"BOOT_AUTOSTART",row);
    if(receiverAge>=0&&state.getLong("elapsedMs",-1)==recordedAt
        &&state.getString(BootAutoStartReceiver.LAUNCH_TOKEN_KEY,"").isEmpty()){
     state.edit().remove("stage").remove("errorType").remove("elapsedMs").apply();
    }
   }catch(Exception ignored){}
  }});}catch(RuntimeException ignored){}
 }

 int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

 private void showHotspotPassphraseDialog() {
  if (busy || maintenance || closed) return;
  if (connecting || connectPending || permissionPending || WiredService.isBusy()) {
   showOperation("停止当前连接后，可调整车载热点设置。");
   return;
  }
  if (!LegacyCarHotspot.isSupported(this)) {
   checkWifiSetup(WifiPurpose.SETTINGS);
   return;
  }
  LinearLayout options = new LinearLayout(this);
  options.setOrientation(LinearLayout.VERTICAL);
  options.setPadding(dp(20), 0, dp(20), 0);
  CheckBox autoStart = new CheckBox(this);
  autoStart.setText("连接时自动开启热点");
  autoStart.setChecked(LegacyCarHotspot.isAutoStartEnabled(this));
  autoStart.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
   public void onCheckedChanged(CompoundButton button, boolean checked) {
    LegacyCarHotspot.setAutoStartEnabled(MainActivity.this, checked);
    showOperation(checked ? "下次连接将自动开启车载热点。" : "请在连接前手动开启车载热点。");
   }
  });
  options.addView(autoStart);
  CheckBox mobileDataOff = new CheckBox(this);
  mobileDataOff.setText("连接前关闭车机移动数据");
  mobileDataOff.setChecked(LegacyCarHotspot.isMobileDataIsolationEnabled(this));
  mobileDataOff.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
   public void onCheckedChanged(CompoundButton button, boolean checked) {
    LegacyCarHotspot.setMobileDataIsolationEnabled(MainActivity.this, checked);
   }
  });
  options.addView(mobileDataOff);
  Button mobileDataSettings = new Button(this);
  mobileDataSettings.setText("车机移动数据设置");
  options.addView(mobileDataSettings);
  final AlertDialog hotspotSettings = new AlertDialog.Builder(this)
      .setTitle("车载 Wi-Fi 设置")
      .setMessage("关闭车机移动数据可排查热点掉线，也会影响车机联网。测试后可在移动数据设置中恢复。")
      .setView(options)
      .setPositiveButton("完成", null)
      .setNeutralButton("查看口令设置", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface ignored, int which) { checkWifiSetup(WifiPurpose.SETTINGS); }
      })
      .setNegativeButton("系统热点设置", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface ignored, int which) { openSystemHotspotSettings(); }
      }).show();
  mobileDataSettings.setOnClickListener(new View.OnClickListener() {
   public void onClick(View view) {
    hotspotSettings.dismiss();
    openVehicleMobileDataSettings();
   }
  });
 }

 private void openVehicleMobileDataSettings() {
  if (closed || busy || maintenance) return;
  if (connecting || connectPending || permissionPending || WiredService.isBusy()) {
   showOperation("停止当前连接后，可调整车机移动数据。");
   return;
  }
  showOperation("测试热点稳定性时可关闭车机移动数据，iPhone 蜂窝网络保持开启。需要车机独立联网时，从此处恢复。");
  try {
   startActivity(new Intent(Intent.ACTION_MAIN)
       .setClassName("com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));
  } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
   try {
    startActivity(new Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS));
   } catch (android.content.ActivityNotFoundException | SecurityException noMobileSettings) {
    showOperation("请在车机系统设置的「流量使用情况」或「移动网络」中调整移动数据。");
   }
  }
 }

 private void checkWifiSetup(WifiPurpose purpose) { checkWifiSetup(purpose, false); }

 private void checkWifiSetup(final WifiPurpose purpose, final boolean afterOpeningSettings) {
  final boolean connectAfter = purpose.connecting();
  if (busy || maintenance || closed) return;
  if (!connectAfter && (connectPending || permissionPending)) {
   showOperation("正在准备连接。停止后可修改车载热点口令。");
   return;
  }
  if (connectAfter) {
   // Manual connection now owns the flow, even if the boot queue was waiting for USB.
   clearHotspotRecovery();
   recordAutoConnectDecision("queue_cancelled","manual_connect",null,0);
   clearBootAutoConnection();
   clearRuntimeReconnect();
   requestStartedAt = 0;
   connectPending = false;
   connectPendingSince = 0;
   uiHandler.removeCallbacks(tryConnection);
  }
  if (LegacyCarHotspot.usesWifiDirect(this)) {
   if (connectAfter) enqueueManualConnection(purpose);
   return;
  }
  final int request = ++wifiSetupRequest;
  busy = true;
  showOperation("正在检查车机系统热点…");
  try {
   worker.execute(new Runnable() { public void run() {
    HybridWifiSetup found = null;
    String failureReason = null;
    final boolean requireStarted = afterOpeningSettings
        || (connectAfter && !LegacyCarHotspot.isAutoStartEnabled(MainActivity.this));
    final long checkUntil = SystemClock.elapsedRealtime() + (afterOpeningSettings ? 2500L : 0L);
    do {
     try {
      found = requireStarted ? HybridNetwork.requireWifiSetup(MainActivity.this)
          : HybridNetwork.readConfiguredWifiSetup(MainActivity.this);
      break;
     } catch (HybridHotspotException unavailable) {
      failureReason = unavailable.getReason();
     } catch (RuntimeException unavailable) {
      failureReason = "ap_config_unavailable";
     }
     if (!afterOpeningSettings || !"ap_not_enabled".equals(failureReason)
         || closed || SystemClock.elapsedRealtime() >= checkUntil) break;
     // The user may return while Android is still bringing the hotspot up.
     SystemClock.sleep(250L);
    } while (!closed);
    final HybridWifiSetup setup = found;
    final String reason = failureReason;
    runOnUiThread(new Runnable() { public void run() {
     busy = false;
     if (closed || isFinishing() || request != wifiSetupRequest || connecting) return;
     if (setup == null && afterOpeningSettings && "ap_not_enabled".equals(reason)) {
      showOperation("车载热点未开启。开启后可点击「连接」。");
     } else if (setup == null) {
      showHotspotNotReady(reason, connectAfter ? purpose : null);
     } else if (resumed && (!afterOpeningSettings || hasWindowFocus())) {
      useWifiSetup(setup, purpose);
     } else if (afterOpeningSettings) {
      // Defer one continuation if the OEM has put a system screen over DiPlay.
      hotspotSettingsReturnPurpose = purpose;
      leftForHotspotSettings = true;
     }
    }});
   }});
  } catch (RejectedExecutionException rejected) {
   busy = false;
   showOperation("暂时无法检查车载热点，请重试。");
  }
 }

 private void showHotspotNotReady(String reason, WifiPurpose purpose) {
  String detail;
  if ("ap_security_unsupported".equals(reason)) {
   detail = "请在系统热点设置中选择 WPA2-PSK 加密，保存后重试。";
  } else if ("ap_ssid_invalid".equals(reason)) {
   detail = "请在系统热点设置中填写有效的热点名称（最多 32 个 UTF-8 字节），保存后重试。";
  } else if ("ap_not_enabled".equals(reason)) {
   detail = "请在车机系统设置中开启便携式 Wi-Fi 热点。";
  } else {
   detail = "暂时读不到系统热点配置。请检查车载热点设置，保存后重试。";
  }
  queueHotspotPrompt(detail, purpose);
 }

 private void queueHotspotPrompt(String detail, WifiPurpose purpose) {
  pendingHotspotPrompt = detail;
  hotspotPromptPurpose = purpose;
  showOperation(detail);
  continueHotspotRecovery();
 }

 private void clearHotspotRecovery() {
  pendingHotspotPrompt = null;
  hotspotPromptPurpose = null;
  hotspotSettingsReturnPurpose = null;
  leftForHotspotSettings = false;
  if (hotspotPrompt != null) hotspotPrompt.dismiss();
 }

 private void continueHotspotRecovery() {
  if (active != this || closed || !resumed || !hasWindowFocus() || isFinishing()
      || !uiBuilt || busy || maintenance || connecting || connectPending || permissionPending || videoReady) return;
  if (hotspotSettingsReturnPurpose != null && leftForHotspotSettings) {
   WifiPurpose purpose = hotspotSettingsReturnPurpose;
   clearHotspotRecovery();
   checkWifiSetup(purpose, true);
   return;
  }
  if (pendingHotspotPrompt == null || hotspotPrompt != null) return;
  final WifiPurpose purpose = hotspotPromptPurpose;
  boolean p2p = LegacyCarHotspot.usesWifiDirect(this);
  final AlertDialog dialog = new AlertDialog.Builder(this)
      .setTitle(p2p ? "关闭系统热点" : "车载热点未就绪")
      .setMessage(pendingHotspotPrompt + (purpose == null ? "" : "\n打开或修正热点后返回 DiPlay，将继续连接。"))
      .setPositiveButton("打开热点设置", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface ignored, int which) {
        clearHotspotRecovery();
        openSystemHotspotSettings(purpose);
       }
      })
      .setNegativeButton("取消", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface ignored, int which) { clearHotspotRecovery(); }
      }).create();
  dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
   public void onCancel(DialogInterface ignored) { clearHotspotRecovery(); }
  });
  dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
   public void onDismiss(DialogInterface ignored) { if (hotspotPrompt == dialog) hotspotPrompt = null; }
  });
  hotspotPrompt = dialog;
  try {
   dialog.show();
  } catch (WindowManager.BadTokenException unavailable) {
   hotspotPrompt = null;
  }
 }

 private void openSystemHotspotSettings() { openSystemHotspotSettings(null); }

 private void openSystemHotspotSettings(WifiPurpose resumePurpose) {
  boolean p2p = LegacyCarHotspot.usesWifiDirect(this);
  hotspotSettingsReturnPurpose = resumePurpose;
  leftForHotspotSettings = false;
  try {
   startActivity(new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"));
  } catch (RuntimeException unavailable) {
   try {
    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
   } catch (RuntimeException noSettings) {
    hotspotSettingsReturnPurpose = null;
    showOperation(p2p ? "请手动关闭车机系统热点。" : "请手动打开车机系统设置里的便携式 Wi-Fi 热点。");
    return;
   }
  }
  showOperation(p2p ? "关闭车机系统热点后，返回 DiPlay 点「连接」。"
      : resumePurpose == null ? "开启车载热点后，返回 DiPlay 点「连接」。"
      : "在系统中打开车载热点后返回 DiPlay，将继续连接。");
 }


 private void useWifiSetup(final HybridWifiSetup setup, final WifiPurpose purpose) {
  boolean connectAfter = purpose.connecting();
  HybridWifiCredential credential = setup.getCredential();
  String source = credential.getSource();
  boolean systemProvidesCredential = "android_softap_configuration".equals(source)
      || "android_softap_configuration_open".equals(source);
  if (connectAfter && credential.getAvailable() && !canReviewWifi) {
   connectWithWifiSetup(setup, purpose);
   return;
  }
  if (systemProvidesCredential) {
   boolean open = "android_softap_configuration_open".equals(source);
   String detail = open ? "此热点未设置口令。" : "投屏使用车机系统中保存的热点口令，可在热点设置中修改。";
   AlertDialog.Builder dialog = new AlertDialog.Builder(this)
       .setTitle(connectAfter ? "检查车载热点" : "当前车载热点")
       .setMessage("车载热点「" + setup.getSsid() + "」。" + detail
           + (connectAfter ? "可先在 iPhone 的 Wi-Fi 列表中手动加入这个热点，再重试。" : ""))
       .setNegativeButton(connectAfter ? "取消" : "关闭", null);
   DialogInterface.OnClickListener settings = new DialogInterface.OnClickListener() {
    public void onClick(DialogInterface ignored, int which) { openSystemHotspotSettings(); }
   };
   if (connectAfter) {
    dialog.setPositiveButton("重试", new DialogInterface.OnClickListener() {
     public void onClick(DialogInterface ignored, int which) { connectWithWifiSetup(setup, purpose); }
    }).setNeutralButton("热点设置", settings);
   } else {
    dialog.setPositiveButton("热点设置", settings);
   }
   showOperation(detail);
   dialog.show();
   return;
  }
  showWifiPassphraseEditor(setup, purpose);
 }

 private void connectWithWifiSetup(HybridWifiSetup setup, WifiPurpose purpose) {
  if (HybridNetwork.isCurrentWifiSetup(this, setup)) {
   enqueueManualConnection(purpose);
  } else {
   wifiSetupChanged(purpose);
  }
 }

 private void wifiSetupChanged(WifiPurpose purpose) {
  Toast.makeText(this, "车载热点配置已变化，请重新核对。", Toast.LENGTH_SHORT).show();
  checkWifiSetup(purpose);
 }

 private void showWifiPassphraseEditor(final HybridWifiSetup setup, final WifiPurpose purpose) {
  final boolean connectAfter = purpose.connecting();
  final boolean haveSaved = setup.getHasSavedPassphrase();
  final EditText input = new EditText(this);
  input.setSingleLine(true);
  input.setSaveEnabled(false);
  input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
  input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
  input.setHint(haveSaved ? "输入新口令" : "输入车载热点口令");

  CheckBox reveal = new CheckBox(this);
  reveal.setText("显示口令");
  reveal.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
   public void onCheckedChanged(CompoundButton button, boolean checked) {
    input.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
    input.setSelection(input.length());
   }
  });
  LinearLayout inputBox = new LinearLayout(this);
  inputBox.setOrientation(LinearLayout.VERTICAL);
  inputBox.setPadding(dp(24), dp(8), dp(24), 0);
  inputBox.addView(input, new LinearLayout.LayoutParams(-1, -2));
  inputBox.addView(reveal, new LinearLayout.LayoutParams(-1, -2));

  String detail = haveSaved
      ? "已保存此车载热点的口令。改过热点密码时，请在这里更新。"
      : "车机系统未提供可用口令。请填写车机系统热点的口令，CarPlay 会把它发给配对的 iPhone。";
  showOperation(detail);
  AlertDialog.Builder builder = new AlertDialog.Builder(this)
      .setTitle(connectAfter ? "CarPlay 热点口令" : "设置车载热点口令")
      .setMessage("车载热点「" + setup.getSsid() + "」。\n" + detail)
      .setView(inputBox)
      .setPositiveButton(connectAfter ? "保存并连接" : "保存", null)
      .setNegativeButton("取消", null);
  if (connectAfter && haveSaved) {
   builder.setNeutralButton("用已保存口令重试",
       new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface ignored, int which) { connectWithWifiSetup(setup, purpose); }
       });
  } else if (haveSaved) {
   builder.setNeutralButton("清除已保存口令", new DialogInterface.OnClickListener() {
    public void onClick(DialogInterface ignored, int which) {
     if (HybridNetwork.clearHotspotPassphrase(MainActivity.this, setup)) {
      showOperation("已清除应用保存的车载热点口令。");
     } else {
      wifiSetupChanged(WifiPurpose.SETTINGS);
     }
    }
   });
  }
  final AlertDialog dialog = builder.create();
  dialog.show();
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
   public void onClick(View view) {
    String passphrase = input.getText().toString();
    if (!HybridNetwork.isHotspotPassphrase(passphrase)) {
     input.setError("请输入 8–63 个可打印 ASCII 字符");
     return;
    }
    dialog.dismiss();
    if (!HybridNetwork.saveHotspotPassphrase(MainActivity.this, setup, passphrase)) {
     wifiSetupChanged(purpose);
     return;
    }
    canReviewWifi = false;
    if (connectAfter) {
     enqueueManualConnection(purpose);
    } else {
     showOperation("已保存车载热点口令。");
    }
   }
  });
 }

 Drawable panelBackground(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xf5121f30,0xf60b1421,0xf6080e18});d.setStroke(dp(1),0x222f5368);return d;}
 Drawable buttonBackground(String label,boolean pressed){boolean primary=label.equals("\u8fde\u63a5"),stop=label.equals("\u505c\u6b62");int color=primary?(pressed?0xff126880:0xff0e7899):stop?(pressed?0xff3b4755:0xff202d3b):(pressed?0xff263e52:0xff19293a);int stroke=primary?0xff35b8d3:stop?0xff465b6d:0xff344c60;GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));d.setStroke(dp(1),stroke);return d;}
 void button(LinearLayout root, final String label, final Runnable action) {
  Button button = new Button(this);
  button.setText(label);
  button.setAllCaps(false);
  button.setTextSize(14);
  button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
  button.setTextColor("连接".equals(label) ? 0xfff4fcff : 0xffe2edf6);
  StateListDrawable states = new StateListDrawable();
  states.addState(new int[]{android.R.attr.state_pressed}, buttonBackground(label, true));
  states.addState(new int[]{}, buttonBackground(label, false));
  button.setBackgroundDrawable(states);
  button.setPadding(dp(6), 0, dp(6), 0);
  button.setOnClickListener(new View.OnClickListener() {
   public void onClick(View view) {
    if ("停止".equals(label) || "连接".equals(label)) {
     action.run();
     return;
    }
    if (busy || maintenance) {
     showOperation("正在处理上一项操作，请稍候");
     return;
    }
    if (connecting && !(videoReady && "提交日志".equals(label))) {
     showOperation("请先停止连接再操作");
     return;
    }
    action.run();
   }
  });
  boolean firstInRow = actionCount % 2 == 0;
  if (firstInRow) {
   actionRow = new LinearLayout(this);
   actionRow.setOrientation(LinearLayout.HORIZONTAL);
   LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
   row.topMargin = dp(10);
   root.addView(actionRow, row);
  }
  LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(50), 1);
  if (firstInRow && !"提交日志".equals(label)) layout.rightMargin = dp(5);
  if (!firstInRow) layout.leftMargin = dp(5);
  actionRow.addView(button, layout);
  actionCount++;
 }
 private void requestUsbBluetoothPermission(final UsbManager manager,final UsbDevice device){
  if(manager==null||device==null)return;
  if(permissionPending){
   if(sameUsbDevice(device,pendingUsbBtDevice)){
    pendingUsbBtOperation=UsbBtPermissionOperation.CONNECT;
   }
   return;
  }
  if(manager.hasPermission(device)){
   recordUsbBluetoothPermission("already_granted",device,true,true,-1);
   connectPort(device);
   return;
  }
  if(waitForBootUsbBluetoothGrant(device)||waitForProjectionDialog())return;

  final String resultAction=USB_BT_PERMISSION_ACTION+"."+UUID.randomUUID().toString();
  permissionPending=true;
  pendingUsbBtDevice=device;
  pendingUsbBtOperation=UsbBtPermissionOperation.CONNECT;
  pendingUsbBtPermissionAt=SystemClock.elapsedRealtime();
  clearBootAutoConnection();
  try{
   usbBtPermissionReceiver=new BroadcastReceiver(){
    public void onReceive(Context context,Intent intent){
     String action=intent.getAction();
     Object supplied=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
     UsbDevice reported=supplied instanceof UsbDevice?(UsbDevice)supplied:null;
     if(!sameUsbDevice(device,reported)||!sameUsbDevice(pendingUsbBtDevice,reported))return;
     UsbDevice current=currentUsbDevice(manager,reported);
     if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
      if(current==null)finishUsbBluetoothPermission(device,null,"permission_device_detached");
      return;
     }
     if(!resultAction.equals(action))return;
     boolean allowed=intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)
         &&current!=null&&manager.hasPermission(current);
     finishUsbBluetoothPermission(device,allowed?current:null,"permission_result");
    }
   };
   IntentFilter callbacks=new IntentFilter(resultAction);
   callbacks.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
   registerReceiver(usbBtPermissionReceiver,callbacks);
   Intent permissionIntent=new Intent(resultAction).setPackage(getPackageName());
   usbBtPermissionIntent=PendingIntent.getBroadcast(this,0,permissionIntent,PendingIntent.FLAG_ONE_SHOT);
   manager.requestPermission(device,usbBtPermissionIntent);
   recordUsbBluetoothPermission("request_sent",device,true,false,-1);
   showOperation("\u8bf7\u5728\u7cfb\u7edf USB \u6388\u6743\u7a97\u53e3\u4e2d\u5141\u8bb8\u672c\u5e94\u7528\u8bbf\u95ee\u84dd\u7259\u9002\u914d\u5668\u3002");
  }catch(RuntimeException error){
   clearUsbBluetoothPermission();
   resetPermissionConnectionRequest();
   try{record(this,"USB_BT_PERMISSION",new JSONObject().put("stage","permission_request_failed")
       .put("deviceVid",device.getVendorId()).put("devicePid",device.getProductId())
       .put("connectAfterGrant",true).put("errorType",error.getClass().getSimpleName()));}catch(Exception ignored){}
   showOperation("\u8bf7\u6c42 USB \u84dd\u7259\u6743\u9650\u5931\u8d25\uff1a"+error.getClass().getSimpleName());
  }
 }
 private boolean waitForBootUsbBluetoothGrant(UsbDevice device){
  if(!autoConnectArmed||!UsbBluetoothProbe.hasUsbAttachHandler(device.getVendorId(),device.getProductId()))return false;
  connectPending=true;
  recordBootWait("usb_system_grant");
  if(!bootUsbBtGrantWaiting){
   bootUsbBtGrantWaiting=true;
   recordUsbBluetoothPermission("waiting_for_system_grant",device,true,false,-1);
   showOperation("等待系统 USB 授权。点击「连接」可手动申请。");
  }
  // Android grants the default USB handler before starting its attach Activity.
  // Recheck the grant even when the OEM does not launch that Activity promptly.
  scheduleConnection(Math.min(BOOT_USB_BT_POLL_MS,Math.max(0,autoConnectDeadlineAt-SystemClock.elapsedRealtime())));
  return true;
 }
 static void onUsbBluetoothSystemGrant(Context context){
  SharedPreferences state=context.getSharedPreferences(BootAutoStartReceiver.PREFS,MODE_PRIVATE);
  if(state.getBoolean(USB_ATTACH_STOPPED_KEY,false))return;
  MainActivity owner=active;
  long now=SystemClock.elapsedRealtime();
  if(owner!=null&&!owner.closed){
   if(!owner.autoConnectArmed||owner.connecting||owner.permissionPending||now>=owner.autoConnectDeadlineAt)return;
   if(!owner.connectPending||owner.connectPendingSince==0)owner.connectPendingSince=now;
   owner.connectPending=true;
   owner.scheduleConnection(0);
   return;
  }
  if(!WiredService.currentConnectionId().isEmpty())return;
  String token=UUID.randomUUID().toString();
  state.edit().putString(USB_ATTACH_TOKEN_KEY,token).apply();
  try{
   context.startActivity(new Intent(context,MainActivity.class).putExtra("usb_attach_token",token)
       .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }catch(RuntimeException unavailable){
   state.edit().remove(USB_ATTACH_TOKEN_KEY).apply();
   android.util.Log.w("DiPlayUsb","Could not launch after system USB grant",unavailable);
  }
 }
 private void finishUsbBluetoothPermission(UsbDevice requested,UsbDevice granted,String stage){
  UsbBtPermissionOperation operation=pendingUsbBtOperation;
  long elapsed=SystemClock.elapsedRealtime()-pendingUsbBtPermissionAt;
  clearUsbBluetoothPermission();
  boolean connect=operation==UsbBtPermissionOperation.CONNECT;
  recordUsbBluetoothPermission(stage,requested,connect,granted!=null,elapsed);
  if(closed||operation==UsbBtPermissionOperation.CANCELLED)return;
  if(granted==null){
   resetPermissionConnectionRequest();
   showOperation("未获得 USB 蓝牙权限。点击「连接」可重试。");
  }else{
   authorizedUsbBtDevice=granted;
   connectPending=true;
   connectPendingSince=SystemClock.elapsedRealtime();
   if(resumed)scheduleConnection(0);
  }
 }
 private void clearUsbBluetoothPermission(){
  if(usbBtPermissionReceiver!=null){
   try{unregisterReceiver(usbBtPermissionReceiver);}catch(IllegalArgumentException ignored){}
   usbBtPermissionReceiver=null;
  }
  if(usbBtPermissionIntent!=null){
   usbBtPermissionIntent.cancel();
   usbBtPermissionIntent=null;
  }
  permissionPending=false;
  pendingUsbBtDevice=null;
  pendingUsbBtOperation=null;
  pendingUsbBtPermissionAt=0;
 }
 private void resetPermissionConnectionRequest(){
  clearRuntimeReconnect();
  reselectPhoneForConnection=false;
  requestStartedAt=0;
  vpnWaitMs=0;
  connectPending=false;
  connectPendingSince=0;
 }
 private void clearRuntimeReconnect(){
  if(runtimeReconnectPreviousId==null)return;
  runtimeReconnectPreviousId=null;
  runtimeReconnectQueuedAt=0;
  authorizedUsbBtDevice=null;
 }

 private void recordRuntimeReconnect(String stage,String reason){
  try{record(this,"HYBRID_RUNTIME_RECOVERY",new JSONObject().put("stage",stage).put("reason",reason));}
  catch(Exception ignored){}
 }

 private boolean enqueueRuntimeReconnect(UsbDevice previous,String previousId,long now){
  if(previous==null||closed)return false;
  if(lastRuntimeReconnectAt>=0&&now-lastRuntimeReconnectAt<RUNTIME_RECONNECT_COOLDOWN_MS){
   recordRuntimeReconnect("retry_skipped","cooldown_active");
   return false;
  }
  UsbManager manager=(UsbManager)getSystemService(Context.USB_SERVICE);
  UsbDevice current=currentUsbDevice(manager,previous);
  if(current==null||!UsbBluetoothProbe.isCandidate(current)||!manager.hasPermission(current)){
   recordRuntimeReconnect("retry_skipped","adapter_or_permission_unavailable");
   return false;
  }
  clearHotspotRecovery();
  reselectPhoneForConnection=false;
  runtimeReconnectPreviousId=previousId;
  runtimeReconnectQueuedAt=now;
  authorizedUsbBtDevice=current;
  lastRuntimeReconnectAt=now;
  connectPending=true;
  connectPendingSince=now;
  recordRuntimeReconnect("retry_queued","confirmed_runtime_failure");
  if(connectionProgress!=null)connectionProgress.resetForAttempt();
  setConnectionStage("BT_PHONE_RETRY","正在释放上一次投屏，随后自动重连。");
  setProjectionMode(projectionMode);
  status.setText("正在恢复 CarPlay");
  showOperation("等上一次连接释放后，将自动重连一次。");
  scheduleConnection(0);
  return true;
 }

 private boolean expireRuntimeReconnect(long now){
  if(runtimeReconnectPreviousId==null||now-runtimeReconnectQueuedAt<CONNECT_QUEUE_TIMEOUT_MS)return false;
  recordRuntimeReconnect("retry_expired","wait_for_resources");
  resetPermissionConnectionRequest();
  setProjectionMode(false);
  status.setText("连接未完成");
  showOperation("自动恢复已超时。点击「连接」可手动重连。");
  return true;
 }
 private void recordUsbBluetoothPermission(String stage,UsbDevice device,boolean connect,boolean granted,long elapsed){
  try{
   JSONObject details=new JSONObject().put("stage",stage).put("deviceVid",device.getVendorId())
       .put("devicePid",device.getProductId()).put("connectAfterGrant",connect).put("granted",granted);
   if(elapsed>=0)details.put("elapsedMs",elapsed);
   if("request_sent".equals(stage))details.put("alreadyGranted",false);
   record(this,"USB_BT_PERMISSION",details);
  }catch(Exception ignored){}
 }
 private static boolean sameUsbDevice(UsbDevice expected,UsbDevice actual){
  return expected!=null&&actual!=null&&expected.getVendorId()==actual.getVendorId()
      &&expected.getProductId()==actual.getProductId()&&expected.getDeviceId()==actual.getDeviceId()
      &&expected.getDeviceName().equals(actual.getDeviceName());
 }
 static UsbDevice currentUsbDevice(UsbManager manager,UsbDevice expected){
  if(manager==null||expected==null)return null;
  UsbDevice current=manager.getDeviceList().get(expected.getDeviceName());
  return sameUsbDevice(expected,current)?current:null;
 }
 void stopConnection(){cancelUsbBtPeerChoice();stopConnectionCore();}
 void stopConnectionCore(){
  wifiSetupRequest++;
  clearHotspotRecovery();
  recordAutoConnectDecision("queue_cancelled","user_stop",null,0);
  if(runtimeReconnectPreviousId!=null)recordRuntimeReconnect("retry_cancelled","user_stop");
  resetPermissionConnectionRequest();
  clearBootAutoConnection();
  getSharedPreferences(BootAutoStartReceiver.PREFS,MODE_PRIVATE).edit()
      .putBoolean(USB_ATTACH_STOPPED_KEY,true).remove(USB_ATTACH_TOKEN_KEY).apply();
  authorizedUsbBtDevice=null;
  if(permissionPending)pendingUsbBtOperation=UsbBtPermissionOperation.CANCELLED;
  uiHandler.removeCallbacks(tryConnection);connectionId="stopped-"+UUID.randomUUID();WiredService.stop(this);connecting=false;videoReady=false;uiStage="STOPPED";setProjectionMode(false);show("已停止连接。");showOperation("点击「连接」可重新连接。");
 }
 void scheduleConnection(long delay) {
  uiHandler.removeCallbacks(tryConnection);
  if(closed)return;
  if(autoConnectArmed){
   long now=SystemClock.elapsedRealtime();
   delay=Math.min(Math.max(delay,autoConnectNotBefore-now),Math.max(0,autoConnectDeadlineAt-now));
  }
  uiHandler.postDelayed(tryConnection,delay);
 }
 void deferConnection(long delay) {
  if(autoConnectArmed){scheduleConnection(delay);return;}
  long now=SystemClock.elapsedRealtime();
  if(expireRuntimeReconnect(now))return;
  if(connectPendingSince==0)connectPendingSince=now;
  if(now-connectPendingSince>=CONNECT_QUEUE_TIMEOUT_MS){
   resetPermissionConnectionRequest();
   uiHandler.removeCallbacks(tryConnection);
   showOperation("上一次连接资源尚未释放。请等几秒后再点「连接」。");
   return;
  }
  scheduleConnection(delay);
 }
 private void deferServiceCleanup(){
  recordBootWait("service_cleanup");
  deferConnection(runtimeReconnectPreviousId!=null?RUNTIME_RECONNECT_POLL_MS:500L);
 }
 // Background worker used by the optional system diagnostics.
 interface Work{String run()throws Exception;}
 void task(final String phase,final Work job){if(busy)return;busy=true;showOperation("正在处理…");worker.execute(new Runnable(){public void run(){String msg;try{msg=job.run();}catch(Throwable e){msg="操作未完成："+e.getClass().getSimpleName();}final String result=msg;runOnUiThread(new Runnable(){public void run(){busy=false;if(!closed)showOperation(result);}});}});}
 void showOperation(String s){if(!closed&&operationStatus!=null){operationStatus.setText(s);operationStatus.setVisibility(View.VISIBLE);}}
 void show(String s){if(closed||s==null)return;if(connecting){status.setText("\u6b63\u5728\u8fde\u63a5 iPhone");if(connectionProgress!=null)connectionProgress.setStage(uiStage,s);}else status.setText(s);}
 void setConnectionStage(String stage,String message){if(stage!=null&&!stage.isEmpty())uiStage=stage;if("CONNECT_START".equals(uiStage))probeAddress=null;else if("PHONE_START".equals(uiStage)){probeAddress=null;if(message!=null&&message.startsWith("PHONE_PROBE http://")){String candidate=message.substring("PHONE_PROBE ".length()).trim();if(candidate.length()<=96&&candidate.endsWith(":7001")&&candidate.indexOf(' ')<0)probeAddress=candidate;}}else if("VIDEO_READY".equals(uiStage)||"FAILED".equals(uiStage)||"DISCONNECTED".equals(uiStage)||"STOPPED".equals(uiStage))probeAddress=null;if(connectionProgress!=null)connectionProgress.setStage(uiStage,message);if(status!=null)status.setText("VIDEO_READY".equals(uiStage)?"CarPlay \u5df2\u8fde\u63a5":"\u6b63\u5728\u8fde\u63a5 iPhone");if(connecting&&operationStatus!=null&&probeAddress!=null){operationStatus.setText("\u7f51\u7edc\u8bca\u65ad\uff08\u53ef\u9009\uff09\uff1a"+probeAddress+"\uff08\u6295\u5c4f\u5361\u4f4f\u65f6\u518d\u7528 iPhone Safari \u6253\u5f00\uff09");operationStatus.setVisibility(View.VISIBLE);}}
 void refreshVideoBlank(){if(videoBlank!=null)videoBlank.setVisibility(connecting&&videoReady?View.GONE:View.VISIBLE);}
 void setProjectionMode(boolean enabled){
  projectionMode=enabled;
  refreshVideoBlank();
  controls.setVisibility(enabled?View.GONE:View.VISIBLE);
  boolean projectionPending=connecting||runtimeReconnectPreviousId!=null;
  if(!enabled&&projectionPending)showOperation(videoReady?"点击「连接」返回 CarPlay 画面。":"点击「连接」查看连接进度。");
  if(connectionProgress!=null){
   connectionProgress.setVisibility(enabled&&projectionPending&&!videoReady?View.VISIBLE:View.GONE);
  }
  if(enabled)getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
  else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
  preview.post(new Runnable(){public void run(){if(!closed)try{record(MainActivity.this,"projection_layout",new JSONObject().put("projectionMode",projectionMode).put("panelVisible",controls.getVisibility()==View.VISIBLE).put("surfaceWidth",preview.getWidth()).put("surfaceHeight",preview.getHeight()).put("windowWidth",preview.getRootView().getWidth()).put("windowHeight",preview.getRootView().getHeight()));}catch(Exception ignored){}}});
 }
 static final class ProjectionSurface extends SurfaceView {
  ProjectionSurface(Context c){super(c);}
  protected void onMeasure(int widthSpec,int heightSpec){int w=View.MeasureSpec.getSize(widthSpec),h=View.MeasureSpec.getSize(heightSpec);int width=Math.min(w,h*ProjectionConfig.WIDTH/ProjectionConfig.HEIGHT);setMeasuredDimension(width,width*ProjectionConfig.HEIGHT/ProjectionConfig.WIDTH);}
 }
 static void record(Context c,String name,JSONObject details)throws Exception{DiagnosticLog.record(c,name,details);}

 private void requestManualConnection(){
  if(connecting||runtimeReconnectPreviousId!=null){setProjectionMode(true);return;}
  if(permissionPending||(connectPending&&!autoConnectArmed)){
   showOperation("\u6b63\u5728\u51c6\u5907\u8fde\u63a5\uff0c\u8bf7\u7a0d\u5019");
   return;
  }
  if(busy||maintenance){showOperation("\u5f53\u524d\u8bca\u65ad\u5c1a\u672a\u5b8c\u6210\uff0c\u8bf7\u7a0d\u540e\u518d\u8fde\u63a5");return;}
  if(WiredService.currentConnectionId().length()>0){
   restoreConnection();
   showOperation("\u5df2\u6062\u590d\u5f53\u524d CarPlay \u8fde\u63a5");
   return;
  }
  if(canReselectPhone){
   new AlertDialog.Builder(this)
       .setTitle("连接 iPhone")
       .setMessage("上次未能连上这台 iPhone。可以继续用原记录重试，或搜索并选择其他手机。")
       .setPositiveButton("重试",new DialogInterface.OnClickListener(){
        public void onClick(DialogInterface dialog,int which){
         checkWifiSetup(WifiPurpose.CONNECT);
        }
       })
       .setNeutralButton("搜索其他手机",new DialogInterface.OnClickListener(){
        public void onClick(DialogInterface dialog,int which){
         checkWifiSetup(WifiPurpose.RESELECT_PHONE);
        }
       })
       .setNegativeButton("取消",null)
       .show();
   return;
  }
  checkWifiSetup(WifiPurpose.CONNECT);
 }

 private void enqueueManualConnection(WifiPurpose purpose) {
  reselectPhoneForConnection=purpose==WifiPurpose.RESELECT_PHONE;
  canReviewWifi = false;
  clearBootAutoConnection();
  connectPending=true;
  connectPendingSince=SystemClock.elapsedRealtime();
  scheduleConnection(0);
 }
 final Runnable tryConnection=new Runnable(){public void run(){
  if(closed||!connectPending)return;
  if(!resumed){recordBootWait("activity_background");return;}
  if(!uiBuilt)return; // buildMainContent schedules the pending request once the views are ready.
  if(permissionPending){recordBootWait("usb_permission_dialog");return;}
  long now=SystemClock.elapsedRealtime();
  if(expireRuntimeReconnect(now))return;
  String serviceId=WiredService.currentConnectionId();
  if(!serviceId.isEmpty()&&serviceId.equals(runtimeReconnectPreviousId)){
   deferServiceCleanup();
   return;
  }
  if(connecting||!serviceId.isEmpty()){
   clearRuntimeReconnect();
   connectPending=false;connectPendingSince=0;restoreConnection();return;
  }
  if(expireBootAutoConnection(now))return;
  if(autoConnectArmed&&now<autoConnectNotBefore){scheduleConnection(0);return;}
  if(busy||maintenance){recordBootWait("ui_busy");deferConnection(500);return;}
  if(WiredService.isBusy()){deferServiceCleanup();return;}
  connectPending=false;connectPendingSince=0;
  requestConnection();
 }};
 private boolean expireBootAutoConnection(long now) {
  if(!autoConnectArmed||now<autoConnectDeadlineAt)return false;
  recordAutoConnectDecision("queue_expired",autoConnectWaitReason.isEmpty()?"no_ready_trigger":autoConnectWaitReason,null,0);
  clearBootAutoConnection();
  resetPermissionConnectionRequest();
  showOperation("自动连接等待已结束。可点击「连接」继续。");
  return true;
 }
 void requestConnection(){
  if(connecting||permissionPending)return;
  if(expireRuntimeReconnect(SystemClock.elapsedRealtime()))return;
  if(expireBootAutoConnection(SystemClock.elapsedRealtime()))return;
  if(!resumed||!uiBuilt){connectPending=true;return;}
  if(WiredService.isBusy()){connectPending=true;deferServiceCleanup();return;}
  if(requestStartedAt==0){
   requestStartedAt=autoConnectArmed?autoConnectDeadlineAt-BOOT_AUTO_CONNECT_WINDOW_MS:SystemClock.elapsedRealtime();
  }
  if(authorizedUsbBtDevice!=null||runtimeReconnectPreviousId!=null){
   UsbManager manager=(UsbManager)getSystemService(Context.USB_SERVICE);
   UsbDevice current=currentUsbDevice(manager,authorizedUsbBtDevice);
   authorizedUsbBtDevice=null;
   if(current!=null&&manager.hasPermission(current)){connectPort(current);return;}
   boolean recoveryCancelled=runtimeReconnectPreviousId!=null;
   if(recoveryCancelled)recordRuntimeReconnect("retry_cancelled","adapter_or_permission_unavailable");
   resetPermissionConnectionRequest();
   if(recoveryCancelled){setProjectionMode(false);status.setText("连接未完成");}
   showOperation("USB \u84dd\u7259\u9002\u914d\u5668\u5df2\u65ad\u5f00\u6216\u6388\u6743\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u8fde\u63a5\u3002");
   return;
  }
  connectUsingUsbBluetooth();
 }
 protected void onNewIntent(Intent i){
  super.onNewIntent(i);
  setIntent(i);
  boolean bootAuthorized=consumeBootLaunch(i);
  boolean autoLaunch=bootAuthorized||consumeUsbAttachLaunch(i);
  if(autoLaunch&&!connecting&&!connectPending)armBootAutoConnection(SystemClock.elapsedRealtime());
  recordBootLaunch(bootAuthorized,autoLaunch);
  if(autoLaunch&&uiBuilt&&resumed)scheduleConnection(0);
 }
 protected void onResume(){super.onResume();resumed=true;if(uiBuilt){restoreConnection();refreshVideoBlank();lifecycle("resume");}else recordStartup("activity_resumed_before_content");if(connectPending)scheduleConnection(0);continueHotspotRecovery();}
 @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)continueHotspotRecovery();}
 protected void onPause(){resumed=false;if(hotspotSettingsReturnPurpose!=null)leftForHotspotSettings=true;if(hotspotPrompt!=null)hotspotPrompt.dismiss();if(connectPending&&!connecting)recordBootWait("activity_background");cancelUsbBtPeerChoice();if(uiBuilt){lifecycle("pause");if(preview!=null)WiredService.detachSurface(this);}uiHandler.removeCallbacks(tryConnection);super.onPause();}
 protected void onStop(){cancelUsbBtPeerChoice();if(uiBuilt)lifecycle("background");super.onStop();}
 public static boolean wheelProbeActive(){return false;}
 public boolean dispatchKeyEvent(KeyEvent event){
  int code=event.getKeyCode();boolean handled=false;String decision="pass_through";
  if(WiredService.consumeWheelDuplicate(code)){handled=true;decision="duplicate_suppressed";}
  else if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN){
   if(event.getAction()==KeyEvent.ACTION_DOWN){WiredService.adjustCarPlayVolume(code==KeyEvent.KEYCODE_VOLUME_UP?android.media.AudioManager.ADJUST_RAISE:android.media.AudioManager.ADJUST_LOWER);decision="volume_adjusted";}else decision="volume_release";
   handled=WiredService.isVideoReady();if(!handled)decision="volume_passthrough";
  }else if(event.getAction()==KeyEvent.ACTION_UP&&event.getRepeatCount()==0){
   handled=WiredService.sendWheelKey(code,"activity_dispatch");decision=handled?"wheel_accepted":"wheel_unmapped_or_unavailable";
  }else if(event.getAction()==KeyEvent.ACTION_DOWN)decision="key_down_observed";
  boolean delivered=handled||super.dispatchKeyEvent(event);
  if(event.getAction()==KeyEvent.ACTION_DOWN||event.getAction()==KeyEvent.ACTION_UP&&event.getRepeatCount()<=1){
   boolean known=code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||code==KeyEvent.KEYCODE_MEDIA_NEXT||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS||code==KeyEvent.KEYCODE_HEADSETHOOK||code==KeyEvent.KEYCODE_CALL||code==KeyEvent.KEYCODE_ENDCALL||CarPlayMediaButton.INSTANCE.opensSiri(code)||CarPlayMediaButton.INSTANCE.opensSiriWhileCarPlay(code);
   if(WiredService.isVideoReady()||known)try{record(this,"WHEEL_KEY",new JSONObject().put("keyPath","activity_dispatch").put("keyCode",code).put("keyAction",event.getAction()).put("keyRepeatCount",event.getRepeatCount()).put("keySource",event.getSource()).put("keyFlags",event.getFlags()).put("keyDecision",decision).put("keyHandled",delivered).put("keyIsSiriCandidate",CarPlayMediaButton.INSTANCE.opensSiri(code)||CarPlayMediaButton.INSTANCE.opensSiriWhileCarPlay(code)));}catch(Exception ignored){}
  }
  return delivered;
 }
 void lifecycle(String event){try{record(this,"PROJECTION_UI",new JSONObject().put("event",event).put("connection",connectionId).put("surfaceValid",preview!=null&&preview.getHolder().getSurface().isValid()));}catch(Exception ignored){}}
 void restoreConnection(){String id=WiredService.currentConnectionId();if(id.length()>0){if(id.equals(runtimeReconnectPreviousId))return;clearRuntimeReconnect();boolean restored=!connecting||!id.equals(connectionId);connectionId=id;connecting=true;connectPending=false;connectPendingSince=0;videoReady=WiredService.isVideoReady();probeAddress=WiredService.currentProbeUrl();uiStage=videoReady?"VIDEO_READY":WiredService.currentProgressStage();String progressMessage=WiredService.currentProgressMessage();if(probeAddress!=null&&"PHONE_START".equals(uiStage))progressMessage="PHONE_PROBE "+probeAddress;if(preview.getHolder().getSurface().isValid())WiredService.attachSurface(this,preview.getHolder().getSurface());if(restored){setConnectionStage(uiStage,progressMessage);setProjectionMode(true);}}else if(connecting){connecting=false;connectPending=false;connectPendingSince=0;videoReady=false;probeAddress=null;setProjectionMode(false);}}

 private void recordUsbBluetoothScan(int candidateCount,int deviceCount){
  long now=SystemClock.elapsedRealtime();
  usbBtScanAttempt++;
  boolean changed=candidateCount!=lastUsbBtCandidateCount||deviceCount!=lastUsbBtDeviceCount;
  boolean found=candidateCount>0;
  boolean due=lastUsbBtScanLoggedAt==0||now-lastUsbBtScanLoggedAt>=5000L;
  if(!changed&&!due)return;
  long autoElapsed=autoConnectArmed?Math.max(0,now-(autoConnectDeadlineAt-BOOT_AUTO_CONNECT_WINDOW_MS)):-1;
  String stage=found?"candidate_found":autoConnectArmed&&now<autoConnectDeadlineAt?"waiting_for_candidate":"candidate_missing";
  try{record(this,"USB_BT_CONNECT_SCAN",new JSONObject().put("stage",stage)
      .put("candidateCount",candidateCount).put("usbDeviceCount",deviceCount)
      .put("scanAttempt",usbBtScanAttempt).put("autoConnectRequested",autoConnectArmed)
      .put("autoConnectElapsedMs",autoElapsed)
      .put("autoConnectRemainingMs",autoConnectArmed?Math.max(0,autoConnectDeadlineAt-now):-1)
      .put("elapsedMs",now));}catch(Exception ignored){}
  lastUsbBtScanLoggedAt=now;
  lastUsbBtCandidateCount=candidateCount;
  lastUsbBtDeviceCount=deviceCount;
 }
 void connectUsingUsbBluetooth(){
  final UsbManager manager=(UsbManager)getSystemService(Context.USB_SERVICE);
  Map<String,UsbDevice> attached=manager==null?Collections.<String,UsbDevice>emptyMap():manager.getDeviceList();
  final List<UsbDevice> devices=new ArrayList<UsbDevice>();
  for(UsbDevice device:attached.values()){
   if(UsbBluetoothProbe.isCandidate(device))devices.add(device);
  }
  recordUsbBluetoothScan(devices.size(),attached.size());
  if(devices.isEmpty()){
   long now=SystemClock.elapsedRealtime();
   if(autoConnectArmed&&now<autoConnectDeadlineAt){
    connectPending=true;
    recordBootWait("usb_adapter_absent");
    if(connectPendingSince==0)connectPendingSince=now;
    showOperation("\u7b49\u5f85 USB \u9002\u914d\u5668\u5c31\u7eea\u2026");
    scheduleConnection(Math.min(1200L,autoConnectDeadlineAt-now));
    return;
   }
   clearBootAutoConnection();
   requestStartedAt=0;
   showOperation("\u672a\u627e\u5230 CM390 / CM591 USB \u84dd\u7259\u68d2\u3002\u8bf7\u63d2\u5165 USB \u9002\u914d\u5668\u540e\u91cd\u8bd5\u3002");
   return;
  }
  if(devices.size()==1){requestUsbBluetoothPermission(manager,devices.get(0));return;}
  if(autoConnectArmed){
   UsbDevice cm591=null;
   for(UsbDevice candidate:devices){
    if(!UsbBluetoothProbe.isKnownAts2851(candidate.getVendorId(),candidate.getProductId()))continue;
    if(cm591!=null){cm591=null;break;}
    cm591=candidate;
   }
   if(cm591!=null){requestUsbBluetoothPermission(manager,cm591);return;}
   recordAutoConnectDecision("queue_cancelled","usb_selection_required",null,0);
   clearBootAutoConnection();
   resetPermissionConnectionRequest();
   showOperation("检测到多个蓝牙适配器。点击「连接」选择适配器。");
   return;
  }
  if(waitForProjectionDialog())return;
  final String[] labels=new String[devices.size()];
  for(int i=0;i<devices.size();i++)labels[i]=UsbBluetoothProbe.label(devices.get(i),manager);
  new AlertDialog.Builder(this).setTitle("\u9009\u62e9\u7528\u4e8e CarPlay \u7684 USB \u84dd\u7259\u68d2")
      .setItems(labels,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
       requestUsbBluetoothPermission(manager,devices.get(which));
      }})
      .setNegativeButton("\u53d6\u6d88",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
       requestStartedAt=0;vpnWaitMs=0;
      }})
      .setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){
       requestStartedAt=0;vpnWaitMs=0;
      }}).show();
 }
 void connectPort(UsbDevice usbBtDevice){
  if(connecting){showOperation("正在连接，请先停止后重试");return;}
  boolean runtimeReconnect=runtimeReconnectPreviousId!=null;
  if(!resumed||!uiBuilt){
   authorizedUsbBtDevice=usbBtDevice;
   connectPending=true;
   return; // The resumed activity verifies the same adapter and grant again.
  }
  if(requestStartedAt==0)requestStartedAt=SystemClock.elapsedRealtime();
  clearRuntimeReconnect();
  boolean reselectPhone=reselectPhoneForConnection;
  currentWifiPurpose=reselectPhone?WifiPurpose.RESELECT_PHONE:WifiPurpose.CONNECT;
  reselectPhoneForConnection=false;
  bootUsbBtGrantWaiting=false;
  connectionId=UUID.randomUUID().toString();
  connecting=true;videoReady=false;probeAddress=null;uiStage="CONNECT_START";
  if(connectionProgress!=null)connectionProgress.resetForAttempt();
  setConnectionStage(uiStage,"");
  setProjectionMode(!runtimeReconnect||projectionMode);
  onProjectionSurfaceAvailable();
  boolean p2p=LegacyCarHotspot.usesWifiDirect(this);
  showOperation(p2p ? "正在准备 Wi-Fi Direct 和蓝牙连接…" : "正在准备车载热点和蓝牙连接…");
  try{record(this,"NETWORK_BACKEND_SELECTED",new JSONObject().put("backend",p2p?"android_wifi_p2p_go":"android_wifi_softap").put("transportMode",p2p?"usb_bt_p2p_data":"usb_bt_softap_data").put("vpnRequired",false).put("ipv4",true).put("tcp",true).put("udp",true).put("uptimeMs",SystemClock.elapsedRealtime()));}catch(Exception ignored){}
  try{
   startService(new Intent(this,WiredService.class).putExtra("connection_id",connectionId)
       .putExtra("request_started_at",requestStartedAt).putExtra("usb_bt_device",usbBtDevice)
        .putExtra(EXTRA_RESELECT_PHONE,reselectPhone).putExtra("boot_auto_connect",autoConnectArmed)
        .putExtra("runtime_reconnect",runtimeReconnect));
   if(runtimeReconnect)recordRuntimeReconnect("service_launch","same_authorized_adapter");
   recordAutoConnectDecision("service_launch","usb_ready",null,0);
   resetBootWaitEvidence();
   canReselectPhone=false;
  }catch(RuntimeException e){
   if(runtimeReconnect)recordRuntimeReconnect("retry_failed","service_start_exception");
   recordAutoConnectDecision("service_launch_failed","service_start_exception",e.getClass().getSimpleName(),0);
   clearBootAutoConnection();resetPermissionConnectionRequest();connecting=false;setProjectionMode(false);
   show("无法启动连接，请重试");
  }
 }
  private static String connectionFailureSummary(String message){String detail=message==null?"":message.replace('\n',' ').replace('\r',' ').trim();if(detail.isEmpty())return "\u8fde\u63a5\u5931\u8d25\uff0c\u8bf7\u63d0\u4ea4\u65e5\u5fd7";int nested=detail.indexOf(" \u2192 ");if(nested>0)detail=detail.substring(0,nested);if(detail.length()>132)detail=detail.substring(0,132)+"...";return "\u8fde\u63a5\u5931\u8d25\uff1a"+detail+"\u3002\u8bf7\u63d0\u4ea4\u65e5\u5fd7";}
 final BroadcastReceiver portStatus=new BroadcastReceiver(){
  public void onReceive(Context context,Intent intent){
   Object recoveryDevice=intent.getParcelableExtra("runtime_retry_device");
   handleConnectionStatus(intent.getStringExtra("connection_id"),intent.getStringExtra("stage"),
       intent.getStringExtra("message"),intent.getBooleanExtra("boot_retryable",false),
       recoveryDevice instanceof UsbDevice?(UsbDevice)recoveryDevice:null);
  }
 };

 private void handleConnectionStatus(String id,String stage,String message,boolean bootRetryable,UsbDevice recoveryDevice){
  if(id==null||id.isEmpty()||!id.equals(connectionId)||stage==null)return;
  setConnectionStage(stage,message);
  if("VIDEO_READY".equals(stage)){
   clearHotspotRecovery();
   recordAutoConnectDecision("auto_projection_ready","first_frame",null,0);
   clearBootAutoConnection();
   videoReady=true;
   // The user may have opened the controls while waiting for the first picture.
   setProjectionMode(projectionMode);
   return;
  }
  if(!"DISCONNECTED".equals(stage)&&!"STOPPED".equals(stage)&&!"FAILED".equals(stage))return;

  long now=SystemClock.elapsedRealtime();
  boolean retry="FAILED".equals(stage)&&bootRetryable&&autoConnectArmed&&!autoConnectRetryUsed
      &&now+BOOT_AUTO_RETRY_DELAY_MS<autoConnectDeadlineAt;
  if("FAILED".equals(stage)){
   if(retry)recordAutoConnectDecision("retry_queued","transient_failure",null,BOOT_AUTO_RETRY_DELAY_MS);
   else{
    String reason=!bootRetryable?"failure_not_retryable":autoConnectRetryUsed?"retry_already_used":"window_exhausted";
    recordAutoConnectDecision("retry_skipped",reason,null,0);
   }
  }else recordAutoConnectDecision("auto_projection_ended","DISCONNECTED".equals(stage)?"disconnected":"stopped",null,0);

  // A later STOPPED from cleanup must not clear a new Connect request.
  connectionId="";
  connectPending=false;
  connectPendingSince=0;
  requestStartedAt=0;
  vpnWaitMs=0;
  connecting=false;
  videoReady=false;

  if(retry){
   setProjectionMode(false);
   autoConnectRetryUsed=true;
   autoConnectNotBefore=now+BOOT_AUTO_RETRY_DELAY_MS;
   connectPending=true;
   connectPendingSince=now;
   status.setText("正在恢复自动连接");
   showOperation("稍等片刻，DiPlay 会自动重试一次。");
   scheduleConnection(0);
   return;
  }
  clearBootAutoConnection();
  if("FAILED".equals(stage)&&enqueueRuntimeReconnect(recoveryDevice,id,now))return;
  setProjectionMode(false);

  if("FAILED".equals(stage)){
   status.setText("连接未完成");
   boolean p2p=LegacyCarHotspot.usesWifiDirect(this);
   canReselectPhone=message!=null&&message.startsWith("IOException: BT_RESELECT_PHONE ");
   canReviewWifi=!p2p&&message!=null&&message.startsWith(WIFI_REVIEW_FAILURE);
   if(p2p&&message!=null&&message.startsWith(P2P_HOTSPOT_ACTIVE_FAILURE)){
    String detail=message.substring(P2P_HOTSPOT_ACTIVE_FAILURE.length()).split(" → ",2)[0];
    queueHotspotPrompt(detail,null);
   }else if(p2p&&message!=null&&message.startsWith(P2P_CONNECTION_FAILURE)){
    String detail=message.substring(P2P_CONNECTION_FAILURE.length()).split(" → ",2)[0];
    showOperation(detail);
   }else if(!p2p&&message!=null&&message.startsWith(WIFI_OPEN_FAILURE)){
    String detail=message.substring(WIFI_OPEN_FAILURE.length()).split(" → ",2)[0];
    queueHotspotPrompt(detail,currentWifiPurpose);
   }else if(canReviewWifi){
    String detail=message.substring(WIFI_REVIEW_FAILURE.length()).split(" → ",2)[0];
    showOperation(detail+" 点击「连接」可检查后重试。");
   }else{
    showOperation(canReselectPhone?"未能连接已保存的 iPhone。点击「连接」可重试或重新选择。":connectionFailureSummary(message));
   }
  }else if("DISCONNECTED".equals(stage)){
   status.setText("连接已断开");
   showOperation("确认 USB 蓝牙适配器仍插着，iPhone 蓝牙和 Wi-Fi 在线后重连。");
  }else{
   status.setText("已停止");
   showOperation("点击「连接」可重新连接。");
  }
 }

 public void onBackPressed(){if(projectionMode){setProjectionMode(false);return;}if(connecting||runtimeReconnectPreviousId!=null){setProjectionMode(true);return;}super.onBackPressed();}
 protected void onActivityResult(int r,int code,Intent data){super.onActivityResult(r,code,data);}
 static JSONArray snapshot(Context c)throws Exception{return DiagnosticLog.snapshot(c);}
 static void acknowledge(Context c,JSONArray rows)throws Exception{DiagnosticLog.acknowledge(c,rows);}
 String asset(String name)throws Exception{BufferedReader r=new BufferedReader(new InputStreamReader(getAssets().open(name),"UTF-8"));try{return r.readLine().trim();}finally{r.close();}}
  public void onDestroy(){if(connectPending&&!connecting)recordAutoConnectDecision("queue_cancelled","activity_destroyed",null,0);closed=true;clearRuntimeReconnect();clearHotspotRecovery();OnlineUpdate.cancel(this);cancelUsbBtPeerChoice();startupBuilding=false;connectPending=false;connectPendingSince=0;uiHandler.removeCallbacksAndMessages(null);if(preview!=null)WiredService.detachSurface(this);if(active==this)active=null;if(portReceiverRegistered)try{unregisterReceiver(portStatus);}catch(Exception ignored){}clearUsbBluetoothPermission();authorizedUsbBtDevice=null;worker.shutdown();super.onDestroy();}
}
