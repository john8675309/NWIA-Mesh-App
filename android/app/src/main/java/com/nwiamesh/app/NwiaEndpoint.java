package com.nwiamesh.app;

import android.content.Context;
import android.os.*;
import android.bluetooth.BluetoothDevice;
import com.nwiamesh.core.*;
import static com.nwiamesh.core.DeliveryEngine.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Phone-local endpoint. Stock command frames terminate HERE, never at BLE/MQTT. */
public final class NwiaEndpoint implements AutoCloseable {
 public interface Listener {void event(Map<String,Object> value);}
 private final Context context;private final Listener listener;private final KeyVault vault;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final PhoneIdentity identity;private final JSONObject settings;
 private JSONArray contacts,channels;private BleTransport ble;private MqttTransport mqtt;
 private final ArrayDeque<byte[]> inbox=new ArrayDeque<>();
 private final Map<String,Map<String,Object>> meta=new HashMap<>();
 private final Map<String,String> expected=new HashMap<>();private final Map<String,Long> deadlines=new HashMap<>(),seen=new HashMap<>();
 private final Map<String,State> lastStates=new HashMap<>();private boolean closed,mqttEnabled,radioEnabled;private long nextMqttAttempt,nextRadioAttempt;
 private DeliveryEngine engine=new DeliveryEngine(10000,30000,15000,5000,ChannelPolicy.ON_RADIO_FAILURE,128);
 public NwiaEndpoint(Context context,Listener listener)throws Exception {
  this.context=context;this.listener=listener;vault=new KeyVault(context);identity=PhoneIdentity.load(context);
  JSONObject loaded;try{loaded=new JSONObject(new String(vault.get("settings"),StandardCharsets.UTF_8));}catch(FileNotFoundException absent){loaded=new JSONObject();loaded.put("name","NWIA Mesh");loaded.put("channel_key",hex(Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==")));loaded.put("port",8883);}
  settings=loaded;contacts=readArray("nwia_contacts");channels=readArray("nwia_channels");
  if(contacts.length()==0 && settings.optString("peer").length()==64){JSONObject peer=new JSONObject();peer.put("pub",settings.getString("peer"));peer.put("name","Mesh contact");peer.put("type",1);contacts.put(peer);saveArray("nwia_contacts",contacts);}
  if(channels.length()==0 && settings.optString("channel_key").length()>=32){JSONObject channel=new JSONObject();channel.put("index",0);channel.put("name","Public");channel.put("psk",settings.getString("channel_key"));channels.put(channel);saveArray("nwia_channels",channels);}
  ble=new BleTransport(context,new BleTransport.Listener(){public void status(String text){NwiaEndpoint.this.status(text);}public void receive(byte[] raw){main.post(()->receiveRaw(raw));}public void disconnected(){if(!closed){dispatch(engine.companionDisconnected(SystemClock.elapsedRealtime()));publishStates();status("Companion disconnected");}}});
  main.post(tick);
  try{if(settings.optBoolean("pushEnabled")){InboxSyncWorker.periodic(context,true);InboxSyncWorker.schedule(context);}}catch(Exception ignored){PushInbox.failed(context);}
  main.post(()->{if(closed)return;try{if(!settings.optString("host").isEmpty())connectMqtt();}catch(Exception ignored){status("MQTT unavailable");}try{if(!settings.optString("bleAddress").isEmpty())connectBle(settings.getString("bleAddress"));}catch(Exception ignored){status("Companion unavailable");}});
 }
 private final Runnable tick=new Runnable(){public void run(){if(closed)return;dispatch(engine.tick(SystemClock.elapsedRealtime()));publishStates();archiveExpired();reconnectTransports();main.postDelayed(this,250);}};
 private JSONArray readArray(String name)throws Exception{try{return new JSONArray(new String(vault.get(name),StandardCharsets.UTF_8));}catch(FileNotFoundException absent){return new JSONArray();}}
 private void saveArray(String name,JSONArray array)throws Exception{byte[] b=array.toString().getBytes(StandardCharsets.UTF_8);try{vault.put(name,b);}finally{Arrays.fill(b,(byte)0);}}
 public Map<String,Object> profile(){Map<String,Object> r=new HashMap<>();r.put("publicKey",hex(identity.publicKey()));r.put("name",settings.optString("name","NWIA Mesh"));r.put("host",settings.optString("host"));r.put("port",settings.optInt("port",8883));r.put("group",settings.optString("group"));r.put("user",settings.optString("user"));r.put("origin",settings.optString("origin"));r.put("hasPassword",!settings.optString("password").isEmpty());r.put("pushEnabled",settings.optBoolean("pushEnabled"));r.put("relayUrl",settings.optString("relayUrl",settings.optString("host").isEmpty()?"":"https://"+settings.optString("host")));try{JSONObject status=PushInbox.status(context);r.put("inboxPending",status.getInt("pending"));r.put("inboxReady",status.optBoolean("ready")&&!status.optBoolean("error"));r.put("inboxDropped",status.optLong("dropped"));r.put("inboxLastSync",status.optLong("lastSync"));}catch(Exception ignored){r.put("inboxReady",false);}return r;}
 public void configure(Map<String,Object> values)throws Exception{if(values.containsKey("pushEnabled")||values.containsKey("relayUrl"))settings.put("inboxRevision",settings.optLong("inboxRevision")+1);for(String key:new String[]{"name","host","port","group","user","origin","relayUrl","pushEnabled"})if(values.containsKey(key))settings.put(key,values.get(key));Object password=values.get("password");if(password instanceof String && !((String)password).isEmpty())settings.put("password",password);byte[] b=settings.toString().getBytes(StandardCharsets.UTF_8);try{vault.put("settings",b);}finally{Arrays.fill(b,(byte)0);}if(values.containsKey("pushEnabled")||values.containsKey("relayUrl")){InboxSyncWorker.periodic(context,settings.optBoolean("pushEnabled"));InboxSyncWorker.schedule(context);}}
 public void connectMqtt()throws Exception{mqttEnabled=true;nextMqttAttempt=SystemClock.elapsedRealtime()+30000;if(mqtt!=null)mqtt.close();char[] password=settings.optString("password").toCharArray();try{mqtt=new MqttTransport(settings.getString("host"),settings.optInt("port",8883),settings.getString("user"),password,settings.getString("group"),unhex(settings.getString("origin")),new MqttTransport.Listener(){public void status(String text){main.post(()->NwiaEndpoint.this.status(text));}public void receive(byte[] raw){main.post(()->receiveRaw(raw));}});}finally{Arrays.fill(password,'\0');}}
 public void disconnectMqtt(){mqttEnabled=false;if(mqtt!=null){mqtt.close();mqtt=null;}status("MQTT disconnected");}
 public void scan(Consumer<List<Map<String,String>>> done){ble.scan(devices->{List<Map<String,String>> result=new ArrayList<>();for(BluetoothDevice d:devices){Map<String,String> item=new HashMap<>();item.put("id",d.getAddress());item.put("name",d.getName()==null?"NWIA companion":d.getName());result.add(item);}done.accept(result);});}
 public void connectBle(String address)throws Exception{radioEnabled=true;nextRadioAttempt=SystemClock.elapsedRealtime()+150000;settings.put("bleAddress",address);configure(Collections.emptyMap());android.bluetooth.BluetoothManager manager=context.getSystemService(android.bluetooth.BluetoothManager.class);ble.connect(manager.getAdapter().getRemoteDevice(address));}
 public void disconnectBle(){radioEnabled=false;ble.close();}
 private void status(String text){if(closed)return;Map<String,Object> e=new HashMap<>();e.put("kind","status");e.put("text",text);e.put("radioReady",ble!=null && ble.ready());e.put("mqttReady",mqtt!=null && mqtt.ready());listener.event(e);}
 private void emit(byte[] frame){if(closed)return;Map<String,Object> e=new HashMap<>();e.put("kind","frame");e.put("frame",frame);listener.event(e);}
 private void queueIncoming(byte[] frame){if(inbox.size()>=128)return;inbox.add(frame);emit(new byte[]{(byte)0x83});}
 private void error(){emit(new byte[]{1,1});}
 private void ok(){emit(new byte[]{0});}
 private JSONObject contact(byte[] key){String h=hex(key);for(int i=0;i<contacts.length();i++){JSONObject c=contacts.optJSONObject(i);if(c!=null && c.optString("pub").equals(h))return c;}return null;}
 private JSONObject contactPrefix(byte[] prefix){for(int i=0;i<contacts.length();i++){JSONObject c=contacts.optJSONObject(i);if(c!=null && c.optString("pub").startsWith(hex(prefix)))return c;}return null;}
 private JSONObject channel(int index){for(int i=0;i<channels.length();i++){JSONObject c=channels.optJSONObject(i);if(c!=null && c.optInt("index")==index)return c;}return null;}
 public Map<String,Object> sendDm(String peerHex,String text,long timestamp)throws Exception {byte[] peer=unhex(peerHex);if(peer.length!=32 || contact(peer)==null)throw new IllegalArgumentException("Unknown contact");byte[] secret=identity.secret(peer);try{DirectCrypto.Outgoing dm=DirectCrypto.encrypt(identity.publicKey(),peer,secret,text,timestamp);return submit(dm.raw(),Kind.DIRECT,dm.expectedAck(),false);}finally{Arrays.fill(secret,(byte)0);}}
 private Map<String,Object> submit(byte[] raw,Kind kind,byte[] ack,boolean channelResponse){
  String id=ChannelCrypto.id(raw);if(meta.containsKey(id))return meta.get(id);
  if(ack!=null && expected.containsKey(hex(ack)))throw new IllegalStateException("Receipt collision");
  List<Action> actions=engine.submit(id,raw,kind,ble.ready(),SystemClock.elapsedRealtime());Map<String,Object> info=new HashMap<>();info.put("id",id);info.put("createdAt",SystemClock.elapsedRealtime());info.put("channelResponse",channelResponse);if(ack!=null){String hash=hex(ack);expected.put(hash,id);deadlines.put(hash,SystemClock.elapsedRealtime()+300000);info.put("ackHash",Integer.toUnsignedLong(ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN).getInt()));}meta.put(id,info);seen.put(id,SystemClock.elapsedRealtime());PushInbox.sent(context,id);main.post(()->{dispatch(actions);publishStates();});return new HashMap<>(info);
 }
 private void dispatch(List<Action> actions){if(closed)return;for(Action a:actions){if(a.route()==Route.COMPANION)ble.send(a.ciphertext(),ok->{if(!closed && meta.containsKey(a.id())){dispatch(engine.radioResult(a.id(),ok,SystemClock.elapsedRealtime()));publishStates();}});else if(mqtt==null)engine.mqttResult(a.id(),false);else mqtt.send(a.ciphertext(),ok->main.post(()->{if(!closed && meta.containsKey(a.id())){engine.mqttResult(a.id(),ok);publishStates();}}));}}
 private void publishStates(){if(closed)return;for(var entry:meta.entrySet()){String id=entry.getKey();State state=engine.status(id).state();if(lastStates.get(id)==state)continue;lastStates.put(id,state);Map<String,Object> event=new HashMap<>(entry.getValue());event.put("kind","delivery");event.put("state",state.name());listener.event(event);if(Boolean.TRUE.equals(entry.getValue().get("channelResponse"))){if(state==State.RADIO_TRANSMITTED || state==State.MQTT_SENT)ok();else if(state==State.FAILED)error();}}}
 private void reconnectTransports(){long now=SystemClock.elapsedRealtime();if(mqttEnabled && (mqtt==null || !mqtt.ready()) && now>=nextMqttAttempt){try{connectMqtt();}catch(Exception ignored){status("MQTT unavailable");}}if(radioEnabled && !ble.ready() && now>=nextRadioAttempt){try{connectBle(settings.getString("bleAddress"));}catch(Exception ignored){nextRadioAttempt=now+150000;status("Companion unavailable");}}}
 private void archiveExpired(){long now=SystemClock.elapsedRealtime();for(String id:new ArrayList<>(meta.keySet())){if(now-((Number)meta.get(id).get("createdAt")).longValue()<600000)continue;if(!engine.archive(id))continue;meta.remove(id);lastStates.remove(id);seen.remove(id);for(String hash:new ArrayList<>(expected.keySet()))if(id.equals(expected.get(hash))){expected.remove(hash);deadlines.remove(hash);}}}
 private void receipt(byte[] ack){String h=hex(ack);if(SystemClock.elapsedRealtime()>deadlines.getOrDefault(h,0L))return;String id=expected.get(h);if(id!=null){engine.recipientReceipt(id);publishStates();}}
 private void reply(byte[] raw){if(ble.ready())ble.send(raw,ok->{if(!closed && !ok && mqtt!=null)mqtt.send(raw,sent->{});});else if(mqtt!=null)mqtt.send(raw,sent->{});}
 private void receiveRaw(byte[] raw){if(closed)return;byte[] secret=null,plain=null;try{
  int type=raw[0]>>2&15,pos=ChannelCrypto.payloadOffset(raw);String id=ChannelCrypto.id(raw);long now=SystemClock.elapsedRealtime();seen.entrySet().removeIf(e->now-e.getValue()>600000);
  if(type==3 || type==10){receipt(DirectCrypto.rawAck(raw));return;}
  if(type==2 || type==8){if(raw.length-pos<20 || raw[pos]!=identity.publicKey()[0])return;for(int i=0;i<contacts.length();i++){JSONObject c=contacts.getJSONObject(i);byte[] pub=unhex(c.getString("pub"));if(pub[0]!=raw[pos+1])continue;try{secret=identity.secret(pub);plain=DirectCrypto.decrypt(identity.publicKey(),pub,secret,raw);if(type==8){receipt(DirectCrypto.pathAck(plain));return;}DirectCrypto.Incoming message=DirectCrypto.text(plain,pub,System.currentTimeMillis()/1000);if(seen.containsKey(id))return;if(seen.size()>=1024)return;seen.put(id,now);PushInbox.live(context,id);reply(DirectCrypto.floodAck(message.ack()));ByteArrayOutputStream f=new ByteArrayOutputStream();f.write(16);f.write(0);f.write(0);f.write(0);f.write(pub,0,6);f.write(255);f.write(0);f.write(plain,0,4);f.write(message.text().getBytes(StandardCharsets.UTF_8));f.write(0);queueIncoming(f.toByteArray());return;}catch(GeneralSecurityException ignored){}finally{if(secret!=null)Arrays.fill(secret,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}}return;}
  if(type==5){for(int i=0;i<channels.length();i++){JSONObject c=channels.getJSONObject(i);try{ChannelCrypto.Incoming incoming=ChannelCrypto.decryptMessage(unhex(c.getString("psk")),raw);if(Math.abs(System.currentTimeMillis()/1000-incoming.timestamp())>300)return;String text=incoming.text();if(seen.containsKey(id))return;if(seen.size()>=1024)return;seen.put(id,now);PushInbox.live(context,id);ByteArrayOutputStream f=new ByteArrayOutputStream();f.write(17);f.write(0);f.write(0);f.write(0);f.write(c.getInt("index"));f.write(255);f.write(0);ByteBuffer stamp=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)incoming.timestamp());f.write(stamp.array());f.write(text.getBytes(StandardCharsets.UTF_8));f.write(0);queueIncoming(f.toByteArray());return;}catch(GeneralSecurityException ignored){}}return;}
  if(type==4){byte[] p=Arrays.copyOfRange(raw,pos,raw.length);if(p.length<101 || p.length>132)return;byte[] pub=Arrays.copyOf(p,32),signed=new byte[p.length-64];System.arraycopy(p,0,signed,0,36);System.arraycopy(p,100,signed,36,p.length-100);if(!NativeCrypto.verify(pub,signed,Arrays.copyOfRange(p,36,100)) || Arrays.equals(pub,identity.publicKey()))return;int flags=p[100]&255,offset=101;if((flags&16)!=0)offset+=8;if((flags&32)!=0)offset+=2;if((flags&64)!=0)offset+=2;if(offset>p.length)return;String name=(flags&128)!=0?new String(p,offset,p.length-offset,StandardCharsets.UTF_8):hex(pub).substring(0,8);JSONObject c=contact(pub);if(c==null){if(contacts.length()>=128)return;c=new JSONObject();contacts.put(c);}c.put("pub",hex(pub));c.put("name",name);c.put("type",flags&15);c.put("lastSeen",System.currentTimeMillis()/1000);saveArray("nwia_contacts",contacts);emit(contactFrame(c));}
 }catch(Exception|LinkageError ignored){}finally{if(secret!=null)Arrays.fill(secret,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}}
 private byte[] contactFrame(JSONObject c)throws Exception{ByteBuffer b=ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)3).put(unhex(c.getString("pub"))).put((byte)c.optInt("type",1)).put((byte)c.optInt("flags")).put((byte)255);b.put(new byte[64]);b.put(cstring(c.optString("name","Mesh contact"),32));b.putInt((int)c.optLong("lastSeen",System.currentTimeMillis()/1000)).putInt(0).putInt(0).putInt((int)(System.currentTimeMillis()/1000));return b.array();}
 /** Local compatibility frames used by Flutter's existing models; never sent to Wio. */
 public void handle(byte[] data){try{if(data.length==0 || data.length>512){error();return;}int cmd=data[0]&255;
  switch(cmd){
   case 22:{ByteBuffer b=ByteBuffer.allocate(82).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)13).put((byte)3).put((byte)64).put((byte)16).putInt(0).put(new byte[12]).put(cstring("NWIA phone endpoint",40)).put(cstring("NWIA 0.3",20)).put((byte)0).put((byte)0);emit(b.array());break;}
   case 1:{ByteBuffer b=ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)5).put((byte)1).put((byte)22).put((byte)22).put(identity.publicKey()).putInt(0).putInt(0).put(new byte[4]).putInt(910525).putInt(62500).put((byte)7).put((byte)5);byte[] name=settings.optString("name","NWIA Mesh").getBytes(StandardCharsets.UTF_8);b.put(name,0,Math.min(name.length,31));emit(Arrays.copyOf(b.array(),b.position()));break;}
   case 4:{emit(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)2).putInt(contacts.length()).array());for(int i=0;i<contacts.length();i++)emit(contactFrame(contacts.getJSONObject(i)));emit(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)4).putInt((int)(System.currentTimeMillis()/1000)).array());break;}
   case 30:{JSONObject c=data.length>=33?contact(Arrays.copyOfRange(data,1,33)):null;if(c==null)error();else emit(contactFrame(c));break;}
   case 9:{if(data.length<133){error();break;}byte[] pub=Arrays.copyOfRange(data,1,33);JSONObject c=contact(pub);if(c==null){if(contacts.length()>=128){error();break;}c=new JSONObject();contacts.put(c);}c.put("pub",hex(pub));c.put("type",data[33]&255);c.put("flags",data[34]&255);c.put("name",string(data,100,32));saveArray("nwia_contacts",contacts);ok();break;}
   case 15:{if(data.length<33){error();break;}String pub=hex(Arrays.copyOfRange(data,1,33));JSONArray next=new JSONArray();for(int i=0;i<contacts.length();i++)if(!contacts.getJSONObject(i).getString("pub").equals(pub))next.put(contacts.getJSONObject(i));contacts=next;saveArray("nwia_contacts",contacts);ok();break;}
   case 31:{if(data.length<2){error();break;}int index=data[1]&255;JSONObject c=channel(index);byte[] psk=c==null?new byte[16]:unhex(c.getString("psk"));ByteBuffer b=ByteBuffer.allocate(50);b.put((byte)18).put((byte)index).put(cstring(c==null?"":c.optString("name"),32)).put(Arrays.copyOf(psk,16));emit(b.array());break;}
   case 32:{if(data.length<50){error();break;}int index=data[1]&255;if(index>=16){error();break;}JSONObject c=channel(index);if(c==null){c=new JSONObject();channels.put(c);}c.put("index",index);c.put("name",string(data,2,32));c.put("psk",hex(Arrays.copyOfRange(data,34,50)));saveArray("nwia_channels",channels);ok();break;}
   case 3:{if(data.length<8 || data[1]!=0){error();break;}JSONObject c=channel(data[2]&255);if(c==null){error();break;}long time=Integer.toUnsignedLong(ByteBuffer.wrap(data,3,4).order(ByteOrder.LITTLE_ENDIAN).getInt());byte[] raw=ChannelCrypto.encrypt(unhex(c.getString("psk")),settings.optString("name","NWIA Mesh"),string(data,7,data.length-7),time);submit(raw,Kind.CHANNEL,null,true);break;}
   case 7:{byte[] signed=DirectCrypto.advertMessage(identity.publicKey(),settings.optString("name","NWIA Mesh"),System.currentTimeMillis()/1000);submit(DirectCrypto.advert(signed,identity.sign(signed)),Kind.CHANNEL,null,false);ok();break;}
   case 8:{settings.put("name",string(data,1,data.length-1));vault.put("settings",settings.toString().getBytes(StandardCharsets.UTF_8));ok();break;}
   case 5:emit(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)9).putInt((int)(System.currentTimeMillis()/1000)).array());break;
   case 6:ok();break;
   case 10:emit(inbox.isEmpty()?new byte[]{10}:inbox.remove());break;
   case 40:emit(new byte[]{21,'g','p','s',':','0',0});break;
   default:error(); // Includes plaintext legacy DM, private-key import/export, login and unsupported radio settings.
  }
 }catch(Exception error){error();}}
 public List<Map<String,Object>> inboxFrames()throws Exception {
  List<Map<String,Object>> result=new ArrayList<>();JSONArray items=PushInbox.pending(context);
  for(int i=0;i<items.length() && result.size()<16;i++){JSONObject item=items.getJSONObject(i);InboxPackets.Decoded decoded=InboxPackets.decode(identity,contacts,channels,Base64.getDecoder().decode(item.getString("raw")),item.getLong("received"));if(decoded!=null){Map<String,Object> r=new HashMap<>();r.put("id",item.getString("id"));r.put("frame",decoded.frame());result.add(r);}}
  return result;
 }
 public void inboxCommit(String id)throws Exception {PushInbox.commit(context,id);}
 public void inboxSync(){InboxSyncWorker.schedule(context);}
 public String secureRead(String name)throws Exception{if(!name.matches("[a-fA-F0-9]{10}"))throw new IllegalArgumentException();try{return new String(vault.get("nwia_channels_"+name),StandardCharsets.UTF_8);}catch(FileNotFoundException e){return null;}}
 public void secureWrite(String name,String data)throws Exception{if(!name.matches("[a-fA-F0-9]{10}") || data.length()>65536)throw new IllegalArgumentException();vault.put("nwia_channels_"+name,data.getBytes(StandardCharsets.UTF_8));}
 public void close(){if(closed)return;closed=true;main.removeCallbacks(tick);ble.close();if(mqtt!=null)mqtt.close();identity.close();meta.clear();expected.clear();inbox.clear();}
 private static byte[] cstring(String s,int size){byte[] b=new byte[size],raw=s.getBytes(StandardCharsets.UTF_8);System.arraycopy(raw,0,b,0,Math.min(raw.length,size-1));return b;}
 private static String string(byte[] data,int start,int count){if(start<0 || count<0 || start+count>data.length)throw new IllegalArgumentException();int end=start;while(end<start+count && data[end]!=0)end++;return new String(data,start,end-start,StandardCharsets.UTF_8);}
 private static byte[] unhex(String s){if(!s.matches("(?:[a-fA-F0-9]{2})+"))throw new IllegalArgumentException();byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b;}
 private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
}
