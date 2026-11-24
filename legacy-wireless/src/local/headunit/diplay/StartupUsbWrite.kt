package local.headunit.diplay

import java.util.concurrent.atomic.AtomicBoolean

/** Ensures startup readiness polling is spent at most once per NCM link. */
internal class StartupWriteBudget {
 private val consumed=AtomicBoolean(false)
 fun claim():Boolean=consumed.compareAndSet(false,true)
 fun consume(){consumed.set(true)}
 fun isConsumed():Boolean=consumed.get()
}

/** Replay only small IPv6 neighbor discovery packets, whose duplicates are harmless.
 * Never replay TCP, media, multi-packet NTBs or a reported partial USB transfer. */
object StartupUsbWrite {
 /** Keep a non-replayable early frame from stalling the USB sender while the iPhone NCM pipe is NAKed. */
 fun timeoutMillis(replayable:Boolean,firstBlockAccepted:Boolean,requestedMillis:Int):Int {
  require(requestedMillis>0)
  return if(!firstBlockAccepted&&!replayable)minOf(requestedMillis,NOT_READY_NON_REPLAYABLE_TIMEOUT_MS) else requestedMillis
 }
 /** One startup-only discovery packet may bridge the kernel's multi-second RS gaps.
  * Yield on inbound data so its queued neighbor response is not held behind this packet.
  * Every ioctl is short; close/disconnect/partial success always stops replay. */
 fun awaitReady(transfer:(Int)->Int,check:()->Unit,keepWaiting:()->Boolean,
                nowMillis:()->Long,pause:()->Unit):Int {
  val started=nowMillis()
  var result=-110
  repeat(128){
   check()
   val remaining=15000L-(nowMillis()-started)
   if(remaining<=0)return result
   if(it>0&&!keepWaiting())return result
   result=transfer(minOf(150L,remaining).toInt())
   if(result!=-110&&result!=-11)return result
   if(!keepWaiting()||nowMillis()-started>=15000)return result
   pause()
  }
  return result
 }
 fun retryable(frame:ByteArray,blockSize:Int):Boolean = retryable(frame,0,frame.size,blockSize)
 fun retryable(frame:ByteArray,offset:Int,length:Int,blockSize:Int):Boolean {
  return blockSize in 0..512&&neighborDiscovery(frame,offset,length)
 }
 /** Only small, link-local IPv6 neighbor discovery packets may leave before CarPlay start. */
 fun neighborDiscovery(frame:ByteArray,offset:Int,length:Int):Boolean {
  if(offset<0||length<58||offset>frame.size-length)return false
  val start=offset
  if(frame[start+12].toInt() and 255!=0x86||frame[start+13].toInt() and 255!=0xdd)return false
  if(frame[start+14].toInt() ushr 4 and 15!=6||frame[start+20].toInt() and 255!=58)return false
  val required=when(frame[start+54].toInt() and 255){133->8;135,136->24;else->return false}
  val declared=((frame[start+18].toInt() and 255) shl 8) or (frame[start+19].toInt() and 255)
  return declared>=required&&declared<=length-54&&frame[start+55].toInt()==0
 }
 fun neighborDiscoveryKind(frame:ByteArray,offset:Int,length:Int):String? {
  if(!neighborDiscovery(frame,offset,length))return null
  return when(frame[offset+54].toInt() and 255){
   133->"router_solicitation"
   135->"neighbor_solicitation"
   136->"neighbor_advertisement"
   else->null
  }
 }
 fun write(retry:Boolean,timeout:Int,transfer:(Int)->Int,check:()->Unit,pause:()->Unit):Int {
  val attempts=if(retry)minOf(8,maxOf(1,timeout/175)) else 1
  var result=0
  repeat(attempts){n->check();result=transfer(if(retry)minOf(timeout,150) else timeout)
   if(result!=-110&&result!=-11)return result
   if(n+1<attempts)pause()
  }
  return result
 }
 const val NOT_READY_NON_REPLAYABLE_TIMEOUT_MS=20
}
