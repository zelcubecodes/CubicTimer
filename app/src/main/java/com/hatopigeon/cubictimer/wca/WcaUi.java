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
    /** Text/icon colour that is readable on item surfaces even if the theme's own pair is not. */
    public final int safe;

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
        int opaqueBg = ColorUtils.setAlphaComponent(itemBg, 255);
        int opaqueText = ColorUtils.setAlphaComponent(itemText, 255);
        if (ColorUtils.calculateContrast(opaqueText, opaqueBg) >= 3.0) {
            safe = itemText;
        } else {
            safe = ColorUtils.calculateLuminance(opaqueBg) > 0.5 ? 0xFF222222 : 0xFFF2F2F2;
        }
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
        t.setTextColor(safe);
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
                recolor(child, text, safe);
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

    // ---------------------------------------------------------------- profile pictures

    /** Picture URL from an object that has an "avatar" child (WCA person or search result). "" for none/default. */
    public static String avatarUrl(org.json.JSONObject owner, boolean large) {
        if (owner == null) return "";
        org.json.JSONObject a = owner.optJSONObject("avatar");
        if (a == null || a.optBoolean("is_default", false)) return "";
        String u = large ? a.optString("url", "") : a.optString("thumb_url", a.optString("url", ""));
        if (u.isEmpty() || "null".equals(u)) u = a.optString("url", "");
        return "null".equals(u) ? "" : u;
    }

    public static String initials(String name) {
        if (name == null) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || !Character.isLetter(part.charAt(0))) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (sb.length() == 2) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    /** A round profile picture. Shows a blank silhouette (like Facebook) until, and unless, the picture loads. */
    public android.widget.FrameLayout avatar(String name, String url, int sizeDp) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(ctx);
        ImageView t = new ImageView(ctx);
        t.setScaleType(ImageView.ScaleType.FIT_END);
        t.setImageDrawable(tinted(R.drawable.ic_wca_person, sizeDp, 0xFF8A8D91));
        t.setPadding(dp(sizeDp * 0.12f), dp(sizeDp * 0.12f), dp(sizeDp * 0.12f), 0);
        android.graphics.drawable.GradientDrawable oval = new android.graphics.drawable.GradientDrawable();
        oval.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        oval.setColor(0xFFDADDE1);
        t.setBackground(oval);
        t.setClipToOutline(true);
        t.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        f.addView(t, new android.widget.FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        f.setTag(Integer.valueOf(sizeDp));
        setAvatarUrl(f, url);
        return f;
    }

    /** Tapping the picture opens the whole picture, full screen. Does nothing without a real picture. */
    public void makeZoomable(android.widget.FrameLayout f, final String fullUrl) {
        if (fullUrl == null || fullUrl.isEmpty()) return;
        f.setOnClickListener(v -> {
            final Dialog d = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xE6000000);
            iv.setOnClickListener(x -> d.dismiss());
            try {
                com.bumptech.glide.Glide.with(iv).load(fullUrl).into(iv);
            } catch (Exception ignored) {
            }
            d.setContentView(iv);
            d.show();
        });
    }

    // ---------------------------------------------------------------- medals and record chips

    public static int medalColor(int place) {
        return place == 1 ? 0xFFFFC107 : place == 2 ? 0xFFB0BEC5 : 0xFFCD7F32;
    }

    public Drawable medalIcon(int place, int sizeDp) {
        return tinted(R.drawable.ic_wca_medal, sizeDp, medalColor(place));
    }

    /** A medal symbol followed by a count, e.g. a gold medal and 12. */
    public View medalCount(int place, int count, float sp) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(medalIcon(place, 22));
        l.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(String.valueOf(count), sp, true, 1f);
        t.setTextColor(safe);
        t.setPadding(dp(3), 0, dp(10), 0);
        l.addView(t);
        return l;
    }

    /** A record badge (WR, CR, NR) followed by a count. */
    public View recordCount(String tag, int count, float sp) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(badge(tag));
        TextView t = text(String.valueOf(count), sp, true, 1f);
        t.setTextColor(safe);
        t.setPadding(dp(4), 0, dp(10), 0);
        l.addView(t);
        return l;
    }

    public void setAvatarUrl(android.widget.FrameLayout f, String url) {
        if (url == null || url.isEmpty() || f.getChildCount() > 1) return;
        int size = f.getTag() instanceof Integer ? (Integer) f.getTag() : 40;
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(iv, new android.widget.FrameLayout.LayoutParams(dp(size), dp(size)));
        try {
            com.bumptech.glide.Glide.with(iv).load(url).circleCrop().into(iv);
        } catch (Exception ignored) {
        }
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

    /** Text color of a result that is a record: WR red, CR amber, NR green, PR blue. */
    public static int valueColor(String tag) {
        switch (tag) {
            case "WR": return 0xFFE53935;
            case "NR": return 0xFF00C853;
            case "PR": return 0xFF2196F3;
            default: return 0xFFF9A825;
        }
    }

    /**
     * A result with its record badge just after the number, at the top. The badge has its own
     * slot, so it never covers a digit and the numbers still line up between rows.
     */
    public LinearLayout resultCell(String label, String value, String tag, float sp, boolean bold) {
        return resultCell(label, value, tag, sp, bold, false);
    }

    /** Same, but start-aligned (for table columns whose header is also start-aligned). */
    public LinearLayout resultCell(String label, String value, String tag, float sp, boolean bold, boolean start) {
        LinearLayout f = new LinearLayout(ctx);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(start ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.END);
        TextView t = text(label.isEmpty() ? value : label + " " + value, sp, bold, 1f);
        t.setGravity(start ? Gravity.START : Gravity.END);
        t.setPadding(0, start ? 0 : dp(7), 0, 0);
        boolean has = tag != null && !tag.isEmpty();
        if (has) t.setTextColor(valueColor(tag.toUpperCase(java.util.Locale.US)));
        f.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout slot = new FrameLayout(ctx);
        if (has) {
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (start ? Gravity.CENTER_VERTICAL : Gravity.TOP) | Gravity.START);
            bp.leftMargin = dp(2);
            slot.addView(badge(tag), bp);
        }
        f.addView(slot, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    /** A heading or note with a solid, themed background (never transparent). */
    public TextView label(String s, float sp, boolean bold) {
        TextView t = text(s, sp, bold, 1f);
        styleLabel(t);
        return t;
    }

    public void styleLabel(TextView t) {
        t.setTextColor(safe);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(rounded(itemBg, dp(8)));
    }

    /** A status line that only shows (with its background) while it has text. */
    public TextView status(float sp) {
        final TextView t = text("", sp, false, 0.8f);
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
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
        recolor(panel, text, safe);
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
        e.setTextColor(safe);
        e.setHintTextColor((itemText & 0x00FFFFFF) | 0x88000000);
        e.setTextSize(16);
        e.setPadding(dp(16), dp(10), dp(16), dp(10));
        e.setMinHeight(dp(48));
        e.setBackground(rounded(itemBg, dp(8)));
        attachClear(e);
        return e;
    }

    /** Shows an X at the far right of a search box while it has text; tapping it clears the box. */
    public void attachClear(final EditText e) {
        final Drawable x = tinted(R.drawable.ic_wca_close, 20, safe);
        e.setCompoundDrawablePadding(dp(8));
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable ed) {
                e.setCompoundDrawablesRelative(null, null, ed.length() > 0 ? x : null, null);
            }
        });
        e.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP && e.getText().length() > 0) {
                Drawable[] d = e.getCompoundDrawablesRelative();
                if (d[2] != null && ev.getX() >= e.getWidth() - e.getPaddingEnd() - d[2].getBounds().width() - dp(12)) {
                    e.setText("");
                    return true;
                }
            }
            return false;
        });
    }
}
