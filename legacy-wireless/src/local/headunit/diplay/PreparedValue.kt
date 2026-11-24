package local.headunit.diplay
import java.util.concurrent.FutureTask
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException

/** One background preparation per process; a failed value can be retried on the next request. */
class PreparedValue<T>(
 private val launch:(Runnable)->Unit={runnable->Thread(runnable,"diplay-prepare-identity").apply{isDaemon=true;start()}},
 private val prepare:()->T,
) {
 private var pending:FutureTask<T>?=null
 @Synchronized private fun task():FutureTask<T>{
  pending?.let{return it}
  val next=FutureTask(Callable{prepare()});pending=next
  try{launch(next)}catch(failure:Throwable){if(pending===next)pending=null;throw failure}
  return next
 }
 fun warm(){task()}
 @Synchronized fun completedValue():T? {val current=pending?:return null;if(!current.isDone)return null;return try{current.get()}catch(_:Throwable){null}}
 fun get():T {val current=task();try{return current.get()}catch(e:ExecutionException){synchronized(this){if(pending===current)pending=null};throw e.cause?:e}}
}
