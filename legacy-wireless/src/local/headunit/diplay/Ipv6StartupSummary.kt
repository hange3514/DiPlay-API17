package local.headunit.diplay

/** Header metadata only. Never stringify network payload, phone address or authentication data. */
object Ipv6StartupSummary {
 fun describe(packet:ByteArray,offset:Int=0):String {
  if(offset<0||offset>packet.size||packet.size-offset<40)return "truncated_ipv6"
  fun u(n:Int)=packet[offset+n].toInt() and 255
  fun u16(n:Int)=(u(n) shl 8) or u(n+1)
  if(u(0) ushr 4!=6)return "not_ipv6"
  val declared=u16(4);val available=packet.size-offset-40
  val bytes=minOf(declared,available);val next=u(6)
  val base="ipv6Bytes=${40+bytes} next=$next truncated=${declared>available}"
  // Extension headers are deliberately not guessed as TCP/ICMP headers.
  return when {
   next==6&&bytes>=20 -> "$base tcp=${u16(40)}->${u16(42)} flags=${u(53)} seq=${(0..3).fold(0L){v,i->(v shl 8) or u(44+i).toLong()}}"
   next==58&&bytes>=4 -> "$base icmp6=${u(40)}"
   next==17&&bytes>=8 -> "$base udp=${u16(40)}->${u16(42)}"
   else -> base
  }
 }
}
