package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import com.shilapi.xcertplay.media.MediaCodecSupport
class MediaConversionTest {
 @Test fun doubleRatePreservesChannelsAndSignedAmplitude(){val input=byteArrayOf(0,0,0,0, -1,127,0,-128);val output=PcmDoubleRate(2).convert(input);val values=ByteBuffer.wrap(output).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();val actual=ShortArray(values.remaining());values.get(actual);assertArrayEquals(shortArrayOf(0,0,0,0,16383,-16384,32767,-32768),actual)}
 @Test fun doubleRatePacketBoundariesMatchContinuous(){val random=java.util.Random(28);val input=ByteArray(4000);random.nextBytes(input);val reference=PcmDoubleRate(2).convert(input);val converter=PcmDoubleRate(2);val out=java.io.ByteArrayOutputStream();for(n in 0 until 1000 step 25){out.write(converter.convert(input.copyOfRange(n*4,(n+25)*4)))};assertArrayEquals(reference,out.toByteArray());assertEquals(8000,reference.size)}
 @Test fun doubleRateDoesNotInventSoundInSilence(){assertArrayEquals(ByteArray(960),PcmDoubleRate(1).convert(ByteArray(480)))}
 @Test(expected=IllegalArgumentException::class) fun doubleRateRejectsPartialStereoFrame(){PcmDoubleRate(2).convert(byteArrayOf(0,0))}

 @Test fun monoStereoRetainsSignedSamplesAndDuration(){val mono=byteArrayOf(0,0,1,0,0,-128,-1,127);val stereo=PcmEvidence.stereo(mono);assertArrayEquals(byteArrayOf(0,0,0,0,1,0,1,0,0,-128,0,-128,-1,127,-1,127),stereo);val e=PcmEvidence();e.observe(stereo);assertEquals(32768,e.peak);assertEquals(6L,e.nonzero)}
 @Test fun silenceIsDistinguishedFromWrittenAudio(){val e=PcmEvidence();e.observe(ByteArray(512));assertEquals(0,e.peak);assertEquals(0L,e.nonzero)}
 @Test fun wirePcmByteOrderCandidatesAreMeasuredWithoutRetainingSamples(){val e=PcmEvidence();e.observeByteOrderCandidates(byteArrayOf(0x12,0x34,0x23,0x45));assertEquals(0x2345,e.candidatePeakBigEndian);assertEquals(0x4523,e.candidatePeakLittleEndian);assertTrue(e.byteOrderCandidateSummary().contains("samples=2"))}
 @Test fun carPlayMicrophoneConversionIsExplicitlyUplinkAndDoesNotMutateCapture(){val captured=byteArrayOf(0x12,0x34,0x23,0x45);val copy=captured.copyOf();val wire=com.shilapi.xcertplay.airplay.MicrophonePacketizer.toCarPlayMicrophoneWirePcm(captured);assertArrayEquals(byteArrayOf(0x34,0x12,0x45,0x23),wire);assertArrayEquals(copy,captured)}
 @Test fun airplayDownlinkPcm16IsConvertedFromWireBigEndianToAndroidLittleEndian(){val wire=byteArrayOf(0x34,0x12,0x45,0x23);val copy=wire.copyOf();assertArrayEquals(byteArrayOf(0x12,0x34,0x23,0x45),com.shilapi.xcertplay.airplay.MicrophonePacketizer.toAndroidPcm16(wire));assertArrayEquals(copy,wire)}
 @Test(expected=IllegalArgumentException::class) fun airplayDownlinkPcm16RejectsPartialSample(){com.shilapi.xcertplay.airplay.MicrophonePacketizer.toAndroidPcm16(byteArrayOf(1))}
 @Test fun realCallEvidenceAggregatesBothByteOrdersAcrossPacketBoundaries(){val e=PcmEvidence();val a=byteArrayOf(0,64,0,64);val b=byteArrayOf(0,64);e.observeByteOrderCandidates(a);e.observeByteOrderCandidates(b);assertEquals(16384,e.candidatePeakLittleEndian);assertEquals(64,e.candidatePeakBigEndian);assertEquals(16384,e.candidateRmsLittleEndian);assertEquals(64,e.candidateRmsBigEndian);assertEquals(0L,e.zeroSamplesLittleEndian);assertEquals(0L,e.zeroSamplesBigEndian);assertTrue(e.byteOrderCandidateSummary().contains("samples=3"))}
 @Test(expected=IllegalArgumentException::class) fun rejectPartialPcmSample(){PcmEvidence.stereo(byteArrayOf(0))}
 @Test fun directWriterMatchesReferenceWithoutModifyingInput(){val random=java.util.Random(26);repeat(100){val input=java.io.ByteArrayOutputStream();repeat(1+random.nextInt(10)){val n=2+random.nextInt(500);input.write(byteArrayOf((n shr 24).toByte(),(n shr 16).toByte(),(n shr 8).toByte(),n.toByte()));val payload=ByteArray(n);random.nextBytes(payload);input.write(payload)};val bytes=input.toByteArray();val copy=bytes.copyOf();val out=ByteBuffer.allocate(bytes.size);assertEquals(bytes.size,VideoBufferWriter.putAnnexB(bytes,out));assertArrayEquals(MediaCodecSupport.toAnnexB(bytes),out.array());assertArrayEquals(copy,bytes)}}
 @Test fun lengthWithStartCodePrefixIsStillConverted(){val bytes=ByteArray(261);bytes[2]=1;bytes[3]=1;bytes[4]=0x65;val out=ByteBuffer.allocate(bytes.size);VideoBufferWriter.putAnnexB(bytes,out);assertEquals(0,out.array()[2].toInt());assertEquals(1,out.array()[3].toInt());assertEquals(0x65,out.array()[4].toInt())}
 @Test(expected=IllegalArgumentException::class) fun rejectTruncatedNal(){VideoBufferWriter.putAnnexB(byteArrayOf(0,0,0,5,2),ByteBuffer.allocate(5))}
 @Test(expected=IllegalArgumentException::class) fun rejectInsufficientBuffer(){VideoBufferWriter.putAnnexB(byteArrayOf(0,0,0,2,4,5),ByteBuffer.allocate(5))}
}
