package local.headunit.diplay;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One consented real-call probe; avoids replaying already-confirmed synthetic route tests. */
public final class CallRouteSelfTest {
 private static final int CAPTURE_SECONDS=3;
 private static final String CALL_TEST_INSTRUCTIONS="\u505c\u8f66\u540e\u8fdb\u884c\u3002\u51c6\u5907\u540e\u901a\u8fc7 CarPlay \u63a5\u542c\u6216\u62e8\u6253\u4e00\u901a\u6d4b\u8bd5\u7535\u8bdd\uff1a\u901a\u8bdd\u5f00\u59cb\u65f6\u6ce8\u610f\u8f66\u673a\u662f\u5426\u64ad\u51fa\u77ed\u63d0\u793a\u97f3\uff1b\u8ba9\u5bf9\u65b9\u6301\u7eed\u8bf4\u8bdd\u81f3\u5c11 3 \u79d2\uff0c\u4f60\u4e5f\u8bf4\u4e00\u6b21\u201cCarPlay \u7535\u8bdd\u58f0\u97f3\u6d4b\u8bd5 123\u201d\uff0c\u8ba9\u5bf9\u65b9\u786e\u8ba4\u662f\u5426\u542c\u5230\u3002\u6302\u65ad\u540e\u518d\u6b21\u70b9\u672c\u6309\u94ae\u56de\u653e\u56db\u7ec4\u76f8\u540c\u901a\u8bdd\u97f3\u9891\uff0c\u6309\u5b9e\u9645\u542c\u5230\u7684\u7ed3\u679c\u9009\u62e9\u6e05\u6670\u8bed\u97f3\u3001\u53ea\u6709\u6742\u97f3\u6216\u6ca1\u6709\u58f0\u97f3\u3002\u6700\u591a\u6682\u5b58 3 \u79d2\u901a\u8bdd\u4e0b\u884c PCM\uff0c\u4e0d\u5f55\u5236\u9ea6\u514b\u98ce\uff1b\u6700\u540e\u8bf7\u624b\u52a8\u63d0\u4ea4\u65e5\u5fd7\u3002";
 private static final Object LOCK=new Object();
 private static final CallPcmRingBuffer RING=new CallPcmRingBuffer();
 private static boolean armed,attempted,liveArmed;
 private static volatile boolean acousticProbeAttempted,acousticProbeWritten;
 private static long liveStartedAt;
 private static int liveStage=-1;
 private static int liveRestoreMode=AudioManager.MODE_NORMAL;
 private static boolean liveRestorePending;
 private static Context liveContext;
 private static long deadline,packets,bytes;
 private static int firstSample,lastSample,rate,channels;
 private static PcmEvidence evidence=new PcmEvidence();
 private CallRouteSelfTest(){}

