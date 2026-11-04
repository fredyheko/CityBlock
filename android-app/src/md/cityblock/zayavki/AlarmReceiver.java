package md.cityblock.zayavki;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Будильник: будит телефон и запускает проверку. */
public class AlarmReceiver extends BroadcastReceiver {
    private static PowerManager.WakeLock lock;

    static PendingIntent pending(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 20, new Intent(ctx, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static synchronized void releaseWakeLock() {
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!new Prefs(ctx).enabled()) return;
        synchronized (AlarmReceiver.class) {
            if (lock == null) {
                lock = ctx.getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zayavki:check");
                lock.setReferenceCounted(false);
            }
            lock.acquire(3 * 60_000L); // держим, пока идёт проверка (максимум 3 мин)
        }
        Intent i = new Intent(ctx, MonitorService.class).setAction(MonitorService.ACTION_CHECK);
        try {
            ctx.startForegroundService(i);
        } catch (Exception e) {
            try {
                ctx.startService(i);
            } catch (Exception ignored) {
                releaseWakeLock();
            }
        }
    }
}
