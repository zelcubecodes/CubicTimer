package com.hatopigeon.cubictimer.wca;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    /** Colors used by the Previous Solves boxes; the WCA boxes use the same ones. */
    public final int itemBg;
    public final int itemText;

    public WcaUi(Context c) {
        ctx = c;
        text = ThemeUtils.fetchAttrColor(c, R.attr.colorTimerText);
        bg = ThemeUtils.fetchAttrColor(c, R.attr.colorPrimary);
        panel = ColorUtils.calculateLuminance(bg) > 0.5
                ? ColorUtils.blendARGB(bg, Color.WHITE, 0.6f)
                : ColorUtils.blendARGB(bg, Color.WHITE, 0.10f);
        itemBg = ThemeUtils.fetchAttrColor(c, R.attr.colorItemListBackground);
        itemText = ThemeUtils.fetchAttrColor(c, R.attr.colorItemListText);
        highlight = ColorUtils.blendARGB(itemBg, itemText, 0.16f);
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
        t.setTextColor(itemText);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setMinHeight(dp(40));
        stylePill(t, selected);
        return t;
    }

    public void stylePill(TextView t, boolean selected) {
        GradientDrawable g = rounded(selected ? highlight : itemBg, dp(8));
        g.setStroke(dp(selected ? 2 : 1), (itemText & 0x00FFFFFF) | (selected ? 0x99000000 : 0x40000000));
        t.setBackground(g);
        t.setAlpha(selected ? 1f : 0.85f);
    }

    /** A solve box: same background and corner radius as the boxes on the Previous Solves page. */
    public LinearLayout card(boolean highlighted) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = rounded(highlighted ? highlight : itemBg, dp(8));
        if (highlighted) g.setStroke(dp(2), (itemText & 0x00FFFFFF) | 0x66000000);
        c.setBackground(g);
        c.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                recolor(child, text, itemText);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        return c;
    }

    /** Text made with text() uses the page text color; inside a box it must use the box text color. */
    public void recolor(View v, int from, int to) {
        if (v instanceof TextView && !(v instanceof EditText)) {
            TextView t = (TextView) v;
            if (!"badge".equals(t.getTag()) && t.getCurrentTextColor() == from) t.setTextColor(to);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolor(g.getChildAt(i), from, to);
        }
    }

    // ---------------------------------------------------------------- icons

    public Drawable eventIcon(String eventId, int sizeDp, int color) {
        if (eventId == null) return null;
        int res = ctx.getResources().getIdentifier("ic_event_" + eventId.toLowerCase(java.util.Locale.US),
                "drawable", ctx.getPackageName());
        if (res == 0) return null;
        return tinted(res, sizeDp, color);
    }

    public Drawable tinted(int resId, int sizeDp, int color) {
        Drawable d = androidx.core.content.ContextCompat.getDrawable(ctx, resId);
        if (d == null) return null;
        d = androidx.core.graphics.drawable.DrawableCompat.wrap(d.mutate());
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, color);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    /** Puts the event's icon to the left of a text view's text. Does nothing if there is no icon. */
    public void setEventIcon(TextView t, String eventId, int sizeDp, int color) {
        Drawable d = eventIcon(eventId, sizeDp, color);
        if (d == null) return;
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(dp(6));
    }

    public ImageView iconButton(int resId, int sizeDp, int color) {
        ImageView v = new ImageView(ctx);
        v.setImageDrawable(tinted(resId, sizeDp, color));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setMinimumWidth(dp(44));
        v.setMinimumHeight(dp(44));
        return v;
    }

    // ---------------------------------------------------------------- record badges

    public static int badgeColor(String tag) {
        switch (tag) {
            case "WR": return 0xFFFF5252;
            case "NR": return 0xFF00E676;
            case "PR": return 0xFFBDBDBD;
            default: return 0xFFFFEB3B; // CR and the named continents (AsR, ER, ...)
        }
    }

    public TextView badge(String tag) {
        TextView b = new TextView(ctx);
        b.setText(tag);
        b.setTextSize(10);
        b.setTextColor(Color.BLACK);
        b.setTag("badge");
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setPadding(dp(4), dp(1), dp(4), dp(1));
        b.setBackground(rounded(badgeColor(tag), dp(4)));
        return b;
    }

    /** A result with its record badge at the top right, like WCA Live. Space is always kept for the badge. */
    public FrameLayout resultCell(String label, String value, String tag, float sp, boolean bold) {
        FrameLayout f = new FrameLayout(ctx);
        TextView t = text(label.isEmpty() ? value : label + " " + value, sp, bold, 1f);
        t.setGravity(Gravity.END);
        t.setPadding(0, dp(7), dp(13), 0);
        f.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        if (tag != null && !tag.isEmpty()) {
            f.addView(badge(tag), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        }
        return f;
    }

    /** A heading or note with a solid, themed background (never transparent). */
    public TextView label(String s, float sp, boolean bold) {
        TextView t = text(s, sp, bold, 1f);
        styleLabel(t);
        return t;
    }

    public void styleLabel(TextView t) {
        t.setTextColor(itemText);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(rounded(itemBg, dp(8)));
    }

    /** A status line that only shows (with its background) while it has text. */
    public TextView status(float sp) {
        final TextView t = text("", sp, false, 1f);
        styleLabel(t);
        t.setVisibility(View.GONE);
        t.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                t.setVisibility(e.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
        return t;
    }

    // ---------------------------------------------------------------- dialog

    /** A rounded, themed pop-up. Colors come from the theme, so it follows light and dark themes. */
    public Dialog dialog(View content) {
        Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = rounded(itemBg, dp(20));
        g.setStroke(dp(1), (itemText & 0x00FFFFFF) | 0x33000000);
        panel.setBackground(g);
        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        recolor(panel, text, itemText);
        d.setContentView(panel);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int w = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f);
            d.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
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
        e.setTextColor(itemText);
        e.setHintTextColor((itemText & 0x00FFFFFF) | 0x88000000);
        e.setTextSize(16);
        e.setPadding(dp(16), dp(10), dp(16), dp(10));
        e.setMinHeight(dp(48));
        e.setBackground(rounded(itemBg, dp(8)));
        return e;
    }
}
