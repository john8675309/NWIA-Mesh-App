package com.nwiamesh.app;
import android.content.Context;
import java.util.*;
import java.security.*;
import java.io.*;
public final class PhoneIdentity implements AutoCloseable {
    private final byte[] encoded;
    private PhoneIdentity(byte[] encoded){if(encoded.length!=96)throw new IllegalArgumentException("Identity format");this.encoded=encoded;}
    public static PhoneIdentity load(Context context)throws GeneralSecurityException,IOException {
        KeyVault vault=new KeyVault(context);
        try{return new PhoneIdentity(vault.get("identity"));}
        catch(FileNotFoundException absent){
            byte[] seed=new byte[32],identity=null;SecureRandom rng=new SecureRandom();
            try{do{if(identity!=null)Arrays.fill(identity,(byte)0);rng.nextBytes(seed);identity=NativeCrypto.keypair(seed);}while(identity[64]==0 || (identity[64]&255)==255);vault.put("identity",identity);return new PhoneIdentity(identity);}
            finally{Arrays.fill(seed,(byte)0);}
        }
    }
    public byte[] publicKey(){return Arrays.copyOfRange(encoded,64,96);}
    public byte[] secret(byte[] peer){byte[] privateKey=Arrays.copyOf(encoded,64);try{return NativeCrypto.sharedSecret(privateKey,peer);}finally{Arrays.fill(privateKey,(byte)0);}}
    public byte[] sign(byte[] data){byte[] privateKey=Arrays.copyOf(encoded,64);try{return NativeCrypto.sign(privateKey,publicKey(),data);}finally{Arrays.fill(privateKey,(byte)0);}}
    public void close(){Arrays.fill(encoded,(byte)0);}
}
