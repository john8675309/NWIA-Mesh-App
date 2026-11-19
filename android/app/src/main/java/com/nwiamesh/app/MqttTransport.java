package com.nwiamesh.app;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import javax.net.ssl.SSLSocketFactory;
import java.util.function.*;
import java.util.*;
import com.nwiamesh.core.NwiaEnvelope;
/** Foreground-only MQTT adapter. Separate app credentials; never reuse a gateway account. */
public final class MqttTransport implements AutoCloseable {
    public interface Listener {void status(String status);void receive(byte[] raw);}
    private final MqttAsyncClient client;private final String prefix;private final byte[] origin;
    private final Listener listener;private volatile boolean ready;
    public MqttTransport(String host,int port,String user,char[] password,String group,byte[] origin,Listener listener)throws Exception {
        if(!host.matches("[a-zA-Z0-9.-]+") || port<1 || port>65535 || !group.matches("[a-zA-Z0-9_-]{1,40}") || origin.length!=6)throw new IllegalArgumentException("Broker settings");
        this.origin=origin.clone();this.prefix="nwiamesh/v2/"+group+"/packets/";this.listener=listener;
        StringBuilder id=new StringBuilder("nwia-app-");for(byte b:origin)id.append(String.format(Locale.ROOT,"%02x",b&255));
        client=new MqttAsyncClient("ssl://"+host+":"+port,id.toString(),new MemoryPersistence());
        client.setCallback(new MqttCallback(){
            public void connectionLost(Throwable cause){ready=false;listener.status("MQTT disconnected");}
            public void deliveryComplete(IMqttDeliveryToken token){}
            public void messageArrived(String topic,MqttMessage msg){
                if(msg.isRetained())return;
                try {listener.receive(NwiaEnvelope.decode(topic,prefix,msg.getPayload(),System.currentTimeMillis()/1000));}catch(IllegalArgumentException ignored){}
            }
        });
        MqttConnectOptions options=new MqttConnectOptions();options.setUserName(user);options.setPassword(password);
        options.setSocketFactory(SSLSocketFactory.getDefault());options.setHttpsHostnameVerificationEnabled(true);
        options.setCleanSession(true);options.setAutomaticReconnect(false);options.setConnectionTimeout(10);options.setKeepAliveInterval(30);
        client.connect(options,null,new IMqttActionListener(){
            public void onSuccess(IMqttToken token){
                try {client.subscribe(prefix+"+",0,null,new IMqttActionListener(){
                    public void onSuccess(IMqttToken t){int[] codes=t.getGrantedQos();ready=codes.length==1 && codes[0]!=128;listener.status(ready?"MQTT ready":"MQTT subscribe rejected");}
                    public void onFailure(IMqttToken t,Throwable error){listener.status("MQTT subscribe failed");}
                });}catch(MqttException error){listener.status("MQTT subscribe failed");}
            }
            public void onFailure(IMqttToken token,Throwable error){listener.status("MQTT connection failed");}
        });
    }
    public boolean ready(){return ready;}
    public void send(byte[] raw,Consumer<Boolean> done) {
        if(!ready){done.accept(false);return;}
        StringBuilder hex=new StringBuilder();for(byte b:origin)hex.append(String.format(Locale.ROOT,"%02x",b&255));
        try {client.publish(prefix+hex,NwiaEnvelope.encode(origin,raw,System.currentTimeMillis()/1000),0,false,null,new IMqttActionListener(){
            public void onSuccess(IMqttToken token){done.accept(true);}public void onFailure(IMqttToken token,Throwable error){done.accept(false);}
        });}catch(Exception error){done.accept(false);}
    }
    public void close(){ready=false;try{client.disconnectForcibly(0,0,false);}catch(Exception ignored){}try{client.close(true);}catch(Exception ignored){}}
}
