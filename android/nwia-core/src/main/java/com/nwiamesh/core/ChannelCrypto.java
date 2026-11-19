package com.nwiamesh.core;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.nio.charset.StandardCharsets;
/** Native MeshCore channel text compatibility; retains upstream AES-ECB and 16-bit MAC limitations.
 * Not a new cryptographic design and not strong individual sender authentication. */
public final class ChannelCrypto {
    private static byte[] expanded(byte[] key) {
        if(key.length!=16 && key.length!=32)throw new IllegalArgumentException("Channel key must be 16 or 32 bytes");
        return Arrays.copyOf(key,32);
    }
    public static byte[] encrypt(byte[] key,String name,String text,long timestamp) throws GeneralSecurityException {
        if(timestamp<1700000000L || timestamp>0xffffffffL)throw new IllegalArgumentException("Clock");
        if(name.trim().isEmpty() || name.length()>24 || name.indexOf('\0')>=0 || text.trim().isEmpty() || text.indexOf('\0')>=0)throw new IllegalArgumentException("Name/text");
        byte[] message=(name+": "+text).getBytes(StandardCharsets.UTF_8);
        if(message.length>150)throw new IllegalArgumentException("Maximum 150 UTF-8 bytes including sender name");
        byte[] plain=new byte[((5+message.length+15)/16)*16];
        for(int i=0;i<4;i++)plain[i]=(byte)(timestamp>>(8*i));System.arraycopy(message,0,plain,5,message.length);
        byte[] secret=expanded(key);
        try {
            Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,0,16,"AES"));
            byte[] encrypted=aes.doFinal(plain);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            byte[] auth=mac.doFinal(encrypted);byte[] raw=new byte[5+encrypted.length];raw[0]=21;raw[1]=0;
            raw[2]=MessageDigest.getInstance("SHA-256").digest(key)[0];raw[3]=auth[0];raw[4]=auth[1];
            System.arraycopy(encrypted,0,raw,5,encrypted.length);return raw;
        } finally {Arrays.fill(secret,(byte)0);Arrays.fill(plain,(byte)0);Arrays.fill(message,(byte)0);}
    }
    public record Incoming(String text,long timestamp) {}
    public static String decrypt(byte[] key,byte[] raw) throws GeneralSecurityException {return decryptMessage(key,raw).text();}
    public static Incoming decryptMessage(byte[] key,byte[] raw) throws GeneralSecurityException {
        int offset=payloadOffset(raw);if(((raw[0]>>2)&15)!=5)throw new IllegalArgumentException("Not channel text");
        if(raw.length-offset<19 || (raw.length-offset-3)%16!=0)throw new IllegalArgumentException("Ciphertext size");
        if(raw[offset]!=MessageDigest.getInstance("SHA-256").digest(key)[0])throw new GeneralSecurityException("Different channel");
        byte[] secret=expanded(key),plain=null;
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update(raw,offset+3,raw.length-offset-3);byte[] expected=mac.doFinal();
            if(!MessageDigest.isEqual(Arrays.copyOf(expected,2),Arrays.copyOfRange(raw,offset+1,offset+3)))throw new GeneralSecurityException("MAC");
            Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,0,16,"AES"));plain=aes.doFinal(raw,offset+3,raw.length-offset-3);
            if(plain.length<6 || plain[4]!=0)throw new GeneralSecurityException("Unsupported text");
            int end=5;while(end<plain.length && plain[end]!=0)end++;
            long timestamp=0;for(int i=0;i<4;i++)timestamp|=(long)(plain[i]&255)<<(8*i);
            return new Incoming(new String(plain,5,end-5,StandardCharsets.UTF_8),timestamp);
        } finally {Arrays.fill(secret,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    public static int payloadOffset(byte[] raw) {
        if(raw.length<3 || raw.length>255 || (raw[0]&0xc0)!=0)throw new IllegalArgumentException("Packet");
        int route=raw[0]&3,pos=(route==0 || route==3)?5:1;
        if(pos>=raw.length)throw new IllegalArgumentException("Route");int path=raw[pos]&255,size=(path>>6)+1;
        int bytes=(path&63)*size,offset=pos+1+bytes;
        if(size>3 || bytes>64 || offset>=raw.length)throw new IllegalArgumentException("Path");return offset;
    }
    public static String id(byte[] raw) {int offset=payloadOffset(raw);return DeliveryEngine.messageId((raw[0]>>2)&15,Arrays.copyOfRange(raw,offset,raw.length));}
}
