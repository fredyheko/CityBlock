package md.cityblock.zayavki;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Экран настроек: логин, пароль, интервал, Telegram; запуск/остановка. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private Prefs prefs;
    private EditText host, workspace, user, pass, interval, tgToken, tgChat;
    private RadioButton rbUnassigned, rbInbox;
    private Switch keepAwake, notifyExisting;
    private TextView status;
    private LinearLayout root;
    private LinearLayout box;
    private boolean diagWaiting = false;
    private boolean returnToCases = false;

    private void openCases() {
        startActivity(new Intent(this, CasesActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        Notifier.createChannels(this);
        setTitle("Настройки");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, p, p, dp(32));
        scroll.addView(root);
        setContentView(scroll);

        status = Ui.text(this, "", 15, Ui.TEXT, false);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(status, lp());

        header("Вход в ProcessMaker");
        user = field("Логин", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pass = field("Пароль", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        header("Что проверять");
        RadioGroup rg = new RadioGroup(this);
        rbUnassigned = new RadioButton(this);
        rbUnassigned.setText("Unassigned (неназначенные)");
        rbUnassigned.setId(View.generateViewId());
        rbInbox = new RadioButton(this);
        rbInbox.setText("Inbox (входящие)");
        rbInbox.setId(View.generateViewId());
        rg.addView(rbUnassigned);
        rg.addView(rbInbox);
        box.addView(rg, lp());
        interval = field("Проверять каждые N минут", InputType.TYPE_CLASS_NUMBER);
        keepAwake = toggle("Не давать телефону засыпать (уведомления без задержек, но расход батареи выше)");
        notifyExisting = toggle("При первом запуске сообщить и об уже висящих заявках");

        header("Telegram (необязательно)");
        tgToken = field("Токен бота от @BotFather", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        tgChat = field("Chat ID (несколько — через запятую)", InputType.TYPE_CLASS_TEXT);

        header("Сайт");
        host = field("Адрес сайта", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        workspace = field("Workspace", InputType.TYPE_CLASS_TEXT);

        box = root;
        primary("Сохранить и запустить", v -> saveAndStart());
        button("Проверить сейчас", v -> {
            if (save()) {
                MonitorService.start(this);
                toast("Проверяю…");
            }
        });
        button("Диагностика: что сайт отдаёт по заявке", v -> {
            if (save()) {
                diagWaiting = true;
                MonitorService.diagnose(this);
                toast("Собираю данные… (до минуты)");
            }
        });
        button("Остановить мониторинг", v -> {
            MonitorService.stop(this);
            toast("Мониторинг остановлен");
        });

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView version = Ui.text(this, "Заявки PM, версия " + ver, 12, Ui.MUTED, false);
        version.setGravity(android.view.Gravity.CENTER);
        root.addView(version, Ui.gapTop(this, 16));

        load();
        askNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.sp.registerOnSharedPreferenceChangeListener(this);
        showStatus();
        if (returnToCases) {
            returnToCases = false;
            openCases();
        }
    }

    @Override
    protected void onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if ("status".equals(key) || "enabled".equals(key)) runOnUiThread(this::showStatus);
        if ("diag".equals(key) && diagWaiting) {
            diagWaiting = false;
            runOnUiThread(this::showDiag);
        }
    }

    private void showDiag() {
        final String text = prefs.sp.getString("diag", "");
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(12);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Диагностика")
                .setView(sv)
                .setPositiveButton("Копировать", (d, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", text));
                    toast("Скопировано — вставьте в чат");
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showStatus() {
        boolean on = prefs.enabled();
        String detail = prefs.status();
        status.setText((on ? "● Мониторинг включён" : "○ Мониторинг выключен")
                + (detail.equals("Мониторинг выключен") ? "" : "\n" + detail));
        status.setTextColor(on ? Ui.GREEN : Ui.MUTED);
        status.setBackground(Ui.rounded(this, on ? Ui.GREEN_BG : Ui.CARD, on ? 0 : Ui.BORDER, 14));
    }

    private void load() {
        host.setText(prefs.host());
        workspace.setText(prefs.workspace());
        user.setText(prefs.user());
        pass.setText(prefs.pass());
        interval.setText(String.valueOf(prefs.intervalMin()));
        (prefs.listAction().equals("todo") ? rbInbox : rbUnassigned).setChecked(true);
        keepAwake.setChecked(prefs.keepAwake());
        notifyExisting.setChecked(prefs.notifyExisting());
        tgToken.setText(prefs.tgToken());
        tgChat.setText(prefs.tgChat());
    }

    private boolean save() {
        String u = user.getText().toString().trim();
        String pw = pass.getText().toString();
        if (u.isEmpty() || pw.isEmpty()) {
            toast("Введите логин и пароль");
            return false;
        }
        int min;
        try {
            min = Math.max(1, Integer.parseInt(interval.getText().toString().trim()));
        } catch (NumberFormatException e) {
            min = 1;
        }
        String list = rbInbox.isChecked() ? "todo" : "unassigned";
        String h = host.getText().toString().trim();
        String ws = workspace.getText().toString().trim();

        boolean accountChanged = !u.equals(prefs.user()) || !pw.equals(prefs.pass())
                || !h.equals(prefs.host()) || !ws.equals(prefs.workspace());
        boolean listChanged = !list.equals(prefs.listAction());

        SharedPreferences.Editor e = prefs.sp.edit()
                .putString("host", h.isEmpty() ? "https://pm1.starnet.md" : h)
                .putString("workspace", ws.isEmpty() ? "starnet" : ws)
                .putString("user", u)
                .putString("pass", pw)
                .putString("list", list)
                .putInt("interval", min)
                .putBoolean("keepAwake", keepAwake.isChecked())
                .putBoolean("notifyExisting", notifyExisting.isChecked())
                .putString("tgToken", tgToken.getText().toString().trim())
                .putString("tgChat", tgChat.getText().toString().trim());
        if (accountChanged || listChanged) e.remove("seen").putInt("failures", 0);
        e.apply();
        if (accountChanged) CookieManager.getInstance().removeAllCookies(null);
        return true;
    }

    private void saveAndStart() {
        if (!save()) return;
        MonitorService.start(this);
        toast("Мониторинг запущен");
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            openCases();
        } else {
            askBatteryExemption();
        }
    }

    // ------------------------------------------------------------------ разрешения
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void askBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        new AlertDialog.Builder(this)
                .setTitle("Работа в фоне")
                .setMessage("Чтобы Android не усыплял проверку заявок, разрешите приложению "
                        + "работать без ограничений батареи. На следующем экране нажмите «Разрешить».")
                .setPositiveButton("Хорошо", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ex) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    returnToCases = true;
                })
                .setNegativeButton("Позже", (d, w) -> openCases())
                .show();
    }

    // ------------------------------------------------------------------ UI helpers
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Новая карточка-раздел с заголовком; поля дальше добавляются в неё. */
    private void header(String text) {
        box = Ui.card(this);
        box.addView(Ui.text(this, text.toUpperCase(), 12, Ui.ACCENT, true), lp());
        root.addView(box, Ui.gapTop(this, 12));
    }

    private EditText field(String hint, int type) {
        box.addView(Ui.text(this, hint, 13, Ui.MUTED, false), Ui.gapTop(this, 10));
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(16);
        box.addView(e, lp());
        return e;
    }

    private Switch toggle(String text) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextSize(14);
        s.setPadding(0, dp(8), 0, dp(8));
        box.addView(s, Ui.gapTop(this, 4));
        return s;
    }

    private void primary(String text, View.OnClickListener l) {
        Button btn = Ui.button(this, text, 0xFFFFFFFF, Ui.ACCENT);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = Ui.gapTop(this, 16);
        lp.height = dp(52);
        root.addView(btn, lp);
    }

    private void button(String text, View.OnClickListener l) {
        Button btn = Ui.button(this, text, Ui.ACCENT, Ui.CARD);
        btn.setBackground(Ui.rounded(this, Ui.CARD, Ui.BORDER, 10));
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = Ui.gapTop(this, 8);
        lp.height = dp(48);
        root.addView(btn, lp);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
