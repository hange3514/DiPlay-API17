package local.headunit.diplay

/** Small monotonic startup trace. Thread safe: decoder and connection worker report independently. */
class ConnectionTimings(private val started:Long) {
 private val completed=LinkedHashMap<String,Long>()
 private var phase="start"
 private var since=started
 private var ready=false
 @Synchronized fun advance(next:String,now:Long){
  if(ready)return
  completed[phase]=(completed[phase]?:0)+(now-since).coerceAtLeast(0)
  phase=next;since=now
 }
 @Synchronized fun firstFrame(now:Long){if(ready)return;advance("video_ready",now);ready=true}
 @Synchronized fun snapshot(now:Long):Map<String,Long>{
  val copy=LinkedHashMap(completed)
  if(!ready)copy[phase]=(copy[phase]?:0)+(now-since).coerceAtLeast(0)
  copy["totalMs"]=((if(ready)since else now)-started).coerceAtLeast(0)
  return copy
 }
}
