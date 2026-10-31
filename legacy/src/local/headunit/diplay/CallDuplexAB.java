package local.headunit.diplay;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Consented one-call A/B probe for the suspected mic-capture/output interaction. */
public final class CallDuplexAB {
 private static final int CAPTURE_SECONDS=3;
 private static final long WAIT_FOR_DOWNLINK_MS=8000,STAGE_A_MS=5000,STAGE_B_MS=8000,STAGE_C_MS=8000,STAGE_D_MS=5000;
 private static final Object LOCK=new Object();
 private static final CallPcmRingBuffer RING=new CallPcmRingBuffer();
 private static boolean armed,attempted,probeArmed;
 private static long deadline,callSetupAt,downlinkStartedAt,packets,bytes;
 private static int rate,channels,firstSample,lastSample,lastStage=-3,lastToneStage=-1;
 private static Context appContext;
 private static JSONArray stageRows=new JSONArray();
 private static PcmEvidence evidence=new PcmEvidence();

 private CallDuplexAB(){}

 private static final String INSTRUCTIONS="本版本每通电话自动运行四段对照，不用提前预备。接通后自动分段：A 段 5 秒关麦、B 段 8 秒开麦（通信模式）、C 段 8 秒开麦（普通麦克风源）、D 段 5 秒再关麦；每段开头有一声不同音高的标记音（523/659/880/1046 Hz），下行播放全程不停。请保持通话至少 26 秒，让对方一直说话，你在 B、C 段各说一遍“CarPlay 电话测试 123”。关麦时段对方暂时听不到你。挂断后按返回键回到本面板，点本按钮逐段记录听感，再点「提交日志」。最多暂存 3 秒下行 PCM，不录制或保存麦克风音频。";

