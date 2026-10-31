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
 private class VideoTarget(val surface:Surface?)
 @Volatile private var videoTarget=VideoTarget(surface)
 @Synchronized fun setSurface(surface:Surface?){if(closed.get())return;if(videoTarget.surface===surface)return;videoTarget=VideoTarget(surface);videoInput.dropped()}
 @Volatile var renderedVideoFrames=0L;private set
 @Volatile var receivedVideo=false;private set
 @Volatile var routing:AudioRouting?=null
 fun setRoleVolume(role:String,value:Float){for(a in audio.values)if(a.role==role)a.volume(value)}
 @Volatile var playbackFocus:PlaybackFocus?=null
 fun setOutputVolume(value:Float){for(a in audio.values)a.volume(value)}
 @Volatile var onVideoFailure:((Throwable)->Unit)?=null
 private val closed=AtomicBoolean(false)
 private val videoInput=VideoInput()
 private val videoFailed=AtomicBoolean(false)
 private var lastRecoveryNs=0L
 private val recoveryWork=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(1))
 @Volatile private var diagnostic:((String)->Unit)?=null
 @Volatile private var recovery:(()->Unit)?=null
 private val audio=ConcurrentHashMap<AudioStreamId,AudioOutput>()
 private val microphones=ConcurrentHashMap<AudioStreamId,Microphone>()
 private val videoThread=MediaWorkers.start("api17-video"){videoLoop()}
 override fun setVideoDiagnosticHandler(type:Int,handler:(String)->Unit){diagnostic=handler}
 override fun setVideoRecoveryHandler(type:Int,handler:()->Unit){recovery=handler}
 override fun onVideoConfig(type:Int,codecData:ByteArray){if(!closed.get()&&!videoFailed.get()&&videoInput.configure(codecData))log("video_configuration_changed bytes=${codecData.size}")}
 override fun onVideoFrame(type:Int,naluBytes:ByteArray){if(naluBytes.isNotEmpty())receivedVideo=true;if(closed.get()||videoFailed.get()||videoTarget.surface?.isValid!=true)return;if(!videoInput.offer(naluBytes))requestRecovery("queue_overflow")}
 override fun onOwnedVideoFrame(type:Int,naluBytes:ByteArray,senderNanos:Long,arrivalNanos:Long){if(naluBytes.isNotEmpty())receivedVideo=true;if(closed.get()||videoFailed.get()||videoTarget.surface?.isValid!=true)return;if(!videoInput.offerOwned(naluBytes))requestRecovery("queue_overflow")}
 @Synchronized private fun requestRecovery(reason:String){val now=System.nanoTime();if(now-lastRecoveryNs<TimeUnit.SECONDS.toNanos(2))return;lastRecoveryNs=now;log("video_recovery reason=$reason");try{recoveryWork.execute{if(!closed.get())runCatching{recovery?.invoke()}}}catch(_:RejectedExecutionException){}}
 private fun videoLoop(){var codec:MediaCodec?=null;var configured:ByteArray?=null;var frames=0;var stage="waiting_config";var target=videoTarget;var surfaceFrames=0
  var pending:VideoInput.Frame?=null;var buffers:Array<ByteBuffer>?=null
  val info=MediaCodec.BufferInfo();var inFlight=0;var skipped=0;var queueMaxMs=0L;var decodeMaxMs=0L;var lastStats=System.nanoTime()
  var releaseMaxUs=0L;var dequeueOverrunMaxUs=0L;var inputMaxUs=0L;var inFlightPeak=0
  fun dispose(){runCatching{codec?.stop()};runCatching{codec?.release()};codec=null;configured=null;buffers=null;pending=null;inFlight=0}
  fun drain(waitUs:Long=0){val c=codec?:return;stage="drain";var latest=-1;var latestPts=0L
   fun release(index:Int,render:Boolean){val at=System.nanoTime();c.releaseOutputBuffer(index,render);releaseMaxUs=maxOf(releaseMaxUs,(System.nanoTime()-at)/1000)}
   // Feed every reference frame to the decoder, but display only the newest ready output.
   try{for(i in 0 until 32){val budget=if(i==0)waitUs else 0;val at=System.nanoTime();val n=c.dequeueOutputBuffer(info,budget);dequeueOverrunMaxUs=maxOf(dequeueOverrunMaxUs,(System.nanoTime()-at)/1000-budget)
    if(n>=0){inFlight=maxOf(0,inFlight-1);if(latest>=0){release(latest,false);skipped++};latest=n;latestPts=info.presentationTimeUs}
    else if(n==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)log("video_output_format_changed")
    else if(n!=MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)break
   }}finally{if(latest>=0){val render=target===videoTarget&&target.surface?.isValid==true;release(latest,render);if(render){frames++;renderedVideoFrames++;surfaceFrames++;if(surfaceFrames==1)log("video_surface_first_frame")};decodeMaxMs=maxOf(decodeMaxMs,(System.nanoTime()/1000-latestPts)/1000);if(render&&frames==1){diagnostic?.invoke("first frame rendered");log("video_rendered_frames=1")}}}
  }
  try{while(!closed.get()) {
   if(target!==videoTarget){dispose();target=videoTarget;surfaceFrames=0;videoInput.dropped();log("video_surface_changed attached=${target.surface!=null}");if(target.surface?.isValid==true)requestRecovery("surface_restored")}
   if(target.surface?.isValid!=true){dispose();Thread.sleep(50);continue}
   try {
   // Drain before waiting for a new frame. Sparse updates must not delay already decoded output by 100ms.
   drain()
   if(pending==null){
    // When decoding is in flight, wait on the codec's completion, not a queue timer.
    // Ready output wakes immediately; a 5ms cap still lets new input/surface changes through.
    pending=videoInput.poll(if(inFlight==0)100 else 0)
    if(pending==null&&inFlight>0){drain(5000);pending=videoInput.poll(0)}
   }
   val packet=pending
   if(packet!=null&&packet.config!==configured){stage="configure";require(target.surface?.isValid==true){"Video Surface is invalid"};runCatching{codec?.stop()};runCatching{codec?.release()};codec=null
    val(sps,pps)=MediaCodecSupport.avcParameterSets(packet.config);require(sps.isNotEmpty()&&pps.isNotEmpty()){"Invalid AVC parameter sets"}
    val format=MediaFormat.createVideoFormat("video/avc",width,height);format.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+sps));format.setByteBuffer("csd-1",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pps));format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,1024*1024)
    val c=MediaCodec.createDecoderByType("video/avc");codec=c;c.configure(format,target.surface,null,0);c.start();buffers=c.inputBuffers;inFlight=0;configured=packet.config
    // Codec metadata only, not image/audio contents. Allows offline checking of
    // reference/reorder requirements before attempting vendor decoder tuning.
    val spsDiagnostic=if(sps.size<=256)com.shilapi.xcertplay.compat.Base64Compat.encode(sps) else "oversize"
    log("video_decoder_started name=default-avc width=$width height=$height sps=$spsDiagnostic")
   }
   val c=codec?:continue
   if(packet!=null){stage="input";val inputAt=System.nanoTime();val index=c.dequeueInputBuffer(5000)
    if(index>=0){val buffer=buffers!![index];buffer.clear();val byteCount=VideoBufferWriter.putAnnexB(packet.bytes,buffer);queueMaxMs=maxOf(queueMaxMs,(System.nanoTime()-packet.arrivedNs)/1000000);c.queueInputBuffer(index,0,byteCount,System.nanoTime()/1000,0);inFlight++;inFlightPeak=maxOf(inFlightPeak,inFlight);pending=null}
    // A brief busy decoder is normal. Retain this reference frame instead of destroying the GOP every 20ms.
    else if(System.nanoTime()-packet.arrivedNs>TimeUnit.MILLISECONDS.toNanos(250)){pending=null;videoInput.dropped();requestRecovery("decoder_backpressure")}
    inputMaxUs=maxOf(inputMaxUs,(System.nanoTime()-inputAt)/1000)
   }
   drain()
   val now=System.nanoTime();if(now-lastStats>=TimeUnit.SECONDS.toNanos(5)){log("video_progress rendered=$frames skippedOutput=$skipped queued=${videoInput.size()} queueMaxMs=$queueMaxMs decodeMaxMs=$decodeMaxMs releaseMaxUs=$releaseMaxUs dequeueOverrunMaxUs=$dequeueOverrunMaxUs inputMaxUs=$inputMaxUs inFlightPeak=$inFlightPeak");lastStats=now;queueMaxMs=0;decodeMaxMs=0;releaseMaxUs=0;dequeueOverrunMaxUs=0;inputMaxUs=0;inFlightPeak=inFlight}
   }catch(e:Throwable){if(e is InterruptedException)throw e;if(target!==videoTarget||target.surface?.isValid!=true){dispose();videoInput.dropped();log("video_surface_unavailable stage=$stage; session retained")}else throw e}
  }}catch(e:InterruptedException){}catch(e:Throwable){if(!closed.get()){videoFailed.set(true);videoInput.clear();log("video_failed stage=$stage type=${e.javaClass.simpleName} detail=${e.message} surfaceValid=${target.surface?.isValid} at=${e.stackTrace.take(4).joinToString()}");onVideoFailure?.invoke(e)}}finally{runCatching{codec?.stop()};runCatching{codec?.release()}}
 }
 @Synchronized override fun onAudioStarted(id:AudioStreamId,format:com.shilapi.xcertplay.airplay.AudioFormat,firstSample:Int){if(closed.get())return;log("audio_stream_start type=${id.type} kind=${id.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} payloadType=${format.payloadType} firstRtpSample=${firstSample.toLong() and 0xffffffffL}");val previous=audio.keys.filter{it.type==id.type&&it.audioType==id.audioType};for(key in previous)audio.remove(key)?.let{it.close();check(it.released()){"Previous audio worker is still releasing"}};val role=LegacyAudioPolicy.role(id.type,format.audioType);val callMode=role=="call";if(callMode){routing?.beginCallAudio();CallDuplexAB.callStreamStarting(routing?.captureContext())};try{audio[id]=AudioOutput(id,format,callMode)}catch(t:Throwable){if(callMode)routing?.endCallAudio();throw t}}
 override fun onAudioRtp(id:AudioStreamId,format:com.shilapi.xcertplay.airplay.AudioFormat,rtp:ByteArray,sample:Int){audio[id]?.offer(rtp,sample)}
 @Synchronized override fun onAudioStopped(id:AudioStreamId){audio.remove(id)?.let{log("audio_stopped type=${id.type} kind=${id.audioType}");it.close();val focusRole=if(it.role=="assistant")"media" else it.role;if(audio.values.none{v->(if(v.role=="assistant")"media" else v.role)==focusRole})routing?.playbackEnded(focusRole)}}
 @Synchronized override fun onMicrophoneStarted(id:AudioStreamId,config:MicrophoneConfig){if(closed.get())return;val previous=microphones.values.toList();for(m in previous)m.close();check(previous.all{it.released()}){"Previous microphone is still releasing"};microphones.clear();microphones[id]=Microphone(config,routing?.captureContext()?.let{AudioRouting.secondMusic(it)==10}?:false)}
 @Synchronized override fun onMicrophoneStopped(id:AudioStreamId){microphones.remove(id)?.close()}
 inner class AudioOutput(id:AudioStreamId,private val f:com.shilapi.xcertplay.airplay.AudioFormat,private val ownsCallMode:Boolean):Closeable {
  val role=LegacyAudioPolicy.role(id.type,f.audioType);private val stream=routing?.stream(role)?:AudioManager.STREAM_MUSIC
  private var playing=false;private val navigationActivity=NavigationActivity()
  private val stop=AtomicBoolean(false);private val queue=AudioInput();@Volatile private var track:AudioTrack?=null
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
  fun offer(b:ByteArray,t:Int){if(!stop.get())queue.offer(b,t)}
  @Synchronized fun volume(value:Float){val t=track?:return;if(appliedVolume==value)return;runCatching{if(t.setStereoVolume(value,value)==AudioTrack.SUCCESS)appliedVolume=value}}
  private fun stopIdleNavigation(){if(playing&&role=="navigation"&&!navigationActivity.active(System.nanoTime())){track?.stop();playing=false;log("navigation_idle_stopped")}}
  private fun write(input:ByteArray){val channelBytes=if(outputChannels!=f.channels)PcmEvidence.stereo(input)else input;val bytes=rateConverter?.invoke(channelBytes)?:channelBytes;pcmEvidence.observe(bytes);if(role=="navigation"&&!navigationActivity.packet(bytes,System.nanoTime())){stopIdleNavigation();return};val level=routing?.packet(role)?:playbackFocus?.packet()?:1f;volume(level);if(level==0f){mutedPackets++;progress();return};if(role=="call")CallDuplexAB.callDownlinkFirstPcm(routing?.captureContext());if(!playing){track!!.play();playing=true};if(role=="call")CallDuplexAB.injectPhaseTone(routing?.captureContext(),track!!,outputRate,outputChannels,stream);if(role=="call")CallToneProbe.maybeInject(routing?.captureContext(),track!!,outputRate,outputChannels,stream);var at=0;while(at<bytes.size&&!stop.get()){val n=track!!.write(bytes,at,bytes.size-at);if(n<=0)throw IllegalStateException("AudioTrack write failed");at+=n;bytesWritten+=n};if(bytesWritten==bytes.size.toLong())log("audio_first_pcm_written bytes=${bytes.size}");progress()}
  private fun progress(){val now=System.nanoTime();if(now-lastProgress<TimeUnit.SECONDS.toNanos(if(checkpoints<5||role=="assistant")1 else 10))return;lastHead=runCatching{track?.playbackHeadPosition?.toLong()?.and(0xffffffffL)?:lastHead}.getOrDefault(lastHead);lastProgress=now;val tag=if(checkpoints++<5)"audio_checkpoint" else "audio_progress";val endian=if(role=="call")" ${pcmEvidence.byteOrderCandidateSummary()}" else "";log("$tag role=$role stream=$stream written=$bytesWritten mutedPackets=$mutedPackets queued=${queue.size()} dropped=${queue.drops()} head=$lastHead peak=${pcmEvidence.peak} nonzero=${pcmEvidence.nonzero} volume=$appliedVolume$endian");if(role=="call")AudioRouting.logCallOutput(routing?.captureContext()?:return,track,stream,tag,bytesWritten,lastHead,appliedVolume)}
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
    val channels=if(outputChannels==1)android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
    val min=AudioTrack.getMinBufferSize(outputRate,channels,android.media.AudioFormat.ENCODING_PCM_16BIT);require(min>0)
    val t=AudioTrack(stream,outputRate,channels,android.media.AudioFormat.ENCODING_PCM_16BIT,maxOf(min*2,outputRate*outputChannels/5),AudioTrack.MODE_STREAM)
    // Publish only after initial gain and its cache agree. Focus callbacks use the same lock.
    synchronized(this){track=t;require(t.state==AudioTrack.STATE_INITIALIZED);check(t.setStereoVolume(0f,0f)==AudioTrack.SUCCESS){"Initial audio gain rejected"};appliedVolume=0f}
    if(f.codec==AudioCodecKind.AAC_LC){val m=MediaFormat.createAudioFormat("audio/mp4a-latm",f.sampleRate,f.channels);m.setInteger("is-adts",1);val value=(2 shl 11) or (MediaCodecSupport.aacFrequencyIndex(f.sampleRate) shl 7) or (f.channels shl 3);m.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf((value shr 8).toByte(),value.toByte())));decoder=MediaCodec.createDecoderByType("audio/mp4a-latm");decoder.configure(m,null,null,0);decoder.start();inputBuffers=decoder.inputBuffers;outputBuffers=decoder.outputBuffers}
    log("audio_started role=$role stream=$stream codec=${f.codec} inputRate=${f.sampleRate} inputChannels=${f.channels} outputChannels=$outputChannels outputRate=$outputRate actualStream=${t.streamType} nativeRate=${AudioTrack.getNativeOutputSampleRate(stream)}");var packets=0;var pending:AudioInput.Packet?=null
    while(!stop.get()){
     drain()
     if(pending==null)pending=queue.poll(if(inFlight==0)100 else 5)
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
  private val thread=MediaWorkers.start("api17-mic"){capture()}
  private var activeSource=0
  private val readRecovery=MicrophoneReadRecoveryPolicy()
  private fun releaseRecorder(){val r=recorder?:return;runCatching{r.stop()};r.release();check(r.state==AudioRecord.STATE_UNINITIALIZED){"Microphone release incomplete"};recorder=null;activeSource=0;readRecovery.recorderStopped()}
  private fun alsaCapture(s:DatagramSocket,counters:MicrophoneCounters,buffer:ByteArray,context:android.content.Context?):Boolean{
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
   var captured=0L;var errors=0;var silentWindows=0;var signalReported=false
   var filled=0;var carry=0;var frac=0.0
   var windowPackets=0;var windowPeak=0;var windowAbs=0L;var windowSamples=0L;var windowStart=System.nanoTime()
   try{
    while(!stop.get()){
     val current=active?:break
     val raw=ByteArray(maxOf(1024,current.rate*current.channels*2/10));val mono=IntArray(raw.size/(2*current.channels)+2);val step=current.rate.toDouble()/c.sampleRate
     var switchCandidate=false
     while(!stop.get()&&!switchCandidate){
       val n=AlsaProbe.captureRead(current.handle,raw,0,raw.size)
       if(n<=0){if(++errors>50){log("microphone_alsa_read_exhausted path=${current.candidate.path} result=${if(n==0)"timeout" else "read_error"} stage=read errno=${if(n<0)-n else 0} errors=$errors");break};Thread.sleep(5);continue}
      errors=0
      val frames=(n/(2*current.channels)).coerceAtMost(mono.size)
      if(frames<=0)continue
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
       log("microphone_duplex_evidence kind=${c.audioType} path=${current.candidate.path} source=alsa windowPackets=$windowPackets windowPeak=$windowPeak windowMeanAbs=$meanAbs hwRate=${current.rate} hwChannels=${current.channels} nextRtpSequence=${counters.sequence}")
       if(windowPeak>=128&&meanAbs>=16L){AlsaCapturePathSelector.rememberSignal(context,inventory,current.candidate.path,current.rate,current.channels,windowPeak,meanAbs);if(!signalReported){log("microphone_alsa_signal_detected path=${current.candidate.path} peak=$windowPeak meanAbs=$meanAbs rate=${current.rate} channels=${current.channels}");signalReported=true}}
       if(windowPeak==0){silentWindows++}else silentWindows=0
       if(silentWindows>=3&&nextIndex<candidates.size){switchCandidate=true}
       windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;windowStart=now
      }
     }
     if(stop.get())break
     if(errors>50){
      AlsaProbe.captureClose(current.handle);active=null
      val next=openNext()
      if(next==null){log("microphone_capture_fallback path=AudioRecord reason=alsa_read_failed kind=${c.audioType} attempts=${attempts.joinToString(";").take(520)}");return false}
      active=next;selected=next;filled=0;frac=0.0;carry=0;errors=0;silentWindows=0;windowStart=System.nanoTime()
      log("microphone_alsa_switch reason=read_failed path=${next.candidate.path} rate=${next.rate} channels=${next.channels} attempts=${attempts.joinToString(";").take(520)}")
      continue
     }
     if(switchCandidate){
      log("microphone_alsa_switch reason=three_digital_zero_windows from=${current.candidate.path} to=probe_next")
      AlsaProbe.captureClose(current.handle);active=null
      val next=openNext()
       if(next!=null){active=next;selected=next;filled=0;frac=0.0;carry=0;errors=0;silentWindows=0;windowStart=System.nanoTime();windowPeak=0;windowAbs=0;windowSamples=0;log("microphone_alsa_switch_selected path=${next.candidate.path} source=${next.candidate.source} rate=${next.rate} channels=${next.channels} untriedCandidates=${(candidates.size-nextIndex).coerceAtLeast(0)} omittedCandidates=${(inventory.totalCandidates-candidates.size).coerceAtLeast(0)} truncated=${inventory.truncated} attempts=${attempts.joinToString(";").take(520)}");continue}
      val restored=openCandidate(current.candidate)
      if(restored==null){log("microphone_capture_fallback path=AudioRecord reason=alsa_zero_path_could_not_reopen kind=${c.audioType} attempts=${attempts.joinToString(";").take(520)}");return false}
      active=restored;selected=restored;filled=0;frac=0.0;carry=0;errors=0;silentWindows=0;windowStart=System.nanoTime();windowPeak=0;windowAbs=0;windowSamples=0
      log("microphone_alsa_zero_alternatives_unavailable retained=${restored.candidate.path} attempts=${attempts.joinToString(";").take(520)}")
     }
    }
    }finally{active?.let{AlsaProbe.captureClose(it.handle)};for(candidate in beforeCards.values)mixerSnapshot(candidate,"after");log("microphone_alsa_closed kind=${c.audioType} selected=${selected.candidate.path} capturedPackets=$captured")}
   return true
  }
  private fun capture(){var captured=0L;var silence=0L;var inputBytes=0L;var windowPackets=0;var windowPeak=0;var windowAbs=0L;var windowSamples=0L;var windowStart=System.nanoTime()
   try{
    log("microphone_negotiated kind=${c.audioType} codec=${c.codec} rate=${c.sampleRate} channels=${c.channels} payloadType=${c.payloadType} frameMillis=${c.frameMillis} frameBytes=${c.frameBytes} rtpSamples=${c.rtpSamplesPerPacket}")
    require(c.codec==AudioCodecKind.LPCM&&c.channels==1&&c.sampleRate in 8000..48000&&c.frameBytes in 2..8192&&c.frameMillis in 1..100)
    val context=routing?.captureContext()
    val s=DatagramSocket();socket=s;val counters=MicrophoneCounters();val buffer=ByteArray(c.frameBytes);var filled=0;var paused=false;var nextSilenceNs=0L
    // Prefer a direct, dynamically discovered ALSA input on every microphone stream.
    // The MX9A0's known-good pcmC1D0c path is tried first; other capture-capable nodes
    // are discovered from /proc/asound and /dev/snd. AudioRecord is used only when no
    // direct ALSA candidate can be opened, and that fallback is explicitly logged.
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
     if(filled==buffer.size){for(i in buffer.indices step 2){val sample=((buffer[i].toInt() and 255) or (buffer[i+1].toInt() shl 8)).toShort().toInt();val amp=kotlin.math.abs(sample);if(amp>windowPeak)windowPeak=amp;windowAbs+=amp;windowSamples++};val p=MicrophonePacketizer.sealPacket(c.key,c.payloadType,counters,MicrophonePacketizer.toCarPlayMicrophoneWirePcm(buffer),c.rtpSamplesPerPacket);s.send(DatagramPacket(p,p.size,c.host,c.port));captured++;inputBytes+=buffer.size;windowPackets++;filled=0;val now=System.nanoTime();if(now-windowStart>=TimeUnit.SECONDS.toNanos(1)){log("microphone_duplex_evidence kind=${c.audioType} sentPackets=$captured windowPackets=$windowPackets inputBytes=$inputBytes windowPeak=$windowPeak windowMeanAbs=${if(windowSamples==0L)0 else windowAbs/windowSamples} source=$activeSource liveStage=${CallRouteSelfTest.liveStage()} nextRtpSequence=${counters.sequence} nextRtpTimestamp=${counters.timestamp.toLong() and 0xffffffffL} recorderState=${recorder?.recordingState}");windowPackets=0;windowPeak=0;windowAbs=0;windowSamples=0;windowStart=now}}
    }
   }catch(_:InterruptedException){}catch(e:Throwable){if(!stop.get())log("microphone_failed="+e.javaClass.simpleName+" detail="+e.message)}finally{stop.set(true);runCatching{releaseRecorder()}.onFailure{log("microphone_release_failed="+it.javaClass.simpleName)};socket?.close();log("microphone_stopped kind=${c.audioType} capturedPackets=$captured silentPackets=$silence")}
  }
  fun released()=!thread.isAlive
  override fun close(){stop.set(true);socket?.close();runCatching{recorder?.stop()};thread.interrupt();if(!MediaWorkers.await(thread))log("microphone_release_pending")}
 }
 override fun close(){if(!closed.compareAndSet(false,true))return
  val outputs:List<AudioOutput>;val inputs:List<Microphone>
  synchronized(this){outputs=audio.values.toList();audio.clear();inputs=microphones.values.toList();microphones.clear()}
  recoveryWork.shutdownNow();videoInput.clear();videoThread.interrupt()
  for(a in outputs)a.close();for(m in inputs)m.close()
  if(!MediaWorkers.await(videoThread))log("video_release_pending");playbackFocus?.close();routing?.close()
 }
}
