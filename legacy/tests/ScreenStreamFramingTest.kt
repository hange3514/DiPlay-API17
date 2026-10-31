package local.headunit.diplay

import com.shilapi.xcertplay.airplay.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ScreenStreamFramingTest {
 private val loopback=InetAddress.getByName("127.0.0.1")
 private fun header(length:Int)=ByteArray(128).also{b->repeat(4){b[it]=(length ushr (it*8)).toByte()}}
 private fun join(stream:ScreenStream){val f=ScreenStream::class.java.getDeclaredField("thread");f.isAccessible=true;val t=f.get(stream) as Thread;t.join(1500);assertFalse(t.isAlive)}
 @Test fun fragmentedEncryptedFramesRemainDistinctAcrossReusedHeaders(){
  val key=ByteArray(32){it.toByte()};val done=CountDownLatch(12)
  val frames=CopyOnWriteArrayList<ByteArray>();val error=AtomicReference<Throwable>()
  val stream=ScreenStream(key,{},loopback)
  val port=stream.listen(object:ScreenStream.Listener{
   override fun onFrame(bytes:ByteArray,senderNanos:Long,arrivalNanos:Long){frames.add(bytes);done.countDown()}
   override fun onClosed(cause:Throwable?){if(cause!=null)error.set(cause)}
  })
  try{Socket(loopback,port).use{s->
   s.tcpNoDelay=true;val out=s.getOutputStream()
   repeat(12){n->
    val payload=byteArrayOf(0,0,0,2,0x65,n.toByte());val h=header(payload.size+16)
    val sealed=AirPlayCrypto.chachaSeal(key,AirPlayCrypto.nonce64(n.toLong()),payload,h)
    val wire=h+sealed;var at=0
    while(at<wire.size){val count=minOf(1+(at%19),wire.size-at);out.write(wire,at,count);at+=count}
   };out.flush();assertTrue("Buffered receiver waited for a full buffer: ${error.get()}",done.await(3,TimeUnit.SECONDS))
   repeat(12){n->assertArrayEquals(byteArrayOf(0,0,0,1,0x65,n.toByte()),frames[n]);if(n>0)assertNotSame(frames[n-1],frames[n])}
  }}finally{stream.close();join(stream)}
  assertNull(error.get())
 }
 @Test fun invalidAndTruncatedMessagesCloseOnceWithoutLargeAllocations(){
  for(wire in listOf(header(-1),header(8*1024*1024+1),header(20)+ByteArray(3),ByteArray(8))){
   val done=CountDownLatch(1);val error=AtomicReference<Throwable>()
   val stream=ScreenStream(ByteArray(32),{},loopback)
   val port=stream.listen(object:ScreenStream.Listener{override fun onClosed(cause:Throwable?){error.set(cause);done.countDown()}})
   try{Socket(loopback,port).use{it.getOutputStream().write(wire);it.shutdownOutput();assertTrue(done.await(2,TimeUnit.SECONDS))};assertNotNull(error.get())}finally{stream.close();join(stream)}
  }
 }
}
