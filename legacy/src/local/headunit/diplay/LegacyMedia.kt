package local.headunit.diplay
import android.media.*
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

private data class LegacyAlsaOpened(val candidate:AlsaCapturePathSelector.Candidate,val handle:Long,val rate:Int,val channels:Int)

/** Bounded API17 media workers. No modern AudioAttributes or codec buffer APIs. */
class LegacyMedia @JvmOverloads constructor(surface:Surface?,private val width:Int,private val height:Int,private val preferredAvcDecoder:String?=null,private val log:(String)->Unit):MediaSink,Closeable {
 private val siriCapture=SiriCapturePolicy()
 fun speechMode(params:Map<String,Any?>){if(siriCapture.update(params))log("microphone_speech_mode=${siriCapture.mode}")}
 private class VideoTarget(val surface:Surface?,config:ByteArray?=null){
  val input=VideoInput().apply{config?.let{configure(it)}}
 }
 private val videoTargetLock=java.lang.Object()
 private var latestVideoConfig:ByteArray?=null
 private var firstVideoKeyframe=false
 @Volatile private var videoTarget=VideoTarget(surface)
 @Volatile private var calibration:VideoCalibrationCapture?=null
 @Volatile var videoDecoderName="unknown";private set
 fun beginVideoCalibration(fps:Int):Boolean=synchronized(videoTargetLock){
  if(closed.get()||videoFailed.get()||videoTarget.surface?.isValid!=true||renderedVideoFrames==0L||calibration!=null)return@synchronized false
  calibration=VideoCalibrationCapture(fps,System.nanoTime(),videoTarget.input.droppedCount())
  true
 }
 fun finishVideoCalibration():Pair<VideoCalibration.Sample,Long>?=synchronized(videoTargetLock){
  val sample=calibration?:return@synchronized null
  calibration=null
  if(closed.get()||videoFailed.get()||videoTarget.surface?.isValid!=true)return@synchronized null
  sample.sample(System.nanoTime(),videoTarget.input.droppedCount()) to sample.deliveryJitterMaxMs()
 }
 fun cancelVideoCalibration(){synchronized(videoTargetLock){calibration=null}}
 fun setSurface(surface:Surface?){
  synchronized(videoTargetLock){
   if(closed.get())return
   if(videoTarget.surface!==surface){
    // Each Surface owns its queue so the decoder cannot flush a new Surface's first IDR.
    calibration=null
    videoTarget.input.retire()
    videoTarget=VideoTarget(surface,latestVideoConfig)
   }
   videoTargetLock.notifyAll()
  }
 }
 @Volatile var renderedVideoFrames=0L;private set
 @Volatile var receivedVideo=false;private set
 @Volatile var routing:AudioRouting?=null
 fun setRoleVolume(role:String,value:Float){for(a in audio.values)if(a.role==role)a.volume(value)}
 @Volatile var playbackFocus:PlaybackFocus?=null
 fun setOutputVolume(value:Float){for(a in audio.values)a.volume(value)}
 @Volatile var onVideoFailure:((Throwable)->Unit)?=null
 private val closed=AtomicBoolean(false)
 private val videoFailed=AtomicBoolean(false)
 private val videoRecoveryLock=Any()
 private var lastRecoveryNs=0L
 private val recoveryWork=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(1))
 @Volatile private var diagnostic:((String)->Unit)?=null
 @Volatile private var recovery:(()->Unit)?=null
 private val audio=ConcurrentHashMap<AudioStreamId,AudioOutput>()
 private val microphones=ConcurrentHashMap<AudioStreamId,Microphone>()
 private val videoThread=MediaWorkers.start("api17-video"){videoLoop()}
 override fun setVideoDiagnosticHandler(type:Int,handler:(String)->Unit){diagnostic=handler}
 override fun setVideoRecoveryHandler(type:Int,handler:()->Unit){
  synchronized(videoRecoveryLock){recovery=handler;lastRecoveryNs=0}
 }
 override fun onVideoConfig(type:Int,codecData:ByteArray){
  synchronized(videoTargetLock){
   if(closed.get()||videoFailed.get()||!videoTarget.input.configure(codecData))return
   latestVideoConfig=codecData.copyOf()
  }
  log("video_configuration_changed eventType=video_configured streamType=$type payloadBytes=${codecData.size}")
 }
 override fun onVideoFrame(type:Int,naluBytes:ByteArray){receiveVideo(type,naluBytes,false,0L,System.nanoTime())}
 override fun onOwnedVideoFrame(type:Int,naluBytes:ByteArray,senderNanos:Long,arrivalNanos:Long){
  receiveVideo(type,naluBytes,true,senderNanos,arrivalNanos)
 }
 private fun receiveVideo(type:Int,bytes:ByteArray,owned:Boolean,senderNs:Long,arrivalNs:Long){
  if(bytes.isEmpty())return
  var logKeyframe=false
  val result=synchronized(videoTargetLock){
   if(closed.get()||videoFailed.get())return
   receivedVideo=true
   val target=videoTarget
   if(target.surface?.isValid!=true)return
   if(type==STREAM_TYPE_MAIN_SCREEN)calibration?.received(senderNs,arrivalNs,bytes.size)
   val offered=if(owned)target.input.offerOwned(bytes,senderNs,arrivalNs) else target.input.offer(bytes,senderNs,arrivalNs)
   if(offered.acceptedKeyframe&&!firstVideoKeyframe){firstVideoKeyframe=true;logKeyframe=true}
   offered
  }
  if(logKeyframe)log("video_keyframe_queued eventType=video_first_keyframe streamType=$type payloadBytes=${bytes.size}")
  result.recoveryReason?.let{requestRecovery(it)}
 }
 private fun requestRecovery(reason:String){
  val queued=synchronized(videoRecoveryLock){
   val handler=recovery?:return
   val now=System.nanoTime()
   if(closed.get()||(lastRecoveryNs!=0L&&now-lastRecoveryNs<TimeUnit.SECONDS.toNanos(2)))return
   try{
    recoveryWork.execute{if(!closed.get()&&recovery===handler)runCatching{handler()}}
    lastRecoveryNs=now
    true
   }catch(_:RejectedExecutionException){false}
  }
  if(queued)log("video_recovery reason=$reason")
 }
 private class SubmittedVideo(val generation:Long,val senderNs:Long,val arrivedNs:Long,val submittedNs:Long)
 private class DecodedVideo(
  val index:Int,val generation:Long,val size:Int,val flags:Int,val arrivedNs:Long,
  val readyNs:Long,val deadlineNs:Long,
 )
 private fun startDecoder(format:MediaFormat,surface:Surface,name:String?):MediaCodec{
  val decoder=if(name==null)MediaCodec.createDecoderByType("video/avc") else MediaCodec.createByCodecName(name)
  try{
   decoder.configure(format,surface,null,0)
   decoder.start()
   return decoder
  }catch(error:Exception){
   runCatching{decoder.release()}
   throw error
  }
 }
 private fun videoLoop(){
  var codec:MediaCodec?=null
  var configured:ByteArray?=null
  var buffers:Array<ByteBuffer>?=null
  var inputIndex=-1
  var target=videoTarget
  var generation=target.input.generation
  var pending:VideoInput.Frame?=null
  var held:DecodedVideo?=null
  val submitted=LinkedHashMap<Long,SubmittedVideo>()
  var lastPts=0L
  var decoderActivityNs:Long?=null
  val pacing=VideoPlayoutTiming()
  val info=MediaCodec.BufferInfo()
  var stage="waiting_config"
  var surfaceFrames=0
  var firstInput=false
  var firstOutput=false
  var lastStats=System.nanoTime()
  var windowFrames=0
  var skipped=0
  var retiredDrops=0
  var retiredPeak=0
  var queueMaxMs=0L
  var decodeMaxMs=0L
  var receiveToSurfaceMaxMs=0L
  var lastSurfaceNs=0L
  var surfaceGapMaxMs=0L
  var releaseMaxUs=0L
  var dequeueOverrunMaxUs=0L
  var inputMaxUs=0L
  var inFlightPeak=0
  var paceHeldFrames=0
  var paceHoldMaxMs=0L
  var pressureSkips=0
  var earlyInputReleases=0
  var inputReadyAfterEarlyRelease=0

  fun release(index:Int,render:Boolean){
   val current=codec?:return
   val at=System.nanoTime()
   current.releaseOutputBuffer(index,render)
   releaseMaxUs=maxOf(releaseMaxUs,(System.nanoTime()-at)/1000)
  }
  fun releaseHeld(render:Boolean):Boolean{
   val output=held?:return false
   held=null
   stage="surface_release"
   val show=render&&!closed.get()&&target===videoTarget&&target.input.generation==output.generation&&
    target.surface?.isValid==true
   release(output.index,show)
   if(!show){skipped++;return false}
   val renderedAt=System.nanoTime()
   val firstOnSurface=synchronized(videoTargetLock){
    if(closed.get()||target!==videoTarget||target.input.generation!=output.generation||target.surface?.isValid!=true)return false
    calibration?.submitted(output.arrivedNs,renderedAt)
    renderedVideoFrames++
    surfaceFrames++
    surfaceFrames==1
   }
   windowFrames++
   if(lastSurfaceNs!=0L)surfaceGapMaxMs=maxOf(surfaceGapMaxMs,(renderedAt-lastSurfaceNs)/1000000)
   lastSurfaceNs=renderedAt
   if(output.arrivedNs!=0L)receiveToSurfaceMaxMs=maxOf(receiveToSurfaceMaxMs,(renderedAt-output.arrivedNs)/1000000)
   if(output.deadlineNs>output.readyNs){
    paceHeldFrames++
    paceHoldMaxMs=maxOf(paceHoldMaxMs,(renderedAt-output.readyNs)/1000000)
   }
   if(firstOnSurface)log("video_surface_first_frame eventType=video_surface_frame frameCount=$renderedVideoFrames outputSize=${output.size} outputFlags=${output.flags} surfaceValid=true")
   if(renderedVideoFrames==1L)log("video_rendered_frames=1")
   if(firstOnSurface)diagnostic?.invoke("first frame rendered")
   return true
  }
  fun dispose(){
   runCatching{releaseHeld(false)}
   runCatching{codec?.stop()}
   runCatching{codec?.release()}
   codec=null
   configured=null
   buffers=null
   inputIndex=-1
   pending=null
   submitted.clear()
   decoderActivityNs=null
   pacing.reset()
  }
  fun takeSubmitted(pts:Long):SubmittedVideo?{
   submitted.remove(pts)?.let{return it}
   // Some legacy components do not echo the timestamp. An unknown output cannot be paced.
   val iterator=submitted.values.iterator()
   if(!iterator.hasNext())return null
   val fallback=iterator.next()
   iterator.remove()
   return SubmittedVideo(fallback.generation,0L,fallback.arrivedNs,fallback.submittedNs)
  }
  fun drain(waitUs:Long=0L){
   val current=codec?:return
   stage="drain"
   val drainStartedAt=System.nanoTime()
   val oldestArrived=held?.arrivedNs?:submitted.values.firstOrNull()?.arrivedNs?:drainStartedAt
   val pressure=VideoPlayoutTiming.underPressure(target.input.size(),submitted.size,drainStartedAt-oldestArrived,surfaceFrames==0)
   when(VideoPlayoutTiming.drainAction(held?.deadlineNs,drainStartedAt,pressure)){
    VideoPlayoutTiming.DrainAction.WAIT_FOR_HELD->return
    VideoPlayoutTiming.DrainAction.RENDER_HELD->{releaseHeld(true);return}
    VideoPlayoutTiming.DrainAction.DEQUEUE->Unit
   }
   // Keep ordered decoded frames in the codec during normal playout. Coalesce only when
   // a real input backlog is building, keeping all H.264 references in the decoder.
   for(i in 0 until 16){
    val budget=if(i==0)waitUs else 0L
    val at=System.nanoTime()
    val index=current.dequeueOutputBuffer(info,budget)
    val now=System.nanoTime()
    dequeueOverrunMaxUs=maxOf(dequeueOverrunMaxUs,(now-at)/1000-budget)
    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){log("video_output_format_changed");continue}
    if(index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)continue
    if(index<0)break
    val codecConfig=info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0
    val emptyEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0&&info.size==0
    if(codecConfig||emptyEnd){release(index,false);continue}
    val stamp=takeSubmitted(info.presentationTimeUs)
    decoderActivityNs=now
    if(stamp==null||stamp.generation!=target.input.generation||target!==videoTarget){
     release(index,false)
     skipped++
     continue
    }
    decodeMaxMs=maxOf(decodeMaxMs,(now-stamp.submittedNs)/1000000)
    if(held!=null){releaseHeld(false);pressureSkips++;calibration?.skipped(now)}
    val deadline=pacing.deadline(stamp.senderNs,stamp.arrivedNs,now,
     surfaceFrames==0||pressure||now-stamp.arrivedNs>=VideoInput.MAX_DELTA_AGE_NS)
    held=DecodedVideo(index,stamp.generation,info.size,info.flags,stamp.arrivedNs,now,deadline)
    if(!firstOutput){firstOutput=true;log("video_decoder_output eventType=video_first_output outputSize=${info.size} outputFlags=${info.flags}")}
    if(!pressure)break
   }
   if(held?.let{pressure||System.nanoTime()>=it.deadlineNs}==true)releaseHeld(true)
  }
  fun reportWindow(){
   val now=System.nanoTime()
   val windowMs=(now-lastStats)/1000000
   if(windowMs<5000L)return
   val metrics=target.input.takeMetrics()
   log("video_progress eventType=video_render_window streamType=110 windowMs=$windowMs submittedFrames=$windowFrames "+
    "skippedFrames=$skipped queueDepth=${metrics.depth} queuePeak=${maxOf(retiredPeak,metrics.peak)} "+
    "droppedFrames=${retiredDrops+metrics.dropped} queueMaxMs=$queueMaxMs decodeMaxMs=$decodeMaxMs "+
    "receiveToSurfaceMaxMs=$receiveToSurfaceMaxMs surfaceGapMaxMs=$surfaceGapMaxMs releaseMaxUs=$releaseMaxUs "+
    "dequeueOverrunMaxUs=$dequeueOverrunMaxUs inputMaxUs=$inputMaxUs inFlightPeak=$inFlightPeak "+
    "paceHeldFrames=$paceHeldFrames paceHoldMaxMs=$paceHoldMaxMs pressureSkips=$pressureSkips "+
    "earlyInputReleases=$earlyInputReleases inputReadyAfterEarlyRelease=$inputReadyAfterEarlyRelease")
   lastStats=now
   windowFrames=0;skipped=0;retiredDrops=0;retiredPeak=0;queueMaxMs=0;decodeMaxMs=0
   receiveToSurfaceMaxMs=0;surfaceGapMaxMs=0;releaseMaxUs=0;dequeueOverrunMaxUs=0
   inputMaxUs=0;inFlightPeak=submitted.size;paceHeldFrames=0;paceHoldMaxMs=0;pressureSkips=0
   earlyInputReleases=0;inputReadyAfterEarlyRelease=0
  }
  try{
   while(!closed.get()){
    if(target!==videoTarget){
     dispose()
     val retired=target.input.takeMetrics()
     retiredDrops+=retired.dropped
     retiredPeak=maxOf(retiredPeak,retired.peak)
     target=videoTarget
     generation=target.input.generation
     surfaceFrames=0
     lastSurfaceNs=0
     log("video_surface_changed surfaceValid=${target.surface?.isValid==true}")
     if(target.surface?.isValid==true)requestRecovery("surface_restored")
    }
    if(target.surface?.isValid!=true){
     dispose()
     synchronized(videoTargetLock){
      if(!closed.get()&&target===videoTarget&&target.surface?.isValid!=true){
       // A detached view needs no polling. An OEM Surface may become valid in place.
       if(target.surface==null)videoTargetLock.wait()else videoTargetLock.wait(50)
      }
     }
     continue
    }
    try{
     if(generation!=target.input.generation){
      releaseHeld(false)
      pacing.reset()
      generation=target.input.generation
      if(pending?.generation!=generation)pending=null
     }
     drain()
     if(pending==null){
      val activity=MediaDecoderActivity.waiting(submitted.size,decoderActivityNs,System.nanoTime(),surfaceFrames==0)
      pending=target.input.poll(0L)
      if(pending==null&&activity&&held==null){drain(5000);pending=target.input.poll(0L)}
      if(pending==null&&(!activity||held!=null)){
       val wait=held?.let{(it.deadlineNs-System.nanoTime()).coerceAtLeast(0L)}?:100_000_000L
       pending=target.input.poll(wait)
      }
     }
     if(target!==videoTarget)continue
     val packet=pending
     if(packet==null){reportWindow();continue}
     if(packet.generation!=target.input.generation){pending=null;continue}
     if(packet.config!==configured){
      stage="configure"
      require(target.surface?.isValid==true){"Video Surface is invalid"}
      dispose()
      pending=packet
      val(sps,pps)=MediaCodecSupport.avcParameterSets(packet.config)
      require(sps.isNotEmpty()&&pps.isNotEmpty()){"Invalid AVC parameter sets"}
      val format=MediaFormat.createVideoFormat("video/avc",width,height)
      format.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+sps))
      format.setByteBuffer("csd-1",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pps))
      format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,VideoInput.MAX_FRAME_BYTES)
      val outputSurface=checkNotNull(target.surface)
      val preferred=preferredAvcDecoder
      var selected=preferred
      val current=if(preferred==null)startDecoder(format,outputSurface,null) else try{
       startDecoder(format,outputSurface,preferred)
      }catch(error:Exception){
       if(target!==videoTarget||!outputSurface.isValid)throw error
       selected=null
       log("video_decoder_fallback eventType=video_decoder_fallback codec=$preferred errorType=${error.javaClass.simpleName}")
       startDecoder(format,outputSurface,null)
      }
      codec=current
      buffers=current.inputBuffers
      configured=packet.config
      videoDecoderName=selected?:runCatching{MediaCodec::class.java.getMethod("getName").invoke(current) as? String}.getOrNull()?:"platform_auto"
      log("video_decoder_started eventType=video_decoder_started codec=$videoDecoderName width=$width height=$height payloadBytes=${packet.config.size}")
     }
     val current=codec?:continue
     stage="input"
     val inputAt=System.nanoTime()
     fun frameIsCurrent()=!closed.get()&&target===videoTarget&&target.surface?.isValid==true&&
      packet.generation==target.input.generation
     fun stale(at:Long):Boolean{
      val age=at-packet.arrivedNs
      return age>VideoInput.MAX_DELTA_AGE_NS&&
       (age>VideoInput.MAX_IDR_AGE_NS||!(packet.startsGop||VideoInput.isKeyframe(packet.bytes)))
     }
     if(!frameIsCurrent()){pending=null;continue}
     if(stale(inputAt)){
      pending=null
      if(target.input.reject(packet,inputAt))requestRecovery("decoder_backpressure")
      reportWindow()
      continue
     }
     if(inputIndex<0){
      var earlyRelease=false
      val inputBuffers=buffers!!
      if(inputBuffers.size==1&&packet.bytes.size>inputBuffers[0].capacity()){
       pending=null
       if(target.input.reject(packet,inputAt))requestRecovery("codec_input_capacity")
       reportWindow()
       continue
      }
      inputIndex=VideoCodecInput.acquire(inputBuffers.size,held?.let{it.deadlineNs-inputAt},current::dequeueInputBuffer){
       val releaseAt=System.nanoTime()
       if(!frameIsCurrent()||stale(releaseAt))false
       else{
        val beforeDeadline=held?.let{it.deadlineNs>releaseAt}==true
        releaseHeld(true).also{
         if(it){
          earlyRelease=beforeDeadline
          if(earlyRelease)earlyInputReleases++
          stage="input"
         }
        }
       }
      }
      if(earlyRelease&&inputIndex>=0)inputReadyAfterEarlyRelease++
     }
     val now=System.nanoTime()
     if(!frameIsCurrent()){pending=null;continue}
     if(stale(now)){
      pending=null
      if(target.input.reject(packet,now))requestRecovery("decoder_backpressure")
     }else if(inputIndex>=0){
      val buffer=buffers!![inputIndex]
      buffer.clear()
      val byteCount=try{VideoBufferWriter.putAnnexB(packet.bytes,buffer)}catch(_:IllegalArgumentException){0}
      if(byteCount==0){
       pending=null
       if(target.input.reject(packet,now))requestRecovery(if(packet.bytes.size>buffer.capacity())"codec_input_capacity" else "invalid_nal_unit")
      }else{
       val submittedAt=System.nanoTime()
       val pts=maxOf(submittedAt/1000,lastPts+1)
       lastPts=pts
       current.queueInputBuffer(inputIndex,0,byteCount,pts,0)
       inputIndex=-1
       if(submitted.size>=64){val staleStamp=submitted.keys.iterator();staleStamp.next();staleStamp.remove()}
       submitted[pts]=SubmittedVideo(packet.generation,packet.senderNs,packet.arrivedNs,submittedAt)
       queueMaxMs=maxOf(queueMaxMs,(submittedAt-packet.arrivedNs)/1000000)
       inFlightPeak=maxOf(inFlightPeak,submitted.size)
       decoderActivityNs=submittedAt
       pending=null
       if(!firstInput){firstInput=true;log("video_decoder_input eventType=video_first_input payloadBytes=$byteCount")}
      }
     }
     inputMaxUs=maxOf(inputMaxUs,(System.nanoTime()-inputAt)/1000)
     reportWindow()
    }catch(error:Throwable){
     if(error is InterruptedException)throw error
     if(target!==videoTarget||target.surface?.isValid!=true){
      dispose()
      target.input.discardFrames()
      log("video_surface_unavailable eventType=video_surface_unavailable stage=$stage surfaceValid=${target.surface?.isValid==true}")
     }else throw error
    }
   }
  }catch(_:InterruptedException){
  }catch(error:Throwable){
   if(!closed.get()){
    synchronized(videoTargetLock){videoFailed.set(true);videoTarget.input.retire()}
    log("video_failed eventType=video_decoder_failure stage=$stage errorType=${error.javaClass.simpleName} surfaceValid=${target.surface?.isValid==true}")
    onVideoFailure?.invoke(error)
   }
  }finally{
   dispose()
  }
 }
 @Synchronized override fun onAudioStarted(id:AudioStreamId,format:com.shilapi.xcertplay.airplay.AudioFormat,firstSample:Int){if(closed.get())return;log("audio_stream_start type=${id.type} kind=${id.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} payloadType=${format.payloadType} firstRtpSample=${firstSample.toLong() and 0xffffffffL}");val previous=audio.keys.filter{it.type==id.type&&it.audioType==id.audioType};for(key in previous)audio.remove(key)?.let{it.close();check(it.released()){"Previous audio worker is still releasing"}};val role=LegacyAudioPolicy.role(id.type,format.audioType);val callMode=role=="call";if(callMode){routing?.beginCallAudio();CallDuplexAB.callStreamStarting(routing?.captureContext())};try{audio[id]=AudioOutput(id,format,callMode)}catch(t:Throwable){if(callMode)routing?.endCallAudio();throw t}}
 override fun onAudioRtp(id:AudioStreamId,format:com.shilapi.xcertplay.airplay.AudioFormat,rtp:ByteArray,sample:Int){audio[id]?.offer(rtp,sample)}
 @Synchronized override fun onAudioStopped(id:AudioStreamId){audio.remove(id)?.let{log("audio_stopped type=${id.type} kind=${id.audioType}");it.close();val focusRole=if(it.role=="assistant")"media" else it.role;if(audio.values.none{v->(if(v.role=="assistant")"media" else v.role)==focusRole})routing?.playbackEnded(focusRole)}}
 @Synchronized override fun onMicrophoneStarted(id:AudioStreamId,config:MicrophoneConfig){if(closed.get())return;val previous=microphones.values.toList();for(m in previous)m.close();check(previous.all{it.released()}){"Previous microphone is still releasing"};microphones.clear();microphones[id]=Microphone(config,routing?.captureContext()?.let{AudioRouting.secondMusic(it)==10}?:false)}
 @Synchronized override fun onMicrophoneStopped(id:AudioStreamId){microphones.remove(id)?.close()}
 inner class AudioOutput(id:AudioStreamId,private val f:com.shilapi.xcertplay.airplay.AudioFormat,private val ownsCallMode:Boolean):Closeable {
  val role=LegacyAudioPolicy.role(id.type,f.audioType);private var stream=routing?.stream(role)?:AudioManager.STREAM_MUSIC
  private var playing=false;private val navigationActivity=NavigationActivity()
  private val callPlayout=if(role=="call"||f.audioType.equals("alert",ignoreCase=true))CallPlayoutBuffer(f.sampleRate)else null
  private val stop=AtomicBoolean(false)
  private val queue=if(callPlayout!=null)AudioInput(capacity=96,maxQueuedBytes=64*1024)else AudioInput()
  @Volatile private var track:AudioTrack?=null
  // Keep call PCM in its negotiated format. The on-device 12-profile replay
  // found clear speech for STREAM_MUSIC / MODE_NORMAL / swapped PCM at 32 kHz
  // mono. The prior software 32->48 kHz + mono->stereo conversion was not
  // covered by that audible result, so do not add it to the production path.
  private val outputChannels=LegacyAudioPolicy.outputChannels(role,f.channels)
  private val outputRate=LegacyAudioPolicy.outputRate(role,f.sampleRate)
  private val rateConverter:((ByteArray)->ByteArray)?=when {
   role=="call"&&outputRate!=f.sampleRate -> Pcm16RateConverter(f.sampleRate,outputRate,outputChannels).let{converter->{input:ByteArray->converter.convert(input)}}
   outputRate!=f.sampleRate -> PcmDoubleRate(outputChannels).let{converter->{input:ByteArray->converter.convert(input)}}
   else -> null
  }
  private val pcmEvidence=PcmEvidence();@Volatile private var lastHead=0L
  @Volatile private var appliedVolume=Float.NaN;private var bytesWritten=0L;private var mutedPackets=0L;private var lastProgress=0L;private var checkpoints=0
  private val thread=MediaWorkers.start("api17-audio"){loop()}
  fun offer(b:ByteArray,t:Int){
   if(stop.get())return
   if(callPlayout==null){queue.offer(b,t);return}
   val payloadBytes=b.size-MicrophonePacketizer.RTP_HEADER_LEN
   val frames=when(f.codec){
    AudioCodecKind.LPCM->if(f.channels in 1..2&&payloadBytes%(2*f.channels)==0)payloadBytes/(2*f.channels)else 0
    AudioCodecKind.AAC_LC->if(payloadBytes>0)1024 else 0
    AudioCodecKind.OPUS->0
   }
   if(frames<=0){queue.drop();return}
   // AudioStream delivers a fresh decrypted RTP buffer and never mutates it after delivery.
   queue.offerOwned(b,t,frames)
  }
  private fun trackQueuedFrames():Long {
   val current=track?:return -1L
   val head=runCatching{current.playbackHeadPosition.toLong() and 0xffffffffL}.getOrNull()?:return -1L
   val queued=(bytesWritten/(2*outputChannels)-head) and 0xffffffffL
   return if(queued<=outputRate*2L)queued else -1L
  }
  @Synchronized fun volume(value:Float){val t=track?:return;if(appliedVolume==value)return;runCatching{if(t.setStereoVolume(value,value)==AudioTrack.SUCCESS)appliedVolume=value}}
  private fun stopIdleNavigation(){if(playing&&role=="navigation"&&!navigationActivity.active(System.nanoTime())){track?.stop();playing=false;log("navigation_idle_stopped")}}
  private fun openTrack():AudioTrack {
   val channels=if(outputChannels==1)android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
   val encoding=android.media.AudioFormat.ENCODING_PCM_16BIT
   val min=AudioTrack.getMinBufferSize(outputRate,channels,encoding)
   require(min>0)
   val t=AudioTrack(stream,outputRate,channels,encoding,maxOf(min*2,outputRate*outputChannels/5),AudioTrack.MODE_STREAM)
   try{
    synchronized(this){
     require(t.state==AudioTrack.STATE_INITIALIZED)
     if(role=="navigation"&&stream!=AudioManager.STREAM_MUSIC)
      check(t.streamType==stream){"Navigation AudioTrack stream remapped to ${t.streamType}"}
     check(t.setStereoVolume(0f,0f)==AudioTrack.SUCCESS){"Initial audio gain rejected"}
     appliedVolume=0f
     track=t
    }
    return t
   }catch(e:RuntimeException){
    runCatching{t.release()}
    throw e
   }
  }
  private fun useMediaForNavigation(phase:String,error:RuntimeException):Boolean {
   if(stop.get()||role!="navigation"||stream==AudioManager.STREAM_MUSIC||
      routing?.fallbackNavigationStream(stream,phase,error)!=true)return false
   val previous=synchronized(this){
    track.also{
     track=null
     appliedVolume=Float.NaN
     playing=false
    }
   }
   runCatching{previous?.stop()}
   runCatching{previous?.release()}
   stream=AudioManager.STREAM_MUSIC
   return true
  }
  private fun applyPlaybackVolume():Boolean {
   val level=routing?.packet(role)?:playbackFocus?.packet()?:1f
   volume(level)
   if(level!=0f)return true
   mutedPackets++
   progress()
   return false
  }
  private fun writePcm(bytes:ByteArray){
   val current=track?:return
   if(role=="call")CallDuplexAB.callDownlinkFirstPcm(routing?.captureContext())
   if(!playing){
    current.play()
    if(role=="navigation"&&stream!=AudioManager.STREAM_MUSIC)
     check(current.playState==AudioTrack.PLAYSTATE_PLAYING){"Navigation AudioTrack play rejected"}
    playing=true
   }
   if(role=="call")CallDuplexAB.injectPhaseTone(routing?.captureContext(),current,outputRate,outputChannels,stream)
   var at=0
   while(at<bytes.size&&!stop.get()){
    val n=current.write(bytes,at,bytes.size-at)
    if(n<=0)throw IllegalStateException("AudioTrack write failed: $n")
    val firstWrite=bytesWritten==0L
    at+=n
    bytesWritten+=n
    if(firstWrite&&role=="navigation")routing?.navigationTrackReady(stream)
   }
   if(bytesWritten==bytes.size.toLong())log("audio_first_pcm_written bytes=${bytes.size}")
   progress()
  }
  private fun write(input:ByteArray){
   val channelBytes=if(outputChannels!=f.channels)PcmEvidence.stereo(input)else input
   val bytes=rateConverter?.invoke(channelBytes)?:channelBytes
   pcmEvidence.observe(bytes)
   if(role=="navigation"&&!navigationActivity.packet(bytes,System.nanoTime())){stopIdleNavigation();return}
   if(!applyPlaybackVolume())return
   try{
    writePcm(bytes)
   }catch(e:RuntimeException){
    // The decoded packet can be retried whole until the first successful write.
    if(bytesWritten!=0L||!useMediaForNavigation(if(playing)"first_write" else "play",e))throw e
    openTrack()
    if(!stop.get()&&applyPlaybackVolume())writePcm(bytes)
   }
  }
  private fun progress() {
   val now = System.nanoTime()
   if (now - lastProgress < TimeUnit.SECONDS.toNanos(if (checkpoints < 5 || role == "assistant") 1 else 10)) return
   lastHead = runCatching { track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: lastHead }.getOrDefault(lastHead)
   lastProgress = now
   val tag = if (checkpoints++ < 5) "audio_checkpoint" else "audio_progress"
   val endian = if (role == "call") " ${pcmEvidence.byteOrderCandidateSummary()}" else ""
   val queueDrops = queue.drops()
   log("$tag role=$role stream=$stream written=$bytesWritten mutedPackets=$mutedPackets queued=${queue.size()} dropped=$queueDrops head=$lastHead peak=${pcmEvidence.peak} nonzero=${pcmEvidence.nonzero} volume=$appliedVolume$endian")
   if (role == "call") {
    val context = routing?.captureContext() ?: return
    val summary = "codec=${f.codec.name} inputRate=${f.sampleRate} inputChannels=${f.channels}" +
        " outputRate=$outputRate outputChannels=$outputChannels peak=${pcmEvidence.peak}" +
        " nonzeroSamples=${pcmEvidence.nonzero} mutedPackets=$mutedPackets queueDrops=$queueDrops"
    AudioRouting.logCallOutput(context, track, stream, tag, bytesWritten, lastHead, appliedVolume, summary)
   }
  }
  private fun loop(){var decoder:MediaCodec?=null
   var inputBuffers:Array<ByteBuffer>?=null;var outputBuffers:Array<ByteBuffer>?=null;var inFlight=0
   val info=MediaCodec.BufferInfo()
   fun drain(){val c=decoder?:return
    for(i in 0 until 32){val n=c.dequeueOutputBuffer(info,0)
     if(n>=0){inFlight=maxOf(0,inFlight-1);val b=outputBuffers!![n];b.clear();require(info.offset>=0&&info.size>=0&&info.offset<=b.capacity()-info.size){"Invalid audio output range"};b.position(info.offset);b.limit(info.offset+info.size);val out=ByteArray(info.size);b.get(out);c.releaseOutputBuffer(n,false);if(out.isNotEmpty())write(out)}
     else if(n==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)outputBuffers=c.outputBuffers
     else if(n!=MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)break
    }
   }
   try{require(f.channels in 1..2&&f.sampleRate in 8000..48000&&f.codec!=AudioCodecKind.OPUS)
    val t=try{openTrack()}catch(e:RuntimeException){
     if(!useMediaForNavigation("track_init",e))throw e
     openTrack()
    }
    if(f.codec==AudioCodecKind.AAC_LC){val m=MediaFormat.createAudioFormat("audio/mp4a-latm",f.sampleRate,f.channels);m.setInteger("is-adts",1);val value=(2 shl 11) or (MediaCodecSupport.aacFrequencyIndex(f.sampleRate) shl 7) or (f.channels shl 3);m.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf((value shr 8).toByte(),value.toByte())));decoder=MediaCodec.createDecoderByType("audio/mp4a-latm");decoder.configure(m,null,null,0);decoder.start();inputBuffers=decoder.inputBuffers;outputBuffers=decoder.outputBuffers}
    val nativeRate=runCatching{AudioTrack.getNativeOutputSampleRate(stream)}.getOrDefault(-1)
    log("audio_started role=$role stream=$stream codec=${f.codec} inputRate=${f.sampleRate} inputChannels=${f.channels} outputChannels=$outputChannels outputRate=$outputRate actualStream=${t.streamType} nativeRate=$nativeRate");var packets=0;var pending:AudioInput.Packet?=null
    while(!stop.get()){
     drain()
     if(pending==null){
      pending=queue.poll(if(inFlight==0)100 else 5)
      val packet=pending
      val timing=callPlayout
      if(packet!=null&&timing!=null&&!queue.expired(packet)){
       val arrival=timing.packetArrived(packet.arrivedNs,packet.audioFrames)
       val runway=if(arrival==CallPlayoutBuffer.Arrival.CHECK_TRACK&&bytesWritten>0&&inFlight==0){
        if(!playing&&role=="navigation")0L else if(playing&&appliedVolume>0f)trackQueuedFrames()else -1L
       }else -1L
       if(timing.shouldPrefill(arrival,runway)){
        val started=System.nanoTime()
        val frames=queue.awaitPrefill(packet,timing.targetFrames,timing.maxWaitMs)
        if(stop.get())break
        log("audio_call_prefill role=$role kind=${f.audioType} codec=${f.codec} stage=${arrival.name.lowercase(Locale.US)} "+
         "targetMs=${timing.targetFrames*1000/f.sampleRate} bufferedMs=${frames*1000L/f.sampleRate} "+
         "waitedMs=${(System.nanoTime()-started)/1_000_000} trackQueuedFrames=$runway")
       }
      }
     }
     val p=pending
     if(p==null){stopIdleNavigation();continue}
     if(queue.expired(p)){queue.drop();pending=null;continue}
     val c=decoder
     if(c==null){val wirePayload=p.bytes.copyOfRange(12,p.bytes.size);if(role=="call"){pcmEvidence.observeByteOrderCandidates(wirePayload);CallDuplexAB.captureDownlinkPcm(wirePayload,f.sampleRate,f.channels,p.sample)};/* AirPlay PCM16 is big-endian on the wire; Android AudioTrack requires little-endian samples. */write(MicrophonePacketizer.toAndroidPcm16(wirePayload));pending=null}
     else {val n=c.dequeueInputBuffer(5000)
      if(n>=0){val b=inputBuffers!![n];b.clear();val adts=MediaCodecSupport.adtsFrame(p.bytes.copyOfRange(12,p.bytes.size),f.sampleRate,f.channels);require(adts.size<=b.remaining());b.put(adts);c.queueInputBuffer(n,0,adts.size,(p.sample.toLong() and 0xffffffffL)*1000000/f.sampleRate,0);inFlight++;pending=null}
      // Preserve a packet across brief decoder backpressure; expiry bounds total latency.
     }
     if(pending==null&&++packets==1)log("audio_first_packet_written_or_queued")
     drain()
    }
    }catch(_:InterruptedException){}catch(e:Throwable){if(!stop.get())log("audio_failed="+e.javaClass.simpleName+" detail="+e.message+" written="+bytesWritten)}finally{if(role=="call")CallRouteSelfTest.finishPlaybackCapture();val endian=if(role=="call")" ${pcmEvidence.byteOrderCandidateSummary()}" else "";log("audio_summary role=$role stream=$stream written=$bytesWritten mutedPackets=$mutedPackets dropped=${queue.drops()} headBeforeStop=$lastHead volume=$appliedVolume peak=${pcmEvidence.peak} nonzero=${pcmEvidence.nonzero}$endian");stop.set(true);queue.clear();runCatching{decoder?.stop()};runCatching{decoder?.release()};runCatching{track?.stop()};runCatching{track?.release()};track=null;if(ownsCallMode)routing?.endCallAudio()}
  }
  fun released()=!thread.isAlive
  override fun close(){lastHead=runCatching{track?.playbackHeadPosition?.toLong()?.and(0xffffffffL)?:lastHead}.getOrDefault(lastHead);stop.set(true);queue.clear();runCatching{track?.pause()};runCatching{track?.flush()};thread.interrupt();if(!MediaWorkers.await(thread))log("audio_release_pending")}
 }
 inner class Microphone(private val c:MicrophoneConfig,private val supported:Boolean):Closeable {
  private val stop=AtomicBoolean(false);@Volatile private var recorder:AudioRecord?=null;@Volatile private var socket:DatagramSocket?=null
  private var activeSource=0
  private val readRecovery=MicrophoneReadRecoveryPolicy()
  private val telephonyFilter=if(c.audioType.equals("telephony",true))TelephonyMicFilter(c.sampleRate)else null
  private val thread=MediaWorkers.start("api17-mic"){capture()}
  private fun releaseRecorder(){val r=recorder?:return;runCatching{r.stop()};r.release();check(r.state==AudioRecord.STATE_UNINITIALIZED){"Microphone release incomplete"};recorder=null;activeSource=0;readRecovery.recorderStopped()}
  private fun alsaCapture(s:DatagramSocket,counters:MicrophoneCounters,buffer:ByteArray,context:android.content.Context?):Boolean{
   if(!AlsaProbe.available()){log("microphone_alsa_scan result=native_unavailable fallback=AudioRecord kind=${c.audioType}");return false}
   val inventory=try{AlsaCapturePathSelector.discover(context)}catch(e:Throwable){log("microphone_alsa_scan result=inventory_failed error=${e.javaClass.simpleName}");return false}
   val candidates=inventory.candidates.filterNot { it.isLoopback() }
   val candidateSummary=candidates.joinToString(","){it.path.substringAfter("/dev/snd/")+":"+it.source}.take(420)
   val procSummary=inventory.pcmText.lines().filter{it.contains("capture",true)}.joinToString(";").take(420)
   log("microphone_alsa_inventory profile=${inventory.profileKey} native=true cards=${inventory.cardsText.replace('\n',';').take(260)} pcmCapture=$procSummary candidates=$candidateSummary truncated=${inventory.truncated}")
   val attempts=ArrayList<String>()
    val beforeCards=LinkedHashMap<Int,AlsaCapturePathSelector.Candidate>()
    fun mixerSnapshot(candidate:AlsaCapturePathSelector.Candidate,phase:String){
     try{
      val raw=AlsaProbe.dumpControls(candidate.card)
      val header=Regex("controls=(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()?:0
      val lines=raw.lines().filter{it.contains(" CAP")}
      val relevant=if(lines.isNotEmpty())lines else raw.lines().drop(1).take(12)
      val summary=relevant.take(18).joinToString(";"){line->line.trim().replace(Regex("\\s+"),"_")}
       .filter{it.isLetterOrDigit()||it in "_.,:/;=x[]()+#-"}.take(700)
      val ok=!raw.startsWith("fail ")
      val failStage=Regex("step=([A-Za-z0-9_]+)").find(raw)?.groupValues?.get(1)?:"none"
      val failErrno=Regex("errno=(-?[0-9]+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()?:0
      log("microphone_alsa_mixer phase=$phase card=${candidate.card} readOnly=true result=${if(ok)"ok" else "unavailable"} stage=$failStage errno=$failErrno controlCount=$header snapshotTruncated=${summary.length>=700||raw.length>=6000} mixer=${if(summary.isEmpty())"none" else summary}")
     }catch(e:Throwable){log("microphone_alsa_mixer phase=$phase card=${candidate.card} readOnly=true result=exception stage=${e.javaClass.simpleName} controlCount=0 mixer=unavailable")}
    }
    fun captureStage(code:Int)=when(code){1->"open";2->"hw_refine";3->"invalid_range";4->"hw_params";5->"sw_params";6->"prepare";7->"ready";8->"start_failed";else->"unknown"}
   var nextIndex=0
   fun openCandidate(candidate:AlsaCapturePathSelector.Candidate):LegacyAlsaOpened?{
    val formats=inventory.captureFormats(candidate,c.sampleRate).toMutableList()
    var formatIndex=0
    while(formatIndex<formats.size&&!stop.get()){
     val(requestedRate,requestedChannels)=formats[formatIndex++]
      if(!beforeCards.containsKey(candidate.card)){beforeCards[candidate.card]=candidate.copy();mixerSnapshot(candidate,"before")}
     val params=IntArray(2)
      val status=IntArray(8)
      val handle=try{AlsaProbe.captureOpen(candidate.path,requestedRate,requestedChannels,params,status)}catch(e:Throwable){
       attempts.add("${candidate.path}:${requestedRate}x${requestedChannels}=jni_${e.javaClass.simpleName}")
       log("microphone_alsa_attempt path=${candidate.path} source=${candidate.source} requestedRate=$requestedRate requestedChannels=$requestedChannels result=jni_failed stage=jni errno=0 rateMin=0 rateMax=0 channelsMin=0 channelsMax=0 negotiatedRate=0 negotiatedChannels=0")
       return null
      }
      val stage=captureStage(status[0])
      if(handle==0L){
       attempts.add("${candidate.path}:${requestedRate}x${requestedChannels}=failed_${stage}_${status[1]}")
       log("microphone_alsa_attempt path=${candidate.path} source=${candidate.source} requestedRate=$requestedRate requestedChannels=$requestedChannels result=open_failed stage=$stage errno=${status[1]} rateMin=${status[2]} rateMax=${status[3]} channelsMin=${status[4]} channelsMax=${status[5]} negotiatedRate=${status[6]} negotiatedChannels=${status[7]}")
       // The requested format cannot affect opening the node or the initial HW_REFINE.
       if(status[0]==1||status[0]==2)return null
       if(formatIndex==1&&status[0] in 4..6){
        formats.addAll(AlsaCapturePathSelector.extraFormats(status[2],status[3],status[4],status[5])
         .filterNot { it in formats }.take(8))
       }
       continue
      }
     val rate=params[0];val channels=params[1]
      if(rate !in 8000..96000||channels !in 1..8){AlsaProbe.captureClose(handle);attempts.add("${candidate.path}:${requestedRate}x${requestedChannels}=bad_${rate}x${channels}");log("microphone_alsa_attempt path=${candidate.path} source=${candidate.source} requestedRate=$requestedRate requestedChannels=$requestedChannels result=invalid_params stage=${captureStage(status[0])} errno=${status[1]} rateMin=${status[2]} rateMax=${status[3]} channelsMin=${status[4]} channelsMax=${status[5]} negotiatedRate=$rate negotiatedChannels=$channels");continue}
     attempts.add("${candidate.path}:${requestedRate}x${requestedChannels}=ok_${rate}x${channels}")
      log("microphone_alsa_attempt path=${candidate.path} source=${candidate.source} requestedRate=$requestedRate requestedChannels=$requestedChannels result=opened stage=$stage errno=${status[1]} rateMin=${status[2]} rateMax=${status[3]} channelsMin=${status[4]} channelsMax=${status[5]} negotiatedRate=$rate negotiatedChannels=$channels")
      mixerSnapshot(candidate,"during")
     return LegacyAlsaOpened(candidate,handle,rate,channels)
    }
    return null
   }
   fun openNext():LegacyAlsaOpened?{
    while(nextIndex<candidates.size&&!stop.get()){
     val candidate=candidates[nextIndex++]
     val opened=openCandidate(candidate)
     if(opened!=null)return opened
    }
    return null
   }
   fun logScan(result:String,selected:LegacyAlsaOpened?){
    val chosen=selected?.let{" selected=${it.candidate.path} rate=${it.rate} channels=${it.channels} source=${it.candidate.source}"}.orEmpty()
     val untried=(candidates.size-nextIndex).coerceAtLeast(0)
     val omitted=(inventory.totalCandidates-inventory.candidates.size).coerceAtLeast(0)
     log("microphone_alsa_scan profile=${inventory.profileKey} result=$result candidates=${candidates.size} tried=$nextIndex untriedCandidates=$untried omittedCandidates=$omitted truncated=${inventory.truncated}$chosen attempts=${attempts.joinToString(";").take(620)}")
   }
   var active=openNext()
    if(active==null){for(candidate in beforeCards.values)mixerSnapshot(candidate,"after");logScan("no_open_candidate",null);log("microphone_capture_fallback path=AudioRecord reason=no_usable_alsa_capture_node kind=${c.audioType} native=true");return false}
   logScan("opened",active)
   var selected=active!!
   log("microphone_capture_path=alsa_dynamic path=${selected.candidate.path} source=${selected.candidate.source} kind=${c.audioType} hwRate=${selected.rate} hwChannels=${selected.channels} targetRate=${c.sampleRate} profile=${inventory.profileKey}")
   var captured=0L;var silentWindows=0;var signalReported=false
   var filled=0;var carry=0;var frac=0.0
   var windowPackets=0;var windowPeak=0;var windowAbs=0L;var windowSamples=0L;var windowStart=System.nanoTime()
   try{
    while(!stop.get()){
     val current=active?:break
     val raw=ByteArray(maxOf(1024,current.rate*current.channels*2/10));val mono=IntArray(raw.size/(2*current.channels)+2);val step=current.rate.toDouble()/c.sampleRate
     var lastReadProgress=System.nanoTime()
     var readFailed=false
     var switchCandidate=false
     while(!stop.get()&&!switchCandidate){
       val n=AlsaProbe.captureRead(current.handle,raw,0,raw.size)
       if(stop.get())break
       val nowRead=System.nanoTime()
       val frames=(n/(2*current.channels)).coerceAtMost(mono.size)
       if(frames<=0){
        if(nowRead-lastReadProgress>=TimeUnit.MILLISECONDS.toNanos(1500)){
         log("microphone_alsa_read_exhausted path=${current.candidate.path} result=${if(n<0)"read_error" else "timeout"} stage=read errno=${if(n<0)-n else 0}")
         readFailed=true
         break
        }
        Thread.sleep(5)
        continue
       }
       lastReadProgress=nowRead
      for(f in 0 until frames){var acc=0;var ch=0;while(ch<current.channels){val o=(f*current.channels+ch)*2;acc+=((raw[o].toInt() and 255) or (raw[o+1].toInt() shl 8)).toShort().toInt();ch++};mono[f]=acc/current.channels}
      var fi=frac
      while(true){
       val i=fi.toInt();val fr=fi-i
       val s0=if(i<0)carry else if(i<frames)mono[i] else break
       val s1=if(i+1<0)carry else if(i+1<frames)mono[i+1] else break
       val v=(s0+(s1-s0)*fr).toInt()
       val amp=kotlin.math.abs(v);if(amp>windowPeak)windowPeak=amp;windowAbs+=amp;windowSamples++
       buffer[filled++]=(v and 255).toByte();buffer[filled++]=((v shr 8) and 255).toByte()
       if(filled==buffer.size){
        telephonyFilter?.process(buffer)
        val p=MicrophonePacketizer.sealPacket(c.key,c.payloadType,counters,MicrophonePacketizer.toCarPlayMicrophoneWirePcm(buffer),c.rtpSamplesPerPacket)
        s.send(DatagramPacket(p,p.size,c.host,c.port));captured++;windowPackets++;filled=0
       }
       fi+=step
      }
      frac=fi-frames
      carry=mono[frames-1]
      val now=System.nanoTime()
      if(now-windowStart>=TimeUnit.SECONDS.toNanos(1)){
       val meanAbs=if(windowSamples==0L)0L else windowAbs/windowSamples
       log("microphone_duplex_evidence kind=${c.audioType} path=${current.candidate.path} source=alsa windowPackets=$windowPackets windowPeak=$windowPeak windowMeanAbs=$meanAbs hwRate=${current.rate} hwChannels=${current.channels} nextRtpSequence=${counters.sequence} dsp=${telephonyFilter?.diagnosticSummary()?:"off"}")
       if(!signalReported&&windowPeak>=128&&meanAbs>=16L){
        AlsaCapturePathSelector.rememberSignal(context,inventory,current.candidate.path,current.rate,current.channels,windowPeak,meanAbs)
        log("microphone_alsa_signal_detected path=${current.candidate.path} peak=$windowPeak meanAbs=$meanAbs rate=${current.rate} channels=${current.channels}")
        signalReported=true
       }
       if(windowPeak==0){silentWindows++}else silentWindows=0
       // Silence can be intentional on a capture path that has already produced signal.
       if(silentWindows>=3&&!signalReported&&!inventory.hasProvenSignal(current.candidate)){
        switchCandidate=true
       }
       windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;windowStart=now
      }
     }
     if(stop.get())break
     if(readFailed){
      AlsaProbe.captureClose(current.handle);active=null
      val next=openNext()
      if(next==null){log("microphone_capture_fallback path=AudioRecord reason=alsa_read_failed kind=${c.audioType} attempts=${attempts.joinToString(";").take(520)}");return false}
      active=next;selected=next;filled=0;frac=0.0;carry=0;silentWindows=0;signalReported=false;windowStart=System.nanoTime();windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0
      log("microphone_alsa_switch reason=read_failed path=${next.candidate.path} rate=${next.rate} channels=${next.channels} attempts=${attempts.joinToString(";").take(520)}")
      continue
     }
     if(switchCandidate){
      log("microphone_alsa_switch reason=three_digital_zero_windows from=${current.candidate.path} to=probe_next")
      AlsaProbe.captureClose(current.handle);active=null
      val next=openNext()
       if(next!=null){active=next;selected=next;filled=0;frac=0.0;carry=0;silentWindows=0;signalReported=false;windowStart=System.nanoTime();windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;log("microphone_alsa_switch_selected path=${next.candidate.path} source=${next.candidate.source} rate=${next.rate} channels=${next.channels} untriedCandidates=${(candidates.size-nextIndex).coerceAtLeast(0)} omittedCandidates=${(inventory.totalCandidates-inventory.candidates.size).coerceAtLeast(0)} truncated=${inventory.truncated} attempts=${attempts.joinToString(";").take(520)}");continue}
      log("microphone_capture_fallback path=AudioRecord reason=alsa_no_signal kind=${c.audioType} attempts=${attempts.joinToString(";").take(520)}")
      return false
     }
    }
    }finally{active?.let{AlsaProbe.captureClose(it.handle)};for(candidate in beforeCards.values)mixerSnapshot(candidate,"after");log("microphone_alsa_closed kind=${c.audioType} selected=${selected.candidate.path} capturedPackets=$captured")}
   return true
  }
  private fun capture(){var captured=0L;var silence=0L;var inputBytes=0L;var windowPackets=0;var windowPeak=0;var windowAbs=0L;var windowSamples=0L;var windowStart=System.nanoTime()
   try{
    log("microphone_negotiated kind=${c.audioType} codec=${c.codec} rate=${c.sampleRate} channels=${c.channels} payloadType=${c.payloadType} frameMillis=${c.frameMillis} frameBytes=${c.frameBytes} rtpSamples=${c.rtpSamplesPerPacket}")
    require(c.codec==AudioCodecKind.LPCM&&c.channels==1&&c.sampleRate in 8000..48000&&c.frameBytes in 2..8192&&c.frameMillis in 1..100)
    if(telephonyFilter!=null)log("microphone_dsp enabled=true kind=telephony location=pcm_before_packetizer routeChanged=false sourceChanged=false downlinkChanged=false ${telephonyFilter.diagnosticSummary()}")
    val context=routing?.captureContext()
    val s=LegacySockets.udp();socket=s;val counters=MicrophoneCounters();val buffer=ByteArray(c.frameBytes);var filled=0;var paused=false;var nextSilenceNs=0L
    // Prefer a direct, dynamically discovered ALSA input on every microphone stream.
    // Keep the known-good path for the MX9A0. Other units use their detected hardware.
    // If no direct path supplies usable PCM, explicitly log the fallback to AudioRecord.
    val alsaDone=alsaCapture(s,counters,buffer,context)
    while(!alsaDone&&!stop.get()){
     val audioManager=context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
     if(audioManager!=null)CallRouteSelfTest.updateLiveRoute(audioManager,context)
     val duplexStage=if(c.audioType.equals("telephony",true))CallDuplexAB.microphoneStage(context,activeSource,recorder?.recordingState?:0)else -1
     val stageSource=CallDuplexAB.microphoneSourceForStage(duplexStage)
     val diagnosticSource=CallRouteSelfTest.liveMicrophoneSource()
     val wantedSource=if(diagnosticSource>=0)diagnosticSource else if(stageSource>=0)stageSource else if(c.audioType.equals("telephony",true))MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
     val diagnosticHold=CallDuplexAB.holdMicrophone(duplexStage)
     if(recorder!=null&&activeSource!=wantedSource){val old=activeSource;releaseRecorder();filled=0;log("live_duplex_source_switch from=$old to=$wantedSource stage=${CallRouteSelfTest.liveStage()}")}
     if(!siriCapture.capture(supported,c.audioType)||diagnosticHold){
      if(!paused){releaseRecorder();filled=0;buffer.fill(0);paused=true;nextSilenceNs=System.nanoTime();log("microphone_playback_hold mode=${siriCapture.mode} hardwareReleased=true kind=${c.audioType} duplexStage=$duplexStage diagnosticHold=$diagnosticHold")}
      // No AudioRecord exists here. Silence keeps RTP sequence/timestamps continuous.
      val period=c.frameMillis*1000000L;nextSilenceNs+=period;val waitNs=nextSilenceNs-System.nanoTime()
      if(waitNs>0)Thread.sleep(waitNs/1000000L,(waitNs%1000000L).toInt())else if(waitNs< -period)nextSilenceNs=System.nanoTime()
      if(stop.get())break
      if(siriCapture.capture(supported,c.audioType)&&!CallDuplexAB.holdMicrophone(if(c.audioType.equals("telephony",true))CallDuplexAB.microphoneStage(context,activeSource,recorder?.recordingState?:0)else -1))continue
      val p=MicrophonePacketizer.sealPacket(c.key,c.payloadType,counters,MicrophonePacketizer.toCarPlayMicrophoneWirePcm(buffer),c.rtpSamplesPerPacket)
      s.send(DatagramPacket(p,p.size,c.host,c.port));silence++;continue
     }
     paused=false
     if(recorder==null){
      val min=AudioRecord.getMinBufferSize(c.sampleRate,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT);require(min>0){"Unsupported microphone buffer rate=${c.sampleRate} result=$min"}
      val bufferBytes=maxOf(min*2,c.frameBytes*4);val isCall=c.audioType.equals("telephony",true);var selected=wantedSource
      var r=runCatching{AudioRecord(selected,c.sampleRate,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,bufferBytes)}.getOrNull()
      if(r?.state!=AudioRecord.STATE_INITIALIZED){runCatching{r?.release()};r=null;if(isCall){selected=MediaRecorder.AudioSource.MIC;r=runCatching{AudioRecord(selected,c.sampleRate,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,bufferBytes)}.getOrNull();if(r?.state!=AudioRecord.STATE_INITIALIZED){runCatching{r?.release()};r=null}}}
      recorder=r;require(r?.state==AudioRecord.STATE_INITIALIZED){"AudioRecord unavailable for source=$selected"}
      activeSource=selected
      if(stop.get())break
      // On the audited MX9A0, public startRecording requests the OEM global audio focus and
      // transiently mutes the concurrent CarPlay downlink. Use the same verified native start
      // path for telephony and Siri; all other devices retain the public Android API.
      val path=CarMicrophone.start(r!!,context)
      check(r!!.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Microphone did not start"}
      log("microphone_capture_path=$path kind=${c.audioType} source=$selected recordingState=${r!!.recordingState} liveStage=${CallRouteSelfTest.liveStage()}")
      log("microphone_started_pcm kind=${c.audioType} rate=${c.sampleRate}")
      if(!siriCapture.capture(supported,c.audioType)||CallDuplexAB.holdMicrophone(if(c.audioType.equals("telephony",true))CallDuplexAB.microphoneStage(context,activeSource,recorder?.recordingState?:0)else -1)){filled=0;continue}
     }
      val n=recorder!!.read(buffer,filled,buffer.size-filled);if(stop.get())break
      when(readRecovery.onReadResult(n,System.nanoTime())){
       MicrophoneReadRecoveryPolicy.Action.DATA -> filled+=n
       MicrophoneReadRecoveryPolicy.Action.WAIT -> {Thread.sleep(5);continue}
       MicrophoneReadRecoveryPolicy.Action.RESTART -> {val source=activeSource;releaseRecorder();filled=0;buffer.fill(0);log("microphone_recording_restarted reason=${if(n<0)"read-error" else "no-audio"} code=$n attempt=${readRecovery.restartAttempts} source=$source");if(n<0)Thread.sleep(20);continue}
       MicrophoneReadRecoveryPolicy.Action.RESTARTS_EXHAUSTED -> {log("microphone_recovery_exhausted reason=no-audio retries=${readRecovery.restartAttempts} source=$activeSource");Thread.sleep(50);continue}
       MicrophoneReadRecoveryPolicy.Action.FATAL -> throw IOException("Microphone read failed after ${readRecovery.restartAttempts} restarts: $n")
      }
     if(!siriCapture.capture(supported,c.audioType)||CallDuplexAB.holdMicrophone(if(c.audioType.equals("telephony",true))CallDuplexAB.microphoneStage(context,activeSource,recorder?.recordingState?:0)else -1)){filled=0;continue}
     if(filled==buffer.size){for(i in buffer.indices step 2){val sample=((buffer[i].toInt() and 255) or (buffer[i+1].toInt() shl 8)).toShort().toInt();val amp=kotlin.math.abs(sample);if(amp>windowPeak)windowPeak=amp;windowAbs+=amp;windowSamples++};telephonyFilter?.process(buffer);val p=MicrophonePacketizer.sealPacket(c.key,c.payloadType,counters,MicrophonePacketizer.toCarPlayMicrophoneWirePcm(buffer),c.rtpSamplesPerPacket);s.send(DatagramPacket(p,p.size,c.host,c.port));captured++;inputBytes+=buffer.size;windowPackets++;filled=0;val now=System.nanoTime();if(now-windowStart>=TimeUnit.SECONDS.toNanos(1)){log("microphone_duplex_evidence kind=${c.audioType} sentPackets=$captured windowPackets=$windowPackets inputBytes=$inputBytes windowPeak=$windowPeak windowMeanAbs=${if(windowSamples==0L)0 else windowAbs/windowSamples} source=$activeSource liveStage=${CallRouteSelfTest.liveStage()} nextRtpSequence=${counters.sequence} nextRtpTimestamp=${counters.timestamp.toLong() and 0xffffffffL} recorderState=${recorder?.recordingState} dsp=${telephonyFilter?.diagnosticSummary()?:"off"}");windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;windowStart=now}}
    }
   }catch(_:InterruptedException){}catch(e:Throwable){if(!stop.get())log("microphone_failed="+e.javaClass.simpleName+" detail="+e.message)}finally{stop.set(true);runCatching{releaseRecorder()}.onFailure{log("microphone_release_failed="+it.javaClass.simpleName)};socket?.close();log("microphone_stopped kind=${c.audioType} capturedPackets=$captured silentPackets=$silence")}
  }
  fun released()=!thread.isAlive
  override fun close(){stop.set(true);socket?.close();runCatching{recorder?.stop()};thread.interrupt();if(!MediaWorkers.await(thread))log("microphone_release_pending")}
 }
 override fun close(){if(!closed.compareAndSet(false,true))return
  val outputs:List<AudioOutput>;val inputs:List<Microphone>
  synchronized(this){outputs=audio.values.toList();audio.clear();inputs=microphones.values.toList();microphones.clear()}
  recoveryWork.shutdownNow();synchronized(videoTargetLock){calibration=null;videoTarget.input.retire();latestVideoConfig=null};videoThread.interrupt()
  for(a in outputs)a.close();for(m in inputs)m.close()
  if(!MediaWorkers.await(videoThread))log("video_release_pending");playbackFocus?.close();routing?.close()
 }
}
