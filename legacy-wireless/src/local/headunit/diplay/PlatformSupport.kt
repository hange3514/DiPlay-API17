package local.headunit.diplay
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
object PlatformSupport {
 private const val MAX_MFI_FILE_BYTES=16*1024
 fun prepareMfi(context:Context):File {
  val dir=File(context.filesDir,"offline-mfi")
  if(!dir.isDirectory&&!dir.mkdirs())throw IOException("Offline MFi directory cannot be created")
  for(name in arrayOf("identity.pk8","certificate.p7b")){
   val dest=File(dir,name)
   val packaged=context.assets.open("offline-mfi/$name").use{readBounded(it)}
   val existing=if(dest.isFile&&dest.canRead()&&dest.length() in 1..MAX_MFI_FILE_BYTES)runCatching{FileInputStream(dest).use{readBounded(it)}}.getOrNull() else null
   try{
    if(existing==null||!packaged.contentEquals(existing)){
     val atomic=AtomicFile(dest);val output=atomic.startWrite()
     try{output.write(packaged);output.fd.sync();atomic.finishWrite(output)}catch(failure:Throwable){atomic.failWrite(output);throw failure}
    }
   }finally{
    if(name=="identity.pk8"){packaged.fill(0);existing?.fill(0)}
   }
  }
  return dir
 }
 private fun readBounded(input:InputStream):ByteArray {
  val buffer=ByteArray(MAX_MFI_FILE_BYTES+1);var size=0
  try{
   while(size<buffer.size){val count=input.read(buffer,size,buffer.size-size);if(count<0)break;size+=count}
   if(size>MAX_MFI_FILE_BYTES)throw IOException("Offline MFi identity file exceeds limit")
   if(size==0)throw IOException("Offline MFi identity file is empty")
   return buffer.copyOf(size)
  }finally{buffer.fill(0)}
 }
 @JvmStatic fun setNonBlocking(p:ParcelFileDescriptor)=setBlockingFlag(p,false)
 @JvmStatic fun setBlocking(p:ParcelFileDescriptor)=setBlockingFlag(p,true)
 private fun setBlockingFlag(p:ParcelFileDescriptor,blocking:Boolean){val os=Class.forName("libcore.io.Libcore").getField("os").get(null);val type=Class.forName("libcore.io.Os");val fd=p.fileDescriptor;val flags=type.getMethod("fcntlVoid",FileDescriptor::class.java,Int::class.javaPrimitiveType).invoke(os,fd,3) as Int;type.getMethod("fcntlLong",FileDescriptor::class.java,Int::class.javaPrimitiveType,Long::class.javaPrimitiveType).invoke(os,fd,4,(if(blocking)flags and 2048.inv() else flags or 2048).toLong());val after=type.getMethod("fcntlVoid",FileDescriptor::class.java,Int::class.javaPrimitiveType).invoke(os,fd,3) as Int;check((after and 2048==0)==blocking)}
}
