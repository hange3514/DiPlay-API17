package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class NcmLinkNotificationTest {
 @Test fun capturedCarrierPacketAndDisconnect(){
  val b=byteArrayOf(0xa1.toByte(),0,1,0,4,0,0,0)
  assertEquals(true,NcmLinkNotification.connected(b,8))
  b[2]=0;assertEquals(false,NcmLinkNotification.connected(b,8))
 }
 @Test fun truncatedAndOtherNotificationsCannotDeclareCarrier(){
  val b=byteArrayOf(0xa1.toByte(),0,1,0,4,0,0,0)
  assertNull(NcmLinkNotification.connected(b,7));assertNull(NcmLinkNotification.connected(b,9))
  b[1]=0x2a;assertNull(NcmLinkNotification.connected(b,8))
  b[1]=0;b[6]=8;assertNull(NcmLinkNotification.connected(b,8))
 }
}
