package local.headunit.diplay
/** On the audited legacy head unit, release physical capture while Siri is speaking.
 * Preserve the negotiated uplink with paced silence. Unknown modes retain previous behavior.
 */
class SiriCapturePolicy {
 @Volatile var mode=0;private set
 fun update(params:Map<String,Any?>):Boolean {
  val states=params["appStates"] as? List<*>?:return false
  for(item in states.take(16)){
   val state=item as? Map<*,*>?:continue
   if(state["appStateID"]!=1&&state["appStateID"]!=1L)continue
   val value=state["speechMode"]
   val next=when(value){1,1L->1;2,2L->2;-1,-1L-> -1;else->return false}
   if(next==mode)return false
   mode=next;return true
  }
  return false
 }
 fun capture(supported:Boolean,audioType:String)=!supported||!audioType.equals("speechrecognition",true)||(mode!=1&&mode!= -1)
}
