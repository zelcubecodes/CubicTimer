package com.hatopigeon.cubictimer.wca;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hatopigeon.cubicify.R;
import com.hatopigeon.cubictimer.activity.RecordsActivity;
import com.hatopigeon.cubictimer.fragment.TimerFragmentMain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WCA Results page: look up a competitor by WCA ID or name and see their personal records and
 * previous results. Data comes from the WCA website's public API. Each profile you open is saved
 * on this device so it can still be read offline.
 */
public class WcaResultsFragment extends Fragment {
    private static final String WCA = "https://www.worldcubeassociation.org/api/v0";
    private static final Pattern WCA_ID = Pattern.compile("^\\d{4}[A-Za-z]{4}\\d{2}$");
    private static final int PAGE_SIZE = 30;

    public static WcaResultsFragment newInstance() {
        return new WcaResultsFragment();
    }

    private WcaUi ui;
    private WcaStore store;
    private EditText input;
    private LinearLayout pinnedRow;
    private final java.util.Set<String> pendingComps = new java.util.HashSet<>();
    private final java.util.Set<String> failedComps = new java.util.HashSet<>();
    private boolean compInfoChanged = false;
    private TextView status;
    private LinearLayout body;
    private ScrollView scroll;

    private String openId = null;
    private String profileJson = null;
    private String resultsJson = null;
    private boolean fromCache = false;
    private long dataTime = 0L;
    private int shown = PAGE_SIZE;
    private Map<String, String[]> compInfo = null;
    private String historyEvent = null;
    private String lastShownId = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ui = new WcaUi(requireContext());
        store = new WcaStore(requireContext());