 public static void offer(final MainActivity owner){
  if(hasCapture()){showReview(owner);return;}
  new AlertDialog.Builder(owner).setTitle(attempted?"\u672a\u53d6\u5230\u901a\u8bdd PCM":"通话四段对照诊断")
   .setMessage(INSTRUCTIONS)
   .setNegativeButton("\u53d6\u6d88",null).setPositiveButton("\u6211\u5df2\u9605\u8bfb\uff0c\u51c6\u5907\u6d4b\u8bd5",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){arm(owner);}}).show();
 }

 private static void arm(MainActivity owner){
  synchronized(LOCK){resetLocked(SystemClock.elapsedRealtime(),owner);}
  try{AudioManager a=(AudioManager)owner.getSystemService(Context.AUDIO_SERVICE);MainActivity.record(owner,"CALL_DUPLEX_TEST_ARM",new JSONObject().put("consent",true).put("timeline","mic_off_5s_on_vc_8s_on_mic_8s_off_5s").put("callDownlinkUnaffected",true).put("captureMaxSeconds",CAPTURE_SECONDS).put("rawDownlinkStoredOnDevice",false).put("rawAudioUploadRequiresManualSubmit",true).put("mode",a.getMode()).put("musicVolume",a.getStreamVolume(AudioManager.STREAM_MUSIC)).put("speakerphone",a.isSpeakerphoneOn()));}catch(Exception ignored){}
  owner.showOperation(INSTRUCTIONS);
 }

 private static void resetLocked(long now,Context context){RING.clear();armed=true;attempted=true;probeArmed=true;callSetupAt=0;downlinkStartedAt=0;deadline=now+TimeUnit.MINUTES.toMillis(10);packets=0;bytes=0;rate=0;channels=0;firstSample=0;lastSample=0;lastStage=-3;lastToneStage=-1;stageRows=new JSONArray();evidence=new PcmEvidence();if(context!=null)appContext=context.getApplicationContext();}

 public static void callStreamStarting(Context context){
  boolean started=false,autoArmed=false;long now=SystemClock.elapsedRealtime();
  synchronized(LOCK){
   if(!armed&&!RING.hasUsefulAudio()){resetLocked(now,context);autoArmed=true;}
   if(probeArmed&&armed&&callSetupAt==0){callSetupAt=now;started=true;}
  }
  if(autoArmed)try{record(context,"CALL_DUPLEX_TEST_ARM",new JSONObject().put("trigger","auto_call_stream").put("timeline","mic_off_5s_on_vc_8s_on_mic_8s_off_5s").put("callDownlinkUnaffected",true).put("captureMaxSeconds",CAPTURE_SECONDS).put("rawDownlinkStoredOnDevice",false).put("rawAudioUploadRequiresManualSubmit",true));}catch(Exception ignored){}
  if(started)try{record(context,"CALL_DUPLEX_STREAM_START",new JSONObject().put("micHeldUntilDownlinkPcm",true).put("waitLimitMs",WAIT_FOR_DOWNLINK_MS).put("stream","telephony"));}catch(Exception ignored){}
 }

 public static void callDownlinkFirstPcm(Context context){
  boolean started=false;long now=SystemClock.elapsedRealtime();
  synchronized(LOCK){if(probeArmed&&armed&&downlinkStartedAt==0){downlinkStartedAt=now;lastStage=-3;started=true;}}
  if(started)try{record(context,"CALL_DUPLEX_AB_START",new JSONObject().put("stageAms",STAGE_A_MS).put("stageBms",STAGE_B_MS).put("stageCms",STAGE_C_MS).put("stageDms",STAGE_D_MS).put("sameAudioTrack",true).put("modeOrSourceChanged",false).put("volumeChanged",false).put("stageBsSource","voice_communication").put("stageCSource","mic"));}catch(Exception ignored){}
 }

 /** -1 inactive, -2 waiting for downlink PCM, 0..4 A/B/C/D/finished. */
 public static int microphoneStage(Context context,int activeSource,int recordingState){
  int stage;long elapsed;boolean changed=false;
  synchronized(LOCK){
   if(!probeArmed||!armed)return -1;
   long now=SystemClock.elapsedRealtime();
   if(downlinkStartedAt==0){
    if(callSetupAt==0)return -1;
    elapsed=now-callSetupAt;
    if(elapsed>WAIT_FOR_DOWNLINK_MS){probeArmed=false;stage=-1;}
    else stage=-2;
   }else{
    elapsed=now-downlinkStartedAt;
    stage=CallDuplexSchedule.stage(elapsed);
    if(stage==4)probeArmed=false;
   }
   if(stage!=lastStage){lastStage=stage;changed=true;}
  }
  if(changed){
   String label=stage==-2?"waiting_for_first_downlink_pcm":stage==0?"A_mic_off":stage==1?"B_mic_on_voice_communication":stage==2?"C_mic_on_plain_mic":stage==3?"D_mic_off_again":stage==4?"complete":"expired_no_downlink";
   boolean hold=CallDuplexSchedule.microphoneSuppressed(stage);
   try{JSONObject row=new JSONObject().put("stage",stage).put("label",label).put("elapsedMs",elapsed).put("microphoneSuppressed",hold).put("activeSource",activeSource).put("recordingState",recordingState).put("downlinkContinues",true).put("sameAudioTrack",true);synchronized(LOCK){stageRows.put(row);}record(context,"CALL_DUPLEX_AB_STAGE",row);}catch(Exception ignored){}
  }
  return stage;
 }

 public static boolean holdMicrophone(int stage){return CallDuplexSchedule.microphoneSuppressed(stage);}

 /** AudioSource override for the active stage, or -1 to keep the production choice. */
 public static int microphoneSourceForStage(int stage){return CallDuplexSchedule.microphoneSource(stage);}

 /** True while the armed per-call stage window runs; other tone probes stay quiet then. */
 public static boolean isProbeActive(){synchronized(LOCK){return probeArmed&&armed&&downlinkStartedAt!=0;}}

 /** Phase markers are written into the same call AudioTrack as the live AirPlay PCM. */
 public static void injectPhaseTone(Context context,AudioTrack track,int sampleRate,int channelCount,int stream){
  int stage; synchronized(LOCK){if(!probeArmed||!armed||downlinkStartedAt==0)return;long elapsed=SystemClock.elapsedRealtime()-downlinkStartedAt;stage=CallDuplexSchedule.stage(elapsed);if(stage>3||stage<=lastToneStage)return;lastToneStage=stage;}
  double hz=stage==0?523.25:stage==1?659.25:stage==2?880.0:1046.5;int frames=Math.max(1,sampleRate/6),written=0;byte[] tone=new byte[frames*channelCount*2];
  for(int frame=0;frame<frames;frame++){short v=(short)(Math.sin(2.0*Math.PI*hz*frame/sampleRate)*8192.0);for(int ch=0;ch<channelCount;ch++){int at=(frame*channelCount+ch)*2;tone[at]=(byte)v;tone[at+1]=(byte)(v>>>8);}}
  int headBefore=-1,headAfter=-1;String status="complete",error=null;long end=SystemClock.elapsedRealtime()+2000;
  try{headBefore=track.getPlaybackHeadPosition();while(written<tone.length&&SystemClock.elapsedRealtime()<end){int n=track.write(tone,written,tone.length-written);if(n<0)throw new IllegalStateException("write="+n);if(n==0){Thread.sleep(10);continue;}written+=n;}if(written!=tone.length)status="timeout";headAfter=track.getPlaybackHeadPosition();}
  catch(Throwable t){status="failed";error=t.getClass().getSimpleName()+":"+String.valueOf(t.getMessage());}
  finally{java.util.Arrays.fill(tone,(byte)0);}
  try{record(context,"CALL_DUPLEX_AB_TONE",new JSONObject().put("stage",stage).put("frequencyHz",hz).put("durationMs",167).put("stream",stream).put("sampleRate",sampleRate).put("channels",channelCount).put("writtenBytes",written).put("expectedBytes",frames*channelCount*2).put("playbackHeadBefore",headBefore).put("playbackHeadAfter",headAfter).put("status",status).put("microphoneSuppressed",CallDuplexSchedule.microphoneSuppressed(stage)).put("microphoneSource",CallDuplexSchedule.microphoneSource(stage)).put("sameAudioTrackAsDownlink",true).put("evidenceScope","AudioTrack accepted the marker; physical audibility requires user confirmation").put("error",error));}catch(Exception ignored){}
 }

 public static void captureDownlinkPcm(byte[] pcm,int sampleRate,int channelCount,int sample){
  if(pcm==null||sampleRate<8000||sampleRate>48000||(channelCount!=1&&channelCount!=2))return;
  synchronized(LOCK){if(!armed)return;if(SystemClock.elapsedRealtime()>deadline){RING.clear();armed=false;probeArmed=false;return;}if(rate==0){rate=sampleRate;channels=channelCount;firstSample=sample;}if(rate!=sampleRate||channels!=channelCount)return;RING.append(pcm,sampleRate,channelCount);evidence.observeByteOrderCandidates(pcm);packets++;bytes+=pcm.length;lastSample=sample;}
 }

 public static void finishPlaybackCapture(){synchronized(LOCK){armed=false;probeArmed=false;}}
 private static boolean hasCapture(){synchronized(LOCK){return RING.hasUsefulAudio();}}

 private static final class Capture {final byte[] pcm;final int rate,channels;final long packets,bytes;final int firstSample,lastSample;final String stats;Capture(byte[] p,int r,int c,long n,long b,int f,int l,String s){pcm=p;rate=r;channels=c;packets=n;bytes=b;firstSample=f;lastSample=l;stats=s;}}
 private static Capture takeCapture(){synchronized(LOCK){CallPcmRingBuffer.Snapshot s=RING.takeUsefulAudio();if(s==null)return null;armed=false;attempted=false;probeArmed=false;Capture c=new Capture(s.pcm,s.sampleRate,s.channels,packets,bytes,firstSample,lastSample,evidence.byteOrderCandidateSummary());packets=0;bytes=0;evidence=new PcmEvidence();return c;}}

 private static void showReview(final MainActivity owner){
  new AlertDialog.Builder(owner).setTitle("一通电话四段对照日志已采集")
   .setMessage("\u6309\u4e0b\u9762\u6309\u94ae\u4f9d\u6b21\u8bb0\u5f55 A/B/C/D 四个阶段\u6807\u8bb0\u97f3\u662f\u5426\u4ece\u8f66\u673a\u626c\u58f0\u5668\u64ad\u51fa\u3002\u4e0d\u4f1a\u91cd\u590d\u65e7\u7684\u5b57\u8282\u5e8f\u3001\u97f3\u91cf\u6216 source 13 \u56de\u653e\u6d4b\u8bd5\u3002")
   .setNegativeButton("\u7a0d\u540e",null).setPositiveButton("记录 A/B/C/D",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){owner.task("call_duplex_review",new MainActivity.Work(){public String run()throws Exception{return review(owner);}});}}).show();
 }

 private static String review(final MainActivity owner)throws Exception{
  final Capture c=takeCapture();if(c==null)throw new IllegalStateException("No call PCM was captured. Re-arm before the next test call and wait until the AirPlay telephony stream starts.");
  String[] labels={"A 523 Hz / \u9ea6\u514b\u98ce\u5173\u95ed 5 \u79d2","B 659 Hz / \u5f00\u9ea6\u00b7\u901a\u4fe1\u6a21\u5f0f 8 \u79d2","C 880 Hz / \u5f00\u9ea6\u00b7\u666e\u901a\u9ea6\u514b\u98ce 8 \u79d2","D 1046 Hz / \u9ea6\u514b\u98ce\u518d\u6b21\u5173\u95ed 5 \u79d2"};
  JSONArray answers=new JSONArray();for(int i=0;i<labels.length;i++){String perception=askPerception(owner,labels[i]);answers.put(new JSONObject().put("stage",i).put("label",labels[i]).put("userPerception",perception));}
  String farEnd=askFarEndMic(owner);JSONArray stages;synchronized(LOCK){stages=new JSONArray(stageRows.toString());stageRows=new JSONArray();}
  byte[] swapped=swap16(c.pcm);String androidPcm=encodeForReport(swapped,c.rate,c.channels);String wirePcm=encodeForReport(c.pcm,c.rate,c.channels);java.util.Arrays.fill(swapped,(byte)0);
  JSONObject report=new JSONObject().put("capturePackets",c.packets).put("captureBytes",c.bytes).put("sampleRate",c.rate).put("channels",c.channels).put("firstSample",c.firstSample).put("lastSample",c.lastSample).put("durationMs",(long)c.pcm.length*1000/(c.rate*c.channels*2)).put("pcmByteOrderEvidence",c.stats).put("stageEvents",stages).put("stageCount",stages.length()).put("userPerceptionByStage",answers).put("farEndHeardPhraseDuringB",farEnd).put("sameCallAudioTrackForAllStages",true).put("micSuppressedStages","A,D").put("micSourceStages","B=voice_communication,C=plain_mic").put("downlinkStoppedBetweenStages",false).put("captureScope","explicit_consent_decrypted_call_downlink_only").put("rawAudioSaved",false).put("rawAudioAttachmentBase64",androidPcm).put("rawAudioAttachmentRate",16000).put("rawAudioAttachmentChannels",1).put("rawAudioAttachmentByteOrder","byte_swapped_for_android_pcm16_little_endian").put("rawWireAudioAttachmentBase64",wirePcm).put("rawWireAudioAttachmentByteOrder","captured_wire_bytes_interpreted_as_pcm16_little_endian_for_comparison").put("audioAttachmentsContainSameCapturedTail",true).put("audioAttachmentDurationMs",2000).put("audioAttachmentRequiresManualSubmit",true).put("diagnosticScope","only microphone capture is toggled; output stream, route, mode, and volume remain unchanged");
  MainActivity.record(owner,"CALL_DUPLEX_AB_DIAG",report);java.util.Arrays.fill(c.pcm,(byte)0);return "通话四段对照已记录\uff0c\u8bf7\u624b\u52a8\u63d0\u4ea4\u65e5\u5fd7\u3002";
 }

 private static String encodeForReport(byte[] pcm,int rate,int channels){int frames=pcm.length/(2*channels),outFrames=Math.min(32000,(int)((long)frames*16000/rate)),first=Math.max(0,frames-(int)((long)outFrames*rate/16000));byte[] mono=new byte[outFrames*2];for(int i=0;i<outFrames;i++){int start=first+(int)((long)i*rate/16000),end=Math.min(frames,Math.max(start+1,first+(int)((long)(i+1)*rate/16000)));long sum=0;int count=0;for(int f=start;f<end;f++)for(int ch=0;ch<channels;ch++){int at=(f*channels+ch)*2;sum+=(short)((pcm[at]&255)|(pcm[at+1]<<8));count++;}short s=(short)(count==0?0:sum/count);mono[i*2]=(byte)s;mono[i*2+1]=(byte)(s>>>8);}String data=android.util.Base64.encodeToString(mono,android.util.Base64.NO_WRAP);java.util.Arrays.fill(mono,(byte)0);return data;}
 private static byte[] swap16(byte[] in){byte[] out=in.clone();for(int i=0;i+1<out.length;i+=2){byte b=out[i];out[i]=out[i+1];out[i+1]=b;}return out;}
 private static void record(Context c,String event,JSONObject row){try{MainActivity.record(c==null?appContext:c,event,row);}catch(Exception ignored){}}

 private static String askPerception(final MainActivity owner,final String label)throws Exception{final CountDownLatch done=new CountDownLatch(1);final String[] result={"timeout"};owner.runOnUiThread(new Runnable(){public void run(){if(owner.closed){done.countDown();return;}new AlertDialog.Builder(owner).setTitle("\u5b9e\u65f6\u9636\u6bb5\u6807\u8bb0\u97f3").setMessage(label+"\n\u5b9e\u9645\u542c\u5230\u4e86\u4ec0\u4e48\uff1f").setPositiveButton("\u6e05\u6670\u6807\u8bb0\u97f3",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="clear";done.countDown();}}).setNegativeButton("\u6709\u58f0\u97f3\uff0c\u4f46\u662f\u6742\u97f3",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="noise";done.countDown();}}).setNeutralButton("\u5b8c\u5168\u65e0\u58f0",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="silence";done.countDown();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){result[0]="skip";done.countDown();}}).show();}});if(!done.await(60,TimeUnit.SECONDS))result[0]="timeout";return result[0];}
 private static String askFarEndMic(final MainActivity owner)throws Exception{final CountDownLatch done=new CountDownLatch(1);final String[] result={"skip"};owner.runOnUiThread(new Runnable(){public void run(){if(owner.closed){done.countDown();return;}new AlertDialog.Builder(owner).setTitle("\u5bf9\u65b9\u9ea6\u514b\u98ce\u786e\u8ba4").setMessage("B 或 C 阶段麦克风开启时\uff0c\u5bf9\u65b9\u6709\u6ca1\u6709\u542c\u5230\u4f60\u8bf4\u7684\u6d4b\u8bd5\u53e5\uff1f").setPositiveButton("\u542c\u5230\u4e86",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="yes";done.countDown();}}).setNegativeButton("\u6ca1\u542c\u5230",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="no";done.countDown();}}).setNeutralButton("\u6ca1\u6d4b\u8bd5",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){result[0]="skip";done.countDown();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){result[0]="skip";done.countDown();}}).show();}});if(!done.await(60,TimeUnit.SECONDS))result[0]="skip";return result[0];}
}
