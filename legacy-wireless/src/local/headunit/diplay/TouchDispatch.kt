package local.headunit.diplay
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException

/** Adjacent unsent moves can be replaced; down/up boundaries keep their order. */
object TouchDispatch {
 private class Event(val action:Int,val work:()->Unit):Runnable {override fun run()=work()}
 fun submit(executor:ThreadPoolExecutor,queue:LinkedBlockingDeque<Runnable>,action:Int,work:()->Unit) {
  synchronized(queue){
   if(executor.isShutdown)return
   val last=queue.peekLast()
   if(action==2&&last is Event&&last.action==2)queue.removeLastOccurrence(last)
   val event=Event(action,work)
   try{executor.execute(event)}catch(_:RejectedExecutionException){
    if(action==1||action==3){queue.clear();runCatching{executor.execute(event)}}
   }
  }
 }
}
