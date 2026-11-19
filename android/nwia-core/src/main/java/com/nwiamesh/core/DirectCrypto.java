package com.nwiamesh.core;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Native-compatible MeshCore DM packets and receipts. Identity ECDH/signing is phone-only. */
public final class DirectCrypto {
    public record Outgoing(byte[] raw,byte[] expectedAck){public Outgoing{raw=raw.clone();expectedAck=expectedAck.clone();}@Override public byte[] raw(){return raw.clone();}@Override public byte[] expectedAck(){return expectedAck.clone();}}
    public record Incoming(String text,byte[] ack){public Incoming{ack=ack.clone();}@Override public byte[] ack(){return ack.clone();}}
    public static Outgoing encrypt(byte[] self,byte[] peer,byte[] secret,String text,long time)throws GeneralSecurityException {
        keys(self,peer,secret);byte[] message=text.getBytes(StandardCharsets.UTF_8);
        if(text.trim().isEmpty() || text.indexOf('\0')>=0 || message.length>150)throw new IllegalArgumentException("DM text size");
        byte[] plain=ByteBuffer.allocate(5+message.length).order(ByteOrder.LITTLE_ENDIAN).putInt((int)time).put((byte)0).put(message).array();
        try{return new Outgoing(datagram(2,self,peer,secret,plain),ack(plain,self));}finally{Arrays.fill(plain,(byte)0);}
    }
    public static byte[] datagram(int type,byte[] self,byte[] peer,byte[] secret,byte[] plain)throws GeneralSecurityException {
        keys(self,peer,secret);if(type!=2 && type!=8)throw new IllegalArgumentException("Type");if(plain.length<1 || plain.length>176)throw new IllegalArgumentException("Plain bounds");
        byte[] padded=Arrays.copyOf(plain,((plain.length+15)/16)*16);
        try{Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,0,16,"AES"));byte[] cipher=aes.doFinal(padded);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));byte[] auth=mac.doFinal(cipher);byte[] raw=new byte[6+cipher.length];raw[0]=(byte)((type<<2)|1);raw[1]=0;raw[2]=peer[0];raw[3]=self[0];raw[4]=auth[0];raw[5]=auth[1];System.arraycopy(cipher,0,raw,6,cipher.length);return raw;}finally{Arrays.fill(padded,(byte)0);}
    }
    public static byte[] decrypt(byte[] self,byte[] peer,byte[] secret,byte[] raw)throws GeneralSecurityException {
        keys(self,peer,secret);int pos=ChannelCrypto.payloadOffset(raw),type=(raw[0]>>2)&15,size=raw.length-pos;
        if((type!=2 && type!=8) || size<20 || (size-4)%16!=0 || raw[pos]!=self[0] || raw[pos+1]!=peer[0])throw new GeneralSecurityException("Other peer/type");
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));mac.update(raw,pos+4,size-4);byte[] auth=mac.doFinal();
        if(!MessageDigest.isEqual(Arrays.copyOf(auth,2),Arrays.copyOfRange(raw,pos+2,pos+4)))throw new GeneralSecurityException("DM MAC");
        Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,0,16,"AES"));return aes.doFinal(raw,pos+4,size-4);
    }
    public static Incoming text(byte[] plain,byte[] sender,long now)throws GeneralSecurityException {
        if(plain.length<6 || (plain[4]>>2)!=0)throw new GeneralSecurityException("DM format");
        long time=Integer.toUnsignedLong(ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).getInt());
        if(time>now+30 || time+300<now)throw new GeneralSecurityException("Stale DM");
        int end=5;while(end<plain.length && plain[end]!=0)end++;
        if(end==5)throw new GeneralSecurityException("Empty DM");
        return new Incoming(new String(plain,5,end-5,StandardCharsets.UTF_8),ack(Arrays.copyOf(plain,end),sender));
    }
    public static byte[] pathAck(byte[] plain){
        if(plain.length<6)throw new IllegalArgumentException("Path return");int path=plain[0]&255,size=(path>>6)+1,count=path&63,offset=1+size*count;
        if(size>3 || size*count>64 || offset+5>plain.length || (plain[offset]&15)!=3)throw new IllegalArgumentException("No path ACK");return Arrays.copyOfRange(plain,offset+1,offset+5);
    }
    /** Native 4-byte ACK matching is not a signed read receipt. */
    public static byte[] rawAck(byte[] raw){int p=ChannelCrypto.payloadOffset(raw),type=(raw[0]>>2)&15;if(type==10){if(raw.length-p<5 || (raw[p]&15)!=3)throw new IllegalArgumentException("Multipart ACK");p++;}else if(type!=3)throw new IllegalArgumentException("ACK type");if(raw.length-p<4)throw new IllegalArgumentException("ACK");return Arrays.copyOfRange(raw,p,p+4);}
    public static byte[] floodAck(byte[] ack){if(ack.length!=4)throw new IllegalArgumentException();byte[] raw=new byte[6];raw[0]=13;raw[1]=0;System.arraycopy(ack,0,raw,2,4);return raw;}
    public static byte[] advertMessage(byte[] pub,String name,long time){
        if(pub.length!=32)throw new IllegalArgumentException();byte[] n=name.getBytes(StandardCharsets.UTF_8);if(n.length<1 || n.length>31)throw new IllegalArgumentException("Advert name");
        return ByteBuffer.allocate(37+n.length).order(ByteOrder.LITTLE_ENDIAN).put(pub).putInt((int)time).put((byte)0x81).put(n).array();
    }
    public static byte[] advert(byte[] message,byte[] signature){if(message.length<38 || message.length>68 || signature.length!=64)throw new IllegalArgumentException();byte[] raw=new byte[message.length+66];raw[0]=17;raw[1]=0;System.arraycopy(message,0,raw,2,36);System.arraycopy(signature,0,raw,38,64);System.arraycopy(message,36,raw,102,message.length-36);return raw;}
    public static byte[] ack(byte[] plain,byte[] sender)throws NoSuchAlgorithmException {MessageDigest d=MessageDigest.getInstance("SHA-256");d.update(plain);return Arrays.copyOf(d.digest(sender),4);}
    private static void keys(byte[] self,byte[] peer,byte[] secret){if(self.length!=32 || peer.length!=32 || secret.length!=32)throw new IllegalArgumentException("DM key sizes");}
}
