package local.headunit.diplay

/** CDC NETWORK_CONNECTION notification; a carrier indication is not proof that
 * bulk OUT has accepted a packet. Never reset the USB device in response to it. */
object NcmLinkNotification {
 fun connected(bytes:ByteArray,length:Int):Boolean? {
  if(length!=8||length>bytes.size)return null
  if(bytes[0].toInt() and 255!=0xa1||bytes[1].toInt()!=0)return null
  if(bytes[3].toInt()!=0||bytes[6].toInt()!=0||bytes[7].toInt()!=0)return null
  return when(bytes[2].toInt()){0->false;1->true;else->null}
 }
}
