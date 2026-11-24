package local.headunit.diplay

import java.io.Closeable
import java.io.IOException

/** Owns two duplicates. No Java stream/finalizer can later close a reused descriptor. */
class BoundedPacketIo(descriptor:Int):Closeable {
 private val readLock=Any();private val writeLock=Any()
 @Volatile private var closed=false
 private val readFd=NativeUsb.duplicate(descriptor).also{if(it<0)throw IOException("TUN read dup errno=${-it}")}
 private val writeFd=NativeUsb.duplicate(descriptor).also{if(it<0){NativeUsb.closeDescriptor(readFd);throw IOException("TUN write dup errno=${-it}")}}
 fun read(buffer:ByteArray):Int=synchronized(readLock){
  if(closed)return@synchronized 0
  val n=NativeUsb.pollRead(readFd,buffer,100)
  when(n){-110,-11,-4->0;0->throw IOException("TUN closed");else->{if(n<0)throw IOException("TUN read errno=${-n}");n}}
 }
 fun write(buffer:ByteArray,offset:Int,length:Int):Boolean=synchronized(writeLock){
  if(closed)return@synchronized false
  val n=NativeUsb.pollWrite(writeFd,buffer,offset,length,100)
  if(n==-110||n==-11||n==-4)return@synchronized false
  if(n!=length)throw IOException("TUN write result=$n expected=$length")
  true
 }
 override fun close(){synchronized(this){if(closed)return;closed=true}
  synchronized(readLock){NativeUsb.closeDescriptor(readFd)}
  synchronized(writeLock){NativeUsb.closeDescriptor(writeFd)}
 }
}
