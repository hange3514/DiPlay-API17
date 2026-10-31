package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class NcmCarrierGateTest {
 @Test fun notificationReleasesWithoutFixedDelay(){var t=0L;NcmCarrierGate.await({t>=100},{false},{},{t},{t+=25});assertEquals(100,t)}
 @Test fun inboundReleasesWithoutCarrier(){var t=0L;NcmCarrierGate.await({false},{t>=75},{},{t},{t+=25});assertEquals(75,t)}
 @Test fun absentNotificationDoesNotBlockForever(){var t=0L;NcmCarrierGate.await({false},{false},{},{t},{t+=25});assertEquals(5000,t)}
 @Test fun cancellationWinsEvenWhenCarrierAlreadyTrue(){var calls=0;try{NcmCarrierGate.await({true},{true},{calls++;throw IllegalStateException("closed")},{0},{fail("No wait after close")});fail()}catch(_:IllegalStateException){};assertEquals(1,calls)}
}
