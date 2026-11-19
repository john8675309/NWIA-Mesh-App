package com.nwiamesh.core;
import java.nio.*;
import java.util.*;
public final class NwiaEnvelope {
    public static byte[] encode(byte[] origin,byte[] raw,long now) {
        if(origin.length!=6 || now<1700000000L || now>0xffffffffL-60)throw new IllegalArgumentException("Origin/clock");
        ChannelCrypto.payloadOffset(raw);
        ByteBuffer b=ByteBuffer.allocate(20+raw.length);b.put(new byte[]{'N','W','I','2'}).put(origin).putInt((int)now).putInt((int)(now+60)).putShort((short)raw.length).put(raw);return b.array();
    }
    public static byte[] decode(String topic,String prefix,byte[] frame,long now) {
        if(now<1700000000L || frame.length<23 || frame.length>275 || !topic.startsWith(prefix))throw new IllegalArgumentException("Envelope");
        ByteBuffer b=ByteBuffer.wrap(frame);byte[] magic=new byte[4];b.get(magic);
        if(!Arrays.equals(magic,new byte[]{'N','W','I','2'}))throw new IllegalArgumentException("Version");
        byte[] origin=new byte[6];b.get(origin);StringBuilder hex=new StringBuilder();for(byte item:origin)hex.append(String.format(Locale.ROOT,"%02x",item&255));
        if(!topic.equals(prefix+hex))throw new IllegalArgumentException("Topic binding");
        long created=Integer.toUnsignedLong(b.getInt()),expiry=Integer.toUnsignedLong(b.getInt());int len=Short.toUnsignedInt(b.getShort());
        if(created>now+2 || expiry<=now || expiry-created!=60 || len!=b.remaining())throw new IllegalArgumentException("Expired/invalid");
        byte[] raw=new byte[len];b.get(raw);ChannelCrypto.payloadOffset(raw);return raw;
    }
}
