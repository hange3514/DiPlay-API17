package local.headunit.diplay
import java.nio.ByteBuffer

/** Convert directly into the decoder buffer; do not allocate another frame-sized array. */
object VideoBufferWriter {
 fun putAnnexB(bytes:ByteArray,out:ByteBuffer):Int {
  require(bytes.isNotEmpty()&&bytes.size<=out.remaining())
  // Wire NAL lengths 256..511 also begin 00 00 01; do not mistake them for
  // three-byte Annex B start codes. This sink uses four-byte start codes.
  if(bytes.size>=4&&bytes[0].toInt()==0&&bytes[1].toInt()==0&&bytes[2].toInt()==0&&bytes[3].toInt()==1){out.put(bytes);return bytes.size}
  var offset=0
  while(offset<bytes.size){
   require(bytes.size-offset>=4){"Truncated NAL length"}
   val count=((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset+1].toInt() and 255) shl 16) or ((bytes[offset+2].toInt() and 255) shl 8) or (bytes[offset+3].toInt() and 255)
   offset+=4;require(count>0&&count<=bytes.size-offset){"Invalid NAL length"}
   out.put(0.toByte());out.put(0.toByte());out.put(0.toByte());out.put(1.toByte());out.put(bytes,offset,count);offset+=count
  }
  return bytes.size
 }
}