 public static void offer(final MainActivity owner){
  if(hasCapture()){showReplay(owner);return;}
  if(attempted){
   new AlertDialog.Builder(owner).setTitle("未采到通话下行音频")
   .setMessage(CALL_TEST_INSTRUCTIONS)
    .setNegativeButton("取消",null).setPositiveButton("重新准备",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){arm(owner);}}).show();
   return;
  }
  new AlertDialog.Builder(owner).setTitle("真实通话一次性诊断")
   .setMessage(CALL_TEST_INSTRUCTIONS)
   .setNegativeButton("取消",null).setPositiveButton("同意并准备",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){arm(owner);}}).show();
 }

 private static void arm(MainActivity owner){
  synchronized(LOCK){RING.clear();armed=true;attempted=true;liveArmed=false;acousticProbeAttempted=false;acousticProbeWritten=false;liveStartedAt=0;liveStage=-1;liveContext=owner.getApplicationContext();deadline=SystemClock.elapsedRealtime()+TimeUnit.MINUTES.toMillis(10);packets=0;bytes=0;firstSample=0;lastSample=0;rate=0;channels=0;evidence=new PcmEvidence();}
  AudioManager a=(AudioManager)owner.getSystemService(Context.AUDIO_SERVICE);
  try{MainActivity.record(owner,"CALL_PCM_CAPTURE_ARM",new JSONObject().put("consent",true).put("maxSeconds",CAPTURE_SECONDS).put("capturePoint","decrypted_lpcm_before_byte_order_conversion").put("liveToneProbe",true).put("toneFrequencyHz",523.25).put("toneDurationMs",250).put("toneAmplitudePcm16",8192).put("uplinkAudioRetained",false).put("rawAudioSaved",false).put("rawAudioUploaded",false)
   .put("preCallMode",a.getMode()).put("preCallMusicActive",a.isMusicActive()).put("preCallMusicVolume",a.getStreamVolume(AudioManager.STREAM_MUSIC)).put("preCallMusicMax",a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).put("preCallVoiceVolume",a.getStreamVolume(AudioManager.STREAM_VOICE_CALL)).put("preCallSpeaker",a.isSpeakerphoneOn()).put("preCallSco",a.isBluetoothScoOn()).put("preCallWiredHeadset",a.isWiredHeadsetOn()).put("routePolicy",AudioRouteProbe.snapshot(a)));}catch(Exception ignored){}
  owner.showOperation(CALL_TEST_INSTRUCTIONS);
 }

 /** Injects one short known signal into the same live call AudioTrack, only after explicit consent. */
 public static void injectAcousticProbe(Context context,AudioTrack track,int sampleRate,int channelCount,int stream){
  synchronized(LOCK){if(!armed||acousticProbeAttempted)return;acousticProbeAttempted=true;}
  AudioManager manager=null;JSONObject audioSystemBefore=null,audioSystemAfter=null;int modeBefore=-1,volumeBefore=-1,playbackHeadBefore=-1;boolean speakerBefore=false,scoBefore=false,a2dpBefore=false;
  try{manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);modeBefore=manager.getMode();volumeBefore=manager.getStreamVolume(stream);speakerBefore=manager.isSpeakerphoneOn();scoBefore=manager.isBluetoothScoOn();a2dpBefore=manager.isBluetoothA2dpOn();playbackHeadBefore=track.getPlaybackHeadPosition();audioSystemBefore=AudioSystemEvidence.routingSnapshot(manager,context);}catch(Throwable ignored){}
  int frames=Math.max(1,sampleRate/4),written=0;byte[] tone=new byte[frames*channelCount*2];
  for(int frame=0;frame<frames;frame++){
   short sample=(short)(Math.sin(2.0*Math.PI*523.25*frame/sampleRate)*8192.0);
   for(int channel=0;channel<channelCount;channel++){int at=(frame*channelCount+channel)*2;tone[at]=(byte)sample;tone[at+1]=(byte)(sample>>>8);}
  }
  String status="complete",error=null;long end=SystemClock.elapsedRealtime()+2000;
  try{while(written<tone.length&&SystemClock.elapsedRealtime()<end){int n=track.write(tone,written,tone.length-written);if(n<0)throw new IllegalStateException("write="+n);if(n==0){Thread.sleep(10);continue;}written+=n;}if(written!=tone.length)status="timeout";}
  catch(Throwable e){status="failed";error=e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage());}
  finally{java.util.Arrays.fill(tone,(byte)0);}
  int modeAfter=-1,volumeAfter=-1,playbackHeadAfter=-1;boolean speakerAfter=false,scoAfter=false,a2dpAfter=false;
  try{if(manager!=null){modeAfter=manager.getMode();volumeAfter=manager.getStreamVolume(stream);speakerAfter=manager.isSpeakerphoneOn();scoAfter=manager.isBluetoothScoOn();a2dpAfter=manager.isBluetoothA2dpOn();audioSystemAfter=AudioSystemEvidence.routingSnapshot(manager,context);}playbackHeadAfter=track.getPlaybackHeadPosition();}catch(Throwable ignored){}
  synchronized(LOCK){acousticProbeWritten="complete".equals(status);}
  try{MainActivity.record(context,"CALL_ACOUSTIC_PROBE",new JSONObject().put("frequencyHz",523.25).put("durationMs",250).put("sampleRate",sampleRate).put("channels",channelCount).put("stream",stream).put("writtenBytes",written).put("expectedBytes",frames*channelCount*2).put("status",status).put("modeBefore",modeBefore).put("modeAfter",modeAfter).put("streamVolumeBefore",volumeBefore).put("streamVolumeAfter",volumeAfter).put("speakerphoneBefore",speakerBefore).put("speakerphoneAfter",speakerAfter).put("bluetoothScoBefore",scoBefore).put("bluetoothScoAfter",scoAfter).put("bluetoothA2dpBefore",a2dpBefore).put("bluetoothA2dpAfter",a2dpAfter).put("playbackHeadBefore",playbackHeadBefore).put("playbackHeadAfter",playbackHeadAfter).put("audioSystemBeforeWrite",audioSystemBefore).put("audioSystemAfterWrite",audioSystemAfter).put("activeAudioTrack",true).put("sameTrackAsCallDownlink",true).put("oemSource13MayBeActive",true).put("evidenceScope","track accepted probe PCM and AudioSystem policy readbacks; user perception is still required to prove acoustic output").put("error",error));}catch(Exception ignored){}
 }

 /** Test-only live duplex A/B. Called only after the user arms this diagnostic. */
 public static void beginLiveComparison(AudioManager manager,Context context,int restoreMode){
  synchronized(LOCK){if(!liveArmed)return;if(liveStartedAt==0){liveStartedAt=SystemClock.elapsedRealtime();liveRestoreMode=restoreMode;liveRestorePending=true;}}
  updateLiveRoute(manager,context);
 }
 public static int liveMicrophoneSource(){
  synchronized(LOCK){if(!liveArmed||liveStartedAt==0)return -1;long elapsed=SystemClock.elapsedRealtime()-liveStartedAt;return elapsed>=TimeUnit.SECONDS.toMillis(24)&&elapsed<TimeUnit.SECONDS.toMillis(36)?android.media.MediaRecorder.AudioSource.MIC:android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION;}
 }
 public static int liveStage(){synchronized(LOCK){if(!liveArmed||liveStartedAt==0)return -1;return (int)Math.min(3,(SystemClock.elapsedRealtime()-liveStartedAt)/TimeUnit.SECONDS.toMillis(12));}}
 public static void updateLiveRoute(AudioManager manager,Context context){
  int stage; long elapsed;
  synchronized(LOCK){if(!liveArmed||liveStartedAt==0)return;elapsed=SystemClock.elapsedRealtime()-liveStartedAt;stage=(int)Math.min(3,elapsed/TimeUnit.SECONDS.toMillis(12));if(stage==liveStage)return;liveStage=stage;}
  int requested=stage==0?AudioManager.MODE_NORMAL:stage==1?AudioManager.MODE_IN_COMMUNICATION:stage==2?AudioManager.MODE_NORMAL:liveRestoreMode;
  String label=stage==0?"normal_mode + VOICE_COMMUNICATION":stage==1?"communication_mode + VOICE_COMMUNICATION":stage==2?"normal_mode + MIC":"restore_original_mode + VOICE_COMMUNICATION";
  String error=null;try{manager.setMode(requested);}catch(Exception e){error=e.getClass().getSimpleName();}
  try{JSONObject row=new JSONObject().put("stage",stage).put("elapsedMs",elapsed).put("profile",label).put("requestedMode",requested).put("actualMode",manager.getMode()).put("microphoneSource",stage==2?android.media.MediaRecorder.AudioSource.MIC:android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION).put("musicVolume",manager.getStreamVolume(AudioManager.STREAM_MUSIC)).put("voiceCallVolume",manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)).put("speakerphone",manager.isSpeakerphoneOn()).put("bluetoothSco",manager.isBluetoothScoOn()).put("bluetoothA2dp",manager.isBluetoothA2dpOn()).put("wiredHeadset",manager.isWiredHeadsetOn()).put("routePolicy",AudioRouteProbe.snapshot(manager));if(error!=null)row.put("modeError",error);MainActivity.record(context==null?liveContext:context,"CALL_LIVE_ROUTE_STAGE",row);}catch(Exception ignored){}
  if(stage==3)synchronized(LOCK){liveArmed=false;liveStartedAt=0;liveStage=-1;}
 }
 public static void endLiveComparison(AudioManager manager,int restoreMode){
  boolean restore;synchronized(LOCK){restore=liveRestorePending;liveRestorePending=false;liveArmed=false;liveStartedAt=0;liveStage=-1;}
  if(restore)try{int before=manager.getMode();if(before!=restoreMode)manager.setMode(restoreMode);JSONObject row=new JSONObject().put("beforeRestore",before).put("restoreMode",restoreMode).put("actualMode",manager.getMode());MainActivity.record(liveContext,"CALL_LIVE_ROUTE_RESTORE",row);}catch(Exception ignored){}
 }

 /** Called only for telephony LPCM, before the production byte-order conversion. */
 public static void captureDownlinkPcm(byte[] pcm,int sampleRate,int channelCount,int sample){
  if(pcm==null||sampleRate<8000||sampleRate>48000||(channelCount!=1&&channelCount!=2))return;
  synchronized(LOCK){if(!armed)return;if(SystemClock.elapsedRealtime()>deadline){RING.clear();armed=false;return;}if(rate==0){rate=sampleRate;channels=channelCount;firstSample=sample;}if(rate!=sampleRate||channels!=channelCount)return;RING.append(pcm,sampleRate,channelCount);evidence.observeByteOrderCandidates(pcm);packets++;bytes+=pcm.length;lastSample=sample;}
 }
 public static void finishPlaybackCapture(){synchronized(LOCK){armed=false;}}
 private static boolean hasCapture(){synchronized(LOCK){return RING.hasUsefulAudio();}}

 private static final class Capture {final byte[] pcm;final int rate,channels;final long packets,bytes;final int firstSample,lastSample;final String byteStats;
  Capture(byte[] p,int r,int c,long n,long b,int first,int last,String stats){pcm=p;rate=r;channels=c;packets=n;bytes=b;firstSample=first;lastSample=last;byteStats=stats;}}
 private static Capture takeCapture(){synchronized(LOCK){CallPcmRingBuffer.Snapshot s=RING.takeUsefulAudio();if(s==null)return null;armed=false;attempted=false;Capture c=new Capture(s.pcm,s.sampleRate,s.channels,packets,bytes,firstSample,lastSample,evidence.byteOrderCandidateSummary());packets=0;bytes=0;evidence=new PcmEvidence();return c;}}

 private static void showReplay(final MainActivity owner){
  new AlertDialog.Builder(owner).setTitle("\u901a\u8bdd\u97f3\u9891\u8bc1\u636e\u5df2\u91c7\u96c6")
   .setMessage("\u5df2\u91c7\u5230\u6700\u591a 3 \u79d2\u901a\u8bdd\u4e0b\u884c PCM\uff0c\u5e76\u5728\u901a\u8bdd\u5f00\u59cb\u65f6\u5199\u5165\u4e86 0.25 \u79d2\u7684\u6d4b\u8bd5\u97f3\u3002\u505c\u8f66\u540e\u5148\u56de\u7b54\u662f\u5426\u542c\u5230\u6d4b\u8bd5\u97f3\uff0c\u7136\u540e\u542c\u56db\u7ec4\u76f8\u540c PCM\uff1asource 13 \u5f00/\u5173\u4e0e\u539f\u59cb/\u4ea4\u6362\u5b57\u8282\u5e8f\u3002")
   .setNegativeButton("\u7a0d\u540e",null).setPositiveButton("\u5f00\u59cb 4 \u7ec4\u5bf9\u7167",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){owner.task("call_pcm_replay",new MainActivity.Work(){public String run()throws Exception{return replay(owner);}});}}).show();
 }

 private static String replay(final MainActivity owner)throws Exception{
  final Capture c=takeCapture();if(c==null)throw new IllegalStateException("No sufficient call PCM; repeat the consented capture and have the far end speak for at least 3 seconds.");
  JSONArray rows=new JSONArray();
  String[] ids={"music_normal_swapped_source13_on","music_normal_swapped_source13_off","music_normal_raw_source13_on","music_normal_raw_source13_off"};
  String[] labels={
   "\u5a92\u4f53\u6d41 3 / \u666e\u901a\u6a21\u5f0f / \u539f\u5382 source 13 \u5f00 / \u5b57\u8282\u4ea4\u6362",
   "\u5a92\u4f53\u6d41 3 / \u666e\u901a\u6a21\u5f0f / \u539f\u5382 source 13 \u5173 / \u5b57\u8282\u4ea4\u6362",
   "\u5a92\u4f53\u6d41 3 / \u666e\u901a\u6a21\u5f0f / \u539f\u5382 source 13 \u5f00 / \u4fdd\u7559\u7ebf\u8def\u5b57\u8282",
   "\u5a92\u4f53\u6d41 3 / \u666e\u901a\u6a21\u5f0f / \u539f\u5382 source 13 \u5173 / \u4fdd\u7559\u7ebf\u8def\u5b57\u8282"};
  int[] modes={AudioManager.MODE_NORMAL,AudioManager.MODE_NORMAL,AudioManager.MODE_NORMAL,AudioManager.MODE_NORMAL};
  boolean[] speakers={false,false,false,false};
  boolean[] source13={true,false,true,false};
  boolean[] swappedBytes={true,true,false,false};
  byte[] swapped=swap16(c.pcm);AudioManager manager=(AudioManager)owner.getSystemService(Context.AUDIO_SERVICE);
  String rawWireAttachment=encodeForReport(c);
  String androidPcmAttachment=encodeForReport(new Capture(swapped,c.rate,c.channels,c.packets,c.bytes,c.firstSample,c.lastSample,c.byteStats));
  int mode=manager.getMode();boolean speaker=manager.isSpeakerphoneOn();int[] controlled={AudioManager.STREAM_MUSIC,AudioManager.STREAM_VOICE_CALL,10};int[] volumes={manager.getStreamVolume(controlled[0]),manager.getStreamVolume(controlled[1]),manager.getStreamVolume(controlled[2])};
  BydMultimediaCallRoute diagnosticRoute=new BydMultimediaCallRoute(owner);boolean sourceRestoreConfirmed=false;String liveTone=askTonePerception(owner);
  try{
   diagnosticRoute.start();
   for(int i=0;i<ids.length;i++){
    boolean routeConfirmed=setDiagnosticPhoneMode(diagnosticRoute,source13[i]);
    byte[] pcm=swappedBytes[i]?swapped:c.pcm;
    JSONObject row=playPcm(owner.getApplicationContext(),manager,AudioManager.STREAM_MUSIC,modes[i],speakers[i],pcm,c.rate,c.channels);
    String perception=askPerception(owner,labels[i]);
    row.put("profile",ids[i]).put("label",labels[i]).put("userHeard",("speech".equals(perception)||"noise".equals(perception))?"yes":("silence".equals(perception)?"no":"skip"))
      .put("userPerception",perception).put("requestedOemPhoneSource13",source13[i]).put("oemPhoneSourceBinderSucceeded",routeConfirmed)
      .put("pcmByteOrder",swappedBytes[i]?"swapped_for_android_little_endian":"raw_wire_bytes").put("clientFocusRequested",false);
    rows.put(row);MainActivity.record(owner,"CALL_REAL_PCM_AB_PROFILE",row);
   }
  }finally{
   try{diagnosticRoute.callEnded();sourceRestoreConfirmed=setDiagnosticPhoneMode(diagnosticRoute,false);}catch(Exception ignored){}
   diagnosticRoute.close();java.util.Arrays.fill(swapped,(byte)0);restore(manager,mode,speaker,controlled,volumes);java.util.Arrays.fill(c.pcm,(byte)0);
  }
  String farEnd=askFarEndMic(owner);JSONObject report=new JSONObject().put("capturePackets",c.packets).put("captureBytes",c.bytes).put("sampleRate",c.rate).put("channels",c.channels).put("firstSample",c.firstSample).put("lastSample",c.lastSample).put("durationMs",(long)c.pcm.length*1000/(c.rate*c.channels*2)).put("pcmByteOrderEvidence",c.byteStats).put("profiles",rows).put("profileCount",rows.length()).put("sameCapturedPcmForAllProfiles",true).put("liveAcousticProbeAttempted",acousticProbeAttempted).put("liveAcousticProbeTrackWriteCompleted",acousticProbeWritten).put("liveAcousticProbeUserPerception",liveTone).put("farEndHeardTestPhrase",farEnd).put("oemPhoneSourceBinderRestoreSucceeded",sourceRestoreConfirmed).put("captureScope","explicit_consent_decrypted_call_downlink_only").put("rawAudioSaved",false).put("rawAudioAttachmentBase64",androidPcmAttachment).put("rawAudioAttachmentRate",16000).put("rawAudioAttachmentChannels",1).put("rawAudioAttachmentByteOrder","byte_swapped_for_android_pcm16_little_endian").put("rawWireAudioAttachmentBase64",rawWireAttachment).put("rawWireAudioAttachmentByteOrder","captured_wire_bytes_interpreted_as_pcm16_little_endian_for_comparison").put("audioAttachmentsContainSameCapturedTail",true).put("audioAttachmentDurationMs",2000).put("audioAttachmentRequiresManualSubmit",true).put("rawAudioUploadRequiresManualSubmit",true);
  MainActivity.record(owner,"CALL_REAL_PCM_AB_DIAG",report);
  return "\u901a\u8bdd PCM 4 \u7ec4\u8def\u7531\u5bf9\u7167\u5b8c\u6210\uff1b\u786e\u8ba4\u539f\u5382 source 13 \u5df2\u6062\u590d\u540e\uff0c\u8bf7\u624b\u52a8\u63d0\u4ea4\u65e5\u5fd7\u3002";
 }

 private static boolean setDiagnosticPhoneMode(BydMultimediaCallRoute route,boolean enabled)throws InterruptedException{
  if(route.phoneModeMayBeActive()==enabled)return true;
  if(enabled)route.callStarted();else route.callEnded();
  long until=SystemClock.elapsedRealtime()+TimeUnit.MILLISECONDS.toMillis(2500);
  while(SystemClock.elapsedRealtime()<until){if(route.phoneModeMayBeActive()==enabled)return true;Thread.sleep(50);}
  return route.phoneModeMayBeActive()==enabled;
 }

 private static JSONObject playPcm(Context context,AudioManager m,int stream,int requestedMode,boolean requestedSpeaker,byte[] pcm,int rate,int channels){
  int priorMode=m.getMode(),priorVolume=m.getStreamVolume(stream),max=m.getStreamMaxVolume(stream);boolean priorSpeaker=m.isSpeakerphoneOn();AudioTrack track=null;int written=0,head=0,activeMode=-1,trackState=-1,playState=-1,actualVolume=-1,sessionId=-1,nativeRate=-1;boolean activeSpeaker=false,activeSco=false,activeA2dp=false,activeHeadset=false,activeMicMuted=false;String status="failed",error=null;JSONObject routePolicy=null,audioSystem=null;
  try{if(max>0)m.setStreamVolume(stream,max,0);m.setMode(requestedMode);m.setSpeakerphoneOn(requestedSpeaker);Thread.sleep(350);int mask=channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;int min=AudioTrack.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IllegalStateException("unsupported_pcm_format");track=new AudioTrack(stream,rate,mask,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,pcm.length),AudioTrack.MODE_STREAM);if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("audio_track_not_initialized");if(track.setStereoVolume(1f,1f)!=AudioTrack.SUCCESS)throw new IllegalStateException("track_gain_rejected");track.play();long until=SystemClock.elapsedRealtime()+6000;while(written<pcm.length&&SystemClock.elapsedRealtime()<until){int n=track.write(pcm,written,pcm.length-written);if(n<0)throw new IllegalStateException("write="+n);if(n==0){Thread.sleep(10);continue;}written+=n;}long expectedMs=(long)(pcm.length/(2*channels))*1000/rate;long end=SystemClock.elapsedRealtime()+Math.min(10000,expectedMs+2000);while(SystemClock.elapsedRealtime()<end){head=track.getPlaybackHeadPosition()&0xffffffff;if(head>=pcm.length/(2*channels)-32)break;Thread.sleep(10);}status=written==pcm.length&&head>=pcm.length/(2*channels)-32?"audio_track_consumed":"incomplete";}
  catch(Throwable e){error=e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage());}
  finally{try{activeMode=m.getMode();actualVolume=m.getStreamVolume(stream);activeSpeaker=m.isSpeakerphoneOn();activeSco=m.isBluetoothScoOn();activeA2dp=m.isBluetoothA2dpOn();activeHeadset=m.isWiredHeadsetOn();activeMicMuted=m.isMicrophoneMute();routePolicy=AudioRouteProbe.snapshot(m);audioSystem=AudioSystemEvidence.routingSnapshot(m,context);if(track!=null){trackState=track.getState();playState=track.getPlayState();sessionId=track.getAudioSessionId();nativeRate=AudioTrack.getNativeOutputSampleRate(stream);}}catch(Exception ignored){}if(track!=null){try{track.pause();track.flush();}catch(Exception ignored){}try{track.release();}catch(Exception ignored){}}restore(m,priorMode,priorSpeaker,new int[]{stream},new int[]{priorVolume});}
  try{return new JSONObject().put("stream",stream).put("requestedMode",requestedMode).put("requestedSpeakerphone",requestedSpeaker).put("modeReadbackMatchesRequested",activeMode==requestedMode).put("speakerReadbackMatchesRequested",activeSpeaker==requestedSpeaker).put("activeModeBeforeRestore",activeMode).put("sampleRate",rate).put("channels",channels).put("writtenBytes",written).put("expectedFrames",pcm.length/(2*channels)).put("playbackHead",head).put("clientFocusRequested",false).put("maxVolume",max).put("volumeMaxAccepted",max==actualVolume).put("actualStreamVolume",actualVolume).put("speakerphone",activeSpeaker).put("bluetoothSco",activeSco).put("bluetoothA2dp",activeA2dp).put("wiredHeadset",activeHeadset).put("microphoneMuted",activeMicMuted).put("trackStateBeforeRelease",trackState).put("playStateBeforeRelease",playState).put("audioSessionId",sessionId).put("nativeOutputRate",nativeRate).put("routePolicyBeforeRestore",routePolicy).put("audioSystemBeforeRestore",audioSystem).put("status",status).put("evidenceScope","AudioTrack queue consumption and AudioSystem policy readback only; not proof of acoustic output").put("error",error);}catch(Exception impossible){throw new IllegalStateException(impossible);}
 }
 private static String encodeForReport(Capture c){int frames=c.pcm.length/(2*c.channels),outFrames=Math.min(32000,(int)((long)frames*16000/c.rate)),first=Math.max(0,frames-(int)((long)outFrames*c.rate/16000));byte[] mono=new byte[outFrames*2];for(int i=0;i<outFrames;i++){int start=first+(int)((long)i*c.rate/16000),end=Math.min(frames,Math.max(start+1,first+(int)((long)(i+1)*c.rate/16000)));long sum=0;int count=0;for(int f=start;f<end;f++)for(int ch=0;ch<c.channels;ch++){int at=(f*c.channels+ch)*2;sum+=(short)((c.pcm[at]&255)|(c.pcm[at+1]<<8));count++;}short s=(short)(count==0?0:sum/count);mono[i*2]=(byte)s;mono[i*2+1]=(byte)(s>>>8);}String data=android.util.Base64.encodeToString(mono,android.util.Base64.NO_WRAP);java.util.Arrays.fill(mono,(byte)0);return data;}
 private static byte[] swap16(byte[] in){byte[] out=in.clone();for(int i=0;i+1<out.length;i+=2){byte b=out[i];out[i]=out[i+1];out[i+1]=b;}return out;}
 private static void restore(AudioManager m,int mode,boolean speaker,int[] streams,int[] volumes){try{if(m.getMode()!=mode)m.setMode(mode);}catch(Exception ignored){}try{if(m.isSpeakerphoneOn()!=speaker)m.setSpeakerphoneOn(speaker);}catch(Exception ignored){}for(int i=0;i<streams.length;i++)try{m.setStreamVolume(streams[i],volumes[i],0);}catch(Exception ignored){}}


 private static String askTonePerception(final MainActivity owner)throws Exception{final CountDownLatch done=new CountDownLatch(1);final String[] result={"skip"};owner.runOnUiThread(new Runnable(){public void run(){if(owner.closed){done.countDown();return;}new AlertDialog.Builder(owner).setTitle("\u901a\u8bdd\u5f00\u59cb\u65f6\u7684\u77ed\u63d0\u793a\u97f3").setMessage("\u6d4b\u8bd5\u7535\u8bdd\u5f00\u59cb\u65f6\uff0c\u672c\u7a0b\u5e8f\u901a\u8fc7\u4e0e\u6765\u7535\u97f3\u9891\u76f8\u540c\u7684 AudioTrack \u64ad\u653e\u4e86 0.25 \u79d2\u97f3\u8c03\u3002\u8bf7\u56de\u7b54\u5f53\u65f6\u662f\u5426\u4ece\u8f66\u673a\u626c\u58f0\u5668\u542c\u5230\u3002").setPositiveButton("\u542c\u5230\u63d0\u793a\u97f3",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="heard";done.countDown();}}).setNegativeButton("\u6ca1\u6709\u58f0\u97f3",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="silent";done.countDown();}}).setNeutralButton("\u4e0d\u786e\u5b9a",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="unsure";done.countDown();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){result[0]="skip";done.countDown();}}).show();}});if(!done.await(60,TimeUnit.SECONDS))result[0]="timeout";return result[0];}
 private static String askPerception(final MainActivity owner,final String label)throws Exception{final CountDownLatch done=new CountDownLatch(1);final String[] result={"timeout"};owner.runOnUiThread(new Runnable(){public void run(){if(owner.closed){done.countDown();return;}new AlertDialog.Builder(owner).setTitle("真实通话回放").setMessage(label+"\n刚才听到的是哪种情况？").setPositiveButton("听到清晰语音",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="speech";done.countDown();}}).setNegativeButton("只有杂音",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="noise";done.countDown();}}).setNeutralButton("没有声音",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="silence";done.countDown();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){result[0]="skip";done.countDown();}}).show();}});if(!done.await(60,TimeUnit.SECONDS))result[0]="timeout";return result[0];}
 private static String askFarEndMic(final MainActivity owner)throws Exception{final CountDownLatch done=new CountDownLatch(1);final String[] result={"skip"};owner.runOnUiThread(new Runnable(){public void run(){if(owner.closed){done.countDown();return;}new AlertDialog.Builder(owner).setTitle("电话麦克风确认").setMessage("刚才通话时你说了指定测试短句。对方是否听到了？").setPositiveButton("对方听到了",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="yes";done.countDown();}}).setNegativeButton("对方没听到",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="no";done.countDown();}}).setNeutralButton("未测试",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="skip";done.countDown();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){result[0]="skip";done.countDown();}}).show();}});if(!done.await(60,TimeUnit.SECONDS))result[0]="skip";return result[0];}
}
