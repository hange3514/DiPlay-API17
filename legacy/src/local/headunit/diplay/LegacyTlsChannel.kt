package local.headunit.diplay

import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.compat.Base64Compat
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.*
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateFactory

/** USB Lockdown only. Never used for HTTPS: pairing owns the device trust relationship. */
class LegacyTlsChannel private constructor(private val underlying:BlockingDuplexByteStream):BlockingDuplexByteStream {
 private val protocol=TlsClientProtocol()
 private val state=Any();private val reader=Any()
 @Volatile private var closed=false
 private fun flushLocked(){while(protocol.availableOutputBytes>0){val b=ByteArray(minOf(4096,protocol.availableOutputBytes));val n=protocol.readOutput(b,0,b.size);check(n>0);underlying.send(if(n==b.size)b else b.copyOf(n))}}
 override fun send(data:ByteArray){try{synchronized(state){check(!closed);protocol.writeApplicationData(data,0,data.size);flushLocked()}}catch(e:Exception){close();throw e}}
 override fun recv(maxBytes:Int,timeoutMillis:Long):ByteArray?=synchronized(reader) receive@{
  require(maxBytes in 1..1048576&&timeoutMillis>0);val end=System.nanoTime()+timeoutMillis*1000000
  try{while(true){synchronized(state){if(protocol.availableInputBytes>0){val b=ByteArray(minOf(maxBytes,protocol.availableInputBytes));val n=protocol.readInput(b,0,b.size);return@receive if(n==b.size)b else b.copyOf(n)};if(closed||protocol.isClosed)return@receive ByteArray(0)}
   val remaining=(end-System.nanoTime())/1000000;if(remaining<=0)return@receive null
   val bytes=underlying.recv(16384,remaining.coerceAtLeast(1))?:return@receive null
   if(bytes.isEmpty()){close();return@receive ByteArray(0)}
   synchronized(state){if(closed)return@receive ByteArray(0);protocol.offerInput(bytes);flushLocked()}
  };null}catch(e:Exception){close();throw e}
 }
 override fun close(){if(closed)return;closed=true;underlying.close()}
 companion object {
  private class Material(val certificate:ByteArray,val key:org.bouncycastle.crypto.params.AsymmetricKeyParameter)
  // A single immutable pairing identity, never a live TLS connection/session or challenge.
  // IdentityStore returns the same immutable record until pairing changes.
  private var cachedRecord:LockdownPairRecord?=null
  private var cachedMaterial:Material?=null
  private val random=SecureRandom()
  @Synchronized private fun material(record:LockdownPairRecord):Material {
   if(cachedRecord===record)return cachedMaterial!!
   val certPem=record.rootCertificatePem.toString(Charsets.US_ASCII).replace("-----BEGIN CERTIFICATE-----","").replace("-----END CERTIFICATE-----","")
   val certificate=Base64Compat.decodeMime(certPem.toByteArray(Charsets.US_ASCII))
   val raw=record.rootPrivateKeyPem.toString(Charsets.US_ASCII).replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","")
   val encoded=Base64Compat.decodeMime(raw.toByteArray(Charsets.US_ASCII));val key=try{PrivateKeyFactory.createKey(encoded)}finally{encoded.fill(0)}
   return Material(certificate,key).also{cachedMaterial=it;cachedRecord=record}
  }
  fun prepare(record:LockdownPairRecord){material(record);random.nextBytes(ByteArray(1))}
  fun client(record:LockdownPairRecord):DefaultTlsClient {
   val crypto=BcTlsCrypto(random)
   val material=material(record);val certificate=material.certificate;val key=material.key
   return object:DefaultTlsClient(crypto){
    override fun getSupportedVersions()=arrayOf(ProtocolVersion.TLSv12,ProtocolVersion.TLSv11,ProtocolVersion.TLSv10)
    override fun getAuthentication()=object:TlsAuthentication {
     override fun notifyServerCertificate(certificate:TlsServerCertificate){require(!certificate.certificate.isEmpty)}
     override fun getClientCredentials(request:CertificateRequest):TlsCredentials? {
      if(request.certificateTypes?.contains(ClientCertificateType.rsa_sign)!=true)return null
      val algorithm=if(TlsUtils.isTLSv12(context)){val supported=request.supportedSignatureAlgorithms;listOf(HashAlgorithm.sha256,HashAlgorithm.sha384,HashAlgorithm.sha512,HashAlgorithm.sha1).map{SignatureAndHashAlgorithm(it,SignatureAlgorithm.rsa)}.firstOrNull{supported==null||supported.contains(it)}?:throw IOException("No compatible USB TLS RSA signature")}else null
      return BcDefaultTlsCredentialedSigner(TlsCryptoParameters(context),crypto,key,Certificate(arrayOf(crypto.createCertificate(certificate))),algorithm)
     }
    }
   }
  }
  fun open(underlying:BlockingDuplexByteStream,pairRecord:LockdownPairRecord,handshakeTimeoutMillis:Long):LegacyTlsChannel {
   require(handshakeTimeoutMillis in 1..90000)
   val c=LegacyTlsChannel(underlying);val end=System.nanoTime()+handshakeTimeoutMillis*1000000
   try{c.protocol.connect(client(pairRecord));c.flushLocked();while(!c.protocol.isConnected){if(c.protocol.isClosed)throw IOException("USB TLS handshake closed");val remaining=(end-System.nanoTime())/1000000;if(remaining<=0)throw IOException("USB TLS handshake timed out");val bytes=underlying.recv(16384,remaining.coerceAtLeast(1))?:continue;if(bytes.isEmpty())throw IOException("USB TLS peer closed");c.protocol.offerInput(bytes);c.flushLocked()};return c}catch(e:Throwable){runCatching{c.close()};throw e}
  }
 }
}
