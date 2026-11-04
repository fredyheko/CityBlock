package md.cityblock.zayavki;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/** Экран заявки: адрес, клиент, телефон, комментарии, техданные, заметки; открыть/взять в PM. */
public class CaseActivity extends Activity {
    static final String EXTRA_CASE = "case";
    static final String EXTRA_ACTION = "action";

    private PmCore.Case c;
    private String action;
    private LinearLayout root;

    static Intent intent(Context ctx, PmCore.Case c, String action) {
        return new Intent(ctx, CaseActivity.class)
                .putExtra(EXTRA_CASE, PmCore.toJson(c).toString())
                .putExtra(EXTRA_ACTION, action);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        action = getIntent().getStringExtra(EXTRA_ACTION);
        if (action == null) action = "unassigned";
        try {
            c = PmCore.fromJson(new JSONObject(getIntent().getStringExtra(EXTRA_CASE)));
        } catch (Exception e) {
            finish();
            return;
        }
        setTitle("Заявка #" + c.number);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        root.setPadding(p, p, p, Ui.dp(this, 32));
        sv.addView(root);
        setContentView(sv);
        render();

        if (!PmCore.hasDetails(c)) {
            new Thread(() -> {
                PmSession.details(this, c, action);
                new Handler(Looper.getMainLooper()).post(() -> { if (!isFinishing()) render(); });
            }).start();
        }
    }

    private void render() {
        root.removeAllViews();
        PmCore.Title t = PmCore.title(c.title);

        // --- адрес и время
        LinearLayout head = Ui.card(this);
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        boolean first = true;
        if (!t.slot.isEmpty()) { addChip(chips, Ui.chip(this, "🕒 " + t.slot, Ui.ACCENT, Ui.ACCENT_BG), first); first = false; }
        if (!t.type.isEmpty()) { addChip(chips, Ui.chip(this, t.type, Ui.ORANGE, Ui.ORANGE_BG), first); first = false; }
        if (chips.getChildCount() > 0) head.addView(chips, Ui.matchWrap());
        boolean bare = ("draft".equals(action) || PmCore.isBareTitle(c.title)) && !c.process.isEmpty();
        TextView addr = Ui.text(this, bare ? c.process : t.address, 19, Ui.TEXT, true);
        addr.setTextIsSelectable(true);
        head.addView(addr, Ui.gapTop(this, chips.getChildCount() > 0 ? 8 : 0));
        if (bare && !c.task.isEmpty()) head.addView(Ui.text(this, "Этап: " + c.task, 14, Ui.MUTED, false), Ui.gapTop(this, 2));
        if (bare && !PmCore.isBareTitle(c.title)) head.addView(Ui.text(this, c.title, 14, Ui.TEXT, false), Ui.gapTop(this, 4));
        if (!bare && !t.date.isEmpty()) head.addView(Ui.text(this, t.date, 14, Ui.MUTED, false), Ui.gapTop(this, 2));
        String svc = CaseDetails.service(c.description);
        if (!svc.isEmpty()) head.addView(Ui.text(this, svc, 14, Ui.MUTED, false), Ui.gapTop(this, 6));
        if (!t.account.isEmpty()) head.addView(Ui.text(this, "Аккаунт: " + t.account, 14, Ui.MUTED, false), Ui.gapTop(this, 2));
        root.addView(head, Ui.matchWrap());

        // --- клиент
        if (!c.client.isEmpty() || !c.phones.isEmpty()) {
            LinearLayout cl = section("Клиент");
            if (!c.client.isEmpty()) cl.addView(Ui.text(this, c.client, 17, Ui.TEXT, true), Ui.gapTop(this, 6));
            for (String ph : c.phones) {
                Button call = Ui.button(this, "📞  Позвонить  " + CaseDetails.pretty(ph), Ui.GREEN, Ui.GREEN_BG);
                call.setOnClickListener(v -> Ui.dial(this, ph));
                call.setOnLongClickListener(v -> { copy(CaseDetails.international(ph)); return true; });
                LinearLayout.LayoutParams lp = Ui.gapTop(this, 10);
                lp.height = Ui.dp(this, 48);
                cl.addView(call, lp);
            }
        }

        // --- что сделать (комментарии из описания)
        List<CaseDetails.Comment> comments = CaseDetails.commentEntries(c.description);
        if (!comments.isEmpty()) {
            LinearLayout cm = section("Комментарии");
            for (CaseDetails.Comment k : comments) {
                cm.addView(Ui.text(this, k.text, 16, Ui.TEXT, false), Ui.gapTop(this, 8));
                String meta = (k.author.isEmpty() ? "" : k.author + " · ") + k.when;
                cm.addView(Ui.text(this, meta, 12, Ui.MUTED, false), Ui.gapTop(this, 1));
            }
        }

        // --- техническая информация
        List<String> tech = CaseDetails.techLines(c.description);
        if (!tech.isEmpty()) {
            LinearLayout te = section("Подключение");
            for (String line : tech) {
                TextView tv = Ui.text(this, "• " + line, 15, Ui.TEXT, false);
                tv.setTextIsSelectable(true);
                te.addView(tv, Ui.gapTop(this, 4));
            }
        }

        // --- если описание нестандартное — показываем целиком
        if (!c.description.isEmpty() && comments.isEmpty() && tech.isEmpty()) {
            LinearLayout de = section("Описание");
            TextView tv = Ui.text(this, c.description, 15, Ui.TEXT, false);
            tv.setTextIsSelectable(true);
            de.addView(tv, Ui.gapTop(this, 6));
        }

        // --- заметки
        if (!c.notes.isEmpty()) {
            LinearLayout no = section("Case Notes");
            for (int i = c.notes.size() - 1; i >= 0; i--) {
                no.addView(Ui.text(this, c.notes.get(i), 14, Ui.TEXT, false), Ui.gapTop(this, 6));
            }
        }

        if (!PmCore.hasDetails(c)) {
            TextView wait = Ui.text(this, "Загрузка подробностей…", 14, Ui.MUTED, false);
            wait.setGravity(Gravity.CENTER);
            root.addView(wait, Ui.gapTop(this, 16));
        }

        // --- прочее
        LinearLayout info = section("Процесс");
        info.addView(Ui.text(this, c.process + (c.task.isEmpty() ? "" : "\n" + c.task), 14, Ui.MUTED, false), Ui.gapTop(this, 4));
        if (!c.sentBy.isEmpty()) info.addView(Ui.text(this, "Отправил: " + c.sentBy, 14, Ui.MUTED, false), Ui.gapTop(this, 2));
        if (!c.due.isEmpty()) info.addView(Ui.text(this, "Срок: " + c.due, 14, Ui.MUTED, false), Ui.gapTop(this, 2));

        // --- действия
        String base = new Prefs(this).base();
        boolean unassigned = "unassigned".equals(action);
        boolean draft = "draft".equals(action);
        Button main = Ui.button(this, unassigned ? "✋  Взять заявку"
                : draft ? "📝  Продолжить заполнение" : "📝  Заполнить заявку", 0xFFFFFFFF, Ui.ACCENT);
        main.setOnClickListener(v -> {
            if (unassigned) confirmClaim(main);
            else startActivity(WebActivity.formIntent(this, c, draft ? "draft" : "todo"));
        });
        LinearLayout.LayoutParams lp = Ui.gapTop(this, 16);
        lp.height = Ui.dp(this, 52);
        root.addView(main, lp);

        Button full = Ui.button(this, "Открыть в полном PM", Ui.ACCENT, Ui.CARD);
        full.setBackground(Ui.rounded(this, Ui.CARD, Ui.BORDER, 10));
        full.setOnClickListener(v -> startActivity(WebActivity.intent(this, PmCore.openCaseUrl(base, c, action))));
        LinearLayout.LayoutParams lp2 = Ui.gapTop(this, 8);
        lp2.height = Ui.dp(this, 46);
        root.addView(full, lp2);
    }

