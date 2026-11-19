package com.nwiamesh.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import com.nwiamesh.core.ChannelCrypto;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** One process-wide lock protects atomic encrypted cache updates across UI and workers. */
final class PushInbox {
    static final int CAPACITY = 512;
    private static JSONObject read(Context c) throws Exception {
        try { JSONObject state=new JSONObject(new String(new KeyVault(c).get("inbox_cache"), StandardCharsets.UTF_8));if(!state.has("sent"))state.put("sent",new JSONObject());return state; }
        catch (FileNotFoundException absent) { return new JSONObject().put("items",new JSONArray()).put("done",new JSONObject()).put("live",new JSONObject()).put("sent",new JSONObject()); }
    }
    private static void write(Context c,JSONObject state) throws Exception {
        byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8);
        try { new KeyVault(c).put("inbox_cache",bytes); } finally { Arrays.fill(bytes,(byte)0); }
    }
    private static void prune(JSONObject state) throws Exception {
        long now=System.currentTimeMillis()/1000;
        for (String name:new String[]{"done","live","sent"}) {
            JSONObject map=state.getJSONObject(name);
            java.util.List<String> keys=new java.util.ArrayList<>(); map.keys().forEachRemaining(keys::add);
            for(String key:keys) if(now-map.optLong(key)>72*3600) map.remove(key);
            while(map.length()>768) { String oldest=map.keys().next();for(java.util.Iterator<String> it=map.keys();it.hasNext();) {String k=it.next();if(map.optLong(k)<map.optLong(oldest))oldest=k;}map.remove(oldest); }
        }
        JSONArray kept=new JSONArray(), items=state.getJSONArray("items");
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.getJSONObject(i);
            if(now-item.getLong("received")<=72*3600)kept.put(item);
            else state.put("localDropped",state.optLong("localDropped")+1);
        }
        state.put("items",kept);
    }
    static synchronized int merge(Context c,JSONArray incoming,long dropped) throws Exception {
        JSONObject state=read(c);prune(state);JSONArray items=state.getJSONArray("items");int fresh=0;
        for(int i=0;i<incoming.length();i++) {
            JSONObject item=incoming.getJSONObject(i);String id=item.getString("id");byte[] raw=Base64.getDecoder().decode(item.getString("raw"));
            long received=item.getLong("received"),now=System.currentTimeMillis()/1000;
            if(!ChannelCrypto.id(raw).equals(id) || received>now+60 || received<now-72*3600 || item.getLong("seq")<=0)throw new IOException("Invalid inbox record");
            boolean exists=state.getJSONObject("done").has(id)||state.getJSONObject("sent").has(id);
            for(int j=0;j<items.length();j++) if(items.getJSONObject(j).getString("id").equals(id))exists=true;
            if(exists)continue;
            int unreadable=0, oldest=-1;
            for(int j=0;j<items.length();j++)if(!items.getJSONObject(j).optBoolean("readable")){unreadable++;if(oldest<0)oldest=j;}
            if((!item.optBoolean("readable") && unreadable>=64) || items.length()>=CAPACITY) {
                if(oldest>=0){items.remove(oldest);state.put("localDropped",state.optLong("localDropped")+1);}
                else throw new IOException("Inbox storage full");
            }
            item.put("notified",state.getJSONObject("live").has(id));items.put(item);
            if(!state.getJSONObject("live").has(id))fresh++;
        }
        state.put("serverDropped",dropped);state.put("lastSync",System.currentTimeMillis()/1000);state.put("error",false);write(c,state);return fresh;
    }
    static synchronized JSONArray pending(Context c) throws Exception {JSONObject state=read(c);prune(state);return state.getJSONArray("items");}
    static synchronized void commit(Context c,String id) throws Exception {
        JSONObject state=read(c);JSONArray kept=new JSONArray(),items=state.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(!items.getJSONObject(i).getString("id").equals(id))kept.put(items.getJSONObject(i));
        state.put("items",kept);state.getJSONObject("done").put(id,System.currentTimeMillis()/1000);prune(state);write(c,state);
    }
    static synchronized void live(Context c,String id) {try{JSONObject state=read(c);state.getJSONObject("live").put(id,System.currentTimeMillis()/1000);prune(state);write(c,state);}catch(Exception ignored){}}
    static synchronized void sent(Context c,String id){try{JSONObject s=read(c);s.getJSONObject("sent").put(id,System.currentTimeMillis()/1000);prune(s);write(c,s);}catch(Exception ignored){}}
    static synchronized boolean wasLive(Context c,String id)throws Exception{return read(c).getJSONObject("live").has(id);}
    static synchronized void receipted(Context c,String id)throws Exception {JSONObject s=read(c);JSONArray items=s.getJSONArray("items");for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals(id))items.getJSONObject(i).put("receipted",true);write(c,s);}
    static synchronized void notified(Context c,String id)throws Exception {JSONObject s=read(c);JSONArray items=s.getJSONArray("items");for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals(id))items.getJSONObject(i).put("notified",true);write(c,s);}
    static synchronized JSONObject status(Context c) throws Exception {JSONObject s=read(c);prune(s);return new JSONObject().put("pending",s.getJSONArray("items").length()).put("lastSync",s.optLong("lastSync")).put("dropped",s.optLong("serverDropped")+s.optLong("localDropped")).put("ready",s.optBoolean("ready")).put("error",s.optBoolean("error"));}
    static synchronized String binding(Context c) throws Exception {JSONObject s=read(c);return System.currentTimeMillis()/1000-s.optLong("registeredAt")>12*3600?"":s.optString("binding");}
    static synchronized void registered(Context c,String binding) throws Exception {JSONObject s=read(c);s.put("binding",binding);s.put("registeredAt",System.currentTimeMillis()/1000);s.put("ready",!binding.isEmpty());s.put("error",false);write(c,s);}
    static synchronized void failed(Context c) {try{JSONObject s=read(c);s.put("error",true);write(c,s);}catch(Exception ignored){}}
}
