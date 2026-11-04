package md.cityblock.zayavki;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Общие элементы интерфейса. */
final class Ui {
    static final int BG = 0xFFF1F3F6;
    static final int CARD = 0xFFFFFFFF;
    static final int TEXT = 0xFF1C1E21;
    static final int MUTED = 0xFF65676B;
    static final int ACCENT = 0xFF1565C0;
    static final int ACCENT_BG = 0xFFE3EEFB;
    static final int GREEN = 0xFF2E7D32;
    static final int GREEN_BG = 0xFFE6F4EA;
    static final int ORANGE = 0xFFB45309;
    static final int ORANGE_BG = 0xFFFDF0DC;
    static final int BORDER = 0xFFDADDE1;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(c, CARD, BORDER, 14));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView chip(Context c, String s, int color, int bg) {
        TextView t = text(c, s, 13, color, true);
        t.setBackground(rounded(c, bg, 0, 20));
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        return t;
    }

    static TextView lines(Context c, String s, int max) {
        TextView t = text(c, s, 14, TEXT, false);
        t.setMaxLines(max);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static Button button(Context c, String s, int color, int bg) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(color);
        b.setBackground(rounded(c, bg, 0, 10));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        return b;
    }

    static LinearLayout.LayoutParams gapTop(Context c, int dpTop) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(c, dpTop);
        return lp;
    }

    static void dial(Context c, String phone) {
        c.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)));
    }

    /** «sectoral, slot» — подпись времени для карточки. */
    static String slotLabel(PmCore.Title t) {
        if (!t.slot.isEmpty()) return "🕒 " + t.slot;
        return "";
    }
}
