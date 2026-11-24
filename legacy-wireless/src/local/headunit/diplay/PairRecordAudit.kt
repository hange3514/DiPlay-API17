package local.headunit.diplay
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.compat.Base64Compat
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.RSADigestSigner

/** Independent BC parser/signature checks, including the device public key and private/public match. */
object PairRecordAudit {
 private fun der(pem:ByteArray):ByteArray = Base64Compat.decode(pem.toString(Charsets.US_ASCII).lineSequence().filterNot{it.startsWith("-----")}.joinToString("").filterNot{it.isWhitespace()})
 fun verify(r:LockdownPairRecord):String {
  val root=Certificate.getInstance(der(r.rootCertificatePem))
  val host=Certificate.getInstance(der(r.hostCertificatePem))
  val device=Certificate.getInstance(der(r.deviceCertificatePem))
  val rootPub=RSAPublicKey.getInstance(root.subjectPublicKeyInfo.parsePublicKey())
  for(c in listOf(root,host,device)){
   require(c.signatureAlgorithm.algorithm.id=="1.2.840.113549.1.1.11") {"Pair certificate algorithm mismatch"}
   val verifier=RSADigestSigner(SHA256Digest());verifier.init(false,RSAKeyParameters(false,rootPub.modulus,rootPub.publicExponent));val body=c.tbsCertificate.encoded;verifier.update(body,0,body.size)
   require(verifier.verifySignature(c.signature.octets)){"Pair certificate signature invalid"}
   require(c.startDate.date.before(c.endDate.date)){"Pair certificate validity invalid"}
  }
  val phonePub=RSAPublicKey.getInstance(der(r.devicePublicKeyPem))
  require(device.subjectPublicKeyInfo.parsePublicKey().toASN1Primitive()==phonePub){"Pair device public key mismatch"}
  for((cert,keyPem) in listOf(root to r.rootPrivateKeyPem,host to r.hostPrivateKeyPem)){
   val pub=RSAPublicKey.getInstance(cert.subjectPublicKeyInfo.parsePublicKey());val key=RSAPrivateKey.getInstance(PrivateKeyInfo.getInstance(der(keyPem)).parsePrivateKey())
   require(pub.modulus==key.modulus&&pub.publicExponent==key.publicExponent){"Pair private/public key mismatch"}
  }
  return "chain_verified=true serial=${root.serialNumber.value} validFromMs=${root.startDate.date.time} validToMs=${root.endDate.date.time} deviceBits=${phonePub.modulus.bitLength()}"
 }
}
