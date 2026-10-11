package local.headunit.diplay
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.security.MessageDigest
import java.util.UUID

class IdentityStore(context:Context) {
 companion object {
  private const val LEGACY_PAIR_KEY="lockdown"
  private const val DEVICE_PAIR_PREFIX="lockdown_device_"
  private const val LAST_PAIR_KEY="lockdown_last_device"
 }
 private val prefs=context.getSharedPreferences("private_pairing",0)
 init {synchronized(Companion){if(prefs.getInt("pairProfile",0)<3||!prefs.contains("hostId")){val edit=prefs.edit().putInt("pairProfile",3);if(!prefs.contains("hostId"))edit.putString("hostId",UUID.randomUUID().toString().uppercase(java.util.Locale.US));check(edit.commit())}}}
 private fun stable(k:String):String = synchronized(Companion) {val old=prefs.getString(k,null);if(old!=null)return@synchronized old;val v=UUID.randomUUID().toString();check(prefs.edit().putString(k,v).commit());v}
 val hostId=stable("hostId");val buid=stable("buid")
 private fun enc(b:ByteArray)=Base64.encodeToString(b,Base64.NO_WRAP)
 private fun dec(s:String)=Base64.decode(s,Base64.DEFAULT)
 @Synchronized fun identity():AirPlayIdentity {val raw=prefs.getString("identity",null);if(raw!=null){val j=JSONObject(raw);return AirPlayIdentity(dec(j.getString("private")),dec(j.getString("public")),j.getString("id"))};val i=AirPlayIdentity.generate();check(prefs.edit().putString("identity",JSONObject().put("private",enc(i.privateKey)).put("public",enc(i.publicKey)).put("id",i.pairingId).toString()).commit());return i}
 fun pairings():PairingStore {val store=PairingStore{key,value->val name="peer_"+enc(key.toByteArray(Charsets.UTF_8));val encoded=enc(value);if(prefs.getString(name,null)!=encoded)check(prefs.edit().putString(name,encoded).commit())};for((key,value) in prefs.all)if(key.startsWith("peer_")&&value is String)store.save(String(dec(key.substring(5)),Charsets.UTF_8),dec(value));return store}
 /** Reads every phone record, keeping the last successful phone first. The legacy singleton record
  * remains readable and is retained for rollback to 0.34. */
 fun loadPairs():List<LockdownPairRecord> = synchronized(Companion) {
  val byDevice=LinkedHashMap<String,LockdownPairRecord>()
  for((name,value) in prefs.all){
   if(!name.startsWith(DEVICE_PAIR_PREFIX)||value !is String)continue
   val pair=restorePairOrNull(value)?:continue
   val expected=devicePairPreferenceKey(pair.devicePublicKeyPem)
   if(expected==name)byDevice[expected]=pair
  }
  val legacy=prefs.getString(LEGACY_PAIR_KEY,null)?.let(::restorePairOrNull)
  if(legacy!=null){val key=devicePairPreferenceKey(legacy.devicePublicKeyPem);if(!byDevice.containsKey(key))byDevice[key]=legacy}
  val last=prefs.getString(LAST_PAIR_KEY,null)
  byDevice.entries.sortedWith(compareByDescending<Map.Entry<String,LockdownPairRecord>>{it.key==last}.thenBy{it.key}).map{it.value}
 }
 fun loadPair():LockdownPairRecord? = loadPairs().firstOrNull()
 /** Writes an additional phone record without replacing the first-version rollback record. */
 fun savePair(r:LockdownPairRecord) = synchronized(Companion) {savePairLocked(r)}
 /** Promotes a successful match so the most recently used phone is checked first next time. */
 fun markPairUsed(r:LockdownPairRecord) = synchronized(Companion) {savePairLocked(r)}
 private fun savePairLocked(r:LockdownPairRecord){
  val raw=encodePair(r);val key=devicePairPreferenceKey(r.devicePublicKeyPem);val edit=prefs.edit().putString(key,raw).putString(LAST_PAIR_KEY,key)
  val legacy=prefs.getString(LEGACY_PAIR_KEY,null)?.let(::restorePairOrNull)
  if(legacy==null)edit.putString(LEGACY_PAIR_KEY,raw)
  check(edit.commit())
 }
 private fun restorePairOrNull(raw:String):LockdownPairRecord? = try {
  val j=JSONObject(raw)
  LockdownPairRecord.restore(hostId,buid,j.getString("wifi"),dec(j.getString("devicePublic")),dec(j.getString("deviceCert")),dec(j.getString("hostPrivate")),dec(j.getString("hostCert")),dec(j.getString("rootPrivate")),dec(j.getString("rootCert")))
 } catch(_:Exception){null}
 private fun encodePair(r:LockdownPairRecord):String = JSONObject().put("wifi",r.wifiMacAddress).put("devicePublic",enc(r.devicePublicKeyPem)).put("deviceCert",enc(r.deviceCertificatePem)).put("hostPrivate",enc(r.hostPrivateKeyPem)).put("hostCert",enc(r.hostCertificatePem)).put("rootPrivate",enc(r.rootPrivateKeyPem)).put("rootCert",enc(r.rootCertificatePem)).toString()
 private fun devicePairPreferenceKey(devicePublicKey:ByteArray):String = DEVICE_PAIR_PREFIX+Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(devicePublicKey),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
