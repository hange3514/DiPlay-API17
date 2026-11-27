package local.headunit.diplay;
import android.app.*;import android.os.*;import android.content.*;import android.hardware.usb.*;import android.view.*;import android.widget.*;
import android.graphics.drawable.*;
import com.shilapi.xcertplay.airplay.CarPlayMediaButton;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;

/** API17 wired CarPlay connection panel. */
public class MainActivity extends Activity {
 static volatile MainActivity active;final Handler uiHandler=new Handler();boolean resumed,connectPending,permissionPending,maintenance,startupBuilding=true,uiBuilt,portReceiverRegistered,splashFrameLogged;String connectionId="";long requestStartedAt,vpnPromptAt,vpnWaitMs;
 static boolean interactionBusy(){MainActivity a=active;return a!=null&&!a.closed&&(a.startupBuilding||a.busy||a.maintenance||a.videoCalibration.isActive()||a.permissionPending||a.connectPending||a.connecting||UsbAttachActivity.permissionFlowActive()||WiredService.awaitingPermission());}
 final ExecutorService worker=Executors.newSingleThreadExecutor();TextView status;SurfaceView preview;volatile boolean closed;volatile NetworkDeadline updateRequest;boolean busy;ScrollView controls;boolean connecting;boolean projectionMode,videoReady;ConnectionProgressView connectionProgress;View videoBlank;TextView resumeButton,operationStatus;LinearLayout actionRow;int actionCount;StartupSplashView startupSplash;long activityStartedAt;boolean firstFrameLogged;String uiStage="CONNECT_START";
 private ProjectionTouchInput projectionTouch;
 private static final String FRAME_RATE_ACTION="画面帧率";
 private Button frameRateButton;
 private AlertDialog frameRateDialog;
 private final VideoCalibrationPanel videoCalibration=new VideoCalibrationPanel(this);
 public void onCreate(Bundle b){super.onCreate(b);active=this;startupBuilding=true;activityStartedAt=SystemClock.elapsedRealtime();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);startupSplash=new StartupSplashView(this);setContentView(startupSplash);recordStartup("splash_attached");final StartupSplashView firstSplash=startupSplash;firstSplash.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(!splashFrameLogged){splashFrameLogged=true;recordStartup("splash_first_pre_draw");android.view.ViewTreeObserver tree=firstSplash.getViewTreeObserver();if(tree.isAlive())tree.removeOnPreDrawListener(this);}return true;}});if((getIntent().getBooleanExtra("auto_connect",false)||UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction()))&&AutoUsbService.canConnect(this))connectPending=true;uiHandler.postDelayed(new Runnable(){public void run(){if(!closed)buildMainContent();}},32);}
 private void buildMainContent(){  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(22),dp(22),dp(18));root.setBackgroundDrawable(panelBackground());
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setOrientation(0);
  ImageView logo=new ImageView(this);logo.setImageBitmap(startupSplash.logoBitmap());logo.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams logoLp=new LinearLayout.LayoutParams(dp(50),dp(50));logoLp.rightMargin=dp(13);header.addView(logo,logoLp);
  LinearLayout brand=new LinearLayout(this);brand.setOrientation(1);TextView title=new TextView(this);title.setText("DiPlay");title.setTextSize(25);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));title.setTextColor(0xfff2f7fc);brand.addView(title);TextView subtitle=new TextView(this);subtitle.setText("\u6709\u7ebf CarPlay");subtitle.setTextSize(12);subtitle.setTextColor(0xff91a7bb);brand.addView(subtitle);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
  TextView versionBadge=new TextView(this);String version="";try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}versionBadge.setText(version.replace("-ncm-ready-retry-beta","").replace("-ui-polish-beta",""));versionBadge.setTextSize(10);versionBadge.setTextColor(0xff9ecfe0);versionBadge.setPadding(dp(9),dp(6),dp(9),dp(6));GradientDrawable badge=new GradientDrawable();badge.setColor(0xff132638);badge.setCornerRadius(dp(20));badge.setStroke(dp(1),0xff284457);versionBadge.setBackgroundDrawable(badge);header.addView(versionBadge);root.addView(header);
  TextView section=new TextView(this);section.setText("\u8fde\u63a5\u72b6\u6001");section.setTextSize(12);section.setTextColor(0xff7e9ab0);section.setPadding(0,dp(25),0,dp(8));root.addView(section);
  LinearLayout statusCard=new LinearLayout(this);statusCard.setOrientation(1);statusCard.setPadding(dp(16),dp(15),dp(16),dp(15));GradientDrawable cardBg=new GradientDrawable();cardBg.setColor(0xff111e2d);cardBg.setCornerRadius(dp(16));cardBg.setStroke(dp(1),0xff263c50);statusCard.setBackgroundDrawable(cardBg);
  LinearLayout statusLine=new LinearLayout(this);statusLine.setGravity(Gravity.CENTER_VERTICAL);View statusDot=new View(this);GradientDrawable dotBg=new GradientDrawable();dotBg.setColor(0xff38c7df);dotBg.setShape(GradientDrawable.OVAL);statusDot.setBackgroundDrawable(dotBg);LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(dp(9),dp(9));dotLp.rightMargin=dp(10);statusLine.addView(statusDot,dotLp);
  status=new TextView(this);status.setTextSize(16);status.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));status.setTextColor(0xffe6f0f8);status.setText("\u7b49\u5f85 iPhone");statusLine.addView(status);statusCard.addView(statusLine);
  operationStatus=new TextView(this);operationStatus.setTextSize(12);operationStatus.setTextColor(0xff91a8ba);operationStatus.setPadding(dp(19),dp(5),0,0);operationStatus.setText("\u63d2\u5165\u6570\u636e\u7ebf\u540e\u81ea\u52a8\u8fde\u63a5 CarPlay");operationStatus.setVisibility(View.VISIBLE);statusCard.addView(operationStatus);root.addView(statusCard);
  button(root,"\u8fde\u63a5",new Runnable(){public void run(){AutoUsbService.resume(MainActivity.this);connectPending=true;scheduleConnection(0);}});
  button(root,"\u505c\u6b62",new Runnable(){public void run(){stopConnection();}});
  TextView toolsLabel=new TextView(this);toolsLabel.setText("\u7248\u672c\u4e0e\u652f\u6301");toolsLabel.setTextSize(12);toolsLabel.setTextColor(0xff7e9ab0);toolsLabel.setPadding(0,dp(24),0,dp(2));root.addView(toolsLabel);
  button(root,"\u5728\u7ebf\u66f4\u65b0",new Runnable(){public void run(){CompatibilityTools.checkForUpdate(MainActivity.this);}});
  button(root,"\u95ee\u9898\u53cd\u9988",new Runnable(){public void run(){CompatibilityTools.open(MainActivity.this);}});
  frameRateButton=button(root,FRAME_RATE_ACTION,new Runnable(){public void run(){showFrameRateDialog();}});
  updateFrameRateButton();
  resumeButton=new TextView(this);resumeButton.setText("\u8fd4\u56de CarPlay \u753b\u9762");resumeButton.setTextSize(14);resumeButton.setGravity(Gravity.CENTER);resumeButton.setTextColor(0xffdffaff);resumeButton.setPadding(0,dp(14),0,dp(14));GradientDrawable resumeBg=new GradientDrawable();resumeBg.setColor(0xff103449);resumeBg.setCornerRadius(dp(13));resumeBg.setStroke(dp(1),0xff28728a);resumeButton.setBackgroundDrawable(resumeBg);resumeButton.setVisibility(View.GONE);resumeButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){setProjectionMode(true);}});LinearLayout.LayoutParams resumeLp=new LinearLayout.LayoutParams(-1,-2);resumeLp.topMargin=dp(10);root.addView(resumeButton,resumeLp);
  Switch autoReconnect=new Switch(this);autoReconnect.setText("\u8fde\u63a5\u5931\u8d25\u540e\u81ea\u52a8\u91cd\u8bd5");autoReconnect.setTextColor(0xffd2deea);autoReconnect.setTextSize(13);autoReconnect.setChecked(AutoUsbService.autoReconnectEnabled(this));autoReconnect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton buttonView,boolean checked){AutoUsbService.setAutoReconnectEnabled(MainActivity.this,checked);}});LinearLayout autoCard=new LinearLayout(this);autoCard.setGravity(Gravity.CENTER_VERTICAL);autoCard.setPadding(dp(12),dp(4),dp(8),dp(4));GradientDrawable autoBg=new GradientDrawable();autoBg.setColor(0xff101a28);autoBg.setCornerRadius(dp(13));autoBg.setStroke(dp(1),0xff233649);autoCard.setBackgroundDrawable(autoBg);autoCard.addView(autoReconnect,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams autoLp=new LinearLayout.LayoutParams(-1,-2);autoLp.topMargin=dp(14);root.addView(autoCard,autoLp);
  TextView footer=new TextView(this);footer.setText("\u6295\u5c4f\u65f6\u6309\u8fd4\u56de\u952e\u6253\u5f00\u63a7\u5236\u9762\u677f");footer.setTextSize(11);footer.setTextColor(0xff71869b);footer.setPadding(0,dp(18),0,dp(4));root.addView(footer);
  preview=new ProjectionSurface(this);preview.getHolder().setFixedSize(ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT);controls=new ScrollView(this);controls.setFillViewport(true);controls.setClipToPadding(false);controls.addView(root);FrameLayout outer=new FrameLayout(this);outer.setBackgroundColor(0xff050a12);outer.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));videoBlank=new View(this);videoBlank.setBackgroundColor(0xff050a12);videoBlank.setClickable(false);outer.addView(videoBlank,new FrameLayout.LayoutParams(-1,-1));connectionProgress=new ConnectionProgressView(this);connectionProgress.setVisibility(View.GONE);outer.addView(connectionProgress,new FrameLayout.LayoutParams(-1,-1));
  controls.setBackgroundDrawable(panelBackground());int panelWidth=Math.min(dp(420),Math.max(dp(320),getResources().getDisplayMetrics().widthPixels*44/100));outer.addView(controls,new FrameLayout.LayoutParams(panelWidth,-1,Gravity.LEFT));ViewGroup splashParent=(ViewGroup)startupSplash.getParent();if(splashParent!=null)splashParent.removeView(startupSplash);outer.addView(startupSplash,new FrameLayout.LayoutParams(-1,-1));setContentView(outer);videoCalibration.attach(outer);recordStartup("content_attached");outer.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(!firstFrameLogged){firstFrameLogged=true;recordStartup("content_first_pre_draw");android.view.ViewTreeObserver tree=outer.getViewTreeObserver();if(tree.isAlive())tree.removeOnPreDrawListener(this);if(startupSplash!=null&&startupSplash.getVisibility()==View.VISIBLE){startupSplash.animate().cancel();startupSplash.animate().alpha(0f).setDuration(140).withEndAction(new Runnable(){public void run(){if(startupSplash!=null){startupSplash.setVisibility(View.GONE);startupSplash.setClickable(false);}}});}}return true;}});registerReceiver(portStatus,new IntentFilter("local.headunit.diplay.STATUS"));portReceiverRegistered=true;
  projectionTouch=new ProjectionTouchInput(this);
  preview.setOnTouchListener(projectionTouch);
  preview.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){WiredService.attachSurface(MainActivity.this,h.getSurface());refreshProjectionTouch();if(connectPending)scheduleConnection(0);}public void surfaceChanged(SurfaceHolder h,int format,int width,int height){}public void surfaceDestroyed(SurfaceHolder h){projectionTouch.setEnabled(false);WiredService.detachSurface(MainActivity.this);lifecycle("surface_destroyed");}});
  uiBuilt=true;startupBuilding=false;if(resumed){restoreConnection();refreshVideoBlank();lifecycle("resume");}if(!getIntent().getBooleanExtra("auto_connect",false))AutoUsbService.resume(this);AutoUsbService.start(this);if((getIntent().getBooleanExtra("auto_connect",false)||UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction()))&&AutoUsbService.canConnect(this))connectPending=true;if(connectPending)scheduleConnection(0);

 }
 private void recordStartup(final String stage){final long elapsed=Math.max(0,SystemClock.elapsedRealtime()-activityStartedAt);try{worker.execute(new Runnable(){public void run(){try{record(MainActivity.this,"APP_STARTUP",new JSONObject().put("stage",stage).put("elapsedMs",elapsed));}catch(Exception ignored){}}});}catch(RuntimeException ignored){}}

 int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 void updateFrameRateButton() {
  frameRateButton.setText(FRAME_RATE_ACTION + " " + PlaybackOptions.read(this) + " FPS");
 }

 private void showFrameRateDialog() {
  final int[] rates = PlaybackOptions.rates();
  final int current = PlaybackOptions.read(this);
  final CharSequence[] labels = new CharSequence[rates.length];
  for (int index = 0; index < rates.length; index++) {
   int fps = rates[index];
   String description;
   switch (fps) {
    case 15: description = "低负载"; break;
    case 20: description = "较低负载"; break;
    case 30: description = "均衡"; break;
    case 60: description = "最高帧率"; break;
    default: description = "高帧率";
   }
   labels[index] = fps + " FPS · " + description;
  }
  final boolean[] compare = {false};
  frameRateDialog = new AlertDialog.Builder(this)
      .setTitle("画面帧率（下次连接生效）")
      .setSingleChoiceItems(labels, Arrays.binarySearch(rates, current), null)
      .setNegativeButton("取消", null)
      .setNeutralButton("自动对比", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface dialog, int which) { compare[0] = true; }
      })
      .setPositiveButton("保存", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface dialog, int which) {
        int index = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
        if (closed || index < 0 || index >= rates.length) return;
        int fps = rates[index];
        PlaybackOptions.save(MainActivity.this, fps);
        updateFrameRateButton();
        showOperation("已保存 " + fps + " FPS。" + (connecting ? "停止后重新连接即可生效。" : "下次连接时生效。"));
       }
      }).create();
  maintenance = true;
  frameRateDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
   public void onDismiss(DialogInterface dialog) {
    frameRateDialog = null;
    maintenance = false;
    if (!closed && connectPending) scheduleConnection(0);
    if (compare[0]) showVideoCalibrationIntro();
   }
  });
  frameRateDialog.show();
 }

 private void showVideoCalibrationIntro() {
  if (closed || !resumed || busy || maintenance || videoCalibration.isActive()) return;
  if (!WiredService.isVideoReady() || !videoReady) {
   showOperation("先连上 CarPlay 并出画面，再打开「画面帧率」进行自动对比。");
   return;
  }
  new AlertDialog.Builder(this)
      .setTitle("自动对比画面流畅度")
      .setMessage("请停车后选择一组。\n基础组：" + VideoCalibrationPanel.Group.BASIC.describeRates()
          + "\n高帧率组：" + VideoCalibrationPanel.Group.HIGH.describeRates()
          + "，40、50、60 FPS 请按车机实际流畅度选择。\n\n程序会自动切换档位。每轮请打开同一个列表并按提示持续滑动约 15 秒。采样时按返回键可取消。")
      .setNegativeButton("取消", null)
      .setNeutralButton("开始高帧对比", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface dialog, int which) { videoCalibration.start(VideoCalibrationPanel.Group.HIGH); }
      })
      .setPositiveButton("开始基础对比", new DialogInterface.OnClickListener() {
       public void onClick(DialogInterface dialog, int which) { videoCalibration.start(VideoCalibrationPanel.Group.BASIC); }
      }).show();
 }

 Drawable panelBackground(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xf5121f30,0xf60b1421,0xf6080e18});d.setStroke(dp(1),0x222f5368);return d;}
 Drawable buttonBackground(String label,boolean pressed){boolean primary=label.equals("\u8fde\u63a5"),stop=label.equals("\u505c\u6b62");int color=primary?(pressed?0xff126880:0xff0e7899):stop?(pressed?0xff3b4755:0xff202d3b):(pressed?0xff263e52:0xff19293a);int stroke=primary?0xff35b8d3:stop?0xff465b6d:0xff344c60;GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));d.setStroke(dp(1),stroke);return d;}
 Button button(LinearLayout r,final String label,final Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));b.setTextColor(label.equals("\u8fde\u63a5")?0xfff4fcff:0xffe2edf6);StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},buttonBackground(label,true));states.addState(new int[]{},buttonBackground(label,false));b.setBackgroundDrawable(states);b.setPadding(dp(6),0,dp(6),0);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(label.equals("\u505c\u6b62")){action.run();return;}if(videoCalibration.isActive())return;if(connecting&&!(videoReady&&FRAME_RATE_ACTION.equals(label))){showOperation("\u6b63\u5728\u8fde\u63a5\uff0c\u8bf7\u5148\u505c\u6b62\u540e\u518d\u64cd\u4f5c");return;}if(busy||maintenance){showOperation("\u6b63\u5728\u5904\u7406\u4e0a\u4e00\u9879\u64cd\u4f5c\uff0c\u8bf7\u7a0d\u5019");return;}action.run();}});if(actionCount%2==0){actionRow=new LinearLayout(this);actionRow.setOrientation(0);LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,-2);row.topMargin=dp(10);r.addView(actionRow,row);}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);if(actionCount%2==0){if(!FRAME_RATE_ACTION.equals(label))lp.rightMargin=dp(5);}else lp.leftMargin=dp(5);actionRow.addView(b,lp);actionCount++;return b;}
 void stopConnection(){if(videoCalibration.isActive()){videoCalibration.cancel();return;}stopConnectionCore(true);}
 void stopConnectionForCalibration(){stopConnectionCore(false);}
 void stopConnectionCore(){stopConnectionCore(true);}
 private void stopConnectionCore(boolean userRequested){requestStartedAt=0;vpnWaitMs=0;if(userRequested)AutoUsbService.pause(this);connectPending=false;permissionPending=false;uiHandler.removeCallbacks(tryConnection);connectionId="stopped-"+UUID.randomUUID();WiredService.stop(this);connecting=false;videoReady=false;uiStage="STOPPED";setProjectionMode(false);show("\u5df2\u505c\u6b62\u8fde\u63a5\u3002\u91cd\u65b0\u63d2\u62d4\u6216\u70b9\u51fb\u201c\u8fde\u63a5\u201d\u53ef\u6062\u590d\u3002");}
 void scheduleConnection(long delay){uiHandler.removeCallbacks(tryConnection);if(!closed)uiHandler.postDelayed(tryConnection,delay);}
 // Legacy probe helpers: no UI entry points remain, kept only so dormant probe classes still compile.
 interface Work{String run()throws Exception;}
 void task(final Work job){task("capability",job);}
 void task(final String phase,final Work job){if(busy)return;busy=true;showOperation("正在处理…");worker.execute(new Runnable(){public void run(){String msg;try{msg=job.run();}catch(Throwable e){msg="操作未完成："+e.getClass().getSimpleName();}final String result=msg;runOnUiThread(new Runnable(){public void run(){busy=false;if(!closed)showOperation(result);}});}});}
 void showOperation(String s){if(!closed&&operationStatus!=null){operationStatus.setText(s);operationStatus.setVisibility(View.VISIBLE);}}
 void show(String s){if(closed||s==null)return;if(connecting){status.setText("\u6b63\u5728\u8fde\u63a5 iPhone");if(connectionProgress!=null)connectionProgress.setStage(uiStage,s);}else status.setText(s);}
 void setConnectionStage(String stage,String message){if(stage!=null&&!stage.isEmpty())uiStage=stage;if(connectionProgress!=null)connectionProgress.setStage(uiStage,message);if(status!=null)status.setText("VIDEO_READY".equals(uiStage)?"CarPlay \u5df2\u8fde\u63a5":"\u6b63\u5728\u8fde\u63a5 iPhone");}
 void refreshVideoBlank(){if(videoBlank!=null)videoBlank.setVisibility(connecting&&videoReady?View.GONE:View.VISIBLE);refreshProjectionTouch();}
 private void refreshProjectionTouch(){
  if(projectionTouch!=null)projectionTouch.setEnabled(!closed&&resumed&&projectionMode&&connecting&&videoReady&&preview.getHolder().getSurface().isValid());
 }
 void setProjectionMode(boolean enabled){projectionMode=enabled;refreshVideoBlank();controls.setVisibility(enabled?View.GONE:View.VISIBLE);if(resumeButton!=null)resumeButton.setVisibility(!enabled&&connecting?View.VISIBLE:View.GONE);if(!enabled&&connecting&&operationStatus!=null)operationStatus.setText(videoReady?"CarPlay \u6b63\u5728\u663e\u793a\uff0c\u70b9\u51fb\u6309\u94ae\u8fd4\u56de\u5168\u5c4f":"\u8fde\u63a5\u6b63\u5728\u8fdb\u884c\uff0c\u53ef\u70b9\u51fb\u6309\u94ae\u67e5\u770b\u8fdb\u5ea6");if(connectionProgress!=null)connectionProgress.setVisibility(enabled&&connecting&&!videoReady?View.VISIBLE:View.GONE);if(enabled)getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);preview.post(new Runnable(){public void run(){if(!closed)try{record(MainActivity.this,"projection_layout",new JSONObject().put("projectionMode",projectionMode).put("panelVisible",controls.getVisibility()==View.VISIBLE).put("surfaceWidth",preview.getWidth()).put("surfaceHeight",preview.getHeight()).put("windowWidth",preview.getRootView().getWidth()).put("windowHeight",preview.getRootView().getHeight()));}catch(Exception ignored){}}});}
 static final class ProjectionSurface extends SurfaceView {
  ProjectionSurface(Context c){super(c);}
  protected void onMeasure(int widthSpec,int heightSpec){int w=View.MeasureSpec.getSize(widthSpec),h=View.MeasureSpec.getSize(heightSpec);int width=Math.min(w,h*ProjectionConfig.WIDTH/ProjectionConfig.HEIGHT);setMeasuredDimension(width,width*ProjectionConfig.HEIGHT/ProjectionConfig.WIDTH);}
 }
 static void record(Context c,String name,JSONObject details)throws Exception{
  if("WHEEL_KEY".equals(name)&&details.has("keyCode")){
   int code=details.optInt("keyCode",-1);
   if(!isWheelDiagnosticKey(code)&&!(isLegacyVendorKeyCode(code)&&
    "vendor_nontext".equals(details.optString("keyCandidate"))))return;
  }
  DiagnosticLog.record(c,name,details);
 }

 final Runnable tryConnection=new Runnable(){public void run(){if(closed||!resumed||!connectPending||permissionPending)return;if(videoCalibration.isActive()){connectPending=false;return;}if(busy||maintenance||UsbAttachActivity.permissionFlowActive()){scheduleConnection(500);return;}if(WiredService.isBusy()){scheduleConnection(500);return;}if(preview==null||!preview.getHolder().getSurface().isValid()){scheduleConnection(100);return;}connectPending=false;requestConnection();}};
 void requestConnection(){if(connecting||permissionPending)return;if(!resumed||!preview.getHolder().getSurface().isValid()){connectPending=true;scheduleConnection(100);return;}if(WiredService.isBusy()){connectPending=true;scheduleConnection(500);return;}if(requestStartedAt==0)requestStartedAt=SystemClock.elapsedRealtime();try{record(this,"NETWORK_BACKEND_SELECTED",new JSONObject().put("backend","lwip_userland_ncm").put("vpnRequired",false).put("ipv4",false).put("ipv6",true).put("tcp",true).put("udp",true).put("uptimeMs",SystemClock.elapsedRealtime()));}catch(Exception ignored){}connectPort();}
 protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);try{record(this,"ACTIVITY_INTENT",new JSONObject().put("action",i.getAction()).put("autoConnect",i.getBooleanExtra("auto_connect",false)).put("connecting",connecting).put("connectPending",connectPending).put("usbPermissionPending",WiredService.awaitingPermission()).put("elapsedMs",SystemClock.elapsedRealtime()));}catch(Exception ignored){}if(videoCalibration.isActive())return;if(!i.getBooleanExtra("auto_connect",false))AutoUsbService.resume(this);if((i.getBooleanExtra("auto_connect",false)||UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction()))&&AutoUsbService.canConnect(this)&&!connecting){connectPending=true;scheduleConnection(0);}}
 protected void onResume(){super.onResume();resumed=true;if(uiBuilt){restoreConnection();refreshVideoBlank();lifecycle("resume");}else recordStartup("activity_resumed_before_content");if(connectPending)scheduleConnection(0);videoCalibration.onResume();}
 protected void onPause(){resumed=false;refreshProjectionTouch();videoCalibration.onPause();if(uiBuilt){lifecycle("pause");if(preview!=null)WiredService.detachSurface(this);}uiHandler.removeCallbacks(tryConnection);super.onPause();}
 protected void onStop(){if(uiBuilt)lifecycle("background");super.onStop();}
 public static boolean wheelProbeActive(){return false;}
 static boolean isWheelDiagnosticKey(int code){
  return code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||
   code==KeyEvent.KEYCODE_VOLUME_MUTE||code==KeyEvent.KEYCODE_MUTE||
   code==KeyEvent.KEYCODE_CALL||code==KeyEvent.KEYCODE_ENDCALL||
   code==KeyEvent.KEYCODE_MEDIA_STOP||code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||
   code==KeyEvent.KEYCODE_MEDIA_REWIND||code==KeyEvent.KEYCODE_MEDIA_RECORD||
   CarPlayMediaButton.INSTANCE.forKeyCode(code,true)!=null||
   CarPlayMediaButton.INSTANCE.opensSiri(code)||CarPlayMediaButton.INSTANCE.opensSiriWhileCarPlay(code);
 }
 static boolean isLegacyVendorKeyCode(int code){return Build.VERSION.SDK_INT==17&&code>=300&&code<=511;}
 private boolean isProjectedVendorKey(KeyEvent event){
  return isLegacyVendorKeyCode(event.getKeyCode())&&projectionMode&&hasWindowFocus()&&
   WiredService.isVideoReady()&&event.getScanCode()>0&&
   (event.getFlags()&(KeyEvent.FLAG_SOFT_KEYBOARD|KeyEvent.FLAG_VIRTUAL_HARD_KEY))==0&&
   !event.isPrintingKey()&&event.getUnicodeChar()==0;
 }
 @Override public void onWindowFocusChanged(boolean focused){
  super.onWindowFocusChanged(focused);
  if(closed)return;
  try{
   final JSONObject details=new JSONObject().put("keyPath","activity_dispatch")
    .put("keyDecision",focused?"observer_focused":"observer_unfocused")
    .put("keyWindowFocused",focused).put("projectionReady",WiredService.isVideoReady())
    .put("connection",WiredService.currentConnectionId()).put("uptimeMs",SystemClock.elapsedRealtime());
   worker.execute(new Runnable(){public void run(){
    try{record(MainActivity.this,"WHEEL_KEY",details);}catch(Exception ignored){}
   }});
  }catch(Exception ignored){}
 }
 public boolean dispatchKeyEvent(KeyEvent event){
  int code=event.getKeyCode();boolean handled=false;String decision="pass_through";
  if(WiredService.consumeWheelDuplicate(code)){handled=true;decision="duplicate_suppressed";}
  else if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN){
   if(event.getAction()==KeyEvent.ACTION_DOWN){WiredService.adjustCarPlayVolume(code==KeyEvent.KEYCODE_VOLUME_UP?android.media.AudioManager.ADJUST_RAISE:android.media.AudioManager.ADJUST_LOWER);decision="volume_adjusted";}else decision="volume_release";
   handled=WiredService.isVideoReady();if(!handled)decision="volume_passthrough";
  }else if(event.getAction()==KeyEvent.ACTION_UP&&event.getRepeatCount()==0){
   handled=WiredService.sendWheelKey(code,"activity_dispatch");decision=handled?"wheel_forwarded":"wheel_unmapped_or_no_session";
  }else if(event.getAction()==KeyEvent.ACTION_DOWN)decision="key_down_observed";
  boolean delivered=handled||super.dispatchKeyEvent(event);
  if(event.getRepeatCount()<=1&&
   (event.getAction()==KeyEvent.ACTION_DOWN||event.getAction()==KeyEvent.ACTION_UP)){
   boolean known=isWheelDiagnosticKey(code);
   try{
    if(known||isProjectedVendorKey(event))record(this,"WHEEL_KEY",new JSONObject()
     .put("keyPath","activity_dispatch").put("keyCandidate",known?"known_control":"vendor_nontext")
     .put("keyCode",code).put("keyScanCode",event.getScanCode()).put("keyAction",event.getAction())
     .put("keyRepeatCount",event.getRepeatCount()).put("keySource",event.getSource()).put("keyFlags",event.getFlags())
     .put("keyDecision",decision).put("keyHandled",delivered).put("keyConsumedByDiPlay",handled)
     .put("keyWindowFocused",hasWindowFocus()).put("projectionReady",WiredService.isVideoReady())
     .put("connection",WiredService.currentConnectionId())
     .put("keyIsSiriCandidate",CarPlayMediaButton.INSTANCE.opensSiri(code)||
      CarPlayMediaButton.INSTANCE.opensSiriWhileCarPlay(code)));
   }catch(Exception ignored){}
  }
  return delivered;
 }
 void lifecycle(String event){try{record(this,"PROJECTION_UI",new JSONObject().put("event",event).put("connection",connectionId).put("surfaceValid",preview!=null&&preview.getHolder().getSurface().isValid()));}catch(Exception ignored){}}
 void restoreConnection(){String id=WiredService.currentConnectionId();if(id.length()>0){boolean restored=!connecting||!id.equals(connectionId);connectionId=id;connecting=true;connectPending=false;videoReady=WiredService.isVideoReady();uiStage=videoReady?"VIDEO_READY":"CONNECT_START";if(preview.getHolder().getSurface().isValid())WiredService.attachSurface(this,preview.getHolder().getSurface());if(restored){setConnectionStage(uiStage,"");setProjectionMode(true);}}else if(connecting){connecting=false;videoReady=false;setProjectionMode(false);}}

 boolean isProjectionSurfaceReady(){return preview!=null&&preview.getHolder().getSurface().isValid();}
 void connectPort(){connectPort(0);}
 void connectPort(int benchmarkFps){if(connecting){showOperation("\u6b63\u5728\u8fde\u63a5\uff0c\u8bf7\u5148\u505c\u6b62\u540e\u91cd\u8bd5");return;}if(requestStartedAt==0)requestStartedAt=SystemClock.elapsedRealtime();if(preview==null||!preview.getHolder().getSurface().isValid()){show("\u753b\u9762\u5c1a\u672a\u5c31\u7eea");return;}connectionId=UUID.randomUUID().toString();connecting=true;videoReady=false;uiStage="CONNECT_START";if(connectionProgress!=null)connectionProgress.resetForAttempt();setConnectionStage(uiStage,"");setProjectionMode(true);WiredService.attachSurface(this,preview.getHolder().getSurface());try{Intent launch=new Intent(this,WiredService.class).putExtra("connection_id",connectionId).putExtra("request_started_at",requestStartedAt).putExtra("vpn_wait_ms",vpnWaitMs);if(benchmarkFps>0)launch.putExtra(WiredService.EXTRA_BENCHMARK_FPS,benchmarkFps);startService(launch);}catch(RuntimeException e){requestStartedAt=0;vpnWaitMs=0;connecting=false;setProjectionMode(false);show("\u65e0\u6cd5\u542f\u52a8\u8fde\u63a5\uff0c\u8bf7\u91cd\u8bd5");}}
 final BroadcastReceiver portStatus=new BroadcastReceiver(){public void onReceive(Context c,Intent i){String id=i.getStringExtra("connection_id");if(id==null||id.isEmpty()||!id.equals(connectionId))return;String stage=i.getStringExtra("stage"),message=i.getStringExtra("message");if("VIDEO_READY".equals(stage)&&(!id.equals(WiredService.currentConnectionId())||!WiredService.isVideoReady()))return;setConnectionStage(stage,message);if("VIDEO_READY".equals(stage)){videoReady=true;refreshVideoBlank();setProjectionMode(true);}if("DISCONNECTED".equals(stage)||"STOPPED".equals(stage)||"FAILED".equals(stage)){requestStartedAt=0;vpnWaitMs=0;connecting=false;videoReady=false;setProjectionMode(false);if(videoCalibration.connectionEnded(id))return;if("FAILED".equals(stage)){status.setText("\u8fde\u63a5\u672a\u5b8c\u6210");showOperation("\u8bf7\u68c0\u67e5 USB \u6388\u6743\u548c\u7ebf\u7f06\uff0c\u7136\u540e\u91cd\u8bd5\u6216\u63d0\u4ea4\u65e5\u5fd7");}else if("DISCONNECTED".equals(stage)){status.setText("\u8fde\u63a5\u5df2\u65ad\u5f00");showOperation("\u91cd\u65b0\u63d2\u5165 iPhone \u6216\u70b9\u51fb\u8fde\u63a5");}else{status.setText("\u5df2\u505c\u6b62");}}}};
 public void onBackPressed(){if(videoCalibration.isActive()){videoCalibration.cancel();return;}if(projectionMode){setProjectionMode(false);return;}if(connecting){setProjectionMode(true);return;}super.onBackPressed();}
 protected void onActivityResult(int r,int code,Intent data){super.onActivityResult(r,code,data);if(r==20||r==21){maintenance=false;AutoUsbService.endMaintenance(this);return;}}
 static JSONArray snapshot(Context c)throws Exception{return DiagnosticLog.snapshot(c);}
 static void acknowledge(Context c,JSONArray rows)throws Exception{DiagnosticLog.acknowledge(c,rows);}
 String asset(String name)throws Exception{BufferedReader r=new BufferedReader(new InputStreamReader(getAssets().open(name),"UTF-8"));try{return r.readLine().trim();}finally{r.close();}}
 String uploadNow()throws Exception{String batchToken=UsbBatchReport.append(this);JSONArray rows=snapshot(MainActivity.this);if(rows.length()==0)return "暂时没有日志。连接或运行后即可提交。";
  JSONArray wireRows=ReportIo.wireRows(rows,asset("token.txt"));JSONObject body=new JSONObject().put("app_version","DiPlay-"+getPackageManager().getPackageInfo(getPackageName(),0).versionName).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL).put("comment","有线CarPlay连接日志").put("events",wireRows);byte[] data=body.toString().getBytes("UTF-8");if(data.length>250000)throw new IOException("Report too large");
  File saved=null;try{File folder=new File(Environment.getExternalStorageDirectory(),"DiPlayCompat");saved=ReportIo.backup(folder,data);}catch(IOException error){record(MainActivity.this,"LOG_EXPORT_FAILED",new JSONObject().put("type",error.getClass().getSimpleName()));}
  HttpURLConnection c=(HttpURLConnection)new URL(asset("server.txt")+"/diagnostics").openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setUseCaches(false);c.setRequestProperty("Connection","close");c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Authorization","Bearer "+asset("token.txt"));NetworkDeadline deadline=new NetworkDeadline(c,45000);try{OutputStream out=c.getOutputStream();out.write(data);out.close();if(c.getResponseCode()!=200)throw new IOException("Upload HTTP "+c.getResponseCode());String id=ReportIo.receipt(c.getInputStream(),wireRows.length());deadline.check();acknowledge(MainActivity.this,rows);UsbBatchReport.acknowledge(this,batchToken);boolean cleared=saved==null||saved.delete();return "提交成功："+id+"\n"+(cleared?"已清理本次已确认日志；并发新日志会保留。":"已确认上传；外部副本清理失败，可手动删除："+saved.getAbsolutePath());}finally{deadline.close();}
 }
 public void onDestroy(){closed=true;videoCalibration.destroy();if(frameRateDialog!=null)frameRateDialog.dismiss();startupBuilding=false;connectPending=false;uiHandler.removeCallbacksAndMessages(null);if(projectionTouch!=null)projectionTouch.setEnabled(false);if(preview!=null)WiredService.detachSurface(this);if(active==this)active=null;if(portReceiverRegistered)try{unregisterReceiver(portStatus);}catch(Exception ignored){}worker.shutdown();super.onDestroy();}
}
