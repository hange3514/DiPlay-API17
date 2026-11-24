package local.headunit.diplay
import android.media.*
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

private data class LegacyAlsaOpened(val candidate:AlsaCapturePathSelector.Candidate,val handle:Long,val rate:Int,val channels:Int)

/** Bounded API17 media workers. No modern AudioAttributes or codec buffer APIs. */
class LegacyMedia(surface:Surface?,private val width:Int,private val height:Int,private val log:(String)->Unit):MediaSink,Closeable {
 private val siriCapture=SiriCapturePolicy()
 fun speechMode(params:Map<String,Any?>){if(siriCapture.update(params))log("microphone_speech_mode=${siriCapture.mode}")}
 private class VideoTarget(val surface:Surface?,config:ByteArray?=null){
  val input=VideoInput().apply{config?.let{configure(it)}}
 }
 private val videoTargetLock=java.lang.Object()
 private var latestVideoConfig:ByteArray?=null
 private var firstVideoKeyframe=false
 @Volatile private var videoTarget=VideoTarget(surface)
 fun setSurface(surface:Surface?){
  synchronized(videoTargetLock){
   if(closed.get())return
   if(videoTarget.surface!==surface){
    // Each Surface owns its queue so the decoder cannot flush a new Surface's first IDR.
    videoTarget=VideoTarget(surface,latestVideoConfig)
   }
   videoTargetLock.notifyAll()
  }
 }
 fun resetProjectionStream(){
  synchronized(videoTargetLock){
   latestVideoConfig=null
   receivedVideo=false
   firstVideoKeyframe=false
   videoTarget=VideoTarget(videoTarget.surface)
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
 private var lastRecoveryNs=0L
 private val recoveryWork=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(1))
 @Volatile private var diagnostic:((String)->Unit)?=null
 @Volatile private var recovery:(()->Unit)?=null
 private val audio=ConcurrentHashMap<AudioStreamId,AudioOutput>()
 @Volatile private var musicPrefillMs=WirelessMusicBuffer.START_MS
 private val microphones=ConcurrentHashMap<AudioStreamId,Microphone>()
 private val videoThread=MediaWorkers.start("api17-video"){videoLoop()}
 override fun setVideoDiagnosticHandler(type:Int,handler:(String)->Unit){diagnostic=handler}
 @Synchronized override fun setVideoRecoveryHandler(type:Int,handler:()->Unit){recovery=handler;lastRecoveryNs=0}
 override fun onVideoConfig(type:Int,codecData:ByteArray){
  synchronized(videoTargetLock){
   if(closed.get()||videoFailed.get()||!videoTarget.input.configure(codecData))return
   latestVideoConfig=codecData.copyOf()
  }
  log("video_configuration_changed eventType=video_configured streamType=$type payloadBytes=${codecData.size}")
 }
 override fun onVideoFrame(type:Int,naluBytes:ByteArray){receiveVideo(type,naluBytes,false)}
 override fun onOwnedVideoFrame(type:Int,naluBytes:ByteArray,senderNanos:Long,arrivalNanos:Long){receiveVideo(type,naluBytes,true)}
 private fun receiveVideo(type:Int,bytes:ByteArray,owned:Boolean){
  if(bytes.isEmpty())return
  var logKeyframe=false
  val result=synchronized(videoTargetLock){
   if(closed.get()||videoFailed.get())return
   receivedVideo=true
   val target=videoTarget
   if(target.surface?.isValid!=true)return
   val offered=if(owned)target.input.offerOwned(bytes) else target.input.offer(bytes)
   if(offered.acceptedKeyframe&&!firstVideoKeyframe){firstVideoKeyframe=true;logKeyframe=true}
   offered
  }
  if(logKeyframe)log("video_keyframe_queued eventType=video_first_keyframe streamType=$type payloadBytes=${bytes.size}")
  result.recoveryReason?.let{requestRecovery(it)}
 }
 @Synchronized private fun requestRecovery(reason:String){
  val handler=recovery?:return
  val now=System.nanoTime()
  if(closed.get()||(lastRecoveryNs!=0L&&now-lastRecoveryNs<TimeUnit.SECONDS.toNanos(2)))return
  try{
   recoveryWork.execute{if(!closed.get()&&recovery===handler)runCatching{handler()}}
   lastRecoveryNs=now
   log("video_recovery reason=$reason")
  }catch(_:RejectedExecutionException){}
 }
 private fun videoLoop(){var codec:MediaCodec?=null;var configured:ByteArray?=null;var frames=0;var stage="waiting_config";var target=videoTarget;var surfaceFrames=0
  var pending:VideoInput.Frame?=null;var buffers:Array<ByteBuffer>?=null;var firstInput=false;var firstOutput=false
  val info=MediaCodec.BufferInfo();var inFlight=0;var decoderActivityNs:Long?=null;var skipped=0;var queueMaxMs=0L;var decodeMaxMs=0L;var lastStats=System.nanoTime()
  var releaseMaxUs=0L;var dequeueOverrunMaxUs=0L;var inputMaxUs=0L;var inFlightPeak=0
  fun dispose(){runCatching{codec?.stop()};runCatching{codec?.release()};codec=null;configured=null;buffers=null;pending=null;inFlight=0;decoderActivityNs=null}
  fun drain(waitUs:Long=0){val c=codec?:return;stage="drain";var latest=-1;var latestPts=0L;var latestSize=0;var latestFlags=0
   fun release(index:Int,render:Boolean){val at=System.nanoTime();c.releaseOutputBuffer(index,render);releaseMaxUs=maxOf(releaseMaxUs,(System.nanoTime()-at)/1000)}
   // Feed every reference frame to the decoder, but display only the newest ready output.
   try{for(i in 0 until 32){val budget=if(i==0)waitUs else 0;val at=System.nanoTime();val n=c.dequeueOutputBuffer(info,budget);dequeueOverrunMaxUs=maxOf(dequeueOverrunMaxUs,(System.nanoTime()-at)/1000-budget)
    if(n>=0){
     val codecConfig=info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0
     val emptyEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0&&info.size==0
     if(codecConfig||emptyEnd){release(n,false);continue}
     inFlight=maxOf(0,inFlight-1)
     decoderActivityNs=System.nanoTime()
     val previous=latest
     latest=n;latestPts=info.presentationTimeUs;latestSize=info.size;latestFlags=info.flags
     if(previous>=0){release(previous,false);skipped++}
     if(!firstOutput){firstOutput=true;log("video_decoder_output eventType=video_first_output outputSize=$latestSize outputFlags=$latestFlags")}
    }
    else if(n==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)log("video_output_format_changed")
    else if(n!=MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)break
   }}finally{
    if(latest>=0){
     stage="surface_release"
     val render=!closed.get()&&target===videoTarget&&target.surface?.isValid==true
     release(latest,render)
     val submitted=render&&synchronized(videoTargetLock){
      if(closed.get()||target!==videoTarget||target.surface?.isValid!=true)false
      else{frames++;renderedVideoFrames++;surfaceFrames++;true}
     }
     decodeMaxMs=maxOf(decodeMaxMs,(System.nanoTime()/1000-latestPts)/1000)
     if(submitted&&surfaceFrames==1)log("video_surface_first_frame eventType=video_surface_frame frameCount=$frames outputSize=$latestSize outputFlags=$latestFlags surfaceValid=true")
     if(submitted&&frames==1)log("video_rendered_frames=1")
     if(submitted&&surfaceFrames==1)diagnostic?.invoke("first frame rendered")
    }
   }
  }
  try{while(!closed.get()) {
   if(target!==videoTarget){dispose();target=videoTarget;surfaceFrames=0;log("video_surface_changed surfaceValid=${target.surface?.isValid==true}");if(target.surface?.isValid==true)requestRecovery("surface_restored")}
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
   try {
   // Drain before waiting for a new frame. Sparse updates must not delay already decoded output by 100ms.
   drain()
   if(pending==null){
    // A retained frame on a static screen does not keep the codec in a 5 ms poll loop.
    val recentDecode=MediaDecoderActivity.waiting(inFlight,decoderActivityNs,System.nanoTime(),surfaceFrames==0)
    pending=target.input.poll(if(recentDecode)0 else 100)
    if(pending==null&&recentDecode){drain(5000);pending=target.input.poll(0)}
   }
   if(target!==videoTarget)continue
   val packet=pending
   if(packet!=null&&packet.config!==configured){stage="configure";require(target.surface?.isValid==true){"Video Surface is invalid"};runCatching{codec?.stop()};runCatching{codec?.release()};codec=null
    val(sps,pps)=MediaCodecSupport.avcParameterSets(packet.config);require(sps.isNotEmpty()&&pps.isNotEmpty()){"Invalid AVC parameter sets"}
    val format=MediaFormat.createVideoFormat("video/avc",width,height);format.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+sps));format.setByteBuffer("csd-1",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pps));format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,1024*1024)
    val c=MediaCodec.createDecoderByType("video/avc");codec=c;c.configure(format,target.surface,null,0);c.start();buffers=c.inputBuffers;inFlight=0;decoderActivityNs=null;configured=packet.config
    log("video_decoder_started eventType=video_decoder_started codec=H264 width=$width height=$height payloadBytes=${packet.config.size}")
   }
   val c=codec?:continue
   if(packet!=null){stage="input";val inputAt=System.nanoTime();val index=c.dequeueInputBuffer(5000)
    if(index>=0){val buffer=buffers!![index];buffer.clear();val byteCount=VideoBufferWriter.putAnnexB(packet.bytes,buffer);val submittedAt=System.nanoTime();queueMaxMs=maxOf(queueMaxMs,(submittedAt-packet.arrivedNs)/1000000);c.queueInputBuffer(index,0,byteCount,submittedAt/1000,0);decoderActivityNs=submittedAt;inFlight++;inFlightPeak=maxOf(inFlightPeak,inFlight);pending=null
     if(!firstInput){firstInput=true;log("video_decoder_input eventType=video_first_input payloadBytes=$byteCount")}
    }
    // A brief busy decoder is normal. Retain this reference frame instead of destroying the GOP every 20ms.
    else if(System.nanoTime()-packet.arrivedNs>TimeUnit.MILLISECONDS.toNanos(250)){pending=null;target.input.dropped();requestRecovery("decoder_backpressure")}
    inputMaxUs=maxOf(inputMaxUs,(System.nanoTime()-inputAt)/1000)
   }
   drain()
   val now=System.nanoTime();if(now-lastStats>=TimeUnit.SECONDS.toNanos(5)){log("video_progress rendered=$frames skippedOutput=$skipped queued=${target.input.size()} queueMaxMs=$queueMaxMs decodeMaxMs=$decodeMaxMs releaseMaxUs=$releaseMaxUs dequeueOverrunMaxUs=$dequeueOverrunMaxUs inputMaxUs=$inputMaxUs inFlightPeak=$inFlightPeak");lastStats=now;queueMaxMs=0;decodeMaxMs=0;releaseMaxUs=0;dequeueOverrunMaxUs=0;inputMaxUs=0;inFlightPeak=inFlight}
   }catch(e:Throwable){if(e is InterruptedException)throw e;if(target!==videoTarget||target.surface?.isValid!=true){dispose();target.input.dropped();log("video_surface_unavailable eventType=video_surface_unavailable stage=$stage surfaceValid=${target.surface?.isValid==true}")}else throw e}
  }}catch(e:InterruptedException){}catch(e:Throwable){if(!closed.get()){synchronized(videoTargetLock){videoFailed.set(true);videoTarget.input.clear()};log("video_failed eventType=video_decoder_failure stage=$stage errorType=${e.javaClass.simpleName} surfaceValid=${target.surface?.isValid==true}");onVideoFailure?.invoke(e)}}finally{runCatching{codec?.stop()};runCatching{codec?.release()}}
 }
 @Synchronized override fun onAudioStarted(id:AudioStreamId,format:com.shilapi.xcertplay.airplay.AudioFormat,firstSample:Int){if(closed.get())return;log("audio_stream_start type=${id.type} kind=${id.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} payloadType=${format.payloadType} firstRtpSample=${firstSample.toLong() and 0xffffffffL}");val previous=audio.keys.filter{it.type==id.type&&it.audioType==id.audioType};for(key in previous)audio.remove(key)?.let{it.close();check(it.released()){"Previous audio worker is still releasing"}};val role=LegacyAudioPolicy.role(id.type,format.audioType);val callMode=role=="call";if(callMode){routing?.beginCallAudio();CallDuplexAB.callStreamStarting(routing?.captureContext())};try{audio[id]=AudioOutput(id,format,callMode)}catch(t:Throwable){if(callMode)routing?.endCallAudio();throw t}}
 override fun onAudioRtp(id:AudioStreamId,format:com.shilapi.xcertplay.airplay.AudioFormat,rtp:ByteArray,sample:Int){audio[id]?.offer(rtp,sample)}
 @Synchronized override fun onAudioStopped(id:AudioStreamId){audio.remove(id)?.let{log("audio_stopped type=${id.type} kind=${id.audioType}");it.close();val focusRole=if(it.role=="assistant")"media" else it.role;if(audio.values.none{v->(if(v.role=="assistant")"media" else v.role)==focusRole})routing?.playbackEnded(focusRole)}}
 @Synchronized override fun onMicrophoneStarted(id:AudioStreamId,config:MicrophoneConfig){if(closed.get())return;val previous=microphones.values.toList();for(m in previous)m.close();check(previous.all{it.released()}){"Previous microphone is still releasing"};microphones.clear();microphones[id]=Microphone(config,routing?.captureContext()?.let{AudioRouting.secondMusic(it)==10}?:false)}
 @Synchronized override fun onMicrophoneStopped(id:AudioStreamId){microphones.remove(id)?.close()}
 inner class AudioOutput(id:AudioStreamId,private val f:com.shilapi.xcertplay.airplay.AudioFormat,private val ownsCallMode:Boolean):Closeable {
  val role=LegacyAudioPolicy.role(id.type,f.audioType);private val stream=routing?.stream(role)?:AudioManager.STREAM_MUSIC
  private var playing=false;private val navigationActivity=NavigationActivity()
  private val music=role=="media"
  private val realtime=id.type!=BufferedAudioStream.STREAM_TYPE
  private val realtimeMusic=music&&realtime
  private val speechTiming=if(realtime&&!music)SpeechPlayoutTuning(role)else null
  private val stop=AtomicBoolean(false)
  // A full 700 ms target can contain 280 short Opus frames. The independent byte cap also bounds LPCM.
  private val queue=when{
   music -> AudioInput(capacity=320,maxAgeMs=950)
   realtime -> AudioInput(capacity=96,maxQueuedBytes=64*1024)
   else -> AudioInput()
  }
  private val rtpOrder=when{
   realtimeMusic -> RealtimeAudioOrder(queue,clockRate=f.rtpClockRate)
   realtime -> RealtimeAudioOrder(queue,clockRate=f.rtpClockRate,gapWaitMs=12L)
   else -> null
  }
  private val musicTiming=if(realtimeMusic)MusicPlayoutTuning(musicPrefillMs)else null
  @Volatile private var track:AudioTrack?=null
  private var emptyQueuePolls=0L
  private var musicRebuffers=0L
  private var confirmedDryEvents=0L
  private var maxConfirmedDryNs=0L
  private var maxTrackWriteNs=0L
  private var maxMusicQueueAgeNs=0L
  private var maxDecoderInputNs=0L
  // Keep call PCM in its negotiated format. The on-device 12-profile replay
  // found clear speech for STREAM_MUSIC / MODE_NORMAL / swapped PCM at 32 kHz
  // mono. The prior software 32->48 kHz + mono->stereo conversion was not
  // covered by that audible result, so do not add it to the production path.
  private val outputChannels=LegacyAudioPolicy.outputChannels(role,f.channels)
  private val outputRate=LegacyAudioPolicy.outputRate(role,f.sampleRate)
  private val musicClock=if(realtimeMusic)MusicClockCalibration(outputRate,f.rtpClockRate)else null
  private var lastClockSequence:Int?=null
  private var lastClockQueueDrops=0L
  private var trackRate=outputRate
  private var trackResetAtBytes=0L
  private val callDownlinkGain=if(role=="call")CallDownlinkGain(outputRate,outputChannels)else null
  private val doubleRate=if(role!="call"&&outputRate!=f.sampleRate)PcmDoubleRate(outputChannels)else null
  private val rateConverter:((ByteArray)->ByteArray)?=when {
   role=="call"&&outputRate!=f.sampleRate -> Pcm16RateConverter(f.sampleRate,outputRate,outputChannels).let{converter->{input:ByteArray->converter.convert(input)}}
   doubleRate!=null -> doubleRate::convert
   else -> null
  }
  private val pcmEvidence=PcmEvidence();@Volatile private var lastHead=0L
  @Volatile private var appliedVolume=Float.NaN;private var bytesWritten=0L;private var mutedPackets=0L;private var lastProgress=0L;private var checkpoints=0
  private val thread=MediaWorkers.start("api17-audio"){loop()}
  fun offer(b:ByteArray,t:Int){
   if(stop.get())return
   val frames=if(music||realtime)WirelessMusicBuffer.packetFrames(b,f.codec,f.sampleRate,f.channels)else 0
   // AudioStream hands over a freshly decrypted array and never mutates it.
   if(realtime)queue.offerOwned(b,t,frames)else queue.offer(b,t,frames)
  }
  @Synchronized fun volume(value:Float){val t=track?:return;if(appliedVolume==value)return;runCatching{if(t.setStereoVolume(value,value)==AudioTrack.SUCCESS)appliedVolume=value}}
  private fun stopIdleNavigation(){
   if(playing&&role=="navigation"&&!navigationActivity.active(System.nanoTime())){
    track?.stop()
    playing=false
    trackResetAtBytes=bytesWritten
    lastHead=0L
    speechTiming?.reset()
    log("navigation_idle_stopped")
   }
  }
  private fun write(input:ByteArray){
   val channelBytes=if(outputChannels!=f.channels)PcmEvidence.stereo(input)else input
   val bytes=rateConverter?.invoke(channelBytes)?:channelBytes
   pcmEvidence.observe(bytes)
   if(role=="navigation"&&!navigationActivity.packet(bytes,System.nanoTime())){stopIdleNavigation();return}
   val level=routing?.packet(role)?:playbackFocus?.packet()?:1f
   volume(level)
   if(level==0f){mutedPackets++;musicClock?.reset();speechTiming?.clearDry();progress();return}
   if(callDownlinkGain!=null){
    CallDuplexAB.callDownlinkFirstPcm(routing?.captureContext())
    callDownlinkGain.apply(bytes)
   }
   if(!playing){track!!.play();playing=true}
   if(role=="call")CallDuplexAB.injectPhaseTone(routing?.captureContext(),track!!,outputRate,outputChannels,stream)
   var at=0
   while(at<bytes.size&&!stop.get()){
    val started=if(music||speechTiming!=null)System.nanoTime()else 0L
    val runway=observeMusicBeforeWrite(started)
    val n=track!!.write(bytes,at,bytes.size-at)
    if(started!=0L){
     val wroteForNs=System.nanoTime()-started
     maxTrackWriteNs=maxOf(maxTrackWriteNs,wroteForNs)
     // A long blocking write can hide an underrun while the driver accepts the new buffer.
     if(runway>0L&&wroteForNs>runway)musicClock?.reset()
     else if(runway>0L&&n>0&&!stop.get())applyMusicClockCorrection()
    }
    if(n<=0&&stop.get())return
    if(n<=0)throw IllegalStateException("AudioTrack write failed")
    at+=n
    bytesWritten+=n
   }
   if(at>0)speechTiming?.clearDry()
   if(bytesWritten==bytes.size.toLong())log("audio_first_pcm_written eventType=audio_pcm_written role=$role codec=${f.codec} sampleRate=$outputRate channels=$outputChannels writtenBytes=$bytesWritten")
   progress()
  }
  private fun observeMusicPacket(packet:AudioInput.Packet){
   val timing=musicTiming?:return
   timing.packetArrived(packet.arrivedNs)
   musicPrefillMs=timing.targetMs
   val sequence=((packet.bytes[2].toInt() and 255) shl 8) or (packet.bytes[3].toInt() and 255)
   val previous=lastClockSequence
   if(previous!=null&&((sequence-previous) and 0xffff)!=1)musicClock?.reset()
   lastClockSequence=sequence
   musicClock?.received(packet.sample,packet.arrivedNs)
  }
  private fun observeMusicBeforeWrite(now:Long):Long {
   val clock=musicClock?:return 0L
   if(!clock.needsObservation)return 0L
   val drops=queue.drops()
   if(drops!=lastClockQueueDrops||appliedVolume<0.5f){
    lastClockQueueDrops=drops
    clock.reset()
    return 0L
   }
   val queued=trackQueuedFrames()
   clock.playedBeforeWrite(lastHead,queued,now)
   return queued.coerceAtLeast(0L)*1_000_000_000L/trackRate
  }
  private fun applyMusicClockCorrection(){
   val correction=musicClock?.correction()?:return
   val accepted=runCatching{track?.setPlaybackRate(correction.rate)==AudioTrack.SUCCESS}.getOrDefault(false)
   if(accepted)trackRate=correction.rate
   log("audio_music_clock accepted=$accepted requestedRate=${correction.rate} outputRate=$trackRate senderPpm=${correction.sourcePpm} trackPpm=${correction.trackPpm}")
  }
  private fun trackQueuedFrames():Long{
   val t=track?:return -1L
   val head=runCatching{t.playbackHeadPosition.toLong() and 0xffffffffL}.getOrNull()?:return -1L
   lastHead=head
   // API 17 has no underrun counter. Reject a reset or otherwise invalid head sample.
   val queued=((bytesWritten-trackResetAtBytes)/(2*outputChannels)-head) and 0xffffffffL
   return if(queued<=outputRate*2L)queued else -1L
  }
  private fun musicDrySummary()=
   "confirmedDryEvents=$confirmedDryEvents maxConfirmedDryMs=${maxConfirmedDryNs/1_000_000}"
  private fun speechProgress(stage:String,queuedFrames:Long){
   val timing=speechTiming?:return
   val queuedMs=if(queuedFrames>=0)queuedFrames*1000/outputRate else -1
   log("audio_speech_playout eventType=audio_speech_playout stage=$stage role=$role codec=${f.codec} stream=$stream trackQueuedMs=$queuedMs queueDrops=${queue.drops()} rebufferCount=${timing.rebufferCount} confirmedDryEvents=${timing.shortDryEvents} maxConfirmedDryMs=${timing.maxShortDryMs} writeMaxUs=${maxTrackWriteNs/1000} decodeInputMaxUs=${maxDecoderInputNs/1000} ${rtpOrder?.summary().orEmpty()}")
  }
  private fun progress(){
   val now=System.nanoTime()
   if(now-lastProgress<TimeUnit.SECONDS.toNanos(if(checkpoints<5)1 else if(speechTiming!=null)5 else 10))return
   val queuedFrames=trackQueuedFrames()
   lastProgress=now
   val tag=if(checkpoints++<5)"audio_checkpoint" else "audio_progress"
   val callStats=callDownlinkGain?.let{" ${pcmEvidence.byteOrderCandidateSummary()} ${it.summary()}"}.orEmpty()
   val queuedMs=if(queuedFrames>=0)queuedFrames*1000/outputRate else -1
   val musicStats=if(music){
    val inputFrames=queue.bufferedFrames()+(rtpOrder?.bufferedFrames()?:0)
    " rebufferCount=$musicRebuffers ${musicDrySummary()} writeMaxUs=${maxTrackWriteNs/1000} decodeInputMaxUs=${maxDecoderInputNs/1000} queueMaxAgeMs=${maxMusicQueueAgeNs/1000000} inputQueuedMs=${inputFrames*1000L/f.sampleRate} bufferTargetMs=${musicTiming?.targetMs?:WirelessMusicBuffer.START_MS} playbackRate=$trackRate ${rtpOrder?.summary().orEmpty()}"
   }else ""
   speechProgress("window",queuedFrames)
   maxTrackWriteNs=0L
   maxMusicQueueAgeNs=0L
   maxDecoderInputNs=0L
   log("$tag role=$role stream=$stream writtenBytes=$bytesWritten mutedPackets=$mutedPackets queueSize=${queue.size()} queueDrops=${queue.drops()} playbackHeadFrames=$lastHead trackQueuedMs=$queuedMs emptyQueuePolls=$emptyQueuePolls peak=${pcmEvidence.peak} nonzero=${pcmEvidence.nonzero} volume=$appliedVolume$callStats$musicStats")
   if(role=="call")AudioRouting.logCallOutput(routing?.captureContext()?:return,track,stream,tag,bytesWritten,lastHead,appliedVolume)
  }
  private fun prepareMusic(first:AudioInput.Packet,targetMs:Int){
   val targetFrames=f.sampleRate*targetMs/1000
   val at=System.nanoTime()
   val maxWaitMs=if(realtimeMusic)(targetMs+125L).coerceAtMost(800L)else WirelessMusicBuffer.MAX_PREFILL_WAIT_MS
   val bufferedFrames=rtpOrder?.awaitPrefill(first,targetFrames,maxWaitMs)?:queue.awaitPrefill(first,targetFrames,maxWaitMs)
   val bufferedMs=bufferedFrames*1000L/f.sampleRate
   log("audio_music_prefill stage=music_prefill queueSize=${queue.size()+1} targetMs=$targetMs bufferedMs=$bufferedMs waitedMs=${(System.nanoTime()-at)/1000000} success=${bufferedFrames>=targetFrames} codec=${f.codec} rebufferCount=$musicRebuffers")
  }
  private fun loop(){var decoder:MediaCodec?=null
   var inputBuffers:Array<ByteBuffer>?=null;var outputBuffers:Array<ByteBuffer>?=null;var inFlight=0
   var decoderActivityNs:Long?=null
   var decoderHadOutput=false
   var decodedPcm=ByteArray(0)
   var aacSamplesQueued=0L
   var rtpGeneration=0L
   val reorderRunwayFrames=outputRate*(if(realtimeMusic)35L else 18L)/1000L
   val hasReorderRunway={playing&&appliedVolume>0f&&trackQueuedFrames()>reorderRunwayFrames}
   val info=MediaCodec.BufferInfo()
   fun startAac(){
    val format=MediaFormat.createAudioFormat("audio/mp4a-latm",f.sampleRate,f.channels)
    format.setInteger("is-adts",1)
    val config=(2 shl 11) or (MediaCodecSupport.aacFrequencyIndex(f.sampleRate) shl 7) or (f.channels shl 3)
    format.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf((config shr 8).toByte(),config.toByte())))
    val codec=MediaCodec.createDecoderByType("audio/mp4a-latm")
    decoder=codec
    codec.configure(format,null,null,0)
    codec.start()
    inputBuffers=codec.inputBuffers
    outputBuffers=codec.outputBuffers
    decoderHadOutput=false
   }
   fun resetAudioTimeline(t:AudioTrack,opus:WirelessOpus.Decoder?){
    if(playing){
     t.pause()
     t.flush()
     playing=false
     trackResetAtBytes=bytesWritten
     lastHead=0L
    }
    if(trackRate==outputRate||runCatching{t.setPlaybackRate(outputRate)==AudioTrack.SUCCESS}.getOrDefault(false)){
     trackRate=outputRate
     musicClock?.rearm()
    }
    lastClockSequence=null
    doubleRate?.reset()
    opus?.reset()
    decoder?.let{codec->
     // Early flush can discard codec-specific data. Recreate if there has been no output yet.
     val flushed=decoderHadOutput&&runCatching{
      codec.flush()
      inputBuffers=codec.inputBuffers
      outputBuffers=codec.outputBuffers
     }.isSuccess
     if(!flushed){
      runCatching{codec.stop()}
      codec.release()
      decoder=null
      startAac()
     }
    }
    inFlight=0
    decoderActivityNs=null
    aacSamplesQueued=0L
    if(realtimeMusic)log("audio_music_timeline_reset generation=$rtpGeneration")
    else speechProgress("timeline_reset",0L)
   }
   fun drain(waitUs:Long=0){val c=decoder?:return
    for(i in 0 until 32){val n=c.dequeueOutputBuffer(info,if(i==0)waitUs else 0)
     if(n>=0){
      if((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){c.releaseOutputBuffer(n,false);continue}
      inFlight=maxOf(0,inFlight-1)
      val b=outputBuffers!![n];b.clear()
      require(info.offset>=0&&info.size>=0&&info.offset<=b.capacity()-info.size){"Invalid audio output range"}
      if(decodedPcm.size!=info.size)decodedPcm=ByteArray(info.size)
      b.position(info.offset);b.limit(info.offset+info.size);b.get(decodedPcm)
      c.releaseOutputBuffer(n,false)
      if(decodedPcm.isNotEmpty()){
       decoderHadOutput=true
       decoderActivityNs=System.nanoTime()
       write(decodedPcm)
      }
     }
     else if(n==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)outputBuffers=c.outputBuffers
     else if(n!=MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)break
    }
   }
   try{
    require(f.channels in 1..2&&f.sampleRate in 8000..48000)
    runCatching{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)}
    val opus=if(f.codec==AudioCodecKind.OPUS)WirelessOpus.Decoder(f.sampleRate,f.channels)else null
    var opusErrors=0
    val channels=if(outputChannels==1)android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
    val min=AudioTrack.getMinBufferSize(outputRate,channels,android.media.AudioFormat.ENCODING_PCM_16BIT);require(min>0)
    val t=AudioTrack(stream,outputRate,channels,android.media.AudioFormat.ENCODING_PCM_16BIT,maxOf(min*2,outputRate*outputChannels/5),AudioTrack.MODE_STREAM)
    // Publish only after initial gain and its cache agree. Focus callbacks use the same lock.
    synchronized(this){track=t;require(t.state==AudioTrack.STATE_INITIALIZED);check(t.setStereoVolume(0f,0f)==AudioTrack.SUCCESS){"Initial audio gain rejected"};appliedVolume=0f}
    if(f.codec==AudioCodecKind.AAC_LC)startAac()
    log("audio_started role=$role stream=$stream codec=${f.codec} inputRate=${f.sampleRate} inputChannels=${f.channels} outputChannels=$outputChannels outputRate=$outputRate actualStream=${t.streamType} nativeRate=${AudioTrack.getNativeOutputSampleRate(stream)}");var packets=0;var pending:AudioInput.Packet?=null
    var musicPrimed=!music
    var musicDrySinceNs:Long?=null
    var musicDryAtBytes=0L
    var musicTargetMs=musicTiming?.targetMs?:WirelessMusicBuffer.START_MS
    while(!stop.get()){
     drain()
     if(pending==null){
      // A decoder can finish late even when no more network packets have arrived.
      if(musicDrySinceNs!=null&&bytesWritten!=musicDryAtBytes)musicDrySinceNs=null
      val previousDry=musicDrySinceNs
      val pollAtNs=System.nanoTime()
      val decoderPending=MediaDecoderActivity.waiting(inFlight,decoderActivityNs,pollAtNs)
      val inputEmpty=rtpOrder!=null&&playing&&appliedVolume>0f&&queue.size()==0&&rtpOrder.bufferedFrames()==0
      val checkForShortGap=realtimeMusic&&musicPrimed&&playing&&appliedVolume>0f&&
          (previousDry==null||pollAtNs-previousDry<WirelessMusicBuffer.DRY_THRESHOLD_NS)
      val waitMs=when{
       decoderPending -> 5L
       checkForShortGap&&previousDry==null&&bytesWritten>0&&inputEmpty -> {
        val runway=trackQueuedFrames()
        if(runway==0L){
         musicDrySinceNs=System.nanoTime()
         musicDryAtBytes=bytesWritten
         musicClock?.reset()
        }
        musicDepletionPollMs(runway,trackRate)
       }
       checkForShortGap -> 30L
       speechTiming!=null&&playing&&appliedVolume>0f&&inputEmpty&&bytesWritten>trackResetAtBytes -> {
        val runway=trackQueuedFrames()
        if(runway==0L){
         speechTiming.markDry(System.nanoTime())
         100L
        }else if(runway>0L)musicDepletionPollMs(runway,trackRate)else 100L
       }
       else -> 100L
      }
      val codecWaitUs=if(decoderPending&&rtpOrder!=null&&playing&&appliedVolume>0f&&inputEmpty)
       MediaDecoderActivity.lowMusicRunwayWaitUs(trackQueuedFrames(),trackRate) else 0L
      // Prioritize decoded PCM at the end of a burst, giving back the remaining input wait.
      if(codecWaitUs>0L){
       drain(codecWaitUs)
       if(musicDrySinceNs!=null&&bytesWritten!=musicDryAtBytes)musicDrySinceNs=null
      }
      val inputWaitMs=if(codecWaitUs>0L)
       (waitMs*1_000_000-(System.nanoTime()-pollAtNs)).coerceAtLeast(0L)/1_000_000 else waitMs
      val next=if(rtpOrder!=null)rtpOrder.poll(inputWaitMs,hasReorderRunway)else queue.poll(inputWaitMs)
      pending=next
      if(next!=null&&rtpOrder!=null){
       if(rtpGeneration!=rtpOrder.generation){
        rtpGeneration=rtpOrder.generation
        resetAudioTimeline(t,opus)
        speechTiming?.reset()
        musicPrimed=!music
        musicDrySinceNs=null
        musicTargetMs=musicTiming?.targetMs?:WirelessMusicBuffer.START_MS
       }
       observeMusicPacket(next)
      }
     }
     val p=pending
     if(p==null){
      val decoderPending=MediaDecoderActivity.waiting(inFlight,decoderActivityNs,System.nanoTime())
      if(music&&playing&&!decoderPending)emptyQueuePolls++
      stopIdleNavigation()
      continue
     }
     if(queue.expired(p)){queue.drop();pending=null;continue}
     // Use the receive time so a delayed playback worker is not mistaken for a network gap.
     val dryForNs=musicDrySinceNs?.let{p.arrivedNs-it}?:0L
     musicDrySinceNs=null
     if(dryForNs in 1L until 1_000_000_000L){
      confirmedDryEvents++
      maxConfirmedDryNs=maxOf(maxConfirmedDryNs,dryForNs)
     }
     val recoveryTarget=musicTiming?.afterDry(dryForNs,p.arrivedNs)
     if(recoveryTarget!=null){
      musicPrimed=false
      musicTargetMs=recoveryTarget
      // Nominal clocks can be measured again if later playback evidence changes.
      if(trackRate==outputRate)musicClock?.rearm()
      musicRebuffers++
      log("audio_music_starved stage=music_starved dryForMs=${dryForNs/1000000} trackQueuedMs=0 recoveryTargetMs=$musicTargetMs rebufferCount=$musicRebuffers")
     }
     if(!musicPrimed){
      prepareMusic(p,musicTargetMs)
      if(stop.get())break
      if(queue.expired(p)){queue.drop();pending=null;continue}
      musicPrimed=true
     }
     if(speechTiming?.prefillFor(p.arrivedNs)==true){
      rtpOrder?.awaitPrefill(p,f.sampleRate*speechTiming.targetMs/1000,speechTiming.maxWaitMs)
      if(stop.get())break
      if(queue.expired(p)){queue.drop();speechTiming.reset();pending=null;continue}
     }
     val c=decoder
     if(music)maxMusicQueueAgeNs=maxOf(maxMusicQueueAgeNs,System.nanoTime()-p.arrivedNs)
     if(opus!=null){
      val decodeStartNs=if(speechTiming!=null)System.nanoTime()else 0L
      val pcm=opus.decode(p.bytes,MicrophonePacketizer.RTP_HEADER_LEN,p.bytes.size-MicrophonePacketizer.RTP_HEADER_LEN)
      if(decodeStartNs!=0L)maxDecoderInputNs=maxOf(maxDecoderInputNs,System.nanoTime()-decodeStartNs)
      pending=null
      if(pcm==null){
       if(opusErrors++<3)log("audio_opus_decode_failed eventType=audio_codec_failure role=$role codec=OPUS stage=decode")
       continue
      }
      write(pcm)
     }else if(c==null){
      val headerSize=MicrophonePacketizer.RTP_HEADER_LEN
      val length=p.bytes.size-headerSize
      if(length==0||length%(2*f.channels)!=0){queue.drop();pending=null;continue}
      if(role=="call"){
       pcmEvidence.observeByteOrderCandidates(p.bytes,headerSize)
       CallDuplexAB.captureDownlinkPcm(p.bytes,headerSize,f.sampleRate,f.channels,p.sample)
      }
      if(decodedPcm.size!=length)decodedPcm=ByteArray(length)
      for(i in 0 until length step 2){
       decodedPcm[i]=p.bytes[headerSize+i+1]
       decodedPcm[i+1]=p.bytes[headerSize+i]
      }
      write(decodedPcm)
      pending=null
     }else {
      if(p.bytes.size !in 13..(12+0x1fff-7)){queue.drop();pending=null;continue}
      val decodeStartNs=if(music||speechTiming!=null)System.nanoTime()else 0L
      val n=c.dequeueInputBuffer(5000)
      if(decodeStartNs!=0L)maxDecoderInputNs=maxOf(maxDecoderInputNs,System.nanoTime()-decodeStartNs)
      if(n>=0){
       val b=inputBuffers!![n];b.clear()
       val size=MediaCodecSupport.putAdtsFrame(b,p.bytes,MicrophonePacketizer.RTP_HEADER_LEN,f.sampleRate,f.channels)
       // The decoder only needs monotonic presentation times. RTP timestamps wrap at 32 bits.
       c.queueInputBuffer(n,0,size,aacSamplesQueued*1_000_000/f.sampleRate,0)
       decoderActivityNs=System.nanoTime()
       aacSamplesQueued+=1024
       inFlight++;pending=null
      }
      // Preserve a packet across brief decoder backpressure; expiry bounds total latency.
     }
     if(pending==null&&++packets==1)log("audio_first_packet_written_or_queued")
     drain()
    }
    }catch(_:InterruptedException){}catch(e:Throwable){if(!stop.get())log("audio_failed="+e.javaClass.simpleName+" detail="+e.message+" written="+bytesWritten)}finally{if(role=="call")CallRouteSelfTest.finishPlaybackCapture();speechProgress("ended",if(stop.get())-1L else trackQueuedFrames());val callStats=callDownlinkGain?.let{" ${pcmEvidence.byteOrderCandidateSummary()} ${it.summary()}"}.orEmpty();val dryStats=if(realtimeMusic)" ${musicDrySummary()}"else "";log("audio_summary role=$role stream=$stream written=$bytesWritten mutedPackets=$mutedPackets dropped=${queue.drops()} headBeforeStop=$lastHead volume=$appliedVolume peak=${pcmEvidence.peak} nonzero=${pcmEvidence.nonzero}$callStats$dryStats");stop.set(true);queue.clear();runCatching{decoder?.stop()};runCatching{decoder?.release()};runCatching{track?.stop()};runCatching{track?.release()};track=null;if(ownsCallMode)routing?.endCallAudio()}
  }
  fun released()=!thread.isAlive
  override fun close(){
   stop.set(true)
   val currentTrack=track
   queue.clear()
   lastHead=runCatching{currentTrack?.playbackHeadPosition?.toLong()?.and(0xffffffffL)?:lastHead}.getOrDefault(lastHead)
   runCatching{currentTrack?.pause()}
   runCatching{currentTrack?.flush()}
   thread.interrupt()
   if(!MediaWorkers.await(thread))log("audio_release_pending")
  }
 }
 inner class Microphone(private val c:MicrophoneConfig,private val supported:Boolean):Closeable {
  private val stop=AtomicBoolean(false);@Volatile private var recorder:AudioRecord?=null;@Volatile private var socket:DatagramSocket?=null
  private var activeSource=0
  // Keep the requested policy separate from the actual source used for hardware and diagnostics.
  private var requestedRecorderSource:Int?=null
  private val readRecovery=MicrophoneReadRecoveryPolicy()
  private val timing=MicrophoneUplinkTiming(c.frameMillis)
  @Volatile private var opusEncoder:WirelessOpus.Encoder?=null
  @Volatile private var firstPcmReadyNs=Long.MIN_VALUE
  private val sendQueue=if(c.codec==AudioCodecKind.OPUS)MicrophoneFrameQueue(c.frameBytes)else null
  private val telephonyFilter=if(c.audioType.equals("telephony",true))TelephonyMicFilter(c.sampleRate)else null
  private val thread=MediaWorkers.start("api17-mic"){capture()}
  private fun releaseRecorder(){val r=recorder?:return;runCatching{r.stop()};r.release();check(r.state==AudioRecord.STATE_UNINITIALIZED){"Microphone release incomplete"};recorder=null;activeSource=0;requestedRecorderSource=null;readRecovery.recorderStopped()}
  private fun processCapturedPcm(buffer:ByteArray,diagnostic:CallMicCapture.Recorder?){
   val start=System.nanoTime()
   diagnostic?.before(buffer)
   telephonyFilter?.process(buffer)
   diagnostic?.after(buffer)
   val done=System.nanoTime()
   if(firstPcmReadyNs==Long.MIN_VALUE)firstPcmReadyNs=done
   timing.onFrame(done,done-start)
  }
  private fun reportUplink(phase:String,summary:String?){
   if(summary==null)return
   val encoder=opusEncoder
   val codecStats=if(encoder==null)"" else " micEncoder=${encoder.implementation} micComplexity=${encoder.complexity}"
   val eventType=if(phase=="mic_transmit_start")"audio_mic_start" else "audio_mic_send_window"
   log("microphone_send_timing eventType=$eventType stage=$phase kind=${c.audioType} codec=${c.codec}$codecStats $summary")
  }
  private fun alsaCapture(buffer:ByteArray,context:android.content.Context?,diagnostic:CallMicCapture.Recorder?,sendFrame:(ByteArray)->Boolean):Boolean{
   if(!AlsaProbe.available()){log("microphone_alsa_scan result=native_unavailable fallback=AudioRecord kind=${c.audioType}");return false}
   val inventory=try{AlsaCapturePathSelector.discover(context)}catch(e:Throwable){log("microphone_alsa_scan result=inventory_failed error=${e.javaClass.simpleName}");return false}
   val candidates=inventory.candidates
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
    val formats=listOf(48000 to 2,44100 to 2,c.sampleRate to 2,48000 to 1,44100 to 1,c.sampleRate to 1,16000 to 1,8000 to 1).distinct()
    for((requestedRate,requestedChannels) in formats){
      if(stop.get())return null
      if(!beforeCards.containsKey(candidate.card)){beforeCards[candidate.card]=candidate.copy();mixerSnapshot(candidate,"before")}
     val params=IntArray(2)
      val status=IntArray(8)
      val handle=try{AlsaProbe.captureOpen(candidate.path,requestedRate,requestedChannels,params,status)}catch(e:Throwable){
       attempts.add("${candidate.path}:${requestedRate}x${requestedChannels}=jni_${e.javaClass.simpleName}")
       log("microphone_alsa_attempt path=${candidate.path} source=${candidate.source} requestedRate=$requestedRate requestedChannels=$requestedChannels result=jni_failed stage=jni errno=0 rateMin=0 rateMax=0 channelsMin=0 channelsMax=0 negotiatedRate=0 negotiatedChannels=0")
       continue
      }
      val stage=captureStage(status[0])
      if(handle==0L){
       attempts.add("${candidate.path}:${requestedRate}x${requestedChannels}=failed_${stage}_${status[1]}")
       log("microphone_alsa_attempt path=${candidate.path} source=${candidate.source} requestedRate=$requestedRate requestedChannels=$requestedChannels result=open_failed stage=$stage errno=${status[1]} rateMin=${status[2]} rateMax=${status[3]} channelsMin=${status[4]} channelsMax=${status[5]} negotiatedRate=${status[6]} negotiatedChannels=${status[7]}")
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
     val omitted=(inventory.totalCandidates-candidates.size).coerceAtLeast(0)
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
     // Read at most one negotiated packet interval, including for LPCM.
     val readFrames=c.captureFramesPerPacket(current.rate)
     val frameBytes=current.channels*2
     val raw=ByteArray(readFrames*frameBytes)
     val mono=IntArray(readFrames)
     val inputLevels=if(diagnostic!=null)MicrophoneInputLevels(current.channels,current.rate*CallMicCapture.MAX_SECONDS)else null
     val sameRate=current.rate==c.sampleRate
     val step=current.rate.toDouble()/c.sampleRate
     val readHealth=AlsaCaptureReadHealth(System.nanoTime())
     var readFailed=false
     var switchCandidate=false
     try{
     while(!stop.get()&&!switchCandidate){
       val readStarted=System.nanoTime()
       val n=AlsaProbe.captureRead(current.handle,raw,0,raw.size)
       if(stop.get())break
       val frames=n.coerceAtLeast(0)/frameBytes
       val readFinished=System.nanoTime()
       timing.onRead(readStarted,readFinished,if(n<0)n else frames)
       if(readHealth.isStalled(frames,readFinished)){
        readFailed=true
        log("microphone_alsa_read_exhausted path=${current.candidate.path} result=${if(n<0)"read_error" else "timeout"} stage=read errno=${if(n<0)-n else 0}")
        break
       }
       if(frames==0){reportUplink("mic_transmit",timing.reportIfDue(readFinished));Thread.sleep(5);continue}
      inputLevels?.observe(raw,frames)
      for(f in 0 until frames){var acc=0;var ch=0;while(ch<current.channels){val o=(f*current.channels+ch)*2;acc+=((raw[o].toInt() and 255) or (raw[o+1].toInt() shl 8)).toShort().toInt();ch++};mono[f]=acc/current.channels}
      var fi=frac
      var sampleIndex=0
      while(true){
       val i=if(sameRate)sampleIndex++ else kotlin.math.floor(fi).toInt()
       if(i< -1||i>=frames)break
       val v=if(sameRate)mono[i]else {
        val fr=fi-i
        val s0=if(i==-1)carry else mono[i]
        if(fr==0.0)s0 else {
         if(i+1>=frames)break
         (s0+(mono[i+1]-s0)*fr).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt())
        }
       }
       val amp=kotlin.math.abs(v);if(amp>windowPeak)windowPeak=amp;windowAbs+=amp;windowSamples++
       buffer[filled++]=(v and 255).toByte();buffer[filled++]=((v shr 8) and 255).toByte()
       if(filled==buffer.size){
        processCapturedPcm(buffer,diagnostic)
        if(sendFrame(buffer)){captured++;windowPackets++}
        filled=0
       }
       if(!sameRate)fi+=step
      }
      if(!sameRate){
       frac=fi-frames
       carry=mono[frames-1]
      }
      val now=System.nanoTime()
      if(now-windowStart>=TimeUnit.SECONDS.toNanos(1)){
       val meanAbs=if(windowSamples==0L)0L else windowAbs/windowSamples
       log("microphone_duplex_evidence kind=${c.audioType} path=${current.candidate.path} source=alsa windowPackets=$windowPackets windowPeak=$windowPeak windowMeanAbs=$meanAbs hwRate=${current.rate} hwChannels=${current.channels} dsp=${telephonyFilter?.diagnosticSummary()?:"off"}")
       if(!signalReported&&windowPeak>=128&&meanAbs>=16L){
        signalReported=true
        AlsaCapturePathSelector.rememberSignal(context,inventory,current.candidate.path,current.rate,current.channels,windowPeak,meanAbs)
        log("microphone_alsa_signal_detected path=${current.candidate.path} peak=$windowPeak meanAbs=$meanAbs rate=${current.rate} channels=${current.channels}")
       }
       if(windowPeak==0){silentWindows++}else silentWindows=0
       if(silentWindows>=3&&nextIndex<candidates.size){switchCandidate=true}
       windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;windowStart=now
      }
     }
     }finally{
      inputLevels?.summary()?.let{log("microphone_input_levels eventType=audio_mic_input_levels stage=raw_pcm kind=${c.audioType} $it")}
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
       if(next!=null){active=next;selected=next;filled=0;frac=0.0;carry=0;silentWindows=0;signalReported=false;windowStart=System.nanoTime();windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;log("microphone_alsa_switch_selected path=${next.candidate.path} source=${next.candidate.source} rate=${next.rate} channels=${next.channels} untriedCandidates=${(candidates.size-nextIndex).coerceAtLeast(0)} omittedCandidates=${(inventory.totalCandidates-candidates.size).coerceAtLeast(0)} truncated=${inventory.truncated} attempts=${attempts.joinToString(";").take(520)}");continue}
      val restored=openCandidate(current.candidate)
      if(restored==null){log("microphone_capture_fallback path=AudioRecord reason=alsa_zero_path_could_not_reopen kind=${c.audioType} attempts=${attempts.joinToString(";").take(520)}");return false}
      active=restored;selected=restored;filled=0;frac=0.0;carry=0;silentWindows=0;signalReported=false;windowStart=System.nanoTime();windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0
      log("microphone_alsa_zero_alternatives_unavailable retained=${restored.candidate.path} attempts=${attempts.joinToString(";").take(520)}")
     }
    }
    }finally{active?.let{AlsaProbe.captureClose(it.handle)};for(candidate in beforeCards.values)mixerSnapshot(candidate,"after");log("microphone_alsa_closed kind=${c.audioType} selected=${selected.candidate.path} capturedPackets=$captured")}
   return true
  }
  private fun capture(){
   val streamStartNs=System.nanoTime()
   var captured=0L;var silence=0L;var inputBytes=0L;var windowPackets=0;var windowPeak=0;var windowAbs=0L;var windowSamples=0L;var windowStart=streamStartNs
   var diagnostic:CallMicCapture.Recorder?=null
   var sender:Thread?=null
   try{
    log("microphone_negotiated kind=${c.audioType} codec=${c.codec} rate=${c.sampleRate} channels=${c.channels} payloadType=${c.payloadType} frameMillis=${c.frameMillis} frameBytes=${c.frameBytes} rtpSamples=${c.rtpSamplesPerPacket}")
    require((c.codec==AudioCodecKind.LPCM||c.codec==AudioCodecKind.OPUS)&&c.channels==1&&c.sampleRate in 8000..48000&&c.frameBytes in 2..8192&&c.frameMillis in 1..100)
    if(c.codec==AudioCodecKind.OPUS)require(c.sampleRate==48000&&c.frameMillis==20&&c.frameBytes==1920)
    diagnostic=runCatching{CallMicCapture.begin(c.sampleRate,c.audioType)}.getOrNull()
    if(c.codec==AudioCodecKind.OPUS)runCatching{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)}
    val legacyCodecBudget=HotspotEvidence.supported()
    val opus=if(c.codec==AudioCodecKind.OPUS)WirelessOpus.Encoder(c.bitrate?:48000,if(legacyCodecBudget)2 else 5,legacyCodecBudget)else null
    opusEncoder=opus
    if(telephonyFilter!=null)log("microphone_dsp enabled=true kind=telephony location=pcm_before_packetizer routeChanged=false sourceChanged=false downlinkChanged=false ${telephonyFilter.diagnosticSummary()}")
    val context=routing?.captureContext()
    val s=LegacySockets.udp(c.localAddress?:LegacySockets.anyAddress())
    socket=s
    val counters=MicrophoneCounters()
    var sentFirstPacket=false
    var opusErrors=0
    val datagram=DatagramPacket(ByteArray(0),0,c.host,c.port)
    val period=TimeUnit.MILLISECONDS.toNanos(c.frameMillis.toLong())
    val pacer=MicrophoneSendPacer(period)
    var measureEncoderCpu=opus!=null
    fun encoderCpuNow():Long{
     if(!measureEncoderCpu)return -1L
     val now=try{android.os.Debug.threadCpuTimeNanos()}catch(_:RuntimeException){-1L}catch(_:LinkageError){-1L}
     if(now<0L)measureEncoderCpu=false
     return now
    }
    fun writeFrame(pcm:ByteArray,paced:Boolean):Boolean{
     val cpuStarted=encoderCpuNow()
     val encodeStarted=System.nanoTime()
     val body=if(opus!=null)opus.encode(pcm)else MicrophonePacketizer.toCarPlayMicrophoneWirePcm(pcm)
     val encoded=System.nanoTime()
     val cpuFinished=encoderCpuNow()
     val encodeWall=encoded-encodeStarted
     val encodeCpu=if(cpuStarted>=0&&cpuFinished>=cpuStarted)cpuFinished-cpuStarted else -1L
     if(body==null){
      counters.timestamp+=c.rtpSamplesPerPacket
      timing.onEncodeFailure(encoded,encodeWall,encodeCpu)
      reportUplink("mic_transmit",timing.reportIfDue(encoded))
      if(opusErrors++<3)log("microphone_opus_encode_failed eventType=audio_codec_failure streamType=${c.payloadType} codec=OPUS stage=encode")
      return false
     }
     val packet=MicrophonePacketizer.sealPacket(c.key,c.payloadType,counters,body,c.rtpSamplesPerPacket)
     val sealed=System.nanoTime()
     if(paced){
      var waitNs=pacer.waitNanos(System.nanoTime())
      while(waitNs>0&&!stop.get()){
       TimeUnit.NANOSECONDS.sleep(waitNs)
       waitNs=pacer.waitNanos(System.nanoTime())
      }
     }
     if(stop.get())return false
     datagram.setData(packet,0,packet.size)
     val sendStarted=System.nanoTime()
     s.send(datagram)
     val sent=System.nanoTime()
     if(paced)pacer.onPacketSent(sendStarted,sendQueue?.size()?:0)
     timing.onSend(sent,encodeWall,sealed-encoded,sent-sendStarted,encodeCpu)
     reportUplink("mic_transmit",timing.reportIfDue(sent))
     if(!sentFirstPacket){
      sentFirstPacket=true
      log("microphone_first_packet_sent eventType=audio_mic_packet_sent streamType=${c.payloadType} codec=${c.codec} sampleRate=${c.sampleRate} channels=${c.channels} payloadBytes=${body.size}")
      if(opus!=null){
       val pcmReady=firstPcmReadyNs
       val fromPcm=if(pcmReady==Long.MIN_VALUE)-1L else (sent-pcmReady)/1_000_000L
       val cpuUs=if(encodeCpu<0L)-1L else encodeCpu/1_000L
       reportUplink("mic_transmit_start","micFirstPacketMs=${(sent-streamStartNs)/1_000_000L} micFirstFromPcmMs=$fromPcm micFirstEncodeUs=${encodeWall/1_000L} micFirstEncodeCpuUs=$cpuUs")
      }
     }
     return true
    }
    val queue=sendQueue
    if(queue!=null)sender=MediaWorkers.start("api17-mic-send"){
     runCatching{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)}
     val outputPcm=ByteArray(c.frameBytes)
     var nextIndex=0L
     try{
      while(!stop.get()){
       val queued=queue.poll(outputPcm)
       val index=if(queued>=0)queued else {
        val received=queue.take(outputPcm)
        val now=System.nanoTime()
        if(received>=0&&pacer.isLate(now))timing.onQueueUnderrun(now)
        received
       }
       if(index<0||stop.get())break
       val omittedFrames=index-nextIndex
       if(legacyCodecBudget&&omittedFrames>0L)opus?.reduceComplexityForBacklog()
       counters.timestamp+=(omittedFrames*c.rtpSamplesPerPacket).toInt()
       nextIndex=index+1
       writeFrame(outputPcm,paced=true)
      }
     }catch(_:InterruptedException){}catch(error:Exception){
      if(!stop.get())log("microphone_sender_failed eventType=audio_mic_send_failure stage=mic_transmit errorType=${error.javaClass.simpleName}")
     }finally{stop.set(true);queue.close();opus?.close()}
    }
    var offeredFrames=0L
    var reportedDrops=0L
    fun sendFrame(pcm:ByteArray):Boolean{
     if(stop.get())return false
     if(queue==null)return writeFrame(pcm,paced=false)
     if(!queue.offer(pcm,offeredFrames++))return false
     val drops=queue.droppedFrames()
     timing.onQueue(queue.size(),(drops-reportedDrops).toInt())
     reportedDrops=drops
     return true
    }
    val buffer=ByteArray(c.frameBytes);var filled=0;var paused=false;var nextSilenceNs=0L
    // Prefer a direct, dynamically discovered ALSA input on every microphone stream.
    // The MX9A0's known-good pcmC1D0c path is tried first; other capture-capable nodes
    // are discovered from /proc/asound and /dev/snd. AudioRecord is used only when no
    // direct ALSA candidate can be opened, and that fallback is explicitly logged.
    val alsaDone=alsaCapture(buffer,context,diagnostic,::sendFrame)
    val audioManager=if(alsaDone||stop.get())null else context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
    while(!alsaDone&&!stop.get()){
     if(audioManager!=null)CallRouteSelfTest.updateLiveRoute(audioManager,context)
     val duplexStage=if(c.audioType.equals("telephony",true))CallDuplexAB.microphoneStage(context,activeSource,recorder?.recordingState?:0)else -1
     val stageSource=CallDuplexAB.microphoneSourceForStage(duplexStage)
     val diagnosticSource=CallRouteSelfTest.liveMicrophoneSource()
     val wantedSource=if(diagnosticSource>=0)diagnosticSource else if(stageSource>=0)stageSource else if(c.audioType.equals("telephony",true))MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
     val diagnosticHold=CallDuplexAB.holdMicrophone(duplexStage)
     if(recorder!=null&&requestedRecorderSource!=wantedSource){val old=activeSource;releaseRecorder();filled=0;log("live_duplex_source_switch from=$old to=$wantedSource stage=${CallRouteSelfTest.liveStage()}")}
     if(!siriCapture.capture(supported,c.audioType)||diagnosticHold){
      if(!paused){releaseRecorder();filled=0;buffer.fill(0);paused=true;nextSilenceNs=System.nanoTime();log("microphone_playback_hold mode=${siriCapture.mode} hardwareReleased=true kind=${c.audioType} duplexStage=$duplexStage diagnosticHold=$diagnosticHold")}
      // No AudioRecord exists here. Silence keeps RTP sequence/timestamps continuous.
      val period=c.frameMillis*1000000L;nextSilenceNs+=period;val waitNs=nextSilenceNs-System.nanoTime()
      if(waitNs>0)Thread.sleep(waitNs/1000000L,(waitNs%1000000L).toInt())else if(waitNs< -period)nextSilenceNs=System.nanoTime()
      if(stop.get())break
      if(siriCapture.capture(supported,c.audioType)&&!CallDuplexAB.holdMicrophone(if(c.audioType.equals("telephony",true))CallDuplexAB.microphoneStage(context,activeSource,recorder?.recordingState?:0)else -1))continue
      if(sendFrame(buffer))silence++
      continue
     }
     paused=false
     if(recorder==null){
      val min=AudioRecord.getMinBufferSize(c.sampleRate,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT);require(min>0){"Unsupported microphone buffer rate=${c.sampleRate} result=$min"}
      val bufferBytes=maxOf(min*2,c.frameBytes*4);val isCall=c.audioType.equals("telephony",true);var selected=wantedSource
      var r=runCatching{AudioRecord(selected,c.sampleRate,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,bufferBytes)}.getOrNull()
      if(r?.state!=AudioRecord.STATE_INITIALIZED){runCatching{r?.release()};r=null;if(isCall&&selected!=MediaRecorder.AudioSource.MIC){selected=MediaRecorder.AudioSource.MIC;r=runCatching{AudioRecord(selected,c.sampleRate,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,bufferBytes)}.getOrNull();if(r?.state!=AudioRecord.STATE_INITIALIZED){runCatching{r?.release()};r=null}}}
      recorder=r;require(r?.state==AudioRecord.STATE_INITIALIZED){"AudioRecord unavailable for source=$selected"}
      activeSource=selected
      requestedRecorderSource=wantedSource
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
      val readStarted=System.nanoTime()
      val n=recorder!!.read(buffer,filled,buffer.size-filled);if(stop.get())break
      val readFinished=System.nanoTime()
      timing.onRead(readStarted,readFinished,if(n<0)n else n/2)
      if(n<=0)reportUplink("mic_transmit",timing.reportIfDue(readFinished))
      when(readRecovery.onReadResult(n,readFinished)){
       MicrophoneReadRecoveryPolicy.Action.DATA -> filled+=n
       MicrophoneReadRecoveryPolicy.Action.WAIT -> {Thread.sleep(5);continue}
       MicrophoneReadRecoveryPolicy.Action.RESTART -> {val source=activeSource;releaseRecorder();filled=0;buffer.fill(0);log("microphone_recording_restarted reason=${if(n<0)"read-error" else "no-audio"} code=$n attempt=${readRecovery.restartAttempts} source=$source");if(n<0)Thread.sleep(20);continue}
       MicrophoneReadRecoveryPolicy.Action.RESTARTS_EXHAUSTED -> {log("microphone_recovery_exhausted reason=no-audio retries=${readRecovery.restartAttempts} source=$activeSource");Thread.sleep(50);continue}
       MicrophoneReadRecoveryPolicy.Action.FATAL -> throw IOException("Microphone read failed after ${readRecovery.restartAttempts} restarts: $n")
      }
     if(!siriCapture.capture(supported,c.audioType)||CallDuplexAB.holdMicrophone(if(c.audioType.equals("telephony",true))CallDuplexAB.microphoneStage(context,activeSource,recorder?.recordingState?:0)else -1)){filled=0;continue}
     if(filled==buffer.size){for(i in buffer.indices step 2){val sample=((buffer[i].toInt() and 255) or (buffer[i+1].toInt() shl 8)).toShort().toInt();val amp=kotlin.math.abs(sample);if(amp>windowPeak)windowPeak=amp;windowAbs+=amp;windowSamples++};processCapturedPcm(buffer,diagnostic);if(sendFrame(buffer)){captured++;windowPackets++};inputBytes+=buffer.size;filled=0;val now=System.nanoTime();if(now-windowStart>=TimeUnit.SECONDS.toNanos(1)){log("microphone_duplex_evidence kind=${c.audioType} sentPackets=$captured windowPackets=$windowPackets inputBytes=$inputBytes windowPeak=$windowPeak windowMeanAbs=${if(windowSamples==0L)0 else windowAbs/windowSamples} source=$activeSource liveStage=${CallRouteSelfTest.liveStage()} recorderState=${recorder?.recordingState} dsp=${telephonyFilter?.diagnosticSummary()?:"off"}");windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;windowStart=now}}
    }
   }catch(_:InterruptedException){}catch(e:Throwable){if(!stop.get())log("microphone_failed="+e.javaClass.simpleName+" detail="+e.message)}finally{
    stop.set(true)
    sendQueue?.close()
    socket?.close()
    sender?.let{it.interrupt();MediaWorkers.await(it)}
    if(sender==null)opusEncoder?.close()
    reportUplink("mic_transmit_end",timing.finish(System.nanoTime()))
    diagnostic?.finish()
    runCatching{releaseRecorder()}.onFailure{log("microphone_release_failed="+it.javaClass.simpleName)}
    log("microphone_stopped kind=${c.audioType} capturedPackets=$captured silentPackets=$silence")
   }
  }
  fun released()=!thread.isAlive
  override fun close(){stop.set(true);sendQueue?.close();socket?.close();runCatching{recorder?.stop()};thread.interrupt();if(!MediaWorkers.await(thread))log("microphone_release_pending")}
 }
 override fun close(){if(!closed.compareAndSet(false,true))return
  val outputs:List<AudioOutput>;val inputs:List<Microphone>
  synchronized(this){outputs=audio.values.toList();audio.clear();inputs=microphones.values.toList();microphones.clear()}
  recoveryWork.shutdownNow();synchronized(videoTargetLock){videoTarget.input.clear();latestVideoConfig=null};videoThread.interrupt()
  for(a in outputs)a.close();for(m in inputs)m.close()
  if(!MediaWorkers.await(videoThread))log("video_release_pending");playbackFocus?.close();routing?.close()
 }
}
