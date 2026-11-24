package local.headunit.diplay

/** One notification on the NCM control interface's interrupt endpoint. */
data class NcmStatusNotification(
 val requestType:Int,
 val code:Int,
 val value:Int,
 val index:Int,
 val payload:ByteArray,
) {
 /** Raw little-endian word reported by the observed Apple 0x2b form; semantics remain untrusted. */
 fun observedAppleLinkWord():Long? {
  if(requestType!=0xa1||code!=0x2b||payload.size!=10)return null
  if(payload[0].toInt()!=0||payload[1].toInt()!=1)return null
  if((6..9).any{payload[it].toInt()!=0})return null
  return readU32(payload,2)
 }

 companion object {
  private fun readU32(bytes:ByteArray,offset:Int):Long=
   (bytes[offset].toLong() and 255L) or
    ((bytes[offset+1].toLong() and 255L) shl 8) or
    ((bytes[offset+2].toLong() and 255L) shl 16) or
    ((bytes[offset+3].toLong() and 255L) shl 24)
 }
}

/** Reassembles CDC notifications that arrive in multiple interrupt packets (for example 16+2). */
class NcmStatusNotificationDecoder {
 private var pending=ByteArray(0)

 @Synchronized fun feed(bytes:ByteArray,length:Int):List<NcmStatusNotification> {
  require(length in 0..bytes.size){"invalid NCM status length"}
  if(length==0)return emptyList()
  if(pending.size+length>MAX_BUFFER_BYTES)pending=ByteArray(0)
  val combined=ByteArray(pending.size+length)
  pending.copyInto(combined)
  bytes.copyInto(combined,pending.size,0,length)
  pending=combined
  val decoded=ArrayList<NcmStatusNotification>(1)
  while(pending.isNotEmpty()) {
   val marker=pending.indexOf(0xa1.toByte())
   if(marker<0) {
    pending=if((pending.last().toInt() and 255)==0xa1) byteArrayOf(pending.last()) else ByteArray(0)
    break
   }
   if(marker>0)pending=pending.copyOfRange(marker,pending.size)
   if(pending.size<HEADER_BYTES)break
   val payloadLength=readU16(pending,6)
   if(payloadLength>MAX_PAYLOAD_BYTES) {
    pending=pending.copyOfRange(1,pending.size)
    continue
   }
   val packetLength=HEADER_BYTES+payloadLength
   if(pending.size<packetLength)break
   decoded.add(NcmStatusNotification(
    requestType=pending[0].toInt() and 255,
    code=pending[1].toInt() and 255,
    value=readU16(pending,2),
    index=readU16(pending,4),
    payload=pending.copyOfRange(HEADER_BYTES,packetLength),
   ))
   pending=pending.copyOfRange(packetLength,pending.size)
  }
  return decoded
 }

 private fun readU16(bytes:ByteArray,offset:Int)=
  (bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8)

 companion object {
  private const val HEADER_BYTES=8
  private const val MAX_PAYLOAD_BYTES=64
  private const val MAX_BUFFER_BYTES=128
 }
}

/** Standard CDC NETWORK_CONNECTION; it is not proof that bulk OUT has accepted a packet. */
object NcmLinkNotification {
 fun connected(notification:NcmStatusNotification,controlInterface:Int):Boolean? {
  if(notification.requestType!=0xa1||notification.code!=0||notification.payload.isNotEmpty())return null
  // CDC NETWORK_CONNECTION.wIndex identifies the CDC control interface.
  // Apple private 0x2b uses the data-interface number instead.
  if(notification.index!=controlInterface)return null
  if(notification.value !in 0..1)return null
  return notification.value==1
 }

 fun connected(bytes:ByteArray,length:Int):Boolean? {
  if(length!=8||length>bytes.size)return null
  if(bytes[0].toInt() and 255!=0xa1||bytes[1].toInt()!=0)return null
  if(bytes[6].toInt()!=0||bytes[7].toInt()!=0)return null
  val value=(bytes[2].toInt() and 255) or ((bytes[3].toInt() and 255) shl 8)
  return if(value in 0..1)value==1 else null
 }
}
