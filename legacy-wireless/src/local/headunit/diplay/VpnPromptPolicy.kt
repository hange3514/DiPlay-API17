package local.headunit.diplay

object VpnPromptPolicy {
 @JvmStatic fun window(pkg:String?,clazz:String?)=pkg=="com.android.vpndialogs"&&clazz=="com.android.vpndialogs.ConfirmDialog"
 @JvmStatic fun target(text:String,label:String,pending:Boolean)=pending&&label.isNotBlank()&&text.contains(label)
 @JvmStatic fun confirm(text:String?)=text?.trim() in setOf("确定","确认","允许","OK","Allow","Confirm","确定连接")
}
