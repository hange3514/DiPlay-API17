package local.headunit.diplay

import android.app.*
import android.content.*
import android.hardware.usb.*
import android.media.AudioManager
import android.os.*
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*
import org.json.*

/** API17-owned wired lifecycle; no BYD vehicle writes or wireless startup path. */
class WiredService:Service() {
 override fun onBind(intent:Intent?):IBinder?=null
 companion object {
  private const val REENUMERATION_WAIT_MS=10000L
  private const val SAME_ENUMERATION_PROBE_MS=250L
  private const val AIRPLAY_CONTROL_PORT=7000
  @Volatile private var surface:Surface?=null
  private var surfaceOwner:Any?=null
  @JvmStatic @Synchronized fun attachSurface(owner:Any,value:Surface){if(surfaceOwner===owner&&surface===value)return;surfaceOwner=owner;surface=value;live?.sink?.setSurface(value);live?.sink?.routing?.foreground();live?.log("VIDEO_SURFACE","attached")}
  @JvmStatic @Synchronized fun detachSurface(owner:Any){if(surfaceOwner!==owner)return;surfaceOwner=null;surface=null;live?.sink?.setSurface(null);live?.log("VIDEO_SURFACE","detached; USB session retained")}
  @JvmStatic fun currentConnectionId():String=live?.let{if(!it.closed)it.connectionId else ""}.orEmpty()
  @JvmStatic fun renderedVideoFrames():Long=live?.sink?.renderedVideoFrames?:0L
  @JvmStatic fun currentFps():Int=live?.selectedFps?:0
  @JvmStatic fun isVideoReady():Boolean=live?.videoReady==true
  @Volatile private var live:WiredService?=null
  private val cleanupCount=java.util.concurrent.atomic.AtomicInteger()
  private val connectingWorkers=java.util.concurrent.atomic.AtomicInteger()
  @JvmStatic fun stop(context:Context){live?.shutdown();context.stopService(Intent(context,WiredService::class.java))}
  @JvmStatic fun isBusy():Boolean=live!=null||cleanupCount.get()>0||connectingWorkers.get()>0||MediaWorkers.busy()
  @JvmStatic fun hasUsbLink():Boolean=live?.usbLinked==true
  @JvmStatic fun awaitingPermission():Boolean=live?.usbPermissionPending==true
  @JvmStatic fun diagnosticState():JSONObject {
   val owner=live
   val result=JSONObject().put("servicePresent",owner!=null).put("cleanupWorkers",cleanupCount.get()).put("connectingWorkers",connectingWorkers.get()).put("mediaWorkers",MediaWorkers.activeCount())
   if(owner!=null){result.put("connectionId",owner.connectionId).put("closed",owner.closed).put("usbLinked",owner.usbLinked).put("videoReady",owner.videoReady).put("permissionPending",owner.usbPermissionPending).put("ageMs",SystemClock.elapsedRealtime()-owner.bornAt).put("controlSessions",owner.sessions.size).put("touchQueue",owner.input.queue.size).put("latestPerformance",JSONObject(owner.latestPerformance as Map<*,*>)).put("startupTimings",owner.timingReport())
    synchronized(owner.resourceLock){result.put("ownedResources",owner.handles.size)}
   }
   return result
  }
  @JvmStatic fun usbDiagnostic(message:String){live?.log("USB_NATIVE",message)}
  @JvmStatic fun consumeWheelDuplicate(key:Int):Boolean=live?.wheelControl?.duplicateAndroid(key)==true
  @JvmStatic fun sendWheelKey(key:Int):Boolean=sendWheelKey(key,"unknown")
  @JvmStatic fun sendWheelKey(key:Int,path:String):Boolean{
   val owner=live?:return false
   if(owner.closed){recordWheelEvent(owner,key,path,"service_closed",false);return false}
   val current=owner.session?.takeIf{owner.videoReady&&!it.isClosed}
   if(current==null){recordWheelEvent(owner,key,path,"no_active_session",false);return false}
   if(CarPlayMediaButton.opensSiri(key)){try{current.invokeSiri();recordWheelEvent(owner,key,path,"siri_invoked",true);owner.log("WHEEL_KEY","keyevent voice_key siri_invoked");return true}catch(t:Throwable){recordWheelEvent(owner,key,path,"siri_send_failed",false);throw t}}
   val index=CarPlayMediaButton.forKeyCode(key)?:run{recordWheelEvent(owner,key,path,"unmapped_key",false);return false}
   if(WheelKeyPolicy.androidAction(key)!=0&&owner.wheelControl?.acceptAndroidKey(key)==false){recordWheelEvent(owner,key,path,"duplicate_suppressed",true);owner.log("WHEEL_KEY","keyevent key=$key suppressed_duplicate");return true}
   val sent=current.sendMedia(index)
   recordWheelEvent(owner,key,path,if(sent)"report_written" else "report_write_failed",sent,index)
   owner.log("WHEEL_KEY","keyevent key=$key mediaIndex=$index sent=$sent")
   return sent
  }
  private fun recordWheelEvent(owner:WiredService,key:Int,path:String,decision:String,handled:Boolean,mediaIndex:Int=-1){try{val row=JSONObject().put("keyPath",path).put("keyCode",key).put("keyDecision",decision).put("keyHandled",handled).put("keyIsSiriCandidate",CarPlayMediaButton.opensSiri(key)||CarPlayMediaButton.opensSiriWhileCarPlay(key));if(mediaIndex>=0)row.put("mediaIndex",mediaIndex);MainActivity.record(owner,"WHEEL_KEY",row)}catch(_:Throwable){}}
  @JvmStatic fun adjustCarPlayVolume(direction:Int):Boolean{
   val owner=live?:return false
   if(owner.closed||!owner.videoReady)return false
   val audio=owner.getSystemService(Context.AUDIO_SERVICE) as AudioManager
   audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,direction,AudioManager.FLAG_SHOW_UI)
   owner.log("WHEEL_KEY","volume direction=$direction stream="+AudioManager.STREAM_MUSIC)
   return true
  }
  @JvmStatic fun touch(action:Int,x:Float,y:Float){val owner=live?:return;if(owner.closed)return;TouchDispatch.submit(owner.input,owner.touchQueue,action){if(!owner.closed)runCatching{owner.session?.sendTouch(listOf(AirPlayContact(0,x.toDouble().coerceIn(0.0,1.0),y.toDouble().coerceIn(0.0,1.0),action!=1&&action!=3)))}}}

 }
 private val work=Executors.newSingleThreadExecutor()
 private val touchQueue=LinkedBlockingDeque<Runnable>(16)
 private val input=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,touchQueue)
 @Volatile private var closed=false
 private var started=false
 @Volatile private var connectionId=""
 private val bornAt=SystemClock.elapsedRealtime()
 private val startup=ConnectionTimings(bornAt)
 private var requestStartedAt=bornAt
 private var selectedFps=15
 private var usbProfile=0
 private var usbBatchId=""
 private var vpnWaitMs=0L
 private fun timingReport()=JSONObject(startup.snapshot(SystemClock.elapsedRealtime())).put("requestedFps",selectedFps).put("usbStartupProfile",usbProfile).put("usbBatchId",usbBatchId).put("vpnWaitMs",vpnWaitMs).put("beforeServiceMs",(bornAt-requestStartedAt).coerceAtLeast(0))
 private fun reportStartup(outcome:String){try{MainActivity.record(this,"CONNECT_SUMMARY",timingReport().put("outcome",outcome).put("connection",connectionId))}catch(_:Exception){}}
 private val failed=java.util.concurrent.atomic.AtomicBoolean(false)
 private val rawAirPlayFailureCount=java.util.concurrent.atomic.AtomicInteger()
 private val resourceLock=Any()
 private val latestPerformance=ConcurrentHashMap<String,String>()
 @Volatile private var usbLinked=false
 @Volatile private var usbPermissionPending=false
 private var wheelControl:WheelControl?=null
 private var audioObserver:OemAudioObserver?=null
 @Volatile private var wiredStartWatchdog:WiredStartWatchdog?=null
 private lateinit var usb:UsbManager
 private lateinit var host:IphoneUsbHost
 private val handles=CopyOnWriteArrayList<Closeable>()
 private val resourceNames=IdentityHashMap<Closeable,String>()
 @Volatile private var userlandNetwork:UserlandNcmNetwork?=null
 @Volatile private var session:AirPlaySession?=null
 private var sessionGeneration=0L // protected by resourceLock; invalidates an older grace timer
 private val sessions=CopyOnWriteArrayList<AirPlaySession>()
 @Volatile private var server:ServerSocket?=null
 @Volatile private var sink:LegacyMedia?=null
 @Volatile private var authorization=CountDownLatch(0)
 @Volatile private var authorizationDenied=false
 @Volatile private var authorizationRequestedAt=0L
 @Volatile private var authorizationRequestPhase=""
 @Volatile private var authorizationRequestSequence=0L
 @Volatile private var videoReady=false
 @Volatile private var lastFailure:String?=null
 private val airplayTcpAccepted=java.util.concurrent.atomic.AtomicBoolean(false)
 private val watchdog=Executors.newSingleThreadScheduledExecutor()
 private val listener=object:AirPlaySessionListener {
  override fun onSessionActive(session:AirPlaySession){synchronized(resourceLock){
   if(closed||session.isClosed||!sessions.contains(session))return
   this@WiredService.session=session;sessionGeneration++;lastFailure=null;wiredStartWatchdog?.sessionActive();if(!videoReady)phase("first_video");note("AIRPLAY_ACTIVE","CarPlay协议会话已建立，等待画面和声音")
  }}
  override fun onSessionEnded(session:AirPlaySession){synchronized(resourceLock){
   val wasActive=this@WiredService.session===session;if(wasActive){this@WiredService.session=null;sessionGeneration++};val endedGeneration=sessionGeneration;sessions.remove(session)
   val error=session.protocolFailure
   if(error!=null&&!closed&&(wasActive||this@WiredService.session==null)){fail(IOException("protocol failure"),"airplay_session_protocol")}
   else if(!closed&&wasActive){note("AIRPLAY_ENDED","手机会话已结束");runCatching{watchdog.schedule({synchronized(resourceLock){if(!closed&&sessionGeneration==endedGeneration&&this@WiredService.session==null)fail(IOException("session ended"),"airplay_session_ended")}},2,TimeUnit.SECONDS)}}
  }}
  override fun onCommand(session:AirPlaySession,type:String,params:Map<String,Any?>){if(type!="modesChanged")return;synchronized(resourceLock){if(closed||!sessions.contains(session)||(this@WiredService.session!=null&&this@WiredService.session!==session))return;sink?.speechMode(params)}}
  override fun onDebugLog(message:String){log("AIRPLAY",message)}
  override fun onTransportError(message:String){fail(IOException(message),"airplay_transport")}
  override fun onProtocolFailure(session:AirPlaySession,evidence:AirPlayFailureEvidence){
   if(rawAirPlayFailureCount.incrementAndGet()>1)return
   try{MainActivity.record(this@WiredService,"AIRPLAY_FAILURE_RAW",JSONObject()
    .put("connection",connectionId).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt)
    .put("requestMethod",evidence.method).put("requestPath",evidence.path).put("requestPathTruncated",evidence.pathTruncated).put("requestProtocol",evidence.protocol)
    .put("requestHeadersJson",evidence.headersJson).put("requestHeadersTruncated",evidence.headersTruncated)
    .put("bodyEncoding","base64").put("requestBodyBase64",evidence.bodyBase64)
    .put("requestBodyBytes",evidence.bodyBytes).put("requestBodyCapturedBytes",evidence.bodyCapturedBytes)
    .put("requestBodyTruncated",evidence.bodyTruncated).put("errorRaw",evidence.errorRaw)
    .put("errorTruncated",evidence.errorTruncated).put("stackTraceRaw",evidence.stackTraceRaw)
    .put("stackTraceTruncated",evidence.stackTraceTruncated).put("peerName",evidence.peerName)
    .put("peerDeviceId",evidence.peerDeviceId).put("peerWifiMac",evidence.peerWifiMac)
    .put("peerModel",evidence.peerModel).put("peerOsVersion",evidence.peerOsVersion)
    .put("peerSourceVersion",evidence.peerSourceVersion))
   }catch(error:Throwable){log("AIRPLAY","failure evidence persistence failed type="+error.javaClass.simpleName)}
  }
 }
 fun log(stage:String,text:String){if(text.startsWith("TRACE ")||stage=="CONTROL"&&text.startsWith("iap2 rx=")&&text.substringAfter("0x","").toIntOrNull(16)?.let{DiagnosticPolicy.skipFrame(it)}==true)return
  val metric=when {stage=="MEDIA"&&(text.startsWith("video_progress ")||text.startsWith("audio_progress ")||text.startsWith("audio_checkpoint "))->(if(text.startsWith("audio_checkpoint "))"audio_progress" else text.substringBefore(' '))+if(text.startsWith("audio_progress ")||text.startsWith("audio_checkpoint ")) ":"+text.substringAfter("role=").substringBefore(' ') else "";stage=="AIRPLAY"&&text.startsWith("audio_modes ")->"audio_modes";stage=="MEDIA"&&text.startsWith("microphone_")->"microphone";stage=="AIRPLAY"&&text.startsWith("Receive:")->text.substringBefore(" packets=").take(80);else->null}
  if(metric!=null&&(latestPerformance.containsKey(metric)||latestPerformance.size<12))latestPerformance[metric]="ageMs=${SystemClock.elapsedRealtime()-bornAt} "+text.take(1200)
  try{MainActivity.record(this,stage,JSONObject().put("detail",text.take(1200)).put("connection",connectionId).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}}
 fun note(stage:String,text:String){if(stage=="FAILED")lastFailure=text;log(stage,text);sendBroadcast(Intent("local.headunit.diplay.STATUS").setPackage(packageName).putExtra("message",text).putExtra("stage",stage).putExtra("connection_id",connectionId))}
 private fun safeDiagnosticType(t:Throwable):String { val value=t.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"),"").take(48);return value.ifEmpty{"Throwable"} }
 private fun recordFailure(e:Throwable,origin:String){try{var root=e;var count=0;while(root.cause!=null&&root.cause!==root&&count++<8)root=root.cause!!;MainActivity.record(this,"CONNECTION_FAILURE",JSONObject().put("origin",origin).put("phase",activeDiagnosticStage).put("errorType",safeDiagnosticType(e)).put("causeType",safeDiagnosticType(root)).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt).put("videoReady",videoReady).put("usbLinked",usbLinked).put("sessionCount",sessions.size).put("activeSession",session!=null).put("userlandNetworkOpen",userlandNetwork!=null).put("networkBackend","lwip_userland_ncm"))}catch(_:Throwable){}}
 private fun fail(e:Throwable,origin:String="connect_worker"){if(!closed&&failed.compareAndSet(false,true)){recordFailure(e,origin);var cause:Throwable?=e;val chain=ArrayList<String>();while(cause!=null&&chain.size<4){chain.add(cause.javaClass.simpleName+": "+(cause.message?:"connection failed").take(160));cause=cause.cause};note("FAILED",chain.joinToString(" → "));shutdown()}}
 override fun onCreate(){super.onCreate();live=this;audioObserver=OemAudioObserver(this);wheelControl=WheelControl(this,object:WheelControl.Sink {override fun session():Any?=this@WiredService.session?.takeIf{!closed&&videoReady&&!it.isClosed};override fun send(token:Any,action:Int):Boolean {val current=this@WiredService.session;if(closed||current==null||current!==token||current.isClosed)return false;return when(action){1->current.sendMedia(5);2->current.sendMedia(4);3->current.sendTelephony(1);4->current.sendTelephony(3);5->{current.invokeSiri();true};else->false}}});usb=getSystemService(Context.USB_SERVICE) as UsbManager;host=IphoneUsbHost(this,usb,IphoneUsbMatcher.appleVendor(),onDiagnostic={log("USB",it)});handles.add(host.registerPermissionReceiver{result->authorizationDenied=result is IphoneUsbHost.PermissionResult.Denied;val device=when(result){is IphoneUsbHost.PermissionResult.Granted->result.device;is IphoneUsbHost.PermissionResult.Denied->result.device};val requestedAt=authorizationRequestedAt;val requestPhase=authorizationRequestPhase;val requestSequence=authorizationRequestSequence;log("USB_PERMISSION_RESULT","sequence=$requestSequence phase=$requestPhase granted=${!authorizationDenied} device=${device.deviceName} vendor=${device.vendorId} product=${device.productId} elapsedMs=${if(requestedAt>0)SystemClock.elapsedRealtime()-requestedAt else -1}");authorization.countDown()});registerMediaButtonOwner()}
 private fun registerMediaButtonOwner(){try{(getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerMediaButtonEventReceiver(ComponentName(this,MediaButtonWheelReceiver::class.java));log("WHEEL_KEY","media_button_owner_registered")}catch(t:Throwable){log("WHEEL_KEY","media_button_owner_failed="+t.javaClass.simpleName)}}
 private fun unregisterMediaButtonOwner(){try{(getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterMediaButtonEventReceiver(ComponentName(this,MediaButtonWheelReceiver::class.java))}catch(_:Throwable){}}
 override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
  if(started)return START_NOT_STICKY;usbBatchId=i?.getStringExtra("usb_batch_id").orEmpty().take(80);usbProfile=if(usbBatchId.isEmpty())UsbStartupProfile.DEFAULT else UsbStartupProfile.valid(i?.getIntExtra("usb_startup_profile",0)?:0);requestStartedAt=(i?.getLongExtra("request_started_at",bornAt)?:bornAt).takeIf{it in 1..bornAt}?:bornAt;vpnWaitMs=(i?.getLongExtra("vpn_wait_ms",0)?:0).coerceIn(0,(bornAt-requestStartedAt).coerceAtLeast(0));connectionId=i?.getStringExtra("connection_id").orEmpty();if(cleanupCount.get()>0||connectingWorkers.get()>0||MediaWorkers.busy()){note("FAILED","上一轮连接正在释放，请稍后重试");shutdown();return START_NOT_STICKY};started=true;CrashEvidence.begin(this,connectionId);selectedFps=PlaybackOptions.read(this);log("VIDEO_CONFIG","requestedFps=$selectedFps width=${ProjectionConfig.WIDTH} height=${ProjectionConfig.HEIGHT}");log("CONNECT_BUILD",runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrDefault("unknown"));note("CONNECT_START","正在建立CarPlay连接");log("CONNECTION_PREWARM","at=service_start identityReady=${ConnectionPrewarm.identityReady()} nativeReady=${ConnectionPrewarm.nativeReady()} nativeStatus=${ConnectionPrewarm.nativePrewarmStatus()} mfiReady=${VerifiedMfiCache.ready()}")
  watchdog.schedule({if(!closed&&!videoReady&&sink?.receivedVideo!=true)fail(IOException("startup timeout"),"startup_watchdog")},180,TimeUnit.SECONDS)
  val notification=Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("DiPlay CarPlay").setContentText("正在连接，可在应用内停止").build();startForeground(1702,notification)
  try{work.execute{synchronized(resourceLock){if(closed)return@execute;connectingWorkers.incrementAndGet()};try{connect()}catch(e:Throwable){fail(e,"connect_worker")}finally{connectingWorkers.decrementAndGet()}}}catch(e:Throwable){fail(e)};return START_NOT_STICKY
 }
 private fun check(){if(closed)throw InterruptedException("Connection cancelled")}
 private fun own(c:Closeable,name:String=c.javaClass.simpleName){synchronized(resourceLock){if(!closed){handles.add(c);resourceNames[c]=name;return}};c.close();throw InterruptedException("Connection cancelled")}
 private fun selectPhone():UsbDevice {
  val startedAt=SystemClock.elapsedRealtime();val until=startedAt+15000;var waiting=false
  while(true){check();val phones=host.discover();require(phones.size<=1){"检测到多台Apple USB设备，请只连接一台iPhone"};if(phones.size==1)return phones.single()
   if(!waiting){waiting=true;UsbPresenceLog.capture(this,"connect_wait");note("WAIT_USB","等待车机识别iPhone USB；请确认手机已解锁并插好数据线")}
   if(SystemClock.elapsedRealtime()>=until){UsbPresenceLog.capture(this,"connect_timeout");throw IOException("车机系统仍未列出iPhone USB设备。请拔线等待几秒后重新插入此前成功的接口；仍失败请提交日志")}
   // USB_DEVICE_ATTACHED can arrive just before UsbManager publishes the new device.
   // Probe quickly during that short race, then back off to avoid polling for the full timeout.
   val elapsed=SystemClock.elapsedRealtime()-startedAt
   Thread.sleep(if(elapsed<1000)50 else 250)
  }
 }
 private fun currentAppleDevice(expectedVendorId:Int):UsbDevice? {
  val devices=host.discover()
  require(devices.size<=1){"Multiple Apple USB devices detected during USB authorization"}
  return devices.singleOrNull()?.takeIf{it.vendorId==expectedVendorId}
 }
 private fun authorize(d:UsbDevice):UsbDevice {
  val reservationDeadline=SystemClock.elapsedRealtime()+TimeUnit.SECONDS.toMillis(45)
  var current=currentAppleDevice(d.vendorId)
  while(current==null){
   check();current=currentAppleDevice(d.vendorId)
   if(current!=null)break
   if(SystemClock.elapsedRealtime()>=reservationDeadline)throw IOException("iPhone USB device disappeared before permission could be checked")
   Thread.sleep(50)
  }
  var target:UsbDevice=current?:throw IOException("iPhone USB device disappeared before permission could be checked")
  if(usb.hasPermission(target)){log("USB_PERMISSION_STATE","state=already_granted phase=$activeDiagnosticStage device=${target.deviceName} vendor=${target.vendorId} product=${target.productId}");return target}
  var ownsPermissionFlow=false
  while(!ownsPermissionFlow){
   check()
   current=currentAppleDevice(d.vendorId)
   if(current==null){if(SystemClock.elapsedRealtime()>=reservationDeadline)throw IOException("iPhone USB device disappeared while waiting for permission ownership");Thread.sleep(50);continue}
   target=current
   if(usb.hasPermission(target)){log("USB_PERMISSION_STATE","state=granted_while_waiting phase=$activeDiagnosticStage device=${target.deviceName}");return target}
   ownsPermissionFlow=UsbAttachActivity.beginPermissionFlow()
   if(!ownsPermissionFlow){if(UsbAttachActivity.consumeRecentActivityPermissionDenial())throw IOException("USB permission was denied in the system prompt");if(SystemClock.elapsedRealtime()>=reservationDeadline)throw IOException("USB permission window remained busy");Thread.sleep(100)}
  }
  authorization=CountDownLatch(1);authorizationDenied=false;usbPermissionPending=true;authorizationRequestedAt=SystemClock.elapsedRealtime();authorizationRequestPhase=activeDiagnosticStage;authorizationRequestSequence++
  try{val requestedDevice=target;val request=host.requestPermission(requestedDevice);val requestType=if(request is IphoneUsbHost.PermissionRequest.AlreadyGranted)"already_granted" else "requested";log("USB_PERMISSION_REQUEST","sequence=$authorizationRequestSequence phase=$authorizationRequestPhase result=$requestType device=${requestedDevice.deviceName} vendor=${requestedDevice.vendorId} product=${requestedDevice.productId}");note("USB_PERMISSION","\u8bf7\u5141\u8bb8\u8f66\u673a USB \u8bbf\u95ee\uff1b\u5982\u679c\u624b\u673a\u63d0\u793a\u4fe1\u4efb\uff0c\u4e5f\u8bf7\u9009\u62e9\u4fe1\u4efb")
   if(request is IphoneUsbHost.PermissionRequest.AlreadyGranted)return request.device
   while(true){
    check()
    current=currentAppleDevice(d.vendorId)
    if(current!=null){target=current;if(usb.hasPermission(target)){log("USB_PERMISSION_WAIT","sequence=$authorizationRequestSequence phase=$authorizationRequestPhase result=granted elapsedMs=${SystemClock.elapsedRealtime()-authorizationRequestedAt} reenumerated=${target.deviceName!=requestedDevice.deviceName}");return target}}
    if(authorizationDenied){AutoUsbService.pause(this);throw IOException("USB permission was denied; automatic retry paused")}
    if(SystemClock.elapsedRealtime()>=reservationDeadline){log("USB_PERMISSION_WAIT","sequence=$authorizationRequestSequence phase=$authorizationRequestPhase result=timeout elapsedMs=${SystemClock.elapsedRealtime()-authorizationRequestedAt}");throw IOException("USB authorization timed out")}
    authorization.await(100,TimeUnit.MILLISECONDS)
   }
  }finally{usbPermissionPending=false;authorizationRequestedAt=0L;UsbAttachActivity.endPermissionFlow(false)}
 }

 private var phaseAt=SystemClock.elapsedRealtime()
 private var previousPhase="start"
 @Volatile private var activeDiagnosticStage="service_start"
 @Synchronized private fun phase(name:String){val now=SystemClock.elapsedRealtime();activeDiagnosticStage=name;CrashEvidence.updateStage(this,connectionId,name,usbLinked,videoReady);startup.advance(name,now);log("CONNECT_TIMING","completed=$previousPhase durationMs=${now-phaseAt} next=$name");phaseAt=now;previousPhase=name}
 private fun readCarPlayConfiguration(d:UsbDevice):Pair<UsbDevice,LegacyConfiguration?> {
  val authorized=authorize(d)
  val c=usb.openDevice(authorized)?:return Pair(authorized,null)
  return try { LegacyUsb.read(authorized,c); Pair(authorized,IphoneCarPlayConfiguration.find(authorized)) } finally { c.close() }
 }
 private fun selectCarPlayConfiguration(initial:UsbDevice):Pair<UsbDevice,LegacyConfiguration> {
  var phone=initial
  var config=IphoneCarPlayConfiguration.find(phone)
  val sessionAlreadyStarted=WiredSessionMemory.startedOn(phone.deviceName)
  var transitionsTried=0
  val maxTransitions=2
  while(true) {
   val carPlayMode=config?.let { IphoneCarPlayConfiguration.isCarPlayMode(it) }==true
   val action=WiredConfigurationAction.choose(config!=null,carPlayMode,transitionsTried,maxTransitions,sessionAlreadyStarted)
   log("USB_MODE","device=${phone.deviceName} config=${config?.id?:"none"} appleEthernet=$carPlayMode transitionCount=$transitionsTried previousSession=$sessionAlreadyStarted action=${action.diagnostic}")
   when(action) {
    WiredConfigurationAction.USE -> return Pair(phone,config!!)
    WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET -> {
     log("USB_MODE","using USBMUX+NCM fallback after CarPlay-mode transition was attempted config=${config?.id}")
     return Pair(phone,config!!)
    }
    WiredConfigurationAction.REJECT -> throw IOException("iPhone exposes no USBMUX+NCM configuration after bounded CarPlay-mode requests")
    WiredConfigurationAction.REQUEST_TRANSITION -> {
     transitionsTried++
     phase("usb_reenumeration")
     note("REENUMERATION","\u6b63\u5728\u8bf7\u6c42iPhone\u5207\u6362CarPlay USB\u6a21\u5f0f")
     val transitionStarted=SystemClock.elapsedRealtime()
     var transition:IphoneUsbHost.TransitionResult?=null
     host.requestCarPlayReenumerationAsync(phone,Executor{it.run()}){transition=it}
     val outcome=transition?:throw IOException("CarPlay USB-mode request returned no result")
     if(outcome is IphoneUsbHost.TransitionResult.Failed) {
      val canFallback=WiredConfigurationAction.fallbackAfterRejection(config!=null,carPlayMode,sessionAlreadyStarted)
      log("USB_MODE","transition rejected elapsedMs=${SystemClock.elapsedRealtime()-transitionStarted} fallback=$canFallback error=${outcome.error.message?.take(240)}")
      if(canFallback)return Pair(phone,config!!)
      throw outcome.error
     }
     log("USB_MODE","transition requested elapsedMs=${SystemClock.elapsedRealtime()-transitionStarted} waitLimitMs=$REENUMERATION_WAIT_MS")
     val waitStarted=SystemClock.elapsedRealtime()
     val deadline=waitStarted+REENUMERATION_WAIT_MS
     var sawDetach=false
     var sameDeviceProbeDone=false
     var inspectedDeviceName:String?=null
     var lastCandidate:UsbDevice?=null
     var lastCandidateConfig:LegacyConfiguration?=null
     while(SystemClock.elapsedRealtime()<deadline) {
      check()
      val devices=host.discover()
      require(devices.size<=1){"Multiple Apple USB devices detected during CarPlay-mode transition"}
      val next=devices.singleOrNull()
      if(next==null) {
       sawDetach=true
       Thread.sleep(50)
       continue
      }
      val changedEnumeration=next.deviceName!=phone.deviceName||sawDetach
      val elapsed=SystemClock.elapsedRealtime()-waitStarted
      val shouldRead=inspectedDeviceName!=next.deviceName||sawDetach||(!sameDeviceProbeDone&&elapsed>=SAME_ENUMERATION_PROBE_MS)
      if(shouldRead) {
      val inspected=readCarPlayConfiguration(next)
      val inspectedDevice=inspected.first
      val nextConfig=inspected.second
       sameDeviceProbeDone=true
       inspectedDeviceName=next.deviceName
       sawDetach=false
       lastCandidate=inspectedDevice
       lastCandidateConfig=nextConfig
       val nextCarPlayMode=nextConfig?.let{IphoneCarPlayConfiguration.isCarPlayMode(it)}==true
       log("USB_MODE","reenumerated=$changedEnumeration device=${next.deviceName} config=${nextConfig?.id?:"none"} appleEthernet=$nextCarPlayMode elapsedMs=$elapsed")
       if(nextConfig!=null&&nextCarPlayMode)return Pair(inspectedDevice,nextConfig)
       if(nextConfig!=null&&changedEnumeration) {
        log("USB_MODE","new enumeration did not expose Apple Ethernet; using fallback after one mode request")
        return Pair(inspectedDevice,nextConfig)
       }
      }
      Thread.sleep(50)
     }
     if(lastCandidate!=null&&lastCandidateConfig!=null) {
      log("USB_MODE","no mode re-enumeration within wait limit; falling back to available config=${lastCandidateConfig.id}")
      return Pair(lastCandidate,lastCandidateConfig)
     }
     val stillPresent=host.discover().singleOrNull()
     if(config!=null&&stillPresent!=null) {
      log("USB_MODE","transition wait ended without replacement descriptors; reusing config=${config.id}")
      return Pair(phone,config)
     }
     if(transitionsTried<maxTransitions&&stillPresent!=null) {
      phone=stillPresent
      config=lastCandidateConfig
      log("USB_MODE","retrying bounded transition count=$transitionsTried")
      continue
     }
     throw IOException("iPhone did not expose USBMUX+NCM after CarPlay-mode transition waitMs=${SystemClock.elapsedRealtime()-waitStarted}")
    }
   }
  }
 }
 private fun connect(){
  phase("usb_discovery")
  val discovered=selectPhone();UsbPresenceLog.recordTransition(this,"selected",discovered);UsbPresenceLog.capture(this,"selected_phone");phase("usb_permission");val d=authorize(discovered);check();phase("descriptors")
  val initial=usb.openDevice(d)?:throw IOException("无法打开iPhone USB")
  val configs=try{LegacyUsb.read(d,initial)}finally{initial.close()}
  log("DESCRIPTORS",configs.joinToString { "config=${it.id}:"+IphoneCarPlayConfiguration.describe(it) })
  val selected=selectCarPlayConfiguration(d)
  val phone=selected.first
  phase("configuration_takeover");check();val config=selected.second
  note("USB_TAKEOVER","正在检查iPhone USB占用并选择CarPlay配置")
  val leaseConnection=usb.openDevice(phone)?:throw IOException("无法打开USB配置连接")
  val lease=try{UsbConfigurationLease(leaseConnection,LegacyUsb.configurations(phone),config.id,onDiagnostic={log("USB_NATIVE",it)})}catch(e:Throwable){leaseConnection.close();throw e};own(lease,"usb_configuration");lease.prepare();check();phase("usbmux_open")
  var us:Iap2UsbSession?=null
  host.openIap2UsbSessionAsync(phone,Executor{it.run()}){when(it){is IphoneUsbHost.Iap2SessionResult.Connected->us=it.session;is IphoneUsbHost.Iap2SessionResult.Failed->throw it.error}}
  val usbSession=us?:throw IOException("USBMUX打开失败");own(usbSession,"usbmux_usb")
  phase("ncm_open");val fn=NcmFunctionDiscovery.find(config)?:throw IOException("NCM功能缺失")
  val nc=usb.openDevice(phone)?:throw IOException("NCM USB打开失败")
  val ncm=try{NcmUsbBridge.open(nc,fn,profile=usbProfile,probe=usbBatchId.isNotEmpty(),onDiagnostic={log("NCM",it)})}catch(e:Throwable){nc.close();throw e};own(ncm,"ncm_usb");ncm.startEarlyReceive();usbLinked=true
  phase("userland_ipv6_stack");log("NETWORK_BACKEND","backend=lwip_userland_ncm vpnUsed=false ipv4=false ipv6=true tcp=true udp=true mtu=1500 hostMacAvailable=${ncm.hostMac!=null}");val nativeReadyBeforeUse=ConnectionPrewarm.nativeReady();val nativeCrashFile=java.io.File(filesDir,"native-crash.txt");val nativeCapture=NativeIpStack.installCrashCapture(nativeCrashFile.absolutePath);NativeIpStack.setCrashStage("lwip_create_before");log("CONNECTION_PREWARM","at=native_before_create readyBeforeUse=$nativeReadyBeforeUse nativeStatus=${ConnectionPrewarm.nativePrewarmStatus()}");log("USERLAND_NETWORK","native crash capture armed=$nativeCapture")
  val mac=ncm.hostMac?:byteArrayOf(2,0,0,0,0,17);val macText=mac.joinToString(":"){"%02x".format(it.toInt() and 255)}
  val network=UserlandNcmNetwork(ncm,mac,{line->log(if(line.startsWith("NCM_OUT "))"NCM_WRITE_TIMING" else "USERLAND_NETWORK",line)},trackedTcpSynPort=AIRPLAY_CONTROL_PORT){fail(it,"userland_network")};own(network,"lwip_userland_network");val nativeStackStartAt=SystemClock.elapsedRealtime();var nativeStackStartResult="ready";try{network.start()}catch(error:Throwable){nativeStackStartResult="failed_${error.javaClass.simpleName.take(40)}";throw error}finally{log("CONNECTION_PREWARM","at=native_start_complete readyBeforeUse=$nativeReadyBeforeUse networkStartDurationMs=${SystemClock.elapsedRealtime()-nativeStackStartAt} result=$nativeStackStartResult")};if(usbProfile==UsbStartupProfile.DEFAULT)network.startNeighborWarmup();userlandNetwork=network
  phase("usbmux_handshake");val mux=Iap2UsbMuxHost.open(usbSession,onDiagnostic={line->log(if(line.startsWith("USBMUX_OUT "))"USBMUX_WRITE_TIMING" else "MUX",line)});own(mux,"usbmux_host")
  phase("lockdown_pairing")
  val identityReadyBeforeUse=ConnectionPrewarm.identityReady();val identityCallAt=SystemClock.elapsedRealtime();val store=ConnectionPrewarm.identityStore(this);log("CONNECTION_PREWARM","at=identity_use readyBeforeUse=$identityReadyBeforeUse waitMs=${SystemClock.elapsedRealtime()-identityCallAt}");val savedPair=store.loadPair();log("LOCKDOWN","saved_pair_present=${savedPair!=null} profile=2");note("PAIRING",if(savedPair!=null)"正在使用已保存的 iPhone 配对信息" else "首次配对，请保持 iPhone 解锁并确认信任");var pair=savedPair?:LockdownPairingClient(mux,onDiagnostic={log("LOCKDOWN",it)}).pair("DiPlay API17",store.hostId,store.buid,90000,isCancelled={closed}).pairRecord.also{store.savePair(it)}
  phase("carkit_tls");check();val carkit=try{LockdownCarKitClient(mux).open(pair,"DiPlay API17")}catch(e:Exception){var cause:Throwable?=e;var rejected=false;while(cause!=null){val m=cause.message.orEmpty();if(m.contains("InvalidPairRecord",true)||m.contains("InvalidHostID",true))rejected=true;cause=cause.cause};if(!rejected)throw e;store.clearPair();note("PAIRING_RETRY","保存的配对已失效，请在iPhone上重新确认信任");pair=LockdownPairingClient(mux,onDiagnostic={log("LOCKDOWN",it)}).pair("DiPlay API17",store.hostId,store.buid,90000,isCancelled={closed}).pairRecord.also{store.savePair(it)};LockdownCarKitClient(mux).open(pair,"DiPlay API17")};own(carkit,"carkit")
  phase("iap2_setup");note("CARKIT","iPhone CarKit通道已打开，正在建立协议会话")
  val csm=Iap2Session.open(carkit,traceContext="api17",onTrace={log("IAP2",it)});own(csm,"iap2")
  phase("mfi_identity");val auth=VerifiedMfiCache.get(this){log("MFI","challenge signed bytes=$it")}
  val identity=store.identity()
  // API17 has no per-app VPN routing. Only the USB link-local /64 is routed; no default/DNS routes.
  phase("airplay_listener");val configAir=AirPlayConfig("DiPlay API17",macText,macText,"320.20",AirPlayDisplayConfig(ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,fps=selectedFps),microphone=true)
  val renderer=LegacyMedia(null,ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,{log("MEDIA",it);if(!closed&&!videoReady&&it=="video_rendered_frames=1"){videoReady=true;activeDiagnosticStage="video_active";CrashEvidence.updateStage(this@WiredService,connectionId,"video_active",usbLinked,true);startup.firstFrame(SystemClock.elapsedRealtime());reportStartup("first_frame");note("VIDEO_READY","已收到并解码CarPlay画面；按返回键显示控制面板")}});renderer.onVideoFailure={fail(IOException("video decode failure",it),"video_decoder")};renderer.routing=AudioRouting(this,renderer);own(renderer,"media");synchronized(Companion){sink=renderer;renderer.setSurface(surface)}
  val media=CarPlayMediaEngine(renderer,true);val pairings=store.pairings()
  val srv=LegacySockets.tcp(InetAddress.getByName("fe80::2"),AIRPLAY_CONTROL_PORT);own(Closeable{srv.close()},"airplay_listener");server=srv;log("NETWORK_LISTENER","service=airplay_control family=ipv6 localPort=${srv.localPort} backend=${if(LegacySockets.isUserlandActive())"lwip" else "android"}")
  Thread({try{while(!closed){val socket=srv.accept();airplayTcpAccepted.set(true);log("AIRPLAY_ACCEPT","control TCP accepted");if(!videoReady){phase("airplay_handshake");note("AIRPLAY_HANDSHAKE","投屏通道已连通，正在建立音视频会话")};try{socket.tcpNoDelay=true;socket.keepAlive=true;if(closed){socket.close();break};if(sessions.size>=4){socket.close();continue};val s=AirPlaySession(socket,configAir,identity,pairings,auth,listener,media);val accepted=synchronized(resourceLock){if(closed)false else{sessions.add(s);s.start();true}};if(!accepted)s.close()}catch(e:Throwable){runCatching{socket.close()};throw e}}}catch(e:Throwable){if(!closed)fail(e,"airplay_accept")}},"legacy-airplay").start()
  phase("iap2_auth_and_phone_start");note("IAP2_AUTH","正在进行CarPlay认证；成功后手机应显示CarPlay画面")
  val endpoint=Iap2WiredCarPlayEndpoint(listOf("fe80::2"),srv.localPort,identity.publicKeyHex,configAir.sourceVersion,macText)
  val ident=Iap2IdentificationConfig("DiPlay API17","SongDM-MX9A0","Independent",store.hostId,"0.2","API17",IphoneCarPlayConfiguration.usbMuxInterface(config)!!.id,language="zh")
  val firstStartSentAt=java.util.concurrent.atomic.AtomicLong(0L)
  val firstStartFlushedAt=java.util.concurrent.atomic.AtomicLong(0L)
  val startSessionQueueAttempts=java.util.concurrent.atomic.AtomicInteger(0)
  val tcpSynBaseline=java.util.concurrent.atomic.AtomicLong(-1L)
  var lastRetryState=""
  val retryDecision={
   val startFlushedAt=firstStartFlushedAt.get()
   val tcpEvidence=network.airPlayTcpEvidence()
   val tcpNow=tcpEvidence?.synCount?:-1L
   val tcpBaseline=tcpSynBaseline.get()
   val ncmOutAccepted=ncm.firstNcmBulkOutAcceptedAtNanos
   val ncmNetworkReady=ncm.ncmNetworkReadyAtNanos
   val lastServiceDiscoveryAt=tcpEvidence?.lastServiceDiscoveryAtNanos
   val serviceDiscoverySinceStart=startFlushedAt!=0L&&lastServiceDiscoveryAt!=null&&lastServiceDiscoveryAt-startFlushedAt>=0L
   val active=airplayTcpAccepted.get()||session!=null||videoReady
   val reliableTcpStats=tcpBaseline>=0&&tcpNow>=tcpBaseline
   // A SYN before NCM bulk-OUT accepts a frame cannot prove the head unit could
   // answer it. Count it as progress only if it followed both this request and
   // the first accepted NCM OUT frame. This covers a slow USBMUX flush without
   // mistaking an early, unanswered SYN for a completed network attempt.
   val requestStartedAt=firstStartSentAt.get()
   val sawTcpSinceStart=reliableTcpStats&&WiredStartAgain.connectionAttemptAfterStart(requestStartedAt,tcpEvidence?.lastSynAtNanos)
   val sawIpv4TcpSinceStart=WiredStartAgain.connectionAttemptAfterStart(requestStartedAt,tcpEvidence?.lastIpv4SynAtNanos)
   val firstStartFlushedAtNanos=firstStartFlushedAt.get().takeIf{it>0L}
   val sawTcpAfterReady=reliableTcpStats&&WiredStartAgain.connectionAttemptAfterReady(firstStartFlushedAtNanos,ncmNetworkReady,tcpEvidence?.lastSynAtNanos)
   val sawIpv4TcpAfterReady=WiredStartAgain.connectionAttemptAfterReady(firstStartFlushedAtNanos,ncmNetworkReady,tcpEvidence?.lastIpv4SynAtNanos)
   val sawAnyTcpAfterReady=WiredStartAgain.anyAddressFamilyAttemptAfterReady(
    firstStartFlushedAtNanos,ncmNetworkReady,
    tcpEvidence?.lastSynAtNanos,tcpEvidence?.lastIpv4SynAtNanos,
   )
   val now=System.nanoTime()
   val decision=if(startFlushedAt==0L||!reliableTcpStats)Iap2WiredControlClient.StartAgain.DONE else WiredStartAgain.decide(
    now,startFlushedAt,ncmNetworkReady,active,sawAnyTcpAfterReady,
   )
   val recentServiceDiscovery=serviceDiscoverySinceStart&&lastServiceDiscoveryAt!=null&&now-lastServiceDiscoveryAt in 0 until WiredStartAgain.QUIET_MILLIS*1_000_000L
   val stateKey=decision.name+":"+(ncmNetworkReady!=null)+":"+sawTcpSinceStart+":"+sawTcpAfterReady+":"+sawIpv4TcpSinceStart+":"+sawIpv4TcpAfterReady+":"+sawAnyTcpAfterReady+":"+serviceDiscoverySinceStart+":"+recentServiceDiscovery+":"+active+":"+reliableTcpStats
   if(stateKey!=lastRetryState){lastRetryState=stateKey;log("START_SESSION_RECOVERY","decision=${decision.name} firstNcmBulkOutAcceptedAgoMs=${ncmOutAccepted?.let{((now-it)/1_000_000).coerceAtLeast(0)}?:-1} ncmNetworkReadyAgoMs=${ncmNetworkReady?.let{((now-it)/1_000_000).coerceAtLeast(0)}?:-1} tcpSynToPort=$AIRPLAY_CONTROL_PORT startFlushedAgoMs=${firstStartFlushedAtNanos?.let{((now-it)/1_000_000).coerceAtLeast(0)}?:-1} tcpSynBaseline=$tcpBaseline tcpSynCurrent=$tcpNow lastTcpSynAgeMs=${tcpEvidence?.lastSynAgeMs?:-1} lastIpv4TcpSynAgeMs=${tcpEvidence?.lastIpv4SynAgeMs?:-1} tcpSynSinceStart=$sawTcpSinceStart tcpSynAfterStartFlushAndNetworkReady=$sawTcpAfterReady ipv4TcpSynSinceStart=$sawIpv4TcpSinceStart ipv4TcpSynAfterStartFlushAndNetworkReady=$sawIpv4TcpAfterReady anyTcpSynAfterStartFlushAndNetworkReady=$sawAnyTcpAfterReady lastRxMdnsServiceAgeMs=${tcpEvidence?.lastServiceDiscoveryAtNanos?.let{((now-it)/1_000_000).coerceAtLeast(0)}?:-1} lastRxMdnsServiceMask=${tcpEvidence?.lastServiceDiscoveryMask?:0} lastRxMdnsServiceQueryMask=${tcpEvidence?.lastServiceDiscoveryQueryMask?:0} lastRxMdnsServiceResponseMask=${tcpEvidence?.lastServiceDiscoveryResponseMask?:0} serviceDiscoverySinceStart=$serviceDiscoverySinceStart serviceDiscoveryQuiet=$recentServiceDiscovery airplayAccepted=${airplayTcpAccepted.get()} sessionActive=$active statsReliable=$reliableTcpStats")}
   decision
  }
  val startWatchdog=WiredStartWatchdog(
   schedule={delay,action->
    val future=watchdog.schedule({action()},delay,TimeUnit.MILLISECONDS)
    val cancel:()->Unit={future.cancel(false);Unit}
    cancel
   },
   onTimeout={if(!closed&&session==null)fail(IOException("iPhone did not open the wired CarPlay session in time"),"wired_start_watchdog")},
   secondRequestCanStillGoOut={retryDecision()!=Iap2WiredControlClient.StartAgain.DONE},
   onHold={log("START_SESSION_RECOVERY","startup timeout extended to ${WiredStartWatchdog.HELD_TIMEOUT_MILLIS}ms while one justified StartSession retry remains possible")},
  )
  wiredStartWatchdog=startWatchdog
  val controlResult=Iap2WiredControlClient(csm,Iap2MfiAuthenticationClient(auth)).run(ident,endpoint,availableCurrentMilliAmps=500,timeoutMillis=Iap2WiredControlClient.NO_TIMEOUT_MILLIS,onProgress={
   log("CONTROL",it)
   if(it.startsWith("iap2 4300 "))log("START_SESSION_QUEUE","phase=availability ${it.removePrefix("iap2 4300 ")}")
   if(it.startsWith("iap2 tx=0x4301 transport flushed ")||it.startsWith("iap2 tx=0x4301 transport flush failed "))log("START_SESSION_QUEUE",it)
   when(it){
   Iap2WiredControlClient.START_SESSION_AGAIN_PROGRESS->Unit
   "iap2 identification accepted"->{phase("mfi_authentication");note("IAP2_AUTH","\u6b63\u5728\u6821\u9a8c CarPlay \u8ba4\u8bc1")}
   "iap2 authentication accepted"->{phase("phone_availability");note("AUTH_COMPLETE","\u8ba4\u8bc1\u5b8c\u6210\uff0c\u7b49\u5f85 iPhone \u542f\u52a8 CarPlay")}
   "iap2 tx=0x4301 carplay-start-session"->{if(!videoReady){phase("phone_projection_start");note("PHONE_START","\u6b63\u5728\u542f\u52a8 iPhone \u6295\u5c4f\u901a\u9053")}}
  }},onSessionStartPreparing={
   val firstAttempt=firstStartSentAt.get()==0L
   if(firstAttempt){
    val baseline=network.airPlayTcpEvidence()?.synCount?:-1L
    if(firstStartSentAt.compareAndSet(0L,System.nanoTime())){
     tcpSynBaseline.set(baseline)
     log("START_SESSION_RECOVERY","initial TCP SYN baseline captured before 0x4301 baseline=$baseline listenerPort=${srv.localPort}")
     startWatchdog.startSessionSent()
    } else {
     startWatchdog.startSessionSentAgain()
    }
   } else {
    startWatchdog.startSessionSentAgain()
   }
   log("START_SESSION_QUEUE","phase=begin attempt=${startSessionQueueAttempts.incrementAndGet()}")
  },onSessionStartFlushed={firstStartFlushedAt.compareAndSet(0L,System.nanoTime())},onSessionStartSent={WiredSessionMemory.started(phone.deviceName);network.startOutbound()},startSessionAgain=retryDecision)
  if(!closed&&controlResult.terminal==Iap2WiredControlTerminal.CARPLAY_UNAVAILABLE){
   log("CONTROL_END","terminal=carplay_unavailable stage=${controlResult.stage} sessions=${controlResult.carPlayStartSessionsSent}")
   log("START_SESSION_QUEUE","phase=availability result=carplay_unavailable stage=${controlResult.stage}")
   note("FAILED","iPhone 报告有线 CarPlay 当前不可用，未发送启动请求；请检查手机 CarPlay 限制或重新插拔")
  } else if(!closed)note("CONTROL_ENDED","手机控制通道已结束，请提交日志")
  shutdown()
 }
 private fun shutdown(){
  val owned:List<Closeable>
  synchronized(resourceLock){if(closed)return;closed=true;owned=handles.toList();handles.clear();cleanupCount.incrementAndGet()}
  wiredStartWatchdog?.terminate();wiredStartWatchdog=null
  note("DISCONNECTED","CarPlay 已断开，正在释放连接");
  reportStartup(if(videoReady)"released_after_video" else "ended_before_video");unregisterMediaButtonOwner();audioObserver?.close();wheelControl?.close();usbLinked=false;authorization.countDown();watchdog.shutdownNow();work.shutdownNow();input.shutdownNow()
  log("RELEASE_BEGIN","handles=${owned.size} sessions=${sessions.size}")
  val pending=java.util.concurrent.atomic.AtomicBoolean(true)
  Handler(Looper.getMainLooper()).postDelayed({if(pending.get()){log("RELEASE_TIMEOUT","cleanup still running after 12 seconds");for((thread,stack) in Thread.getAllStackTraces())if(thread.name.startsWith("legacy-")||thread.name.startsWith("api17-")||thread.name.startsWith("ncm-"))log("RELEASE_TIMEOUT","thread=${thread.name} state=${thread.state} at=${stack.take(5).joinToString()}")}},12000)
  Thread({
   fun release(name:String,action:()->Unit){val at=SystemClock.elapsedRealtime();log("RELEASE_STEP","begin=$name");try{action()}catch(e:Throwable){log("RELEASE_ERROR","$name ${e.javaClass.simpleName}: ${e.message}")}finally{log("RELEASE_STEP","end=$name durationMs=${SystemClock.elapsedRealtime()-at}")}}
   try{
    release("listener"){server?.close()}
    for(s in sessions)release("airplay"){s.close()};sessions.clear()
    for(h in owned.reversed())release(resourceNames[h]?:h.javaClass.simpleName){h.close()}
    LegacyUsb.clear()
   }finally{
    pending.set(false);cleanupCount.decrementAndGet();note("STOPPED",lastFailure?.let{"连接已释放：$it"}?:"连接已停止")
    CrashEvidence.end(this@WiredService,connectionId,"cleanup_complete",SystemClock.elapsedRealtime()-bornAt);Handler(Looper.getMainLooper()).post{stopForeground(true);stopSelf()}
   }
  },"legacy-release").start()
 }
 override fun onDestroy(){shutdown();if(live===this)live=null;super.onDestroy()}
}
