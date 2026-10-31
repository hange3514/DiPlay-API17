package local.headunit.diplay

import android.app.*
import android.content.*
import android.hardware.usb.*
import android.media.AudioManager
import android.net.VpnService
import android.os.*
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*
import org.json.*

/** API17-owned wired lifecycle; no BYD vehicle writes or wireless startup path. */
class WiredService:VpnService() {
 companion object {
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
 private val resourceLock=Any()
 private val latestPerformance=ConcurrentHashMap<String,String>()
 @Volatile private var usbLinked=false
 @Volatile private var usbPermissionPending=false
 private var wheelControl:WheelControl?=null
 private var audioObserver:OemAudioObserver?=null
 private lateinit var usb:UsbManager
 private lateinit var host:IphoneUsbHost
 private val handles=CopyOnWriteArrayList<Closeable>()
 private val resourceNames=IdentityHashMap<Closeable,String>()
 @Volatile private var tunnel:ParcelFileDescriptor?=null
 @Volatile private var session:AirPlaySession?=null
 private var sessionGeneration=0L // protected by resourceLock; invalidates an older grace timer
 private val sessions=CopyOnWriteArrayList<AirPlaySession>()
 @Volatile private var server:ServerSocket?=null
 @Volatile private var sink:LegacyMedia?=null
 @Volatile private var authorization=CountDownLatch(0)
 @Volatile private var authorizationDenied=false
 @Volatile private var videoReady=false
 @Volatile private var lastFailure:String?=null
 private val watchdog=Executors.newSingleThreadScheduledExecutor()
 private val listener=object:AirPlaySessionListener {
  override fun onSessionActive(session:AirPlaySession){synchronized(resourceLock){
   if(closed||session.isClosed||!sessions.contains(session))return
   this@WiredService.session=session;sessionGeneration++;lastFailure=null;if(!videoReady)phase("first_video");note("AIRPLAY_ACTIVE","CarPlay协议会话已建立，等待画面和声音")
  }}
  override fun onSessionEnded(session:AirPlaySession){synchronized(resourceLock){
   val wasActive=this@WiredService.session===session;if(wasActive){this@WiredService.session=null;sessionGeneration++};val endedGeneration=sessionGeneration;sessions.remove(session)
   val error=session.protocolFailure
   if(error!=null&&!closed&&(wasActive||this@WiredService.session==null)){fail(IOException("CarPlay协议处理失败：$error"))}
   else if(!closed&&wasActive){note("AIRPLAY_ENDED","手机会话已结束");runCatching{watchdog.schedule({synchronized(resourceLock){if(!closed&&sessionGeneration==endedGeneration&&this@WiredService.session==null)fail(IOException("CarPlay音视频会话中断"))}},2,TimeUnit.SECONDS)}}
  }}
  override fun onCommand(session:AirPlaySession,type:String,params:Map<String,Any?>){if(type!="modesChanged")return;synchronized(resourceLock){if(closed||!sessions.contains(session)||(this@WiredService.session!=null&&this@WiredService.session!==session))return;sink?.speechMode(params)}}
  override fun onDebugLog(message:String){log("AIRPLAY",message)}
  override fun onTransportError(message:String){fail(IOException(message))}
 }
 fun log(stage:String,text:String){if(text.startsWith("TRACE ")||stage=="CONTROL"&&text.startsWith("iap2 rx=")&&text.substringAfter("0x","").toIntOrNull(16)?.let{DiagnosticPolicy.skipFrame(it)}==true)return
  val metric=when {stage=="MEDIA"&&(text.startsWith("video_progress ")||text.startsWith("audio_progress ")||text.startsWith("audio_checkpoint "))->(if(text.startsWith("audio_checkpoint "))"audio_progress" else text.substringBefore(' '))+if(text.startsWith("audio_progress ")||text.startsWith("audio_checkpoint ")) ":"+text.substringAfter("role=").substringBefore(' ') else "";stage=="AIRPLAY"&&text.startsWith("audio_modes ")->"audio_modes";stage=="MEDIA"&&text.startsWith("microphone_")->"microphone";stage=="AIRPLAY"&&text.startsWith("Receive:")->text.substringBefore(" packets=").take(80);else->null}
  if(metric!=null&&(latestPerformance.containsKey(metric)||latestPerformance.size<12))latestPerformance[metric]="ageMs=${SystemClock.elapsedRealtime()-bornAt} "+text.take(1200)
  try{MainActivity.record(this,stage,JSONObject().put("detail",text.take(1200)).put("connection",connectionId).put("elapsedMs",SystemClock.elapsedRealtime()-bornAt))}catch(_:Exception){}}
 fun note(stage:String,text:String){if(stage=="FAILED")lastFailure=text;log(stage,text);sendBroadcast(Intent("local.headunit.diplay.STATUS").setPackage(packageName).putExtra("message",text).putExtra("stage",stage).putExtra("connection_id",connectionId))}
 private fun fail(e:Throwable){if(!closed&&failed.compareAndSet(false,true)){var cause:Throwable?=e;val chain=ArrayList<String>();while(cause!=null&&chain.size<4){chain.add(cause.javaClass.simpleName+": "+(cause.message?:"连接失败").take(160));cause=cause.cause};note("FAILED",chain.joinToString(" → "));shutdown()}}
 override fun onCreate(){super.onCreate();live=this;audioObserver=OemAudioObserver(this);wheelControl=WheelControl(this,object:WheelControl.Sink {override fun session():Any?=this@WiredService.session?.takeIf{!closed&&videoReady&&!it.isClosed};override fun send(token:Any,action:Int):Boolean {val current=this@WiredService.session;if(closed||current==null||current!==token||current.isClosed)return false;return when(action){1->current.sendMedia(5);2->current.sendMedia(4);3->current.sendTelephony(1);4->current.sendTelephony(3);5->{current.invokeSiri();true};else->false}}});usb=getSystemService(Context.USB_SERVICE) as UsbManager;host=IphoneUsbHost(this,usb,IphoneUsbMatcher.appleVendor(),onDiagnostic={log("USB",it)});handles.add(host.registerPermissionReceiver{authorizationDenied=it is IphoneUsbHost.PermissionResult.Denied;authorization.countDown()});registerMediaButtonOwner()}
 private fun registerMediaButtonOwner(){try{(getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerMediaButtonEventReceiver(ComponentName(this,MediaButtonWheelReceiver::class.java));log("WHEEL_KEY","media_button_owner_registered")}catch(t:Throwable){log("WHEEL_KEY","media_button_owner_failed="+t.javaClass.simpleName)}}
 private fun unregisterMediaButtonOwner(){try{(getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterMediaButtonEventReceiver(ComponentName(this,MediaButtonWheelReceiver::class.java))}catch(_:Throwable){}}
 override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
  if(started)return START_NOT_STICKY;usbBatchId=i?.getStringExtra("usb_batch_id").orEmpty().take(80);usbProfile=if(usbBatchId.isEmpty())UsbStartupProfile.DEFAULT else UsbStartupProfile.valid(i?.getIntExtra("usb_startup_profile",0)?:0);requestStartedAt=(i?.getLongExtra("request_started_at",bornAt)?:bornAt).takeIf{it in 1..bornAt}?:bornAt;vpnWaitMs=(i?.getLongExtra("vpn_wait_ms",0)?:0).coerceIn(0,(bornAt-requestStartedAt).coerceAtLeast(0));connectionId=i?.getStringExtra("connection_id").orEmpty();if(cleanupCount.get()>0||connectingWorkers.get()>0||MediaWorkers.busy()){note("FAILED","上一轮连接正在释放，请稍后重试");shutdown();return START_NOT_STICKY};started=true;selectedFps=PlaybackOptions.read(this);log("VIDEO_CONFIG","requestedFps=$selectedFps width=${ProjectionConfig.WIDTH} height=${ProjectionConfig.HEIGHT}");log("CONNECT_BUILD",runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrDefault("unknown"));note("CONNECT_START","正在建立CarPlay连接")
  watchdog.schedule({if(!closed&&!videoReady&&sink?.receivedVideo!=true)fail(IOException("等待CarPlay画面超时，请提交日志"))},180,TimeUnit.SECONDS)
  val notification=Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("DiPlay CarPlay").setContentText("正在连接，可在应用内停止").build();startForeground(1702,notification)
  try{work.execute{synchronized(resourceLock){if(closed)return@execute;connectingWorkers.incrementAndGet()};try{connect()}catch(e:Throwable){fail(e)}finally{connectingWorkers.decrementAndGet()}}}catch(e:Throwable){fail(e)};return START_NOT_STICKY
 }
 private fun check(){if(closed)throw InterruptedException("Connection cancelled")}
 private fun own(c:Closeable,name:String=c.javaClass.simpleName){synchronized(resourceLock){if(!closed){handles.add(c);resourceNames[c]=name;return}};c.close();throw InterruptedException("Connection cancelled")}
 private fun selectPhone():UsbDevice {
  val until=SystemClock.elapsedRealtime()+15000;var waiting=false
  while(true){check();val phones=host.discover();require(phones.size<=1){"检测到多台Apple USB设备，请只连接一台iPhone"};if(phones.size==1)return phones.single()
   if(!waiting){waiting=true;UsbPresenceLog.capture(this,"connect_wait");note("WAIT_USB","等待车机识别iPhone USB；请确认手机已解锁并插好数据线")}
   if(SystemClock.elapsedRealtime()>=until){UsbPresenceLog.capture(this,"connect_timeout");throw IOException("车机系统仍未列出iPhone USB设备。请拔线等待几秒后重新插入此前成功的接口；仍失败请提交日志")}
   Thread.sleep(250)
  }
 }
 private fun authorize(d:UsbDevice){
  check();if(usb.hasPermission(d))return
  authorization=CountDownLatch(1);authorizationDenied=false;usbPermissionPending=true
  try{host.requestPermission(d);note("USB_PERMISSION","请允许车机USB访问；如果手机提示信任，也请选择信任")
   val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45)
   while(!usb.hasPermission(d)){check();if(System.nanoTime()>end)throw IOException("USB授权超时");authorization.await(250,TimeUnit.MILLISECONDS);if(authorizationDenied){AutoUsbService.pause(this);throw IOException("USB授权被拒绝，自动重试已暂停")};if(!usb.hasPermission(d))Thread.sleep(100)}
  }finally{usbPermissionPending=false}
 }
 private var phaseAt=SystemClock.elapsedRealtime()
 private var previousPhase="start"
 @Synchronized private fun phase(name:String){val now=SystemClock.elapsedRealtime();startup.advance(name,now);log("CONNECT_TIMING","completed=$previousPhase durationMs=${now-phaseAt} next=$name");phaseAt=now;previousPhase=name}
 private fun connect(){
  phase("usb_discovery")
  val d=selectPhone();UsbPresenceLog.recordTransition(this,"selected",d);UsbPresenceLog.capture(this,"selected_phone");phase("usb_permission");authorize(d);check();phase("descriptors")
  val initial=usb.openDevice(d)?:throw IOException("无法打开iPhone USB")
  val configs=try{LegacyUsb.read(d,initial)}finally{initial.close()}
  log("DESCRIPTORS",configs.joinToString { "config=${it.id}:"+IphoneCarPlayConfiguration.describe(it) })
  var phone=d
  if(IphoneCarPlayConfiguration.find(d)==null){
   phase("usb_reenumeration");note("REENUMERATION","正在请求iPhone切换CarPlay USB模式")
   val latch=CountDownLatch(1);var transition:IphoneUsbHost.TransitionResult?=null
   host.requestCarPlayReenumerationAsync(d,Executor{it.run()}){transition=it;latch.countDown()};latch.await();if(transition is IphoneUsbHost.TransitionResult.Failed)throw (transition as IphoneUsbHost.TransitionResult.Failed).error
   val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);var found:UsbDevice?=null
   while(found==null&&System.nanoTime()<deadline){check();Thread.sleep(100);val next=host.discover().singleOrNull()?:continue;authorize(next);val c=usb.openDevice(next)?:continue;try{LegacyUsb.read(next,c);if(IphoneCarPlayConfiguration.find(next)!=null)found=next}finally{c.close()}}
   phone=found?:throw IOException("未发现包含USBMUX和NCM的CarPlay配置")
  }
  phase("configuration_takeover");check();val config=IphoneCarPlayConfiguration.find(phone)?:throw IOException("CarPlay配置缺失")
  note("USB_TAKEOVER","正在检查iPhone USB占用并选择CarPlay配置")
  val leaseConnection=usb.openDevice(phone)?:throw IOException("无法打开USB配置连接")
  val lease=try{UsbConfigurationLease(leaseConnection,LegacyUsb.configurations(phone),config.id,onDiagnostic={log("USB_NATIVE",it)})}catch(e:Throwable){leaseConnection.close();throw e};own(lease,"usb_configuration");lease.prepare();check();phase("usbmux_open")
  var us:Iap2UsbSession?=null
  host.openIap2UsbSessionAsync(phone,Executor{it.run()}){when(it){is IphoneUsbHost.Iap2SessionResult.Connected->us=it.session;is IphoneUsbHost.Iap2SessionResult.Failed->throw it.error}}
  val usbSession=us?:throw IOException("USBMUX打开失败");own(usbSession,"usbmux_usb")
  phase("ncm_open");val fn=NcmFunctionDiscovery.find(config)?:throw IOException("NCM功能缺失")
  val nc=usb.openDevice(phone)?:throw IOException("NCM USB打开失败")
  val ncm=try{NcmUsbBridge.open(nc,fn,profile=usbProfile,probe=usbBatchId.isNotEmpty(),onDiagnostic={log("NCM",it)})}catch(e:Throwable){nc.close();throw e};own(ncm,"ncm_usb");ncm.startEarlyReceive();usbLinked=true
  phase("usbmux_handshake");val mux=Iap2UsbMuxHost.open(usbSession,onDiagnostic={log("MUX",it)});own(mux,"usbmux_host")
  phase("lockdown_pairing")
  val store=IdentityStore(this);val savedPair=store.loadPair();log("LOCKDOWN","saved_pair_present=${savedPair!=null} profile=2");note("PAIRING",if(savedPair!=null)"正在使用已保存的 iPhone 配对信息" else "首次配对，请保持 iPhone 解锁并确认信任");var pair=savedPair?:LockdownPairingClient(mux,onDiagnostic={log("LOCKDOWN",it)}).pair("DiPlay API17",store.hostId,store.buid,90000,isCancelled={closed}).pairRecord.also{store.savePair(it)}
  phase("carkit_tls");check();val carkit=try{LockdownCarKitClient(mux).open(pair,"DiPlay API17")}catch(e:Exception){var cause:Throwable?=e;var rejected=false;while(cause!=null){val m=cause.message.orEmpty();if(m.contains("InvalidPairRecord",true)||m.contains("InvalidHostID",true))rejected=true;cause=cause.cause};if(!rejected)throw e;store.clearPair();note("PAIRING_RETRY","保存的配对已失效，请在iPhone上重新确认信任");pair=LockdownPairingClient(mux,onDiagnostic={log("LOCKDOWN",it)}).pair("DiPlay API17",store.hostId,store.buid,90000,isCancelled={closed}).pairRecord.also{store.savePair(it)};LockdownCarKitClient(mux).open(pair,"DiPlay API17")};own(carkit,"carkit")
  phase("iap2_setup");note("CARKIT","iPhone CarKit通道已打开，正在建立协议会话")
  val csm=Iap2Session.open(carkit,traceContext="api17",onTrace={log("IAP2",it)});own(csm,"iap2")
  phase("mfi_identity");val auth=VerifiedMfiCache.get(this){log("MFI","challenge signed bytes=$it")}
  val identity=store.identity();val mac=ncm.hostMac?:byteArrayOf(2,0,0,0,0,17);val macText=mac.joinToString(":"){"%02x".format(it.toInt() and 255)}
  // API17 has no per-app VPN routing. Only the USB link-local /64 is routed; no default/DNS routes.
  phase("vpn_bridge");val tun=Builder().setSession("DiPlay USB link only").setMtu(1500).addAddress("fe80::2",64).addRoute("fe80::",64).establish()?:throw IOException("VPN网卡建立失败");own(Closeable{tun.close()},"vpn_descriptor");tunnel=tun;check();PlatformSupport.setNonBlocking(tun)
  val bridge=Ipv6NcmBridge(ncm,tun,mac,onDiagnostic={log("USB_NETWORK",it)}){fail(it)};own(bridge,"ipv6_bridge");bridge.start()
  phase("airplay_listener");val configAir=AirPlayConfig("DiPlay API17",macText,macText,"320.20",AirPlayDisplayConfig(ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,fps=selectedFps),microphone=true)
  val renderer=LegacyMedia(null,ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,{log("MEDIA",it);if(!closed&&!videoReady&&it=="video_rendered_frames=1"){videoReady=true;startup.firstFrame(SystemClock.elapsedRealtime());reportStartup("first_frame");note("VIDEO_READY","已收到并解码CarPlay画面；按返回键显示控制面板")}});renderer.onVideoFailure={fail(IOException("视频解码失败",it))};renderer.routing=AudioRouting(this,renderer);own(renderer,"media");synchronized(Companion){sink=renderer;renderer.setSurface(surface)}
  val media=CarPlayMediaEngine(renderer,true);val pairings=store.pairings()
  val srv=ServerSocket();own(Closeable{srv.close()},"airplay_listener");srv.reuseAddress=true;srv.bind(InetSocketAddress(InetAddress.getByName("fe80::2"),7000));server=srv
  Thread({try{while(!closed){val socket=srv.accept();log("AIRPLAY_ACCEPT","control TCP accepted");if(!videoReady){phase("airplay_handshake");note("AIRPLAY_HANDSHAKE","投屏通道已连通，正在建立音视频会话")};try{socket.tcpNoDelay=true;socket.keepAlive=true;if(closed){socket.close();break};if(sessions.size>=4){socket.close();continue};val s=AirPlaySession(socket,configAir,identity,pairings,auth,listener,media);val accepted=synchronized(resourceLock){if(closed)false else{sessions.add(s);s.start();true}};if(!accepted)s.close()}catch(e:Throwable){runCatching{socket.close()};throw e}}}catch(e:Throwable){if(!closed)fail(e)}},"legacy-airplay").start()
  phase("iap2_auth_and_phone_start");note("IAP2_AUTH","正在进行CarPlay认证；成功后手机应显示CarPlay画面")
  val endpoint=Iap2WiredCarPlayEndpoint(listOf("fe80::2"),srv.localPort,identity.publicKeyHex,configAir.sourceVersion,macText)
  val ident=Iap2IdentificationConfig("DiPlay API17","SongDM-MX9A0","Independent",store.hostId,"0.2","API17",IphoneCarPlayConfiguration.usbMuxInterface(config)!!.id,language="zh")
  Iap2WiredControlClient(csm,Iap2MfiAuthenticationClient(auth)).run(ident,endpoint,availableCurrentMilliAmps=500,timeoutMillis=Iap2WiredControlClient.NO_TIMEOUT_MILLIS,onProgress={log("CONTROL",it);when(it){
   "iap2 identification accepted"->{phase("mfi_authentication");note("IAP2_AUTH","正在校验 CarPlay 认证")}
   "iap2 authentication accepted"->{phase("phone_availability");note("AUTH_COMPLETE","认证完成，等待 iPhone 启动 CarPlay")}
   "iap2 tx=0x4301 carplay-start-session"->{if(!videoReady){phase("phone_projection_start");note("PHONE_START","正在启动 iPhone 投屏通道")}}
  }},onSessionStartSent={bridge.startOutbound()})
  if(!closed)note("CONTROL_ENDED","手机控制通道已结束，请提交日志")
  shutdown()
 }
 override fun onRevoke(){AutoUsbService.pause(this);note("FAILED","VPN授权已撤销，自动重试暂停");shutdown()}
 private fun shutdown(){
  val owned:List<Closeable>
  synchronized(resourceLock){if(closed)return;closed=true;owned=handles.toList();handles.clear();cleanupCount.incrementAndGet()}
  note("DISCONNECTED","CarPlay 已断开，正在释放连接");
  reportStartup(if(videoReady)"released_after_video" else "ended_before_video");unregisterMediaButtonOwner();audioObserver?.close();wheelControl?.close();usbLinked=false;authorization.countDown();watchdog.shutdownNow();work.shutdownNow();input.shutdownNow()
  log("RELEASE_BEGIN","handles=${owned.size} sessions=${sessions.size}")
  val pending=java.util.concurrent.atomic.AtomicBoolean(true)
  Handler(Looper.getMainLooper()).postDelayed({if(pending.get()){log("RELEASE_TIMEOUT","cleanup still running after 12 seconds");for((thread,stack) in Thread.getAllStackTraces())if(thread.name.startsWith("legacy-")||thread.name.startsWith("api17-")||thread.name.startsWith("ncm-"))log("RELEASE_TIMEOUT","thread=${thread.name} state=${thread.state} at=${stack.take(5).joinToString()}")}},12000)
  Thread({
   fun release(name:String,action:()->Unit){val at=SystemClock.elapsedRealtime();log("RELEASE_STEP","begin=$name");try{action()}catch(e:Throwable){log("RELEASE_ERROR","$name ${e.javaClass.simpleName}: ${e.message}")}finally{log("RELEASE_STEP","end=$name durationMs=${SystemClock.elapsedRealtime()-at}")}}
   try{
    // Close the VPN before relying on onDestroy: system binding can otherwise keep this service alive.
    release("listener"){server?.close()}
    release("vpn"){tunnel?.close();tunnel=null}
    for(s in sessions)release("airplay"){s.close()};sessions.clear()
    for(h in owned.reversed())release(resourceNames[h]?:h.javaClass.simpleName){h.close()}
    LegacyUsb.clear()
   }finally{
    pending.set(false);cleanupCount.decrementAndGet();note("STOPPED",lastFailure?.let{"连接已释放：$it"}?:"连接已停止")
    Handler(Looper.getMainLooper()).post{stopForeground(true);stopSelf()}
   }
  },"legacy-release").start()
 }
 override fun onDestroy(){shutdown();if(live===this)live=null;super.onDestroy()}
}
