package local.headunit.diplay

/** Aggregate amplitude only; no speech samples are retained or uploaded. */
class PcmEvidence {
 var peak=0;private set
 var nonzero=0L;private set
 var candidatePeakLittleEndian=0;private set
 var candidatePeakBigEndian=0;private set
 var candidateMeanAbsLittleEndian=0L;private set
 var candidateMeanAbsBigEndian=0L;private set
 var candidateRmsLittleEndian=0L;private set
 var candidateRmsBigEndian=0L;private set
 var zeroSamplesLittleEndian=0L;private set
 var zeroSamplesBigEndian=0L;private set
 private var candidateSamples=0L
 private var candidateSquaresLittleEndian=0L;private var candidateSquaresBigEndian=0L
 fun observe(pcm:ByteArray){
  require(pcm.size%2==0)
  for(i in pcm.indices step 2){val value=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort().toInt();val amplitude=kotlin.math.abs(value);if(amplitude>peak)peak=amplitude;if(value!=0)nonzero++}
 }
 /** Summarizes both possible byte interpretations of CarPlay LPCM without retaining audio. */
 fun observeByteOrderCandidates(wirePcm:ByteArray){
  require(wirePcm.size%2==0)
  var littleSum=0L;var bigSum=0L;var littleSquares=0L;var bigSquares=0L;var littleZeros=0L;var bigZeros=0L;var count=0L
  for(i in wirePcm.indices step 2){
   val little=((wirePcm[i].toInt() and 255) or (wirePcm[i+1].toInt() shl 8)).toShort().toInt()
   val big=((wirePcm[i+1].toInt() and 255) or (wirePcm[i].toInt() shl 8)).toShort().toInt()
   val littleAbs=kotlin.math.abs(little).toLong();val bigAbs=kotlin.math.abs(big).toLong()
   littleSquares+=little.toLong()*little.toLong();bigSquares+=big.toLong()*big.toLong()
   if(little==0)littleZeros++;if(big==0)bigZeros++
   if(littleAbs>candidatePeakLittleEndian)candidatePeakLittleEndian=littleAbs.toInt()
   if(bigAbs>candidatePeakBigEndian)candidatePeakBigEndian=bigAbs.toInt()
   littleSum+=littleAbs;bigSum+=bigAbs;count++
  }
  if(count>0){val total=candidateSamples+count;candidateMeanAbsLittleEndian=(candidateMeanAbsLittleEndian*candidateSamples+littleSum)/total;candidateMeanAbsBigEndian=(candidateMeanAbsBigEndian*candidateSamples+bigSum)/total;candidateSquaresLittleEndian+=littleSquares;candidateSquaresBigEndian+=bigSquares;candidateRmsLittleEndian=kotlin.math.sqrt(candidateSquaresLittleEndian.toDouble()/total).toLong();candidateRmsBigEndian=kotlin.math.sqrt(candidateSquaresBigEndian.toDouble()/total).toLong();zeroSamplesLittleEndian+=littleZeros;zeroSamplesBigEndian+=bigZeros;candidateSamples=total}
 }
 fun byteOrderCandidateSummary()="pcm16Candidates{littlePeak=$candidatePeakLittleEndian,littleMeanAbs=$candidateMeanAbsLittleEndian,littleRms=$candidateRmsLittleEndian,littleZero=$zeroSamplesLittleEndian,bigPeak=$candidatePeakBigEndian,bigMeanAbs=$candidateMeanAbsBigEndian,bigRms=$candidateRmsBigEndian,bigZero=$zeroSamplesBigEndian,samples=$candidateSamples}"
 companion object {
  /** Duplicate little-endian mono samples without changing rate, duration or amplitude. */
  fun stereo(mono:ByteArray):ByteArray {
   require(mono.size%2==0&&mono.size<=1024*1024)
   val out=ByteArray(mono.size*2)
   var j=0
   for(i in mono.indices step 2){out[j++]=mono[i];out[j++]=mono[i+1];out[j++]=mono[i];out[j++]=mono[i+1]}
   return out
  }
 }
}
