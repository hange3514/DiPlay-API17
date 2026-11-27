package local.headunit.diplay;
import android.content.*;import android.media.*;import android.os.*;import java.io.Closeable;import java.util.*;import org.json.*;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Firmware-audited navigation stream. Does not impersonate a phone hang-up or change car sources. */
public final class AudioRouting implements Closeable {
 private static final AtomicBoolean CALL_OUTPUT_DIAGNOSTIC_PENDING=new AtomicBoolean();
 private static final Executor CALL_OUTPUT_DIAGNOSTICS=new ThreadPoolExecutor(
     1,1,0L,TimeUnit.MILLISECONDS,new SynchronousQueue<Runnable>(),new ThreadFactory(){
      @Override public Thread newThread(Runnable work){
       Thread worker=new Thread(work,"DiPlay-call-output-diagnostics");
       worker.setDaemon(true);
       return worker;
      }
     });
 private final Context context;private final LegacyMedia media;private final int secondMusic;private final BydCallUsbAssert callUsbAssert;
 private final LegacyAudioPolicy.CallRoute callRoute;
 private final NavigationAudioRoute navigationRoute;
 private final Map<String,PlaybackFocus> focus=new HashMap<String,PlaybackFocus>();private boolean closed;private int callAudioUsers,previousCallMode=AudioManager.MODE_NORMAL,previousCallVolume,callMaxVolume;private boolean previousCallSpeaker,ownsCallVolume,ownsCallMode,ownsCallSpeaker;
 public AudioRouting(Context c, LegacyMedia m) {
  context = c.getApplicationContext();
  media = m;
  secondMusic = secondMusic(c);
  callRoute = LegacyAudioPolicy.resolveCallRoute(c, secondMusic);
  navigationRoute = NavigationAudioRoute.detect(c, secondMusic);
  callUsbAssert = secondMusic == 10 ? new BydCallUsbAssert(c) : null;
  if (callUsbAssert != null) callUsbAssert.start();
  for (final String role : new String[]{"media", "navigation", "assistant", "call"}) {
   if (role.equals("assistant")) continue;
   int gain = role.equals("call") ? CallAudioFocusPolicy.gain(!callRoute.getPlatformPhoneStrategy())
       : role.equals("media") ? AudioManager.AUDIOFOCUS_GAIN : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
   int focusStream = role.equals("call") ? callRoute.getOutputStream() : AudioManager.STREAM_MUSIC;
   focus.put(role, new PlaybackFocus(c, new PlaybackFocus.Volume() {
    public void set(float value) {
     media.setRoleVolume(role, value);
     if (role.equals("media")) media.setRoleVolume("assistant", value);
    }
   }, gain, role, focusStream));
  }
  try {
   JSONObject row = new JSONObject().put("navigationStream", stream("navigation"))
       .put("nativeNavigation", navigationRoute.stream() != AudioManager.STREAM_MUSIC)
       .put("navigationRoute", navigationRoute.evidence())
       .put("assistantStream", stream("assistant")).put("callStream", stream("call"));
   MainActivity.record(context, "AUDIO_ROUTING", describeCallRoute(row, callRoute));
  } catch (Exception ignored) { }
 }

