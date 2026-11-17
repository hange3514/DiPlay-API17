package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class StartupUsbWriteTest {
 private fun nd():ByteArray=ByteArray(86).also{it[12]=0x86.toByte();it[13]=0xdd.toByte();it[14]=0x60;it[19]=32;it[20]=58;it[54]=135.toByte()}
 @Test fun onlySmallNeighborMessagesReplay(){assertTrue(StartupUsbWrite.retryable(nd(),112));assertFalse(StartupUsbWrite.retryable(nd(),513));val tcp=nd();tcp[20]=6;assertFalse(StartupUsbWrite.retryable(tcp,112));val other=nd();other[54]=128.toByte();assertFalse(StartupUsbWrite.retryable(other,112));assertFalse(StartupUsbWrite.retryable(ByteArray(20),60))}
 @Test fun retryabilityUsesOnlyTheRequestedFrameRange(){val source=ByteArray(101);nd().copyInto(source,15);assertTrue(StartupUsbWrite.retryable(source,15,86,112));assertFalse(StartupUsbWrite.retryable(source,0,86,112))}
 @Test fun retriesTransientThenStopsOnSuccess(){var calls=0;var waits=0;assertEquals(112,StartupUsbWrite.write(true,2000,{assertEquals(150,it);if(++calls<3)-110 else 112},{},{waits++}));assertEquals(3,calls);assertEquals(2,waits)}
 @Test fun timeoutHasFiniteAttemptBound(){var calls=0;StartupUsbWrite.write(true,2000,{calls++;-110},{},{});assertEquals(8,calls)}
 @Test fun disconnectAndPartialWritesAreNeverReplayed(){for(result in listOf(-108,-19,40)){var calls=0;assertEquals(result,StartupUsbWrite.write(true,2000,{calls++;result},{},{}));assertEquals(1,calls)}}
 @Test fun mediaTimeoutIsNotReplayed(){var calls=0;StartupUsbWrite.write(false,2000,{assertEquals(2000,it);calls++;-110},{},{});assertEquals(1,calls)}
 @Test fun cancellationPreventsAnotherTransfer(){var calls=0;var checks=0;try{StartupUsbWrite.write(true,2000,{calls++;-110},{if(++checks==2)throw IllegalStateException("closed")},{});fail()}catch(_:IllegalStateException){};assertEquals(1,calls)}
 @Test fun malformedDiscoveryIsNotRetried(){val p=nd();p[19]=3;assertFalse(StartupUsbWrite.retryable(p,112));p[19]=33;assertFalse(StartupUsbWrite.retryable(p,112));p[19]=32;p[55]=1;assertFalse(StartupUsbWrite.retryable(p,112))}
}
