package md.cityblock.zayavki;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Управление туннелем WireGuard: на мобильном интернете — включить, на Wi-Fi — выключить.
 * Требует в WireGuard: Настройки → «Allow remote control apps» и разрешение CONTROL_TUNNELS.
 */
final class VpnSwitch {
    static final String WG_PACKAGE = "com.wireguard.android";
    static final String PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS";
    private static final String ACTION_UP = "com.wireguard.android.action.SET_TUNNEL_UP";
    private static final String ACTION_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN";
    private static final long SETTLE_MS = 3000; // сеть часто «прыгает» при переключении

    interface Listener { void vpnTurnedOn(); }

    private static ConnectivityManager.NetworkCallback callback;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Boolean lastSent = null;
    private static Runnable pending;

    static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(WG_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean permitted(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Начать следить за сетью (из фоновой службы). */
    static synchronized void start(Context ctx, Listener l) {
        Context app = ctx.getApplicationContext();
        if (callback != null) return;
        if (!new Prefs(app).wgAuto()) return;
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { schedule(app, l); }
            @Override public void onLost(Network n) { schedule(app, l); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { schedule(app, l); }
        };
        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        cm.registerNetworkCallback(req, callback);
        lastSent = null;
        schedule(app, l);
    }

    static synchronized void stop(Context ctx) {
        if (callback == null) return;
        try {
            ctx.getApplicationContext().getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
        }
        callback = null;
        if (pending != null) H.removeCallbacks(pending);
    }

    private static synchronized void schedule(Context app, Listener l) {
        if (pending != null) H.removeCallbacks(pending);
        pending = () -> apply(app, l, false);
        H.postDelayed(pending, SETTLE_MS);
    }

    /** Какая сеть сейчас (без учёта VPN): "wifi", "mobile" или "" (нет сети). */
    @SuppressWarnings("deprecation")
    static String currentNetwork(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        boolean wifi = false, mobile = false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) wifi = true;
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mobile = true;
        }
        return wifi ? "wifi" : mobile ? "mobile" : "";
    }

    /** Привести VPN в нужное состояние. force — отправить команду, даже если уже отправляли. */
    static void apply(Context ctx, Listener l, boolean force) {
        Prefs p = new Prefs(ctx);
        if (!p.wgAuto()) return;
        String net = currentNetwork(ctx);
        if (net.isEmpty()) return; // сети нет — ничего не трогаем
        boolean up = "mobile".equals(net);
        if (!force && lastSent != null && lastSent == up) return;
        String err = send(ctx, p.wgTunnel(), up);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        if (err == null) {
            lastSent = up;
            p.setVpnState((up ? "VPN включён" : "VPN выключен") + " (" + ("mobile".equals(net) ? "мобильный интернет" : "Wi-Fi") + ", " + time + ")");
            if (up && l != null) l.vpnTurnedOn();
        } else {
            p.setVpnState("⚠️ " + err);
        }
    }

    /** Команда WireGuard. null — отправлено, иначе текст ошибки. */
    static String send(Context ctx, String tunnel, boolean up) {
        if (tunnel == null || tunnel.trim().isEmpty()) return "Не указано имя туннеля WireGuard";
        if (!installed(ctx)) return "WireGuard не установлен";
        if (!permitted(ctx)) return "Нет разрешения управлять WireGuard — откройте настройки приложения";
        Intent i = new Intent(up ? ACTION_UP : ACTION_DOWN)
                .setPackage(WG_PACKAGE)
                .putExtra("tunnel", tunnel.trim());
        try {
            ctx.sendBroadcast(i, null);
            return null;
        } catch (Exception e) {
            return "WireGuard: " + e.getMessage();
        }
    }
}
