#include <jni.h>
#include "monocypher/monocypher.h"
extern void *malloc(size_t);
extern void free(void *);
static void fail(JNIEnv *e,const char *type,const char *text){jclass c=(*e)->FindClass(e,type);if(c)(*e)->ThrowNew(e,c,text);}
JNIEXPORT jbyteArray JNICALL Java_local_headunit_diplay_NativeCrypto_openNative(JNIEnv *e,jclass cls,jbyteArray key,jbyteArray nonce,jbyteArray sealed,jbyteArray aad){
 (void)cls;
 if(!key||!nonce||!sealed||!aad||(*e)->GetArrayLength(e,key)!=32||(*e)->GetArrayLength(e,nonce)!=12){fail(e,"java/lang/IllegalArgumentException","Invalid AEAD parameters");return 0;}
 jsize n=(*e)->GetArrayLength(e,sealed),a=(*e)->GetArrayLength(e,aad);
 if(n<16||n>8388608||a>4096){fail(e,"java/lang/IllegalArgumentException","AEAD size outside bounds");return 0;}
 /* Monocypher authenticates before decrypting and permits identical input/output.
  * Keep one bounded native copy; MAC and AAD follow it and are not overwritten. */
 size_t size=(size_t)n+(size_t)a;uint8_t *mem=malloc(size);
 if(!mem){fail(e,"java/lang/OutOfMemoryError","AEAD buffer");return 0;}
 uint8_t k[32],iv[12];crypto_aead_ctx ctx; jbyteArray result=0;
 (*e)->GetByteArrayRegion(e,key,0,32,(jbyte*)k);(*e)->GetByteArrayRegion(e,nonce,0,12,(jbyte*)iv);
 (*e)->GetByteArrayRegion(e,sealed,0,n,(jbyte*)mem);(*e)->GetByteArrayRegion(e,aad,0,a,(jbyte*)(mem+n));
 if((*e)->ExceptionCheck(e))goto done;
 crypto_aead_init_ietf(&ctx,k,iv);
 if(crypto_aead_read(&ctx,mem,mem+n-16,mem+n,(size_t)a,mem,(size_t)n-16)!=0){fail(e,"java/security/GeneralSecurityException","AEAD authentication failed");goto done;}
 result=(*e)->NewByteArray(e,n-16);if(result)(*e)->SetByteArrayRegion(e,result,0,n-16,(jbyte*)mem);
done:
 crypto_wipe(k,sizeof k);crypto_wipe(iv,sizeof iv);crypto_wipe(&ctx,sizeof ctx);crypto_wipe(mem,size);free(mem);return result;
}
