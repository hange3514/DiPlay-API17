package local.headunit.diplay

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Each frame retains the configuration it arrived under. Duplicate avcC never resets decoding. */
class VideoInput(private val capacity:Int=6) {
 data class Frame(val config:ByteArray,val bytes:ByteArray,val arrivedNs:Long)
 enum class OfferResult(val acceptedKeyframe:Boolean=false,val recoveryReason:String?=null) {
  WITHOUT_CONFIG, WAITING_FOR_KEYFRAME(recoveryReason="waiting_for_keyframe"),
  FRAME_TOO_LARGE(recoveryReason="frame_too_large"), QUEUE_OVERFLOW(recoveryReason="queue_overflow"),
  ACCEPTED, KEYFRAME(acceptedKeyframe=true),
  REPLACED_WITH_KEYFRAME(acceptedKeyframe=true,recoveryReason="queue_overflow")
 }
 private val queue=ArrayBlockingQueue<Frame>(capacity)
 private var config:ByteArray?=null
 private var waitingForKeyframe=false
 @Synchronized fun configure(bytes:ByteArray):Boolean {
  if(config?.contentEquals(bytes)==true)return false
  config=bytes.copyOf();queue.clear();waitingForKeyframe=true;return true
 }
 fun offer(bytes:ByteArray):OfferResult = enqueue(bytes,false)
 /** Ownership moves to this queue. The producer must never mutate/reuse bytes after this call. */
 fun offerOwned(bytes:ByteArray):OfferResult = enqueue(bytes,true)
 @Synchronized private fun enqueue(bytes:ByteArray,owned:Boolean):OfferResult {
  val c=config?:return OfferResult.WITHOUT_CONFIG
  if(bytes.isEmpty())return OfferResult.WITHOUT_CONFIG
  // Once any reference frame is lost, later deltas cannot safely continue this GOP.
  if(bytes.size>1024*1024){queue.clear();waitingForKeyframe=true;return OfferResult.FRAME_TOO_LARGE}
  // Scan NAL bytes only during recovery. Normal P-frames already belong to the
  // current GOP; walking every byte on Dalvik wastes a full frame-sized pass.
  val startsGop=waitingForKeyframe
  if(startsGop){if(!isKeyframe(bytes))return OfferResult.WAITING_FOR_KEYFRAME;waitingForKeyframe=false}
  val frame=Frame(c,if(owned)bytes else bytes.copyOf(),System.nanoTime())
  if(queue.offer(frame))return if(startsGop)OfferResult.KEYFRAME else OfferResult.ACCEPTED
  val key=isKeyframe(bytes)
  queue.clear();waitingForKeyframe=!key
  if(key)queue.offer(frame)
  return if(key)OfferResult.REPLACED_WITH_KEYFRAME else OfferResult.QUEUE_OVERFLOW
 }
 fun poll(timeoutMillis:Long=100):Frame?=queue.poll(timeoutMillis,TimeUnit.MILLISECONDS)
 fun size():Int=queue.size
 @Synchronized fun dropped(){queue.clear();waitingForKeyframe=true}
 @Synchronized fun clear(){queue.clear();config=null;waitingForKeyframe=false}
 companion object {
  fun isKeyframe(bytes:ByteArray):Boolean {
   val annex=bytes.size>=4&&bytes[0].toInt()==0&&bytes[1].toInt()==0&&(bytes[2].toInt()==0&&bytes[3].toInt()==1)
   if(!annex){var at=0;while(at+4<bytes.size){val size=((bytes[at].toInt() and 255) shl 24) or ((bytes[at+1].toInt() and 255) shl 16) or ((bytes[at+2].toInt() and 255) shl 8) or (bytes[at+3].toInt() and 255);at+=4;if(size<=0||size>bytes.size-at)return false;if(bytes[at].toInt() and 31==5)return true;at+=size};return false}
   for(i in 0 until bytes.size-3){
    if(bytes[i].toInt()==0&&bytes[i+1].toInt()==0){
     val n=if(bytes[i+2].toInt()==1)i+3 else if(bytes[i+2].toInt()==0&&bytes[i+3].toInt()==1)i+4 else -1
     if(n>=0&&n<bytes.size&&bytes[n].toInt() and 31==5)return true
    }
   }
   return false
  }
 }
}
