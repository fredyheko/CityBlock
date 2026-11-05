package md.cityblock.zayavki;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Ввод лицензионного ключа. Без действующего ключа приложение дальше не пускает. */
public class LicenseActivity extends Activity {
    private EditText keyField;
    private TextView error;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Лицензия");
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.logo_full);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(this, 110), -2);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logo, llp);

        TextView title = Ui.text(this, "Нужен лицензионный ключ", 20, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, Ui.gapTop(this, 16));
        TextView hint = Ui.text(this, "Отправьте владельцу код этого телефона — он пришлёт ключ. "
                + "Ключ работает только на этом телефоне.", 14, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, Ui.gapTop(this, 6));

        // --- код устройства
        LinearLayout codeCard = Ui.card(this);
        codeCard.addView(Ui.text(this, "КОД ЭТОГО ТЕЛЕФОНА", 12, Ui.ACCENT, true), Ui.matchWrap());
        final String code = License.deviceCode(this);
        TextView codeView = Ui.text(this, code, 30, Ui.TEXT, true);
        codeView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        codeView.setTextIsSelectable(true);
        codeView.setGravity(Gravity.CENTER);
        codeCard.addView(codeView, Ui.gapTop(this, 8));
        LinearLayout row = new LinearLayout(this);
        Button copy = Ui.button(this, "Копировать", Ui.ACCENT, Ui.ACCENT_BG);
        copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("code", code));
            Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show();
        });
        Button share = Ui.button(this, "Отправить", Ui.ACCENT, Ui.ACCENT_BG);
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Код телефона для «Заявки PM»: " + code), "Отправить код")));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        row.addView(copy, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        half2.leftMargin = Ui.dp(this, 8);
        row.addView(share, half2);
        codeCard.addView(row, Ui.gapTop(this, 12));
        root.addView(codeCard, Ui.gapTop(this, 16));

        // --- ключ
        LinearLayout keyCard = Ui.card(this);
        keyCard.addView(Ui.text(this, "ЛИЦЕНЗИОННЫЙ КЛЮЧ", 12, Ui.ACCENT, true), Ui.matchWrap());
        keyField = new EditText(this);
        keyField.setHint("PM1-…");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keyField.setMinLines(3);
        keyField.setTextSize(13);
        keyField.setText(License.key(this));
        keyCard.addView(keyField, Ui.gapTop(this, 6));
        Button paste = Ui.button(this, "Вставить из буфера", Ui.ACCENT, Ui.CARD);
        paste.setBackground(Ui.rounded(this, Ui.CARD, Ui.BORDER, 10));
        paste.setOnClickListener(v -> {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                // в сообщении может быть лишний текст — берём сам ключ
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("PM1-[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+")
                        .matcher(t.toString().replaceAll("\\s+", ""));
                keyField.setText(m.find() ? m.group() : t);
            }
        });
        LinearLayout.LayoutParams plp = Ui.gapTop(this, 8);
        plp.height = Ui.dp(this, 44);
        keyCard.addView(paste, plp);
        error = Ui.text(this, "", 14, 0xFFC62828, false);
        keyCard.addView(error, Ui.gapTop(this, 8));
        root.addView(keyCard, Ui.gapTop(this, 12));

        Button activate = Ui.button(this, "Активировать", 0xFFFFFFFF, Ui.ACCENT);
        activate.setOnClickListener(v -> activate());
        LinearLayout.LayoutParams alp = Ui.gapTop(this, 16);
        alp.height = Ui.dp(this, 52);
        root.addView(activate, alp);

        TextView owner = Ui.text(this, "Я владелец", 13, Ui.MUTED, false);
        owner.setGravity(Gravity.CENTER);
        owner.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
        owner.setOnClickListener(v -> ownerDialog());
        root.addView(owner, Ui.matchWrap());

        if (!License.key(this).isEmpty()) {
            LicenseCore.Check c = License.check(this);
            if (!c.ok) error.setText(c.error);
        }
    }

    private void activate() {
        String k = keyField.getText().toString();
        LicenseCore.Check c = LicenseCore.check(k, License.deviceCode(this), java.time.LocalDate.now());
        if (!c.ok) {
            error.setText(c.error);
            return;
        }
        License.saveKey(this, k);
        Toast.makeText(this, "Готово! Лицензия: " + c.info.name + ", до " + human(c.info.expiry), Toast.LENGTH_LONG).show();
        openApp();
    }

    private void ownerDialog() {
        EditText f = new EditText(this);
        f.setHint("PMOWNER-…");
        f.setMinLines(3);
        f.setTextSize(12);
        int p = Ui.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, 0, p, 0);
        box.addView(f, Ui.matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("Ключ владельца")
                .setMessage("Вставьте секретный ключ владельца — с ним можно выдавать лицензии.")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    try {
                        License.setOwnerKey(this, f.getText().toString());
                        Toast.makeText(this, "Режим владельца включён", Toast.LENGTH_SHORT).show();
                        openApp();
                    } catch (Exception e) {
                        error.setText(e.getMessage());
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void openApp() {
        startActivity(new Intent(this, CasesActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    static String human(String expiry) {
        return LicenseCore.FOREVER.equals(expiry) ? "бессрочно" : expiry;
    }
}
