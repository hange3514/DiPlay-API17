package local.headunit.diplay

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Each frame retains the configuration it arrived under. Duplicate avcC never resets decoding. */
class VideoInput(private val capacity:Int=6) {
 data class Frame(val config:ByteArray,val bytes:ByteArray,val arrivedNs:Long)
 private val queue=ArrayBlockingQueue<Frame>(capacity)
 private var config:ByteArray?=null
 private var waitingForKeyframe=false
 @Synchronized fun configure(bytes:ByteArray):Boolean {
  if(config?.contentEquals(bytes)==true)return false
  config=bytes.copyOf();queue.clear();waitingForKeyframe=true;return true
 }
 /** false means a frame was lost and recovery may be needed; caller rate-limits requests. */
 fun offer(bytes:ByteArray):Boolean = enqueue(bytes,false)
 /** Ownership moves to this queue. The producer must never mutate/reuse bytes after this call. */
 fun offerOwned(bytes:ByteArray):Boolean = enqueue(bytes,true)
 @Synchronized private fun enqueue(bytes:ByteArray,owned:Boolean):Boolean {
  val c=config?:return true
  if(bytes.isEmpty())return true
  // Once any reference frame is lost, later deltas cannot safely continue this GOP.
  if(bytes.size>1024*1024){queue.clear();waitingForKeyframe=true;return false}
  // Scan NAL bytes only during recovery. Normal P-frames already belong to the
  // current GOP; walking every byte on Dalvik wastes a full frame-sized pass.
  if(waitingForKeyframe){if(!isKeyframe(bytes))return false;waitingForKeyframe=false}
  val frame=Frame(c,if(owned)bytes else bytes.copyOf(),System.nanoTime())
  if(queue.offer(frame))return true
  val key=isKeyframe(bytes)
  queue.clear();waitingForKeyframe=!key
  if(key)queue.offer(frame)
  return false
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
