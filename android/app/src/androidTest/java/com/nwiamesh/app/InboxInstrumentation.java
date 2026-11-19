package com.nwiamesh.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import com.nwiamesh.core.ChannelCrypto;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

/** Uses an isolated on-phone directory; never replaces or exports the real identity. */
public final class InboxInstrumentation extends Instrumentation {
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean value){if(!value)throw new AssertionError();}
    private void erase(File f){if(f.isDirectory())for(File c:f.listFiles())erase(c);f.delete();}
    @Override public void onStart(){
        Bundle result=new Bundle();File sandbox=new File(getTargetContext().getNoBackupFilesDir(),"inbox-instrumentation");sandbox.mkdirs();
        Context context=new ContextWrapper(getTargetContext()){@Override public File getNoBackupFilesDir(){return sandbox;}};
        try(PhoneIdentity identity=PhoneIdentity.load(context)) {
            long captured=System.currentTimeMillis()/1000-3600;
            byte[] key=new byte[16];for(int i=0;i<16;i++)key[i]=(byte)(i+1);
            byte[] raw=ChannelCrypto.encrypt(key,"Fixture","offline inbox",captured);String id=ChannelCrypto.id(raw);
            JSONArray channels=new JSONArray().put(new JSONObject().put("index",0).put("name","Fixture").put("psk",InboxPackets.hex(key)));
            InboxPackets.Decoded decoded=InboxPackets.decode(identity,new JSONArray(),channels,raw,captured);
            check(decoded!=null && decoded.frame()[0]==17 && decoded.receipt()==null);
            check(InboxPackets.decode(identity,new JSONArray(),channels,raw,captured+3600)==null);
            JSONObject item=new JSONObject().put("seq",1).put("id",id).put("raw",Base64.getEncoder().encodeToString(raw)).put("received",captured).put("readable",true);
            check(PushInbox.merge(context,new JSONArray().put(item),0)==1);
            check(PushInbox.merge(context,new JSONArray().put(item),0)==0);
            check(PushInbox.pending(context).length()==1);
            byte[] disk=Files.readAllBytes(new File(sandbox,"key-inbox_cache").toPath());
            check(disk[0]==1 && !new String(disk,java.nio.charset.StandardCharsets.ISO_8859_1).contains(id));
            JSONObject invalid=new JSONObject(item.toString()).put("id","00".repeat(32));
            try{PushInbox.merge(context,new JSONArray().put(invalid),0);throw new AssertionError();}catch(java.io.IOException expected){}
            check(PushInbox.pending(context).length()==1);
            JSONArray full=new JSONArray();
            for(int i=0;i<513;i++){byte[] packet=ChannelCrypto.encrypt(key,"Fixture","bounded",captured+i);full.put(new JSONObject().put("seq",i+2).put("id",ChannelCrypto.id(packet)).put("raw",Base64.getEncoder().encodeToString(packet)).put("received",captured+i).put("readable",true));}
            try{PushInbox.merge(context,full,0);throw new AssertionError();}catch(java.io.IOException expected){}
            check(PushInbox.pending(context).length()==1);
            PushInbox.commit(context,id);check(PushInbox.pending(context).length()==0);
            check(PushInbox.merge(context,new JSONArray().put(item),0)==0);
            check(PushInbox.pending(context).length()==0);
            result.putString("result","PASS isolated encrypted inbox: original capture time, at-rest metadata encryption, duplicate merge, atomic capacity/invalid-record rejection, durable commit/replay suppression");
            finish(-1,result);
        }catch(Throwable error){result.putString("result","FAIL "+error.getClass().getSimpleName());finish(0,result);}
        finally{erase(sandbox);}
    }
}
