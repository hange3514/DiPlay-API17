package local.headunit.diplay

/** Stateful causal 2x interpolation of PCM16LE. Preserves duration across packet boundaries. */
class PcmDoubleRate(private val channels:Int) {
 private val previous=IntArray(channels)
 private var initialized=false
 init {require(channels in 1..2)}
 fun reset(){initialized=false}
 fun convert(input:ByteArray):ByteArray {
  require(input.size%(channels*2)==0&&input.size<=1024*1024)
  val out=ByteArray(input.size*2)
  var target=0
  for(frame in input.indices step channels*2){
   for(channel in 0 until channels){
    val offset=frame+channel*2
    val value=((input[offset].toInt() and 255) or (input[offset+1].toInt() shl 8)).toShort().toInt()
    val midpoint=if(initialized)(previous[channel]+value)/2 else value
    out[target+channel*2]=midpoint.toByte();out[target+channel*2+1]=(midpoint shr 8).toByte()
    out[target+channels*2+channel*2]=value.toByte();out[target+channels*2+channel*2+1]=(value shr 8).toByte()
    previous[channel]=value
   }
   target+=channels*4;initialized=true
  }
  return out
 }
}