        scroll = new ScrollView(requireContext());
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setClipToPadding(false);
        scroll.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(64));

        LinearLayout page = new LinearLayout(requireContext());
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(ui.text("WCA Results", 22, true, 1f), ui.matchWrap(4, 4, 4, 8));

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = ui.input("WCA ID or name");
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });
        row.addView(input, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        ((LinearLayout.LayoutParams) input.getLayoutParams()).weight = 1f;
        TextView go = ui.pill("Search", true);
        go.setOnClickListener(v -> search());
        row.addView(go);
        page.addView(row, ui.matchWrap(0, 0, 0, 8));

        TextView records = ui.pill("World records history", false);
        records.setOnClickListener(v -> startActivity(new Intent(requireContext(), RecordsActivity.class)));
        TextView rankings = ui.pill("Rankings", false);
        rankings.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                com.hatopigeon.cubictimer.activity.RankingsActivity.class)));
        LinearLayout recordsRow = new LinearLayout(requireContext());
        recordsRow.setOrientation(LinearLayout.HORIZONTAL);
        recordsRow.addView(records, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        recordsRow.addView(rankings);
        page.addView(recordsRow, ui.matchWrap(0, 0, 0, 8));

        page.addView(ui.text("Starred competitors", 13, true, 0.8f), ui.matchWrap(4, 0, 4, 4));
        android.widget.HorizontalScrollView pinScroll = new android.widget.HorizontalScrollView(requireContext());
        pinScroll.setHorizontalScrollBarEnabled(false);
        pinnedRow = new LinearLayout(requireContext());
        pinnedRow.setOrientation(LinearLayout.HORIZONTAL);
        pinScroll.addView(pinnedRow);
        page.addView(pinScroll, ui.matchWrap(0, 0, 0, 8));
        renderPinned();

        status = ui.status(13);
        page.addView(status, ui.matchWrap(4, 0, 4, 6));

        body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body, ui.matchWrap(0, 0, 0, 0));

        status.setText("Type a WCA ID (like 2023CUAR02) or a name, then tap Search. "
                + "Open a competitor and tap the star to keep them here.");
        return scroll;
    }

    private void renderPinned() {
        pinnedRow.removeAllViews();
        List<String[]> pinned = store.getPinned();
        if (pinned.isEmpty()) {
            pinnedRow.addView(ui.text("None yet", 13, false, 0.6f), ui.lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 0));
            return;
        }
        for (final String[] p : pinned) {
            String label = p[1].isEmpty() || p[1].equals(p[0]) ? p[0] : p[1] + " (" + p[0] + ")";
            TextView chip = ui.pill(label, false);
            chip.setOnClickListener(v -> {
                input.setText(p[0]);
                loadPerson(p[0]);
            });
            pinnedRow.addView(chip, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- search

    private void search() {
        hideKeyboard();
        String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            status.setText("Type a WCA ID or a name first.");
            return;
        }
        if (WCA_ID.matcher(q).matches()) {
            loadPerson(q.toUpperCase(Locale.US));
        } else {
            searchByName(q);
        }
    }

    private void searchByName(String q) {
        status.setText("Searching...");
        body.removeAllViews();
        openId = null;
        WcaHttp.get(WCA + "/search/users?persons_table=true&q=" + Uri.encode(q), new WcaHttp.Callback() {
            @Override
            public void onSuccess(String text) {
                if (!isAdded()) return;
                body.removeAllViews();
                try {
                    JSONObject root = new JSONObject(text);
                    JSONArray arr = root.optJSONArray("result");
                    int count = 0;
                    if (arr != null) {
                        for (int i = 0; i < arr.length() && count < 25; i++) {
                            JSONObject o = arr.getJSONObject(i);
                            final String id = o.optString("wca_id");
                            if (id.isEmpty() || "null".equals(id)) continue;
                            LinearLayout card = ui.card(false);
                            String sFlag = WcaFlags.emoji(o.optString("country_iso2"));
                            card.addView(ui.text((sFlag.isEmpty() ? "" : sFlag + " ") + o.optString("name"), 16, true, 1f));
                            card.addView(ui.text(id, 13, false, 0.8f));
                            card.setOnClickListener(v -> {
                                input.setText(id);
                                loadPerson(id);
                            });
                            body.addView(card, ui.matchWrap(0, 4, 0, 4));
                            count++;
                        }
                    }
                    status.setText(count == 0 ? "No competitors found." : "Tap a competitor to open their results.");
                } catch (Exception e) {
                    status.setText("Unexpected response from the WCA website.");
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                status.setText(WcaHttp.describe(requireContext(), message) + ". "
                        + "You can still open a competitor by WCA ID if you opened them before.");
            }
        });
    }

    // ---------------------------------------------------------------- profile

    private void loadPerson(final String wcaId) {
        status.setText("Loading " + wcaId + "...");
        body.removeAllViews();
        openId = wcaId;
        WcaHttp.get(WCA + "/persons/" + Uri.encode(wcaId), new WcaHttp.Callback() {
            @Override
            public void onSuccess(final String profile) {
                if (!isAdded()) return;
                WcaHttp.get(WCA + "/persons/" + Uri.encode(wcaId) + "/results", new WcaHttp.Callback() {
                    @Override
                    public void onSuccess(String results) {
                        if (!isAdded()) return;
                        store.cachePerson(wcaId, profile, results);
                        show(wcaId, profile, results, false, System.currentTimeMillis());
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded()) return;
                        useCache(wcaId, message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                useCache(wcaId, message);
            }
        });
    }

    private void useCache(String wcaId, String error) {
        String p = store.cachedProfile(wcaId);
        String r = store.cachedResults(wcaId);
        if (p != null && r != null) {
            show(wcaId, p, r, true, store.cachedAt(wcaId));
            status.setText(WcaHttp.describe(requireContext(), error) + ". Showing a saved copy from "
                    + dateTime(store.cachedAt(wcaId)) + ".");
        } else {
            String why = WcaHttp.describe(requireContext(), error);
            String hint = error.contains("403")
                    ? " The WCA website refused the request."
                    : (why.startsWith("No internet") ? " Connect to the internet to open " + wcaId + "."
                    : " Tap Refresh or search again to retry.");
            status.setText(why + "." + hint);
        }
    }

    private String dateTime(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private void show(String wcaId, String profile, String results, boolean cached, long time) {
        openId = wcaId;
        profileJson = profile;
        resultsJson = results;
        fromCache = cached;
        dataTime = time;
        shown = PAGE_SIZE;
        if (lastShownId == null || !lastShownId.equals(wcaId)) {
            historyEvent = null;
            lastShownId = wcaId;
        }
        if (compInfo == null) {
            final android.content.Context appCtx = requireContext().getApplicationContext();
            WcaHttp.background(new Runnable() {
                @Override
                public void run() {
                    compInfo = WcaData.competitions(appCtx);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    if (isAdded()) render();
                }
            });
        } else {
            render();
        }
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        body.removeAllViews();
        try {
            JSONObject root = new JSONObject(profileJson);
            JSONObject person = root.optJSONObject("person");
            String name = person != null ? person.optString("name") : "";
            String id = person != null ? person.optString("wca_id") : openId;
            if (id == null || id.isEmpty()) id = openId;
            String country = person != null ? person.optString("country_iso2") : "";
            String flag = WcaFlags.emoji(country);

            // Header card
            LinearLayout head = ui.card(false);
            final String pinId = id;
            final String pinName = name;
            LinearLayout titleRow = new LinearLayout(requireContext());
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView titleText = ui.text((flag.isEmpty() ? "" : flag + " ") + (name.isEmpty() ? id : name), 20, true, 1f);
            titleRow.addView(titleText, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0));
            ((LinearLayout.LayoutParams) titleText.getLayoutParams()).weight = 1f;
            final android.widget.ImageView star = ui.iconButton(store.isPinned(pinId)
                    ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.itemText);
            star.setContentDescription("Star this competitor");
            star.setOnClickListener(v -> {
                boolean now = store.togglePinned(pinId, pinName);
                star.setImageDrawable(ui.tinted(now ? R.drawable.ic_wca_star_filled
                        : R.drawable.ic_wca_star_outline, 24, ui.itemText));
                renderPinned();
            });
            titleRow.addView(star);
            android.widget.ImageView refreshIcon = ui.iconButton(R.drawable.ic_wca_refresh, 24, ui.itemText);
            refreshIcon.setContentDescription("Refresh");
            refreshIcon.setOnClickListener(v -> loadPerson(pinId));
            titleRow.addView(refreshIcon);
            head.addView(titleRow);
            String sub = id;
            int comps = root.optInt("competition_count", -1);
            if (comps >= 0) sub += "  \u00B7  " + comps + " competitions";
            head.addView(ui.text(sub, 13, false, 0.85f));
            JSONObject medals = root.optJSONObject("medals");
            if (medals != null && medals.optInt("total", 0) > 0) {
                head.addView(ui.text("Medals: " + medals.optInt("gold") + " gold, " + medals.optInt("silver")
                        + " silver, " + medals.optInt("bronze") + " bronze", 13, false, 0.85f));
            }
            JSONObject recs = root.optJSONObject("records");
            if (recs != null && recs.optInt("total", 0) > 0) {
                head.addView(ui.text("Records: " + recs.optInt("world") + " world, "
                        + recs.optInt("continental") + " continental, " + recs.optInt("national")
                        + " national", 13, false, 0.85f));
            }
            body.addView(head, ui.matchWrap(0, 4, 0, 4));

            if (fromCache) {
                status.setText("Saved copy from " + dateTime(dataTime) + ". It may be out of date.");
            } else {
                status.setText("Live from the WCA website, fetched " + dateTime(dataTime) + ".");
            }

            addPersonalRecords(root.optJSONObject("personal_records"));
            addHistory(new JSONArray(resultsJson));
        } catch (Exception e) {
            e.printStackTrace();
            status.setText("Unexpected data from the WCA website for " + openId + ".");
        }
    }

    private String rankText(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        int n = o.optInt("country_rank", -1);
        int c = o.optInt("continent_rank", -1);
        int w = o.optInt("world_rank", -1);
        if (n > 0) sb.append("NR ").append(n);
        if (c > 0) sb.append(sb.length() > 0 ? ", " : "").append("CR ").append(c);
        if (w > 0) sb.append(sb.length() > 0 ? ", " : "").append("WR ").append(w);
        return sb.toString();
    }

    private void addPersonalRecords(JSONObject pr) {
        if (pr == null || pr.length() == 0) return;
        List<String> ids = new ArrayList<>();
        Iterator<String> it = pr.keys();
        while (it.hasNext()) ids.add(it.next());
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return WcaFormat.eventOrder(a) - WcaFormat.eventOrder(b);
            }
        });
        body.addView(ui.label("Personal records", 18, true), ui.matchWrap(0, 12, 0, 6));
        LinearLayout card = ui.card(false);
        card.setPadding(0, 0, 0, 0);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(requireContext());
        LinearLayout table = new LinearLayout(requireContext());
        table.setOrientation(LinearLayout.VERTICAL);
        hs.addView(table);
        String[] heads = {"Event", "NR", "CR", "WR", "Single", "Average", "WR", "CR", "NR"};
        int[] widths = {176, 64, 64, 64, 84, 84, 64, 64, 64};
        LinearLayout hrow = tableRow(0);
        for (int i = 0; i < heads.length; i++) hrow.addView(prCell(heads[i], widths[i], true, i == 0, false));
        table.addView(hrow);
        int n = 0;
        for (String ev : ids) {
            JSONObject e = pr.optJSONObject(ev);
            if (e == null) continue;
            JSONObject single = e.optJSONObject("single");
            JSONObject avg = e.optJSONObject("average");
            LinearLayout line = tableRow(n++ % 2 == 0 ? 3 : 2);
            TextView title = ui.text(WcaFormat.eventName(ev), 14, false, 1f);
            ui.setEventIcon(title, ev, 20, ui.text);
            title.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(widths[0]), ViewGroup.LayoutParams.WRAP_CONTENT));
            line.addView(title);
            boolean hasS = single != null && single.optLong("best") != 0;
            boolean hasA = avg != null && avg.optLong("best") != 0;
            line.addView(prCell(hasS ? rankOf(single, "country_rank") : "", widths[1], false, false, isOne(single, "country_rank")));
            line.addView(prCell(hasS ? rankOf(single, "continent_rank") : "", widths[2], false, false, isOne(single, "continent_rank")));
            line.addView(prCell(hasS ? rankOf(single, "world_rank") : "", widths[3], false, false, isOne(single, "world_rank")));
            line.addView(prCell(hasS ? WcaFormat.formatResult(single.optLong("best"), ev, false) : "", widths[4], true, false, false));
            line.addView(prCell(hasA ? WcaFormat.formatResult(avg.optLong("best"), ev, true) : "", widths[5], true, false, false));
            line.addView(prCell(hasA ? rankOf(avg, "world_rank") : "", widths[6], false, false, isOne(avg, "world_rank")));
            line.addView(prCell(hasA ? rankOf(avg, "continent_rank") : "", widths[7], false, false, isOne(avg, "continent_rank")));
            line.addView(prCell(hasA ? rankOf(avg, "country_rank") : "", widths[8], false, false, isOne(avg, "country_rank")));
            table.addView(line);
        }
        card.addView(hs);
        body.addView(card, ui.matchWrap(0, 4, 0, 4));
    }

    private static String rankOf(JSONObject o, String key) {
        int v = o == null ? -1 : o.optInt(key, -1);
        return v > 0 ? String.valueOf(v) : "";
    }

    private static boolean isOne(JSONObject o, String key) {
        return o != null && o.optInt(key, -1) == 1;
    }

    /** A cell of the personal records table. Ranks are grey, and red when the person holds the record. */
    private TextView prCell(String s, int widthDp, boolean bold, boolean left, boolean record) {
        TextView t = ui.text(s, 14, bold, 1f);
        t.setGravity(left ? Gravity.START : Gravity.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setPadding(0, 0, ui.dp(10), 0);
        if (record) {
            t.setTextColor(0xFFE53935);
        } else if (!bold && !left) {
            t.setAlpha(0.65f);
        }
        return t;
    }

    private static final int W_COMP = 190, W_ROUND = 112, W_PLACE = 56, W_SINGLE = 100, W_AVG = 100, W_SOLVES = 290;

    private void addHistory(JSONArray results) throws Exception {
        List<JSONObject> all = new ArrayList<>();
        java.util.Set<String> evSet = new java.util.HashSet<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            if (r.optString("competition_id").isEmpty()) continue;
            all.add(r);
            evSet.add(r.optString("event_id"));
        }
        List<String> events = new ArrayList<>(evSet);
        Collections.sort(events, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return WcaFormat.eventOrder(a) - WcaFormat.eventOrder(b);
            }
        });
        if (events.isEmpty()) return;
        if (historyEvent == null || !events.contains(historyEvent)) {
            historyEvent = events.contains("333") ? "333" : events.get(0);
        }

        body.addView(ui.label("Previous results", 18, true), ui.matchWrap(0, 14, 0, 6));

        // Event picker: one icon per event the person has results in.
        android.widget.HorizontalScrollView pickScroll = new android.widget.HorizontalScrollView(requireContext());
        pickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pick = new LinearLayout(requireContext());
        pick.setOrientation(LinearLayout.HORIZONTAL);
        pick.setGravity(Gravity.CENTER_VERTICAL);
        pickScroll.addView(pick);
        for (final String evId : events) {
            View item;
            boolean sel = evId.equals(historyEvent);
            android.graphics.drawable.Drawable ic = ui.eventIcon(evId, 28, ui.text);
            if (ic != null) {
                android.widget.ImageView iv = new android.widget.ImageView(requireContext());
                iv.setImageDrawable(ic);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                iv.setAlpha(sel ? 1f : 0.35f);
                iv.setContentDescription(WcaFormat.eventName(evId));
                item = iv;
            } else {
                item = ui.pill(WcaFormat.eventName(evId), sel);
            }
            item.setOnClickListener(v -> {
                historyEvent = evId;
                shown = PAGE_SIZE;
                render();
            });
            pick.addView(item, ui.lp(ui.dp(44), ui.dp(44), 0, 0, 4, 0));
        }
        body.addView(pickScroll, ui.matchWrap(0, 0, 0, 6));

        // Rows for the chosen event, newest competition first and the final before earlier rounds.
        final Map<String, String[]> info = compInfo;
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject r : all) if (historyEvent.equals(r.optString("event_id"))) rows.add(r);
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                String ia = a.optString("competition_id"), ib = b.optString("competition_id");
                String da = info != null && info.containsKey(ia) ? info.get(ia)[1] : "9999-99-99";
                String db = info != null && info.containsKey(ib) ? info.get(ib)[1] : "9999-99-99";
                int c = db.compareTo(da);
                if (c != 0) return c;
                c = ib.compareTo(ia);
                if (c != 0) return c;
                return WcaFormat.roundRank(b.optString("round_type_id"))
                        - WcaFormat.roundRank(a.optString("round_type_id"));
            }
        });
        int limit = Math.min(shown, rows.size());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String cid = rows.get(i).optString("competition_id");
            if (!ids.contains(cid)) ids.add(cid);
        }
        fetchMissingComps(ids);

        LinearLayout card = ui.card(false);
        card.setPadding(0, 0, 0, 0);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(requireContext());
        LinearLayout table = new LinearLayout(requireContext());
        table.setOrientation(LinearLayout.VERTICAL);
        hs.addView(table);

        LinearLayout header = tableRow(0);
        header.addView(cell("Competition", W_COMP, true));
        header.addView(cell("Round", W_ROUND, true));
        header.addView(cell("Place", W_PLACE, true));
        header.addView(cell("Single", W_SINGLE, true));
        header.addView(cell("Average", W_AVG, true));
        header.addView(cell("Solves", W_SOLVES, true));
        table.addView(header);

        LinearLayout evRow = tableRow(1);
        TextView evName = ui.text(WcaFormat.eventName(historyEvent), 14, true, 1f);
        ui.setEventIcon(evName, historyEvent, 20, ui.text);
        evRow.addView(evName);
        table.addView(evRow);

        String lastComp = "";
        for (int i = 0; i < limit; i++) {
            JSONObject r = rows.get(i);
            final String cid = r.optString("competition_id");
            String ev = r.optString("event_id");
            LinearLayout line = tableRow(i % 2 == 0 ? 2 : 3);
            if (cid.equals(lastComp)) {
                line.addView(cell("", W_COMP, false));
            } else {
                String cname = info != null && info.containsKey(cid) ? info.get(cid)[0] : cid;
                TextView link = cell(cname, W_COMP, false);
                link.setTextColor(0xFFE8531F);
                link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                final String linkName = cname;
                link.setOnClickListener(v -> openCompetition(cid, linkName));
                line.addView(link);
            }
            lastComp = cid;
            line.addView(cell(WcaFormat.roundName(r.optString("round_type_id")), W_ROUND, false));
            int pos = r.optInt("pos", 0);
            line.addView(cell(pos > 0 ? String.valueOf(pos) : "", W_PLACE, false));
            line.addView(resultCell(WcaFormat.formatResult(r.optLong("best"), ev, false),
                    regionTag(r, "regional_single_record"), W_SINGLE));
            line.addView(resultCell(WcaFormat.formatResult(r.optLong("average"), ev, true),
                    regionTag(r, "regional_average_record"), W_AVG));
            line.addView(cell(solvesText(r.optJSONArray("attempts"), ev), W_SOLVES, false));
            table.addView(line);
        }
        card.addView(hs);
        body.addView(card, ui.matchWrap(0, 4, 0, 4));

        if (limit < rows.size()) {
            TextView more = ui.pill("Show more (" + (rows.size() - limit) + " left)", false);
            more.setOnClickListener(v -> {
                shown += PAGE_SIZE;
                render();
            });
            body.addView(more, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));
        }
        body.addView(ui.text("Tap a competition name to open it. Results come from the WCA website "
                + "each time you open or refresh a competitor.", 12, false, 0.7f), ui.matchWrap(4, 4, 4, 6));
    }

    /** kind: 0 header, 1 event title, 2 and 3 alternating result rows. */
    private LinearLayout tableRow(int kind) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(12), ui.dp(kind == 0 ? 10 : 8), ui.dp(12), ui.dp(kind == 0 ? 10 : 8));
        if (kind == 1 || kind == 3) {
            row.setBackgroundColor(androidx.core.graphics.ColorUtils.blendARGB(ui.itemBg, ui.itemText, 0.07f));
        }
        return row;
    }

    private TextView cell(String s, int widthDp, boolean bold) {
        TextView t = ui.text(s, 14, bold, 1f);
        t.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setPadding(0, 0, ui.dp(8), 0);
        return t;
    }

    private View resultCell(String value, String tag, int widthDp) {
        if (value.isEmpty()) return cell("", widthDp, false);
        LinearLayout f = ui.resultCell("", value, tag, 14, true);
        f.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    /** WR, NR or a continental tag like AsR from a results row; PR and empty values give "". */
    private static String regionTag(JSONObject r, String key) {
        if (!r.has(key) || r.isNull(key)) return "";
        String v = r.optString(key, "").trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return "";
        String u = v.toUpperCase(Locale.US);
        if (u.equals("WR") || u.equals("NR") || u.equals("CR")) return u;
        if (u.endsWith("R") && u.length() <= 3) return v;
        return "";
    }

    /** All solves in order; on a five-solve average the best and the worst are in brackets. */
    private static String solvesText(JSONArray at, String ev) {
        if (at == null || at.length() == 0) return "";
        int n = at.length();
        long[] v = new long[n];
        boolean allSet = true;
        for (int i = 0; i < n; i++) {
            v[i] = at.optLong(i);
            if (v[i] == 0) allSet = false;
        }
        int bi = -1, wi = -1;
        if (n == 5 && allSet && !"333mbf".equals(ev)) {
            for (int i = 0; i < n; i++) {
                if (v[i] > 0 && (bi < 0 || v[i] < v[bi])) bi = i;
            }
            for (int i = n - 1; i >= 0; i--) {
                if (i == bi) continue;
                if (wi < 0) { wi = i; continue; }
                boolean iBad = v[i] < 0, wBad = v[wi] < 0;
                if (iBad && !wBad) wi = i;
                else if (iBad == wBad && !iBad && v[i] > v[wi]) wi = i;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String t = WcaFormat.formatResult(v[i], ev, false);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("  ");
            sb.append(i == bi || i == wi ? "(" + t + ")" : t);
        }
        return sb.toString();
    }

    /** Opens the competition in the WCA Live tab, or on the WCA website if the tab cannot be found. */
    private void openCompetition(String cid, String name) {
        store.setPendingComp(cid, name);
        View pager = requireActivity().findViewById(R.id.pager);
        if (pager instanceof androidx.viewpager.widget.ViewPager) {
            ((androidx.viewpager.widget.ViewPager) pager).setCurrentItem(TimerFragmentMain.LIVE_PAGE, true);
        } else {
            store.takePendingComp();
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.worldcubeassociation.org/competitions/" + Uri.encode(cid))));
        }
    }

    /** Looks up names and dates of competitions that are newer than the bundled export. */
    private void fetchMissingComps(List<String> ids) {
        if (compInfo == null) return;
        int started = 0;
        for (final String cid : ids) {
            if (compInfo.containsKey(cid) || pendingComps.contains(cid) || failedComps.contains(cid)) continue;
            if (started >= 10) break;
            started++;
            pendingComps.add(cid);
            WcaHttp.get(WCA + "/competitions/" + Uri.encode(cid), new WcaHttp.Callback() {
                @Override
                public void onSuccess(String text) {
                    pendingComps.remove(cid);
                    try {
                        JSONObject o = new JSONObject(text);
                        String name = o.optString("name");
                        String date = o.optString("start_date");
                        if (!name.isEmpty() && !date.isEmpty()) {
                            compInfo.put(cid, new String[]{name, date});
                            compInfoChanged = true;
                        } else {
                            failedComps.add(cid);
                        }
                    } catch (Exception e) {
                        failedComps.add(cid);
                    }
                    finishCompLookups();
                }

                @Override
                public void onError(String message) {
                    pendingComps.remove(cid);
                    failedComps.add(cid);
                    finishCompLookups();
                }
            });
        }
    }

    private void finishCompLookups() {
        if (pendingComps.isEmpty() && compInfoChanged && isAdded()) {
            compInfoChanged = false;
            render();
        }
    }
}
