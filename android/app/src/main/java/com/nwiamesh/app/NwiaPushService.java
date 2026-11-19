package com.nwiamesh.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.meshcore.meshcore_open.R;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Generic wakeup alerts only. Message plaintext and mesh keys never go to FCM. */
public final class NwiaPushService extends FirebaseMessagingService {
    private static final String CHANNEL = "nwia_push";

    @Override public void onNewToken(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        try { new KeyVault(this).put("push_token", bytes); if (InboxClient.settings(this).optBoolean("pushEnabled")) InboxSyncWorker.schedule(this); }
        catch (Exception ignored) { /* Registration can be retried from the app. */ }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        byte[] receipt = Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
        try { new KeyVault(this).put("push_last_received", receipt); }
        catch (Exception ignored) { }
        finally { Arrays.fill(receipt, (byte) 0); }
        if ("inbox".equals(message.getData().get("kind"))) {
            try { if (InboxClient.settings(this).optBoolean("pushEnabled")) InboxSyncWorker.schedule(this); }
            catch (Exception ignored) { }
            return;
        }
        if ("test".equals(message.getData().get("kind"))) show(this, true);
    }

    @Override public void onDeletedMessages() {
        try { if (InboxClient.settings(this).optBoolean("pushEnabled")) InboxSyncWorker.schedule(this); }
        catch (Exception ignored) { }
    }

    static void show(android.content.Context context, boolean test) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, context.getString(R.string.nwia_push_channel), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) return;
        PendingIntent open = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        // Remote bodies are intentionally ignored; alerts never contain decrypted chat text.
        builder.setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.nwia_push_title))
            .setContentText(context.getString(test ? R.string.nwia_push_test : R.string.nwia_push_activity))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE);
        manager.notify(0x4e574941, builder.build());
    }
}
