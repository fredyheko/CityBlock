package md.cityblock.zayavki;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;

final class Notifier {
    static final String CH_STATUS = "status";
    static final String CH_CASES = "cases";
    static final String CH_ERRORS = "errors";
    static final int ID_STATUS = 1;
    static final int ID_ERROR = 2;

    static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Работа мониторинга",
                NotificationManager.IMPORTANCE_MIN);
        status.setShowBadge(false);

        NotificationChannel cases = new NotificationChannel(CH_CASES, "Новые заявки",
                NotificationManager.IMPORTANCE_HIGH);
        cases.enableVibration(true);
        cases.setVibrationPattern(new long[]{0, 400, 200, 400});
        cases.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());

        NotificationChannel errors = new NotificationChannel(CH_ERRORS, "Ошибки",
                NotificationManager.IMPORTANCE_DEFAULT);

        nm.createNotificationChannel(status);
        nm.createNotificationChannel(cases);
        nm.createNotificationChannel(errors);
    }

    private static PendingIntent activity(Context ctx, Class<?> cls, int req) {
        Intent i = new Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification status(Context ctx, String text) {
        Intent stop = new Intent(ctx, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(ctx, 10, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Мониторинг заявок включён")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(activity(ctx, MainActivity.class, 11))
                .addAction(new Notification.Action.Builder(null, "Остановить", stopPi).build())
                .build();
    }

    static void newCase(Context ctx, PmCore.Case c) {
        int id = 1000 + (c.key.hashCode() & 0x0fffffff);
        Notification.Builder b = new Notification.Builder(ctx, CH_CASES)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Новая заявка #" + c.number)
                .setContentText(c.title)
                .setStyle(new Notification.BigTextStyle().bigText(PmCore.details(c)))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(activity(ctx, WebActivity.class, 12));
        if (!c.phones.isEmpty()) {
            String ph = c.phones.get(0);
            Intent dial = new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + ph))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent dialPi = PendingIntent.getActivity(ctx, id, dial,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null,
                    "📞 Позвонить " + CaseDetails.pretty(ph), dialPi).build());
        }
        ctx.getSystemService(NotificationManager.class).notify(id, b.build());
    }

    static void error(Context ctx, String text) {
        Notification n = new Notification.Builder(ctx, CH_ERRORS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Мониторинг заявок: ошибка")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(activity(ctx, MainActivity.class, 13))
                .build();
        ctx.getSystemService(NotificationManager.class).notify(ID_ERROR, n);
    }

    static void clearError(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(ID_ERROR);
    }
}