 private static JSONObject describeCallRoute(JSONObject row, LegacyAudioPolicy.CallRoute route) throws JSONException {
  boolean platform = route.getPlatformPhoneStrategy();
  String downlink = platform ? "airplay_telephony_on_android_phone_stream"
      : route == LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA ? "airplay_telephony_pcm_on_sah_mx9_android_media"
      : "airplay_telephony_pcm_on_android_media_preserve_current_mcu_source";
  return row.put("strategy", route.getDiagnosticProfile())
      .put("callFocusStream", route.getOutputStream())
      .put("callDownlinkRoute", downlink)
      .put("oemPhoneStateControl", platform ? "not_applicable" : "not_sent_preserve_current_source")
      .put("callFocusPolicy", platform ? "client_full_gain_phone_stream" : "client_transient_media_focus")
      .put("callAndroidRoutePolicy", platform ? "request_in_call_mode_and_speakerphone"
          : "preserve_mode_speaker_and_mcu_source");
 }
 public Context captureContext(){return context;}
 public int stream(String role) {
  if ("navigation".equals(role)) return navigationRoute.stream();
  return "call".equals(role) ? callRoute.getOutputStream() : LegacyAudioPolicy.stream(role, secondMusic);
 }
 boolean fallbackNavigationStream(int failedStream, String phase, Throwable error) {
  return navigationRoute.fallback(failedStream, phase, error);
 }
 void navigationTrackReady(int stream) {
  navigationRoute.trackReady(stream);
 }
 private boolean shouldMaximizeCallVolume() {
  return callRoute.getPlatformPhoneStrategy() && callRoute.getMaximizeVolume();
 }
 public synchronized int volumeControlStream(){return !closed&&callAudioUsers>0?stream("call"):AudioManager.STREAM_MUSIC;}
 public synchronized void beginCallAudio(){if(closed)return;if(callAudioUsers++>0)return;AudioManager a=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int callStream=stream("call");boolean platformPhoneStrategy=callRoute.getPlatformPhoneStrategy();try{previousCallMode=a.getMode();previousCallSpeaker=a.isSpeakerphoneOn();previousCallVolume=a.getStreamVolume(callStream);callMaxVolume=a.getStreamMaxVolume(callStream);ownsCallVolume=false;ownsCallMode=false;ownsCallSpeaker=false;}catch(Exception e){callAudioUsers=0;callRouteLog("call_state_read_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());return;}
  // The BYD media routes share STREAM_MUSIC with playback and preserve the driver's selected volume.
  try{if(shouldMaximizeCallVolume()&&callMaxVolume>0){a.setStreamVolume(callStream,callMaxVolume,0);ownsCallVolume=a.getStreamVolume(callStream)==callMaxVolume;}}catch(Exception e){callRouteLog("call_max_volume_request_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}
  if(platformPhoneStrategy){try{a.setMode(LegacyAudioPolicy.callMode());ownsCallMode=a.getMode()==LegacyAudioPolicy.callMode();}catch(Exception e){callRouteLog("in_call_mode_request_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}try{a.setSpeakerphoneOn(LegacyAudioPolicy.callSpeakerphone());ownsCallSpeaker=a.isSpeakerphoneOn()==LegacyAudioPolicy.callSpeakerphone();}catch(Exception e){callRouteLog("phone_speaker_route_request_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}callRouteLog("android_phone_strategy_requested",previousCallMode,a.getMode(),null);}else callRouteLog("android_media_route_preserving_current_mcu_source",previousCallMode,a.getMode(),null);
  if(callRoute==LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA)OemAudioObserver.beginSahCallRouteCapture(false);
  if(callUsbAssert!=null)callUsbAssert.callStarted();}
 public synchronized void endCallAudio(){if(callAudioUsers<=0)return;if(--callAudioUsers>0)return;if(callRoute==LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA)OemAudioObserver.beginSahCallRouteCapture(true);AudioManager a=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);try{int callStream=stream("call");if(ownsCallMode&&a.getMode()==LegacyAudioPolicy.callMode())a.setMode(previousCallMode);if(ownsCallSpeaker&&a.isSpeakerphoneOn()==LegacyAudioPolicy.callSpeakerphone())a.setSpeakerphoneOn(previousCallSpeaker);if(ownsCallVolume&&a.getStreamVolume(callStream)==callMaxVolume)a.setStreamVolume(callStream,previousCallVolume,0);callRouteLog("call_audio_route_restored",previousCallMode,a.getMode(),null);}catch(Exception e){callRouteLog("call_route_restore_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}finally{ownsCallVolume=false;ownsCallMode=false;ownsCallSpeaker=false;previousCallMode=AudioManager.MODE_NORMAL;previousCallVolume=0;callMaxVolume=0;previousCallSpeaker=false;if(callUsbAssert!=null)callUsbAssert.callEnded();}}
 private void callRouteLog(String detail, int before, int after, String error) {
  try {
   AudioManager a = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
   int callStream = stream("call");
   boolean platform = callRoute.getPlatformPhoneStrategy();
   boolean activeSpeaker = a.isSpeakerphoneOn();
   JSONObject row = new JSONObject().put("detail", detail).put("previousMode", before)
       .put("activeMode", after).put("strategy", callRoute.getDiagnosticProfile())
       .put("platformPhoneStrategy", platform).put("modePreservedForOemRoute", !platform && before == after)
       .put("speakerphoneForced", platform).put("activeSpeakerphone", activeSpeaker)
       .put("speakerphonePreservedForOemRoute", !platform && activeSpeaker == previousCallSpeaker)
       .put("previousSpeakerphone", previousCallSpeaker).put("outputStream", callStream)
       .put("focusStream", callRoute.getOutputStream()).put("clientFocusRequested", LegacyAudioPolicy.usesClientCallFocus(secondMusic))
       .put("previousVolume", previousCallVolume).put("activeVolume", a.getStreamVolume(callStream))
       .put("maxVolume", a.getStreamMaxVolume(callStream))
       .put("volumePolicy", shouldMaximizeCallVolume() ? "maximize_dedicated_call" : "preserve_shared_media")
       .put("volumeMaxAccepted", ownsCallVolume).put("oemBtPhoneSourceRequested", false)
       .put("audioSystem", AudioSystemEvidence.routingSnapshot(a, context));
   if (error != null) row.put("error", error);
   MainActivity.record(context, "CALL_AUDIO_ROUTE", row);
  } catch (Exception ignored) { }
 }
 public static void logCallOutput(Context context,AudioTrack track,final int stream,final String phase,
                                  final long written,final long head,final float gain,final String pcmSummary){
  if(!CALL_OUTPUT_DIAGNOSTIC_PENDING.compareAndSet(false,true))return;
  boolean submitted=false;
  try{
   Context applicationContext=context.getApplicationContext();
   final Context app=applicationContext==null?context:applicationContext;
   final long capturedAt=SystemClock.elapsedRealtime();
   final boolean trackPresent=track!=null;
   // Read Java-side state while the render worker owns the track. The diagnostic
   // thread receives only values and can safely finish after the track closes.
   final int trackState=trackPresent?track.getState():0;
   final int playState=trackPresent?track.getPlayState():0;
   final int sessionId=trackPresent?track.getAudioSessionId():0;
   CALL_OUTPUT_DIAGNOSTICS.execute(new Runnable(){
    @Override public void run(){
     try{
      AudioManager audio=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
      int volume=audio.getStreamVolume(stream);
      int maxVolume=audio.getStreamMaxVolume(stream);
      JSONObject routePolicy=AudioRouteProbe.snapshot(audio);
      JSONObject audioSystem=AudioSystemEvidence.routingSnapshot(audio,app);
      long routeSnapshotAgeMs=SystemClock.elapsedRealtime()-capturedAt;
      StringBuilder summary=new StringBuilder(pcmSummary)
          .append(" streamVolume=").append(volume).append(" streamMax=").append(maxVolume)
          .append(" routeSnapshotAgeMs=").append(routeSnapshotAgeMs);
      JSONArray outputDevices=audioSystem.optJSONArray("streamOutputDevices");
      if(outputDevices!=null)for(int i=0;i<outputDevices.length();i++){
       JSONObject device=outputDevices.optJSONObject(i);
       if(device==null||device.optInt("stream",-1)!=stream)continue;
       if(device.has("deviceMask"))summary.append(" outputDeviceMask=0x")
           .append(Integer.toHexString(device.getInt("deviceMask")));
       break;
      }
      JSONObject row=new JSONObject()
          .put("phase",phase).put("mode",audio.getMode()).put("stream",stream)
          .put("streamVolume",volume).put("streamMax",maxVolume)
          .put("speakerphone",audio.isSpeakerphoneOn()).put("bluetoothSco",audio.isBluetoothScoOn())
          .put("bluetoothA2dp",audio.isBluetoothA2dpOn()).put("wiredHeadset",audio.isWiredHeadsetOn())
          .put("microphoneMuted",audio.isMicrophoneMute()).put("musicActive",audio.isMusicActive())
          .put("routePolicy",routePolicy).put("audioSystem",audioSystem)
          .put("trackPresent",trackPresent).put("writtenBytes",written).put("playbackHeadFrames",head)
          .put("trackCapturedElapsedMs",capturedAt).put("routeSnapshotAgeMs",routeSnapshotAgeMs)
          .put("diagnosticSummary",summary.toString())
          .put("evidenceScope","AudioTrack queue consumption only; no amplifier loopback");
      if(!Float.isNaN(gain)&&!Float.isInfinite(gain))row.put("trackGain",gain);
      if(trackPresent)row.put("trackState",trackState).put("playState",playState).put("sessionId",sessionId)
          .put("nativeRate",AudioTrack.getNativeOutputSampleRate(stream));
      MainActivity.record(app,"CALL_OUTPUT_DIAG",row);
     }catch(Exception ignored){}finally{CALL_OUTPUT_DIAGNOSTIC_PENDING.set(false);}
    }
   });
   submitted=true;
  }catch(Exception ignored){}finally{if(!submitted)CALL_OUTPUT_DIAGNOSTIC_PENDING.set(false);}
 }
 public float packet(String role){PlaybackFocus f; synchronized(this){if(closed)return 0;if("navigation".equals(role)&&navigationRoute.usesAuditedStream())return 1;f=focus.get(role.equals("assistant")?"media":role);}return f==null?1:f.packet();}
 public void playbackEnded(String role){PlaybackFocus f,mediaFocus;synchronized(this){if(closed||"navigation".equals(role)&&navigationRoute.usesAuditedStream())return;f=focus.get(role.equals("assistant")?"media":role);mediaFocus=role.equals("call")?focus.get("media"):null;}if(f!=null)f.playbackEnded();if(mediaFocus!=null)mediaFocus.foreground();}
 public synchronized void foreground(){if(!closed)for(PlaybackFocus f:focus.values())f.foreground();}
 public synchronized void close(){if(closed)return;closed=true;if(callRoute==LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA)OemAudioObserver.endCallRouteCapture();if(callUsbAssert!=null)callUsbAssert.close();for(PlaybackFocus f:focus.values())f.close();}
 public static int secondMusic(Context c){try{if(Build.VERSION.SDK_INT!=17||!"MX9A0".equals(Build.MODEL)||c.getPackageManager().getPackageInfo("com.byd.multimediaservice",0).versionCode!=382)return -1;int value=AudioManager.class.getField("STREAM_MUSIC_SECOND").getInt(null);return value==10?value:-1;}catch(Exception e){return -1;}}
 public static JSONObject evidence(Context c) throws Exception {
  AudioManager a = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
  int second = secondMusic(c);
  LegacyAudioPolicy.CallRoute route = LegacyAudioPolicy.resolveCallRoute(c, second);
  JSONArray streams = new JSONArray();
  for (java.lang.reflect.Field field : AudioManager.class.getFields()) {
   if (!field.getName().startsWith("STREAM_") || field.getType() != Integer.TYPE) continue;
   JSONObject item = new JSONObject().put("name", field.getName());
   try {
    int stream = field.getInt(null);
    item.put("id", stream).put("volume", a.getStreamVolume(stream)).put("max", a.getStreamMaxVolume(stream));
   } catch (Exception e) {
    item.put("error", e.getClass().getSimpleName());
   }
   streams.put(item);
  }
  return describeCallRoute(new JSONObject().put("streams", streams).put("auditedNavigationStream", second).put("navigationCapability",NavigationAudioRoute.inspect(c,second))
      .put("callDownlinkStream", route.getOutputStream()).put("clientCallFocus", LegacyAudioPolicy.usesClientCallFocus(second))
      .put("legacyRouting", AudioRouteProbe.snapshot(a)).put("audioSystem", AudioSystemEvidence.routingSnapshot(a, c)),
      route);
 }
}
