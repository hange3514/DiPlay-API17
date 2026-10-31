package local.headunit.diplay
/** Experimental wait; receiving data wins even if a notification was lost. */
object NcmCarrierGate {
 fun await(up:()->Boolean,inbound:()->Boolean,check:()->Unit,now:()->Long,pause:()->Unit){
  val start=now()
  while(true){check();if(up()||inbound()||now()-start>=5000)return;pause()}
 }
}
