package com.hatopigeon.cubictimer.wca;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.hatopigeon.cubicify.R;
import com.hatopigeon.cubictimer.utils.ThemeUtils;

/** Small helper that builds themed views in code for the WCA screens. */
public final class WcaUi {
    public final Context ctx;
    public final int text;
    public final int bg;
    public final int panel;
    public final int highlight;

    public WcaUi(Context c) {
        ctx = c;
        text = ThemeUtils.fetchAttrColor(c, R.attr.colorTimerText);
        bg = ThemeUtils.fetchAttrColor(c, R.attr.colorPrimary);
        panel = ColorUtils.calculateLuminance(bg) > 0.5
                ? ColorUtils.blendARGB(bg, Color.WHITE, 0.6f)
                : ColorUtils.blendARGB(bg, Color.WHITE, 0.10f);
        highlight = ColorUtils.blendARGB(panel, text, 0.16f);
    }

    public int dp(float v) {
        return ThemeUtils.dpToPix(ctx, v);
    }

    public GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    public TextView text(String s, float sp, boolean bold, float alpha) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(text);
        t.setAlpha(alpha);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    public TextView pill(String s, boolean selected) {
        TextView t = text(s, 14, true, 1f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setMinHeight(dp(40));
        stylePill(t, selected);
        return t;
    }

    public void stylePill(TextView t, boolean selected) {
        GradientDrawable g = rounded(selected ? panel : Color.TRANSPARENT, dp(20));
        g.setStroke(dp(1), (text & 0x00FFFFFF) | 0x55000000);
        t.setBackground(g);
        t.setAlpha(selected ? 1f : 0.75f);
    }

    public LinearLayout card(boolean highlighted) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = rounded(highlighted ? highlight : panel, dp(16));
        if (highlighted) g.setStroke(dp(2), (text & 0x00FFFFFF) | 0x66000000);
        c.setBackground(g);
        return c;
    }

    public LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public LinearLayout.LayoutParams matchWrap(int l, int t, int r, int b) {
        return lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, l, t, r, b);
    }

    public EditText input(String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        e.setTextColor(text);
        e.setHintTextColor((text & 0x00FFFFFF) | 0x88000000);
        e.setTextSize(16);
        e.setPadding(dp(16), dp(10), dp(16), dp(10));
        e.setMinHeight(dp(48));
        e.setBackground(rounded(panel, dp(24)));
        return e;
    }
}
