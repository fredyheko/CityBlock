package md.cityblock.zayavki;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Запуск мониторинга после перезагрузки телефона или обновления приложения. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (new Prefs(ctx).enabled()) {
            try {
                MonitorService.start(ctx);
            } catch (Exception ignored) {
            }
        }
    }
}
