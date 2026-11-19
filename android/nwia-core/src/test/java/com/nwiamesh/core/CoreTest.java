package com.nwiamesh.core;
import java.util.*;
import static com.nwiamesh.core.DeliveryEngine.*;
public final class CoreTest {
    static int checks;
    static void check(boolean b) { checks++; if(!b) throw new AssertionError("Check "+checks); }
    static DeliveryEngine engine(ChannelPolicy p) { return new DeliveryEngine(1000,5000,3000,2000,p,8); }
    static void state(DeliveryEngine e,String id,State s) { check(e.status(id).state()==s); }
    static byte[] packet={21,0,1,2,3};
    public static void main(String[] args) throws Exception {
        var e=engine(ChannelPolicy.ON_RADIO_FAILURE);
        var action=e.submit("a",packet,Kind.DIRECT,true,0).get(0);
        check(action.route()==Route.COMPANION); packet[0]=99; check(action.ciphertext()[0]==21);
        check(e.tick(999).isEmpty()); check(e.radioResult("a",true,500).isEmpty());
        state(e,"a",State.RADIO_WAITING_ACK); check(e.tick(5499).isEmpty());
        var fallback=e.tick(5500); check(fallback.size()==1 && fallback.get(0).route()==Route.MQTT);
        check(Arrays.equals(action.ciphertext(),fallback.get(0).ciphertext()));
        e.radioResult("a",false,5501); check(e.tick(5501).isEmpty());
        e.mqttResult("a",true); state(e,"a",State.MQTT_SENT);
        e.recipientReceipt("a"); e.mqttResult("a",false); state(e,"a",State.DELIVERED);
        check(e.tick(Long.MAX_VALUE).isEmpty());
        e=engine(ChannelPolicy.ON_RADIO_FAILURE);
        e.submit("b",packet,Kind.DIRECT,true,0); e.recipientReceipt("b");
        check(e.tick(9000).isEmpty()); e.radioResult("b",false,9000); state(e,"b",State.DELIVERED);
        e.submit("c",packet,Kind.CHANNEL,true,0); e.radioResult("c",true,100);
        check(e.tick(9000).isEmpty()); state(e,"c",State.RADIO_TRANSMITTED);
        e.submit("d",packet,Kind.DIRECT,false,0); state(e,"d",State.MQTT_PENDING);
        check(e.tick(3000).isEmpty()); state(e,"d",State.FAILED); check(e.tick(9999).isEmpty());
        e.submit("f",packet,Kind.DIRECT,true,10000); check(e.companionDisconnected(10001).size()==1);
        check(e.companionDisconnected(10002).isEmpty());
        check(e.submit("f",packet,Kind.DIRECT,true,10003).isEmpty());
        try {e.submit("f",new byte[]{0},Kind.DIRECT,true,10003); throw new AssertionError();} catch(IllegalArgumentException expected){checks++;}
        e=engine(ChannelPolicy.DELAYED_MQTT); e.submit("g",packet,Kind.CHANNEL,true,0); e.radioResult("g",true,10);
        check(e.tick(2009).isEmpty()); check(e.tick(2010).size()==1); check(e.tick(2011).isEmpty());
        e=engine(ChannelPolicy.ON_RADIO_FAILURE); e.submit("h",packet,Kind.DIRECT,true,0);
        check(e.tick(1000).size()==1); e.radioResult("h",true,1001); state(e,"h",State.MQTT_PENDING);
        for(int n=0;n<7;n++) e.submit("capacity"+n,packet,Kind.CHANNEL,false,0);
        try{e.submit("full",packet,Kind.CHANNEL,false,0);throw new AssertionError();}catch(IllegalStateException expected){checks++;}
        check(!e.archive("h")); e.recipientReceipt("h"); check(e.archive("h"));
        check(messageId(5,packet).equals(messageId(5,packet))); check(!messageId(2,packet).equals(messageId(5,packet)));
        Random random=new Random(421);
        for(int n=0;n<=255;n++) {
            byte[] data=new byte[n]; random.nextBytes(data);
            byte[] encoded=ModemProtocol.encode(new ModemProtocol.Frame(2,0xffffffffL,data));
            for(int chunk=1;chunk<=23;chunk++) {
                var decoder=new ModemProtocol.Decoder(); var frames=new ArrayList<ModemProtocol.Frame>();
                for(int pos=0;pos<encoded.length;pos+=chunk) frames.addAll(decoder.feed(Arrays.copyOfRange(encoded,pos,Math.min(encoded.length,pos+chunk))));
                check(frames.size()==1); check(frames.get(0).requestId()==0xffffffffL); check(Arrays.equals(data,frames.get(0).data()));
            }
        }
        try {new ModemProtocol.Decoder().feed(new byte[]{127,127});throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        check(ModemProtocol.isKeylessCaps(new ModemProtocol.Frame(0x81,0,new byte[]{'N','W','A','1',1})));

        byte[] channelKey=new byte[16];for(int i=0;i<16;i++)channelKey[i]=(byte)(i+1);
        byte[] raw=ChannelCrypto.encrypt(channelKey,"Test","hi",1800000000);
        StringBuilder golden=new StringBuilder();for(byte b:raw)golden.append(String.format("%02x",b&255));
        check(golden.toString().equals("15005d8e133860a047ee3f8f4fb1e3a42b9f57e93a"));
        check(ChannelCrypto.decrypt(channelKey,raw).equals("Test: hi"));
        check(ChannelCrypto.decryptMessage(channelKey,raw).timestamp()==1800000000L);
        byte[] routed=new byte[raw.length+2];routed[0]=raw[0];routed[1]=2;routed[2]=17;routed[3]=34;System.arraycopy(raw,2,routed,4,raw.length-2);
        check(ChannelCrypto.id(raw).equals(ChannelCrypto.id(routed)));check(ChannelCrypto.decrypt(channelKey,routed).equals("Test: hi"));
        byte[] wrong=channelKey.clone();wrong[0]^=1;
        try{ChannelCrypto.decrypt(wrong,raw);throw new AssertionError();}catch(java.security.GeneralSecurityException expected){checks++;}
        byte[] tampered=raw.clone();tampered[5]^=1;
        try{ChannelCrypto.decrypt(channelKey,tampered);throw new AssertionError();}catch(java.security.GeneralSecurityException expected){checks++;}
        byte[] origin={2,0,0,0,0,1};String prefix="nwiamesh/v2/test/packets/",topic=prefix+"020000000001";
        byte[] envelope=NwiaEnvelope.encode(origin,raw,1800000000);
        check(Arrays.equals(raw,NwiaEnvelope.decode(topic,prefix,envelope,1800000001)));
        try{NwiaEnvelope.decode(topic,prefix,envelope,1800000060);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        try{NwiaEnvelope.decode(prefix+"020000000002",prefix,envelope,1800000001);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        Random fuzz=new Random(84);for(int n=0;n<100000;n++){byte[] bytes=new byte[fuzz.nextInt(300)];fuzz.nextBytes(bytes);try{ChannelCrypto.payloadOffset(bytes);}catch(IllegalArgumentException expected){}try{NwiaEnvelope.decode(topic,prefix,bytes,1800000001);}catch(IllegalArgumentException expected){}}
        checks+=100000;

        byte[] dmSelf=java.util.HexFormat.of().parseHex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"),dmPeer=java.util.HexFormat.of().parseHex("712651f450ba05b63898b99ef5f7ba45632e8e2527f7f715cd671ec4024cc51e"),dmSecret=java.util.HexFormat.of().parseHex("d4d437eefc3ec319428e8335882266905bb9addf91ccc1e1d7f3c46300741c70");
        DirectCrypto.Outgoing dm=DirectCrypto.encrypt(dmSelf,dmPeer,dmSecret,"NWIA DM vector",1800000000);
        check(java.util.HexFormat.of().formatHex(dm.raw()).equals("09007103d7d8bc9ee8a9a5ae975a34cf180a5d70a0b3eb9f6bf81efe834f1cc4a9894d2e0df9"));
        check(java.util.HexFormat.of().formatHex(dm.expectedAck()).equals("7404549b"));
        byte[] recoveredDM=DirectCrypto.decrypt(dmPeer,dmSelf,dmSecret,dm.raw());
        check(DirectCrypto.text(recoveredDM,dmSelf,1800000001).text().equals("NWIA DM vector"));
        check(Arrays.equals(DirectCrypto.text(recoveredDM,dmSelf,1800000001).ack(),dm.expectedAck()));
        check(Arrays.equals(dm.expectedAck(),DirectCrypto.rawAck(DirectCrypto.floodAck(dm.expectedAck()))));
        byte[] multipart={42,0,0x23,0x74,4,0x54,(byte)0x9b};check(Arrays.equals(dm.expectedAck(),DirectCrypto.rawAck(multipart)));
        byte[] pathPlain=new byte[]{2,17,34,3,0x74,4,0x54,(byte)0x9b,0,0};check(Arrays.equals(dm.expectedAck(),DirectCrypto.pathAck(pathPlain)));
        byte[] pathPacket=DirectCrypto.datagram(8,dmPeer,dmSelf,dmSecret,pathPlain);check(Arrays.equals(dm.expectedAck(),DirectCrypto.pathAck(DirectCrypto.decrypt(dmSelf,dmPeer,dmSecret,pathPacket))));
        try{DirectCrypto.text(recoveredDM,dmSelf,1800000400);throw new AssertionError();}catch(java.security.GeneralSecurityException expected){checks++;}
        for(int n=0;n<10000;n++){byte[] bytes=new byte[fuzz.nextInt(300)];fuzz.nextBytes(bytes);try{DirectCrypto.decrypt(dmSelf,dmPeer,dmSecret,bytes);}catch(IllegalArgumentException|java.security.GeneralSecurityException expected){}try{DirectCrypto.pathAck(bytes);}catch(IllegalArgumentException expected){}}
        checks+=10000;
        System.out.println("PASS "+checks+" checks: failover, late ACK/callback races, bounded outbox, MQTT is not delivery, channel policies, defensive copies, fragmented modem frames, native MeshCore crypto vector, route-independent IDs, TLS envelope bounds/fuzz");
    }
}
