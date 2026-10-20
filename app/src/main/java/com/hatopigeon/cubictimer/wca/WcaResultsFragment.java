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

import com.hatopigeon.cubictimer.activity.RecordsActivity;

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
    private TextView myButton;
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

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        myButton = ui.pill("", false);
        myButton.setOnClickListener(v -> {
            String id = store.getMyWcaId();
            if (id.isEmpty()) {
                status.setText("Open a profile and tap \"Save as my WCA ID\" first.");
            } else {
                input.setText(id);
                loadPerson(id);
            }
        });
        buttons.addView(myButton, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        TextView records = ui.pill("World records history", false);
        records.setOnClickListener(v -> startActivity(new Intent(requireContext(), RecordsActivity.class)));
        buttons.addView(records);
        page.addView(buttons, ui.matchWrap(0, 0, 0, 8));
        refreshMyButton();

        status = ui.text("", 13, false, 0.85f);
        page.addView(status, ui.matchWrap(4, 0, 4, 6));

        body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body, ui.matchWrap(0, 0, 0, 0));

        if (store.getMyWcaId().isEmpty()) {
            status.setText("Type a WCA ID (like 2023CUAR02) or a name, then tap Search.");
        } else {
            status.setText("Tap \"" + myButtonLabel() + "\" to see your results.");
        }
        return scroll;
    }

    private String myButtonLabel() {
        String id = store.getMyWcaId();
        return id.isEmpty() ? "My results" : "My results (" + id + ")";
    }

    private void refreshMyButton() {
        myButton.setText(myButtonLabel());
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
                            card.addView(ui.text(o.optString("name"), 16, true, 1f));
                            String country = o.optString("country_iso2");
                            card.addView(ui.text(id + (country.isEmpty() ? "" : "  \u00B7  " + country), 13, false, 0.8f));
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
                status.setText("Could not search (" + message + "). This needs internet. "
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
            status.setText("Could not reach the WCA website (" + error + "). Showing a saved copy from "
                    + dateTime(store.cachedAt(wcaId)) + ".");
        } else {
            String hint = error.contains("403")
                    ? " The WCA website refused the request."
                    : " This needs internet the first time you open a competitor.";
            status.setText("Could not load " + wcaId + " (" + error + ")." + hint);
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

            // Header card
            LinearLayout head = ui.card(false);
            head.addView(ui.text(name.isEmpty() ? id : name, 20, true, 1f));
            String sub = id + (country.isEmpty() ? "" : "  \u00B7  " + country);
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
            final String saveId = id;
            boolean mine = saveId.equalsIgnoreCase(store.getMyWcaId());
            TextView save = ui.pill(mine ? "This is my WCA ID" : "Save as my WCA ID", mine);
            save.setOnClickListener(v -> {
                store.setMyWcaId(saveId);
                refreshMyButton();
                ui.stylePill((TextView) v, true);
                ((TextView) v).setText("This is my WCA ID");
            });
            head.addView(save, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
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
        body.addView(ui.text("Personal records", 18, true, 1f), ui.matchWrap(4, 12, 4, 4));
        LinearLayout card = ui.card(false);
        boolean first = true;
        for (String ev : ids) {
            JSONObject e = pr.optJSONObject(ev);
            if (e == null) continue;
            JSONObject single = e.optJSONObject("single");
            JSONObject avg = e.optJSONObject("average");
            TextView title = ui.text(WcaFormat.eventName(ev), 15, true, 1f);
            card.addView(title, ui.matchWrap(0, first ? 0 : 10, 0, 0));
            first = false;
            if (single != null && single.optLong("best") != 0) {
                String r = rankText(single);
                card.addView(ui.text("Single  " + WcaFormat.formatResult(single.optLong("best"), ev, false)
                        + (r.isEmpty() ? "" : "   (" + r + ")"), 14, false, 0.95f));
            }
            if (avg != null && avg.optLong("best") != 0) {
                String r = rankText(avg);
                card.addView(ui.text("Average  " + WcaFormat.formatResult(avg.optLong("best"), ev, true)
                        + (r.isEmpty() ? "" : "   (" + r + ")"), 14, false, 0.95f));
            }
        }
        body.addView(card, ui.matchWrap(0, 4, 0, 4));
    }

    private void addHistory(JSONArray results) throws Exception {
        // Group the results by competition.
        Map<String, List<JSONObject>> byComp = new LinkedHashMap<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String cid = r.optString("competition_id");
            if (cid.isEmpty()) continue;
            List<JSONObject> list = byComp.get(cid);
            if (list == null) {
                list = new ArrayList<>();
                byComp.put(cid, list);
            }
            list.add(r);
        }
        final Map<String, String[]> info = compInfo;
        List<String> ids = new ArrayList<>(byComp.keySet());
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                String da = info != null && info.containsKey(a) ? info.get(a)[1] : "";
                String db = info != null && info.containsKey(b) ? info.get(b)[1] : "";
                return db.compareTo(da); // newest first; unknown dates last
            }
        });

        body.addView(ui.text("Competition history (" + ids.size() + ")", 18, true, 1f),
                ui.matchWrap(4, 14, 4, 2));
        body.addView(ui.text("Competition names and dates come from the WCA results export of "
                + "2026-10-18. Newer competitions show only their ID.", 12, false, 0.7f),
                ui.matchWrap(4, 0, 4, 6));

        int limit = Math.min(shown, ids.size());
        for (int i = 0; i < limit; i++) {
            String cid = ids.get(i);
            body.addView(buildCompetitionCard(cid, byComp.get(cid)), ui.matchWrap(0, 4, 0, 4));
        }
        if (limit < ids.size()) {
            TextView more = ui.pill("Show more (" + (ids.size() - limit) + " left)", false);
            more.setOnClickListener(v -> {
                shown += PAGE_SIZE;
                render();
            });
            body.addView(more, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));
        }
    }

    private View buildCompetitionCard(String cid, List<JSONObject> list) {
        String name = cid;
        String date = "";
        if (compInfo != null && compInfo.containsKey(cid)) {
            name = compInfo.get(cid)[0];
            date = compInfo.get(cid)[1];
        }
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int ea = WcaFormat.eventOrder(a.optString("event_id"));
                int eb = WcaFormat.eventOrder(b.optString("event_id"));
                if (ea != eb) return ea - eb;
                return WcaFormat.roundRank(a.optString("round_type_id"))
                        - WcaFormat.roundRank(b.optString("round_type_id"));
            }
        });

        LinearLayout card = ui.card(false);
        card.addView(ui.text(name, 16, true, 1f));
        if (!date.isEmpty()) card.addView(ui.text(date, 12, false, 0.7f));

        final List<TextView> attemptViews = new ArrayList<>();
        for (JSONObject r : list) {
            String ev = r.optString("event_id");
            int pos = r.optInt("pos", 0);
            String best = WcaFormat.formatResult(r.optLong("best"), ev, false);
            String avg = WcaFormat.formatResult(r.optLong("average"), ev, true);
            StringBuilder line = new StringBuilder();
            line.append(WcaFormat.eventName(ev)).append("  \u00B7  ")
                    .append(WcaFormat.roundName(r.optString("round_type_id")));
            if (pos > 0) line.append("  \u00B7  #").append(pos);
            card.addView(ui.text(line.toString(), 14, true, 1f), ui.matchWrap(0, 8, 0, 0));
            StringBuilder res = new StringBuilder();
            if (!best.isEmpty()) res.append("best ").append(best);
            if (!avg.isEmpty()) res.append(res.length() > 0 ? "   " : "").append("avg ").append(avg);
            if (res.length() > 0) card.addView(ui.text(res.toString(), 14, false, 0.95f));

            JSONArray at = r.optJSONArray("attempts");
            if (at != null && at.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < at.length(); i++) {
                    String t = WcaFormat.formatResult(at.optLong(i), ev, false);
                    if (t.isEmpty()) continue;
                    if (sb.length() > 0) sb.append("   ");
                    sb.append(t);
                }
                if (sb.length() > 0) {
                    TextView tv = ui.text(sb.toString(), 13, false, 0.8f);
                    tv.setVisibility(View.GONE);
                    attemptViews.add(tv);
                    card.addView(tv);
                }
            }
        }
        if (!attemptViews.isEmpty()) {
            card.addView(ui.text("Tap to show or hide each solve", 11, false, 0.5f), ui.matchWrap(0, 6, 0, 0));
            card.setOnClickListener(v -> {
                for (TextView tv : attemptViews) {
                    tv.setVisibility(tv.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
                }
            });
        }
        return card;
    }
}