    private void confirmClaim(Button btn) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Взять заявку #" + c.number + "?")
                .setMessage(PmCore.title(c.title).address)
                .setPositiveButton("Взять", (d, w) -> claim(btn))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void claim(Button btn) {
        btn.setEnabled(false);
        btn.setText("Беру заявку…");
        new Thread(() -> {
            try {
                PmCore.Case mine = PmSession.claim(this, c);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    c = mine;
                    action = "todo";
                    getIntent().putExtra(EXTRA_CASE, PmCore.toJson(c).toString()).putExtra(EXTRA_ACTION, action);
                    Toast.makeText(this, "Заявка ваша ✓", Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    btn.setEnabled(true);
                    btn.setText("✋  Взять заявку");
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Не получилось взять")
                            .setMessage(e.getMessage())
                            .setPositiveButton("Открыть в PM", (d, w) -> startActivity(WebActivity.intent(this,
                                    PmCore.openCaseUrl(new Prefs(this).base(), c, "unassigned"))))
                            .setNegativeButton("OK", null)
                            .show();
                });
            }
        }).start();
    }

    private LinearLayout section(String title) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, title.toUpperCase(), 12, Ui.ACCENT, true), Ui.matchWrap());
        root.addView(card, Ui.gapTop(this, 10));
        return card;
    }

    private void addChip(LinearLayout row, TextView chip, boolean first) {
        LinearLayout.LayoutParams lp = Ui.wrap();
        if (!first) lp.leftMargin = Ui.dp(this, 8);
        row.addView(chip, lp);
    }

    private void copy(String s) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("phone", s));
        Toast.makeText(this, "Номер скопирован", Toast.LENGTH_SHORT).show();
    }
}
