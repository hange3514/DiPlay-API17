package local.headunit.diplay

/** Sample Surface output, not input packets or configured maximum. */
class FrameRateMeter {
 private var previous=-1L
 private var at=0L
 private var session=""
 fun reset(){previous=-1;session=""}
 fun sample(count:Long,now:Long,id:String):Double {
  val valid=previous>=0&&count>=previous&&now>at&&session==id&&id.isNotEmpty()
  val value=if(valid)(count-previous)*1000.0/(now-at) else Double.NaN
  previous=count;at=now;session=id;return value
 }
}
