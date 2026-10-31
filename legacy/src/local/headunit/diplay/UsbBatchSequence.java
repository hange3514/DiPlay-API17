package local.headunit.diplay;

/** Pure bounded scheduler. Persistence is an explicit barrier between USB owners. */
public final class UsbBatchSequence {
 public static final int NONE=0,START=1,CAPTURE=2,FINISH=3,ABORT=4;
 private static final int[] PROFILES={3,6,7,8,9,10,10,9,8,7,6,3};
 public static int[] plannedProfiles(){return PROFILES.clone();}
 private final long born;private long phaseAt,idleAt=-1,readyAt=-1;
 private int phase,index,failures;private String reason="";private boolean success;
 public UsbBatchSequence(long now){born=phaseAt=now;}
 public int profile(){return PROFILES[Math.min(index,PROFILES.length-1)];}
 public int index(){return index;}public int count(){return PROFILES.length;}
 public long idleMillis(){return 1200;}
 public long observeMillis(){return index>=6?10000:2500;}
 public boolean success(){return success;}public String reason(){return reason;}
 public int poll(long now,boolean busy,boolean ready,boolean ended,boolean foreground,boolean phone){
  if(phase==3)return NONE;
  if(!foreground||!phone||now-born>=1200000){reason=!foreground?"background":!phone?"phone_removed":"batch_deadline";phase=3;return ABORT;}
  if(phase==2)return NONE;
  if(phase==0){
   if(busy){idleAt=-1;if(now-phaseAt>=20000){reason="cleanup_timeout";phase=3;return ABORT;}return NONE;}
   if(idleAt<0)idleAt=now;
   if(now-idleAt<idleMillis())return NONE;
   if(index>=PROFILES.length||failures>=2){reason=failures>=2?"consecutive_failures":"complete";phase=3;return FINISH;}
   phase=1;phaseAt=now;readyAt=-1;success=false;reason="";return START;
  }
  if(ready){if(readyAt<0)readyAt=now;}else readyAt=-1;
  if(ended){success=false;reason="connection_ended";phase=2;return CAPTURE;}
  if(readyAt>=0&&now-readyAt>=observeMillis()){success=true;reason="video_stable";phase=2;return CAPTURE;}
  if(now-phaseAt>=45000+observeMillis()){success=false;reason="connection_timeout";phase=2;return CAPTURE;}
  return NONE;
 }
 public void saved(long now){if(phase!=2)throw new IllegalStateException("No completed trial to save");failures=success?0:failures+1;index++;phase=0;phaseAt=now;idleAt=-1;}
 public void cancel(){phase=3;reason="cancelled";}
}
