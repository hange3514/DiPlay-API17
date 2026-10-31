package local.headunit.diplay
import java.util.concurrent.atomic.AtomicBoolean

/** A single prefetched chunk, using the same lock and buffer as normal reads.
 * Handoff never cancels an in-flight request or discards bytes already read. */
class EarlyUsbReceive {
 private val started=AtomicBoolean(false)
 private val wanted=AtomicBoolean(true)
 @Volatile private var thread:Thread?=null
 fun start(lock:Any,read:()->Boolean,onError:(Throwable)->Unit) {
  check(started.compareAndSet(false,true))
  thread=MediaWorkers.start("ncm-early-in") {
   try{synchronized(lock){while(wanted.get()){if(read())break}}}
   catch(e:Throwable){onError(e)}
  }
 }
 fun handoff(){wanted.set(false)}
 fun await(){thread?.let{MediaWorkers.await(it,1500)}}
}
