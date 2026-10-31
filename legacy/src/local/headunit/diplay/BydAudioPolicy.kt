package local.headunit.diplay
object BydAudioPolicy {
 @JvmStatic fun supported(sdk:Int,model:String,version:Int)=sdk==17&&model.uppercase().contains("MX9A0")&&version==182
 @JvmStatic fun shouldLogCallFocusGrant(callActive:Boolean,alreadyLogged:Boolean)=callActive&&!alreadyLogged
}
