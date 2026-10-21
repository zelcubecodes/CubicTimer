package com.hatopigeon.cubictimer.activity;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;

import com.hatopigeon.cubicify.R;
import com.hatopigeon.cubictimer.utils.InsetsUtils;
import com.hatopigeon.cubictimer.utils.LocaleUtils;
import com.hatopigeon.cubictimer.utils.ThemeUtils;
import com.hatopigeon.cubictimer.wca.WcaFlags;
import com.hatopigeon.cubictimer.wca.WcaFormat;
import com.hatopigeon.cubictimer.wca.WcaHttp;
import com.hatopigeon.cubictimer.wca.WcaNames;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** World, continent and country rankings from the unofficial WCA static JSON API (updated daily). */
public class RankingsActivity extends AppCompatActivity {
    private static final String API = "https://raw.githubusercontent.com/robiningelbrecht/wca-rest-api/refs/heads/v1";
    private static final int PAGE = 25;
    private static final String[] EVENTS = {"333", "222", "444", "555", "666", "777", "333bf", "333fm",
            "333oh", "clock", "minx", "pyram", "skewb", "sq1", "444bf", "555bf", "333mbf", "fto"};
    private static final String[][] CONTINENTS = {{"world", "World"}, {"africa", "Africa"}, {"asia", "Asia"},
            {"europe", "Europe"}, {"north-america", "North America"}, {"oceania", "Oceania"},
            {"south-america", "South America"}};

    private int text, bg, itemBg, itemText, highlight;
    private String eventId = "333";
    private String region = "world";
    private String regionName = "World";
    private boolean average = false;
    private int shown = PAGE;
    private JSONArray items = new JSONArray();
    private String error = null;
    private boolean loading = false;
    private int requestToken = 0;

