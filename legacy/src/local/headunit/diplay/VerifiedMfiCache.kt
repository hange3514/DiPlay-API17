package local.headunit.diplay
import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
object VerifiedMfiCache {
 private var prepared:PreparedValue<LocalMfiAuthenticationClient>?=null
 @Synchronized private fun value(c:Context):PreparedValue<LocalMfiAuthenticationClient>{
  prepared?.let{return it};val app=c.applicationContext
  return PreparedValue{LocalMfiAuthenticationClient.load(PlatformSupport.prepareMfi(app)).also{runCatching{ConnectionPrewarm.identityStore(app).loadPairForPrewarm()?.let{LegacyTlsChannel.prepare(it)}}}}.also{prepared=it}
 }
 @JvmStatic fun warm(c:Context){runCatching{value(c).warm()}}
 @JvmStatic @Synchronized fun ready():Boolean=prepared?.completedValue()!=null
 fun get(c:Context,onSignature:(Int)->Unit)=value(c).get().withSignatureObserver(onSignature)
}
