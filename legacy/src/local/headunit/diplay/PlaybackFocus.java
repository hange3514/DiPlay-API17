package local.headunit.diplay;
import android.content.Context;
import android.media.AudioManager;
import android.os.*;
import java.io.Closeable;
import org.json.JSONObject;

/** Only real PCM playback requests focus. No packet for 1.5s releases it. */
public final class PlaybackFocus implements Closeable,AudioManager.OnAudioFocusChangeListener {
 public interface Volume {void set(float value);}
 private final AudioManager manager;private final Context context;private final Volume output;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final int gain;private final String role;private final int focusStream;
 private AudioManager.OnAudioFocusChangeListener sessionListener;
 private boolean closed,requested,blocked,timer;private long lastPacket;private float volume;
 public PlaybackFocus(Context c,Volume v){this(c,v,AudioManager.AUDIOFOCUS_GAIN,"media",AudioManager.STREAM_MUSIC);}
 public PlaybackFocus(Context c,Volume v,int hint,String label){this(c,v,hint,label,AudioManager.STREAM_MUSIC);}
 public PlaybackFocus(Context c,Volume v,int hint,String label,int requestedStream){context=c.getApplicationContext();manager=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);output=v;gain=hint;role=label;focusStream=requestedStream;}
 public synchronized void foreground(){if(!closed&&blocked&&!requested){blocked=false;log("foreground_retry_allowed");}}
 public synchronized float packet(){return packet(gain);}
 public synchronized void playbackEnded(){if(closed)return;handler.removeCallbacks(expire);timer=false;release();blocked=false;log("playback_ended");}
 public synchronized float packet(int requestedGain){if(closed)return 0;lastPacket=SystemClock.elapsedRealtime();if(!requested&&!blocked){sessionListener=new AudioManager.OnAudioFocusChangeListener(){public void onAudioFocusChange(int change){synchronized(PlaybackFocus.this){if(sessionListener!=this)return;PlaybackFocus.this.onAudioFocusChange(change);}}};requested=manager.requestAudioFocus(sessionListener,focusStream,requestedGain)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;blocked=!requested;volume=requested?1:0;output.set(volume);log((requested?"granted":"denied")+" hint="+requestedGain+" stream="+focusStream);}if(!timer){timer=true;handler.postDelayed(expire,1500);}output.set(volume);return volume;}
 public synchronized void onAudioFocusChange(int change){if(closed||!timer)return;if(change==AudioManager.AUDIOFOCUS_GAIN){blocked=false;requested=true;volume=1;}else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){volume=.2f;}else{blocked=true;volume=0;if(change==AudioManager.AUDIOFOCUS_LOSS){requested=false;abandonListener();}}output.set(volume);log("change="+change);}
 private final Runnable expire=new Runnable(){public void run(){synchronized(PlaybackFocus.this){if(closed)return;long remaining=1500-(SystemClock.elapsedRealtime()-lastPacket);if(remaining>0){handler.postDelayed(this,remaining);return;}timer=false;release();blocked=false;}}};
 private void abandonListener(){AudioManager.OnAudioFocusChangeListener previous=sessionListener;sessionListener=null;if(previous!=null)manager.abandonAudioFocus(previous);}
 private void release(){abandonListener();requested=false;volume=0;output.set(0);}
 public synchronized void close(){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);release();}
 private void log(String event){try{MainActivity.record(context,"AUDIO_FOCUS",new JSONObject().put("role",role).put("detail",event).put("mode",manager.getMode()).put("focusStream",focusStream).put("focusStreamVolume",manager.getStreamVolume(focusStream)).put("focusStreamMax",manager.getStreamMaxVolume(focusStream)).put("musicVolume",manager.getStreamVolume(AudioManager.STREAM_MUSIC)).put("voiceCallVolume",manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)).put("voiceCallMax",manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)).put("speaker",manager.isSpeakerphoneOn()).put("bluetoothSco",manager.isBluetoothScoOn()).put("wiredHeadset",manager.isWiredHeadsetOn()));}catch(Exception ignored){}}
}
