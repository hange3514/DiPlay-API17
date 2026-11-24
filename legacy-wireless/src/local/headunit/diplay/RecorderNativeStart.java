package local.headunit.diplay;
import java.lang.reflect.*;
/** Mirrors the audited API17 start/stop state contract, without OEM global focus hooks. */
public final class RecorderNativeStart {
 private final Field state,recording,lock,hook;private final Method start,stop;
 public RecorderNativeStart(Class<?> type)throws Exception {
  state=type.getDeclaredField("mState");recording=type.getDeclaredField("mRecordingState");lock=type.getDeclaredField("mRecordingStateLock");
  Field flag;try{flag=type.getDeclaredField("mStart");}catch(NoSuchFieldException e){flag=null;}hook=flag;
  start=type.getDeclaredMethod("native_start",int.class,int.class);stop=type.getDeclaredMethod("native_stop");
  for(Field f:new Field[]{state,recording,lock})f.setAccessible(true);if(hook!=null)hook.setAccessible(true);start.setAccessible(true);stop.setAccessible(true);
  if(state.getType()!=int.class||recording.getType()!=int.class||start.getReturnType()!=int.class||(hook!=null&&hook.getType()!=boolean.class))throw new NoSuchFieldException("Unexpected AudioRecord layout");
 }
 public boolean hasVendorHook(){return hook!=null;}
 public void start(Object recorder)throws Exception {
  Object monitor=lock.get(recorder);if(monitor==null)throw new IllegalStateException("Missing recorder lock");
  synchronized(monitor){if(state.getInt(recorder)!=1||recording.getInt(recorder)!=1||(hook!=null&&hook.getBoolean(recorder)))throw new IllegalStateException("Recorder not fresh");
   int result=((Integer)start.invoke(recorder,0,0)).intValue();if(result!=0)throw new IllegalStateException("Native capture rejected: "+result);
   try{recording.setInt(recorder,3);}catch(Exception e){try{stop.invoke(recorder);}catch(Exception ignored){}throw e;}
  }
 }
}
