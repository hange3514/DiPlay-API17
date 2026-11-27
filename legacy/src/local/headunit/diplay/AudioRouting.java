package local.headunit.diplay;
import android.content.*;import android.media.*;import android.os.*;import java.io.Closeable;import java.util.*;import org.json.*;

/** Selects playback routes while keeping vehicle source and call controls independent. */
public final class AudioRouting implements Closeable {
 private final Context context;private final LegacyMedia media;private final int secondMusic;private final BydCallUsbAssert callUsbAssert;
 private final LegacyAudioPolicy.CallRoute callRoute;
 private final NavigationAudioRoute navigationRoute;
 private final Map<String,PlaybackFocus> focus=new HashMap<String,PlaybackFocus>();private boolean closed;private int callAudioUsers,previousCallMode=AudioManager.MODE_NORMAL,previousCallVolume,callMaxVolume;private boolean previousCallSpeaker,ownsCallVolume,ownsCallMode,ownsCallSpeaker;
 public AudioRouting(Context c,LegacyMedia m){context=c.getApplicationContext();media=m;secondMusic=secondMusic(c);
  navigationRoute=NavigationAudioRoute.detect(context,secondMusic);
  callRoute=LegacyAudioPolicy.resolveCallRoute(context,secondMusic);
  callUsbAssert=secondMusic==10?new BydCallUsbAssert(c):null;if(callUsbAssert!=null)callUsbAssert.start();
  focus.put("media",newFocus("media"));
  focus.put("call",newFocus("call"));
  if(!navigationRoute.usesAuditedStream())focus.put("navigation",newFocus("navigation"));
  try{MainActivity.record(context,"AUDIO_ROUTING",new JSONObject().put("navigationStream",stream("navigation")).put("nativeNavigation",navigationRoute.usesAuditedStream()).put("navigationRoute",navigationRoute.evidence()).put("assistantStream",stream("assistant")).put("callStream",stream("call")).put("callFocusStream",callRoute.getOutputStream()).put("strategy",callRoute.getDiagnosticProfile()).put("callDownlinkRoute",!callRoute.getPlatformPhoneStrategy()?"airplay_telephony_pcm_on_android_media_preserve_current_mcu_source":"airplay_telephony_on_android_phone_stream").put("oemPhoneStateControl",secondMusic==10?"not_sent_preserve_current_source":"not_applicable").put("callFocusPolicy",!callRoute.getPlatformPhoneStrategy()?"client_transient_media_focus":"client_full_gain_phone_stream").put("callAndroidRoutePolicy",!callRoute.getPlatformPhoneStrategy()?"preserve_mode_speaker_and_mcu_source":"request_in_call_mode_and_speakerphone"));}catch(Exception ignored){}
 }
 private PlaybackFocus newFocus(final String role){
  int gain=role.equals("call")?CallAudioFocusPolicy.gain(!callRoute.getPlatformPhoneStrategy()):role.equals("media")?AudioManager.AUDIOFOCUS_GAIN:AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
  final int focusStream=role.equals("call")?callRoute.getOutputStream():AudioManager.STREAM_MUSIC;
  return new PlaybackFocus(context,new PlaybackFocus.Volume(){public void set(float value){media.setRoleVolume(role,value);if(role.equals("media"))media.setRoleVolume("assistant",value);}},gain,role,focusStream);
 }
 public Context captureContext(){return context;}
 public int stream(String role){if("navigation".equals(role))return navigationRoute.stream();return "call".equals(role)?callRoute.getOutputStream():LegacyAudioPolicy.stream(role,secondMusic);}
 synchronized boolean fallbackNavigationStream(int failedStream,String phase,Throwable error){
  if(closed||!navigationRoute.fallback(failedStream,phase,error))return false;
  if(!focus.containsKey("navigation"))focus.put("navigation",newFocus("navigation"));
  return true;
 }
 void navigationTrackReady(int stream){navigationRoute.trackReady(stream);}
 public synchronized void beginCallAudio(){if(closed)return;if(callAudioUsers++>0)return;AudioManager a=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int callStream=stream("call");boolean platformPhoneStrategy=callRoute.getPlatformPhoneStrategy();try{previousCallMode=a.getMode();previousCallSpeaker=a.isSpeakerphoneOn();previousCallVolume=a.getStreamVolume(callStream);callMaxVolume=a.getStreamMaxVolume(callStream);ownsCallVolume=false;ownsCallMode=false;ownsCallSpeaker=false;}catch(Exception e){callAudioUsers=0;callRouteLog("call_state_read_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());return;}
  try{if(callRoute.getMaximizeVolume()&&callMaxVolume>0){a.setStreamVolume(callStream,callMaxVolume,0);ownsCallVolume=a.getStreamVolume(callStream)==callMaxVolume;}}catch(Exception e){callRouteLog("call_max_volume_request_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}
  if(platformPhoneStrategy){try{a.setMode(LegacyAudioPolicy.callMode());ownsCallMode=a.getMode()==LegacyAudioPolicy.callMode();}catch(Exception e){callRouteLog("in_call_mode_request_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}try{a.setSpeakerphoneOn(LegacyAudioPolicy.callSpeakerphone());ownsCallSpeaker=a.isSpeakerphoneOn()==LegacyAudioPolicy.callSpeakerphone();}catch(Exception e){callRouteLog("phone_speaker_route_request_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}callRouteLog("android_phone_strategy_requested",previousCallMode,a.getMode(),null);}else callRouteLog("android_media_route_preserving_current_mcu_source",previousCallMode,a.getMode(),null);
  if(callRoute==LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA)OemAudioObserver.beginSahCallRouteCapture(false);
  if(callUsbAssert!=null)callUsbAssert.callStarted();}
 public synchronized void endCallAudio(){if(callAudioUsers<=0)return;if(--callAudioUsers>0)return;if(callRoute==LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA)OemAudioObserver.beginSahCallRouteCapture(true);AudioManager a=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);try{int callStream=stream("call");if(ownsCallMode&&a.getMode()==LegacyAudioPolicy.callMode())a.setMode(previousCallMode);if(ownsCallSpeaker&&a.isSpeakerphoneOn()==LegacyAudioPolicy.callSpeakerphone())a.setSpeakerphoneOn(previousCallSpeaker);if(ownsCallVolume&&a.getStreamVolume(callStream)==callMaxVolume)a.setStreamVolume(callStream,previousCallVolume,0);callRouteLog("call_audio_route_restored",previousCallMode,a.getMode(),null);}catch(Exception e){callRouteLog("call_route_restore_failed",previousCallMode,a.getMode(),e.getClass().getSimpleName());}finally{ownsCallVolume=false;ownsCallMode=false;ownsCallSpeaker=false;previousCallMode=AudioManager.MODE_NORMAL;previousCallVolume=0;callMaxVolume=0;previousCallSpeaker=false;if(callUsbAssert!=null)callUsbAssert.callEnded();}}
 private void callRouteLog(String detail,int before,int after,String error){try{AudioManager a=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int callStream=stream("call");boolean platformPhoneStrategy=callRoute.getPlatformPhoneStrategy();JSONObject j=new JSONObject().put("detail",detail).put("strategy",callRoute.getDiagnosticProfile()).put("previousMode",before).put("activeMode",after).put("platformPhoneStrategy",platformPhoneStrategy).put("modePreservedForOemRoute",!platformPhoneStrategy&&before==after).put("speakerphoneForced",platformPhoneStrategy).put("activeSpeakerphone",a.isSpeakerphoneOn()).put("speakerphonePreservedForOemRoute",!platformPhoneStrategy&&a.isSpeakerphoneOn()==previousCallSpeaker).put("previousSpeakerphone",previousCallSpeaker).put("outputStream",callStream).put("focusStream",callRoute.getOutputStream()).put("clientFocusRequested",LegacyAudioPolicy.usesClientCallFocus(secondMusic)).put("previousVolume",previousCallVolume).put("activeVolume",a.getStreamVolume(callStream)).put("maxVolume",a.getStreamMaxVolume(callStream)).put("volumePolicy",platformPhoneStrategy?"maximize_dedicated_call":(callRoute.getMaximizeVolume()?"maximize_shared_media":"preserve_shared_media")).put("volumeMaxAccepted",ownsCallVolume).put("oemBtPhoneSourceRequested",false).put("audioSystem",AudioSystemEvidence.routingSnapshot(a,context));if(error!=null)j.put("error",error);MainActivity.record(context,"CALL_AUDIO_ROUTE",j);}catch(Exception ignored){}}
 public static void logCallOutput(Context c, AudioTrack track, int stream, String phase, long written,
                                  long head, float gain, String pcmSummary) {
  try {
   AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
   int volume = audio.getStreamVolume(stream);
   int maxVolume = audio.getStreamMaxVolume(stream);
   JSONObject routePolicy = AudioRouteProbe.snapshot(audio);
   JSONObject audioSystem = AudioSystemEvidence.routingSnapshot(audio, c);
   StringBuilder summary = new StringBuilder(pcmSummary)
       .append(" streamVolume=").append(volume).append(" streamMax=").append(maxVolume);
   JSONArray outputDevices = audioSystem.optJSONArray("streamOutputDevices");
   if (outputDevices != null) for (int i = 0; i < outputDevices.length(); i++) {
    JSONObject device = outputDevices.optJSONObject(i);
    if (device == null || device.optInt("stream", -1) != stream) continue;
    if (device.has("deviceMask")) {
     summary.append(" outputDeviceMask=0x").append(Integer.toHexString(device.getInt("deviceMask")));
    }
    break;
   }
   JSONObject event = new JSONObject()
       .put("phase", phase).put("mode", audio.getMode()).put("stream", stream)
       .put("streamVolume", volume).put("streamMax", maxVolume)
       .put("speakerphone", audio.isSpeakerphoneOn()).put("bluetoothSco", audio.isBluetoothScoOn())
       .put("bluetoothA2dp", audio.isBluetoothA2dpOn()).put("wiredHeadset", audio.isWiredHeadsetOn())
       .put("microphoneMuted", audio.isMicrophoneMute()).put("musicActive", audio.isMusicActive())
       .put("routePolicy", routePolicy).put("audioSystem", audioSystem)
       .put("trackPresent", track != null).put("writtenBytes", written).put("playbackHeadFrames", head)
       .put("diagnosticSummary", summary.toString())
       .put("evidenceScope", "AudioTrack queue consumption only; no amplifier loopback");
   if (!Float.isNaN(gain) && !Float.isInfinite(gain)) event.put("trackGain", gain);
   if (track != null) event.put("trackState", track.getState()).put("playState", track.getPlayState())
       .put("sessionId", track.getAudioSessionId()).put("nativeRate", AudioTrack.getNativeOutputSampleRate(stream));
   MainActivity.record(c, "CALL_OUTPUT_DIAG", event);
  } catch (Exception ignored) { }
 }
 public float packet(String role){PlaybackFocus f; synchronized(this){if(closed)return 0;f=focus.get(role.equals("assistant")?"media":role);}return f==null?1:f.packet();}
 public void playbackEnded(String role){PlaybackFocus f,mediaFocus;synchronized(this){if(closed)return;f=focus.get(role.equals("assistant")?"media":role);mediaFocus=role.equals("call")?focus.get("media"):null;}if(f!=null)f.playbackEnded();if(mediaFocus!=null)mediaFocus.foreground();}
 public synchronized void foreground(){if(!closed)for(PlaybackFocus f:focus.values())f.foreground();}
 public synchronized void close(){if(closed)return;closed=true;if(callRoute==LegacyAudioPolicy.CallRoute.BYD_SAH_MX9_MEDIA)OemAudioObserver.endCallRouteCapture();if(callUsbAssert!=null)callUsbAssert.close();for(PlaybackFocus f:focus.values())f.close();}
 public static int secondMusic(Context c){try{if(Build.VERSION.SDK_INT!=17||!"MX9A0".equals(Build.MODEL)||c.getPackageManager().getPackageInfo("com.byd.multimediaservice",0).versionCode!=382)return -1;int value=AudioManager.class.getField("STREAM_MUSIC_SECOND").getInt(null);return value==10?value:-1;}catch(Exception e){return -1;}}
 public static JSONObject evidence(Context c)throws Exception{AudioManager a=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);int second=secondMusic(c);LegacyAudioPolicy.CallRoute route=LegacyAudioPolicy.resolveCallRoute(c,second);JSONArray streams=new JSONArray();for(java.lang.reflect.Field f:AudioManager.class.getFields()){if(!f.getName().startsWith("STREAM_")||f.getType()!=Integer.TYPE)continue;JSONObject item=new JSONObject().put("name",f.getName());try{int stream=f.getInt(null);item.put("id",stream).put("volume",a.getStreamVolume(stream)).put("max",a.getStreamMaxVolume(stream));}catch(Exception e){item.put("error",e.getClass().getSimpleName());}streams.put(item);}return new JSONObject().put("streams",streams).put("auditedNavigationStream",second).put("navigationCapability",NavigationAudioRoute.inspect(c,second)).put("callDownlinkStream",route.getOutputStream()).put("callFocusStream",route.getOutputStream()).put("strategy",route.getDiagnosticProfile()).put("clientCallFocus",LegacyAudioPolicy.usesClientCallFocus(second)).put("callFocusPolicy",!route.getPlatformPhoneStrategy()?"client_transient_media_focus":"client_full_gain_phone_stream").put("oemPhoneStateControl",second==10?"not_sent_preserve_current_source":"not_applicable").put("callAndroidRoutePolicy",!route.getPlatformPhoneStrategy()?"preserve_mode_speaker_and_mcu_source":"request_in_call_mode_and_speakerphone").put("legacyRouting",AudioRouteProbe.snapshot(a)).put("audioSystem",AudioSystemEvidence.routingSnapshot(a,c));}
}
