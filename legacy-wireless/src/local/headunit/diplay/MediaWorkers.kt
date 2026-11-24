package local.headunit.diplay
import java.util.concurrent.atomic.AtomicInteger

/** Count actual worker lifetimes, including native release after the service has stopped. */
object MediaWorkers {
 private val count=AtomicInteger()
 @JvmStatic fun busy()=count.get()>0
 @JvmStatic fun activeCount()=count.get()
 fun start(name:String,run:()->Unit):Thread {
  count.incrementAndGet()
  val thread=Thread({try{run()}finally{count.decrementAndGet()}},name)
  try{thread.start()}catch(e:Throwable){count.decrementAndGet();throw e}
  return thread
 }
 fun await(thread:Thread,timeout:Long=1500):Boolean {
  if(Thread.currentThread()===thread)return false
  try{thread.join(timeout)}catch(e:InterruptedException){Thread.currentThread().interrupt()}
  return !thread.isAlive
 }
}