    private LinearLayout page;
    private LinearLayout eventRow;
    private TextView regionButton, singleTab, averageTab, status;
    private LinearLayout list;
    private final Map<String, TextView[]> rowViews = new HashMap<>();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtils.updateLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setTheme(ThemeUtils.getPreferredTheme());
        super.onCreate(savedInstanceState);
        text = ThemeUtils.fetchAttrColor(this, R.attr.colorTimerText);
        bg = ThemeUtils.fetchAttrColor(this, R.attr.colorPrimary);
        itemBg = ThemeUtils.fetchAttrColor(this, R.attr.colorItemListBackground);
        itemText = ThemeUtils.fetchAttrColor(this, R.attr.colorItemListText);
        highlight = ColorUtils.blendARGB(itemBg, itemText, 0.16f);
        buildUi();
        load();
    }

    private int dp(float v) {
        return ThemeUtils.dpToPix(this, v);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView tv(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private TextView pill(String s) {
        TextView t = tv(s, 14, true, itemText);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setMinHeight(dp(40));
        return t;
    }

    private void stylePill(TextView t, boolean selected) {
        GradientDrawable g = rounded(selected ? highlight : itemBg, dp(8));
        g.setStroke(dp(selected ? 2 : 1), (itemText & 0x00FFFFFF) | (selected ? 0x99000000 : 0x40000000));
        t.setBackground(g);
    }

    private Drawable eventIcon(String id, int sizeDp) {
        int res = getResources().getIdentifier("ic_event_" + id.toLowerCase(Locale.US), "drawable", getPackageName());
        if (res == 0) return null;
        Drawable d = androidx.core.content.ContextCompat.getDrawable(this, res);
        if (d == null) return null;
        d = androidx.core.graphics.drawable.DrawableCompat.wrap(d.mutate());
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, itemText);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_arrow_back_black_24dp);
        back.setColorFilter(text);
        back.setPadding(dp(16), dp(16), dp(16), dp(16));
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        header.addView(tv("Rankings", 20, true, text), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        ScrollView sv = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(4), dp(16), dp(24));
        sv.addView(page);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        eventRow = new LinearLayout(this);
        eventRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(eventRow);
        page.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        regionButton = pill("World  \u25BE");
        stylePill(regionButton, false);
        regionButton.setOnClickListener(v -> showRegionPicker());
        singleTab = pill("Single");
        averageTab = pill("Average");
        singleTab.setOnClickListener(v -> setType(false));
        averageTab.setOnClickListener(v -> setType(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), dp(8), dp(8));
        controls.addView(regionButton, lp);
        controls.addView(singleTab, lp);
        controls.addView(averageTab, lp);
        page.addView(controls);

        status = tv("", 13, false, text);
        status.setAlpha(0.8f);
        status.setPadding(dp(4), dp(4), dp(4), dp(4));
        status.setVisibility(View.GONE);
        page.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(8);
        page.addView(list, ll);

        TextView note = tv("Data: unofficial WCA public API, updated once a day. Names come from the WCA website.",
                12, false, text);
        note.setAlpha(0.7f);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        page.addView(note);

        setContentView(root);
        InsetsUtils.applySafeInsetsPadding(root, false);
        renderControls();
    }

    private void renderControls() {
        eventRow.removeAllViews();
        for (final String id : EVENTS) {
            boolean sel = id.equals(eventId);
            View v;
            Drawable ic = eventIcon(id, 28);
            if (ic != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(ic);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                v = iv;
            } else {
                TextView t = pill(WcaFormat.eventName(id));
                v = t;
            }
            v.setBackground(rounded(sel ? highlight : itemBg, dp(8)));
            if (sel) {
                GradientDrawable g = rounded(highlight, dp(8));
                g.setStroke(dp(2), (itemText & 0x00FFFFFF) | 0x99000000);
                v.setBackground(g);
            }
            v.setContentDescription(WcaFormat.eventName(id));
            v.setOnClickListener(x -> {
                eventId = id;
                if ("333mbf".equals(id)) average = false;
                shown = PAGE;
                renderControls();
                load();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
            p.setMargins(0, 0, dp(6), 0);
            eventRow.addView(v, p);
        }
        regionButton.setText(regionName + "  \u25BE");
        stylePill(singleTab, !average);
        stylePill(averageTab, average);
    }

    private void setType(boolean avg) {
        if (avg && "333mbf".equals(eventId)) return;
        average = avg;
        shown = PAGE;
        renderControls();
        load();
    }

    private void showRegionPicker() {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(box);
        sv.setBackground(rounded(itemBg, dp(20)));
        List<String[]> opts = new ArrayList<>();
        for (String[] c : CONTINENTS) opts.add(c);
        List<String[]> countries = new ArrayList<>();
        for (String code : Locale.getISOCountries()) {
            String n = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
            if (n != null && !n.isEmpty()) countries.add(new String[]{code, n});
        }
        Collections.sort(countries, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[1].compareToIgnoreCase(b[1]);
            }
        });
        opts.addAll(countries);
        for (final String[] o : opts) {
            String flag = o[0].length() == 2 ? WcaFlags.emoji(o[0]) : "";
            TextView row = tv((flag.isEmpty() ? "" : flag + "  ") + o[1], 16, o[0].equals(region), itemText);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setMinHeight(dp(46));
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (o[0].equals(region)) row.setBackground(rounded(highlight, dp(12)));
            row.setOnClickListener(v -> {
                region = o[0];
                regionName = o[1];
                shown = PAGE;
                renderControls();
                load();
                d.dismiss();
            });
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        d.setContentView(sv);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    // ---------------------------------------------------------------- data

    private void load() {
        final int token = ++requestToken;
        loading = true;
        error = null;
        items = new JSONArray();
        showStatus("Loading rankings...");
        renderList();
        final String base = API + "/rank/" + region + "/" + (average ? "average" : "single") + "/" + eventId;
        WcaHttp.get(base + ".json", new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                if (token == requestToken) accept(body);
            }

            @Override
            public void onError(String message) {
                if (token != requestToken) return;
                WcaHttp.get(base + "-page-1.json", new WcaHttp.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        if (token == requestToken) accept(body);
                    }

                    @Override
                    public void onError(String message2) {
                        if (token != requestToken) return;
                        loading = false;
                        String why = WcaHttp.describe(RankingsActivity.this, message);
                        error = why.startsWith("No internet") ? why : "No ranking found for this event and region";
                        showStatus(error + ".");
                        renderList();
                    }
                });
            }
        });
    }

    private void accept(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("items");
            items = arr == null ? new JSONArray() : arr;
            loading = false;
            showStatus(items.length() == 0 ? "No results for this ranking." : "");
        } catch (Exception e) {
            loading = false;
            error = "Unexpected ranking data";
            showStatus(error + ".");
        }
        renderList();
    }

    private void showStatus(String s) {
        status.setText(s);
        status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderList() {
        list.removeAllViews();
        rowViews.clear();
        int limit = Math.min(shown, items.length());
        for (int i = 0; i < limit; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            final String id = it.optString("personId");
            JSONObject rk = it.optJSONObject("rank");
            int place = i + 1;
            if (rk != null) {
                String key = "world".equals(region) ? "world"
                        : (region.length() == 2 ? "country" : "continent");
                int v = rk.optInt(key, 0);
                if (v > 0) place = v;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(rounded(itemBg, dp(8)));
            TextView rank = tv(String.valueOf(place), 16, true, itemText);
            rank.setMinWidth(dp(36));
            row.addView(rank);
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            TextView name = tv(id, 16, true, itemText);
            TextView sub = tv(id, 12, false, itemText);
            sub.setAlpha(0.7f);
            mid.addView(name);
            mid.addView(sub);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mp.setMargins(dp(4), 0, dp(8), 0);
            row.addView(mid, mp);
            row.addView(tv(WcaFormat.formatResult(it.optLong("best"), eventId, average), 16, true, itemText));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, dp(3), 0, dp(3));
            list.addView(row, rp);
            rowViews.put(id, new TextView[]{name, sub});
            final int token = requestToken;
            WcaNames.lookup(id, (wcaId, n, iso) -> runOnUiThread(() -> {
                if (token != requestToken) return;
                TextView[] tvs = rowViews.get(wcaId);
                if (tvs == null) return;
                String f = WcaFlags.emoji(iso);
                tvs[0].setText((f.isEmpty() ? "" : f + " ") + n);
            }));
        }
        if (limit < items.length()) {
            TextView more = pill("Show more");
            stylePill(more, false);
            more.setOnClickListener(v -> {
                shown += PAGE;
                renderList();
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(8);
            list.addView(more, mlp);
        }
    }
}
