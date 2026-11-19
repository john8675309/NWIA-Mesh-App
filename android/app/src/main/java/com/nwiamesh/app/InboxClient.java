package com.nwiamesh.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Signed HTTPS sync. Broker credentials are used only to authorize registration. */
final class InboxClient {
    private final Context context;
    private final JSONObject settings;
    private final PhoneIdentity identity;
    InboxClient(Context c,JSONObject settings,PhoneIdentity identity) {this.context=c;this.settings=settings;this.identity=identity;}
    static JSONObject settings(Context c) throws Exception {try{return new JSONObject(new String(new KeyVault(c).get("settings"),StandardCharsets.UTF_8));}catch(FileNotFoundException absent){return new JSONObject();}}
    static JSONArray array(Context c,String name) throws Exception {try{return new JSONArray(new String(new KeyVault(c).get(name),StandardCharsets.UTF_8));}catch(FileNotFoundException absent){return new JSONArray();}}
    private JSONObject request(String method,String path,JSONObject data,boolean registration) throws Exception {
        URI base=new URI(settings.optString("relayUrl","https://"+settings.getString("host")));
        if(!"https".equals(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null || !(base.getPath().isEmpty() || base.getPath().equals("/")))throw new IOException("Relay must be an HTTPS origin");
        byte[] body=data==null?new byte[0]:data.toString().getBytes(StandardCharsets.UTF_8),nonce=new byte[16];new SecureRandom().nextBytes(nonce);
        String group=settings.getString("group"),origin=settings.getString("origin"),pub=InboxPackets.hex(identity.publicKey()),stamp=Long.toString(System.currentTimeMillis()/1000),n=InboxPackets.hex(nonce);
        String audience="https://"+base.getHost().toLowerCase(java.util.Locale.ROOT)+(base.getPort()==-1||base.getPort()==443?"":":"+base.getPort());
        String signed="NWIA-INBOX-1\n"+audience+"\n"+method+"\n"+path+"\n"+group+"\n"+origin+"\n"+pub+"\n"+stamp+"\n"+n+"\n"+InboxPackets.hex(MessageDigest.getInstance("SHA-256").digest(body));
        HttpsURLConnection connection=(HttpsURLConnection)base.resolve(path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setRequestMethod(method);
        connection.setRequestProperty("X-NWIA-Audience",audience);connection.setRequestProperty("X-NWIA-Group",group);connection.setRequestProperty("X-NWIA-Origin",origin);connection.setRequestProperty("X-NWIA-Public",pub);connection.setRequestProperty("X-NWIA-Time",stamp);connection.setRequestProperty("X-NWIA-Nonce",n);connection.setRequestProperty("X-NWIA-Signature",InboxPackets.hex(identity.sign(signed.getBytes(StandardCharsets.UTF_8))));
        if(registration) {
            byte[] auth=(settings.getString("user")+":"+settings.getString("password")).getBytes(StandardCharsets.UTF_8);
            try{connection.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString(auth));}finally{Arrays.fill(auth,(byte)0);}
        }
        try {
            if(data!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(body.length);try(var output=connection.getOutputStream()){output.write(body);}}
            int code=connection.getResponseCode();if(code!=200)throw new HttpFailure(code);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[4096];
            try(var input=connection.getInputStream()){int count;while((count=input.read(chunk))!=-1){if(buffer.size()+count>65536)throw new IOException("Inbox response too large");buffer.write(chunk,0,count);}}
            return new JSONObject(buffer.toString("UTF-8"));
        } finally {Arrays.fill(body,(byte)0);connection.disconnect();}
    }
    static final class HttpFailure extends IOException {final int code;HttpFailure(int code){super("Inbox HTTP failure");this.code=code;}}
    static void sendReceipt(JSONObject settings,byte[] raw) {
        org.eclipse.paho.client.mqttv3.MqttClient client=null;char[] password=null;
        try {
            String origin=settings.getString("origin"),host=settings.getString("host"),group=settings.getString("group");
            if(!origin.matches("[a-f0-9]{12}") || !host.matches("[a-zA-Z0-9.-]+") || !group.matches("[a-zA-Z0-9_-]{1,40}"))return;
            client=new org.eclipse.paho.client.mqttv3.MqttClient("ssl://"+host+":"+settings.optInt("port",8883),"nwia-ack-"+java.util.UUID.randomUUID(),new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
            client.setTimeToWait(5000);var options=new org.eclipse.paho.client.mqttv3.MqttConnectOptions();password=settings.getString("password").toCharArray();options.setUserName(settings.getString("user"));options.setPassword(password);options.setConnectionTimeout(5);options.setCleanSession(true);options.setHttpsHostnameVerificationEnabled(true);options.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());client.connect(options);
            client.publish("nwiamesh/v2/"+group+"/packets/"+origin,com.nwiamesh.core.NwiaEnvelope.encode(InboxPackets.unhex(origin),raw,System.currentTimeMillis()/1000),0,false);
        } catch(Exception ignored) { }
        finally {if(password!=null)Arrays.fill(password,'\0');if(client!=null){try{client.disconnectForcibly(0,0,false);}catch(Exception ignored){}try{client.close();}catch(Exception ignored){}}}
    }
    int sync(String token) throws Exception {
        JSONArray channels=array(context,"nwia_channels");java.util.TreeSet<String> hashes=new java.util.TreeSet<>();
        for(int i=0;i<channels.length();i++){byte[] key=InboxPackets.unhex(channels.getJSONObject(i).getString("psk"));try{hashes.add(InboxPackets.hex(new byte[]{MessageDigest.getInstance("SHA-256").digest(key)[0]}));}finally{Arrays.fill(key,(byte)0);}}
        JSONObject registration=new JSONObject().put("revision",settings.optLong("inboxRevision")).put("enabled",settings.optBoolean("pushEnabled")).put("token",token).put("channels",new JSONArray(hashes));
        String binding=InboxPackets.hex(MessageDigest.getInstance("SHA-256").digest((settings.optString("relayUrl")+settings.getString("host")+settings.getString("group")+settings.getString("origin")+InboxPackets.hex(identity.publicKey())+registration.toString()).getBytes(StandardCharsets.UTF_8)));
        if(!binding.equals(PushInbox.binding(context))){request("POST","/v1/register",registration,true);PushInbox.registered(context,binding);}
        if(!settings.optBoolean("pushEnabled")){PushInbox.registered(context,"");return 0;}
        int fresh=0;
        for(int page=0;page<8;page++) {
            JSONObject response;
            try{response=request("GET","/v1/inbox",null,false);}catch(HttpFailure error){if(error.code==401)PushInbox.registered(context,"");throw error;}
            JSONArray items=response.getJSONArray("items");
            JSONArray contacts=array(context,"nwia_contacts");
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.getJSONObject(i);
                item.put("readable",InboxPackets.decode(identity,contacts,channels,Base64.getDecoder().decode(item.getString("raw")),item.getLong("received"))!=null);
            }
            fresh+=PushInbox.merge(context,items,response.getLong("dropped"));
            if(items.length()==0)break;
            request("POST","/v1/ack",new JSONObject().put("cursor",items.getJSONObject(items.length()-1).getLong("seq")),false);
        }
        return fresh;
    }
}
