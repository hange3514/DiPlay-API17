package local.headunit.diplay

import com.shilapi.xcertplay.transport.Ntb16Codec
import org.junit.Assert.*
import org.junit.Test

class NtbAllocationBoundsTest {
 private fun u16(bytes:ByteArray,offset:Int,value:Int){bytes[offset]=value.toByte();bytes[offset+1]=(value ushr 8).toByte()}
 private fun u16(bytes:ByteArray,offset:Int)= (bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8)
 @Test fun rangeBuildMatchesCopiedFrameAndPadsBoundaryWithOneAllocation(){
  val source=ByteArray(510){(it*7).toByte()};val expected=Ntb16Codec.build(source.copyOfRange(13,497),9)
  val actual=Ntb16Codec.build(source,13,484,9)
  assertArrayEquals(expected,actual);assertEquals(513,actual.size);assertEquals(512,u16(actual,8))
 }
 @Test fun rangeBuilderMatchesReferenceAcrossLengthFieldAndUsbBoundaries(){
  for((index,length) in listOf(1,57,483,484,485,995,996,997,1500,1501,65507).withIndex()){
   val source=ByteArray(length+19){((it*31+index) and 255).toByte()};val offset=11
   val reference=Ntb16Codec.build(source.copyOfRange(offset,offset+length),index)
   val optimized=Ntb16Codec.build(source,offset,length,index)
   assertArrayEquals("length=$length",reference,optimized);assertEquals(28+length,u16(optimized,8))
  }
 }
 @Test fun rangeBuildRejectsInvalidBounds(){
  val source=ByteArray(100)
  for((offset,length) in listOf(-1 to 60,0 to 0,50 to 60,0 to -1)){
   try{Ntb16Codec.build(source,offset,length,1);fail("accepted invalid range $offset/$length")}catch(_:IllegalArgumentException){}
  }
 }
 @Test fun cyclicTableDoesNotDuplicateTheSameFrame(){
  val frame=ByteArray(1500){it.toByte()};val block=Ntb16Codec.build(frame,1)
  u16(block,18,12)
  val result=Ntb16Codec.parse(block)
  assertEquals(1,result.size);assertArrayEquals(frame,result.single())
 }
 @Test fun overlappingEntriesCannotExpandBeyondInputCapacity(){
  val block=ByteArray(60000);val prefix=Ntb16Codec.build(byteArrayOf(1),1);prefix.copyInto(block)
  u16(block,8,block.size);u16(block,16,2048)
  var entry=20
  while(entry+4<=2060){u16(block,entry,3000);u16(block,entry+2,28000);entry+=4}
  val result=Ntb16Codec.parse(block)
  assertEquals(2,result.size);assertTrue(result.sumOf{it.size}<=block.size)
 }
 @Test fun excessiveTinyEntriesStayWithinQueueCapacity(){
  val block=ByteArray(6000);Ntb16Codec.build(byteArrayOf(7),1).copyInto(block)
  u16(block,8,block.size);u16(block,16,2048)
  var entry=20
  while(entry+4<=2060){u16(block,entry,3000);u16(block,entry+2,1);entry+=4}
  assertEquals(256,Ntb16Codec.parse(block).size)
 }
 @Test fun absentAndOutOfBoundsTablesAreRejectedAtNonzeroOffset(){
  val valid=Ntb16Codec.build(ByteArray(60){3},1)
  val container=ByteArray(valid.size+17);valid.copyInto(container,17)
  u16(container,27,0);assertTrue(Ntb16Codec.parse(container,17,valid.size).isEmpty())
  u16(container,27,12);u16(container,33,0xffff);assertTrue(Ntb16Codec.parse(container,17,valid.size).isEmpty())
 }
}
