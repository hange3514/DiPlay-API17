package local.headunit.diplay

/** A persistent RTP stream may send digital silence between spoken prompts. */
class NavigationActivity {
 private var lastSignal:Long?=null
 fun packet(pcm:ByteArray,nowNs:Long):Boolean {if(pcm.any{it.toInt()!=0})lastSignal=nowNs;return active(nowNs)}
 fun active(nowNs:Long):Boolean=lastSignal?.let{nowNs-it in 0 until 500_000_000L}?:false
}
