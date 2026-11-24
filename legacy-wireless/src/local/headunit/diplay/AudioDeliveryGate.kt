package local.headunit.diplay
import java.io.Closeable

/** Per-stream delivery barrier. Closing waits only for this stream, never for video delivery. */
class AudioDeliveryGate(private val alive:()->Boolean,private val start:(Int)->Unit,private val packet:(ByteArray,Int)->Unit,private val stop:()->Unit):Closeable {
 private var closed=false;private var started=false
 @Synchronized fun onStarted(sample:Int){if(closed||started||!alive())return;started=true;start(sample)}
 @Synchronized fun onPacket(bytes:ByteArray,sample:Int){if(!closed&&started&&alive())packet(bytes,sample)}
 @Synchronized override fun close(){if(closed)return;closed=true;if(started)stop()}
}
