package md.cityblock.zayavki;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Настройки и состояние приложения. */
final class Prefs {
    static final String FILE = "cfg";

    final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String host() { return sp.getString("host", "https://pm1.starnet.md"); }
    String workspace() { return sp.getString("workspace", "starnet"); }
    String user() { return sp.getString("user", ""); }
    String pass() { return sp.getString("pass", ""); }
    /** unassigned | todo */
    String listAction() { return sp.getString("list", "unassigned"); }
    int intervalMin() { return Math.max(1, sp.getInt("interval", 1)); }
    boolean keepAwake() { return sp.getBoolean("keepAwake", true); }
    boolean notifyExisting() { return sp.getBoolean("notifyExisting", true); }
    String tgToken() { return sp.getString("tgToken", "").trim(); }
    String tgChat() { return sp.getString("tgChat", "").trim(); }

    boolean enabled() { return sp.getBoolean("enabled", false); }
    void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    boolean configured() { return !user().isEmpty() && !pass().isEmpty(); }

    String base() { return PmCore.base(host(), workspace()); }

    Set<String> seen() { return new HashSet<>(sp.getStringSet("seen", new HashSet<String>())); }
    boolean firstRun() { return !sp.contains("seen"); }
    void setSeen(Set<String> s) { sp.edit().putStringSet("seen", new HashSet<>(s)).apply(); }
    void resetSeen() { sp.edit().remove("seen").apply(); }

    int failures() { return sp.getInt("failures", 0); }
    void setFailures(int n) { sp.edit().putInt("failures", n).apply(); }

    String status() { return sp.getString("status", "Мониторинг выключен"); }
    void setStatus(String s) { sp.edit().putString("status", s).apply(); }
}
