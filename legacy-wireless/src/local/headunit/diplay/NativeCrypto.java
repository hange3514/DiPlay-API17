package local.headunit.diplay;
import java.security.GeneralSecurityException;
/** Monocypher 4.0.2 IETF AEAD, for the high-volume video path only. */
public final class NativeCrypto {
 public static final boolean available;
 static {boolean ok;try{System.loadLibrary("diplayusb");ok=true;}catch(UnsatisfiedLinkError e){ok=false;}available=ok;}
 public static byte[] open(byte[] key,byte[] nonce,byte[] sealed,byte[] aad)throws GeneralSecurityException{return openNative(key,nonce,sealed,aad);}
 private static native byte[] openNative(byte[] key,byte[] nonce,byte[] sealed,byte[] aad)throws GeneralSecurityException;
 private NativeCrypto(){}
}
