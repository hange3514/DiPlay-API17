package local.headunit.diplay

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.*
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.json.*

/** API17-owned wired lifecycle; no BYD vehicle writes or wireless startup path. */
class WiredService:Service() {
 override fun onBind(intent:Intent?):IBinder?=null
 companion object {
  private const val REENUMERATION_WAIT_MS=10000L
  private const val SAME_ENUMERATION_PROBE_MS=250L
  private const val AIRPLAY_CONTROL_PORT=7000
  const val EXTRA_BENCHMARK_FPS="benchmark_fps"
  private val mx9a0AvcDecoder by lazy {
   val name="OMX.Freescale.std.video_decoder.avc.v3.hw-based"
   runCatching {
    (0 until MediaCodecList.getCodecCount()).any { index ->
     val codec=MediaCodecList.getCodecInfoAt(index)
     !codec.isEncoder&&codec.name==name&&codec.supportedTypes.any{it.equals("video/avc",ignoreCase=true)}
    }
   }.getOrDefault(false).let{if(it)name else null}
  }
  @Volatile private var surface:Surface?=null
  @Volatile private var touchSurfaceGeneration=0L
  private var surfaceOwner:Any?=null
  @JvmStatic @Synchronized fun attachSurface(owner:Any,value:Surface){
   if(surfaceOwner===owner&&surface===value)return
   val previousGeneration=touchSurfaceGeneration++
   val service=live
   if(surface!=null)service?.cancelTouchOnDetach(previousGeneration)
   surfaceOwner=owner
   surface=value
   service?.sink?.setSurface(value)
   service?.sink?.routing?.foreground()
   service?.log("VIDEO_SURFACE","attached")
  }
  @JvmStatic @Synchronized fun detachSurface(owner:Any){
   if(surfaceOwner!==owner)return
   surfaceOwner=null
   surface=null
   val previousGeneration=touchSurfaceGeneration++
   val service=live
   service?.cancelTouchOnDetach(previousGeneration)
   service?.sink?.setSurface(null)
   service?.log("VIDEO_SURFACE","detached; USB session retained")
  }
  @JvmStatic fun currentConnectionId():String=live?.let{if(!it.closed)it.connectionId else ""}.orEmpty()
  @JvmStatic fun renderedVideoFrames():Long=live?.sink?.renderedVideoFrames?:0L
  @JvmStatic fun currentFps():Int=live?.selectedFps?:0
  @JvmStatic fun beginVideoCalibration(id:String):Boolean=live?.startCalibration(id)==true
  @JvmStatic fun finishVideoCalibration(id:String):VideoCalibration.Sample?=live?.finishCalibration(id)
  @JvmStatic fun cancelVideoCalibration(id:String){live?.cancelCalibration(id)}
  @JvmStatic fun isVideoReady():Boolean=live?.let{!it.closed&&it.videoReady}==true
  @Volatile private var live:WiredService?=null
  // Main-thread request state. AudioManager has no registration result.
  private var mediaButtonRegistrationRequested=false
  private var mediaButtonRegistrationGeneration=0L
  private val cleanupCount=java.util.concurrent.atomic.AtomicInteger()
  private val connectingWorkers=java.util.concurrent.atomic.AtomicInteger()
  @JvmStatic fun stop(context:Context){live?.shutdown();context.stopService(Intent(context,WiredService::class.java))}
  @JvmStatic fun isBusy():Boolean=live!=null||cleanupCount.get()>0||connectingWorkers.get()>0||MediaWorkers.busy()
  @JvmStatic fun hasUsbLink():Boolean=live?.usbLinked==true
  @JvmStatic fun awaitingPermission():Boolean=live?.usbPermissionPending==true
  @JvmStatic fun tracksUsbDevice(device:UsbDevice):Boolean=
   UsbAttachActivity.sameDevice(live?.selectedUsbDevice,device)
  @JvmStatic fun forgetUsbEnumeration(device:UsbDevice){WiredSessionMemory.detached(device.deviceName)}
  @JvmStatic fun releaseMissingUsbTarget(devices:Collection<UsbDevice>):Boolean{
   val owner=live?:return false
   if(owner.closed||owner.usbReenumerationExpected)return false
   val target=owner.selectedUsbDevice?:return false
   if(devices.any{UsbAttachActivity.sameDevice(target,it)})return false
   // The USB worker may have adopted a new enumeration after the watcher sampled its list.
   if(UsbAttachActivity.sameDevice(target,owner.usb.deviceList[target.deviceName]))return false
   if(owner.usbReenumerationExpected||owner.selectedUsbDevice!==target)return false
   owner.log("USB","stage=target_missing result=release_replaced_link")
   owner.shutdown()
   forgetUsbEnumeration(target)
   return true
  }
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
  @JvmStatic @Synchronized fun touch(source:Any,action:Int,contacts:List<AirPlayContact>,newPointerId:Int?,androidPointerCount:Int){
   // A replaced Activity can still deliver CANCEL. Do not let it touch the current queue.
   if(surfaceOwner!==source)return
   val owner=live?:return
   val target=owner.session?:return
   if(owner.closed||!target.canSendInput())return
   if(action!=MotionEvent.ACTION_CANCEL&&(!owner.videoReady||surface?.isValid!=true))return
   val surfaceGeneration=touchSurfaceGeneration
   if(androidPointerCount>=2&&owner.androidMultiTouchSeen.compareAndSet(false,true)){
    owner.recordTouchEvidence("android_two_contacts",action,androidPointerCount)
   }
   TouchDispatch.submit(owner.input,owner.touchQueue,action,
    onOverflow={owner.cancelTouch(target,surfaceGeneration,"queue_overflow")}){
    if(action==MotionEvent.ACTION_CANCEL)owner.cancelTouch(target,surfaceGeneration,"cancelled")
    else if(surfaceGeneration==touchSurfaceGeneration&&surface?.isValid==true)
     owner.sendTouchSnapshot(target,surfaceGeneration,action,contacts,newPointerId,androidPointerCount)
   }
  }

 }
 private val work=Executors.newSingleThreadExecutor()
 private val touchQueue=LinkedBlockingDeque<Runnable>(16)
 private val input=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,touchQueue)
 // Session, slots and diagnostic de-duplication are owned by the input worker.
 private var touchSession:AirPlaySession?=null
 private val touchState=ProjectionTouchState()
 private val loggedTouchEvidence=HashSet<String>()
 private val androidMultiTouchSeen=AtomicBoolean(false)

 private fun sendTouchSnapshot(target:AirPlaySession,generation:Long,action:Int,contacts:List<AirPlayContact>,newPointerId:Int?,androidPointerCount:Int){
  if(!prepareTouchSession(target))return
  val before=touchState.activeCount
  val sent=touchState.dispatch(generation,action,contacts,newPointerId){
   generation==touchSurfaceGeneration&&surface?.isValid==true&&writeTouchReport(target,it)
  }
  val active=touchState.activeCount
  val stage=when{
   sent&&before<2&&active==2->"dual_report_written"
   sent&&before==2&&active==1->"one_finger_released"
   !sent&&action==MotionEvent.ACTION_POINTER_DOWN&&androidPointerCount>=2->"second_contact_not_written"
   else->null
  }
  if(stage!=null&&loggedTouchEvidence.add(stage))recordTouchEvidence(stage,action,androidPointerCount,active)
 }

 private fun prepareTouchSession(target:AirPlaySession):Boolean{
  if(closed||session!==target||!target.canSendInput())return false
  if(touchSession!==target){
   touchState.reset()
   touchSession=target
  }
  return true
 }

 private fun writeTouchReport(target:AirPlaySession,contacts:List<AirPlayContact>):Boolean=
  !closed&&session===target&&runCatching{target.sendTouch(contacts)}.getOrDefault(false)

 private fun cancelTouch(target:AirPlaySession,generation:Long,stage:String){
  if(touchSession!==target||!prepareTouchSession(target))return
  val before=touchState.activeCount
  val sent=touchState.cancel(generation){writeTouchReport(target,it)}
  if((stage=="queue_overflow"||before==2&&sent)&&loggedTouchEvidence.add(stage))
   recordTouchEvidence(stage,MotionEvent.ACTION_CANCEL,0,touchState.activeCount)
 }

 private fun cancelTouchOnDetach(generation:Long){
  val target=session?:return
  val cancel={cancelTouch(target,generation,"surface_detached")}
  TouchDispatch.submit(input,touchQueue,MotionEvent.ACTION_CANCEL,onOverflow=cancel,work=cancel)
 }

 private fun recordTouchEvidence(stage:String,action:Int,androidPointerCount:Int,activeContacts:Int=-1){
  runCatching{MainActivity.record(this,"TOUCH_MULTITOUCH",JSONObject()
   .put("stage",stage).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt)
   .put("diagnosticSummary","stage=$stage action=$action androidPointers=$androidPointerCount activeContacts=$activeContacts"))}
 }
 @Volatile private var closed=false
 private var started=false
 @Volatile private var connectionId=""
 private val bornAt=SystemClock.elapsedRealtime()
 private val startup=ConnectionTimings(bornAt)
 private var requestStartedAt=bornAt
 @Volatile private var selectedFps=PlaybackOptions.DEFAULT_FPS
 private var usbProfile=0
 private var usbBatchId=""
 private var vpnWaitMs=0L
 private fun timingReport()=JSONObject(startup.snapshot(SystemClock.elapsedRealtime())).put("requestedFps",selectedFps).put("usbStartupProfile",usbProfile).put("usbBatchId",usbBatchId).put("vpnWaitMs",vpnWaitMs).put("beforeServiceMs",(bornAt-requestStartedAt).coerceAtLeast(0))
 private fun reportStartup(outcome:String){try{MainActivity.record(this,"CONNECT_SUMMARY",timingReport().put("outcome",outcome).put("connection",connectionId))}catch(_:Exception){}}
 private val failed=java.util.concurrent.atomic.AtomicBoolean(false)
 private val rawAirPlayFailureCount=java.util.concurrent.atomic.AtomicInteger()
 private val resourceLock=Any()
 private val mainHandler=Handler(Looper.getMainLooper())
 private val latestPerformance=ConcurrentHashMap<String,String>()
 @Volatile private var usbLinked=false
 @Volatile private var usbPermissionPending=false
 @Volatile private var selectedUsbDevice:UsbDevice?=null
 @Volatile private var usbReenumerationExpected=false
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
 private class UsbAuthorization(val device:UsbDevice,val sequence:Int,val stage:String) {
  val startedAt=SystemClock.elapsedRealtime()
  val result=CountDownLatch(1)
  @Volatile var denied=false
 }
 @Volatile private var authorization:UsbAuthorization?=null
 private var authorizationRequestSequence=0
 @Volatile private var videoReady=false
 @Volatile private var lastFailure:String?=null
 private var calibrationSession:AirPlaySession?=null // protected by resourceLock
 private fun startCalibration(id:String):Boolean=synchronized(resourceLock){
  val current=session?:return@synchronized false
  if(id.isEmpty()||connectionId!=id||closed||failed.get()||!videoReady||current.isClosed||
   surface?.isValid!=true||calibrationSession!=null)return@synchronized false
  if(sink?.beginVideoCalibration(selectedFps)!=true)return@synchronized false
  calibrationSession=current
  true
 }
 private fun finishCalibration(id:String):VideoCalibration.Sample?{
  val result=synchronized(resourceLock){
   if(connectionId!=id||id.isEmpty()||calibrationSession==null)return null
   val owner=calibrationSession
   calibrationSession=null
   if(closed||failed.get()||session!==owner||owner?.isClosed!=false){sink?.cancelVideoCalibration();return null}
   sink?.finishVideoCalibration()
  }?:return null
  val (sample,jitter)=result
  val summary="requestedFps=${sample.requestedFps} durationMs=${sample.durationMs} receivedFrames=${sample.receivedFrames} "+
   "activeFrames=${sample.movingFrames} submittedFrames=${sample.surfaceSubmittedFrames} "+
   "worstFpsMilli=${sample.lowestWindowSurfaceFpsMilli?:0} surfaceGapMaxMs=${sample.surfaceGapMaxMs} "+
   "pressureSkips=${sample.pressureSkips} droppedFrames=${sample.droppedFrames} "+
   "receiveToSurfaceMaxMs=${sample.receiveToSurfaceMaxMs} deliveryJitterMaxMs=$jitter "+
   "networkBackend=lwip_userland_ncm codec=${sink?.videoDecoderName?:"unknown"} completed=${sample.completed}"
  runCatching{MainActivity.record(this,"VIDEO_CALIBRATION_SAMPLE",JSONObject()
   .put("eventType","video_calibration_sample").put("connection",id).put("requestedFps",sample.requestedFps)
   .put("durationMs",sample.durationMs).put("diagnosticSummary",summary))}
  return sample
 }
 private fun cancelCalibration(id:String){synchronized(resourceLock){
  if(id.isNotEmpty()&&connectionId==id){calibrationSession=null;sink?.cancelVideoCalibration()}
 }}
 private val airplayTcpAccepted=java.util.concurrent.atomic.AtomicBoolean(false)
 private val watchdog=Executors.newSingleThreadScheduledExecutor()
 private val listener=object:AirPlaySessionListener {
  override fun onSessionActive(session:AirPlaySession){synchronized(resourceLock){
   if(closed||session.isClosed||!sessions.contains(session))return
   this@WiredService.session=session;sessionGeneration++;lastFailure=null;wiredStartWatchdog?.sessionActive();refreshMediaButtonOwner();if(!videoReady)phase("first_video");note("AIRPLAY_ACTIVE","CarPlay协议会话已建立，等待画面和声音")
  }}
  override fun onSessionEnded(session:AirPlaySession){synchronized(resourceLock){
   if(calibrationSession===session){calibrationSession=null;sink?.cancelVideoCalibration()}
   val wasActive=this@WiredService.session===session;if(wasActive){this@WiredService.session=null;sessionGeneration++;refreshMediaButtonOwner()};val endedGeneration=sessionGeneration;sessions.remove(session)
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
  val videoWindow=(stage=="MEDIA"&&text.startsWith("video_progress eventType=video_render_window "))||
   (stage=="AIRPLAY"&&text.startsWith("Receive: video eventType=video_rx_window "))
  val detail=if(videoWindow)"$text requestedFps=$selectedFps" else text
  if(metric!=null&&(latestPerformance.containsKey(metric)||latestPerformance.size<12))latestPerformance[metric]="ageMs=${SystemClock.elapsedRealtime()-bornAt} "+detail.take(1200)
  try{MainActivity.record(this,stage,JSONObject().put("detail",detail.take(1200)).put("connection",connectionId).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}}
 fun note(stage:String,text:String){if(stage=="FAILED")lastFailure=text;log(stage,text);sendBroadcast(Intent("local.headunit.diplay.STATUS").setPackage(packageName).putExtra("message",text).putExtra("stage",stage).putExtra("connection_id",connectionId))}
 private fun onFirstVideoFrame() {
  synchronized(resourceLock){
   if(closed||failed.get()||videoReady)return
   videoReady=true
   activeDiagnosticStage="video_active"
   startup.firstFrame(SystemClock.elapsedRealtime())
  }
  refreshMediaButtonOwner()
  CrashEvidence.updateStage(this,connectionId,"video_active",usbLinked,true)
  reportStartup("first_frame")
  if(!closed)note("VIDEO_READY","已收到并解码CarPlay画面；按返回键显示控制面板")
 }
 private fun safeDiagnosticType(t:Throwable):String { val value=t.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"),"").take(48);return value.ifEmpty{"Throwable"} }
 private fun recordFailure(e:Throwable,origin:String){try{var root=e;var count=0;while(root.cause!=null&&root.cause!==root&&count++<8)root=root.cause!!;MainActivity.record(this,"CONNECTION_FAILURE",JSONObject().put("origin",origin).put("phase",activeDiagnosticStage).put("errorType",safeDiagnosticType(e)).put("causeType",safeDiagnosticType(root)).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt).put("videoReady",videoReady).put("usbLinked",usbLinked).put("sessionCount",sessions.size).put("activeSession",session!=null).put("userlandNetworkOpen",userlandNetwork!=null).put("networkBackend","lwip_userland_ncm"))}catch(_:Throwable){}}
 private fun fail(e:Throwable,origin:String="connect_worker"){if(!closed&&failed.compareAndSet(false,true)){recordFailure(e,origin);var cause:Throwable?=e;val chain=ArrayList<String>();while(cause!=null&&chain.size<4){chain.add(cause.javaClass.simpleName+": "+(cause.message?:"connection failed").take(160));cause=cause.cause};note("FAILED",chain.joinToString(" → "));shutdown()}}
 override fun onCreate(){
  super.onCreate();live=this;audioObserver=OemAudioObserver(this);wheelControl=WheelControl(this,object:WheelControl.Sink {
   override fun session():Any?=this@WiredService.session?.takeIf{!closed&&videoReady&&!it.isClosed}
   override fun send(token:Any,action:Int):Boolean {
    val current=this@WiredService.session
    if(closed||current==null||current!==token||current.isClosed)return false
    return when(action){1->current.sendMedia(5);2->current.sendMedia(4);3->current.sendTelephony(1);4->current.sendTelephony(3);5->{current.invokeSiri();true};else->false}
   }
  })
  usb=getSystemService(Context.USB_SERVICE) as UsbManager
  host=IphoneUsbHost(this,usb,IphoneUsbMatcher.appleVendor(),"$packageName.IPHONE_USB_PERMISSION.${UUID.randomUUID()}",onDiagnostic={log("USB",it)})
  handles.add(host.registerPermissionReceiver(::onUsbPermission))
  val detachReceiver=object:BroadcastReceiver(){
   override fun onReceive(context:Context,intent:Intent){
    val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?:return
    val targetStillPresent=UsbAttachActivity.sameDevice(device,usb.deviceList[device.deviceName])
    if(!usbReenumerationExpected&&!targetStillPresent&&UsbAttachActivity.sameDevice(selectedUsbDevice,device)){
     log("USB","stage=device_detached result=release_active_link")
     shutdown()
    }
    if(!targetStillPresent)forgetUsbEnumeration(device)
   }
  }
  registerReceiver(detachReceiver,IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
  handles.add(Closeable{unregisterReceiver(detachReceiver)})
 }

 private fun onUsbPermission(result:IphoneUsbHost.PermissionResult){
  val pending=authorization?:return
  val device=result.device
  if(pending.sequence!=result.requestId||!UsbAttachActivity.sameDevice(pending.device,device)){
   log("USB_PERMISSION_RESULT","sequence=${pending.sequence} phase=${pending.stage} result=stale_ignored")
   return
  }
  pending.denied=result is IphoneUsbHost.PermissionResult.Denied
  log("USB_PERMISSION_RESULT","sequence=${pending.sequence} phase=${pending.stage} result=${if(pending.denied)"denied" else "granted"} vendor=${device.vendorId} product=${device.productId} elapsedMs=${SystemClock.elapsedRealtime()-pending.startedAt}")
  pending.result.countDown()
 }
 private fun shouldOwnMediaButtons():Boolean =
  !closed && !failed.get() && videoReady && session?.isClosed==false

 private fun refreshMediaButtonOwner(){
  mainHandler.post{
   // A delayed callback from an older service applies the state of the current service.
   val current=live
   val owner=current?.takeIf{it.shouldOwnMediaButtons()}
   if(mediaButtonRegistrationRequested==(owner!=null))return@post
   val generation=++mediaButtonRegistrationGeneration
   val logger=current?:this
   val receiver=ComponentName(this,MediaButtonWheelReceiver::class.java)
   if(owner!=null){
    try{
     packageManager.getReceiverInfo(receiver,0)
    }catch(error:PackageManager.NameNotFoundException){
     logger.recordMediaButtonOwner("receiver_unavailable",error)
     return@post
    }catch(error:RuntimeException){
     logger.recordMediaButtonOwner("receiver_check_failed",error)
     return@post
    }
    try{
     val initialOwner=owner.readMediaButtonOwnerSetting()
     val audio=getSystemService(Context.AUDIO_SERVICE) as AudioManager
     audio.registerMediaButtonEventReceiver(receiver)
     mediaButtonRegistrationRequested=true
     logger.recordMediaButtonOwner("register_requested")
     owner.verifyMediaButtonOwner(receiver,initialOwner,generation)
    }catch(error:Exception){
     logger.recordMediaButtonOwner("register_failed",error)
    }
   }else{
    try{
     val audio=getSystemService(Context.AUDIO_SERVICE) as AudioManager
     audio.unregisterMediaButtonEventReceiver(receiver)
     mediaButtonRegistrationRequested=false
     logger.recordMediaButtonOwner("release_requested")
    }catch(error:Exception){
     logger.recordMediaButtonOwner("release_failed",error)
    }
   }
  }
 }

 private fun readMediaButtonOwnerSetting():Result<String?> =
  runCatching { Settings.System.getString(contentResolver,"media_button_receiver") }

 private fun verifyMediaButtonOwner(receiver:ComponentName,initialOwner:Result<String?>,generation:Long,remaining:Int=4){
  if(Build.VERSION.SDK_INT>=21){
   recordMediaButtonOwner("owner_readback_unsupported")
   return
  }
  // API17 AudioService writes its selected receiver to Settings asynchronously.
  // Observe one bounded window because another player may claim the same role.
  val delayMs=if(remaining==4)150L else 350L
  mainHandler.postDelayed({
   if(generation!=mediaButtonRegistrationGeneration || live!==this ||
      !mediaButtonRegistrationRequested || !shouldOwnMediaButtons())return@postDelayed
   val readback=readMediaButtonOwnerSetting()
   val observed=readback.getOrNull()?.let(ComponentName::unflattenFromString)
   if(observed==receiver){
    val previous=initialOwner.getOrNull()?.let(ComponentName::unflattenFromString)
    val decision=when {
     initialOwner.isFailure -> "owner_matches_without_baseline"
     previous==receiver -> "owner_already_recorded"
     else -> "owner_readback_confirmed"
    }
    recordMediaButtonOwner(decision)
   }else if(remaining>1){
    verifyMediaButtonOwner(receiver,initialOwner,generation,remaining-1)
   }else{
    val decision=if(readback.getOrNull()==null)"owner_readback_unavailable" else "owner_readback_not_confirmed"
    recordMediaButtonOwner(decision,readback.exceptionOrNull())
   }
  },delayMs)
 }

 private fun recordMediaButtonOwner(decision:String,error:Throwable?=null){
  try{
   val row=JSONObject().put("keyPath","media_button_owner").put("keyDecision",decision)
   if(error!=null)row.put("errorType",safeDiagnosticType(error))
   MainActivity.record(this,"WHEEL_KEY",row)
  }catch(_:Exception){}
 }
 override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
  if(started)return START_NOT_STICKY;usbBatchId=i?.getStringExtra("usb_batch_id").orEmpty().take(80);usbProfile=if(usbBatchId.isEmpty())UsbStartupProfile.DEFAULT else UsbStartupProfile.valid(i?.getIntExtra("usb_startup_profile",0)?:0);requestStartedAt=(i?.getLongExtra("request_started_at",bornAt)?:bornAt).takeIf{it in 1..bornAt}?:bornAt;vpnWaitMs=(i?.getLongExtra("vpn_wait_ms",0)?:0).coerceIn(0,(bornAt-requestStartedAt).coerceAtLeast(0));connectionId=i?.getStringExtra("connection_id").orEmpty();if(cleanupCount.get()>0||connectingWorkers.get()>0||MediaWorkers.busy()){note("FAILED","上一轮连接正在释放，请稍后重试");shutdown();return START_NOT_STICKY};started=true;CrashEvidence.begin(this,connectionId);val benchmarkFps=i?.getIntExtra(EXTRA_BENCHMARK_FPS,0)?:0;selectedFps=if(benchmarkFps==0)PlaybackOptions.read(this) else PlaybackOptions.normalize(benchmarkFps);log("VIDEO_CONFIG","requestedFps=$selectedFps width=${ProjectionConfig.WIDTH} height=${ProjectionConfig.HEIGHT}");log("CONNECT_BUILD",runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrDefault("unknown"));note("CONNECT_START","正在建立CarPlay连接");log("CONNECTION_PREWARM","at=service_start identityReady=${ConnectionPrewarm.identityReady()} nativeReady=${ConnectionPrewarm.nativeReady()} nativeStatus=${ConnectionPrewarm.nativePrewarmStatus()} mfiReady=${VerifiedMfiCache.ready()}")
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
 private fun currentAppleDevice(expected:UsbDevice):UsbDevice? {
  val devices=host.discover()
  devices.firstOrNull{UsbAttachActivity.sameDevice(expected,it)}?.let{return it}
  if(devices.isEmpty())return null
  throw IOException("iPhone changed during USB authorization")
 }
 private fun authorize(d:UsbDevice):UsbDevice {
  val reservationDeadline=SystemClock.elapsedRealtime()+TimeUnit.SECONDS.toMillis(45)
  while(true){
   check()
   val target=currentAppleDevice(d)
   if(target!=null&&usb.hasPermission(target)){
    log("USB_PERMISSION_STATE","state=already_granted phase=$activeDiagnosticStage vendor=${target.vendorId} product=${target.productId}")
    return target
   }
   if(SystemClock.elapsedRealtime()>=reservationDeadline)throw IOException("USB authorization timed out")
   if(target==null){Thread.sleep(50);continue}
   if(UsbAttachActivity.consumeRecentActivityPermissionDenial(target)){
    AutoUsbService.pauseForDevice(this,target)
    throw IOException("USB permission was denied in the system prompt")
   }
   if(!UsbAttachActivity.beginPermissionFlow()){Thread.sleep(100);continue}
   val pending=UsbAuthorization(target,++authorizationRequestSequence,activeDiagnosticStage)
   authorization=pending
   usbPermissionPending=true
   try{
    val request=host.requestPermission(target,pending.sequence)
    val immediate=request is IphoneUsbHost.PermissionRequest.AlreadyGranted
    log("USB_PERMISSION_REQUEST","sequence=${pending.sequence} phase=${pending.stage} result=${if(immediate)"already_granted" else "requested"} vendor=${target.vendorId} product=${target.productId}")
    if(!immediate)note("USB_PERMISSION","\u8bf7\u5141\u8bb8\u8f66\u673a USB \u8bbf\u95ee\uff1b\u5982\u679c\u624b\u673a\u63d0\u793a\u4fe1\u4efb\uff0c\u4e5f\u8bf7\u9009\u62e9\u4fe1\u4efb")
    while(true){
     check()
     val current=currentAppleDevice(d)
     val elapsed=SystemClock.elapsedRealtime()-pending.startedAt
     if(current!=null&&usb.hasPermission(current)){
      log("USB_PERMISSION_WAIT","sequence=${pending.sequence} phase=${pending.stage} result=granted elapsedMs=$elapsed")
      return current
     }
     if(current!=null&&pending.denied){AutoUsbService.pauseForDevice(this,current);throw IOException("USB permission was denied; automatic retry paused")}
     if(SystemClock.elapsedRealtime()>=reservationDeadline){
      log("USB_PERMISSION_WAIT","sequence=${pending.sequence} phase=${pending.stage} result=timeout elapsedMs=$elapsed")
      throw IOException("USB authorization timed out")
     }
     if(pending.result.count!=0L)pending.result.await(100,TimeUnit.MILLISECONDS) else Thread.sleep(100)
    }
   }finally{
    authorization=null
    usbPermissionPending=false
    UsbAttachActivity.endPermissionFlow(null)
   }
  }
 }

 private var phaseAt=SystemClock.elapsedRealtime()
 private var previousPhase="start"
 @Volatile private var activeDiagnosticStage="service_start"
 @Synchronized private fun phase(name:String){val now=SystemClock.elapsedRealtime();activeDiagnosticStage=name;CrashEvidence.updateStage(this,connectionId,name,usbLinked,videoReady);startup.advance(name,now);log("CONNECT_TIMING","completed=$previousPhase durationMs=${now-phaseAt} next=$name");phaseAt=now;previousPhase=name}
 private fun readCarPlayConfiguration(d:UsbDevice,expectedPeerKey:String?):Pair<UsbDevice,LegacyConfiguration?>? {
  val authorized=authorize(d)
  val c=usb.openDevice(authorized)?:return null
  return try {
   val peerKey=expectedPeerKey?.let{runCatching{WiredPairingPolicy.usbKey(LegacyUsb.boundedSerial(c))}.getOrNull()}
   if(WiredPairingPolicy.isDifferentUsbPeer(expectedPeerKey,peerKey)){
    log("USB_MODE","transition candidate belongs to a different iPhone")
    throw IOException("iPhone changed during CarPlay USB-mode transition")
   }
   LegacyUsb.read(authorized,c)
   val previous=selectedUsbDevice
   selectedUsbDevice=authorized
   if(previous!=null&&!UsbAttachActivity.sameDevice(previous,authorized)){
    AutoUsbService.adoptUsbEnumeration(previous,authorized)
   }
   Pair(authorized,IphoneCarPlayConfiguration.find(authorized))
  }finally{c.close()}
 }
 private fun transitionCandidate(current:UsbDevice):UsbDevice? {
  val devices=host.discover()
  return devices.firstOrNull{UsbAttachActivity.sameDevice(current,it)}?:devices.singleOrNull()
 }

 private fun selectCarPlayConfiguration(initial:UsbDevice,expectedPeerKey:String?):Pair<UsbDevice,LegacyConfiguration> {
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
      usbReenumerationExpected=true
      try{
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
       var settledDeviceProbeDone=false
       var lastCandidate:UsbDevice?=null
       var lastCandidateConfig:LegacyConfiguration?=null
       while(SystemClock.elapsedRealtime()<deadline) {
        check()
        val next=transitionCandidate(lastCandidate?:phone)
        if(next==null) {
         sawDetach=true
         Thread.sleep(50)
         continue
        }
        val changedEnumeration=!UsbAttachActivity.sameDevice(phone,next)||sawDetach
        val elapsed=SystemClock.elapsedRealtime()-waitStarted
        val probeAfterSettle=!changedEnumeration&&!settledDeviceProbeDone&&elapsed>=SAME_ENUMERATION_PROBE_MS
        if(!UsbAttachActivity.sameDevice(lastCandidate,next)||sawDetach||probeAfterSettle) {
         val inspectedConfig=readCarPlayConfiguration(next,expectedPeerKey)
         if(inspectedConfig==null){
          // Android can publish the new enumeration just before its USB node opens.
          Thread.sleep(100)
          continue
         }
         val (inspected,nextConfig)=inspectedConfig
         if(probeAfterSettle)settledDeviceProbeDone=true
         sawDetach=false
         lastCandidate=inspected
         lastCandidateConfig=nextConfig
         val nextCarPlayMode=nextConfig?.let{IphoneCarPlayConfiguration.isCarPlayMode(it)}==true
         log("USB_MODE","reenumerated=$changedEnumeration device=${next.deviceName} config=${nextConfig?.id?:"none"} appleEthernet=$nextCarPlayMode elapsedMs=$elapsed")
         if(nextConfig!=null&&nextCarPlayMode)return Pair(inspected,nextConfig)
         if(nextConfig!=null&&changedEnumeration) {
          log("USB_MODE","new enumeration did not expose Apple Ethernet; using fallback after one mode request")
          return Pair(inspected,nextConfig)
         }
        }
        Thread.sleep(50)
       }
       val stillPresent=transitionCandidate(lastCandidate?:phone)
       if(lastCandidate!=null&&lastCandidateConfig!=null&&UsbAttachActivity.sameDevice(lastCandidate,stillPresent)) {
        log("USB_MODE","no mode re-enumeration within wait limit; falling back to available config=${lastCandidateConfig.id}")
        return Pair(lastCandidate,lastCandidateConfig)
       }
       if(config!=null&&UsbAttachActivity.sameDevice(phone,stillPresent)) {
        log("USB_MODE","transition wait ended without replacement descriptors; reusing config=${config.id}")
        return Pair(phone,config)
       }
       if(transitionsTried<maxTransitions&&stillPresent!=null) {
        val (inspected,nextConfig)=readCarPlayConfiguration(stillPresent,expectedPeerKey)
         ?:throw IOException("Cannot open iPhone USB after the CarPlay-mode transition")
        phone=inspected
        config=nextConfig
        log("USB_MODE","retrying bounded transition count=$transitionsTried")
        continue
       }
       throw IOException("iPhone did not expose USBMUX+NCM after CarPlay-mode transition waitMs=${SystemClock.elapsedRealtime()-waitStarted}")
      }finally{usbReenumerationExpected=false}
    }
   }
  }
 }

 private fun openCarKit(
  store:IdentityStore, mux:Iap2UsbMuxHost, usbSession:Iap2UsbSession
 ):BlockingDuplexByteStream {
  val label="DiPlay API17"
  val pairing=LockdownPairingClient(mux,onDiagnostic={log("LOCKDOWN",it)})
  val usbKey=runCatching{WiredPairingPolicy.usbKey(usbSession.serialNumber())}.getOrNull()
  val usbRecord=usbKey?.let(store::loadPair)
  val oldRecord=if(usbRecord==null)store.loadLegacyPair() else null
  val devicePublicKey=if(usbKey==null||oldRecord!=null){
   try{pairing.readDevicePublicKey(label){closed}}catch(e:Exception){
    check()
    log("LOCKDOWN_PAIRING","stage=identity result=public_key_unavailable errorType=${e.javaClass.simpleName}")
    null
   }
  }else null
  val publicScope=devicePublicKey?.let(WiredPairingPolicy::publicKey)
  val scope=usbKey?:publicScope
  val scopedRecord=usbRecord?:publicScope?.let(store::loadPair)
  val legacyMatches=oldRecord!=null&&devicePublicKey!=null&&
   WiredPairingPolicy.matchesPublicKey(oldRecord.devicePublicKeyPem,devicePublicKey)
  val cached=scopedRecord?:oldRecord?.takeIf{legacyMatches}
  val source=when{
   usbRecord!=null->"usb_cache"
   scopedRecord!=null->"public_key_cache"
   cached!=null->"verified_legacy"
   else->"new_peer"
  }
  log("LOCKDOWN_PAIRING","stage=identity result=$source")
  note("PAIRING",if(cached!=null)"正在使用已保存的 iPhone 配对信息" else "首次配对，请保持 iPhone 解锁并确认信任")
  val client=LockdownCarKitClient(mux)
  if(cached!=null){
   phase("carkit_tls");check()
   try{
    val carkit=client.open(cached,label)
    // Save the migration only after the phone accepted its verified record.
    if(usbRecord==null&&scope!=null)try{store.savePair(scope,cached)}catch(e:Exception){
     log("LOCKDOWN_PAIRING","stage=cache result=write_failed errorType=${e.javaClass.simpleName}")
    }
    return carkit
   }catch(e:Exception){
    check()
    if(!WiredPairingPolicy.needsRepair(e))throw e
    val rejectedScope=if(usbRecord!=null)usbKey else if(scopedRecord!=null)publicScope else null
    if(rejectedScope!=null)store.clearPair(rejectedScope)
    log("LOCKDOWN_PAIRING","stage=carkit_tls result=credential_rejected errorType=${e.javaClass.simpleName}")
    note("PAIRING_RETRY","此 iPhone 的配对已失效，请重新确认信任")
   }
  }
  phase("lockdown_pairing")
  val record=pairing.pair(label,store.hostId,store.buid,90000,isCancelled={closed}).pairRecord
  check()
  val newScope=usbKey?:WiredPairingPolicy.publicKey(record.devicePublicKeyPem)
  try{store.savePair(newScope,record)}catch(e:Exception){
   log("LOCKDOWN_PAIRING","stage=cache result=write_failed errorType=${e.javaClass.simpleName}")
  }
  phase("carkit_tls");check()
  return client.open(record,label)
 }

 private fun connect(){
  phase("usb_discovery")
  val discovered=selectPhone();selectedUsbDevice=discovered;UsbPresenceLog.recordTransition(this,"selected",discovered);UsbPresenceLog.capture(this,"selected_phone");phase("usb_permission");val d=authorize(discovered);check();phase("descriptors")
  val initial=usb.openDevice(d)?:throw IOException("无法打开iPhone USB")
  val (configs,initialPeerKey)=try{
   val peerKey=runCatching{WiredPairingPolicy.usbKey(LegacyUsb.boundedSerial(initial))}.getOrNull()
   Pair(LegacyUsb.read(d,initial),peerKey)
  }finally{initial.close()}
  log("DESCRIPTORS",configs.joinToString { "config=${it.id}:"+IphoneCarPlayConfiguration.describe(it) })
  val selected=selectCarPlayConfiguration(d,initialPeerKey)
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
  val ncm=try{NcmUsbBridge.open(nc,fn,profile=usbProfile,probe=usbBatchId.isNotEmpty(),onDiagnostic={log("NCM",it)})}catch(e:Throwable){nc.close();throw e};own(ncm,"ncm_usb");ncm.startEarlyReceive();selectedUsbDevice=phone;usbLinked=true
  phase("userland_ipv6_stack");log("NETWORK_BACKEND","backend=lwip_userland_ncm vpnUsed=false ipv4=false ipv6=true tcp=true udp=true mtu=1500 hostMacAvailable=${ncm.hostMac!=null}");val nativeReadyBeforeUse=ConnectionPrewarm.nativeReady();val nativeCrashFile=java.io.File(filesDir,"native-crash.txt");val nativeCapture=NativeIpStack.installCrashCapture(nativeCrashFile.absolutePath);NativeIpStack.setCrashStage("lwip_create_before");log("CONNECTION_PREWARM","at=native_before_create readyBeforeUse=$nativeReadyBeforeUse nativeStatus=${ConnectionPrewarm.nativePrewarmStatus()}");log("USERLAND_NETWORK","native crash capture armed=$nativeCapture")
  val mac=ncm.hostMac?:byteArrayOf(2,0,0,0,0,17);val macText=mac.joinToString(":"){"%02x".format(it.toInt() and 255)}
  val network=UserlandNcmNetwork(ncm,mac,{line->log(if(line.startsWith("NCM_OUT "))"NCM_WRITE_TIMING" else "USERLAND_NETWORK",line)},trackedTcpSynPort=AIRPLAY_CONTROL_PORT){fail(it,"userland_network")};own(network,"lwip_userland_network");val nativeStackStartAt=SystemClock.elapsedRealtime();var nativeStackStartResult="ready";try{network.start()}catch(error:Throwable){nativeStackStartResult="failed_${error.javaClass.simpleName.take(40)}";throw error}finally{log("CONNECTION_PREWARM","at=native_start_complete readyBeforeUse=$nativeReadyBeforeUse networkStartDurationMs=${SystemClock.elapsedRealtime()-nativeStackStartAt} result=$nativeStackStartResult")};if(usbProfile==UsbStartupProfile.DEFAULT)network.startNeighborWarmup();userlandNetwork=network
  phase("usbmux_handshake");val mux=Iap2UsbMuxHost.open(usbSession,onDiagnostic={line->log(if(line.startsWith("USBMUX_OUT "))"USBMUX_WRITE_TIMING" else "MUX",line)});own(mux,"usbmux_host")
  phase("lockdown_pairing")
  val identityReadyBeforeUse=ConnectionPrewarm.identityReady();val identityCallAt=SystemClock.elapsedRealtime();val store=ConnectionPrewarm.identityStore(this)
  log("CONNECTION_PREWARM","at=identity_use readyBeforeUse=$identityReadyBeforeUse waitMs=${SystemClock.elapsedRealtime()-identityCallAt}")
  val carkit=openCarKit(store,mux,usbSession);own(carkit,"carkit")
  phase("iap2_setup");note("CARKIT","iPhone CarKit通道已打开，正在建立协议会话")
  val csm=Iap2Session.open(carkit,traceContext="api17",onTrace={log("IAP2",it)});own(csm,"iap2")
  phase("mfi_identity");val auth=VerifiedMfiCache.get(this){log("MFI","challenge signed bytes=$it")}
  val identity=store.identity()
  // API17 has no per-app VPN routing. Only the USB link-local /64 is routed; no default/DNS routes.
  phase("airplay_listener");val configAir=AirPlayConfig("DiPlay API17",macText,macText,"320.20",AirPlayDisplayConfig(ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,fps=selectedFps),microphone=true)
  val preferredDecoder=if(Build.VERSION.SDK_INT==17&&"freescale".equals(Build.MANUFACTURER,true)&&"mx9a0".equals(Build.MODEL,true))mx9a0AvcDecoder else null
  val renderer=LegacyMedia(null,ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,preferredDecoder,{log("MEDIA",it);if(it=="video_rendered_frames=1")onFirstVideoFrame()});renderer.onVideoFailure={fail(IOException("video decode failure",it),"video_decoder")};renderer.routing=AudioRouting(this,renderer);own(renderer,"media");synchronized(Companion){sink=renderer;renderer.setSurface(surface)}
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
  },onSessionStartFlushed={firstStartFlushedAt.compareAndSet(0L,System.nanoTime())},onSessionStartSent={
   val active=synchronized(resourceLock){if(closed)false else{WiredSessionMemory.started(phone.deviceName);true}}
   if(active)network.startOutbound()
  },startSessionAgain=retryDecision)
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
  refreshMediaButtonOwner()
  wiredStartWatchdog?.terminate();wiredStartWatchdog=null
  note("DISCONNECTED","CarPlay 已断开，正在释放连接");
  reportStartup(if(videoReady)"released_after_video" else "ended_before_video");audioObserver?.close();wheelControl?.close();usbLinked=false;authorization?.result?.countDown();watchdog.shutdownNow();work.shutdownNow();input.shutdownNow()
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
