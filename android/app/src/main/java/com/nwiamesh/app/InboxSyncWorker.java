package com.nwiamesh.app;

import android.content.Context;
import androidx.work.*;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public final class InboxSyncWorker extends Worker {
    public InboxSyncWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    static void schedule(Context c) {
        Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest.Builder builder=new OneTimeWorkRequest.Builder(InboxSyncWorker.class).setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS);
        if(android.os.Build.VERSION.SDK_INT>=31)builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        OneTimeWorkRequest next=builder.build();
        WorkManager.getInstance(c).enqueueUniqueWork("nwia-inbox-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,next);
    }
    static void periodic(Context c,boolean enabled) {
        if(!enabled){WorkManager.getInstance(c).cancelUniqueWork("nwia-inbox-recovery");return;}
        PeriodicWorkRequest next=new PeriodicWorkRequest.Builder(InboxSyncWorker.class,15,TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("nwia-inbox-recovery",ExistingPeriodicWorkPolicy.KEEP,next);
    }
    @Override public Result doWork() {
        Context c=getApplicationContext();
        try {
            JSONObject settings=InboxClient.settings(c);
            boolean enabled=settings.optBoolean("pushEnabled");
            if(!enabled && !PushInbox.status(c).optBoolean("ready"))return Result.success();
            String token=enabled?Tasks.await(FirebaseMessaging.getInstance().getToken(),15,TimeUnit.SECONDS):"";
            try(PhoneIdentity identity=PhoneIdentity.load(c)) {
                new InboxClient(c,settings,identity).sync(token);
            }
            return Result.success();
        } catch(Exception error) {
            PushInbox.failed(c);
            return getRunAttemptCount()<5?Result.retry():Result.failure();
        } finally {
            try {
                if(InboxClient.settings(c).optBoolean("pushEnabled")) {
                    boolean notify=false;
                    try(PhoneIdentity identity=PhoneIdentity.load(c)) {
                        JSONArray contacts=InboxClient.array(c,"nwia_contacts"),channels=InboxClient.array(c,"nwia_channels"),items=PushInbox.pending(c);
                        for(int i=0;i<items.length();i++) {
                            JSONObject item=items.getJSONObject(i);
                            InboxPackets.Decoded decoded=InboxPackets.decode(identity,contacts,channels,java.util.Base64.getDecoder().decode(item.getString("raw")),item.getLong("received"));
                            if(decoded==null)continue;
                            String id=item.getString("id");
                            if(!item.optBoolean("notified")){PushInbox.notified(c,id);notify=true;}
                            if(decoded.receipt()!=null && !item.optBoolean("receipted") && !PushInbox.wasLive(c,id) && Math.abs(System.currentTimeMillis()/1000-decoded.timestamp())<240) {
                                PushInbox.receipted(c,id);
                                InboxClient.sendReceipt(InboxClient.settings(c),decoded.receipt());
                            }
                        }
                    }
                    if(notify)NwiaPushService.show(c,false);
                }
            } catch(Exception ignored) { }
        }
    }
}
