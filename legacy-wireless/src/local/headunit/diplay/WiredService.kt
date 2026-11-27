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
import java.util.concurrent.atomic.AtomicIntegerArray
import org.json.*

/** API17 hybrid lifecycle: USB bootstrap and Wi-Fi AirPlay data; no BYD vehicle writes. */
class WiredService:Service() {
 override fun onBind(intent:Intent?):IBinder?=null
 private fun registerDetachReceiver(){if(detachReceiver!=null)return;val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){if(intent?.action!=UsbManager.ACTION_USB_DEVICE_DETACHED)return;val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?:return;val active=usbBtDevice;val isActiveDongle=active!=null&&active.deviceId==device.deviceId&&active.vendorId==device.vendorId&&active.productId==device.productId;if(!isActiveDongle)return;usbLinked=false;log("HYBRID_USB_DETACHED","stage=android_usb_broadcast role=usb_bluetooth vendor=${device.vendorId} product=${device.productId} activeSessions=${sessions.size} videoReady=$videoReady");try{MainActivity.record(this@WiredService,"HYBRID_USB_DETACHED",JSONObject().put("usbDetached",true).put("role","usb_bluetooth").put("activeSession",session!=null).put("videoReady",videoReady).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){};shutdown()}};registerReceiver(receiver,IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));detachReceiver=receiver;own(Closeable{try{unregisterReceiver(receiver)}catch(_:Throwable){};detachReceiver=null},"usb_detach_receiver")}
 companion object {
  const val EXTRA_BENCHMARK_FPS="benchmark_fps"
  private const val AIRPLAY_CONTROL_PORT=7000
  private const val FIRST_FRAME_WAIT_MS=25000L
  private const val AIRPLAY_NEGOTIATION_TIMEOUT_MS=20000
  private const val SURFACE_READY_WAIT_MS=25000L
  private const val WIFI_RECONNECT_FIRST_FRAME_MS=4500L
  private const val HOTSPOT_REOPEN_LIMIT_MS=6500L
  private const val HOTSPOT_SESSION_PROOF_MS=750L
  private const val HOTSPOT_REOPEN_COOLDOWN_MS=15000L
  private val mx9a0AvcDecoder by lazy {
   val name="OMX.Freescale.std.video_decoder.avc.v3.hw-based"
   runCatching {
    (0 until MediaCodecList.getCodecCount()).any { index ->
     val codec=MediaCodecList.getCodecInfoAt(index)
     !codec.isEncoder&&codec.name==name&&codec.supportedTypes.any{it.equals("video/avc",ignoreCase=true)}
    }
   }.getOrDefault(false).let{if(it)name else null}
  }
  private val bufferedAacAvailable by lazy {
   runCatching {
    (0 until MediaCodecList.getCodecCount()).any { index ->
     val codec=MediaCodecList.getCodecInfoAt(index)
     !codec.isEncoder&&codec.supportedTypes.any{it.equals("audio/mp4a-latm",ignoreCase=true)}
    }
   }.getOrDefault(false)
  }
  private val airPlayStartupStep=Regex("^airplay startup stage=([a-z][a-z0-9_]{0,47})(?: |$)")
  private val videoStartupStep=Regex("(?:^| )eventType=((?:screen|video)_[a-z0-9_]{1,40})(?: |$)")
  private val startupDetail=Regex("[A-Za-z0-9_.,:/;=x\\[\\]()+# -]{1,500}")
  @Volatile private var surface:Surface?=null
  @Volatile private var touchSurfaceGeneration=0L
  @Volatile private var activeProbeUrl:String?=null
  private var surfaceOwner:Any?=null
  @JvmStatic @Synchronized fun attachSurface(owner:Any,value:Surface){if(surfaceOwner===owner&&surface===value){live?.signalProjectionSurface();return};val previousGeneration=touchSurfaceGeneration++;if(surface!=null)live?.cancelTouchOnDetach(previousGeneration);surfaceOwner=owner;surface=value;live?.signalProjectionSurface();live?.sink?.setSurface(value);live?.sink?.routing?.foreground();live?.log("VIDEO_SURFACE","attached")}
  @JvmStatic @Synchronized fun detachSurface(owner:Any){if(surfaceOwner!==owner)return;surfaceOwner=null;surface=null;val previousGeneration=touchSurfaceGeneration++;live?.cancelTouchOnDetach(previousGeneration);live?.sink?.setSurface(null);live?.log("VIDEO_SURFACE","detached; USB session retained")}
  @JvmStatic fun currentConnectionId():String=live?.let{if(!it.closed&&!it.failed.get())it.connectionId else ""}.orEmpty()
  @JvmStatic fun currentProbeUrl():String?=activeProbeUrl
  @JvmStatic fun currentProgressStage():String=live?.progressStage?:"CONNECT_START"
  @JvmStatic fun currentProgressMessage():String=live?.progressMessage.orEmpty()
  @JvmStatic fun renderedVideoFrames():Long=live?.sink?.renderedVideoFrames?:0L
  @JvmStatic fun currentFps():Int=live?.selectedFps?:0
  @JvmStatic fun routeMetadataCounts():IntArray {
   val owner=live?.takeUnless{it.closed}?:return IntArray(2)
   return IntArray(2){owner.routeMetadata.get(it)}
  }
  @JvmStatic fun siriDiagnosticSummary():String=
   live?.takeUnless{it.closed}?.session?.siriCapabilitySummary()?:"siriSession=absent"
   @JvmStatic fun vehicleDiagnosticSummary():String {
    val signals=live?.takeUnless{it.closed}?.vehicleSignals?.snapshot()?:return "vehicleSession=absent"
    val brake=signals.parkingBrake?.let{if(it)"engaged" else "released"}?:"unknown"
    val lights=signals.night?.let{if(it)"night" else "day"}?:"unknown"
    val motion=when{signals.speedKph.isNaN()->"unknown";signals.speedKph==0.0->"stationary";else->"moving"}
    val steering=signals.steeringRaw?.toString()?:"unknown"
    return "vehicleSession=present parkingBrake=$brake motion=$motion oemTheme=$lights speedSource=${signals.speedSource} speedSelector=${signals.speedSelector} steeringRaw=$steering steeringSwingRaw=${signals.steeringSwingRaw}"
   }
  @JvmStatic fun currentUsbBluetoothDevice():UsbDevice?=live?.takeUnless{it.closed}?.usbBtDevice
  @JvmStatic fun beginVideoCalibration(id:String):Boolean=live?.startCalibration(id)==true
  @JvmStatic fun finishVideoCalibration(id:String):VideoCalibration.Sample?=live?.finishCalibration(id)
  @JvmStatic fun cancelVideoCalibration(id:String){live?.cancelCalibration(id)}
  @JvmStatic fun isVideoReady():Boolean=live?.videoReady==true
  @Volatile private var live:WiredService?=null
  // Main-thread request state. AudioManager has no registration result.
  private var mediaButtonRegistrationRequested=false
  private var mediaButtonRegistrationGeneration=0L
  private val cleanupCount=java.util.concurrent.atomic.AtomicInteger()
  private val connectingWorkers=java.util.concurrent.atomic.AtomicInteger()
  @JvmStatic fun stop(context:Context){live?.shutdown(userRequested=true);context.stopService(Intent(context,WiredService::class.java))}
  @JvmStatic fun isBusy():Boolean=live!=null||cleanupCount.get()>0||connectingWorkers.get()>0||MediaWorkers.busy()
  @JvmStatic fun hasUsbLink():Boolean=live?.usbLinked==true
  @JvmStatic fun hasActiveAirPlay():Boolean=live?.let{!it.closed&&it.session?.isClosed==false}==true
  @JvmStatic fun diagnosticState():JSONObject {
   val owner=live
   val result=JSONObject().put("servicePresent",owner!=null).put("cleanupWorkers",cleanupCount.get()).put("connectingWorkers",connectingWorkers.get()).put("mediaWorkers",MediaWorkers.activeCount())
   if(owner!=null){result.put("connectionId",owner.connectionId).put("closed",owner.closed).put("usbLinked",owner.usbLinked).put("videoReady",owner.videoReady).put("ageMs",SystemClock.elapsedRealtime()-owner.bornAt).put("controlSessions",owner.sessions.size).put("touchQueue",owner.input.queue.size).put("latestPerformance",JSONObject(owner.latestPerformance as Map<*,*>)).put("startupTimings",owner.timingReport())
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
   val siri=CarPlayMediaButton.opensSiri(key)
   val index=CarPlayMediaButton.forKeyCode(key)
   if(!siri&&index==null){recordWheelEvent(owner,key,path,"unmapped_key",false);return false}
   if(!current.canSendInput()){recordWheelEvent(owner,key,path,"event_channel_unavailable",false);return false}
   val wheel=owner.wheelControl?:return false
   if(WheelKeyPolicy.androidAction(key)!=0&&!wheel.acceptAndroidKey(key)){
    recordWheelEvent(owner,key,path,"duplicate_suppressed",true,index?:-1)
    return true
   }
   val receivedAt=SystemClock.elapsedRealtime()
   val queued=wheel.enqueueAndroidReport{
    if(owner.closed||owner.session!==current||!current.canSendInput()||
       SystemClock.elapsedRealtime()-receivedAt>WheelControl.MAX_INPUT_AGE_MS){
     recordWheelEvent(owner,key,path,"stale_ignored",false,index?:-1)
    }else{
     val sent=runCatching{if(siri)current.invokeSiri() else current.sendMedia(requireNotNull(index))}.getOrDefault(false)
     recordWheelEvent(owner,key,path,if(sent)"report_written" else "report_write_failed",sent,index?:-1)
    }
   }
   if(!queued)recordWheelEvent(owner,key,path,"queue_unavailable",false,index?:-1)
   return queued
  }
  private fun recordWheelEvent(owner:WiredService,key:Int,path:String,decision:String,handled:Boolean,mediaIndex:Int=-1){try{val row=JSONObject().put("keyPath",path).put("keyCode",key).put("keyDecision",decision).put("keyHandled",handled).put("keyIsSiriCandidate",CarPlayMediaButton.opensSiri(key)||CarPlayMediaButton.opensSiriWhileCarPlay(key));if(mediaIndex>=0)row.put("mediaIndex",mediaIndex);MainActivity.record(owner,"WHEEL_KEY",row)}catch(_:Throwable){}}
  @JvmStatic fun adjustCarPlayVolume(direction:Int):Boolean{
   val owner=live?:return false
   if(owner.closed||!owner.videoReady)return false
   val audio=owner.getSystemService(Context.AUDIO_SERVICE) as AudioManager
   val stream=owner.sink?.routing?.volumeControlStream()?:AudioManager.STREAM_MUSIC
   audio.adjustStreamVolume(stream,direction,AudioManager.FLAG_SHOW_UI)
   owner.log("WHEEL_KEY","volume direction=$direction stream=$stream")
   return true
  }
  @JvmStatic fun touch(action:Int,contacts:List<AirPlayContact>,newPointerId:Int?,androidPointerCount:Int){
   val owner=live?:return
   val target=owner.session?:return
   if(owner.closed||!target.hasRenderedVideo||!target.canSendInput())return
   if(action!=MotionEvent.ACTION_CANCEL&&surface?.isValid!=true)return
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
 private val mainHandler=Handler(Looper.getMainLooper())
  private var vehicleSignals:LegacyVehicleSignals?=null
  private var lastVehicleNight:Boolean?=null // main-thread broadcast state
  private val themeWorker=Executors.newSingleThreadExecutor{job->Thread(job,"legacy-vehicle-theme")}
  private val themeQueued=AtomicBoolean(false)
  private var themeSession:AirPlaySession?=null // theme worker only
  private var themeValue:Boolean?=null
 private val touchQueue=LinkedBlockingDeque<Runnable>(16)
 private val routeMetadata=AtomicIntegerArray(2)
 private val input=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,touchQueue)
 // Accessed exclusively by the input worker.
 private var touchSession:AirPlaySession?=null
 private val touchState=ProjectionTouchState()
 private val androidMultiTouchSeen=AtomicBoolean(false)
 private val loggedTouchEvidence=HashSet<String>() // input worker only

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
 private var bootAutoConnect=false
 private var runtimeReconnect=false
 @Volatile private var runtimePeerVerified=false
 @Volatile private var runtimePhoneBluetooth:String?=null
 @Volatile private var connectionId=""
 private val bornAt=SystemClock.elapsedRealtime()
 private val startup=ConnectionTimings(bornAt)
 private var requestStartedAt=bornAt
 private var selectedFps=PlaybackOptions.DEFAULT_FPS
 private var benchmarkConnection=false
 private var usbProfile=0
 private var usbBatchId=""
 @Volatile private var detachReceiver:BroadcastReceiver?=null
 private var vpnWaitMs=0L
 @Volatile private var wifiIpReady=false
 @Volatile private var useP2p=false
 @Volatile private var p2pHotspot:WifiP2pHotspot?=null
 private fun networkBackend()=if(useP2p)"android_wifi_p2p_go" else "android_wifi_softap"
 private fun wirelessTransportMode()=if(useP2p)"usb_bt_p2p_data" else "usb_bt_softap_data"
 private fun wirelessNetworkReady()=if(useP2p)wifiIpReady else HybridNetwork.isApEnabled(this)
 @Volatile private var hybridIap2Profile=1
 @Volatile private var hybridBluetoothComponentEnabled=true
 @Volatile private var hybridWifiCredentialsIncluded=false
 @Volatile private var lastIap2MessageId=-1
 @Volatile private var wifiHandoff:HybridWirelessHandoff?=null
 private fun timingReport()=JSONObject(startup.snapshot(SystemClock.elapsedRealtime())).put("requestedFps",selectedFps).put("usbStartupProfile",usbProfile).put("usbBatchId",usbBatchId).put("vpnWaitMs",vpnWaitMs).put("beforeServiceMs",(bornAt-requestStartedAt).coerceAtLeast(0))
 private fun reportStartup(outcome:String){
  try{
   val report=timingReport().put("outcome",outcome).put("connection",connectionId).put("networkBackend",networkBackend())
   if(outcome=="first_wifi_frame"&&LegacyCarHotspot.isSupported(this)){
    LegacyCarHotspot.mobileDataSetting(this)?.let{report.put("diagnosticSummary","vehicleMobileDataSetting=$it backend=${networkBackend()}")}
   }
   MainActivity.record(this,"CONNECT_SUMMARY",report)
  }catch(_:Exception){}
 }
 private val failed=java.util.concurrent.atomic.AtomicBoolean(false)
 private val rawAirPlayFailureCount=java.util.concurrent.atomic.AtomicInteger()
 private val projectionStepCount=java.util.concurrent.atomic.AtomicInteger()
 private val resourceLock=java.lang.Object()
 private val projectionSurfaceSignal=java.lang.Object()
 private val latestPerformance=ConcurrentHashMap<String,String>()
 @Volatile private var usbLinked=false
 private var wheelControl:WheelControl?=null
 private var audioObserver:OemAudioObserver?=null
 @Volatile private var wiredStartWatchdog:WiredStartWatchdog?=null
 private lateinit var usb:UsbManager
 @Volatile private var usbBtDevice:UsbDevice?=null
 private val handles=CopyOnWriteArrayList<Closeable>()
 private val resourceNames=IdentityHashMap<Closeable,String>()
 @Volatile private var userlandNetwork:UserlandNcmNetwork?=null
 @Volatile private var session:AirPlaySession?=null
 private var calibrationSession:AirPlaySession?=null // protected by resourceLock
 private var sessionGeneration=0L // protected by resourceLock; invalidates an older grace timer
 private val sessions=CopyOnWriteArrayList<AirPlaySession>()
 @Volatile private var server:ServerSocket?=null
 @Volatile private var sink:LegacyMedia?=null
 @Volatile private var videoReady=false
 @Volatile private var lastFailure:String?=null
 private fun startCalibration(id:String):Boolean=synchronized(resourceLock){
  val current=session?:return@synchronized false
  if(id.isEmpty()||connectionId!=id||closed||failed.get()||!videoReady||current.isClosed||
   !current.hasRenderedVideo||surface?.isValid!=true||calibrationSession!=null)return@synchronized false
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
  val channel=runtimeHotspot?.channelNumber?:0
  val summary="requestedFps=${sample.requestedFps} durationMs=${sample.durationMs} receivedFrames=${sample.receivedFrames} "+
   "activeFrames=${sample.movingFrames} submittedFrames=${sample.surfaceSubmittedFrames} "+
   "worstFpsMilli=${sample.lowestWindowSurfaceFpsMilli?:0} surfaceGapMaxMs=${sample.surfaceGapMaxMs} "+
   "pressureSkips=${sample.pressureSkips} droppedFrames=${sample.droppedFrames} "+
   "receiveToSurfaceMaxMs=${sample.receiveToSurfaceMaxMs} deliveryJitterMaxMs=$jitter "+
   "networkBackend=${networkBackend()} channel=$channel codec=${sink?.videoDecoderName?:"unknown"} completed=${sample.completed}"
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
 private var firstFrameWatch:ScheduledFuture<*>?=null // protected by resourceLock
 private var firstFrameExpectedAt=0L
 private var runtimeWatch:ScheduledFuture<*>?=null // protected by resourceLock
 private val runtimeHealth=WirelessSessionHealth()
 private val runtimeKeyframePending=AtomicBoolean(false)
 @Volatile private var runtimeHotspot:HybridNetworkSnapshot?=null
 @Volatile private var refreshHotspotDiscovery:((HybridNetworkSnapshot,Boolean)->Boolean)?=null
 private class WifiRecoveryTarget(val peer:InetAddress,val controllerId:String,val bluetoothAddress:String){
  var firstFrameDeadlineMs=0L // Starts only when the replacement session can actually draw.
 }
 private var wifiRecovery:WifiRecoveryTarget?=null // protected by resourceLock
 private class HotspotRepair(val previous:AirPlaySession,val target:WifiRecoveryTarget,
  val binding:HybridNetworkSnapshot,val startedAt:Long){
  var restoredAtNs=0L
  var proofUntilMs=0L
  var mayKeepOriginal=false
  var discoveryReady=false
  var cleanupWatchArmed=false
  var worker:Thread?=null
 }
 private class RestoredHotspot(val binding:HybridNetworkSnapshot,val readyAtNs:Long,val discoveryReady:Boolean)
 @Volatile private var hotspotRepair:HotspotRepair?=null
 private var lastHotspotRepairAt=Long.MIN_VALUE // protected by resourceLock
 private var closingStalledSession:AirPlaySession?=null // protected by resourceLock
 private var projectionOwner:AirPlaySession?=null // Reserved on SETUP and released after media cleanup.
 private var bluetoothHandoffRequestedBy:AirPlaySession?=null // protected by resourceLock
 private var bluetoothBootstrapStop:(()->Boolean)?=null
 private var bluetoothHandoffWorkerQueued=false
 private var bluetoothBootstrapRetired=false

 private fun bluetoothHandoffKeepsWifi():Boolean=synchronized(resourceLock){
  bluetoothBootstrapRetired&&!closed&&(session?.isClosed==false||wifiRecoveryInProgress())
 }

 private fun canReleaseBluetoothBootstrap():Boolean{
  val active=session
  return !closed&&!failed.get()&&active?.isClosed==false&&active.controllerId!=null&&
   shouldReleaseBluetoothAfterHandoff(bluetoothHandoffRequestedBy,active,runtimePeerVerified,wifiHandoff?.tunnelReadyFor(active)==true)
 }

 private fun maybeReleaseBluetoothBootstrap(){synchronized(resourceLock){
  if(bluetoothHandoffWorkerQueued||bluetoothBootstrapStop==null||!canReleaseBluetoothBootstrap())return
  bluetoothHandoffWorkerQueued=true
  connectingWorkers.incrementAndGet()
  try{
   Thread({
    try{
     val stop=synchronized(resourceLock){
      bluetoothHandoffWorkerQueued=false
      if(!canReleaseBluetoothBootstrap())null
      else bluetoothBootstrapStop.also{
       bluetoothBootstrapRetired=true
       bluetoothBootstrapStop=null
      }
     }
     if(stop!=null){
      val result=runCatching{stop()}
      usbLinked=false
      recordRuntimeLink("bluetooth_handoff_closed",
       "scanDisabled=${result.getOrDefault(false)} error=${result.exceptionOrNull()?.javaClass?.simpleName?:"none"}")
     }
    }finally{connectingWorkers.decrementAndGet()}
   },"diplay-bluetooth-handoff").apply{isDaemon=true;start()}
  }catch(error:Exception){
   connectingWorkers.decrementAndGet()
   bluetoothHandoffWorkerQueued=false
   recordRuntimeLink("bluetooth_handoff_worker_failed","error=${error.javaClass.simpleName}")
  }
 }}
 private data class BootPeerCandidate(val prefs:SharedPreferences,val savedKey:String,val verifiedKey:String,val value:String)
 private var bootPeerCandidate:BootPeerCandidate?=null // protected by resourceLock
 private var expectedAirPlayController:String?=null // set before accepting and pinned under resourceLock on first frame
 private var otherAirPlayControllers=emptySet<String>() // assigned before accept workers start
 private val unavailablePhones=LinkedHashSet<String>() // initialized and filled by the connect worker
 private var continuingPhoneSelection=false
 private var phoneForAlternateRetry:String?=null // used only before the first projection
 private var previousAirPlayControllerIgnored=false // protected by resourceLock

 private fun peerControllerKey(verifiedKey:String,address:String)="$verifiedKey.peer.${address.uppercase(Locale.US)}.controller"

 private fun matchesAirPlayController(current:AirPlaySession):Boolean =
  WirelessPhoneReconnect.matchesAirPlayController(current.controllerId,expectedAirPlayController,otherAirPlayControllers)

 private fun selectedAirPlayResponding():Boolean=synchronized(resourceLock){
  sessions.any{!it.isClosed&&matchesAirPlayController(it)&&it.hasReceivedControl()}
 }

 private fun confirmBootPeerIfProjected(current:AirPlaySession){
  val candidate=bootPeerCandidate?:return
  val controller=current.controllerId?:return
  if(!current.hasRenderedVideo)return
  expectedAirPlayController=controller
  bootPeerCandidate=null
  val controllerKey="${candidate.verifiedKey}.controller"
  val previousRecord=candidate.prefs.getString(candidate.verifiedKey,null)
  val previousController=candidate.prefs.getString(controllerKey,null)
  val projectedPhone=candidate.value.substringAfter('|')
  val peerKey=peerControllerKey(candidate.verifiedKey,projectedPhone)
  if(previousRecord!=candidate.value||
     candidate.prefs.getString(candidate.savedKey,null)!=candidate.value||
     previousController!=controller||candidate.prefs.getString(peerKey,null)!=controller){
   val edit=candidate.prefs.edit()
   val previousPeer=WirelessPhoneReconnect.choose(previousRecord,candidate.value.substringBefore('|'),false).remembered
   if(previousPeer!=null&&previousController!=null&&!previousPeer.equals(projectedPhone,true)){
    edit.putString(peerControllerKey(candidate.verifiedKey,previousPeer),previousController)
   }
   edit.putString(candidate.savedKey,candidate.value).putString(candidate.verifiedKey,candidate.value)
    .putString(controllerKey,controller).putString(peerKey,controller).apply()
   recordPhoneConnection("projected_peer_verified")
  }
 }

 private fun wifiRecoveryInProgress():Boolean=synchronized(resourceLock){
  wifiRecovery!=null||hotspotRepair!=null||closingStalledSession!=null
 }

 private fun wifiRecoveryTarget(previous:AirPlaySession):WifiRecoveryTarget? {
  if(!videoReady||!runtimePeerVerified||wifiHandoff==null)return null
  val peer=previous.remoteAddress?:return null
  val controller=previous.controllerId?:return null
  val bluetooth=runtimePhoneBluetooth?:return null
  return WifiRecoveryTarget(peer,controller,bluetooth)
 }

 private fun startWifiRecovery(target:WifiRecoveryTarget,hotspotReopened:Boolean=false):Boolean {
  // A control socket admitted before recovery did not receive its identity restriction.
  if(sessions.isNotEmpty())return false
  val handoff=wifiHandoff?:return false
  wifiRecovery=target
  val requested=handoff.tryResumePhone(target.peer,target.bluetoothAddress,hotspotReopened){accepted->
   if(!accepted)runCatching{watchdog.execute{
    synchronized(resourceLock){
     if(!closed&&wifiRecovery===target&&session==null&&sessions.isEmpty())failWifiRecovery("control_unconfirmed")
    }
   }}
  }
  if(!requested){
   wifiRecovery=null
   return false
  }
  // Callback and accepted TCP sessions take resourceLock, so no pending peer can use the old queue.
  sink?.resetProjectionStream()
  note("WIFI_RECOVERING",if(useP2p)"正在通过 Wi-Fi Direct 恢复原 iPhone 的连接" else "正在通过车载热点恢复原 iPhone 的连接")
  recordRuntimeLink("wifi_reconnect_started","same_controller=true hotspotReopened=$hotspotReopened")
  watchdog.schedule({
   synchronized(resourceLock){
    if(!closed&&wifiRecovery===target&&session==null)failWifiRecovery("control_timeout")
   }
  },if(hotspotReopened)HybridWirelessHandoff.HOTSPOT_RESUME_WINDOW_MS else HybridWirelessHandoff.PHONE_RESUME_WINDOW_MS,TimeUnit.MILLISECONDS)
  return true
 }

 private fun failWifiRecovery(reason:String){
  wifiHandoff?.cancelPhoneResume()
  recordRuntimeLink("wifi_reconnect_fallback","reason=$reason")
  fail(IOException("iPhone 投屏连接需要通过蓝牙重新建立"),"wireless_ip_reconnect",allowRuntimeRetry=true)
 }

 private fun finishWifiRecovery(result:String){
  wifiRecovery=null
  wifiHandoff?.cancelPhoneResume()
  recordRuntimeLink("wifi_reconnect_complete","result=$result")
  note("VIDEO_READY",if(useP2p)"原 iPhone 已重新连接 Wi-Fi Direct" else "原 iPhone 已重新连接车载热点")
 }

 private fun recordRuntimeLink(stage:String,summary:String) {
  try{MainActivity.record(this,"HYBRID_RUNTIME_RECOVERY",JSONObject()
   .put("stage",stage).put("diagnosticSummary",summary)
   .put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
 }

 private fun captureRuntimeHotspot(phase:String,peer:InetAddress?=session?.remoteAddress){
  HotspotEvidence.captureRuntime(this,phase,SystemClock.elapsedRealtime()-bornAt,runtimeHotspot,peer)
 }

 private fun registerHotspotRecoveryReceiver(){
  val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){
   if(intent?.action!=HybridNetwork.AP_STATE_ACTION||!HybridNetwork.isApStoppingOrOff(intent.getIntExtra("wifi_state",-1)))return
   runCatching{watchdog.execute{
    synchronized(resourceLock){session?.let{startHotspotRepair(it)}}
   }}
  }}
  try{registerReceiver(receiver,IntentFilter(HybridNetwork.AP_STATE_ACTION))}catch(_:RuntimeException){return}
  own(Closeable{runCatching{unregisterReceiver(receiver)}},"hotspot_recovery_receiver")
 }

 // Called under resourceLock. Let the old session and its audio drain while the radio returns.
 private fun startHotspotRepair(previous:AirPlaySession):Boolean{
  if(useP2p||closed||failed.get()||previous.isClosingNormally||wifiRecovery!=null||!LegacyCarHotspot.isAutoStartEnabled(this))return false
  if(hotspotRepair!=null)return true
  val target=wifiRecoveryTarget(previous)?:return false
  val binding=runtimeHotspot?:return false
  if(!HybridNetwork.isApStoppingOrOff(this))return false
  val now=SystemClock.elapsedRealtime()
  if(lastHotspotRepairAt!=Long.MIN_VALUE&&now-lastHotspotRepairAt<HOTSPOT_REOPEN_COOLDOWN_MS){
   recordRuntimeLink("hotspot_reopen_skipped","reason=cooldown")
   return false
  }
  val repair=HotspotRepair(previous,target,binding,now)
  hotspotRepair=repair
  lastHotspotRepairAt=now
  recordRuntimeLink("hotspot_reopen_started","same_controller=true")
  captureRuntimeHotspot("hotspot_reopen_started",previous.remoteAddress)
  note("WIFI_RECOVERING","正在恢复车载热点")
  connectingWorkers.incrementAndGet()
  val worker=Thread({
   try{
    val restored=restoreHotspotBinding(repair)
    synchronized(resourceLock){
     if(closed||hotspotRepair!==repair)return@synchronized
     runtimeHotspot=restored.binding
     repair.restoredAtNs=restored.readyAtNs
     repair.proofUntilMs=SystemClock.elapsedRealtime()+HOTSPOT_SESSION_PROOF_MS
     repair.discoveryReady=restored.discoveryReady
     repair.mayKeepOriginal=repair.target.peer !is Inet6Address ||
      (repair.binding.wifiLinkLocalIpv6==restored.binding.wifiLinkLocalIpv6&&
       repair.binding.wifiLinkLocalIpv6?.scopeId==restored.binding.wifiLinkLocalIpv6?.scopeId)
     recordRuntimeLink("hotspot_reopen_ready","durationMs=${SystemClock.elapsedRealtime()-repair.startedAt} scopeChanged=${repair.binding.wifiLinkLocalIpv6?.scopeId!=restored.binding.wifiLinkLocalIpv6?.scopeId} discoveryReady=${repair.discoveryReady}")
     captureRuntimeHotspot("hotspot_reopen_ready",repair.target.peer)
     if(repair.mayKeepOriginal&&!repair.previous.isClosed)repair.previous.prepareLinkRecovery()
     advanceHotspotRepair(repair)
     if(hotspotRepair===repair&&repair.mayKeepOriginal){
      watchdog.schedule({synchronized(resourceLock){advanceHotspotRepair(repair)}},HOTSPOT_SESSION_PROOF_MS,TimeUnit.MILLISECONDS)
     }
    }
   }catch(error:Exception){
    synchronized(resourceLock){
     if(!closed&&hotspotRepair===repair){
      hotspotRepair=null
      val reason=(error as? LegacyCarHotspotException)?.reason?:"endpoint_rebind_failed"
      recordRuntimeLink("hotspot_reopen_fallback","reason=$reason")
      fail(error,"wireless_hotspot_reopen",allowRuntimeRetry=LegacyCarHotspot.isAutoStartEnabled(this)&&
       reason!="ap_station_not_disabled"&&reason!="ap_cancelled")
     }
    }
   }finally{connectingWorkers.decrementAndGet()}
  },"diplay-hotspot-recovery").apply{isDaemon=true}
  repair.worker=worker
  try{worker.start()}catch(_:RuntimeException){
   connectingWorkers.decrementAndGet();hotspotRepair=null;return false
  }
  watchdog.schedule({synchronized(resourceLock){
   if(!closed&&hotspotRepair===repair&&repair.restoredAtNs==0L){
    recordRuntimeLink("hotspot_reopen_fallback","reason=worker_timeout")
    fail(IOException("恢复车载热点超时"),"wireless_hotspot_reopen",allowRuntimeRetry=true)
   }
  }},LegacyCarHotspot.RECOVERY_AP_STOP_TIMEOUT_MS+HOTSPOT_REOPEN_LIMIT_MS+2500L,TimeUnit.MILLISECONDS)
  return true
 }

 private fun restoreHotspotBinding(repair:HotspotRepair):RestoredHotspot{
  fun cancelled()=closed||hotspotRepair!==repair||!LegacyCarHotspot.isAutoStartEnabled(this)
  LegacyCarHotspot.prepare(this,HOTSPOT_REOPEN_LIMIT_MS,::cancelled,recovering=true){stage->
   recordRuntimeLink("hotspot_radio_step","result=$stage")
  }
  val readyAtNs=System.nanoTime()
  val waitForIpv6=repair.target.peer is Inet6Address
  val until=SystemClock.elapsedRealtime()+850L
  var current=HybridNetwork.requireCurrentConnection(this,repair.binding,verifyIpv6=false)
  while(waitForIpv6&&current.wifiLinkLocalIpv6==null&&SystemClock.elapsedRealtime()<until&&!cancelled()){
   Thread.sleep(75L)
   current=HybridNetwork.requireCurrentConnection(this,repair.binding,verifyIpv6=false)
  }
  check()
  if(cancelled())throw InterruptedException("Hotspot recovery cancelled")
  if(waitForIpv6&&current.wifiLinkLocalIpv6==null)throw IOException("CarPlay IPv6 address did not return")
  // The established iAP2 endpoint contains the original wire addresses, without zone indices.
  val originalIpv6=repair.binding.wifiLinkLocalIpv6
  val compatible=originalIpv6==null||originalIpv6==current.wifiLinkLocalIpv6
  val rebound=compatible&&runCatching{refreshHotspotDiscovery?.invoke(current,waitForIpv6)==true}.getOrDefault(false)
  return RestoredHotspot(current,readyAtNs,rebound)
 }

 private fun advanceHotspotRepair(repair:HotspotRepair){
  if(closed||hotspotRepair!==repair||repair.restoredAtNs==0L)return
  val previous=repair.previous
  if(session===previous&&!previous.isClosed){
   val evidence=previous.runtimeEvidence()
   if(repair.mayKeepOriginal&&maxOf(evidence.lastInboundTrafficNs,evidence.lastTimingReplyNs)>repair.restoredAtNs){
    hotspotRepair=null
    runtimeHealth.begin(System.nanoTime())
    recordRuntimeLink("hotspot_original_session_resumed","durationMs=${SystemClock.elapsedRealtime()-repair.startedAt}")
    requestRecoveryKeyFrame(previous,repair.restoredAtNs)
    note("VIDEO_READY","已恢复车载热点和原投屏会话")
    return
   }
   if(repair.mayKeepOriginal&&SystemClock.elapsedRealtime()<repair.proofUntilMs)return
   if(closingStalledSession!==previous&&previous.interruptTransportForRecovery())closingStalledSession=previous
   watchHotspotSessionCleanup(repair)
   return
  }
  if(session===previous||sessions.contains(previous)){
   watchHotspotSessionCleanup(repair)
   return // Its teardown still owns media and its iAP tunnel.
  }
  hotspotRepair=null
  if(!repair.discoveryReady){failWifiRecovery("endpoint_rebind_failed");return}
  val remote=repair.target.peer
  val scope=runtimeHotspot?.wifiLinkLocalIpv6?.scopeId
  val peer=if(remote is Inet6Address&&scope!=null)Inet6Address.getByAddress(null,remote.address,scope) else remote
  val target=WifiRecoveryTarget(peer,repair.target.controllerId,repair.target.bluetoothAddress)
  if(!startWifiRecovery(target,hotspotReopened=true))failWifiRecovery("hotspot_ip_resume_unavailable")
 }

 private fun watchHotspotSessionCleanup(repair:HotspotRepair){
  if(repair.cleanupWatchArmed)return
  repair.cleanupWatchArmed=true
  watchdog.schedule({synchronized(resourceLock){
   if(!closed&&hotspotRepair===repair){
    if(repair.previous.isClosingNormally)shutdown()
    else failWifiRecovery("hotspot_old_session_timeout")
   }
  }},2500L,TimeUnit.MILLISECONDS)
 }

 private fun requestRecoveryKeyFrame(active:AirPlaySession,afterNs:Long){
  if(surface?.isValid!=true||active.runtimeEvidence().lastPrimaryVideoNs>afterNs||!runtimeKeyframePending.compareAndSet(false,true))return
  Thread({
   try{
    if(!closed&&session===active&&surface?.isValid==true){
     val result=runCatching{active.requestRecoveryKeyFrame(afterNs)}
     val error=result.exceptionOrNull()
     recordRuntimeLink("same_session_keyframe",if(error==null)"sent=${result.getOrNull()}" else "error=${error.javaClass.simpleName}")
    }
   }finally{runtimeKeyframePending.set(false)}
  },"airplay-resume-keyframe").apply{isDaemon=true;start()}
 }

 private fun checkRuntimeLink() {
  synchronized(resourceLock){
   hotspotRepair?.let{advanceHotspotRepair(it);return}
   val active=session ?: return
   if(closed||failed.get()||!videoReady||active.isClosed||closingStalledSession===active)return
   val recovery=wifiRecovery
   if(recovery!=null){
    val elapsed=SystemClock.elapsedRealtime()
    if(surface?.isValid!=true)recovery.firstFrameDeadlineMs=0L
    else if(recovery.firstFrameDeadlineMs==0L)recovery.firstFrameDeadlineMs=elapsed+WIFI_RECONNECT_FIRST_FRAME_MS
    else if(elapsed>=recovery.firstFrameDeadlineMs){
     failWifiRecovery("first_frame_timeout")
     return
    }
   }
   val now=System.nanoTime()
   var state=active.runtimeEvidence()
   fun assess()=runtimeHealth.check(now,state.validatedTimingReplies,state.lastTimingReplyNs,
    state.lastInboundTrafficNs,state.timingProbeAttempts)
   var decision=assess() ?: return
   if(decision.action==WirelessSessionHealth.Action.RECONNECT){
    // A read racing this tick can save the existing sockets.
    state=active.runtimeEvidence()
    decision=assess() ?: return
   }
   fun age(mark:Long)=if(mark==0L)-1L else TimeUnit.NANOSECONDS.toMillis((now-mark).coerceAtLeast(0))
   val summary="timingReplies=${state.validatedTimingReplies} timingAgeMs=${age(state.lastTimingReplyNs)} inboundAgeMs=${age(state.lastInboundTrafficNs)} probeAttempts=${state.timingProbeAttempts}"
   when(decision.action){
    WirelessSessionHealth.Action.PROBE -> {
     captureRuntimeHotspot("link_probe",active.remoteAddress)
     val expedited=active.prepareLinkRecovery()
     recordRuntimeLink("link_probe","$summary expedited=$expedited")
    }
    WirelessSessionHealth.Action.RESUME -> {
     recordRuntimeLink("same_session_resumed",summary)
     requestRecoveryKeyFrame(active,decision.lastActivityBeforeGapNs)
    }
    WirelessSessionHealth.Action.RECONNECT -> {
     if(startHotspotRepair(active))return
     if(recovery!=null){
      failWifiRecovery("replacement_transport_stalled")
     }else if(wifiRecoveryTarget(active)!=null&&active.interruptTransportForRecovery()){
      closingStalledSession=active
      recordRuntimeLink("releasing_stalled_session",summary)
      watchdog.schedule({
       synchronized(resourceLock){
        if(!closed&&session===active&&closingStalledSession===active){
         fail(IOException("投屏旧会话未及时释放"),"wireless_stalled_session_cleanup",
          allowRuntimeRetry=true,runtimeSummary=summary)
        }
       }
      },2500L,TimeUnit.MILLISECONDS)
     }else if(!active.isClosingNormally){
      fail(IOException("iPhone 投屏连接无响应"),"wireless_liveness_timeout",
       allowRuntimeRetry=true,runtimeSummary=summary)
     }
    }
   }
  }
 }
 private fun checkFirstFrame(){synchronized(resourceLock){
  if(closed||videoReady||session==null){firstFrameWatch?.cancel(false);firstFrameWatch=null;firstFrameExpectedAt=0L;return}
  if(session?.hasRenderedVideo==true)return
  if(surface?.isValid!=true){firstFrameExpectedAt=0L;return}
  val now=SystemClock.elapsedRealtime()
  if(firstFrameExpectedAt==0L){firstFrameExpectedAt=now;return}
  if(now-firstFrameExpectedAt>=FIRST_FRAME_WAIT_MS){
   val pending=if(sink?.receivedVideo==true)"video packets received" else "no video packets received"
   recordStartupStep("video_first_frame_timeout","first_video",pending)
   fail(IOException("First picture timed out after ${FIRST_FRAME_WAIT_MS/1000} seconds ($pending)"),"first_video_watchdog")
  }
 }}
 // Called by the service watchdog while holding resourceLock.
 private fun firstPictureSubmitted(renderedAtMs:Long){
  if(closed||failed.get()||videoReady)return
  videoReady=true
  runtimeHealth.begin(System.nanoTime())
  runtimeWatch=watchdog.scheduleWithFixedDelay(::checkRuntimeLink,500,500,TimeUnit.MILLISECONDS)
  refreshMediaButtonOwner()
  firstFrameWatch?.cancel(false);firstFrameWatch=null
  activeDiagnosticStage="video_active"
  CrashEvidence.updateStage(this,connectionId,"video_active",usbLinked,true,lastIap2MessageId)
  startup.firstFrame(renderedAtMs);reportStartup("first_wifi_frame")
  captureRuntimeHotspot("first_frame")
  note("VIDEO_READY","Wi-Fi AirPlay video decoded; press Back to show controls")
 }
 private val listener=object:AirPlaySessionListener {
  override fun claimProjection(session:AirPlaySession):Boolean=synchronized(resourceLock){
   if(closed||failed.get()||session.isClosed||!sessions.contains(session))return@synchronized false
   // Preflight can run in parallel, but a retained session cannot take over before Bluetooth iAP2 is ready.
   if(!runtimePeerVerified)return@synchronized false
   if(!matchesAirPlayController(session)){
    if(!previousAirPlayControllerIgnored){
     previousAirPlayControllerIgnored=true
     recordPhoneConnection("previous_airplay_controller_ignored")
    }
    // Release a stale authenticated session's slot without stopping the new phone's listener.
    runCatching{watchdog.execute{session.close()}}
    return@synchronized false
   }
   val recovery=wifiRecovery
   if(recovery!=null&&(session.remoteAddress!=recovery.peer||session.controllerId!=recovery.controllerId))return@synchronized false
   if(projectionOwner!=null&&projectionOwner!==session)return@synchronized false
   projectionOwner=session
   true
  }
  override fun onSessionActive(session:AirPlaySession){synchronized(resourceLock){
   if(closed||session.isClosed||projectionOwner!==session)return
   val recovery=wifiRecovery
   if(recovery!=null&&(session.remoteAddress!=recovery.peer||session.controllerId!=recovery.controllerId))return
   if(this@WiredService.session!==session){
    firstFrameExpectedAt=0L
    if(videoReady)runtimeHealth.begin(System.nanoTime())
   }
   this@WiredService.session=session;sessionGeneration++;lastFailure=null;wiredStartWatchdog?.sessionActive()
    syncVehicleNightMode()
   resourceLock.notifyAll()
   refreshMediaButtonOwner()
   confirmBootPeerIfProjected(session)
   wifiHandoff?.stopPhoneHandoff()
   maybeReleaseBluetoothBootstrap()
   if(recovery!=null){
    wifiHandoff?.cancelPhoneResume()
    if(session.hasRenderedVideo)finishWifiRecovery("first_frame")
   }
   if(!videoReady&&session.hasRenderedVideo){onVideoFrameRendered(session);return}
   if(!videoReady){
    phase("first_video")
    if(firstFrameWatch==null)firstFrameWatch=watchdog.scheduleWithFixedDelay(::checkFirstFrame,1,1,TimeUnit.SECONDS)
    note("AIRPLAY_ACTIVE","CarPlay协议会话已建立，等待画面和声音")
   }
  }}
  override fun onVideoFrameRendered(session:AirPlaySession){
   runCatching{watchdog.execute{synchronized(resourceLock){
    if(closed||session.isClosed||this@WiredService.session!==session)return@synchronized
    firstPictureSubmitted(session.firstVideoSubmittedAtMs)
    confirmBootPeerIfProjected(session)
    if(wifiRecovery!=null)finishWifiRecovery("first_frame")
   }}}
  }
  override fun onSessionEnded(session:AirPlaySession){synchronized(resourceLock){
   if(closingStalledSession===session)closingStalledSession=null
   if(projectionOwner===session)projectionOwner=null
   if(bluetoothHandoffRequestedBy===session)bluetoothHandoffRequestedBy=null
   val wasActive=this@WiredService.session===session;if(wasActive){this@WiredService.session=null;sessionGeneration++;refreshMediaButtonOwner()};val endedGeneration=sessionGeneration;sessions.remove(session)
   if(calibrationSession===session){calibrationSession=null;sink?.cancelVideoCalibration()}
   if(closed)return
   val protocolFailed=session.protocolFailure!=null
   if(wasActive){
    val lostTransport=session.runtimeEvidence().transportIoFailure
    if(videoReady)captureRuntimeHotspot(when{
     session.isClosingNormally -> "session_ended_normal"
     lostTransport -> "session_ended_transport_io"
     protocolFailed -> "session_ended_protocol"
     else -> "session_ended_other"
    },session.remoteAddress)
    if(session.isClosingNormally){shutdown();return}
    hotspotRepair?.takeIf{it.previous===session}?.let{advanceHotspotRepair(it);return}
    if(wifiRecovery!=null){
     failWifiRecovery(if(protocolFailed)"replacement_protocol_failed" else "replacement_control_ended")
     return
    }
    if(startHotspotRepair(session))return
    if(lostTransport&&wifiRecoveryTarget(session)?.let{startWifiRecovery(it)}==true)return
    if(!lostTransport&&protocolFailed){
     fail(IOException("protocol failure"),"airplay_session_protocol")
     return
    }
    note("AIRPLAY_ENDED","手机会话已结束")
    runCatching{watchdog.schedule({
     synchronized(resourceLock){
      if(!closed&&sessionGeneration==endedGeneration&&this@WiredService.session==null){
       if(lostTransport){
        val failure=IOException("iPhone 投屏控制连接已断开")
        val wifi=runtimeHotspot
        if(wifi==null||!tryAlternatePhone(failure,"wireless_transport_io",wifi)){
         fail(failure,"wireless_transport_io",allowRuntimeRetry=true)
        }
       }
       else fail(IOException("session ended"),"airplay_session_ended")
      }
     }
    },2,TimeUnit.SECONDS)}
   }
   else if(protocolFailed){recordStartupStep("airplay_candidate_protocol_failed","airplay_handshake","Waiting for another AirPlay control connection")}
  }}
  override fun onCommand(session:AirPlaySession,type:String,params:Map<String,Any?>){synchronized(resourceLock){
   if(closed||projectionOwner!==session)return
   if(type=="modesChanged")sink?.speechMode(params)
   if(isBluetoothHandoffCommand(type)&&session.controllerId!=null&&
       bluetoothBootstrapStop!=null&&bluetoothHandoffRequestedBy!==session){
    bluetoothHandoffRequestedBy=session
    recordRuntimeLink("bluetooth_handoff_requested","tunnelOpen=${wifiHandoff?.tunnelReadyFor(session)==true}")
    maybeReleaseBluetoothBootstrap()
   }
  }}
  override fun onDebugLog(message:String){
   if(message.startsWith("airplay vehicle night_mode_written ")){
    recordVehicleIntegration("night_mode_written",message.removePrefix("airplay vehicle night_mode_written "))
   }
   log("AIRPLAY",message)
  }
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
 private fun recordStartupStep(event:String,stage:String,summary:String){try{
  if(projectionStepCount.incrementAndGet()>64)return
  MainActivity.record(this,"PROJECTION_STARTUP_STEP",JSONObject().put("eventType",event).put("stage",stage).put("diagnosticSummary",summary).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))
 }catch(_:Exception){}}
 fun log(stage:String,text:String){if(text.startsWith("TRACE ")||stage=="CONTROL"&&text.startsWith("iap2 rx=")&&text.substringAfter("0x","").toIntOrNull(16)?.let{DiagnosticPolicy.skipFrame(it)}==true)return
  val videoWindow=text.startsWith("Receive: video eventType=video_rx_window ")||text.startsWith("video_progress ")
  val projectionStep=if(videoWindow)null else when(stage){
   "AIRPLAY"->airPlayStartupStep.find(text)?:videoStartupStep.find(text)
   "MEDIA"->videoStartupStep.find(text)
   else->null
  }
  if(projectionStep!=null){
   val event=projectionStep.groupValues[1]
   val eventStage=if(event.startsWith("video_")||event.startsWith("screen_")||event=="first_frame_rendered")"first_video" else "airplay_handshake"
   recordStartupStep(event,eventStage,text.takeIf{startupDetail.matches(it)}?:event)
   return
  }
  val metric=when {stage=="MEDIA"&&(text.startsWith("video_progress ")||text.startsWith("audio_progress ")||text.startsWith("audio_checkpoint "))->(if(text.startsWith("audio_checkpoint "))"audio_progress" else text.substringBefore(' '))+if(text.startsWith("audio_progress ")||text.startsWith("audio_checkpoint ")) ":"+text.substringAfter("role=").substringBefore(' ') else "";stage=="AIRPLAY"&&text.startsWith("audio_modes ")->"audio_modes";stage=="MEDIA"&&text.startsWith("microphone_")->"microphone";stage=="AIRPLAY"&&text.startsWith("Receive:")->text.substringBefore(" packets=").take(80);else->null}
  val detail=if(videoWindow)"$text requestedFps=$selectedFps" else text
  if(metric!=null&&(videoWindow||latestPerformance.containsKey(metric)||latestPerformance.size<12))latestPerformance[metric]="ageMs=${SystemClock.elapsedRealtime()-bornAt} "+detail.take(1200)
  val eventType=when{
   stage=="MEDIA"&&text.startsWith("microphone_send_timing ") -> "MICROPHONE_UPLINK"
   stage=="MEDIA"&&text.startsWith("microphone_input_levels ") -> "MICROPHONE_INPUT_LEVELS"
   stage=="MEDIA"&&text.startsWith("audio_speech_playout ") -> "AUDIO_SPEECH_PLAYOUT"
   stage=="AIRPLAY"&&text.startsWith("audio eventType=audio_rx_window ")&&
       (text.contains(" role=call ")||text.contains(" role=assistant ")||text.contains(" role=navigation ")) ->
       "AUDIO_SPEECH_RECEIVE"
   else -> stage
  }
  try{MainActivity.record(this,eventType,JSONObject().put("detail",detail.take(1200)).put("connection",connectionId).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}}
 fun note(stage:String,text:String,bootRetryable:Boolean=false,runtimeRetryDevice:UsbDevice?=null,
          unavailableOnRetry:ArrayList<String>?=null){
  progressStage=stage;progressMessage=text
  if(stage=="PHONE_START"&&text.startsWith("PHONE_PROBE http://"))activeProbeUrl=text.substring("PHONE_PROBE ".length).trim()
  else if(stage=="AIRPLAY_HANDSHAKE"||stage=="AIRPLAY_ACTIVE"||stage=="VIDEO_READY"||stage=="FAILED"||stage=="DISCONNECTED"||stage=="STOPPED")activeProbeUrl=null
  if(stage=="FAILED")lastFailure=text
  log(stage,text)
  val update=Intent("local.headunit.diplay.STATUS").setPackage(packageName)
   .putExtra("message",text).putExtra("stage",stage).putExtra("connection_id",connectionId)
   .putExtra("boot_retryable",bootRetryable)
  if(runtimeRetryDevice!=null)update.putExtra("runtime_retry_device",runtimeRetryDevice)
  if(unavailableOnRetry!=null)update.putStringArrayListExtra(MainActivity.EXTRA_UNAVAILABLE_IPHONES,unavailableOnRetry)
    .putExtra("request_started_at",requestStartedAt)
  sendBroadcast(update)
 }
 private fun safeDiagnosticType(t:Throwable):String { val value=t.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"),"").take(48);return value.ifEmpty{"Throwable"} }
 private fun mfiFailureReason(message:String?):String{
  val text=message.orEmpty().lowercase(Locale.US)
  return when{
   "was not acknowledged" in text -> "iap2_packet_unacknowledged"
   "sent iap2 reset" in text -> "iap2_peer_reset"
   "authenticationfailed" in text -> "mfi_rejected_by_phone"
   "timed out waiting for iap2 mfi" in text -> "mfi_response_timeout"
   "timed out sending iap2 mfi" in text -> "mfi_send_timeout"
   "unexpected iap2 mfi message" in text -> "mfi_unexpected_message"
   "link ended" in text || "closed" in text -> "iap2_link_closed"
   else -> "mfi_other_failure"
  }
 }
 private fun safeFailureMessage(value:String?):String {
  if(value.isNullOrBlank())return "failure_detail_redacted"
  val message=value.lowercase(Locale.US)
  return when {
   "timed out" in message || "timeout" in message -> "timeout"
   "permission" in message || "denied" in message || "not authorized" in message -> "permission_denied"
   "reset by peer" in message || "peer reset" in message -> "peer_reset"
   "not found" in message || "missing" in message || "no such" in message -> "resource_missing"
   "invalid" in message || "protocol" in message || "malformed" in message -> "protocol_error"
   "unsupported" in message || "not supported" in message -> "unsupported"
   "closed" in message || "disconnected" in message || "broken pipe" in message -> "link_closed"
   else -> "failure_detail_redacted"
  }
 }
 private fun recordPreparation(step:String,result:String,durationMs:Long,failure:Throwable?=null){try{
  val row=JSONObject().put("stage",step).put("result",result).put("durationMs",durationMs.coerceAtLeast(0)).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt).put("iap2Profile",hybridIap2Profile).put("bluetoothGroup17Enabled",hybridBluetoothComponentEnabled).put("wifiCredentialsIncluded",hybridWifiCredentialsIncluded)
  if(failure!=null){var root:Throwable=failure;var depth=0;while(root.cause!=null&&root.cause!==root&&depth++<8)root=root.cause!!;val reason=safeFailureMessage(root.message);row.put("errorType",safeDiagnosticType(failure)).put("causeType",safeDiagnosticType(root)).put("diagnosticSummary","step=$step result=$result error=${safeDiagnosticType(root)} reason=$reason".take(500))}
  MainActivity.record(this,"HYBRID_PREPARE_STEP",row)
 }catch(_:Throwable){}}
 private fun <T> tracedPreparation(step:String,action:()->T):T{val startedAt=SystemClock.elapsedRealtime();return try{action().also{recordPreparation(step,"ok",SystemClock.elapsedRealtime()-startedAt)}}catch(failure:Throwable){recordPreparation(step,"failed",SystemClock.elapsedRealtime()-startedAt,failure);throw failure}}
 private fun recordFailure(e:Throwable,origin:String,runtimeSummary:String?){try{
  var root=e;var count=0;while(root.cause!=null&&root.cause!==root&&count++<8)root=root.cause!!
  val rejected=root as? Iap2IdentificationException.Rejected
  val rejectedIds=rejected?.parameterIds?.sorted()?.joinToString("+"){ "0x"+it.toString(16).padStart(4,'0') }
    val details=JSONObject().put("origin",origin).put("phase",activeDiagnosticStage).put("errorType",safeDiagnosticType(e)).put("causeType",safeDiagnosticType(root)).put("failureMessage",safeFailureMessage(root.message?:e.message)).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt).put("videoReady",videoReady).put("usbLinked",usbLinked).put("sessionCount",sessions.size).put("activeSession",session!=null).put("wifiConnected",wirelessNetworkReady()).put("wifiIpReady",wifiIpReady).put("airplayTcpAccepted",airplayTcpAccepted.get()).put("networkBackend",networkBackend()).put("iap2Profile",hybridIap2Profile).put("bluetoothGroup17Enabled",hybridBluetoothComponentEnabled).put("wifiCredentialsIncluded",hybridWifiCredentialsIncluded)
  if(lastIap2MessageId>=0)details.put("lastIap2MessageId",lastIap2MessageId)
  if(activeDiagnosticStage=="mfi_authentication")details.put("reason",mfiFailureReason(root.message?:e.message))
  if(!rejectedIds.isNullOrEmpty())details.put("diagnosticSummary","rejectedParameterIds=$rejectedIds")
  if(runtimeSummary!=null)details.put("diagnosticSummary",runtimeSummary)
  MainActivity.record(this,"CONNECTION_FAILURE",details)
 }catch(_:Throwable){}}
 private fun fail(e:Throwable,origin:String="connect_worker",retryPhone:Boolean=false,
                   allowRuntimeRetry:Boolean=false,runtimeSummary:String?=null,unavailableOnRetry:ArrayList<String>?=null){
  if(closed||!failed.compareAndSet(false,true))return
  if(videoReady)captureRuntimeHotspot("failure_$origin")
  recordFailure(e,origin,runtimeSummary)
  var cause:Throwable?=e
  val chain=ArrayList<String>()
  while(cause!=null&&chain.size<4){
   chain.add(cause.javaClass.simpleName+": "+(cause.message?:"connection failed").take(160));cause=cause.cause
  }
  val retryDevice=usbBtDevice?.takeIf{unavailableOnRetry!=null||(allowRuntimeRetry&&videoReady&&runtimePeerVerified)}
  try{note("FAILED",chain.joinToString(" → "),bootAutoConnect&&retryPhone&&!videoReady,retryDevice,unavailableOnRetry)}finally{shutdown()}
 }

 private fun tryAlternatePhone(e:IOException,origin:String,wifi:HybridNetworkSnapshot):Boolean=synchronized(resourceLock){
  val phone=phoneForAlternateRetry?:return@synchronized false
  if(closed||failed.get()||videoReady||session!=null||projectionOwner!=null||selectedAirPlayResponding()||
      !WirelessPhoneReconnect.canContinueSelection(requestStartedAt,SystemClock.elapsedRealtime())||
      runCatching{currentWirelessBinding(wifi,verifyIpv6=false)}.isFailure)return@synchronized false
  recordPhoneConnection("retry_other_bonded_phone",source=origin)
  fail(e,origin,unavailableOnRetry=ArrayList(unavailablePhones+phone))
  true
 }
 private fun wifiHandoffFailure(detail:String,cause:IOException):IOException {
  if(useP2p)return IOException("P2P_CONNECTION_FAILED ${detail}请保持 iPhone 的蓝牙和 Wi-Fi 已开启，并提交日志。",cause)
  return IOException("WIFI_CHECK_SETTINGS ${detail}请确认车载热点仍开启，可在 iPhone 上手动加入该热点后重试。",cause)
 }
  override fun onCreate(){
   super.onCreate()
   usb=getSystemService(Context.USB_SERVICE) as UsbManager
   audioObserver=OemAudioObserver(this)
   wheelControl=WheelControl(this,object:WheelControl.Sink {
    override fun session():Any?=this@WiredService.session?.takeIf{!closed&&videoReady&&it.canSendInput()}
    override fun send(token:Any,action:Int):Boolean {
     val current=this@WiredService.session
     if(closed||current==null||current!==token||current.isClosed)return false
     return when(action){1->current.sendMedia(5);2->current.sendMedia(4);3->current.sendTelephony(1);4->current.sendTelephony(3);5->current.invokeSiri();else->false}
    }
   })
   val signals=LegacyVehicleSignals(this){snapshot->
    if(snapshot.night!=lastVehicleNight){
     lastVehicleNight=snapshot.night
     syncVehicleNightMode()
    }
   }
   vehicleSignals=signals
   live=this
   if(signals.isSupported())signals.start()
  }

  private fun syncVehicleNightMode(){
   if(closed||session==null||!themeQueued.compareAndSet(false,true))return
   try{
    themeWorker.execute {
     themeQueued.set(false)
     val target=session?.takeUnless{it.isClosed}?:return@execute
     val night=vehicleSignals?.snapshot()?.night?:return@execute
     if(closed||session!==target||themeSession===target&&themeValue==night)return@execute
     // AirPlay retains a pending theme until its event channel opens.
     val written=target.setNightMode(night)
     themeSession=target
     themeValue=night
     if(!written&&!closed&&!target.isClosed)recordVehicleIntegration("night_mode_pending","state=${if(night)"night" else "day"}")
    }
   }catch(_:RejectedExecutionException){themeQueued.set(false)}
  }

  private fun recordVehicleIntegration(stage:String,summary:String){
   val event=if(stage=="yaw_estimate")"VEHICLE_YAW_ESTIMATE" else "VEHICLE_INTEGRATION"
   try{MainActivity.record(this,event,JSONObject().put("stage",stage)
     .put("elapsedMs",SystemClock.elapsedRealtime()-bornAt).put("diagnosticSummary",summary))}
   catch(_:Exception){}
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
  if(started)return START_NOT_STICKY
  useP2p=LegacyCarHotspot.usesWifiDirect(this)
  activeProbeUrl=null;wifiIpReady=false;usbBatchId="";usbProfile=UsbStartupProfile.DEFAULT;requestStartedAt=(i?.getLongExtra("request_started_at",bornAt)?:bornAt).takeIf{it in 1..bornAt}?:bornAt;vpnWaitMs=0;connectionId=i?.getStringExtra("connection_id").orEmpty();usbBtDevice=i?.getParcelableExtra<UsbDevice>("usb_bt_device")
  if(cleanupCount.get()>0||connectingWorkers.get()>0||MediaWorkers.busy()){fail(IOException("Previous connection is still releasing; retry shortly"),"previous_connection_cleanup");return START_NOT_STICKY}
  started=true;registerDetachReceiver()
  if(!useP2p)registerHotspotRecoveryReceiver()
  CrashEvidence.begin(this,connectionId)
  val benchmarkFps=i?.getIntExtra(EXTRA_BENCHMARK_FPS,0)?.takeIf{it in PlaybackOptions.rates()}
  benchmarkConnection=benchmarkFps!=null
  selectedFps=benchmarkFps?:PlaybackOptions.read(this)
  log("VIDEO_CONFIG","requestedFps=$selectedFps width=${ProjectionConfig.WIDTH} height=${ProjectionConfig.HEIGHT}")
  log("CONNECT_BUILD",runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrDefault("unknown"))
  note("CONNECT_START",if(useP2p)"正在准备 Wi-Fi Direct 局域网和 USB 蓝牙" else "正在检查车载热点并准备 USB 蓝牙")
  log("HYBRID_TRANSPORT","transportMode=${wirelessTransportMode()} sdk=${Build.VERSION.SDK_INT} nativeNcmUsed=false vpnUsed=false donglePresent=${usbBtDevice!=null}")
  watchdog.schedule({synchronized(resourceLock){
   if(closed||videoReady)return@synchronized
   val current=session
   if(current!=null&&!current.isClosed&&current.controllerId!=null&&
       current.runtimeEvidence().lastPrimaryAudioNs!=0L){
    recordStartupStep("active_audio_retained","first_video","surfaceValid=${surface?.isValid==true}")
    // Once audio is confirmed, leave the separate visible first-frame watchdog in charge.
   }else fail(IOException("startup timeout"),"startup_watchdog")
  }},180,TimeUnit.SECONDS)
  val notification=Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("DiPlay CarPlay").setContentText("正在连接，可在应用内停止").build();startForeground(1702,notification)
  val reselectPhone=i?.getBooleanExtra(MainActivity.EXTRA_RESELECT_PHONE,false)==true
  if(!reselectPhone)i?.getStringArrayListExtra(MainActivity.EXTRA_UNAVAILABLE_IPHONES).orEmpty()
   .filterTo(unavailablePhones,WirelessPhoneReconnect::isBluetoothAddress)
  continuingPhoneSelection=unavailablePhones.isNotEmpty()
  bootAutoConnect=i?.getBooleanExtra("boot_auto_connect",false)==true&&!reselectPhone
  runtimeReconnect=i?.getBooleanExtra("runtime_reconnect",false)==true&&!reselectPhone
  try{work.execute{synchronized(resourceLock){if(closed)return@execute;connectingWorkers.incrementAndGet()};try{connect(reselectPhone)}catch(e:Throwable){
   val outbound=e.cause as? UsbBtHostStack.OutboundFailure
   val retryPhone=activeDiagnosticStage=="bluetooth_phone_connect"&&outbound!=null&&
    WirelessPhoneReconnect.isTransientAclFailure(outbound.stage,outbound.status)
   fail(e,"connect_worker",retryPhone)
  }finally{connectingWorkers.decrementAndGet()}}}catch(e:Throwable){fail(e)};return START_NOT_STICKY
 }
 private fun check(){if(closed)throw InterruptedException("Connection cancelled")}
 private fun signalProjectionSurface(){synchronized(projectionSurfaceSignal){projectionSurfaceSignal.notifyAll()}}
 private fun awaitProjectionSurface(){
  check()
  if(surface?.isValid==true)return
  val deadline=SystemClock.elapsedRealtime()+SURFACE_READY_WAIT_MS
  phase("waiting_projection_surface")
  synchronized(projectionSurfaceSignal){
   while(surface?.isValid!=true){
    check()
    val remaining=deadline-SystemClock.elapsedRealtime()
    if(remaining<=0L)throw IOException("等待车机投屏画面窗口超时。")
    projectionSurfaceSignal.wait(remaining)
   }
  }
  check()
 }
 private fun own(c:Closeable,name:String=c.javaClass.simpleName){synchronized(resourceLock){if(!closed){handles.add(c);resourceNames[c]=name;return}};c.close();throw InterruptedException("Connection cancelled")}
 private var phaseAt=SystemClock.elapsedRealtime()
 private var previousPhase="start"
 @Volatile private var activeDiagnosticStage="service_start"
 @Volatile private var progressStage="CONNECT_START"
 @Volatile private var progressMessage=""
 @Synchronized private fun phase(name:String){val now=SystemClock.elapsedRealtime();activeDiagnosticStage=name;CrashEvidence.updateStage(this,connectionId,name,usbLinked,videoReady,lastIap2MessageId);startup.advance(name,now);log("CONNECT_TIMING","completed=$previousPhase durationMs=${now-phaseAt} next=$name");phaseAt=now;previousPhase=name}
 private fun availabilityState(line:String,key:String):String {
  val value=line.split(' ').firstOrNull{it.startsWith("$key=")}?.substringAfter('=')
  return when(value){"true"->"available";"false"->"unavailable";else->"unknown"}
 }
 private fun recordCarPlayAvailability(line:String){
  try{MainActivity.record(this,"HYBRID_CARPLAY_AVAILABILITY",JSONObject().put("stage","carplay_availability").put("wiredState",availabilityState(line,"wired")).put("wirelessState",availabilityState(line,"wireless")).put("themeAssetsState",availabilityState(line,"themeAssets")).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
 }
 private fun recordWirelessAvailability(line:String){
  try{MainActivity.record(this,"HYBRID_WIRELESS_UPDATE",JSONObject().put("stage","wireless_carplay_update").put("wirelessAvailable",line.substringAfter("available=","").substringBefore(' ').equals("true",true)).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
 }
 private fun recordPhoneConnection(stage:String,candidateCount:Int?=null,source:String?=null,
                                   retries:Int?=null,retryInMs:Long?=null,status:Int?=null){
  try{
   val row=JSONObject().put("stage",stage).put("strategy","active_iphone").put("elapsedMs",SystemClock.elapsedRealtime()-bornAt)
   if(candidateCount!=null)row.put("candidateCount",candidateCount)
   if(source!=null)row.put("decision",source)
   if(retries!=null)row.put("retryCount",retries)
   if(retryInMs!=null)row.put("retryInMs",retryInMs)
   if(status!=null)row.put("commandStatus",status)
   MainActivity.record(this,"USB_BT_STACK",row)
  }catch(_:Exception){}
 }
 private fun discoverPhone(btStack:UsbBtHostStack,source:String):String{
  phase("bluetooth_phone_inquiry")
  note("BT_PHONE_INQUIRY","请在 iPhone 上打开「设置 → 蓝牙」，车机正在搜索。")
  recordPhoneConnection("active_inquiry_requested",source=source)
  val phones=btStack.discoverPhones(22000L)
  recordPhoneConnection("active_inquiry_results",phones.size)
  if(phones.isEmpty())throw IOException("未搜到 iPhone。保持手机「设置 → 蓝牙」页面打开，再重试。")
  phase("bluetooth_phone_select")
  note("BT_PHONE_SELECT","请在车机上选择你的 iPhone。")
  val labels=phones.mapIndexed{i,peer->
   val name=peer.name.ifBlank{"手机（未广播名称）"}
   if(phones.size==1)name else "${i+1}. $name"
  }.toTypedArray()
  val selected=MainActivity.chooseUsbBluetoothPeer(labels,60000L,java.util.concurrent.Callable{closed})
  if(closed)throw InterruptedException("Connection cancelled")
  if(selected !in phones.indices)throw IOException("未选择 iPhone。请打开车机应用并点「连接」重试。")
  return phones[selected].address
 }

 private fun connectToPhone(btStack:UsbBtHostStack,selection:WirelessPhoneReconnect.Selection):String{
  val remembered=selection.remembered
   ?:return connectPhonePeer(btStack,discoverPhone(btStack,selection.reason),remembered=false)
  try{
   return connectPhonePeer(btStack,remembered,remembered=true)
  }catch(failure:IOException){
   val outbound=failure as? UsbBtHostStack.OutboundFailure
   if(bootAutoConnect||runtimeReconnect||benchmarkConnection||outbound==null||
      !WirelessPhoneReconnect.shouldDiscoverAfterPageFailure(outbound.stage,outbound.status)){
    throw IOException("BT_RESELECT_PHONE 已保存的 iPhone 未能连接。请检查它的蓝牙是否开启。",failure)
   }
   check()
   recordPhoneConnection("remembered_phone_unreachable",source="manual_fallback",status=outbound.status)
  }
  return connectPhonePeer(btStack,discoverPhone(btStack,"remembered_phone_unreachable"),remembered=false)
 }

 private fun tryBondedPhones(btStack:UsbBtHostStack,phones:List<String>,preferred:String?,allowIncoming:Boolean):String?{
  if(phones.isEmpty())return null
  phase("bluetooth_phone_connect")
  note("BT_PHONE_RECONNECT","正在自动连接已配对的 iPhone。")
  if(allowIncoming&&phones.size==1){
   val phone=phones.single()
   return phone.takeIf{btStack.tryRememberedPeer(it,4500L)}
  }
  for(phone in phones){
   check()
   val source=if(phone.equals(preferred,ignoreCase=true))"last_projected" else "other_bonded"
   recordPhoneConnection("bonded_phone_attempt",candidateCount=phones.size,source=source)
   // The controller bounds paging separately. Leave the existing RFCOMM budget
   // available once a phone answers, including its security and service discovery.
   var retries=0
   var finalFailure:UsbBtHostStack.OutboundFailure?
   while(true){
    val started=SystemClock.elapsedRealtime()
    if(btStack.tryRememberedPeer(phone,80000L))return phone
    finalFailure=btStack.lastRememberedFailure()
    val retryMs=finalFailure?.let{
     WirelessPhoneReconnect.retryDelayMs(it.stage,it.status,true,retries,SystemClock.elapsedRealtime()-started)
    }?:0L
    if(retryMs==0L)break
    retries++
    Thread.sleep(retryMs)
    check()
   }
   if(finalFailure==null||!WirelessPhoneReconnect.canTryNextBondedPhone(finalFailure.stage,finalFailure.status)){
    throw IOException("BT_RESELECT_PHONE 已配对的 iPhone 未能完成蓝牙连接。",finalFailure)
   }
   unavailablePhones.add(phone)
   recordPhoneConnection("bonded_phone_unavailable",source=source,status=finalFailure.status)
  }
  return null
 }

 private fun connectPhonePeer(btStack:UsbBtHostStack,address:String,remembered:Boolean):String{
  phase("bluetooth_phone_connect")
  val source=if(remembered)"remembered" else "selected"
  var retries=0
  while(true){
   check()
   if(!remembered)note("BT_PHONE_CONNECT","车机正在连接 iPhone。首次配对时请确认手机上弹出的请求。")
   else note("BT_PHONE_RECONNECT","正在连接已保存的 iPhone。")
   recordPhoneConnection("active_connect_requested",source=source,retries=retries)
   val attemptStartedAt=SystemClock.elapsedRealtime()
   try{
    btStack.connectPeer(address)
    if(!btStack.awaitRfcomm(80000L))throw IOException("等待 iPhone 的 iAP2 蓝牙通道超时或通道已关闭。")
    return address
   }catch(failure:IOException){
    val outbound=failure as? UsbBtHostStack.OutboundFailure
    val attemptElapsedMs=SystemClock.elapsedRealtime()-attemptStartedAt
    val retryMs=outbound?.let{
     WirelessPhoneReconnect.retryDelayMs(it.stage,it.status,remembered,retries,attemptElapsedMs)
    }?:0L
    if(retryMs==0L)throw failure
    retries++
    note("BT_PHONE_RETRY","蓝牙正在结束上一个连接操作，即将重试当前 iPhone。")
    recordPhoneConnection("active_connect_retry_wait",source=source,retries=retries,retryInMs=retryMs,status=outbound?.status)
    Thread.sleep(retryMs)
   }
  }
 }
 private fun waitForPhone(btStack:UsbBtHostStack,currentBt:UsbDevice,ats2851:Boolean,rtlFirmwareCandidate:Boolean,carplayDiscovery:Boolean){
  phase("bluetooth_pairing")
  btStack.awaitPairingVisibilityEvidence(4000L,ats2851)
  val scanConfirmed=btStack.isScanEnableConfirmed()
  val scanWriteAccepted=btStack.isScanEnableWriteAccepted()
  val scanWriteFallback=ats2851&&!scanConfirmed&&scanWriteAccepted
  val scanMode=btStack.getScanEnable()
  val scanStatus=btStack.getScanEnableStatus()
  val inquiryScan=btStack.isScanEnableKnown()&&(scanMode and 1)!=0
  val pageScan=btStack.isScanEnableKnown()&&(scanMode and 2)!=0
  try{MainActivity.record(this,"USB_BT_HCI",JSONObject().put("stage","discoverability_check").put("deviceVid",currentBt.vendorId).put("devicePid",currentBt.productId).put("firmwareSetupExpected",rtlFirmwareCandidate).put("realtekSetupSelected",btStack.isRealtekSetupSelected()).put("scanReadbackKnown",btStack.isScanEnableKnown()).put("scanEnable",scanMode).put("commandStatus",scanStatus).put("inquiryScanConfirmed",inquiryScan).put("pageScanConfirmed",pageScan).put("discoverableConfirmed",scanConfirmed).put("scanReadbackSkipped",ats2851).put("scanWriteFallbackUsed",scanWriteFallback).put("scanEnableRequested",btStack.getScanEnableRequested()).put("scanEnableUsbSubmitKnown",btStack.isScanEnableUsbSubmitKnown()).put("scanEnableUsbSubmitResult",btStack.getScanEnableUsbSubmitResult()).put("scanEnableWriteKnown",btStack.isScanEnableWriteKnown()).put("scanEnableWriteStatus",btStack.getScanEnableWriteStatus()).put("scanEnableWriteAccepted",scanWriteAccepted).put("attempt",btStack.getScanEnableWriteCount()).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  if(!scanConfirmed&&!scanWriteFallback){
   val scanDescription=if(btStack.isScanEnableKnown())"mode=$scanMode inquiryScan=$inquiryScan pageScan=$pageScan status=$scanStatus" else "readback=unavailable status=$scanStatus"
   throw IOException("USB Bluetooth controller did not confirm pairing visibility ($scanDescription); stopped before the 120-second iPhone wait. Check the USB adapter ID/HCI manufacturer and firmware initialization.")
  }
  val manufacturer=btStack.controllerManufacturer
  val hciVersion=btStack.controllerHciVersion
  if(carplayDiscovery)note("PAIRING_CARPLAY","请在 iPhone「设置 → 通用 → CarPlay 车载」中选择车机广播名称。")
  else if(scanConfirmed)note("PAIRING","HCI readback confirms inquiry+page scan enabled; controller manufacturer code=$manufacturer, HCI version=$hciVersion. Open iPhone Settings > Bluetooth and pair using the configured adapter name.")
  else note("PAIRING","ATS2851 accepted the inquiry+page scan command; this controller does not provide the readback used for independent confirmation. Pairing is continuing. Open iPhone Settings > Bluetooth now and pair using the configured adapter name.")
  if(!btStack.awaitRfcomm(120000L))throw IOException(
   if(carplayDiscovery)"120 秒内未接到 iPhone 的 iAP2 连接，请在「设置 → 通用 → CarPlay 车载」中选择车机。"
   else "No iAP2 RFCOMM connection arrived within 120 seconds; check iPhone Bluetooth pairing and keep the Bluetooth settings page open")
 }
 private fun connect(reselectPhone:Boolean){
  val hybridPrefs=getSharedPreferences("hybrid_settings",MODE_PRIVATE)
  val hybridProfile=0
  // The USB adapter provides the Bluetooth identity and transport represented by group 17.
  val advertiseBluetoothComponent=true
  hybridIap2Profile=hybridProfile;hybridBluetoothComponentEnabled=advertiseBluetoothComponent;hybridWifiCredentialsIncluded=false;lastIap2MessageId=-1
  phase("usb_bt_permission")
  val selectedBt=usbBtDevice?:throw IOException("请插入 CM390 或 CM591 USB 蓝牙适配器再点「连接」")
  val currentBt=usb.deviceList.values.firstOrNull{it.deviceId==selectedBt.deviceId&&it.vendorId==selectedBt.vendorId&&it.productId==selectedBt.productId}
    ?:throw IOException("The selected USB Bluetooth adapter is no longer attached")
  if(!UsbBluetoothProbe.isCandidate(currentBt))throw IOException("Selected USB device is not recognized as a Bluetooth HCI adapter")
  if(!usb.hasPermission(currentBt))throw IOException("Android has not granted USB access to the Bluetooth adapter; retry and approve the USB dialog")
  phase("bluetooth_prepare")
  try{MainActivity.record(this,"USB_BT_PERMISSION",JSONObject().put("stage","service_open").put("granted",true).put("deviceVid",currentBt.vendorId).put("devicePid",currentBt.productId).put("interfaceCount",currentBt.interfaceCount))}catch(_:Throwable){}
  val btConnection=usb.openDevice(currentBt)?:throw IOException("Android USB permission was granted but openDevice failed")
  own(Closeable{btConnection.close()},"usb_bt_usb_connection")
  phase("usb_bt_hci_start")
  val bluetoothName=MainActivity.bluetoothName(this)
  val eirProfile=MainActivity.bluetoothEirProfile(this)
  val ats2851=UsbBluetoothProbe.isKnownAts2851(currentBt.vendorId,currentBt.productId)
  val activePhoneConnect=ats2851||runtimeReconnect||reselectPhone
  val carplayDiscovery=ats2851&&!activePhoneConnect&&eirProfile==1
  val discoveryStrategy=when {
   activePhoneConnect -> "active_iphone"
   carplayDiscovery -> "iap2_carplay_uuid_and_name"
   eirProfile==1 -> "iap2_uuid_and_name"
   ats2851 -> "windows_name_and_tx_power"
   else -> "windows_name_only"
  }
  try{MainActivity.record(this,"USB_BT_STACK",JSONObject().put("stage","advertising_config").put("strategy",discoveryStrategy).put("configuredNameBytes",bluetoothName.toByteArray(Charsets.UTF_8).size).put("deviceVid",currentBt.vendorId).put("devicePid",currentBt.productId))}catch(_:Throwable){}
  val btStack=UsbBtHostStack(this,currentBt,btConnection,bluetoothName,eirProfile,activePhoneConnect);own(btStack,"usb_bt_host_stack")
  val btStartAt=SystemClock.elapsedRealtime();btStack.start(true)
  ConnectionPrewarm.warmWireless(this)
  val rtlFirmwareCandidate=UsbBluetoothProbe.usesBundledRtl8761BuFirmware(currentBt.vendorId,currentBt.productId)
  val chipsetHint=UsbBluetoothProbe.chipsetHint(currentBt.vendorId,currentBt.productId)
  log("USB_BT_STACK","stage=start vid=${currentBt.vendorId} pid=${currentBt.productId} chipset=$chipsetHint interfaceCount=${currentBt.interfaceCount} rtl8761buFirmwareCandidate=$rtlFirmwareCandidate ats2851GeneralBondingQuirk=$ats2851")
  try{MainActivity.record(this,"USB_BT_STACK",JSONObject().put("stage","start").put("vid",currentBt.vendorId).put("pid",currentBt.productId).put("chipsetHint",chipsetHint).put("interfaceCount",currentBt.interfaceCount).put("firmwareSetupExpected",rtlFirmwareCandidate).put("ats2851GeneralBondingQuirkEnabled",ats2851).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  phase("wifi_wait")
  note("WIFI_WAIT",if(useP2p)"正在创建 Wi-Fi Direct 局域网。" else "正在并行准备车载热点和 USB 蓝牙。")
  if(!useP2p&&LegacyCarHotspot.isMobileDataIsolationEnabled(this)){
   try{
    VehicleMobileData.disableBeforeHotspot(this,{closed}){
     recordRuntimeLink("mobile_data_disconnecting","before_hotspot=true")
     note("WIFI_WAIT","正在等待车机移动网络断开。")
    }
    recordRuntimeLink("mobile_data_disconnected","before_hotspot=true")
   }catch(failure:LegacyCarHotspotException){
    recordRuntimeLink("mobile_data_disconnect_failed","reason=${failure.reason}")
    throw IOException("WIFI_CHECK_SETTINGS ${failure.message}",failure)
   }
  }
  val hotspot=if(useP2p){
   val p2p=WifiP2pHotspot(this)
   own(p2p,"wifi_direct_group");p2pHotspot=p2p
   try{
    p2p.start({closed}){stage->
     recordRuntimeLink(stage,"backend=${networkBackend()} vehicleMobileDataSetting=${LegacyCarHotspot.mobileDataSetting(this)?:-1}")
     if(stage=="p2p_wait_wifi")note("WIFI_WAIT","正在开启车机 Wi-Fi，以创建 Wi-Fi Direct 局域网。")
     if(stage=="p2p_create_group")note("WIFI_WAIT","正在创建 Wi-Fi Direct 局域网。")
    }
   }catch(unavailable:WifiP2pHotspot.StartException){
    recordRuntimeLink("p2p_start_failed","reason=${unavailable.reason}")
    val prefix=if(unavailable.reason=="system_hotspot_active")"P2P_SYSTEM_HOTSPOT_ACTIVE" else "P2P_CONNECTION_FAILED"
    throw IOException("$prefix ${unavailable.message}",unavailable)
   }
  }else if(LegacyCarHotspot.isAutoStartEnabled(this)){
   try{
    val prepared=LegacyCarHotspot.prepare(this,30000L,{closed}){stage->
     val apEnabled=HybridNetwork.isApEnabled(this)
     log("HYBRID_WIFI","stage=$stage wifiConnected=$apEnabled")
     try{MainActivity.record(this,"HYBRID_WIFI",JSONObject().put("stage",stage).put("wifiConnected",apEnabled).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
     if(stage=="ap_enable_requested")note("WIFI_WAIT","正在开启车载热点。")
    }
    prepared
   }catch(unavailable:LegacyCarHotspotException){
    try{MainActivity.record(this,"HYBRID_WIFI",JSONObject().put("stage","ap_auto_failed").put("reason",unavailable.reason).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
    val prefix=if(LegacyCarHotspot.shouldOpenSystemSettings(unavailable.reason))"WIFI_OPEN_FALLBACK" else "WIFI_CHECK_SETTINGS"
    throw IOException("$prefix ${unavailable.message}",unavailable)
   }
  }else awaitVehicleHotspot(30000L)
  check()
  validateVehicleHotspot(hotspot)
  runtimeHotspot=hotspot
  wifiIpReady=true
  if(useP2p)captureRuntimeHotspot("p2p_group_ready")
  recordStartupStep("hotspot_ready","wifi_ready","interface=${hotspot.interfaceName}")
  phase("airplay_listener")
  val primaryListener=bindAirPlayIpv4(hotspot.localAddress)
  val airplayPort=primaryListener.localPort
  val remainingHciMs=(60000L-(SystemClock.elapsedRealtime()-btStartAt)).coerceAtLeast(0L)
  phase("usb_bt_hci_wait")
  if(!btStack.awaitReady(remainingHciMs))throw IOException("USB Bluetooth HCI initialization timed out; state=${btStack.lastState}")
  val dongleMac=btStack.bluetoothAddress.takeIf{it.isNotBlank()}?:throw IOException("USB Bluetooth HCI became ready without a controller address")
  log("USB_BT_STACK","stage=hci_and_hotspot_ready addressPresent=true bluetoothElapsedMs=${SystemClock.elapsedRealtime()-btStartAt} state=${btStack.lastState}")
  try{MainActivity.record(this,"USB_BT_STACK_READY",JSONObject().put("stage","hci_and_hotspot_ready").put("vid",currentBt.vendorId).put("pid",currentBt.productId).put("interfaceCount",currentBt.interfaceCount).put("addressPresent",true).put("controllerState",btStack.lastState).put("bluetoothElapsedMs",SystemClock.elapsedRealtime()-btStartAt).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  awaitProjectionSurface()
  validateHotspotBinding(hotspot,verifyIpv6=false)
  val peerPreference=MainActivity.PREF_USB_BT_PEER+"."+dongleMac.uppercase(Locale.US)
  val verifiedPreference="$peerPreference.verified"
  val verifiedRecord=hybridPrefs.getString(verifiedPreference,null)
  val savedPeer=hybridPrefs.getString(peerPreference,null)
  val legacyPeer=hybridPrefs.getString(MainActivity.PREF_USB_BT_PEER,null)
  val selection=WirelessPhoneReconnect.chooseForAdapter(verifiedRecord,savedPeer,legacyPeer,dongleMac,reselectPhone)
  if(benchmarkConnection&&selection.remembered==null)throw IOException("画面检测需要已确认的 iPhone。")
  val lastProjected=WirelessPhoneReconnect.choose(verifiedRecord,dongleMac,reselect=false).remembered
  val preferred=if(reselectPhone)null else lastProjected
  val bonded=if(reselectPhone||benchmarkConnection)emptyList() else
   WirelessPhoneReconnect.bondedCandidates(preferred?:selection.remembered,btStack.bondedPeerAddresses(),unavailablePhones)
  if(continuingPhoneSelection&&bonded.isEmpty()){
   throw IOException("BT_RESELECT_PHONE 没有其他已配对的 iPhone 可供连接。")
  }
  if(runtimeReconnect&&selection.remembered==null&&bonded.isEmpty()){
   throw IOException("找不到这只适配器已确认的 iPhone，请手动连接。")
  }
  if(activePhoneConnect)btStack.allowPhoneConnections()
  val selectedPhone=tryBondedPhones(btStack,bonded,preferred?:selection.remembered,allowIncoming=!activePhoneConnect)?:when{
   activePhoneConnect&&bonded.isNotEmpty()&&(bootAutoConnect||runtimeReconnect)->
    throw IOException("BT_RESELECT_PHONE 暂时无法连接已配对的 iPhone。",btStack.lastRememberedFailure())
   activePhoneConnect&&bonded.isNotEmpty()->
    connectPhonePeer(btStack,discoverPhone(btStack,"bonded_phones_unavailable"),remembered=false)
   activePhoneConnect->connectToPhone(btStack,selection)
   benchmarkConnection&&rtlFirmwareCandidate&&preferred!=null&&btStack.tryRememberedPeer(preferred,4500L)->preferred
   else->{
    check()
    btStack.allowPhoneConnections()
    waitForPhone(btStack,currentBt,ats2851,rtlFirmwareCandidate,carplayDiscovery)
    null
   }
  }
  val rfcommPeerAddress=btStack.rfcommPeerAddress
  if(benchmarkConnection&&!selection.remembered.equals(rfcommPeerAddress,ignoreCase=true)){
   throw IOException("画面检测连接到了不同的蓝牙设备。")
  }
  usbLinked=true
  val confirmedPhone=WirelessPhoneReconnect.confirmedRecord(dongleMac,selectedPhone?:rfcommPeerAddress,rfcommPeerAddress)
  val rememberedPhoneConfirmed=confirmedPhone!=null&&selection.remembered!=null&&
   selection.remembered.equals(rfcommPeerAddress,ignoreCase=true)
  if(confirmedPhone!=null&&rfcommPeerAddress!=null){
   if(bonded.any{it.equals(rfcommPeerAddress,true)}&&
      bonded.any{!it.equals(rfcommPeerAddress,true)&&it !in unavailablePhones})phoneForAlternateRetry=rfcommPeerAddress
   val ownController=hybridPrefs.getString(peerControllerKey(verifiedPreference,rfcommPeerAddress),null)
    ?:if(lastProjected.equals(rfcommPeerAddress,ignoreCase=true))hybridPrefs.getString("$verifiedPreference.controller",null) else null
   expectedAirPlayController=ownController.takeUnless{reselectPhone}
   otherAirPlayControllers=WirelessPhoneReconnect.otherKnownControllers(
    hybridPrefs.all,MainActivity.PREF_USB_BT_PEER,rfcommPeerAddress,
   ).filterNot{it==ownController}.toSet()
  }
  log("USB_BT_STACK","stage=rfcomm_connected addressPresent=${rfcommPeerAddress!=null} bluetoothElapsedMs=${SystemClock.elapsedRealtime()-btStartAt}")
  try{MainActivity.record(this,"USB_BT_STACK",JSONObject().put("stage","rfcomm_connected").put("rfcommConnected",true).put("addressPresent",rfcommPeerAddress!=null).put("bluetoothElapsedMs",SystemClock.elapsedRealtime()-btStartAt).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  val rfcomm=UsbRfcommDuplexStream(btStack);own(rfcomm,"usb_bt_rfcomm_stream")
  fun openWirelessIap2():Iap2Session {
   val control=Iap2Session.openWireless(rfcomm,traceContext="hybrid_gpl_usb_bt",
    onLinkDiagnostic={stage,sequence,payloadBytes,retryCount->
     try{
      val row=JSONObject().put("stage",stage).put("iap2Profile",hybridProfile).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt)
      if(sequence>=0)row.put("diagnosticSummary","sequence=$sequence")
      if(stage.startsWith("wifi_config_"))row.put("messageId",0x5703)
      else if(stage.startsWith("carplay_start_"))row.put("messageId",0x4301)
      if(payloadBytes>=0)row.put("payloadBytes",payloadBytes)
      if(retryCount>=0)row.put("retryCount",retryCount)
      MainActivity.record(this,"HYBRID_IAP2_IDENTIFICATION",row)
     }catch(_:Exception){}
    })
   own(control,"iap2")
   return control
  }
  // The iPhone starts link negotiation as soon as either RFCOMM direction opens.
  phase("iap2_setup")
  note("CARKIT","蓝牙通道已打开，正在与 iPhone 建立 iAP2。")
  val csm=openWirelessIap2()
  val iap2Ready=try{csm.awaitReady(12000L)}catch(error:IOException){
   if(btStack.hasPhoneDisconnected()&&tryAlternatePhone(error,"iap2_setup",hotspot))return
   throw error
  }
  if(!iap2Ready){
   val error=IOException("已连接 iPhone 蓝牙，但 iAP2 握手未完成。")
   if(tryAlternatePhone(error,"iap2_setup",hotspot))return
   throw error
  }
  synchronized(resourceLock){
   check()
   bluetoothBootstrapStop={
    try{btStack.stopDiscoveryForHandoff()}
    finally{try{csm.close()}finally{btStack.close()}}
   }
  }
  if(selectedPhone!=null)recordPhoneConnection("active_iap2_link_ready")
  // SoftAP may assign IPv6 after IPv4. Establish the optional listener from the latest snapshot.
  awaitProjectionSurface()
  val wifi=validateHotspotBinding(hotspot,verifyIpv6=false)
  runtimeHotspot=wifi
  val listeners=ArrayList<Pair<InetAddress,ServerSocket>>(2)
  listeners.add(wifi.localAddress to primaryListener)
  val ipv6Address=wifi.wifiLinkLocalIpv6?.takeIf{address->
   bindAirPlayIpv6(address,airplayPort,wifi.interfaceName)?.let{listeners.add(address to it)}==true
  }
  val advertisedPassphrase=wifi.credential.passphrase
  val includeWifiCredentials=advertisedPassphrase!=null
  hybridWifiCredentialsIncluded=includeWifiCredentials
  wifiIpReady=true
  phase("wifi_ready")
  log("HYBRID_WIFI","stage=ap_ready transportMode=${wirelessTransportMode()} wifiIpReady=true interfaceName=${wifi.interfaceName} localAddress=${wifi.localAddress.hostAddress} frequencyMhz=${wifi.frequencyMhz} frequencySource=${wifi.frequencySource} channel=${wifi.channelNumber} apBssidPresent=${wifi.apBssid!=null} credentialAvailable=${wifi.credential.available} credentialSource=${wifi.credential.source} security=${wifi.credential.security}")
  try{MainActivity.record(this,"HYBRID_WIFI_CREDENTIAL",JSONObject().put("stage",if(useP2p)"wifi_p2p_group_configuration_read" else "android_softap_configuration_read").put("configuredNetworkMatched",wifi.credential.configuredNetworkMatched).put("credentialAvailable",wifi.credential.available).put("credentialSource",wifi.credential.source).put("security",wifi.credential.security.name).put("frequencyMhz",wifi.frequencyMhz).put("frequencySource",wifi.frequencySource).put("channel",wifi.channelNumber).put("iap2Profile",hybridProfile).put("bluetoothGroup17Enabled",advertiseBluetoothComponent).put("wifiCredentialsIncluded",includeWifiCredentials))}catch(_:Throwable){}
  note("WIFI_READY",if(useP2p)"Wi-Fi Direct 局域网已就绪，正在准备无线投屏。" else "车载热点已就绪，正在准备无线投屏。")
  phase("mfi_identity")
  val store=tracedPreparation("airplay_identity_store"){ConnectionPrewarm.identityStore(this)}
  val auth=tracedPreparation("mfi_authenticator"){VerifiedMfiCache.get(this,{log("MFI","challenge signed bytes=$it")}){failure->recordPreparation("mfi_authenticator_retry","retry",0,failure)}}
  val identity=tracedPreparation("airplay_identity"){store.identity()}
  val accessoryMac=wifi.macAddress
  val hybridBluetoothMac=dongleMac
  try{MainActivity.record(this,"HYBRID_IAP2_IDENTITY",JSONObject().put("stage","configured").put("iap2Profile",hybridProfile).put("bluetoothGroup17Enabled",true).put("usbHostGroup16Enabled",false).put("bluetoothIdentitySource","usb_hci_controller_address").put("wifiCredentialsIncluded",includeWifiCredentials).put("activeIap2Transport","usb_bt_rfcomm"))}catch(_:Throwable){}
  phase("airplay_listener")
  note("HYBRID_WIFI_HANDOFF",if(useP2p)"正在 Wi-Fi Direct 网卡上启动 AirPlay。" else "正在车载热点网卡上启动 AirPlay。")
  val preferredDecoder=if(LegacyCarHotspot.isSupported(this))mx9a0AvcDecoder else null
  val renderer=LegacyMedia(null,ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,preferredDecoder){log("MEDIA",it)}
  renderer.onVideoFailure={fail(IOException("video decode failure",it),"video_decoder")};renderer.routing=AudioRouting(this,renderer);own(renderer,"media");synchronized(Companion){sink=renderer;renderer.setSurface(surface)}
  val media=CarPlayMediaEngine(renderer,true);val pairings=store.pairings()
  val ipv6ListenerAvailable=ipv6Address!=null
  val configAir=AirPlayConfig(bluetoothName,accessoryMac,hybridBluetoothMac,"320.20",
   AirPlayDisplayConfig(ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,fps=selectedFps),microphone=true,
   port=airplayPort,opusAudio=true,mainBufferedAudio=bufferedAacAvailable)
  recordRuntimeLink("buffered_media_capability","aacDecoder=$bufferedAacAvailable")
  for((address,bound) in listeners){
   val family=if(address is Inet4Address)"ipv4" else "ipv6"
   val literal=address.hostAddress.substringBefore('%')
   log("NETWORK_LISTENER","transportMode=${if(useP2p)"wifi_p2p" else "wifi_softap"} service=airplay_control family=$family localAddress=$literal localPort=${bound.localPort} backend=${networkBackend()}")
   try{MainActivity.record(this,"HYBRID_WIFI_LISTENER",JSONObject().put("stage","listener_ready").put("localAddress",literal).put("interfaceName",wifi.interfaceName).put("localPort",bound.localPort).put("addressFamily",family).put("success",true).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  }
  try{val endpoint=JSONObject().put("stage","listener_ready").put("localAddress",wifi.localAddress.hostAddress).put("interfaceName",wifi.interfaceName).put("localPort",airplayPort).put("addressFamily","ipv4").put("addressCount",listeners.size).put("ipv6ListenerAvailable",ipv6ListenerAvailable).put("apBssidPresent",wifi.apBssid!=null).put("diagnosticSummary","bindingSource=${wifi.bindingSource} ipv6Scope=${wifi.wifiLinkLocalIpv6?.scopeId?:0} credentialAvailable=${wifi.credential.available} credentialSource=${wifi.credential.source} frequencySource=${wifi.frequencySource}").put("frequencyMhz",wifi.frequencyMhz).put("frequencySource",wifi.frequencySource).put("channel",wifi.channelNumber).put("credentialAvailable",wifi.credential.available).put("credentialSource",wifi.credential.source).put("configuredNetworkMatched",wifi.credential.configuredNetworkMatched).put("security",wifi.credential.security.name);if(wifi.gatewayAddress.isNotBlank())endpoint.put("gatewayAddress",wifi.gatewayAddress);if(wifi.networkMask.isNotBlank())endpoint.put("networkMask",wifi.networkMask);MainActivity.record(this,"HYBRID_WIFI_ENDPOINT",endpoint)}catch(_:Throwable){}
  val returnPathProbe=runCatching{WifiReturnPathProbe(wifi.localAddress){event->try{event.put("iap2Profile",hybridProfile);MainActivity.record(this,"HYBRID_PATH_PROBE",event)}catch(_:Throwable){}}}.getOrElse{error->
   try{MainActivity.record(this,"HYBRID_PATH_PROBE",JSONObject().put("stage","unavailable").put("success",false).put("errorType",error.javaClass.simpleName.take(48)).put("localAddress",wifi.localAddress.hostAddress).put("localPort",WifiReturnPathProbe.PORT).put("addressFamily","ipv4").put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
   null
  }
  if(returnPathProbe!=null)own(returnPathProbe,"wifi_return_path_probe")
  fun acceptFrom(bound:ServerSocket,address:InetAddress){
   val family=if(address is Inet4Address)"ipv4" else "ipv6"
   val literal=address.hostAddress.substringBefore('%')
   Thread({try{
    while(!closed){
     val socket=bound.accept()
     try{
      socket.tcpNoDelay=true;socket.keepAlive=true
      val accepted=synchronized(resourceLock){
       val recovery=wifiRecovery
       if(closed||hotspotRepair!=null||sessions.size>=4||recovery!=null&&socket.inetAddress!=recovery.peer)false else{
        val control=AirPlaySession(
         socket,configAir,identity,pairings,auth,listener,media,recovery?.controllerId,
         controlBootstrapTimeoutMs=AIRPLAY_NEGOTIATION_TIMEOUT_MS,
        )
        sessions.add(control)
        airplayTcpAccepted.set(true)
        try{MainActivity.record(this,"HYBRID_AIRPLAY_ACCEPT",JSONObject().put("stage","tcp_accept").put("localAddress",literal).put("addressFamily",family).put("localPort",bound.localPort).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
        // A speculative TCP connection must not hide an earlier iAP2 failure in the report.
        if(!videoReady&&(activeDiagnosticStage=="phone_availability"||activeDiagnosticStage=="phone_projection_start")){
         phase("airplay_handshake");note("AIRPLAY_HANDSHAKE","Wi-Fi data path accepted; negotiating AirPlay")
        }
        control.start();true
       }
      }
      if(!accepted)socket.close()
     }catch(e:Throwable){runCatching{socket.close()};throw e}
    }
   }catch(e:Throwable){
    if(!closed&&!bound.isClosed){
     if(address is Inet4Address){
      val recovering=synchronized(resourceLock){
       val requested=hotspotRepair!=null||session?.let{startHotspotRepair(it)}==true
       if(requested)runCatching{bound.close()}
       requested
      }
      if(!recovering)fail(e,"airplay_accept_$family",allowRuntimeRetry=useP2p)
     } else {
      runCatching{bound.close()}
      try{MainActivity.record(this,"HYBRID_WIFI_LISTENER",JSONObject()
       .put("stage","accept_failed").put("localAddress",literal)
       .put("interfaceName",wifi.interfaceName).put("localPort",airplayPort)
       .put("addressFamily","ipv6").put("success",false)
       .put("errorType",e.javaClass.simpleName.take(48))
       .put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
     }
    }
   }},"hybrid-airplay-$family").apply{isDaemon=true;start()}
  }
  phase("iap2_identification");note("IAP2_AUTH","正在通过蓝牙 iAP2 完成设备识别与 MFi 认证")
   // The host AP's BSSID is the optional 0x5703 network hint. It stays separate from the
   // AirPlay receiver identity (accessoryMac), which identifies this head unit to the phone.
   fun endpointFor(wireAddresses:List<String>)=Iap2WirelessCarPlayEndpoint(
    wifi.ssid,advertisedPassphrase,wifi.channelNumber,wifi.credential.security,wireAddresses,
    airplayPort,accessoryMac,identity.publicKeyHex,configAir.sourceVersion,wifi.apBssid,
   )
   val ipv4WireAddress=wifi.localAddress.hostAddress
   val ipv6WireAddress=ipv6Address?.hostAddress?.substringBefore('%')
   val invitationAddresses=listOfNotNull(ipv4WireAddress,ipv6WireAddress)
   val endpoint=endpointFor(invitationAddresses)
   val alternateEndpoint=ipv6WireAddress?.let{endpointFor(listOf(ipv4WireAddress))}
   val wirelessIdentity=Iap2WirelessIdentification(hybridBluetoothMac,wifi.ssid)
   val appVersion=runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrNull()?.takeIf{it.isNotBlank()}?:"unknown"
   val ident=Iap2IdentificationConfig(bluetoothName,"SongDM-MX9A0","Independent",store.hostId,appVersion,"API17",0,language="zh",wireless=wirelessIdentity,wirelessCarPlaySsid=wifi.ssid,usbHostTransportComponentEnabled=false)
  val offerGnss=LegacyCarPlayLocationProvider.canReport(this)
  val offerCanSpeed=vehicleSignals?.isSupported()==true
  val runtimeIdent=ident.copy(locationInformationEnabled=offerGnss||offerCanSpeed,
    gnssPositionEnabled=offerGnss,vehicleSpeedEnabled=offerCanSpeed)
  recordVehicleIntegration("location_offer","gnssOffered=$offerGnss pascdOffered=$offerCanSpeed")
  val authenticatedPhoneAddress=java.util.concurrent.atomic.AtomicReference<String?>(rfcommPeerAddress?:selectedPhone)
  val handoff=HybridWirelessHandoff(
   this,wifi.localAddress,configAir,identity,runtimeIdent,endpoint,auth,
   ipv6Address,authenticatedPhoneAddress::get,
    onTunnelReady=::maybeReleaseBluetoothBootstrap,
    locationProvider={LegacyCarPlayLocationProvider(this,vehicleSignals,offerGnss,offerCanSpeed){summary->
     recordVehicleIntegration(if(summary.startsWith("yaw_estimate "))"yaw_estimate" else "location_source",summary)
    }},
  ){type,data->try{data.put("elapsedMs",SystemClock.elapsedRealtime()-bornAt);MainActivity.record(this,type,data)}catch(_:Throwable){}}
  own(handoff,"wireless_handoff");wifiHandoff=handoff
  media.setIapTunnelHandler(handoff::attachTunnel)
  var currentIpv4=primaryListener
  var currentIpv6=listeners.firstOrNull{it.first is Inet6Address}?.second
  var boundIpv6Address=ipv6Address
  own(Closeable{synchronized(resourceLock){currentIpv4.close();currentIpv6?.close()}},"rebound_hotspot_listeners")
  refreshHotspotDiscovery=refresh@{restored,needsIpv6->
   synchronized(resourceLock){
    check()
    if(currentIpv4.isClosed){
     currentIpv4=LegacySockets.tcp(restored.localAddress,airplayPort)
     server=currentIpv4
     acceptFrom(currentIpv4,restored.localAddress)
    }
    val newAddress=restored.wifiLinkLocalIpv6
    if(boundIpv6Address!=newAddress||boundIpv6Address?.scopeId!=newAddress?.scopeId||
     (newAddress!=null&&currentIpv6?.isClosed!=false)){
     currentIpv6?.close()
     currentIpv6=newAddress?.let{bindAirPlayIpv6(it,airplayPort,restored.interfaceName,registerResource=false)}
     boundIpv6Address=newAddress?.takeIf{currentIpv6!=null}
     currentIpv6?.let{acceptFrom(it,requireNotNull(boundIpv6Address))}
    }
    if(needsIpv6&&boundIpv6Address==null)return@refresh false
   }
   handoff.reopenDiscovery(boundIpv6Address)
  }
  listeners.forEach{(address,bound)->acceptFrom(bound,address)}
  handoff.start()
  log("HYBRID_WIFI_HANDOFF","stage=advertising_queued")
  val startSent=AtomicBoolean(false)
  val peerEvidence=SoftApPeerEvidence(wifi)
  fun recordPeer(whenObserved:String,summary:String){
   try{
    MainActivity.record(this,"HYBRID_WIFI_PEER",JSONObject()
     .put("stage",whenObserved).put("phase",activeDiagnosticStage)
     .put("diagnosticSummary",summary).put("airplayTcpAccepted",airplayTcpAccepted.get())
     .put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))
   }catch(_:Exception){}
  }
  recordPeer("before_iap2",peerEvidence.baseline())
  val peerSamplingArmed=AtomicBoolean(false)
  fun sampleAfterHandoff(){
   if(closed||!peerSamplingArmed.compareAndSet(false,true))return
   for(delay in longArrayOf(2000L,8000L,18000L,34000L)){
    try{watchdog.schedule({
     if(!closed&&!videoReady){
      recordPeer("after_handoff",peerEvidence.sample())
      p2pHotspot?.sampleClientCount{count->
       if(!closed)recordRuntimeLink("p2p_station_sample","clientCount=$count")
      }
      try{MainActivity.record(this,"HYBRID_MDNS_STATE",JSONObject()
       .put("stage","handoff_sample").put("diagnosticSummary",handoff.diagnosticSnapshot())
       .put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
     }
    },delay,TimeUnit.MILLISECONDS)}catch(_:java.util.concurrent.RejectedExecutionException){return}
   }
  }
  val inboundEvidenceCount=java.util.concurrent.atomic.AtomicInteger()
  val mfi=Iap2MfiAuthenticationClient(auth,
   onExchange={stage,id,bytes->
    lastIap2MessageId=id
    try{MainActivity.record(this,"HYBRID_IAP2_IDENTIFICATION",JSONObject().put("stage",stage).put("messageId",id).put("payloadBytes",bytes).put("iap2Profile",hybridProfile).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
   })
  val verifyHotspot={
   if(!videoReady)awaitProjectionSurface()
   validateHotspotBinding(wifi,verifyIpv6=ipv6Address!=null)
   Unit
  }
  val result=try{Iap2WirelessControlClient(csm,mfi).run(
   ident,endpoint,timeoutMillis=45000L,alternateEndpoint=alternateEndpoint,
   canInviteOverBluetooth={
    !closed&&session==null&&!handoff.tunnelReady&&!wifiRecoveryInProgress()&&
     (!startSent.get()||!selectedAirPlayResponding())
   },
   keepAliveAfterDeadline={session!=null||handoff.tunnelReady||wifiRecoveryInProgress()},
   onReady={
    if(!closed){
     if(confirmedPhone!=null){
      synchronized(resourceLock){
       if(!closed){
        runtimePhoneBluetooth=rfcommPeerAddress?:selectedPhone
        runtimePeerVerified=true
        maybeReleaseBluetoothBootstrap()
        bootPeerCandidate=BootPeerCandidate(hybridPrefs,peerPreference,verifiedPreference,confirmedPhone)
        session?.let(::confirmBootPeerIfProjected)
       }
      }
     }
     if(session==null&&!wifiRecoveryInProgress())handoff.beginPhoneHandoff()
    }
    log("HYBRID_IAP2","stage=authenticated transportMode=${wirelessTransportMode()}")
   },
   beforeStartSession=verifyHotspot,
   beforeWifiConfiguration=verifyHotspot,
   onStartSessionSent={
    startSent.set(true)
    sampleAfterHandoff()
    WiredSessionMemory.started(currentBt.deviceName)
    log("HYBRID_IAP2","stage=start_session_sent message=0x4301 airplayPort=$airplayPort addressFamily=ipv4 ipv6Listener=$ipv6ListenerAvailable apBssidPresent=${wifi.apBssid!=null}")
    try{MainActivity.record(this,"HYBRID_START_SESSION_SENT",JSONObject()
     .put("stage","sent").put("addressFamily","ipv4").put("localAddress",ipv4WireAddress)
     .put("localPort",airplayPort).put("addressCount",invitationAddresses.size).put("ipv6ListenerAvailable",ipv6ListenerAvailable)
     .put("apBssidPresent",wifi.apBssid!=null).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
   },
   onIncoming={frame->
    when(frame.messageId){
     0x5201->routeMetadata.incrementAndGet(0)
     0x5202->routeMetadata.incrementAndGet(1)
    }
    if(inboundEvidenceCount.getAndIncrement()<32){try{MainActivity.record(this,"HYBRID_IAP2_RX",JSONObject().put("stage",if(startSent.get())"after_start_session" else "before_start_session").put("messageId",frame.messageId).put("payloadBytes",frame.payload.size).put("afterStartSession",startSent.get()).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}}
   },
   onProgress={line->
    log("CONTROL",line)
    if(line.startsWith("iap2 identify ")){
     val id=Regex("(?:rx|tx|messageId)=0x([0-9a-fA-F]{1,4})").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull(16)
     if(id!=null)lastIap2MessageId=id
     try{val detail=JSONObject().put("stage","identification").put("iap2Profile",hybridProfile).put("usbHostGroup16Enabled",ident.usbHostTransportComponentEnabled).put("bluetoothGroup17Enabled",ident.wireless!=null).put("diagnosticSummary",line.take(500)).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt);if(id!=null)detail.put("messageId",id);MainActivity.record(this,"HYBRID_IAP2_IDENTIFICATION",detail)}catch(_:Throwable){}
    }
    when{
     line.startsWith("iap2 availability wired=")->recordCarPlayAvailability(line)
     line.startsWith("iap2 rx=0x4e0d wireless-carplay-update available=")->recordWirelessAvailability(line)
     line=="iap2 identification accepted"->phase("mfi_authentication")
     line=="iap2 authentication accepted"->{phase("phone_availability");note("AUTH_COMPLETE","iAP2 认证完成，等待 iPhone 接入车载热点")}
     line=="iap2 rx=0x5702 request-wifi-configuration"->recordStartupStep("wifi_config_requested","phone_availability","message=0x5702")
     line.startsWith("iap2 tx=0x5703")->{
      recordStartupStep("wifi_config_queued","phone_availability","message=0x5703")
      sampleAfterHandoff()
     }
     line=="iap2 tx=0x4301 carplay-start-session"-> {
      if(sessions.isEmpty()&&!videoReady){
       phase("phone_projection_start")
       val probe=returnPathProbe
       if(probe!=null)note("PHONE_START","PHONE_PROBE http://${wifi.localAddress.hostAddress}:${WifiReturnPathProbe.PORT}")
       else note("PHONE_START","正在等待 iPhone 通过车载热点接入投屏。")
      }
     }
     line.startsWith("iap2 tx=0x4301 carplay-start-session retry=no-airplay-tcp")->try{
      MainActivity.record(this,"HYBRID_START_SESSION_SENT",JSONObject()
       .put("stage","retry").put("messageId",0x4301).put("retryCount",1)
       .put("addressFamily","ipv4")
       .put("localAddress",ipv4WireAddress)
       .put("localPort",airplayPort).put("addressCount",1).put("ipv6ListenerAvailable",ipv6ListenerAvailable)
       .put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))
     }catch(_:Exception){}
     line.startsWith("iap2 rx=0x4e0e device-transport-identifier ")->{
      val bluetoothId=line.substringAfter("bluetooth=").substringBefore(" ")
      val usbId=line.substringAfter("usb=").substringBefore(";")
      if(UsbBtHostStack.isBluetoothAddress(bluetoothId))authenticatedPhoneAddress.compareAndSet(null,bluetoothId)
      try{MainActivity.record(this,"HYBRID_TRANSPORT_IDENTIFIER",JSONObject().put("stage","received").put("bluetoothIdentifierPresent",bluetoothId.isNotBlank()&&bluetoothId!="none").put("usbTransportIdentifierPresent",usbId.isNotBlank()&&usbId!="none").put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
      log("HYBRID_TRANSPORT_NOTICE",line)
     }
     line.contains("0x4e0d")->log("HYBRID_TRANSPORT_NOTICE",line)
    }
   },
  )}catch(error:IOException){
   if(bluetoothHandoffKeepsWifi())return
   if(btStack.hasPhoneDisconnected()&&tryAlternatePhone(error,"iap2_phone_disconnected",wifi))return
   throw error
  }
  if(bluetoothHandoffKeepsWifi())return
  log("HYBRID_CONTROL_END","terminal=${result.terminal} stage=${result.stage} startSessions=${result.carPlayStartSessionsSent} wifiConfigurations=${result.wifiConfigurationsSent} transportNotification=${result.transportNotificationSeen} airplayAccepted=${airplayTcpAccepted.get()} activeSessions=${sessions.size} videoReady=$videoReady usbLinked=$usbLinked")
  try{MainActivity.record(this,"HYBRID_CONTROL_END",JSONObject().put("terminal",result.terminal.name.lowercase(Locale.US)).put("stage",result.stage.name.lowercase(Locale.US)).put("iap2Profile",hybridProfile).put("usbHostGroup16Enabled",ident.usbHostTransportComponentEnabled).put("bluetoothGroup17Enabled",advertiseBluetoothComponent).put("wifiCredentialsIncluded",includeWifiCredentials).put("addressCount",listeners.size).put("ipv6ListenerAvailable",ipv6ListenerAvailable).put("apBssidPresent",wifi.apBssid!=null).put("probeHitCount",returnPathProbe?.hitCount?:0).put("carPlayStartSessionsSent",result.carPlayStartSessionsSent).put("wifiConfigurationsSent",result.wifiConfigurationsSent).put("transportNotificationSeen",result.transportNotificationSeen).put("wirelessCarPlayAvailableSeen",result.wirelessCarPlayAvailableSeen).put("airplayTcpAccepted",airplayTcpAccepted.get()).put("sessionCount",sessions.size).put("videoReady",videoReady).put("tunnelOpen",handoff.tunnelReady).put("usbLinked",usbLinked).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  try{MainActivity.record(this,"HYBRID_MDNS_STATE",JSONObject().put("stage","control_end_snapshot").put("diagnosticSummary",handoff.diagnosticSnapshot()).put("tunnelOpen",handoff.tunnelReady).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  fun canRetryHandoff():Boolean=bootAutoConnect&&rememberedPhoneConfirmed&&startSent.get()&&
   !videoReady&&session==null&&!selectedAirPlayResponding()&&
   runCatching{currentWirelessBinding(wifi,verifyIpv6=true)}.isSuccess
  if(!closed&&result.terminal==Iap2WirelessControlTerminal.TIMED_OUT){
   recordPeer("handoff_timed_out",peerEvidence.sample())
   val tcpAccepted=airplayTcpAccepted.get()
   val timeout=IOException("Timed out waiting for Wi-Fi AirPlay after authentication; airplayTcpAccepted=$tcpAccepted")
   val failure=if(startSent.get()&&!tcpAccepted)
    wifiHandoffFailure(if(useP2p)"尚未收到 iPhone 通过 Wi-Fi Direct 发起的投屏连接。"
      else "尚未收到 iPhone 通过车载热点发起的投屏连接。",timeout)
   else timeout
   val retry=canRetryHandoff()
   synchronized(resourceLock){
    // RECORD can finish after the control loop times out, while diagnostics are collected.
    if(closed||videoReady||session?.isClosed==false||wifiRecoveryInProgress())return
    if(!tryAlternatePhone(failure,"phone_projection_start",wifi))fail(failure,"phone_projection_start",retry)
   }
   return
  }
  if(!closed&&result.terminal==Iap2WirelessControlTerminal.CHANNEL_CLOSED){
   usbLinked=false
   log("HYBRID_USB_DETACHED","stage=iap2_channel_closed startSessionSent=${startSent.get()} airplayAccepted=${airplayTcpAccepted.get()} activeSessions=${sessions.size} videoReady=$videoReady")
   val waitUntil=SystemClock.elapsedRealtime()+if(startSent.get()||airplayTcpAccepted.get())30000L else 0L
   synchronized(resourceLock){
    if(closed||session!=null||wifiRecoveryInProgress())return
    note("IAP2_CHANNEL_CLOSED_WIFI_WAIT","Bluetooth RFCOMM channel closed; waiting for Wi-Fi AirPlay")
    // RECORD wakes this wait and hands all ongoing lifecycle work to AirPlay.
    // An open tunnel is not itself proof the phone completed that handoff.
    while(!closed&&session==null&&!wifiRecoveryInProgress()){
     val remaining=waitUntil-SystemClock.elapsedRealtime()
     if(remaining<=0L)break
     resourceLock.wait(remaining)
    }
    if(closed||session!=null||wifiRecoveryInProgress())return
   }
   recordPeer("control_closed",peerEvidence.sample())
   val retry=canRetryHandoff()
   synchronized(resourceLock){
    if(closed||session!=null||wifiRecoveryInProgress())return
    val invited=startSent.get()
    val failure=when {
     invited&&!airplayTcpAccepted.get() -> wifiHandoffFailure(
      "蓝牙控制通道已结束，尚未收到 iPhone 通过车载热点发起的投屏连接。",
      IOException("Bluetooth RFCOMM channel closed with no AirPlay TCP after invitation"))
     invited -> IOException("蓝牙控制通道关闭后，iPhone 未完成 Wi-Fi 投屏接续。")
     else -> IOException("蓝牙控制通道在投屏启动前关闭。")
    }
    if(!tryAlternatePhone(failure,"wifi_handoff_after_rfcomm_close",wifi))fail(failure,"wifi_handoff_after_rfcomm_close",retry)
   }
   return
  }
  if(!closed){if(videoReady)log("HYBRID_WIFI_SESSION_END","videoReady=true sessionCount=${sessions.size} usbLinked=$usbLinked");shutdown()}
 }
 private fun bindAirPlayIpv4(address:InetAddress):ServerSocket{
  val listener=try{
   LegacySockets.tcp(address,AIRPLAY_CONTROL_PORT)
  }catch(_:BindException){
   LegacySockets.tcp(address,0)
  }
  own(Closeable{listener.close()},"airplay_listener")
  server=listener
  recordStartupStep("listener_bound","airplay_listener","port=${listener.localPort} family=ipv4")
  return listener
 }
 private fun bindAirPlayIpv6(address:Inet6Address,port:Int,interfaceName:String,registerResource:Boolean=true):ServerSocket?{
  // Bind with the local scope; iPhone applies its own scope to the zone-free invitation.
  val bound=try{LegacySockets.tcp(address,port)}catch(error:IOException){
   log("NETWORK_LISTENER","optional_ipv6_bind_failed type=${error.javaClass.simpleName}")
   try{MainActivity.record(this,"HYBRID_WIFI_LISTENER",JSONObject()
    .put("stage","bind_failed").put("localAddress",address.hostAddress.substringBefore('%'))
    .put("interfaceName",interfaceName).put("localPort",port).put("addressFamily","ipv6")
    .put("success",false).put("errorType",error.javaClass.simpleName.take(48))
    .put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}
   return null
  }
  if(registerResource)own(Closeable{bound.close()},"airplay_listener_ipv6")
  return bound
 }
 private fun validateVehicleHotspot(snapshot:HybridNetworkSnapshot):HybridNetworkSnapshot{
  if(snapshot.ssid.toByteArray(Charsets.UTF_8).size>32)
   throw IOException("WIFI_CHECK_SETTINGS 车载热点名称超过 32 个 UTF-8 字节。")
  if(!snapshot.credential.available)
   throw IOException("WIFI_CHECK_SETTINGS 请在 DiPlay 的「车载热点」中核对系统热点口令。")
  return snapshot
 }
 private fun currentWirelessBinding(snapshot:HybridNetworkSnapshot,verifyIpv6:Boolean):HybridNetworkSnapshot{
  if(!useP2p)return HybridNetwork.requireCurrentConnection(this,snapshot,verifyIpv6)
  val p2p=p2pHotspot?:throw IOException("Wi-Fi Direct group is not available")
  return HybridNetwork.requireSameBinding(snapshot,p2p.current(),verifyIpv6)
 }
 private fun validateHotspotBinding(snapshot:HybridNetworkSnapshot,verifyIpv6:Boolean=true):HybridNetworkSnapshot{
  return try{currentWirelessBinding(snapshot,verifyIpv6)}catch(error:IOException){
   recordStartupStep("wifi_binding_changed","phone_projection_start","hotspot_changed_after_airplay_bind")
   if(useP2p)throw IOException("P2P_CONNECTION_FAILED Wi-Fi Direct 局域网的接口或地址已变化，请停止连接后重试。",error)
   throw IOException("WIFI_CHECK_SETTINGS 车载热点的地址或配置已变化，请核对车机热点后重试。",error)
  }
 }
 private fun awaitVehicleHotspot(timeoutMs:Long):HybridNetworkSnapshot{
  val startedAt=SystemClock.elapsedRealtime();var nextStatusAt=startedAt;var lastError:IllegalStateException?=null
  while(!closed){
   try{
    val snapshot=HybridNetwork.requireConnected(this)
    if(!snapshot.credential.available)throw IOException("WIFI_CHECK_SETTINGS 请在 DiPlay 的「车载热点」中核对系统热点口令。")
    log("HYBRID_WIFI","stage=ap_ready_before_bluetooth elapsedMs=${SystemClock.elapsedRealtime()-startedAt} ssidConfigured=true interfaceName=${snapshot.interfaceName} localAddress=${snapshot.localAddress.hostAddress}")
    return snapshot
   }catch(error:IllegalStateException){
    lastError=error
    val elapsed=SystemClock.elapsedRealtime()-startedAt
    if(elapsed>=timeoutMs)break
    if(SystemClock.elapsedRealtime()>=nextStatusAt){
     note("WIFI_WAIT","请在车机系统设置中开启便携式 Wi-Fi 热点。")
     log("HYBRID_WIFI","stage=waiting_for_ap elapsedMs=$elapsed wifiConnected=${HybridNetwork.isApEnabled(this)} reason=${safeFailureMessage(error.message)}")
     nextStatusAt=SystemClock.elapsedRealtime()+5000L
    }
    try{Thread.sleep(500L)}catch(interrupted:InterruptedException){Thread.currentThread().interrupt();throw IOException("等待车载热点时连接已停止。",interrupted)}
   }
  }
  if(closed)throw IOException("等待车载热点时连接已停止。")
  throw IOException("WIFI_CHECK_SETTINGS 车载热点尚未就绪，请开启热点后重试。",lastError)
 }
 private fun shutdown(userRequested:Boolean=false){
  val owned:List<Closeable>
  synchronized(resourceLock){if(closed)return;closed=true;hotspotRepair?.worker?.interrupt();hotspotRepair=null;owned=handles.toList();handles.clear();cleanupCount.incrementAndGet();resourceLock.notifyAll()}
  if(videoReady)captureRuntimeHotspot(when{
   userRequested -> "ui_stop_requested"
   failed.get() -> "projection_failure_cleanup"
   else -> "projection_stopped"
  })
  refreshMediaButtonOwner()
  try{MainActivity.record(this,"HYBRID_PROJECTION_STATE",JSONObject().put("phase",activeDiagnosticStage).put("airplayTcpAccepted",airplayTcpAccepted.get()).put("sessionCount",sessions.size).put("videoReady",videoReady).put("usbLinked",usbLinked).put("wifiConnected",wirelessNetworkReady()).put("wifiIpReady",wifiIpReady).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Throwable){}
  wiredStartWatchdog?.terminate();wiredStartWatchdog=null
  runtimeWatch?.cancel(false);runtimeWatch=null
  wifiHandoff?.cancelPhoneResume()
   themeWorker.shutdownNow()
   val stopSignals=Runnable{vehicleSignals?.stop()}
   if(Looper.myLooper()==Looper.getMainLooper())stopSignals.run() else mainHandler.post(stopSignals)
  if(!failed.get())note("DISCONNECTED","CarPlay 已断开，正在释放连接");
  reportStartup(if(videoReady)"released_after_video" else "ended_before_video");audioObserver?.close();wheelControl?.close();usbLinked=false;watchdog.shutdownNow();work.shutdownNow();input.shutdownNow()
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
    pending.set(false)
    try{
     if(!failed.get())note("STOPPED",lastFailure?.let{"连接已释放：$it"}?:"连接已停止")
     CrashEvidence.end(this@WiredService,connectionId,"cleanup_complete",SystemClock.elapsedRealtime()-bornAt)
    }finally{
     mainHandler.post{try{stopForeground(true)}finally{try{stopSelf()}finally{cleanupCount.decrementAndGet()}}}
    }
   }
  },"legacy-release").start()
 }
 override fun onDestroy(){shutdown();if(live===this)live=null;super.onDestroy()}
}
