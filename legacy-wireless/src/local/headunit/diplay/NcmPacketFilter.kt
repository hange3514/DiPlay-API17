package local.headunit.diplay

/** CDC SET_ETHERNET_PACKET_FILTER: directed, broadcast and all multicast (IPv6 NDP).
 * Optional requests may stall on an Apple firmware: one bounded attempt, no reset/retry.
 * Matches Linux usbnet_cdc_update_filter's multicast-enabled receive mode. */
object NcmPacketFilter {
 fun configure(controlInterface:Int,transfer:(Int,Int,Int,Int,Int)->Int):Int {
  require(controlInterface in 0..255)
  return transfer(0x21,0x43,0x0e,controlInterface,250)
 }
}
