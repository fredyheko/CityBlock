package md.cityblock.zayavki;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Главный экран: заявки карточками, вкладки Unassigned / Inbox. */
public class CasesActivity extends Activity {
    static final String[] TABS = {"unassigned", "todo", "draft"};
    static final String[] TAB_NAMES = {"Unassigned", "Inbox", "Draft"};
    private static final long AUTO_REFRESH_MS = 60_000;

    /** Подробности заявок, загруженные за время работы приложения. */
    private static final Map<String, PmCore.Case> DETAILS = new HashMap<>();
    /** Что было в списке при прошлом показе — чтобы подсветить новые. */
    private static final Map<String, Set<String>> SHOWN = new HashMap<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = this::refresh;
    private boolean resumed = false;

    private String tab = TABS[0];
    private int generation = 0;
    private List<PmCore.Case> current = new ArrayList<>();
    private Set<String> fresh = new HashSet<>();

    private LinearLayout list;
    private TextView status;
    private Button[] tabButtons = new Button[TABS.length];
    private final int[] counts = {-1, -1, -1};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Заявки");
        Notifier.createChannels(this);
        if (b != null) tab = b.getString("tab", tab);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 4));
        for (int i = 0; i < TABS.length; i++) {
            final String t = TABS[i];
            Button btn = Ui.button(this, TAB_NAMES[i], Ui.TEXT, Ui.CARD);
            btn.setOnClickListener(v -> switchTab(t));
            btn.setTextSize(14);
            btn.setMaxLines(1);
            btn.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            btn.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            tabs.addView(btn, lp);
            tabButtons[i] = btn;
        }
        Button plus = Ui.button(this, "+", 0xFFFFFFFF, Ui.GREEN);
        plus.setTextSize(24);
        plus.setPadding(0, 0, 0, 0);
        plus.setOnClickListener(v -> newCase());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44));
        plp.leftMargin = Ui.dp(this, 6);
        tabs.addView(plus, plp);
        root.addView(tabs, Ui.matchWrap());

        status = Ui.text(this, "", 13, Ui.MUTED, false);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 4));
        root.addView(status, Ui.matchWrap());

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        paintTabs();

        if (!new Prefs(this).configured()) {
            startActivity(new Intent(this, MainActivity.class));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Обновить").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, 2, 0, "Сайт PM");
        m.add(0, 3, 0, "Настройки и уведомления");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: refresh(); return true;
            case 2: startActivity(new Intent(this, WebActivity.class)); return true;
            case 3: startActivity(new Intent(this, MainActivity.class)); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void switchTab(String t) {
        if (t.equals(tab)) return;
        tab = t;
        current = new ArrayList<>();
        paintTabs();
        render();
        refresh();
    }

    private void paintTabs() {
        for (int i = 0; i < TABS.length; i++) {
            boolean on = TABS[i].equals(tab);
            String label = TAB_NAMES[i] + (counts[i] >= 0 ? "  " + counts[i] : "");
            tabButtons[i].setText(label);
            tabButtons[i].setTextColor(on ? 0xFFFFFFFF : Ui.TEXT);
            tabButtons[i].setBackground(Ui.rounded(this, on ? Ui.ACCENT : Ui.CARD, on ? 0 : Ui.BORDER, 10));
        }
    }

    // ------------------------------------------------------------------ загрузка
    private void refresh() {
        main.removeCallbacks(autoRefresh);
        if (!new Prefs(this).configured()) {
            status.setText("Укажите логин и пароль: меню → Настройки");
            return;
        }
        final int gen = ++generation;
        final String act = tab;
        status.setText(current.isEmpty() ? "Загрузка…" : "Обновление…");
        pool.execute(() -> {
            try {
                List<PmCore.Case> cs = PmSession.cases(this, act);
                synchronized (DETAILS) {
                    for (PmCore.Case c : cs) {
                        PmCore.Case d = DETAILS.get(c.key);
                        if (d != null) PmCore.copyDetails(d, c);
                    }
                }
                main.post(() -> {
                    if (gen != generation) return;
                    Set<String> before = SHOWN.get(act);
                    Set<String> now = PmCore.keys(cs, new HashSet<String>());
                    fresh = new HashSet<>();
                    if (before != null) for (String k : now) if (!before.contains(k)) fresh.add(k);
                    SHOWN.put(act, now);
                    current = cs;
                    counts[indexOf(act)] = cs.size();
                    paintTabs();
                    status.setText("Обновлено в " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                    render();
                    loadMissingDetails(gen, act, cs);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (gen != generation) return;
                    status.setText("⚠️ " + e.getMessage());
                    if (current.isEmpty()) render();
                });
            } finally {
                main.post(() -> {
                    if (resumed && gen == generation) main.postDelayed(autoRefresh, AUTO_REFRESH_MS);
                });
            }
        });
    }

    private void loadMissingDetails(int gen, String act, List<PmCore.Case> cs) {
        for (PmCore.Case c : cs) {
            if (PmCore.hasDetails(c)) continue;
            pool.execute(() -> {
                if (gen != generation) return;
                PmSession.details(this, c, act);
                synchronized (DETAILS) {
                    DETAILS.put(c.key, c);
                }
                main.post(() -> { if (gen == generation) render(); });
            });
        }
    }

    private static int indexOf(String act) {
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(act)) return i;
        return 0;
    }

    // ------------------------------------------------------------------ отображение
    private void render() {
        list.removeAllViews();
        if (current.isEmpty()) {
            TextView empty = Ui.text(this, status.getText().toString().startsWith("Обновлено")
                    ? "Заявок нет 🎉" : "", 16, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 48), 0, 0);
            list.addView(empty, Ui.matchWrap());
            return;
        }
        for (PmCore.Case c : current) list.addView(cardFor(c), Ui.gapTop(this, 10));
    }

    private View cardFor(PmCore.Case c) {
        PmCore.Title t = PmCore.title(c.title);
        LinearLayout card = Ui.card(this);
        if (fresh.contains(c.key)) card.setBackground(Ui.rounded(this, Ui.CARD, Ui.GREEN, 14));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(this, "#" + c.number, 13, Ui.MUTED, false), Ui.wrap());
        if (fresh.contains(c.key)) addChip(top, Ui.chip(this, "новая", Ui.GREEN, Ui.GREEN_BG));
        String slot = Ui.slotLabel(t);
        if (!slot.isEmpty()) addChip(top, Ui.chip(this, slot, Ui.ACCENT, Ui.ACCENT_BG));
        if (!t.type.isEmpty()) addChip(top, Ui.chip(this, t.type, Ui.ORANGE, Ui.ORANGE_BG));
        card.addView(top, Ui.matchWrap());

        boolean draft = "draft".equals(tab);
        if ((draft || PmCore.isBareTitle(c.title)) && !c.process.isEmpty()) {
            // черновик: главное — что это за процесс и на каком он этапе
            card.addView(Ui.text(this, c.process, 17, Ui.TEXT, true), Ui.gapTop(this, 8));
            if (!c.task.isEmpty()) card.addView(Ui.text(this, "Этап: " + c.task, 14, Ui.MUTED, false), Ui.gapTop(this, 2));
            if (!PmCore.isBareTitle(c.title)) card.addView(Ui.lines(this, c.title, 2), Ui.gapTop(this, 6));
        } else {
            TextView addr = Ui.text(this, t.address, 17, Ui.TEXT, true);
            card.addView(addr, Ui.gapTop(this, 8));
            if (!t.date.isEmpty()) card.addView(Ui.text(this, t.date, 13, Ui.MUTED, false), Ui.gapTop(this, 2));
        }

        if (!c.client.isEmpty()) card.addView(Ui.lines(this, "👤 " + c.client, 1), Ui.gapTop(this, 8));
        List<CaseDetails.Comment> comments = CaseDetails.commentEntries(c.description);
        if (!comments.isEmpty()) {
            CaseDetails.Comment latest = comments.get(0); // в описании новые записи идут первыми
            card.addView(Ui.lines(this, "💬 " + latest.text, 2), Ui.gapTop(this, 4));
        } else if (!PmCore.hasDetails(c)) {
            card.addView(Ui.text(this, "загрузка подробностей…", 13, Ui.MUTED, false), Ui.gapTop(this, 6));
        }

        if (!c.phones.isEmpty()) {
            String ph = c.phones.get(0);
            Button call = Ui.button(this, "📞  " + CaseDetails.pretty(ph), Ui.GREEN, Ui.GREEN_BG);
            call.setOnClickListener(v -> Ui.dial(this, ph));
            LinearLayout.LayoutParams lp = Ui.gapTop(this, 10);
            lp.height = Ui.dp(this, 44);
            card.addView(call, lp);
        }

        card.setOnClickListener(v -> startActivity(CaseActivity.intent(this, c, tab)));
        return card;
    }

    // ------------------------------------------------------------------ New case
    private void newCase() {
        android.app.AlertDialog loading = new android.app.AlertDialog.Builder(this)
                .setTitle("Новая заявка").setMessage("Загружаю список процессов…").show();
        pool.execute(() -> {
            try {
                List<PmCore.StartItem> items = PmSession.startList(this);
                main.post(() -> {
                    loading.dismiss();
                    if (!isFinishing()) chooseProcess(items);
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading.dismiss();
                    if (!isFinishing()) startFallback(e.getMessage());
                });
            }
        });
    }

    private void chooseProcess(List<PmCore.StartItem> items) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
        sv.addView(box);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Какую заявку создать?").setView(sv)
                .setNegativeButton("Отмена", null).create();
        String lastCat = null;
        for (PmCore.StartItem it : items) {
            if (!it.category.equals(lastCat)) {
                lastCat = it.category;
                if (!lastCat.isEmpty()) {
                    TextView h = Ui.text(this, lastCat.toUpperCase(), 12, Ui.ACCENT, true);
                    h.setPadding(Ui.dp(this, 8), Ui.dp(this, 14), 0, Ui.dp(this, 4));
                    box.addView(h, Ui.matchWrap());
                }
            }
            TextView row = Ui.text(this, it.title, 16, Ui.TEXT, false);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            row.setBackground(Ui.rounded(this, Ui.BG, 0, 10));
            row.setOnClickListener(v -> {
                dlg.dismiss();
                confirmStart(it);
            });
            box.addView(row, Ui.gapTop(this, 6));
        }
        dlg.show();
    }

    private void confirmStart(PmCore.StartItem it) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Создать заявку?")
                .setMessage(it.title)
                .setPositiveButton("Start case", (d, w) -> start(it))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void start(PmCore.StartItem it) {
        status.setText("Создаю заявку «" + it.title + "»…");
        pool.execute(() -> {
            try {
                String[] r = PmSession.startCase(this, it);
                main.post(() -> {
                    PmCore.Case c = new PmCore.Case();
                    c.uid = r[0];
                    c.delIndex = r[1];
                    c.number = "";
                    status.setText("Заявка создана — она во вкладке Draft, пока не отправлена");
                    startActivity(WebActivity.formIntent(this, c, "draft"));
                });
            } catch (Exception e) {
                main.post(() -> startFallback(e.getMessage()));
            }
        });
    }

    /** Если не получилось своими силами — страница New case самого PM. */
    private void startFallback(String error) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Не получилось")
                .setMessage(error + "\n\nОткрыть страницу «New case» в PM?")
                .setPositiveButton("Открыть", (d, w) -> startActivity(WebActivity.intent(this,
                        PmCore.startPageUrl(new Prefs(this).base()))))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void addChip(LinearLayout row, TextView chip) {
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.leftMargin = Ui.dp(this, 8);
        row.addView(chip, lp);
    }
}
