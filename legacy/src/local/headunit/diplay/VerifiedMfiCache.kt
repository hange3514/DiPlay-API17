package local.headunit.diplay
import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
object VerifiedMfiCache {
 private var prepared:PreparedValue<LocalMfiAuthenticationClient>?=null
 @Synchronized private fun value(c:Context):PreparedValue<LocalMfiAuthenticationClient>{
  prepared?.let{return it};val app=c.applicationContext
  return PreparedValue{LocalMfiAuthenticationClient.load(PlatformSupport.prepareMfi(app)).also{runCatching{IdentityStore(app).loadPair()?.let{LegacyTlsChannel.prepare(it)}}}}.also{prepared=it}
 }
 @JvmStatic fun warm(c:Context){value(c).warm()}
 fun get(c:Context,onSignature:(Int)->Unit)=value(c).get().withSignatureObserver(onSignature)
}
