package local.headunit.diplay;

import android.app.*;
import android.content.*;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.*;
import android.widget.*;

/** Installed only on the owned emulator; not included in the release APK. */
public class ProjectionUiInstrumentation extends Instrumentation {
 MainActivity activity;int soakRounds,streamSeconds;boolean vpnAuto;String routeEvidence="";
 public void onCreate(Bundle args){super.onCreate(args);if(args!=null){vpnAuto="true".equals(args.getString("vpnAuto"));soakRounds=Integer.parseInt(args.getString("soakRounds","0"));streamSeconds=Integer.parseInt(args.getString("streamSeconds","0"));}start();}
 void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public void onStart(){Bundle result=new Bundle();try{
  activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  waitForIdleSync();
  if(streamSeconds>0){streamSoak(result);finish(Activity.RESULT_OK,result);return;}
  if(vpnAuto){
   for(int n=0;n<150&&!VpnAutoConfirmService.connected;n++)Thread.sleep(100);
   check(VpnAutoConfirmService.connected,"Accessibility service not bound");
   check(android.net.VpnService.prepare(activity)!=null,"Fresh VPN approval required for real dialog test; reboot emulator");
   runOnMainSync(new Runnable(){public void run(){activity.requestConnection();}});
   for(int n=0;n<80&&android.net.VpnService.prepare(activity)!=null;n++)Thread.sleep(100);
   check(android.net.VpnService.prepare(activity)==null,"System VPN consent was not granted through accessibility");
   runOnMainSync(new Runnable(){public void run(){activity.stopConnection();}});
   result.putString("stream","PASS: real API17 system VPN checkbox and confirmation automatically completed");finish(Activity.RESULT_OK,result);return;
  }
  if(soakRounds>0){mediaSoak(result);finish(Activity.RESULT_OK,result);return;}
  batchReportPersistence();
  nativeStartupBinding();
  nativeCrypto();
  blankSurfaceLifecycle();
  frameRateSelection();
  runOnMainSync(new Runnable(){public void run(){
   check(activity.controls.getVisibility()==View.VISIBLE,"Initial panel absent");
   View testSurface=activity.preview;
   testSurface.measure(View.MeasureSpec.makeMeasureSpec(1024,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
   check(testSurface.getMeasuredWidth()==1024&&testSurface.getMeasuredHeight()==600,"Native 1024x600 viewport mismatch");
   testSurface.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
   check(testSurface.getMeasuredWidth()==800&&testSurface.getMeasuredHeight()==468,"Smaller viewport aspect mismatch");
   testSurface.requestLayout();

   check(countButtons(activity.controls)==9,"Expected nine controls including diagnostics and version manager");
   for(String label:new String[]{"回退到0.34有线稳定版","在线更新","提交日志","连接","停止","环境检测并提交","通话音频批量诊断"})check(findButton(activity.controls,label)!=null,"Missing "+label);
   // Check connectPort synchronously before Android starts the real USB service.
   activity.connectPort();check(activity.projectionMode,"Connect did not enter projection");
   check(activity.controls.getVisibility()==View.GONE,"Connect left panel visible");
   WiredService.stop(activity);
  }});
  waitForIdleSync();
  for(int i=0;i<20&&WiredService.isBusy();i++){Thread.sleep(100);waitForIdleSync();}
  Thread.sleep(200);waitForIdleSync();
  runOnMainSync(new Runnable(){public void run(){activity.connectionId="synthetic-test";activity.connecting=true;activity.setProjectionMode(true);
   activity.portStatus.onReceive(activity,new Intent().putExtra("stage","VIDEO_READY").putExtra("message","test frame"));
   check(activity.connectionHint.getVisibility()==View.GONE,"Hint covers video after ready");
   activity.portStatus.onReceive(activity,new Intent().putExtra("stage","FAILED").putExtra("connection_id","old-attempt"));check(activity.connecting,"Old failure overwrote current connection");
  }});
  waitForIdleSync();
  final int firstWidth=activity.preview.getWidth();
  check(Math.abs(activity.preview.getWidth()*600-activity.preview.getHeight()*1024)<1024,"Actual viewport aspect mismatch");
  java.lang.reflect.Field requestedWidth=SurfaceView.class.getDeclaredField("mRequestedWidth"),requestedHeight=SurfaceView.class.getDeclaredField("mRequestedHeight");
  requestedWidth.setAccessible(true);requestedHeight.setAccessible(true);
  check(requestedWidth.getInt(activity.preview)==1024&&requestedHeight.getInt(activity.preview)==600,"Requested buffer not native 1024x600");
  check(firstWidth>activity.preview.getRootView().getWidth()-100,"Video still reserved panel width");
  for(int i=0;i<3;i++){
   runOnMainSync(new Runnable(){public void run(){activity.onBackPressed();check(activity.controls.getVisibility()==View.VISIBLE,"Back failed to reveal panel");}});
   waitForIdleSync();
   runOnMainSync(new Runnable(){public void run(){activity.onBackPressed();check(activity.controls.getVisibility()==View.GONE,"Back failed to restore projection");}});
   waitForIdleSync();check(activity.preview.getWidth()==firstWidth,"Projection geometry drifted");
  }
  runOnMainSync(new Runnable(){public void run(){activity.onBackPressed();activity.busy=true;activity.connectPending=true;activity.permissionPending=true;findButton(activity.controls,"停止").performClick();check(!activity.connectPending&&!activity.connecting&&!activity.permissionPending,"Stop did not cancel pending work while busy");activity.busy=false;activity.onActivityResult(11,Activity.RESULT_OK,null);check(!activity.connectPending,"Late permission result restarted stopped connection");}});
  waitForIdleSync();
  runOnMainSync(new Runnable(){public void run(){activity.portStatus.onReceive(activity,new Intent().putExtra("stage","FAILED").putExtra("message","synthetic failure"));check(!activity.connecting&&!activity.projectionMode,"Failure did not restore controls");check(activity.controls.getVisibility()==View.VISIBLE,"Failure panel hidden");}});
  siriPlaybackHold();wheelProbe();wheelControl();verifiedMfiReuse();updateIdentity();updateFiles();networkDeadline();earlyNetworkCancellation();usbBindingEvidence();environmentCheck();thirdPartyAudioSnapshot();reportIo();connectedOperations();mediaLifecycle();surfaceRecreation();aacPlayback();assistantPlayback();focusLifecycle();roleFocusLifecycle();callPcmDiagnosticEvidence();boundServiceStop();logRetention();bydMultimediaControlProtocol();boundedPacketIo();updateCancellationOnDestroy();
   result.putString("stream","PASS: four surface decodes across recreation, Siri PCM, CarPlay telephony media-volume output with established media focus and preserved mode, mic hold, shared media focus, update/storage safety, 20 native cancellation cycles, BYD protocol, VPN cleanup, retained diagnostics, wheel controls, stop/permission races and full viewport; API17 route policy="+routeEvidence+"\n");
  finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
 void nativeCrypto()throws Exception {
  check(NativeCrypto.available,"Native library failed to load on API17 ARM");java.util.Random rng=new java.util.Random(4317);byte[] key=new byte[32],nonce=new byte[12];rng.nextBytes(key);rng.nextBytes(nonce);
  for(int size:new int[]{0,1,15,16,17,63,64,65,1024,32768,262144,1048576}){byte[] plain=new byte[size],aad=new byte[size%2==0?128:0];rng.nextBytes(plain);rng.nextBytes(aad);
   byte[] sealed=com.shilapi.xcertplay.airplay.AirPlayCrypto.INSTANCE.chachaSeal(key,nonce,plain,aad), originalSealed=sealed.clone();
   check(java.util.Arrays.equals(plain,NativeCrypto.open(key,nonce,sealed,aad)),"Native AEAD differs from BC size="+size);check(java.util.Arrays.equals(originalSealed,sealed),"Native decrypt mutated caller ciphertext");
   sealed[sealed.length-1]^=1;boolean denied=false;try{NativeCrypto.open(key,nonce,sealed,aad);}catch(java.security.GeneralSecurityException expected){denied=true;}check(denied,"Tampered AEAD accepted");sealed[sealed.length-1]^=1;
   if(aad.length>0){aad[0]^=1;denied=false;try{NativeCrypto.open(key,nonce,sealed,aad);}catch(java.security.GeneralSecurityException expected){denied=true;}check(denied,"Wrong AAD accepted");aad[0]^=1;}
   nonce[0]^=1;denied=false;try{NativeCrypto.open(key,nonce,sealed,aad);}catch(java.security.GeneralSecurityException expected){denied=true;}check(denied,"Wrong nonce accepted");nonce[0]^=1;
  }
  byte[] plain=new byte[32768],aad=new byte[128];rng.nextBytes(plain);byte[] sealed=com.shilapi.xcertplay.airplay.AirPlayCrypto.INSTANCE.chachaSeal(key,nonce,plain,aad);
  long start=System.nanoTime();for(int n=0;n<8;n++)NativeCrypto.open(key,nonce,sealed,aad);long nativeNs=System.nanoTime()-start;
  start=System.nanoTime();for(int n=0;n<8;n++)com.shilapi.xcertplay.airplay.AirPlayCrypto.INSTANCE.chachaOpen(key,nonce,sealed,aad);long javaNs=System.nanoTime()-start;
  java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getFilesDir(),"crypto-benchmark.json"));try{out.write(("{\"scope\":\"API17_emulator_not_car\",\"bytesPerIteration\":32768,\"iterations\":8,\"nativeNs\":"+nativeNs+",\"javaNs\":"+javaNs+"}").getBytes("UTF-8"));}finally{out.close();}
 }
 void blankSurfaceLifecycle()throws Exception {
  runOnMainSync(new Runnable(){public void run(){
   check(activity.videoBlank.getVisibility()==View.VISIBLE,"Initial display not blank");
   activity.connecting=true;activity.videoReady=false;activity.setProjectionMode(true);check(activity.videoBlank.getVisibility()==View.VISIBLE,"Reconnect exposes old frame");
   activity.portStatus.onReceive(activity,new Intent().putExtra("stage","VIDEO_READY").putExtra("message","test"));check(activity.videoBlank.getVisibility()==View.GONE,"New video remains blank");
   activity.setProjectionMode(false);check(activity.videoBlank.getVisibility()==View.GONE,"Opening controls hid live video");
   activity.portStatus.onReceive(activity,new Intent().putExtra("stage","DISCONNECTED").putExtra("message","test"));check(activity.videoBlank.getVisibility()==View.VISIBLE&&!activity.connecting,"Disconnect retained last frame");
   activity.connecting=true;activity.videoReady=true;activity.setProjectionMode(true);activity.stopConnection();check(activity.videoBlank.getVisibility()==View.VISIBLE,"Stop retained last frame");
  }});
 }
 void frameRateSelection()throws Exception {
  android.content.SharedPreferences p=activity.getSharedPreferences("playback",0);boolean had=p.contains("fps");int old=p.getInt("fps",30);
  try{p.edit().putInt("fps",60).commit();check(PlaybackOptions.read(activity)==15,"Legacy 60fps preference must not override fixed 15fps");p.edit().remove("fps").commit();check(PlaybackOptions.read(activity)==15,"Fresh profile must use 15fps");}
  finally{if(had)p.edit().putInt("fps",old).commit();else p.edit().remove("fps").commit();}
  for(int route=0;route<3;route++)check(activity.getPackageManager().resolveActivity(AccessibilityEntry.intent(route),0)!=null,"Accessibility route unresolved on stock API17");
  check((AccessibilityEntry.intent(3).getFlags()&android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)!=0,"Fresh settings route must recreate task");
  check("com.android.settings.AccessibilitySettings".equals(AccessibilityEntry.intent(1).getStringExtra(":android:show_fragment")),"Wrong direct fragment");

 }
 void verifiedMfiReuse()throws Exception {
  final int[] calls=new int[2];
  com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient one=VerifiedMfiCache.INSTANCE.get(activity,new kotlin.jvm.functions.Function1<Integer,kotlin.Unit>(){public kotlin.Unit invoke(Integer n){calls[0]++;return kotlin.Unit.INSTANCE;}});
  com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient two=VerifiedMfiCache.INSTANCE.get(activity,new kotlin.jvm.functions.Function1<Integer,kotlin.Unit>(){public kotlin.Unit invoke(Integer n){calls[1]++;return kotlin.Unit.INSTANCE;}});
  check(java.util.Arrays.equals(one.readCertificate(16384),two.readCertificate(16384)),"Prepared identity changed across connections");
  check(one.signChallenge(new byte[32]).length==64&&two.signChallenge(new byte[32]).length==64,"Prepared identity cannot sign on API17");
  check(calls[0]==1&&calls[1]==1,"Cached identity retained a previous connection callback");
 }
 void usbBindingEvidence()throws Exception {
  java.io.File root=new java.io.File(activity.getCacheDir(),"usb-binding-fixture"),node=new java.io.File(root,"1-1:1.3"),driver=new java.io.File(root,"cdc_ncm"),link=new java.io.File(node,"driver"),number=new java.io.File(node,"bInterfaceNumber");check(node.mkdirs()&&driver.mkdir(),"USB fixture creation");writeBytes(number,"03".getBytes("US-ASCII"));
  try{Process ln=Runtime.getRuntime().exec(new String[]{"/system/bin/ln","-s",driver.getAbsolutePath(),link.getAbsolutePath()});check(ln.waitFor()==0,"USB fixture symlink");
   org.json.JSONObject result=EnvironmentCheck.usbBindings(new java.io.File[]{root,node});org.json.JSONArray entries=result.getJSONArray("interfaces");check(entries.length()==1,"Device node confused with interface");check("cdc_ncm".equals(entries.getJSONObject(0).getString("driverName")),"Driver symlink unresolved");check("03".equals(entries.getJSONObject(0).getString("interfaceNumber")),"Interface number missing");
   java.io.File[] many=new java.io.File[65];java.util.Arrays.fill(many,node);org.json.JSONObject limited=EnvironmentCheck.usbBindings(many);check(limited.getBoolean("truncated")&&limited.getJSONArray("interfaces").length()==64,"USB evidence truncation not marked");
   check(link.delete(),"Cannot remove test binding");check("unbound_or_unreadable".equals(EnvironmentCheck.usbBindings(new java.io.File[]{node}).getJSONArray("interfaces").getJSONObject(0).getString("driverStatus")),"Missing driver falsely attributed");check("unavailable_or_denied".equals(EnvironmentCheck.usbBindings(null).getString("status")),"Unavailable sysfs misreported");
  }finally{link.delete();number.delete();node.delete();driver.delete();root.delete();}
 }
 void updateIdentity()throws Exception {
  android.content.pm.PackageManager pm=activity.getPackageManager();android.content.pm.PackageInfo current=pm.getPackageInfo(activity.getPackageName(),android.content.pm.PackageManager.GET_SIGNATURES);
  android.content.pm.PackageInfo actual=pm.getPackageArchiveInfo(activity.getApplicationInfo().sourceDir,android.content.pm.PackageManager.GET_SIGNATURES);
  OnlineUpdate.verifyPackage(actual,current,current.versionCode);
  rejectUpdate(null,current,current.versionCode,"Unparseable APK accepted");
  android.content.pm.PackageInfo other=pm.getPackageInfo(getContext().getPackageName(),android.content.pm.PackageManager.GET_SIGNATURES);
  rejectUpdate(other,current,other.versionCode,"Other package accepted");
  rejectUpdate(actual,current,current.versionCode+1,"Metadata version mismatch accepted");
  actual.versionCode=current.versionCode-1;rejectUpdate(actual,current,actual.versionCode,"Downgrade accepted");actual.versionCode=current.versionCode;
  actual.signatures=new android.content.pm.Signature[]{new android.content.pm.Signature(new byte[]{1,2,3})};rejectUpdate(actual,current,actual.versionCode,"Foreign signature accepted");
  actual.signatures=new android.content.pm.Signature[0];rejectUpdate(actual,current,actual.versionCode,"Unsigned APK accepted");
  actual.signatures=new android.content.pm.Signature[]{null};rejectUpdate(actual,current,actual.versionCode,"Null signature accepted");
 }
 void rejectUpdate(android.content.pm.PackageInfo archive,android.content.pm.PackageInfo current,int code,String message)throws Exception{boolean rejected=false;try{OnlineUpdate.verifyPackage(archive,current,code);}catch(java.io.IOException expected){rejected=true;}check(rejected,message);}
 void updateFiles()throws Exception {
  java.io.File full=new java.io.File("test-only"){public long getTotalSpace(){return 10000000L;}public long getUsableSpace(){return 0;}};boolean noSpace=false;try{UpdateFiles.requireSpace(full,100);}catch(java.io.IOException expected){noSpace=true;}check(noSpace,"Full storage accepted");
  java.io.File unknown=new java.io.File("test-only"){public long getTotalSpace(){return 0;}public long getUsableSpace(){return 0;}};UpdateFiles.requireSpace(unknown,100);
  java.io.File folder=new java.io.File(activity.getCacheDir(),"update-storage-test");java.io.File first=UpdateFiles.part(folder),second=UpdateFiles.part(folder);check(!first.equals(second),"Downloads share temporary path");
  writeBytes(first,new byte[]{1});writeBytes(second,new byte[]{2});check(first.delete()&&second.exists(),"Old cleanup removed successor download");
  String sha=new String(new char[64]).replace('\0','a');java.io.File target=UpdateFiles.target(folder,sha);writeBytes(target,new byte[]{3});UpdateFiles.promote(second,target);java.io.FileInputStream in=new java.io.FileInputStream(target);try{check(in.read()==2,"Atomic APK replacement failed");}finally{in.close();}check(!second.exists(),"Successful promotion left partial file");
  java.io.File blocked=new java.io.File(folder,"blocked");check(blocked.mkdir(),"Cannot create rename-failure fixture");java.io.File part=UpdateFiles.part(folder);writeBytes(part,new byte[]{4});boolean failed=false;try{UpdateFiles.promote(part,blocked);}catch(java.io.IOException expected){failed=true;}check(failed&&part.exists()&&blocked.isDirectory(),"Failed promotion destroyed its source or destination");part.delete();blocked.delete();
  java.io.File unrelated=new java.io.File(folder,"user-file.txt");writeBytes(unrelated,new byte[]{5});long old=System.currentTimeMillis()-86400001L;check(target.setLastModified(old)&&unrelated.setLastModified(old),"Cannot age update fixtures");java.io.File fresh=UpdateFiles.part(folder);writeBytes(fresh,new byte[]{6});UpdateFiles.expire(folder,System.currentTimeMillis());check(!target.exists()&&unrelated.exists()&&fresh.exists(),"Update cleanup crossed ownership/age bounds");unrelated.delete();fresh.delete();folder.delete();
 }
 void writeBytes(java.io.File file,byte[] data)throws Exception{java.io.FileOutputStream out=new java.io.FileOutputStream(file);try{out.write(data);}finally{out.close();}}
 void updateCancellationOnDestroy()throws Exception {
  final java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));final java.util.concurrent.CountDownLatch accepted=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),done=new java.util.concurrent.CountDownLatch(1);
  Thread peer=new Thread(new Runnable(){public void run(){try{java.net.Socket socket=server.accept();try{accepted.countDown();release.await(15,java.util.concurrent.TimeUnit.SECONDS);}finally{socket.close();}}catch(Exception ignored){}}},"update-cancel-peer");peer.start();
  final java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+server.getLocalPort()+"/stall").openConnection();connection.setConnectTimeout(2000);connection.setReadTimeout(10000);final NetworkDeadline deadline=new NetworkDeadline(connection,30000);OnlineUpdate.track(activity,deadline);
  final java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<Throwable>();Thread request=new Thread(new Runnable(){public void run(){try{connection.getResponseCode();}catch(Throwable e){error.set(e);}finally{done.countDown();}}},"update-cancel-request");request.start();
  try{check(accepted.await(2,java.util.concurrent.TimeUnit.SECONDS),"Cancellation request never connected");runOnMainSync(new Runnable(){public void run(){activity.finish();}});for(int n=0;n<100&&!activity.closed;n++)Thread.sleep(50);check(activity.closed,"Android did not destroy finished Activity");boolean stopped=done.await(2,java.util.concurrent.TimeUnit.SECONDS);boolean cancelObserved=false;try{deadline.check();}catch(java.io.InterruptedIOException observed){cancelObserved=true;}check(stopped,"Activity destruction left update request blocked: closed="+activity.closed+" cancel="+cancelObserved+" sameActivity="+(MainActivity.active==activity));check(error.get() instanceof java.io.IOException,"Cancellation did not interrupt HTTP read");boolean cancelled=false;try{deadline.check();}catch(java.io.InterruptedIOException expected){cancelled=true;}check(cancelled&&activity.closed,"Activity did not cancel its owned update");}
  finally{OnlineUpdate.release(activity,deadline);release.countDown();server.close();peer.join(2000);request.join(2000);}check(!peer.isAlive()&&!request.isAlive(),"Cancellation fixture leaked threads");
 }
 void earlyNetworkCancellation()throws Exception {
  // Reproduce the API17 window: the first disconnect has no published socket to close.
  final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
  java.net.HttpURLConnection c=new java.net.HttpURLConnection(new java.net.URL("http://127.0.0.1/fixture")){
   public void disconnect(){calls.incrementAndGet();}public boolean usingProxy(){return false;}public void connect(){}
  };
  NetworkDeadline d=new NetworkDeadline(c,10000);try{d.cancel();long until=android.os.SystemClock.elapsedRealtime()+1500;while(calls.get()<3&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(20);check(calls.get()>=3,"Cancellation abandoned a not-yet-published socket");}finally{d.close();}
  Thread.sleep(150);int stopped=calls.get();Thread.sleep(250);check(stopped==calls.get(),"Closed request kept cancellation timer alive");
 }
 void networkDeadline()throws Exception {
  final java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));final java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
  Thread peer=new Thread(new Runnable(){public void run(){try{java.net.Socket client=server.accept();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}finally{client.close();}}catch(Exception ignored){}}},"deadline-test-peer");peer.start();
  java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+server.getLocalPort()+"/stall").openConnection();connection.setConnectTimeout(2000);connection.setReadTimeout(4000);NetworkDeadline deadline=new NetworkDeadline(connection,350);long started=android.os.SystemClock.elapsedRealtime();boolean failed=false;
  try{connection.getResponseCode();}catch(java.io.IOException expected){failed=true;}finally{deadline.close();release.countDown();server.close();peer.join(2000);}
  check(failed,"Stalled HTTP request did not fail");check(android.os.SystemClock.elapsedRealtime()-started<2500,"Whole-request deadline did not interrupt API17 I/O");boolean expired=false;try{deadline.check();}catch(java.net.SocketTimeoutException expected){expired=true;}check(expired,"Deadline state missing");check(!peer.isAlive(),"Test peer leaked");
 }
 void environmentCheck()throws Exception {
  org.json.JSONObject partial=new org.json.JSONObject().put("oemCallAudioApks",new org.json.JSONObject().put("packages",new org.json.JSONArray().put(new org.json.JSONObject().put("package","com.android.phone").put("retryUpload",true)).put(new org.json.JSONObject().put("package","com.android.systemui").put("retryUpload",false))));String retry=DeviceApkEvidence.retryMessage(partial);check(retry.contains("com.android.phone")&&retry.contains("\u8bf7\u518d\u6b21\u70b9\u51fb")&&!retry.contains("com.android.systemui"),"Failed APK upload must tell the user what to retry and leave successful packages out");check(DeviceApkEvidence.retryMessage(new org.json.JSONObject()).length()==0,"No failed APK upload must not show a retry prompt");
  org.json.JSONObject report=EnvironmentCheck.collect(activity,new org.json.JSONObject());check(report.getBoolean("passive"),"Environment check must be passive");check(!report.getJSONObject("rootReadiness").getBoolean("exploitExecuted"),"Passive check executed exploit");check(report.getJSONObject("rootReadiness").getInt("uid")==android.os.Process.myUid(),"Root evidence wrong UID");for(String key:new String[]{"platform","usb","avcDecoders","audio","process","display"})check(report.getJSONObject(key).has("status"),"Missing check status: "+key);check(report.toString().getBytes("UTF-8").length<40000,"Environment report too large");check(report.getJSONObject("platform").getInt("sdk")==17,"Wrong device identity");check(!report.getJSONObject("usb").getBoolean("phonePresent"),"Emulator unexpectedly identified as iPhone");
  check(report.getJSONObject("audioBuffers").getJSONArray("formats").length()==4,"Missing passive audio buffer evidence");
  check(report.getJSONObject("settingsImplementation").has("status"),"Settings evidence unavailable without status");
  org.json.JSONObject automation=report.getJSONObject("automationCapabilities");check("measured".equals(automation.getString("status")),"Automation evidence failed on API17");check(automation.getJSONArray("permissions").length()==4,"Missing automation permissions");check(!automation.getJSONArray("permissions").getJSONObject(0).getBoolean("granted"),"Ordinary APK unexpectedly has input injection permission");check(automation.getJSONArray("accessibilityIntentHandlers").length()>0,"API17 accessibility settings handler missing");check(automation.getString("adbUsable").startsWith("not_tested"),"ADB capability must not be inferred from properties");check(report.getJSONObject("playbackOptions").has("renderedFrames"),"FPS evidence missing");
   org.json.JSONObject permissions=report.getJSONObject("connectionPermissions");check(permissions.has("vpnApprovalRequired")&&permissions.has("lockdownPairSaved"),"Missing permission/pair evidence");boolean found=false;org.json.JSONArray handlers=permissions.getJSONArray("usbHandlerCandidates");for(int n=0;n<handlers.length();n++){org.json.JSONObject handler=handlers.getJSONObject(n);if("local.headunit.diplay".equals(handler.getString("package"))){found=true;check(handler.getJSONArray("filters").getJSONObject(0).getString("vendor-id").equals("1452"),"Apple USB filter was not decoded");}}check(found,"DiPlay USB handler absent");

  report.put("fragmentTest",new String(new char[5000]).replace('\0','\u6d4b'));EnvironmentCheck.record(activity,report);org.json.JSONArray rows=DiagnosticLog.snapshot(activity);java.io.ByteArrayOutputStream decoded=new java.io.ByteArrayOutputStream();int parts=0;
  for(int n=0;n<rows.length();n++){String event=rows.getJSONObject(n).getString("event");if(event.startsWith("ENVIRONMENT_CHECK_PART ")){org.json.JSONObject part=new org.json.JSONObject(event.substring(event.indexOf(' ')+1));if(!report.getString("checkId").equals(part.getString("checkId")))continue;check(event.length()<=3000,"Server per-event bound exceeded");check(part.getInt("part")==parts++,"Missing or reordered fragment");decoded.write(android.util.Base64.decode(part.getString("data"),android.util.Base64.NO_WRAP));}}
  check(parts>1,"Fragment test did not span multiple events");check(report.toString().equals(new String(decoded.toByteArray(),"UTF-8")),"Environment fragments corrupted Unicode report");

 }
 void thirdPartyAudioSnapshot()throws Exception{org.json.JSONObject state=OemAudioObserver.snapshot(activity);for(String key:new String[]{"mode","musicActive","musicVolume","voiceCallVolume","bluetoothSco","speaker","microphoneMuted","recordingOwner","focusOwner","status"})check(state.has(key),"Missing passive third-party audio evidence: "+key);check("not_available_on_api17".equals(state.getString("recordingOwner")),"Must not claim third-party mic ownership visibility");check("measured_without_audio_capture".equals(state.getString("status")),"Third-party probe must not capture or compete for audio");check(state.getJSONObject("legacyRouting").has("currentMode"),"Missing API17 route-policy observation");routeEvidence=state.getJSONObject("legacyRouting").toString();int old=DiagnosticLog.snapshot(activity).length();OemAudioObserver observer=new OemAudioObserver(activity);Thread.sleep(1200);observer.close();boolean recorded=false;org.json.JSONArray events=DiagnosticLog.snapshot(activity);for(int i=old;i<events.length();i++)if(events.getJSONObject(i).optString("event").contains("THIRD_PARTY_AUDIO_STATE"))recorded=true;check(recorded,"Passive audio timeline did not record a sample");}
 void reportIo()throws Exception {
  String legacyEvent=new String(new char[5000]).replace('\0','\u6d4b');org.json.JSONArray original=new org.json.JSONArray().put(new org.json.JSONObject().put("id","old-large-row").put("time",1).put("event",legacyEvent));org.json.JSONArray wire=ReportIo.wireRows(original,"");java.io.ByteArrayOutputStream joined=new java.io.ByteArrayOutputStream();
  for(int i=0;i<wire.length();i++){String event=wire.getJSONObject(i).getString("event");check(event.length()<=3000,"Legacy event exceeds server bound");org.json.JSONObject fragment=new org.json.JSONObject(event.substring(event.indexOf(' ')+1));check(fragment.getInt("part")==i,"Legacy fragments unordered");joined.write(android.util.Base64.decode(fragment.getString("data"),android.util.Base64.NO_WRAP));}check(legacyEvent.equals(new String(joined.toByteArray(),"UTF-8")),"Legacy oversized log lost bytes");check(original.length()==1&&original.getJSONObject(0).getString("event").equals(legacyEvent),"Upload conversion mutated acknowledgement IDs");

  String id="20990101T000000Z-abcdef123456";byte[] valid=("{\"id\":\""+id+"\",\"events\":2}").getBytes("UTF-8");check(id.equals(ReportIo.receipt(new java.io.ByteArrayInputStream(valid),2)),"Valid receipt rejected");
  boolean wrong=false;try{ReportIo.receipt(new java.io.ByteArrayInputStream(valid),3);}catch(java.io.IOException expected){wrong=true;}check(wrong,"Wrong event count acknowledged");
  boolean large=false;try{ReportIo.receipt(new java.io.ByteArrayInputStream(new byte[4097]),2);}catch(java.io.IOException expected){large=true;}check(large,"Unbounded receipt accepted");
  java.io.File directory=new java.io.File(activity.getCacheDir(),"report-io-test");java.io.File saved=ReportIo.backup(directory,valid);check(saved.length()==valid.length,"Report backup corrupted");
  ReportIo.expireBackup(directory,System.currentTimeMillis());check(saved.exists(),"Fresh report prematurely removed");check(saved.setLastModified(System.currentTimeMillis()-86400001L),"Cannot age test report");ReportIo.expireBackup(directory,System.currentTimeMillis());check(!saved.exists(),"Expired export was retained");directory.delete();
  java.io.File blocked=new java.io.File(activity.getCacheDir(),"report-blocker");new java.io.FileOutputStream(blocked).close();boolean unavailable=false;try{ReportIo.backup(blocked,valid);}catch(java.io.IOException expected){unavailable=true;}finally{blocked.delete();}check(unavailable,"Failed storage must be surfaced for optional fallback");
 }
 void connectedOperations()throws Exception {
  final java.util.concurrent.CountDownLatch completed=new java.util.concurrent.CountDownLatch(1);
  runOnMainSync(new Runnable(){public void run(){activity.connecting=true;activity.show("连接保持测试");
   check(activity.actionAllowedWhileConnected("提交日志"),"Live diagnostics blocked");check(!activity.actionAllowedWhileConnected("在线更新"),"Updating an active projection allowed");
   activity.task("upload",new MainActivity.Work(){public String run(){completed.countDown();return "模拟提交完成";}});
  }});
  check(completed.await(5,java.util.concurrent.TimeUnit.SECONDS),"Live diagnostic worker did not execute");waitForIdleSync();for(int n=0;n<50&&activity.busy;n++){Thread.sleep(20);waitForIdleSync();}check(!activity.busy,"Diagnostic completion did not reach UI");
  runOnMainSync(new Runnable(){public void run(){check(activity.connecting,"Submitting diagnostics stopped projection");check("连接保持测试".equals(activity.status.getText().toString()),"Upload overwrote connection state");check(activity.operationStatus.getVisibility()==View.VISIBLE,"Operation feedback hidden");activity.connecting=false;}});
 }
 void boundedPacketIo()throws Exception{
  check(NativeUsb.write(-1,1,new byte[64],1)==-9,"Native USB write must preserve EBADF");
  check(NativeUsb.write(-1,129,new byte[64],1)==-22,"Native USB write accepted IN endpoint");
  check(NativeUsb.write(-1,1,new byte[16385],1)==-22,"Native USB write exceeded stack bound");

  int before=new java.io.File("/proc/self/fd").list().length;
  for(int round=0;round<20;round++){
   android.os.ParcelFileDescriptor[] pipe=android.os.ParcelFileDescriptor.createPipe();
   PlatformSupport.setNonBlocking(pipe[0]);PlatformSupport.setNonBlocking(pipe[1]);
   final BoundedPacketIo reader=new BoundedPacketIo(pipe[0].getFd());
   BoundedPacketIo writer=new BoundedPacketIo(pipe[1].getFd());
   pipe[0].close();pipe[1].close(); // Duplicates retain exact endpoint ownership.
   final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
   try{
    byte[] bytes=new byte[]{1,2,3,4};check(writer.write(bytes,1,2),"Native packet write");byte[] output=new byte[64];
    check(reader.read(output)==2&&output[0]==2&&output[1]==3,"Native packet integrity");
    final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
    Thread waiting=new Thread(new Runnable(){public void run(){try{entered.countDown();check(reader.read(new byte[64])==0,"Empty read must time out");}catch(Throwable t){failure.set(t);}}},"bounded-io-test");
    waiting.start();entered.await();Thread.sleep(10);long at=android.os.SystemClock.elapsedRealtime();reader.close();waiting.join(1000);
    check(!waiting.isAlive()&&android.os.SystemClock.elapsedRealtime()-at<1000,"Read kept old connection alive");
    if(failure.get()!=null)throw new AssertionError(failure.get());check(reader.read(output)==0,"Closed reader used released descriptor");
   }finally{reader.close();writer.close();}
  }
  int after=new java.io.File("/proc/self/fd").list().length;check(after<=before+2,"Repeated native I/O leaked descriptors: "+before+" -> "+after);
 }
 void bydMultimediaControlProtocol()throws Exception{
  check(BydMultimediaCallRoute.supportsFirmware(17,"MX9A0",382),"Audited MX9A0 multimedia service was not enabled");
  check(!BydMultimediaCallRoute.supportsFirmware(17,"Freescale MX9A0",382),"Unverified OEM model variant was enabled");
  check(!BydMultimediaCallRoute.supportsFirmware(17,"MX9A0",381)&&!BydMultimediaCallRoute.supportsFirmware(28,"MX9A0",382)&&!BydMultimediaCallRoute.supportsFirmware(17,"other",382),"OEM Binder route escaped its firmware allowlist");
  check(java.util.Arrays.equals(BydMultimediaCallRoute.phoneModeStartPayload(),new byte[]{13}),"OEM phone-state enter payload changed");
  check(java.util.Arrays.equals(BydMultimediaCallRoute.phoneModeStopPayload(),new byte[]{13,0}),"OEM background restore payload changed");
  class FixtureBinder extends Binder implements IInterface {
   byte group,sub;byte[] received;String[] keys,output;int transactions;
   FixtureBinder(){attachInterface(this,BydMultimediaCallRoute.DESCRIPTOR);}
   public IBinder asBinder(){return this;}
   @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
    if(code!=1)return false;data.enforceInterface(BydMultimediaCallRoute.DESCRIPTOR);group=data.readByte();sub=data.readByte();keys=data.createStringArray();received=data.createByteArray();output=data.createStringArray();transactions++;
    if(output==null||output.length!=1)throw new RemoteException("missing_official_output_buffer");
    byte[] returned=received.clone();if(returned.length>0)returned[0]=42;String[] strings=new String[]{"fixture"};reply.writeNoException();reply.writeByteArray(returned);reply.writeStringArray(strings);return true;
   }
  }
  FixtureBinder binder=new FixtureBinder();BydMultimediaCallRoute.ControlReply entered=BydMultimediaCallRoute.transactControl(binder,(byte)2,(byte)1,BydMultimediaCallRoute.phoneModeStartPayload());
  check(entered.handled&&binder.group==2&&binder.sub==1&&binder.keys==null&&binder.received.length==1&&binder.received[0]==13,"OEM AIDL enter transaction wire contract differs from shipped proxy");
  check(entered.returnedBytes[0]==42&&entered.returnedStrings.length==1&&"fixture".equals(entered.returnedStrings[0]),"OEM AIDL out-parameter reply was not consumed");
  BydMultimediaCallRoute.ControlReply exited=BydMultimediaCallRoute.transactControl(binder,(byte)2,(byte)2,BydMultimediaCallRoute.phoneModeStopPayload());
  check(exited.handled&&binder.transactions==2&&binder.group==2&&binder.sub==2&&binder.received.length==2&&binder.received[0]==13&&binder.received[1]==0,"OEM AIDL restore transaction wire contract differs from shipped proxy");
  class WrongDescriptorBinder extends Binder implements IInterface {WrongDescriptorBinder(){attachInterface(this,"wrong.descriptor");}public IBinder asBinder(){return this;}}
  boolean rejected=false;try{BydMultimediaCallRoute.transactControl(new WrongDescriptorBinder(),(byte)2,(byte)1,new byte[]{13});}catch(RemoteException expected){rejected=true;}check(rejected,"Unrecognized OEM Binder descriptor was accepted");
  check(OemAudioObserver.shouldCaptureCallRouteFrame("APP_REV_DATA",2,1,120,130),"Missing bounded OEM source-state echo");check(OemAudioObserver.shouldCaptureCallRouteFrame("APP_REV_DATA",4,4,120,130),"Missing bounded OEM mute feedback");check(OemAudioObserver.shouldCaptureCallRouteFrame("INTENT_SERIAL_DATA",1,2,120,130),"Missing raw MCU source-state feedback from DecodeSourceInfo path");check(OemAudioObserver.shouldCaptureCallRouteFrame("INTENT_SERIAL_DATA",4,4,120,130),"Missing raw MCU audio-state feedback");check(!OemAudioObserver.shouldCaptureCallRouteFrame("INTENT_SERIAL_DATA",2,1,120,130)&&!OemAudioObserver.shouldCaptureCallRouteFrame("APP_REV_DATA",4,1,120,130)&&!OemAudioObserver.shouldCaptureCallRouteFrame("APP_REV_BT_DATA",2,1,120,130)&&!OemAudioObserver.shouldCaptureCallRouteFrame("INTENT_SERIAL_DATA",3,1,120,130)&&!OemAudioObserver.shouldCaptureCallRouteFrame("APP_REV_DATA",2,1,131,130),"Call feedback capture escaped its scope");
 }
 void logRetention()throws Exception{long at=android.os.SystemClock.elapsedRealtime();MainActivity.record(activity,"TEST_STARTUP",new org.json.JSONObject().put("marker","keep-startup"));for(int n=0;n<500;n++)MainActivity.record(activity,"CONTROL",new org.json.JSONObject().put("detail","iap2 rx=0x5001"));check(MainActivity.snapshot(activity).toString().contains("keep-startup"),"Routine updates evicted startup");long took=android.os.SystemClock.elapsedRealtime()-at;android.util.Log.i("DiPlayTest","500 log records durationMs="+took);check(took<10000,"Logging stalls protocol threads");
  org.json.JSONArray sent=MainActivity.snapshot(activity);MainActivity.record(activity,"AFTER_UPLOAD",new org.json.JSONObject().put("marker","retain-new-row"));DiagnosticLog.acknowledge(activity,sent);String remaining=MainActivity.snapshot(activity).toString();check(remaining.contains("retain-new-row")&&!remaining.contains("keep-startup"),"Upload acknowledgement removed concurrent logs or retained old rows");
 }
 void boundServiceStop()throws Exception{
  final java.util.concurrent.CountDownLatch bound=new java.util.concurrent.CountDownLatch(1),released=new java.util.concurrent.CountDownLatch(1);
  final ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,android.os.IBinder b){bound.countDown();}public void onServiceDisconnected(ComponentName n){}};
  final BroadcastReceiver status=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if("bound-test".equals(i.getStringExtra("connection_id"))&&"STOPPED".equals(i.getStringExtra("stage")))released.countDown();}};
  activity.registerReceiver(status,new IntentFilter("local.headunit.diplay.STATUS"));
  try{
   runOnMainSync(new Runnable(){public void run(){activity.startService(new Intent(activity,WiredService.class).putExtra("connection_id","bound-test"));check(activity.bindService(new Intent(activity,WiredService.class).setAction(android.net.VpnService.SERVICE_INTERFACE),connection,Context.BIND_AUTO_CREATE),"Bind rejected");}});
   check(bound.await(5,java.util.concurrent.TimeUnit.SECONDS),"VPN service bind timeout");
   runOnMainSync(new Runnable(){public void run(){activity.restoreConnection();check("bound-test".equals(activity.connectionId),"UI did not adopt running session");activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}});
   waitForIdleSync();Thread.sleep(400);check("bound-test".equals(WiredService.currentConnectionId()),"Backgrounding stopped USB session");check(released.getCount()==1,"Background cleanup was scheduled");
   runOnMainSync(new Runnable(){public void run(){activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}});waitForIdleSync();Thread.sleep(300);check("bound-test".equals(WiredService.currentConnectionId()),"Foreground return created a new session");

   runOnMainSync(new Runnable(){public void run(){WiredService.stop(activity);}});
   check(released.await(5,java.util.concurrent.TimeUnit.SECONDS),"Bound service waited for onDestroy before cleanup");
   check(WiredService.isBusy(),"Test did not keep service bound until release");
  }finally{runOnMainSync(new Runnable(){public void run(){activity.unbindService(connection);activity.unregisterReceiver(status);}});}
  for(int n=0;n<50&&WiredService.isBusy();n++)Thread.sleep(100);
  check(!WiredService.isBusy(),"Released connection still blocks reconnect");
 }
 void wheelControl()throws Exception {
  final Object token=new Object();final java.util.List<Integer> actions=new java.util.concurrent.CopyOnWriteArrayList<Integer>();
  final WheelControl control=new WheelControl(activity,new WheelControl.Sink(){public Object session(){return token;}public boolean send(Object session,int action){check(session==token,"Wheel session changed");actions.add(action);return true;}},true);
  try{for(int key:new int[]{4,3,94,95})control.onReceive(activity,new Intent("APP_REV_DATA").putExtra("list",new byte[]{4,1,(byte)key}));long until=System.currentTimeMillis()+2000;while(actions.size()<4&&System.currentTimeMillis()<until)Thread.sleep(10);check(actions.equals(java.util.Arrays.asList(1,2,3,4)),"Measured wheel events not dispatched once in order: "+actions);control.onReceive(activity,new Intent("APP_REV_BT_DATA").putExtra("list",new byte[]{4,1,3}));control.close();control.onReceive(activity,new Intent("APP_REV_DATA").putExtra("list",new byte[]{4,1,3}));Thread.sleep(50);check(actions.size()==4,"Closed or wrong-action wheel event dispatched");}finally{control.close();}
 }
 void wheelProbe()throws Exception {
  final java.util.concurrent.atomic.AtomicReference<WheelProbe> ref=new java.util.concurrent.atomic.AtomicReference<WheelProbe>();
  runOnMainSync(new Runnable(){public void run(){WheelProbe p=new WheelProbe(activity);activity.wheelProbe=p;ref.set(p);p.start();check(MainActivity.interactionBusy(),"Auto foreground can interrupt wheel probe");}});final WheelProbe p=ref.get();
  try{runOnMainSync(new Runnable(){public void run(){p.baselineDone=true;p.advance();for(int step=0;step<6;step++){p.receiver.onReceive(activity,new android.content.Intent("APP_REV_DATA").putExtra("list",new byte[]{4,1,(byte)(80+step)}));p.receiver.onReceive(activity,new android.content.Intent("APP_REV_BT_DATA").putExtra("list",new byte[]{4,1,49,50,51,52,53}));p.key(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MEDIA_NEXT),"test");p.key(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_MEDIA_NEXT),"test");if(step<5)p.advance();}p.finish("complete",false);}});
   check(p.ended&&!p.registered&&activity.wheelProbe==null,"Probe leaked receiver or owner");check(p.steps.length()==7,"Missing baseline or wheel steps");
   for(int i=1;i<7;i++){org.json.JSONObject step=p.steps.getJSONObject(i);check(WheelProbe.KEYS[i-1].equals(step.getString("label")),"Wrong wheel order");org.json.JSONArray rows=step.getJSONArray("events");check(rows.length()>=3,"Missing key ingress");int oem=0;for(int n=0;n<rows.length();n++)if("oem_key_broadcast".equals(rows.getJSONObject(n).optString("source"))){oem++;check(rows.getJSONObject(n).getJSONArray("bytes").length()==3,"Phone payload retained");}check(oem==1,"BT payload treated as key");}
   check(p.report.toString().getBytes("UTF-8").length<64000,"Probe too large");int count=p.observed;p.key(new android.view.KeyEvent(0,87),"late");check(p.observed==count,"Late event after close");
  }finally{runOnMainSync(new Runnable(){public void run(){p.finish("test_cleanup",false);}});}
  long until=System.currentTimeMillis()+5000;while(activity.busy&&System.currentTimeMillis()<until)Thread.sleep(20);check(!activity.busy,"Probe persistence did not finish");
 }

 void siriPlaybackHold()throws Exception {
  final java.util.List<String> lines=new java.util.concurrent.CopyOnWriteArrayList<String>();
  LegacyMedia media=new LegacyMedia(null,800,480,new kotlin.jvm.functions.Function1<String,kotlin.Unit>(){public kotlin.Unit invoke(String line){lines.add(line);return kotlin.Unit.INSTANCE;}});
  java.net.DatagramSocket receiver=new java.net.DatagramSocket(0,java.net.InetAddress.getByName("127.0.0.1"));receiver.setSoTimeout(2000);LegacyMedia.Microphone mic=null;
  try{
   java.util.Map<String,Object> state=new java.util.HashMap<String,Object>();state.put("appStateID",1L);state.put("speechMode",1L);java.util.Map<String,Object> params=new java.util.HashMap<String,Object>();params.put("appStates",java.util.Collections.singletonList(state));media.speechMode(params);
   com.shilapi.xcertplay.airplay.MicrophoneConfig config=new com.shilapi.xcertplay.airplay.MicrophoneConfig("speechrecognition",24000,1,96,20,java.net.InetAddress.getByName("127.0.0.1"),receiver.getLocalPort(),new byte[32],com.shilapi.xcertplay.airplay.AudioCodecKind.LPCM,null,24000);
   mic=LegacyMedia.Microphone.class.getConstructor(LegacyMedia.class,com.shilapi.xcertplay.airplay.MicrophoneConfig.class,Boolean.TYPE).newInstance(media,config,true);long start=android.os.SystemClock.elapsedRealtime();
   for(int j=0;j<6;j++){if(j==2||j==4){state.put("speechMode",j==2?-1L:1L);media.speechMode(params);}byte[] b=new byte[2048];java.net.DatagramPacket packet=new java.net.DatagramPacket(b,b.length);try{receiver.receive(packet);}catch(java.net.SocketTimeoutException e){throw new RuntimeException("Hold uplink timed out: "+lines.toString(),e);}check(packet.getLength()==996,"Wrong silent uplink size");int seq=((b[2]&255)<<8)|(b[3]&255);int timestamp=((b[4]&255)<<24)|((b[5]&255)<<16)|((b[6]&255)<<8)|(b[7]&255);check(seq==j&&timestamp==j*480,"Paused RTP counters discontinuous");byte[] nonce=new byte[12];System.arraycopy(b,packet.getLength()-8,nonce,4,8);byte[] pcm=com.shilapi.xcertplay.airplay.AirPlayCrypto.INSTANCE.chachaOpen(new byte[32],nonce,java.util.Arrays.copyOfRange(b,12,packet.getLength()-8),java.util.Arrays.copyOfRange(b,4,12));for(byte value:pcm)check(value==0,"Hold sent captured speech");}
   check(android.os.SystemClock.elapsedRealtime()-start>=100,"Silent uplink busy loop");mic.close();check(mic.released(),"Held microphone failed to stop");
   boolean held=false;for(String line:lines){check(!line.startsWith("microphone_capture_path")&&!line.startsWith("microphone_failed"),"Hold opened physical recorder or failed: "+line);if(line.startsWith("microphone_playback_hold"))held=true;}check(held,"No physical capture hold evidence");
  }finally{if(mic!=null)mic.close();receiver.close();media.close();}check(!MediaWorkers.busy(),"Held microphone worker leaked");
 }
 void assistantPlayback()throws Exception {
  final java.util.List<String> lines=new java.util.concurrent.CopyOnWriteArrayList<String>();
  LegacyMedia media=new LegacyMedia(null,800,480,new kotlin.jvm.functions.Function1<String,kotlin.Unit>(){public kotlin.Unit invoke(String line){lines.add(line);return kotlin.Unit.INSTANCE;}});
  AudioRouting route=new AudioRouting(activity,media);media.setRouting(route);
  try{
   check(route.stream("assistant")==android.media.AudioManager.STREAM_MUSIC,"Siri must use music stream");
   check(route.packet("media")==1f&&route.packet("assistant")==1f&&route.packet("media")==1f,"Shared playback focus failed");
   waitForIdleSync();check(route.packet("media")==1f&&route.packet("assistant")==1f,"Main audio roles stole focus from each other");
   final int secondMusic=AudioRouting.secondMusic(activity);final boolean bydMediaCall=secondMusic==10;check(!LegacyAudioPolicy.usesOemCallRoute(secondMusic),"CarPlay telephony must not switch the MCU to Bluetooth Phone source 13");check(LegacyAudioPolicy.usesClientCallFocus(secondMusic),"CarPlay telephony needs its own Android audio focus");check(route.stream("call")== (bydMediaCall?android.media.AudioManager.STREAM_MUSIC:android.media.AudioManager.STREAM_VOICE_CALL),"CarPlay telephony PCM did not use the selected Android output stream");
   for(String kind:new String[]{"speechrecognition","telephony"}){lines.clear();String role=kind.equals("speechrecognition")?"assistant":kind.equals("telephony")?"call":"media";
   android.media.AudioManager audioManager=(android.media.AudioManager)activity.getSystemService(android.content.Context.AUDIO_SERVICE);int priorMode=audioManager.getMode();boolean priorSpeaker=audioManager.isSpeakerphoneOn();int priorVolume=audioManager.getStreamVolume(route.stream("call"));com.shilapi.xcertplay.airplay.AudioStreamId id=new com.shilapi.xcertplay.airplay.AudioStreamId(100,kind);
   com.shilapi.xcertplay.airplay.AudioFormat format=new com.shilapi.xcertplay.airplay.AudioFormat(com.shilapi.xcertplay.airplay.AudioCodecKind.LPCM,24000,1,96,kind);
   media.onAudioStarted(id,format,0);if(kind.equals("telephony")){boolean modeOk=bydMediaCall?audioManager.getMode()==priorMode:audioManager.getMode()==android.media.AudioManager.MODE_IN_CALL;boolean speakerOk=bydMediaCall?audioManager.isSpeakerphoneOn()==priorSpeaker:audioManager.isSpeakerphoneOn();check(modeOk&&speakerOk&&audioManager.getStreamVolume(route.stream("call"))==audioManager.getStreamMaxVolume(route.stream("call")),"Call route failed to preserve BYD mode/speaker state or request fallback phone strategy/max volume");}
   for(int packet=0;packet<80;packet++){byte[] rtp=new byte[972];for(int n=0;n<480;n++){short sample=(short)(Math.sin((packet*480+n)*2*Math.PI*440/24000)*2000);rtp[12+n*2]=(byte)(sample>>8);rtp[13+n*2]=(byte)sample;}media.onAudioRtp(id,format,rtp,packet*480);Thread.sleep(20);}
   media.onAudioStopped(id);boolean started=false,progress=false,summary=false;boolean call=kind.equals("telephony");if(call)check(audioManager.getMode()==priorMode&&audioManager.isSpeakerphoneOn()==priorSpeaker&&audioManager.getStreamVolume(route.stream("call"))==priorVolume,"Call route did not restore prior mode, speaker state, and volume");int actualStream=call?route.stream("call"):android.media.AudioManager.STREAM_MUSIC;String expectedStream="stream="+actualStream,expectedFormat=call?"inputChannels=1 outputChannels=1 outputRate=24000 actualStream="+actualStream:"inputChannels=1 outputChannels=2 outputRate=48000 actualStream=3";
   for(String line:lines){check(!line.startsWith("audio_failed"),"Audio PCM failed: "+line);if(line.contains("audio_started role="+role+" "+expectedStream)&&line.contains(expectedFormat))started=true;if((line.contains("audio_progress role="+role)||line.contains("audio_checkpoint role="+role))&&line.contains("peak=2000")&&line.contains("volume=1.0")&&!line.contains("head=0 "))progress=true;if(line.contains("audio_summary role="+role)&&!line.contains("headBeforeStop=0 "))summary=true;}
   check(started&&progress&&summary,"No verified voice output/progress kind="+kind+": "+lines.toString());
   }
  }finally{media.close();}check(!MediaWorkers.busy(),"Siri workers leaked");
 }


 void roleFocusLifecycle()throws Exception {
  final java.util.concurrent.atomic.AtomicReference<Float> music=new java.util.concurrent.atomic.AtomicReference<Float>(0f),voice=new java.util.concurrent.atomic.AtomicReference<Float>(0f);
  PlaybackFocus media=new PlaybackFocus(activity,new PlaybackFocus.Volume(){public void set(float v){music.set(v);}},android.media.AudioManager.AUDIOFOCUS_GAIN,"media");
  PlaybackFocus call=new PlaybackFocus(activity,new PlaybackFocus.Volume(){public void set(float v){voice.set(v);}},android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,"call",android.media.AudioManager.STREAM_MUSIC);
  try{check(media.packet()==1f,"Media focus unavailable");check(call.packet()==1f,"Call focus unavailable");waitForIdleSync();Thread.sleep(100);check(music.get()==0f&&voice.get()==1f,"Call focus muted wrong role");check(media.packet()==0f,"Media stole focus during call");call.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS);check(call.packet()==0f&&voice.get()==0f,"Call output continued after permanent focus loss");call.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);check(call.packet()==1f&&voice.get()==1f,"Call output did not resume after focus gain");call.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);check(call.packet()==0f&&voice.get()==0f,"Transient focus loss was ignored");call.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);call.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);check(call.packet()==.2f&&voice.get()==.2f,"Call output did not honor duck focus");check(media.packet()==0f,"Call focus recovery incorrectly reactivated media");call.close();media.foreground();waitForIdleSync();Thread.sleep(100);check(media.packet()==1f,"Media did not resume after call focus was released");}finally{call.close();media.close();}
 }
 void callPcmDiagnosticEvidence(){
  PcmEvidence evidence=new PcmEvidence();byte[] bytes=new byte[4096];for(int i=0;i<bytes.length;i+=2){bytes[i]=0x20;bytes[i+1]=0x10;}
  evidence.observeByteOrderCandidates(bytes);String summary=evidence.byteOrderCandidateSummary();
  check(summary.contains("littleRms=")&&summary.contains("bigRms=")&&summary.contains("littleZero=")&&summary.contains("bigZero=")&&summary.contains("samples=2048"),"Real-call PCM summary lacks both sample interpretations or silence measures: "+summary);
 }
 void focusLifecycle()throws Exception{
  final java.util.concurrent.atomic.AtomicReference<Float> level=new java.util.concurrent.atomic.AtomicReference<Float>(0f);
  PlaybackFocus focus=new PlaybackFocus(activity,new PlaybackFocus.Volume(){public void set(float v){level.set(v);}});
  try{check(level.get()==0f,"Idle focus must not play");check(focus.packet()==1f,"Audio focus grant failed");focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS);check(focus.packet()==0f&&level.get()==0f,"Playback stole focus after loss");focus.foreground();check(focus.packet()==1f,"Foreground playback did not recover from permanent focus loss");focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS);Thread.sleep(1800);waitForIdleSync();check(level.get()==0f,"Idle focus not released");check(focus.packet()==1f,"Playback after idle did not request focus");focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);check(focus.packet()==0f,"Transient focus loss ignored");java.lang.reflect.Field listenerField=PlaybackFocus.class.getDeclaredField("sessionListener");listenerField.setAccessible(true);android.media.AudioManager.OnAudioFocusChangeListener oldListener=(android.media.AudioManager.OnAudioFocusChangeListener)listenerField.get(focus);focus.playbackEnded();check(focus.packet(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)==1f,"New Siri session retained stale blocked focus");oldListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);check(focus.packet()==1f,"Old session callback muted next Siri session");focus.close();check(focus.packet()==0f,"Closed focus reactivated");}finally{focus.close();}
 }
 int countButtons(View v){if(v instanceof Button && !(v instanceof CompoundButton))return 1;int count=0;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)count+=countButtons(g.getChildAt(i));}return count;}
 byte[] asset(String name)throws Exception{java.io.InputStream in=getContext().getAssets().open("media-test/"+name);try{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}finally{in.close();}}
 void batchReportPersistence()throws Exception{
  org.json.JSONObject r=new org.json.JSONObject().put("batchId","instrumentation").put("time",System.currentTimeMillis()).put("revision",1).put("trials",new org.json.JSONArray());
  UsbBatchReport.save(activity,r);String old=UsbBatchReport.append(activity);r.put("revision",2);UsbBatchReport.save(activity,r);
  UsbBatchReport.acknowledge(activity,old);check(UsbBatchReport.read(activity)!=null,"Old upload deleted a newer batch");
  String current=UsbBatchReport.append(activity);UsbBatchReport.acknowledge(activity,current);check(UsbBatchReport.read(activity)==null,"Confirmed batch was not removed");
  r.put("time",System.currentTimeMillis()-86400001L);UsbBatchReport.save(activity,r);check(UsbBatchReport.read(activity)==null,"Expired batch retained");
  UsbBatchCapture.begin("a");UsbBatchCapture.accept("NCM",new org.json.JSONObject().put("connection","b").put("detail","wrong owner"));check(UsbBatchCapture.finish().length()==0,"Cross-session capture");
  UsbBatchCapture.begin("a");for(int n=0;n<100;n++)UsbBatchCapture.accept("NCM",new org.json.JSONObject().put("connection","a").put("detail","event "+n));
  check(UsbBatchCapture.finish().length()<=60,"Unbounded batch capture");
  UsbBatchCapture.begin("a");UsbBatchCapture.accept("NCM",new org.json.JSONObject().put("connection","a").put("detail","initialization evidence"));
  for(int n=0;n<100;n++)UsbBatchCapture.accept("USB_NETWORK",new org.json.JSONObject().put("connection","a").put("detail","packet "+n));
  org.json.JSONArray kept=UsbBatchCapture.finish();check(kept.length()==1&&kept.getString(0).contains("initialization evidence"),"Packet trace evicted startup evidence");

  org.json.JSONArray tests=new org.json.JSONArray();StringBuilder longText=new StringBuilder();for(int n=0;n<1400;n++)longText.append('x');
  for(int trial=0;trial<12;trial++){UsbBatchCapture.begin("large");for(int n=0;n<60;n++)UsbBatchCapture.accept("NCM",new org.json.JSONObject().put("connection","large").put("detail",longText.toString()));tests.put(new org.json.JSONObject().put("events",UsbBatchCapture.finish()).put("resourcesAtEnd",longText.toString()).put("performanceAtEnd",longText.toString()+longText.toString()).put("resourcesAfterRelease",longText.toString().substring(0,300)));}
  r.put("time",System.currentTimeMillis()).put("trials",tests).put("revision",3);UsbBatchReport.save(activity,r);
  String large=UsbBatchReport.append(activity);org.json.JSONArray rows=MainActivity.snapshot(activity);org.json.JSONArray wire=ReportIo.wireRows(rows,"unused-test-token");
  check(wire.toString().getBytes("UTF-8").length<240000,"Batch wire report exceeds upload budget");check(wire.length()<=1200,"Batch event limit");
  UsbBatchReport.acknowledge(activity,large);

 }
 void nativeStartupBinding(){
  java.util.concurrent.atomic.AtomicBoolean flag=new java.util.concurrent.atomic.AtomicBoolean(true);
  int[] stats=new int[6];byte[] packet=new byte[90];
  check(NativeUsb.startupWrite(999999,1,packet,15000,flag,stats)==-9,"Native startup did not preserve EBADF");
  check(stats[0]==0&&stats[5]==-9,"Failed submit marked as submitted");
  flag.set(false);
  check(NativeUsb.startupWrite(999999,1,packet,15000,flag,stats)==-125,"Native cancellation flag JNI binding");
  check(NativeUsb.startupWrite(999999,1,new byte[513],15000,flag,stats)==-22,"Native small-packet limit");
  check(NativeUsb.startupWrite(999999,1,packet,15001,flag,stats)==-22,"Native deadline limit");
  flag.set(true);byte[] incoming=new byte[16384];java.util.Arrays.fill(incoming,(byte)77);
  check(NativeUsb.startupRead(999999,136,incoming,15000,flag,stats)==-9,"Receive must preserve EBADF");
  check(incoming[0]==77&&incoming[16383]==77,"Failed receive changed output buffer");
  flag.set(false);
  for(int n=0;n<20;n++)check(NativeUsb.startupRead(999999,136,incoming,15000,flag,stats)==-125,"Receive cancel before submit");
  check(NativeUsb.startupRead(999999,6,incoming,15000,flag,stats)==-22,"Receive direction guard");
  check(NativeUsb.startupRead(999999,136,new byte[16385],15000,flag,stats)==-22,"Receive buffer bound");
  check(NativeUsb.startupRead(999999,136,incoming,15001,flag,stats)==-22,"Receive deadline bound");

 }
 void streamSoak(Bundle result)throws Exception {
  check(streamSeconds>=15&&streamSeconds<=1200,"Bounded stream test duration");
  final java.util.List<String> diagnostics=new java.util.concurrent.CopyOnWriteArrayList<String>();
  final java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<String>();
  java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(asset("perf-frames.bin")));
  int count=in.readInt();check(count==30,"Wrong 1024 fixture");final byte[][] frames=new byte[count][];
  for(int n=0;n<count;n++){int size=in.readInt();check(size>0&&size<1048576,"Fixture frame bound");frames[n]=new byte[size];in.readFully(frames[n]);}in.close();
  LegacyMedia media=new LegacyMedia(activity.preview.getHolder().getSurface(),1024,600,new kotlin.jvm.functions.Function1<String,kotlin.Unit>(){public kotlin.Unit invoke(String line){if(line.startsWith("video_failed"))failure.set(line);if(line.startsWith("video_progress")){diagnostics.add(line);if(diagnostics.size()>30)diagnostics.remove(0);}return kotlin.Unit.INSTANCE;}});
  long started=System.nanoTime(),next=started,lastSample=started,lastRendered=0;int sent=0,baselineFd=-1,baselineThreads=-1;
  org.json.JSONArray samples=new org.json.JSONArray();
  try{
   media.onVideoConfig(110,asset("perf-config.bin"));
   while(System.nanoTime()-started<streamSeconds*1000000000L){
    check(failure.get()==null,"Continuous decoder failed: "+failure.get());
    long now=System.nanoTime();media.onOwnedVideoFrame(110,frames[sent%count].clone(),0,now);sent++;next+=1000000000L/15;
    long wait=next-System.nanoTime();if(wait>0)Thread.sleep(wait/1000000,(int)(wait%1000000));else if(wait< -200000000L)next=System.nanoTime();
    now=System.nanoTime();if(now-lastSample>=5000000000L){
     int fd=new java.io.File("/proc/self/fd").list().length,threads=Thread.getAllStackTraces().size();
     if(baselineFd<0){baselineFd=fd;baselineThreads=threads;}
     check(fd<=baselineFd+3,"Continuous stream descriptor growth");check(threads<=baselineThreads+3,"Continuous stream thread growth");
     long rendered=media.getRenderedVideoFrames();check(rendered>lastRendered,"Continuous decoder stopped producing frames");lastRendered=rendered;lastSample=now;
     org.json.JSONObject sample=new org.json.JSONObject().put("elapsedMs",(now-started)/1000000).put("sent",sent).put("rendered",rendered).put("fd",fd).put("threads",threads).put("nativeBytes",android.os.Debug.getNativeHeapAllocatedSize()).put("heapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());samples.put(sample);
     Bundle progress=new Bundle();progress.putString("stream","stream "+sample.toString()+"\n");sendStatus(0,progress);
    }
   }
  }finally{media.close();}
  check(!MediaWorkers.busy(),"Continuous decoder worker leaked");
  org.json.JSONObject report=new org.json.JSONObject().put("scope","API17_emulator_synthetic_decode_not_physical_USB_or_display_latency").put("seconds",streamSeconds).put("sent",sent).put("rendered",media.getRenderedVideoFrames()).put("samples",samples).put("diagnostics",new org.json.JSONArray(diagnostics));
  writeBytes(new java.io.File(activity.getFilesDir(),"stream-soak.json"),report.toString().getBytes("UTF-8"));
  result.putString("stream","PASS: sustained 1024x600 / 15fps input, decoder progress, bounded descriptors/threads, clean release\n");
 }
 void mediaSoak(Bundle result)throws Exception {
  check(soakRounds<=100,"Soak round limit");mediaLifecycle();aacPlayback();
  int baselineFd=new java.io.File("/proc/self/fd").list().length,baselineThreads=Thread.getAllStackTraces().size();long baselineNative=android.os.Debug.getNativeHeapAllocatedSize();org.json.JSONArray samples=new org.json.JSONArray();
  for(int round=0;round<soakRounds;round++){
   mediaLifecycle();aacPlayback();check(!MediaWorkers.busy(),"Soak leaked media worker");
   int fd=new java.io.File("/proc/self/fd").list().length,threads=Thread.getAllStackTraces().size();
   org.json.JSONObject sample=new org.json.JSONObject().put("round",round+1).put("fd",fd).put("threads",threads).put("nativeBytes",android.os.Debug.getNativeHeapAllocatedSize()).put("heapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());samples.put(sample);
   check(fd<=baselineFd+3,"Soak descriptor growth: "+sample);check(threads<=baselineThreads+3,"Soak thread growth: "+sample);
   Bundle progress=new Bundle();progress.putString("stream","soak "+sample.toString()+"\n");sendStatus(0,progress);
  }
  org.json.JSONObject evidence=new org.json.JSONObject().put("rounds",soakRounds).put("h264PcmCycles",(soakRounds+1)*3).put("aacCycles",soakRounds+1).put("baselineFd",baselineFd).put("baselineThreads",baselineThreads).put("baselineNativeBytes",baselineNative).put("samples",samples);
  java.io.File file=new java.io.File(activity.getFilesDir(),"media-soak.json");writeBytes(file,evidence.toString().getBytes("UTF-8"));result.putString("stream","PASS: repeated real H264/PCM/AAC lifecycle soak; evidence media-soak.json\n");
 }
 void aacPlayback()throws Exception {
  final java.util.concurrent.CountDownLatch pcm=new java.util.concurrent.CountDownLatch(1);final java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<String>();
  LegacyMedia media=new LegacyMedia(activity.preview.getHolder().getSurface(),800,480,new kotlin.jvm.functions.Function1<String,kotlin.Unit>(){public kotlin.Unit invoke(String line){if(line.startsWith("audio_first_pcm_written"))pcm.countDown();if(line.startsWith("audio_failed"))failure.set(line);return kotlin.Unit.INSTANCE;}});
  try{com.shilapi.xcertplay.airplay.AudioStreamId id=new com.shilapi.xcertplay.airplay.AudioStreamId(100,"aac-test");com.shilapi.xcertplay.airplay.AudioFormat format=new com.shilapi.xcertplay.airplay.AudioFormat(com.shilapi.xcertplay.airplay.AudioCodecKind.AAC_LC,48000,1,96,"media");media.onAudioStarted(id,format,0);byte[] adts=asset("silence48k-mono.aac");int at=0,sample=0,frames=0;
   while(at<adts.length){check((adts[at]&255)==255,"Bad AAC fixture sync");int size=((adts[at+3]&3)<<11)|((adts[at+4]&255)<<3)|((adts[at+5]&255)>>5);int header=(adts[at+1]&1)==1?7:9;check(size>=header&&at+size<=adts.length,"Bad AAC fixture length");byte[] rtp=new byte[12+size-header];System.arraycopy(adts,at+header,rtp,12,size-header);media.onAudioRtp(id,format,rtp,sample);at+=size;sample+=1024;frames++;Thread.sleep(20);}
   check(frames>=40,"AAC fixture too short");check(pcm.await(3,java.util.concurrent.TimeUnit.SECONDS),"AAC never produced PCM: "+failure.get());check(failure.get()==null,"AAC decoder failed: "+failure.get());media.onAudioStopped(id);
  }finally{media.close();}check(!MediaWorkers.busy(),"AAC workers leaked after stop");
 }
 void surfaceRecreation()throws Exception {
  final java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger(),recoveries=new java.util.concurrent.atomic.AtomicInteger();final java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<String>();
  final LegacyMedia media=new LegacyMedia(activity.preview.getHolder().getSurface(),800,480,new kotlin.jvm.functions.Function1<String,kotlin.Unit>(){public kotlin.Unit invoke(String line){if(line.equals("video_surface_first_frame"))frames.incrementAndGet();if(line.startsWith("video_failed"))failure.set(line);return kotlin.Unit.INSTANCE;}});
  media.setVideoRecoveryHandler(110,new kotlin.jvm.functions.Function0<kotlin.Unit>(){public kotlin.Unit invoke(){recoveries.incrementAndGet();return kotlin.Unit.INSTANCE;}});
  try{
   media.onVideoConfig(110,asset("config.bin"));
   for(int round=0;round<4;round++){
    for(int n=0;n<4;n++){media.onVideoFrame(110,asset("frame"+n+".bin"));Thread.sleep(120);}
    for(int n=0;n<80&&frames.get()<round+1;n++)Thread.sleep(50);
    check(frames.get()==round+1,"Surface reattach did not render: "+failure.get());
    if(round==3)break;
    media.setSurface(null);runOnMainSync(new Runnable(){public void run(){activity.preview.setVisibility(View.GONE);}});waitForIdleSync();Thread.sleep(150);
    check(!activity.preview.getHolder().getSurface().isValid(),"Fixture surface was not destroyed");int before=recoveries.get();
    for(int n=0;n<30;n++)media.onVideoFrame(110,asset("frame1.bin"));Thread.sleep(200);check(recoveries.get()==before,"Background frames triggered recovery requests");
    runOnMainSync(new Runnable(){public void run(){activity.preview.setVisibility(View.VISIBLE);}});waitForIdleSync();for(int n=0;n<40&&!activity.preview.getHolder().getSurface().isValid();n++)Thread.sleep(50);
    media.setSurface(activity.preview.getHolder().getSurface());Thread.sleep(200);
   }
   check(failure.get()==null,"Surface loss failed session: "+failure.get());
  }finally{media.close();}check(!MediaWorkers.busy(),"Surface recreation leaked decoder");
 }
 void mediaLifecycle()throws Exception {
  for(int round=0;round<3;round++){
   check(!MediaWorkers.busy(),"Previous media workers still alive");
   final java.util.concurrent.CountDownLatch video=new java.util.concurrent.CountDownLatch(1),audio=new java.util.concurrent.CountDownLatch(1);
   final java.util.concurrent.atomic.AtomicInteger starts=new java.util.concurrent.atomic.AtomicInteger(),pcm=new java.util.concurrent.atomic.AtomicInteger();
   LegacyMedia media=new LegacyMedia(activity.preview.getHolder().getSurface(),800,480,new kotlin.jvm.functions.Function1<String,kotlin.Unit>(){public kotlin.Unit invoke(String line){if(line.startsWith("audio_first_pcm_written"))pcm.incrementAndGet();if(line.startsWith("video_decoder_started"))starts.incrementAndGet();if(line.equals("video_rendered_frames=1"))video.countDown();if(line.equals("audio_first_packet_written_or_queued"))audio.countDown();return kotlin.Unit.INSTANCE;}});
   try{
    for(int n=0;n<4;n++){media.onVideoConfig(110,asset("config.bin"));media.onVideoFrame(110,asset("frame"+n+".bin"));Thread.sleep(100);}
    check(video.await(10,java.util.concurrent.TimeUnit.SECONDS),"No H264 frame");check(starts.get()==1,"Repeated config restarted decoder");
    com.shilapi.xcertplay.airplay.AudioStreamId id=new com.shilapi.xcertplay.airplay.AudioStreamId(100,"selftest");
    com.shilapi.xcertplay.airplay.AudioFormat format=new com.shilapi.xcertplay.airplay.AudioFormat(com.shilapi.xcertplay.airplay.AudioCodecKind.LPCM,16000,1,96,"media");
    media.onAudioStarted(id,format,0);media.onAudioRtp(id,format,new byte[652],0);check(audio.await(5,java.util.concurrent.TimeUnit.SECONDS),"No PCM playback");
    com.shilapi.xcertplay.airplay.AudioStreamId next=new com.shilapi.xcertplay.airplay.AudioStreamId(100,"selftest",new Object());
    media.onAudioStarted(next,format,0);media.onAudioStopped(id);media.onAudioRtp(next,format,new byte[652],320);
    for(int n=0;n<50&&pcm.get()<2;n++)Thread.sleep(100);
    check(pcm.get()>=2,"Old session stop silenced new session PCM");media.onAudioStopped(next);
   }finally{media.close();}
   check(!MediaWorkers.busy(),"close returned before hardware workers released");
  }
 }
 Button findButton(View view,String text){if(view instanceof Button&&text.equals(((Button)view).getText().toString()))return (Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=findButton(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
