package uz.yulemdo.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

/**
 * Yuklamalar uchun tizim bildirishnomalari (notification).
 * Status panelida va bildirishnoma ro'yxatida har doim ilova ikonkasi bilan ko'rinadi.
 */
final class DownloadNotifier {
    private static final String CHANNEL_ID = "yulemdo_downloads";
    private static final String CHANNEL_NAME = "Yuklamalar";

    private DownloadNotifier() { }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Fayl yuklanishi haqida bildirishnomalar");
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    private static boolean canNotify(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Yuklanish boshlanganda — aniqlanmagan (indeterminate) progress bilan. */
    static void showProgress(Context ctx, int id, String name) {
        if (!canNotify(ctx)) return;
        ensureChannel(ctx);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                        ctx.getResources(), R.mipmap.ic_launcher))
                .setColor(MainActivity.ORANGE_DEEP)
                .setContentTitle(name == null || name.isEmpty() ? "Yulemdo" : name)
                .setContentText("Yuklanmoqda...")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        try {
            NotificationManagerCompatSafe.notify(ctx, id, b.build());
        } catch (SecurityException ignored) { }
    }

    /** Yuklanish muvaffaqiyatli tugaganda — bosilsa faylni ochadi. */
    static void showDone(Context ctx, int id, String name, Uri fileUri, String mime, boolean isContentUri) {
        if (!canNotify(ctx)) return;
        ensureChannel(ctx);

        Intent open = new Intent(Intent.ACTION_VIEW);
        Uri target = fileUri;
        if (!isContentUri && fileUri != null) {
            try {
                java.io.File f = new java.io.File(fileUri.getPath());
                target = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
            } catch (Exception ignored) { }
        }
        if (target != null) {
            open.setDataAndType(target, mime == null || mime.isEmpty() ? "*/*" : mime);
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                        ctx.getResources(), R.mipmap.ic_launcher))
                .setColor(MainActivity.ORANGE_DEEP)
                .setContentTitle(name == null || name.isEmpty() ? "Yulemdo" : name)
                .setContentText("Yuklab olindi")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompatSafe.notify(ctx, id, b.build());
        } catch (SecurityException ignored) { }
    }

    /** Yuklanish muvaffaqiyatsiz tugaganda. */
    static void showFailed(Context ctx, int id, String name) {
        if (!canNotify(ctx)) return;
        ensureChannel(ctx);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                        ctx.getResources(), R.mipmap.ic_launcher))
                .setColor(MainActivity.ORANGE_DEEP)
                .setContentTitle(name == null || name.isEmpty() ? "Yulemdo" : name)
                .setContentText("Yuklab bo'lmadi")
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        try {
            NotificationManagerCompatSafe.notify(ctx, id, b.build());
        } catch (SecurityException ignored) { }
    }

    /** Oddiy kichik wrapper — NotificationManagerCompat importini bitta joyga jamlash uchun. */
    private static final class NotificationManagerCompatSafe {
        static void notify(Context ctx, int id, Notification n) {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(id, n);
        }
    }
}
