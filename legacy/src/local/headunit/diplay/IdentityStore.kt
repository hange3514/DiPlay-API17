package local.headunit.diplay
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.util.UUID

class IdentityStore(context:Context) {
 companion object {private var cachedRaw:String?=null;private var cachedHost:String?=null;private var cachedPair:LockdownPairRecord?=null}
 private val prefs=context.getSharedPreferences("private_pairing",0)
 @Volatile private var cachedIdentity:AirPlayIdentity?=null
 @Volatile private var cachedPairings:PairingStore?=null
 init {synchronized(Companion){if(prefs.getInt("pairProfile",0)<2){val edit=prefs.edit().putInt("pairProfile",2);if(!prefs.contains("lockdown"))edit.putString("hostId",UUID.randomUUID().toString().uppercase(java.util.Locale.US));check(edit.commit())}}}
 private fun stable(k:String):String = synchronized(Companion) {val old=prefs.getString(k,null);if(old!=null)return@synchronized old;val v=UUID.randomUUID().toString();check(prefs.edit().putString(k,v).commit());v}
 val hostId=stable("hostId");val buid=stable("buid")
 private fun enc(b:ByteArray)=Base64.encodeToString(b,Base64.NO_WRAP)
 private fun dec(s:String)=Base64.decode(s,Base64.DEFAULT)
 @Synchronized fun identity():AirPlayIdentity {cachedIdentity?.let{return it};val raw=prefs.getString("identity",null);val value=if(raw!=null){val j=JSONObject(raw);AirPlayIdentity(dec(j.getString("private")),dec(j.getString("public")),j.getString("id"))}else{AirPlayIdentity.generate().also{check(prefs.edit().putString("identity",JSONObject().put("private",enc(it.privateKey)).put("public",enc(it.publicKey)).put("id",it.pairingId).toString()).commit())}};cachedIdentity=value;return value}
 @Synchronized fun pairings():PairingStore {cachedPairings?.let{return it};val store=PairingStore{key,value->val name="peer_"+enc(key.toByteArray(Charsets.UTF_8));val encoded=enc(value);if(prefs.getString(name,null)!=encoded)check(prefs.edit().putString(name,encoded).commit())};for((key,value) in prefs.all)if(key.startsWith("peer_")&&value is String)store.save(String(dec(key.substring(5)),Charsets.UTF_8),dec(value));cachedPairings=store;return store}
 fun warm(){identity();pairings();loadPairForPrewarm()}

 internal fun loadPairForPrewarm():LockdownPairRecord? =
  prefs.getString("lastLockdownPeer",null)?.let(::loadPair) ?: loadLegacyPair()

 internal fun loadLegacyPair():LockdownPairRecord? = loadPair("lockdown")

 internal fun loadPair(key:String):LockdownPairRecord? = synchronized(Companion) {
  val raw=prefs.getString(key,null)?:return@synchronized null
  val host=hostId+":"+buid
  if(cachedRaw==raw&&cachedHost==host)return@synchronized cachedPair
  val pair=runCatching {
   val j=JSONObject(raw)
   LockdownPairRecord.restore(hostId,buid,j.getString("wifi"),dec(j.getString("devicePublic")),
    dec(j.getString("deviceCert")),dec(j.getString("hostPrivate")),dec(j.getString("hostCert")),
    dec(j.getString("rootPrivate")),dec(j.getString("rootCert")))
  }.getOrNull()
  cachedRaw=raw;cachedHost=host;cachedPair=pair
  pair
 }

 internal fun clearPair(key:String){check(prefs.edit().remove(key).commit())}

 internal fun savePair(key:String,r:LockdownPairRecord){
  val j=JSONObject().put("wifi",r.wifiMacAddress).put("devicePublic",enc(r.devicePublicKeyPem))
   .put("deviceCert",enc(r.deviceCertificatePem)).put("hostPrivate",enc(r.hostPrivateKeyPem))
   .put("hostCert",enc(r.hostCertificatePem)).put("rootPrivate",enc(r.rootPrivateKeyPem))
   .put("rootCert",enc(r.rootCertificatePem))
  check(prefs.edit().putString(key,j.toString()).putString("lastLockdownPeer",key).commit())
 }
}
