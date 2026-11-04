package md.cityblock.zayavki;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновая служба: раз в N минут проверяет список заявок и показывает уведомления.
 */
public class MonitorService extends Service {
    static final String ACTION_START = "md.cityblock.zayavki.START";
    static final String ACTION_STOP = "md.cityblock.zayavki.STOP";
    static final String ACTION_CHECK = "md.cityblock.zayavki.CHECK";
    static final String ACTION_DIAG = "md.cityblock.zayavki.DIAG";

    /** После скольких ошибок подряд показать уведомление об ошибке. */
    static final int ERROR_ALERT_AFTER = 3;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Runnable loopTick = this::triggerCheck;
    private PowerManager.WakeLock keepAwakeLock;
    /** VPN только что включился — проверить заявки, когда туннель поднимется. */
    private final VpnSwitch.Listener vpnListener = () -> main.postDelayed(this::triggerCheck, 8000);
    private Prefs prefs;

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, MonitorService.class).setAction(ACTION_START));
    }

    static void diagnose(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, MonitorService.class).setAction(ACTION_DIAG));
    }

    static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, MonitorService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        Notifier.createChannels(this);
        PmSession.init(this);
    }

    private void goForeground(String text) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_STATUS, Notifier.status(this, text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Notifier.ID_STATUS, Notifier.status(this, text));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        goForeground(prefs.status());

        if (ACTION_STOP.equals(action)) {
            prefs.setEnabled(false);
            prefs.setStatus("Мониторинг выключен");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_DIAG.equals(action)) {
            worker.execute(this::runDiagnostics);
            if (!prefs.enabled()) {
                worker.execute(() -> main.post(() -> {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }));
            }
            return prefs.enabled() ? START_STICKY : START_NOT_STICKY;
        }
        if (!prefs.enabled() && intent != null && !ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs.setEnabled(true);
        applyKeepAwake();
        if (prefs.wgAuto()) {
            VpnSwitch.start(this, vpnListener);
        } else {
            VpnSwitch.stop(this);
        }
        triggerCheck();
        return START_STICKY;
    }

    private void applyKeepAwake() {
        if (prefs.keepAwake()) {
            if (keepAwakeLock == null) {
                PowerManager pm = getSystemService(PowerManager.class);
                keepAwakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zayavki:keepAwake");
                keepAwakeLock.setReferenceCounted(false);
            }
            if (!keepAwakeLock.isHeld()) keepAwakeLock.acquire();
        } else if (keepAwakeLock != null && keepAwakeLock.isHeld()) {
            keepAwakeLock.release();
        }
    }

    private void triggerCheck() {
        if (!checking.compareAndSet(false, true)) return;
        main.removeCallbacks(loopTick);
        worker.execute(() -> {
            try {
                runCheck();
            } catch (Throwable t) {
                prefs.setStatus("Ошибка: " + t);
            } finally {
                checking.set(false);
                AlarmReceiver.releaseWakeLock();
                main.post(this::scheduleNext);
            }
        });
    }

    private void scheduleNext() {
        if (!prefs.enabled()) return;
        long interval = prefs.intervalMin() * 60_000L;
        // 1) обычный таймер — работает, пока телефон не спит (в режиме «не давать засыпать» — всегда)
        main.removeCallbacks(loopTick);
        main.postDelayed(loopTick, interval);
        // 2) будильник — разбудит телефон, если он всё-таки уснул
        AlarmManager am = getSystemService(AlarmManager.class);
        PendingIntent pi = AlarmReceiver.pending(this);
        long at = SystemClock.elapsedRealtime() + interval + (prefs.keepAwake() ? 30_000 : 0);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    // ------------------------------------------------------------------ проверка
    private void runCheck() {
        if (!prefs.configured()) {
            setStatus("Укажите логин и пароль в приложении");
            return;
        }
        String base = prefs.base();
        try {
            List<PmCore.Case> cases;
            try {
                cases = Net.fetchCases(base, prefs.listAction());
            } catch (PmCore.SessionExpired e) {
                setStatus("Вход на сайт…");
                login(base);
                cases = Net.fetchCases(base, prefs.listAction());
            }

            boolean first = prefs.firstRun();
            Set<String> seen = prefs.seen();
            List<PmCore.Case> fresh = PmCore.newCases(cases, seen);
            String tgError = null;
            if (!(first && !prefs.notifyExisting())) {
                for (PmCore.Case c : fresh) {
                    Net.fetchDetails(base, c, prefs.listAction(), null);
                    Notifier.newCase(this, c);
                    String e = Net.telegram(prefs.tgToken(), prefs.tgChat(),
                            PmCore.telegramHtml(c, PmCore.homeUrl(base)));
                    if (e != null) tgError = e;
                }
            }
            // помним только то, что сейчас в списке: вернут заявку — сообщим снова
            prefs.setSeen(PmCore.keys(cases, new HashSet<String>()));

            if (prefs.failures() >= ERROR_ALERT_AFTER) Notifier.clearError(this);
            prefs.setFailures(0);
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            String what = "unassigned".equals(prefs.listAction()) ? "Unassigned" : "Inbox";
            setStatus("Проверено в " + time + ". " + what + ": " + cases.size()
                    + (tgError != null ? "\n⚠️ " + tgError : ""));
        } catch (Exception e) {
            int n = prefs.failures() + 1;
            prefs.setFailures(n);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            setStatus("Ошибка (" + n + " подряд): " + msg);
            if (n == ERROR_ALERT_AFTER) Notifier.error(this, msg);
        }
    }

    /** Диагностика: что сайт отдаёт по первой заявке в списке. Результат — в prefs "diag". */
    private void runDiagnostics() {
        StringBuilder d = new StringBuilder();
        String base = prefs.base();
        try {
            if (!prefs.configured()) throw new Exception("Укажите логин и пароль");
            List<PmCore.Case> cases;
            try {
                cases = Net.fetchCases(base, prefs.listAction());
            } catch (PmCore.SessionExpired e) {
                login(base);
                cases = Net.fetchCases(base, prefs.listAction());
            }
            d.append("Заявок в списке: ").append(cases.size()).append('\n');
            if (cases.isEmpty()) {
                d.append("Список пуст — диагностику можно сделать, когда появится заявка.");
            } else {
                PmCore.Case c = cases.get(0);
                d.append("Заявка #").append(c.number).append(" (").append(c.uid).append(", ")
                        .append(c.delIndex).append(")\n");
                Net.fetchDetails(base, c, prefs.listAction(), d);
                d.append("\n══ Итог, как будет в уведомлении:\n").append(PmCore.details(c));
            }
        } catch (Exception e) {
            d.append("Ошибка: ").append(e.getMessage());
        }
        prefs.sp.edit().putString("diag", d.toString()).putLong("diagAt", System.currentTimeMillis()).apply();
    }

    private void login(String base) throws Exception {
        PmSession.login(this, base, prefs);
    }

    private void setStatus(String s) {
        prefs.setStatus(s);
        getSystemService(NotificationManager.class)
                .notify(Notifier.ID_STATUS, Notifier.status(this, s));
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(loopTick);
        VpnSwitch.stop(this);
        if (!prefs.enabled()) {
            getSystemService(AlarmManager.class).cancel(AlarmReceiver.pending(this));
        }
        if (keepAwakeLock != null && keepAwakeLock.isHeld()) keepAwakeLock.release();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
