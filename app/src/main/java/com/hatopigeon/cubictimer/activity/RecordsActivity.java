package com.hatopigeon.cubictimer.activity;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.hatopigeon.cubicify.R;
import com.hatopigeon.cubictimer.utils.InsetsUtils;
import com.hatopigeon.cubictimer.utils.LocaleUtils;
import com.hatopigeon.cubictimer.utils.Prefs;
import com.hatopigeon.cubictimer.utils.ThemeUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows the history of world records for every event, from the WCA results export that is
 * bundled in assets/wr_history.json. Nothing here is fetched live.
 */
public class RecordsActivity extends AppCompatActivity {

    private static final String STATE_EVENT = "state_event";
    private static final String STATE_AVERAGE = "state_average";

    private JSONObject mData;
    private final List<String> mEventIds = new ArrayList<>();
    private final List<String> mEventNames = new ArrayList<>();
    private int mEventIndex = 0;
    private boolean mShowAverage = false;

    private int mTextColor;
    private int mBgColor;
    private int mPanelColor;
    private int mCardColor;

    private TextView mEventButton;
    private TextView mSingleTab;
    private TextView mAverageTab;
    private TextView mEmptyText;
    private TextView mLiveNote;
    private int mLiveToken = 0;
    private LineChart mChart;
    private RecyclerView mList;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtils.updateLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setTheme(ThemeUtils.getPreferredTheme());
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 &&
                !Prefs.getBoolean(R.string.pk_tint_navigation_bar, false)) {
            Window window = getWindow();
            if (window != null) {
                window.setNavigationBarColor(0xB3000000);
            }
        }

        mTextColor = ThemeUtils.fetchAttrColor(this, R.attr.colorTimerText);
        mBgColor = ThemeUtils.fetchAttrColor(this, R.attr.colorPrimary);
        // The foreground panel is a lighter surface on top of the background.
        mPanelColor = ColorUtils.calculateLuminance(mBgColor) > 0.5
                ? ColorUtils.blendARGB(mBgColor, Color.WHITE, 0.6f)
                : ColorUtils.blendARGB(mBgColor, Color.WHITE, 0.10f);
        mCardColor = ColorUtils.blendARGB(mPanelColor, mTextColor, 0.07f);

        boolean loaded = loadData();

        if (savedInstanceState != null) {
            mEventIndex = Math.max(0, Math.min(savedInstanceState.getInt(STATE_EVENT, 0),
                    Math.max(0, mEventIds.size() - 1)));
            mShowAverage = savedInstanceState.getBoolean(STATE_AVERAGE, false);
        }

        buildUi(loaded);
        if (loaded) {
            refresh();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_EVENT, mEventIndex);
        outState.putBoolean(STATE_AVERAGE, mShowAverage);
    }

    // ---------------------------------------------------------------- data

    private boolean loadData() {
        try (InputStream in = getAssets().open("wr_history_v2.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            mData = new JSONObject(out.toString("UTF-8"));
            JSONObject events = mData.getJSONObject("events");
            JSONArray order = mData.getJSONArray("order");
            for (int i = 0; i < order.length(); i++) {
                String id = order.getString(i);
                mEventIds.add(id);
                mEventNames.add(events.getJSONObject(id).getString("name"));
            }
            return !mEventIds.isEmpty();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private JSONArray currentRecords() {
        try {
            JSONObject ev = mData.getJSONObject("events").getJSONObject(mEventIds.get(mEventIndex));
            return ev.getJSONArray(mShowAverage ? "average" : "single");
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private String currentNote() {
        try {
            return mData.getJSONObject("events").getJSONObject(mEventIds.get(mEventIndex))
                    .optString("note", "");
        } catch (Exception e) {
            return "";
        }
    }

    private String currentFormat() {
        try {
            return mData.getJSONObject("events").getJSONObject(mEventIds.get(mEventIndex))
                    .getString("format");
        } catch (Exception e) {
            return "time";
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return ThemeUtils.dpToPix(this, v);
    }

    private TextView text(String s, float sp, boolean bold, float alpha) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(mTextColor);
        t.setAlpha(alpha);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void styleTab(TextView t, boolean selected) {
        t.setBackground(selected ? rounded(mPanelColor, dp(24)) : null);
        t.setAlpha(selected ? 1f : 0.6f);
    }

    private void buildUi(boolean loaded) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mBgColor);

        // Header: back arrow and title.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_arrow_back_black_24dp);
        back.setColorFilter(mTextColor);
        back.setPadding(dp(16), dp(16), dp(16), dp(16));
        back.setContentDescription(getString(R.string.records_back));
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text(getString(R.string.records_title), 20, true, 1f);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        if (!loaded) {
            TextView err = text(getString(R.string.records_load_error), 16, false, 1f);
            err.setPadding(dp(16), dp(16), dp(16), dp(16));
            root.addView(err);
            setContentView(root);
            InsetsUtils.applySafeInsetsPadding(root, false);
            return;
        }

        // Event picker: a rounded pill.
        mEventButton = text("", 18, true, 1f);
        mEventButton.setGravity(Gravity.CENTER_VERTICAL);
        mEventButton.setPadding(dp(20), dp(12), dp(20), dp(12));
        mEventButton.setMinHeight(dp(52));
        mEventButton.setBackground(rounded(mPanelColor, dp(26)));
        mEventButton.setOnClickListener(v -> showEventPicker());
        LinearLayout.LayoutParams pickerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickerLp.setMargins(dp(16), dp(4), dp(16), dp(8));
        root.addView(mEventButton, pickerLp);

        mLiveNote = text("", 13, false, 1f);
        mLiveNote.setPadding(dp(14), dp(8), dp(14), dp(8));
        mLiveNote.setBackground(rounded(mCardColor, dp(8)));
        mLiveNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liveLp.setMargins(dp(16), 0, dp(16), dp(8));
        root.addView(mLiveNote, liveLp);

        // Single / Average tabs.
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        mSingleTab = text(getString(R.string.records_single), 16, true, 1f);
        mAverageTab = text(getString(R.string.records_average), 16, true, 0.6f);
        for (TextView t : new TextView[]{mSingleTab, mAverageTab}) {
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(44));
            tabs.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        mSingleTab.setOnClickListener(v -> { mShowAverage = false; refresh(); });
        mAverageTab.setOnClickListener(v -> { mShowAverage = true; refresh(); });
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.setMargins(dp(16), 0, dp(16), dp(12));
        root.addView(tabs, tabsLp);

        // Foreground panel with rounded top corners holding the chart and the list.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        float r = dp(28);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(mPanelColor);
        panelBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(panelBg);

        mChart = new LineChart(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
        chartLp.setMargins(dp(8), dp(16), dp(8), dp(4));
        panel.addView(mChart, chartLp);

        mEmptyText = text(getString(R.string.records_no_data), 16, false, 0.8f);
        mEmptyText.setPadding(dp(16), dp(16), dp(16), dp(16));
        mEmptyText.setVisibility(View.GONE);
        panel.addView(mEmptyText);

        mList = new RecyclerView(this);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setClipToPadding(false);
        mList.setPadding(0, dp(4), 0, dp(8));
        panel.addView(mList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        String source = "", exported = "";
        try {
            source = mData.getString("source");
            exported = mData.getString("exportDate");
        } catch (Exception ignored) {
        }
        TextView foot = text(getString(R.string.records_source, source, exported), 12, false, 0.7f);
        foot.setPadding(dp(20), dp(8), dp(20), dp(8));
        panel.addView(foot);

        root.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        InsetsUtils.applySafeInsetsPadding(root, false);
    }

    private android.graphics.drawable.Drawable eventIcon(String id, int sizeDp) {
        int res = getResources().getIdentifier("ic_event_" + id.toLowerCase(Locale.US), "drawable", getPackageName());
        if (res == 0) return null;
        android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(this, res);
        if (d == null) return null;
        d = androidx.core.graphics.drawable.DrawableCompat.wrap(d.mutate());
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, mTextColor);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    private void showEventPicker() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(list);
        sv.setBackground(rounded(mCardColor, dp(20)));
        for (int i = 0; i < mEventNames.size(); i++) {
            final int index = i;
            TextView row = text(mEventNames.get(i), 16, i == mEventIndex, 1f);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setMinHeight(dp(48));
            android.graphics.drawable.Drawable ic = eventIcon(mEventIds.get(i), 26);
            if (ic != null) {
                row.setCompoundDrawablesRelative(ic, null, null, null);
                row.setCompoundDrawablePadding(dp(14));
            }
            if (i == mEventIndex) row.setBackground(rounded(mPanelColor, dp(12)));
            row.setOnClickListener(v -> {
                mEventIndex = index;
                refresh();
                dialog.dismiss();
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        dialog.setContentView(sv);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    /** Shows today's world record (the top of the daily-updated world ranking) above the history. */
    private void fetchLiveRecord() {
        final int token = ++mLiveToken;
        final String ev = mEventIds.get(mEventIndex);
        final boolean avg = mShowAverage;
        mLiveNote.setVisibility(View.GONE);
        String url = "https://wca-rest-api.robiningelbrecht.be/rank/world/" + (avg ? "average" : "single")
                + "/" + ev + ".json";
        com.hatopigeon.cubictimer.wca.WcaHttp.get(url, new com.hatopigeon.cubictimer.wca.WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                if (token != mLiveToken) return;
                try {
                    JSONArray arr = new JSONObject(body).optJSONArray("items");
                    if (arr == null || arr.length() == 0) return;
                    JSONObject top = arr.getJSONObject(0);
                    final String value = com.hatopigeon.cubictimer.wca.WcaFormat.formatResult(
                            top.optLong("best"), ev, avg);
                    final String id = top.optString("personId");
                    mLiveNote.setText("Current world record: " + value + " (" + id + ")");
                    mLiveNote.setVisibility(View.VISIBLE);
                    com.hatopigeon.cubictimer.wca.WcaNames.lookup(id, (wcaId, name, iso) -> runOnUiThread(() -> {
                        if (token != mLiveToken) return;
                        String f = com.hatopigeon.cubictimer.wca.WcaFlags.emoji(iso);
                        mLiveNote.setText("Current world record: " + value + "  \u00B7  "
                                + (f.isEmpty() ? "" : f + " ") + name);
                    }));
                } catch (Exception ignored) {
                    // the history below still works without the live line
                }
            }

            @Override
            public void onError(String message) {
                // no live line when offline; the saved history is still shown
            }
        });
    }

    private void refresh() {
        mEventButton.setText(mEventNames.get(mEventIndex) + "  \u25BE");
        android.graphics.drawable.Drawable evIcon = eventIcon(mEventIds.get(mEventIndex), 26);
        if (evIcon != null) {
            mEventButton.setCompoundDrawablesRelative(evIcon, null, null, null);
            mEventButton.setCompoundDrawablePadding(dp(12));
        }
        styleTab(mSingleTab, !mShowAverage);
        styleTab(mAverageTab, mShowAverage);
        fetchLiveRecord();

        JSONArray records = currentRecords();
        boolean empty = records.length() == 0;
        String note = currentNote();
        String message = !note.isEmpty() ? note : (empty ? getString(R.string.records_no_data) : "");
        mEmptyText.setText(message);
        mEmptyText.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        mChart.setVisibility(empty ? View.GONE : View.VISIBLE);
        mList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            mList.setAdapter(null);
            return;
        }

        List<JSONObject> items = new ArrayList<>();
        for (int i = records.length() - 1; i >= 0; i--) { // Newest first.
            items.add(records.optJSONObject(i));
        }
        mList.setAdapter(new RecordAdapter(items, currentFormat(), mShowAverage));
        mList.scrollToPosition(0);
        drawChart(records);
    }

    // ---------------------------------------------------------------- chart

    private static float yearFraction(String date) {
        // "yyyy-mm-dd"
        try {
            int y = Integer.parseInt(date.substring(0, 4));
            int m = Integer.parseInt(date.substring(5, 7));
            int d = Integer.parseInt(date.substring(8, 10));
            return y + (m - 1) / 12f + (d - 1) / 372f;
        } catch (Exception e) {
            return 0f;
        }
    }

    private void drawChart(JSONArray records) {
        String format = currentFormat();
        List<Entry> entries = new ArrayList<>();
        float lastX = 0f;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            float x = Math.max(lastX, yearFraction(r.optString("d")));
            lastX = x;
            entries.add(new Entry(x, chartValue(r.optLong("v"), format, mShowAverage)));
        }
        if (entries.isEmpty()) {
            mChart.clear();
            return;
        }
        // Extend the current record to the export date.
        float endX = Math.max(lastX, yearFraction(mData.optString("exportDate")));
        Entry lastEntry = entries.get(entries.size() - 1);
        entries.add(new Entry(endX, lastEntry.getY()));

        LineDataSet set = new LineDataSet(entries, "");
        set.setMode(LineDataSet.Mode.STEPPED);
        set.setColor(mTextColor);
        set.setLineWidth(2f);
        set.setDrawCircles(false);
        set.setDrawValues(false);

        mChart.setData(new LineData(set));
        mChart.getDescription().setEnabled(false);
        mChart.getLegend().setEnabled(false);
        mChart.setTouchEnabled(false);
        mChart.getAxisRight().setEnabled(false);
        mChart.getAxisLeft().setTextColor(mTextColor);
        mChart.getAxisLeft().setDrawGridLines(true);
        mChart.getAxisLeft().setGridColor((mTextColor & 0x00FFFFFF) | 0x22000000);
        XAxis xAxis = mChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(mTextColor);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        });
        mChart.invalidate();
    }

    private static float chartValue(long v, String format, boolean average) {
        switch (format) {
            case "multi":
                return multiPoints(v);
            case "number":
                return average ? v / 100f : (float) v;
            default:
                return v / 100f; // seconds
        }
    }

    // ---------------------------------------------------------------- formatting

    /** Multi-blind: value is DDTTTTTMM (DD = 99 - points, TTTTT = seconds, MM = missed). */
    private static int multiPoints(long v) {
        int dd = (int) (v / 10000000L);
        return 99 - dd;
    }

    static String formatSeconds(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    static String formatValue(long v, String format, boolean average) {
        if ("multi".equals(format)) {
            int dd = (int) (v / 10000000L);
            long rest = v % 10000000L;
            long seconds = rest / 100;
            int missed = (int) (rest % 100);
            int points = 99 - dd;
            int solved = points + missed;
            int attempted = solved + missed;
            return solved + "/" + attempted + " " + formatSeconds(seconds);
        }
        if ("number".equals(format) && !average) {
            return String.valueOf(v); // Fewest moves single: a number of moves.
        }
        // Centiseconds (or hundredths of a move for the fewest-moves average).
        if ("number".equals(format)) {
            return String.format(Locale.US, "%d.%02d", v / 100, v % 100);
        }
        long totalSeconds = v / 100;
        long cs = v % 100;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
        }
        if (m > 0) {
            return String.format(Locale.US, "%d:%02d.%02d", m, s, cs);
        }
        return String.format(Locale.US, "%d.%02d", s, cs);
    }

    // ---------------------------------------------------------------- list

    private class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.Holder> {
        private final List<JSONObject> mItems;
        private final String mFormat;
        private final boolean mAverage;

        RecordAdapter(List<JSONObject> items, String format, boolean average) {
            mItems = items;
            mFormat = format;
            mAverage = average;
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView value, date, who, comp, tags;

            Holder(LinearLayout row, TextView value, TextView date, TextView who, TextView comp,
                   TextView tags) {
                super(row);
                this.value = value;
                this.date = date;
                this.who = who;
                this.comp = comp;
                this.tags = tags;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(RecordsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            RecyclerView.LayoutParams rowLp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(dp(12), dp(5), dp(12), dp(5));
            row.setLayoutParams(rowLp);

            LinearLayout top = new LinearLayout(RecordsActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView value = text("", 20, true, 1f);
            TextView date = text("", 14, false, 0.7f);
            date.setGravity(Gravity.END);
            top.addView(value, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(date, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(top);

            TextView who = text("", 16, false, 1f);
            TextView comp = text("", 14, false, 0.7f);
            TextView tags = text("", 12, true, 0.9f);
            row.addView(who);
            row.addView(comp);
            row.addView(tags);
            return new Holder(row, value, date, who, comp, tags);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            JSONObject r = mItems.get(position);
            boolean isCurrent = r.optBoolean("current");
            GradientDrawable cardBg = rounded(isCurrent
                    ? ColorUtils.blendARGB(mPanelColor, mTextColor, 0.16f) : mCardColor, dp(16));
            if (isCurrent) {
                cardBg.setStroke(dp(2), (mTextColor & 0x00FFFFFF) | 0x66000000);
            }
            h.itemView.setBackground(cardBg);
            h.value.setText(formatValue(r.optLong("v"), mFormat, mAverage));
            h.date.setText(r.optString("d"));
            String country = r.optString("country");
            h.who.setText(r.optString("name") + (country.isEmpty() ? "" : " \u00B7 " + country));
            h.comp.setText(r.optString("comp") + " \u00B7 " + r.optString("round"));
            StringBuilder tags = new StringBuilder();
            if (r.optBoolean("current")) tags.append(getString(R.string.records_current));
            if (r.optBoolean("tie")) {
                if (tags.length() > 0) tags.append("  ");
                tags.append(getString(R.string.records_tie));
            }
            if (r.optBoolean("reordered")) {
                if (tags.length() > 0) tags.append("  ");
                tags.append(getString(R.string.records_order_note));
            }
            h.tags.setText(tags.toString());
            h.tags.setVisibility(tags.length() == 0 ? View.GONE : View.VISIBLE);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }
}
