package local.headunit.diplay
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.compat.Base64Compat
import java.net.*
import java.security.*
import java.security.cert.*
import javax.net.ssl.*
import java.util.concurrent.*

/** Synthetic, local mutual TLS 1.2 interoperability check, never uses a real phone identity. */
object TlsSelfTest {
 fun run(){
  val generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048)
  val key=generator.generateKeyPair().public as java.security.interfaces.RSAPublicKey
  val der=org.bouncycastle.asn1.pkcs.RSAPublicKey(key.modulus,key.publicExponent).encoded
  val pem="-----BEGIN RSA PUBLIC KEY-----\n"+Base64Compat.encode(der)+"\n-----END RSA PUBLIC KEY-----\n"
  var record=LockdownPairRecordGenerator.generate(pem.toByteArray(Charsets.US_ASCII),"00:00:00:00:00:00","selftest-host","selftest-buid")
  val raw=record.rootPrivateKeyPem.toString(Charsets.US_ASCII).replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","")
  val priv=KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64Compat.decodeMime(raw.toByteArray(Charsets.US_ASCII))))
  // The real Lockdown profile has an empty issuer, which desktop JSSE rejects.
  // Give only this synthetic loopback certificate a name; production pairing stays unchanged.
  val certPem=record.rootCertificatePem.toString(Charsets.US_ASCII).replace("-----BEGIN CERTIFICATE-----","").replace("-----END CERTIFICATE-----","")
  val original=org.bouncycastle.asn1.ASN1Sequence.getInstance(Base64Compat.decodeMime(certPem.toByteArray(Charsets.US_ASCII)))
  val tbs=org.bouncycastle.asn1.ASN1Sequence.getInstance(original.getObjectAt(0));val v=org.bouncycastle.asn1.ASN1EncodableVector()
  for(i in 0 until tbs.size())v.add(if(i==3||i==5)org.bouncycastle.asn1.x500.X500Name("CN=DiPlay local self test")else tbs.getObjectAt(i))
  val named=org.bouncycastle.asn1.DERSequence(v);val signer=Signature.getInstance("SHA256withRSA");signer.initSign(priv);signer.update(named.encoded)
  val certificateDer=org.bouncycastle.asn1.DERSequence(arrayOf(named,original.getObjectAt(1),org.bouncycastle.asn1.DERBitString(signer.sign()))).encoded
  val certificatePem=("-----BEGIN CERTIFICATE-----\n"+Base64Compat.encode(certificateDer)+"\n-----END CERTIFICATE-----\n").toByteArray(Charsets.US_ASCII)
  record=LockdownPairRecord.restore(record.hostId,record.systemBuid,record.wifiMacAddress,record.devicePublicKeyPem,record.deviceCertificatePem,record.hostPrivateKeyPem,record.hostCertificatePem,record.rootPrivateKeyPem,certificatePem)
  val cert=CertificateFactory.getInstance("X.509").generateCertificate(certificateDer.inputStream())
  val password="test".toCharArray();val store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,password);store.setKeyEntry("test",priv,password,arrayOf(cert))
  val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password)
  val trust=object:X509TrustManager {override fun getAcceptedIssuers()=emptyArray<X509Certificate>();override fun checkClientTrusted(c:Array<out X509Certificate>,a:String){};override fun checkServerTrusted(c:Array<out X509Certificate>,a:String){}}
  val ctx=SSLContext.getInstance("TLS");ctx.init(km.keyManagers,arrayOf(trust),SecureRandom())
  val server=ctx.serverSocketFactory.createServerSocket(0,1,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
  server.enabledProtocols=arrayOf("TLSv1.2");server.needClientAuth=true;server.soTimeout=15000
  val executor=Executors.newSingleThreadExecutor();var accepted:SSLSocket?=null
  val future=executor.submit(Callable {val peer=server.accept() as SSLSocket;accepted=peer;try{val it=peer;it.soTimeout=15000;it.startHandshake();check(it.session.peerCertificates.isNotEmpty());val body=ByteArray(32768);var at=0;while(at<body.size){val n=it.inputStream.read(body,at,body.size-at);check(n>0);at+=n};it.outputStream.write(body);it.outputStream.flush()}finally{peer.close()};true})
  LegacyTlsChannel.prepare(record)
  LegacyTlsChannel.prepare(record)
  check(LegacyTlsChannel.client(record)!==LegacyTlsChannel.client(record)){"Only immutable identity material may be cached, not TLS client state"}
  val socket=Socket();try{
   socket.connect(InetSocketAddress("127.0.0.1",server.localPort),5000)
   val wire=object:BlockingDuplexByteStream {override fun send(data:ByteArray){socket.outputStream.write(data);socket.outputStream.flush()};override fun recv(maxBytes:Int,timeoutMillis:Long):ByteArray?{socket.soTimeout=timeoutMillis.coerceIn(1,15000).toInt();val b=ByteArray(maxBytes);return try{val n=socket.inputStream.read(b);if(n<0)ByteArray(0)else b.copyOf(n)}catch(_:SocketTimeoutException){null}};override fun close(){socket.close()}}
   LegacyTlsChannel.open(wire,record,15000).use {tls->check(tls.recv(16,30)==null){"Idle receive must time out"};val body=ByteArray(32768){(it*37).toByte()};tls.send(body);val received=java.io.ByteArrayOutputStream();while(received.size()<body.size){val b=tls.recv(2000,10000)?:error("TLS echo timeout");check(b.isNotEmpty());received.write(b)};check(body.contentEquals(received.toByteArray()))}
   check(future.get(15,TimeUnit.SECONDS))
  }finally{socket.close();server.close();accepted?.close();executor.shutdownNow()}
 }
}
