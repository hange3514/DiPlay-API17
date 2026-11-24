package local.headunit.diplay
/** Numeric/boolean routing metadata only; never phone numbers, text or audio payloads. */
object AudioModeEvidence {
 fun describe(params:Map<String,Any?>):String {
  val keys=setOf("resourceID","entity","owner","appStateID","speechMode","state","mainAudio","screen","phoneCall","turnByTurn","speech","transferType")
  fun scalar(m:Map<*,*>):String=m.entries.filter{it.key in keys&&(it.value is Number||it.value is Boolean)}.take(16).joinToString(","){"${it.key}=${it.value}"}
  val parts=mutableListOf(scalar(params))
  for(k in listOf("resources","appStates")){val items=params[k] as? List<*>?:continue;parts.add(k+"="+items.take(8).filterIsInstance<Map<*,*>>().joinToString(";"){scalar(it)})}
  return parts.filter{it.isNotEmpty()}.joinToString(" ").take(1000)
 }
}
