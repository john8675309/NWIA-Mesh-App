#include <jni.h>
#include <stdint.h>
#include <cstring>
#include <ed_25519.h>
#include <Ed25519.h>
#include <Crypto.h>
static bool bytes(JNIEnv* e,jbyteArray a,uint8_t* out,size_t n){if(!a || e->GetArrayLength(a)!=int(n)){e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),"Crypto input length");return false;}e->GetByteArrayRegion(a,0,n,reinterpret_cast<jbyte*>(out));return !e->ExceptionCheck();}
static jbyteArray result(JNIEnv* e,const uint8_t* data,size_t n){auto a=e->NewByteArray(n);if(a)e->SetByteArrayRegion(a,0,n,reinterpret_cast<const jbyte*>(data));return a;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_nwiamesh_app_NativeCrypto_keypair(JNIEnv* e,jclass,jbyteArray seedArray){
 uint8_t seed[32]{},identity[96]{};if(!bytes(e,seedArray,seed,32))return nullptr;
 ed25519_create_keypair(identity+64,identity,seed);auto out=result(e,identity,96);clean(seed,sizeof(seed));clean(identity,sizeof(identity));return out;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_nwiamesh_app_NativeCrypto_sharedSecret(JNIEnv* e,jclass,jbyteArray privateArray,jbyteArray publicArray){
 uint8_t secret[32]{},prv[64]{},pub[32]{};jbyteArray out=nullptr;
 if(bytes(e,privateArray,prv,64) && bytes(e,publicArray,pub,32)){ed25519_key_exchange(secret,pub,prv);uint8_t nonzero=0;for(auto b:secret)nonzero|=b;if(nonzero)out=result(e,secret,32);else e->ThrowNew(e->FindClass("java/security/GeneralSecurityException"),"Invalid peer shared secret");}
 clean(prv,sizeof(prv));clean(secret,sizeof(secret));return out;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_nwiamesh_app_NativeCrypto_sign(JNIEnv* e,jclass,jbyteArray privateArray,jbyteArray publicArray,jbyteArray message){
 uint8_t prv[64]{},pub[32]{},data[512]{},signature[64]{};jbyteArray out=nullptr;int len=message?e->GetArrayLength(message):-1;
 if(len<0 || len>512){e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),"Signature message length");return nullptr;}
 if(bytes(e,privateArray,prv,64) && bytes(e,publicArray,pub,32) && bytes(e,message,data,len)){ed25519_sign(signature,data,len,pub,prv);out=result(e,signature,64);}clean(prv,sizeof(prv));clean(data,sizeof(data));return out;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_nwiamesh_app_NativeCrypto_verify(JNIEnv* e,jclass,jbyteArray publicArray,jbyteArray message,jbyteArray sigArray){
 uint8_t pub[32]{},data[512]{},signature[64]{};int len=message?e->GetArrayLength(message):-1;
 if(len<0 || len>512){e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),"Signature message length");return false;}
 if(!bytes(e,publicArray,pub,32) || !bytes(e,message,data,len) || !bytes(e,sigArray,signature,64))return false;
 // Use upstream's rweather verification, not its disabled ref10 verify path.
 return Ed25519::verify(signature,pub,data,len);
}
