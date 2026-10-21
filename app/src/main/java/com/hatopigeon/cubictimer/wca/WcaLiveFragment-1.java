package com.hatopigeon.cubictimer.wca;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hatopigeon.cubicify.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WCA Live page: browse competitions and watch results. Data comes from live.worldcubeassociation.org
 * and is only fetched while this page is visible.
 */
public class WcaLiveFragment extends Fragment {
    private static final String API = "https://live.worldcubeassociation.org/api";
    private static final long AUTO_REFRESH_MS = 30000L;

    public static WcaLiveFragment newInstance() {
        return new WcaLiveFragment();
    }

    // ---- models
    private static class Comp {
        String id = "", wcaId = "", name = "", start = "", end = "";
    }

    private static class Person {
        String wcaId = "", name = "", country = "", iso = "";
    }

    private static class Row {
        int personId;
        int ranking;
        long best, average;
        long[] attempts = new long[0];
        String singleTag = "", avgTag = "";
    }

    private static class Round {
        int number;
        List<Row> rows = new ArrayList<>();
    }

    private static class Ev {
        String id = "";
        List<Round> rounds = new ArrayList<>();
    }

    // ---- state
    private WcaUi ui;
    private WcaStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout listPage;
    private LinearLayout detailPage;
    private EditText searchInput;
    private TextView listStatus;
    private LinearLayout listContainer;
    private final List<TextView> filterPills = new ArrayList<>();
    private int filterMode = 0; // 0 all, 1 favorites, 2 recent
    private List<Comp> loadedComps = new ArrayList<>();
    private boolean listLoaded = false;
    private boolean listLoading = false;
    private String listError = null;
    private long listFetchedAt = 0L;

    private TextView detailTitle;
    private TextView detailStatus;
    private LinearLayout eventRow;
    private LinearLayout roundRow;
    private LinearLayout resultsContainer;
    private TextView attemptsPill;
    private TextView sortPill;
    private ScrollView resultsScroll;

    private Comp currentComp = null;
    private boolean inDetail = false;
    private List<Ev> events = new ArrayList<>();
    private Map<Integer, Person> people = new HashMap<>();
    private int eventIndex = 0;
    private int roundIndex = 0;
    private boolean showAttempts = false;
    private boolean sortByName = false;
    private boolean autoRefresh = false;
    private boolean fetching = false;
    private boolean liveOk = false;
    private long detailFetchedAt = 0L;
    private String detailError = null;
    private boolean haveData = false;

    private OnBackPressedCallback backCallback;
    private final Runnable autoRunnable = this::fetchResults;

    // ---------------------------------------------------------------- lifecycle

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ui = new WcaUi(requireContext());
        store = new WcaStore(requireContext());
        autoRefresh = store.getAutoRefresh();

        FrameLayout frame = new FrameLayout(requireContext());
        frame.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(64));

        listPage = buildListPage();
        detailPage = buildDetailPage();
        detailPage.setVisibility(View.GONE);
        frame.addView(listPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(detailPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                closeDetail();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);
        return frame;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getUserVisibleHint()) onShown();
    }

    @Override
    public void setUserVisibleHint(boolean visible) {
        super.setUserVisibleHint(visible);
        if (listPage == null) return;
        if (visible) {
            onShown();
        } else {
            handler.removeCallbacks(autoRunnable);
        }
        updateBack();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(autoRunnable);
        super.onDestroyView();
    }

    private void onShown() {
        autoRefresh = store.getAutoRefresh();
        updateBack();
        String[] pending = store.takePendingComp();
        if (pending != null) {
            Comp pc = new Comp();
            pc.id = pending[0];
            pc.wcaId = pending[0];
            pc.name = pending[1];
            openCompetition(pc);
            return;
        }
        if (inDetail) {
            if (detailError != null && !fetching && WcaHttp.isOnline(requireContext())) {
                fetchResults();
            } else {
                scheduleAuto();
            }
        } else if (!listLoaded && !listLoading) {
            loadCompetitions(null);
        } else if (listError != null && !listLoading && WcaHttp.isOnline(requireContext())) {
            loadCompetitions(null);
        }
    }

    private void updateBack() {
        if (backCallback != null) {
            backCallback.setEnabled(inDetail && getUserVisibleHint());
        }
    }

    // ---------------------------------------------------------------- list page

    private LinearLayout buildListPage() {
        LinearLayout page = new LinearLayout(requireContext());
        page.setOrientation(LinearLayout.VERTICAL);

        page.addView(ui.text("WCA Live", 22, true, 1f), ui.matchWrap(4, 4, 4, 8));

        LinearLayout searchRow = new LinearLayout(requireContext());
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchInput = ui.input("Search competitions");
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                doSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(searchInput, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        ((LinearLayout.LayoutParams) searchInput.getLayoutParams()).weight = 1f;
        TextView go = ui.pill("Search", true);
        go.setOnClickListener(v -> {
            hideKeyboard();
            doSearch();
        });
        searchRow.addView(go);
        page.addView(searchRow, ui.matchWrap(0, 0, 0, 8));

        LinearLayout pills = new LinearLayout(requireContext());
        pills.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"All", "Favorites", "Recent"};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            TextView p = ui.pill(names[i], i == 0);
            p.setOnClickListener(v -> {
                filterMode = mode;
                for (int k = 0; k < filterPills.size(); k++) ui.stylePill(filterPills.get(k), k == mode);
                renderList();
            });
            filterPills.add(p);
            pills.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        }
        page.addView(pills, ui.matchWrap(0, 0, 0, 8));

        listStatus = ui.text("", 13, false, 0.8f);
        page.addView(listStatus, ui.matchWrap(4, 0, 4, 6));

        ScrollView scroll = new ScrollView(requireContext());
        listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null && searchInput != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void doSearch() {
        String q = searchInput.getText().toString().trim();
        filterMode = 0;
        for (int k = 0; k < filterPills.size(); k++) ui.stylePill(filterPills.get(k), k == 0);
        loadCompetitions(q.isEmpty() ? null : q);
    }

    private static String isoDate(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private void loadCompetitions(String filter) {
        listLoading = true;
        listError = null;
        listStatus.setText("Loading competitions...");
        Calendar from = Calendar.getInstance();
        if (filter == null) from.add(Calendar.DAY_OF_MONTH, -7);
        else from.add(Calendar.YEAR, -20);
        try {
            JSONObject vars = new JSONObject();
            vars.put("from", isoDate(from));
            vars.put("limit", 100);
            vars.put("filter", filter == null ? JSONObject.NULL : filter);
            JSONObject body = new JSONObject();
            body.put("query", "query($from: Date, $filter: String, $limit: Int) "
                    + "{ competitions(from: $from, filter: $filter, limit: $limit) "
                    + "{ id wcaId name shortName startDate endDate } }");
            body.put("variables", vars);
            WcaHttp.postJson(API, body.toString(), new WcaHttp.Callback() {
                @Override
                public void onSuccess(String text) {
                    if (!isAdded()) return;
                    listLoading = false;
                    try {
                        JSONObject root = new JSONObject(text);
                        JSONArray arr = root.getJSONObject("data").getJSONArray("competitions");
                        List<Comp> out = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            Comp c = new Comp();
                            c.id = o.optString("id");
                            c.wcaId = o.optString("wcaId");
                            c.name = o.optString("name");
                            c.start = o.optString("startDate");
                            c.end = o.optString("endDate");
                            out.add(c);
                        }
                        loadedComps = out;
                        listLoaded = true;
                        listFetchedAt = System.currentTimeMillis();
                        listError = null;
                    } catch (Exception e) {
                        listError = "Unexpected response from WCA Live";
                    }
                    renderList();
                }

                @Override
                public void onError(String message) {
                    if (!isAdded()) return;
                    listLoading = false;
                    listError = WcaHttp.describe(requireContext(), message);
                    renderList();
                }
            });
        } catch (Exception e) {
            listLoading = false;
            listError = "Could not build the request";
            renderList();
        }
    }

    private String status(Comp c) {
        String today = isoDate(Calendar.getInstance());
        if (c.end.compareTo(today) < 0) return "Finished";
        if (c.start.compareTo(today) > 0) return "Upcoming";
        return "Ongoing";
    }

    private int statusRank(Comp c) {
        switch (status(c)) {
            case "Ongoing": return 0;
            case "Upcoming": return 1;
            default: return 2;
        }
    }

    private void renderList() {
        listContainer.removeAllViews();
        List<Comp> items = new ArrayList<>();
        if (filterMode == 0) {
            items.addAll(loadedComps);
            final String today = isoDate(Calendar.getInstance());
            Collections.sort(items, new Comparator<Comp>() {
                @Override
                public int compare(Comp a, Comp b) {
                    int ra = statusRank(a), rb = statusRank(b);
                    if (ra != rb) return ra - rb;
                    if (ra == 1) return a.start.compareTo(b.start);   // soonest upcoming first
                    return b.start.compareTo(a.start);                  // newest first
                }
            });
        } else {
            List<String[]> saved = store.getComps(filterMode == 1 ? "fav_comps" : "recent_comps");
            for (String[] s : saved) {
                Comp c = new Comp();
                c.id = s[0];
                c.wcaId = s[0];
                c.name = s[1];
                items.add(c);
            }
        }

        if (filterMode == 0) {
            if (listError != null) {
                String when = listLoaded ? " Showing the list from " + time(listFetchedAt) + "." : "";
                listStatus.setText(listError + "." + when);
            } else if (listLoaded) {
                listStatus.setText("Live data from WCA Live, fetched " + time(listFetchedAt)
                        + ". " + items.size() + " competitions.");
            } else {
                listStatus.setText("");
            }
        } else {
            listStatus.setText("Saved on this device.");
        }

        if (items.isEmpty()) {
            String msg = filterMode == 1 ? "No favorite competitions yet. Tap the star on a competition."
                    : filterMode == 2 ? "No recently viewed competitions yet."
                    : (listError != null ? "" : "No competitions found.");
            if (!msg.isEmpty()) listContainer.addView(ui.text(msg, 15, false, 0.8f), ui.matchWrap(8, 12, 8, 12));
            return;
        }

        for (Comp c : items) {
            listContainer.addView(buildCompCard(c), ui.matchWrap(0, 4, 0, 4));
        }
    }

    private View buildCompCard(Comp c) {
        LinearLayout card = ui.card(false);
        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.text(c.name, 16, true, 1f);
        top.addView(name, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        ((LinearLayout.LayoutParams) name.getLayoutParams()).weight = 1f;
        final ImageView star = ui.iconButton(store.isFavoriteComp(c.wcaId)
                ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.itemText);
        star.setOnClickListener(v -> {
            store.toggleFavoriteComp(c.wcaId, c.name);
            star.setImageDrawable(ui.tinted(store.isFavoriteComp(c.wcaId)
                    ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.itemText));
            if (filterMode == 1) renderList();
        });
        top.addView(star);
        card.addView(top);
        if (!c.start.isEmpty()) {
            String dates = c.start.equals(c.end) ? c.start : c.start + " to " + c.end;
            card.addView(ui.text(dates + "  \u00B7  " + status(c), 13, false, 0.8f));
        }
        card.setOnClickListener(v -> openCompetition(c));
        return card;
    }

    private String time(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(ms));
    }

    // ---------------------------------------------------------------- detail page

    private LinearLayout buildDetailPage() {
        LinearLayout page = new LinearLayout(requireContext());
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = ui.text("\u2190", 26, true, 1f);
        back.setPadding(ui.dp(8), ui.dp(4), ui.dp(16), ui.dp(4));
        back.setOnClickListener(v -> closeDetail());
        header.addView(back);
        detailTitle = ui.text("", 18, true, 1f);
        header.addView(detailTitle, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        ((LinearLayout.LayoutParams) detailTitle.getLayoutParams()).weight = 1f;
        TextView refresh = ui.pill("Refresh", false);
        refresh.setOnClickListener(v -> fetchResults());
        header.addView(refresh);
        page.addView(header, ui.matchWrap(0, 0, 0, 6));

        detailStatus = ui.text("", 13, false, 0.85f);
        page.addView(detailStatus, ui.matchWrap(4, 0, 4, 6));

        eventRow = new LinearLayout(requireContext());
        eventRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView evScroll = new HorizontalScrollView(requireContext());
        evScroll.setHorizontalScrollBarEnabled(false);
        evScroll.addView(eventRow);
        page.addView(evScroll, ui.matchWrap(0, 0, 0, 6));

        roundRow = new LinearLayout(requireContext());
        roundRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView rdScroll = new HorizontalScrollView(requireContext());
        rdScroll.setHorizontalScrollBarEnabled(false);
        rdScroll.addView(roundRow);
        page.addView(rdScroll, ui.matchWrap(0, 0, 0, 6));

        LinearLayout opts = new LinearLayout(requireContext());
        opts.setOrientation(LinearLayout.HORIZONTAL);
        attemptsPill = ui.pill("Attempts", false);
        attemptsPill.setOnClickListener(v -> {
            showAttempts = !showAttempts;
            ui.stylePill(attemptsPill, showAttempts);
            renderResults();
        });
        sortPill = ui.pill("Sort: rank", false);
        sortPill.setOnClickListener(v -> {
            sortByName = !sortByName;
            sortPill.setText(sortByName ? "Sort: name" : "Sort: rank");
            renderResults();
        });
        opts.addView(attemptsPill, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        opts.addView(sortPill);
        page.addView(opts, ui.matchWrap(0, 0, 0, 8));

        resultsScroll = new ScrollView(requireContext());
        resultsContainer = new LinearLayout(requireContext());
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        resultsScroll.addView(resultsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(resultsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void openCompetition(Comp c) {
        hideKeyboard();
        if (searchInput != null) searchInput.clearFocus();
        fetching = false;
        currentComp = c;
        store.addRecentComp(c.wcaId, c.name);
        events = new ArrayList<>();
        people = new HashMap<>();
        eventIndex = 0;
        roundIndex = 0;
        haveData = false;
        liveOk = false;
        detailError = null;
        detailFetchedAt = 0L;
        inDetail = true;
        listPage.setVisibility(View.GONE);
        detailPage.setVisibility(View.VISIBLE);
        detailTitle.setText(c.name);
        updateBack();
        renderEvents();
        renderResults();
        fetchResults();
    }

    private void closeDetail() {
        inDetail = false;
        handler.removeCallbacks(autoRunnable);
        detailPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        updateBack();
        if (filterMode != 0) renderList();
    }

    private void scheduleAuto() {
        handler.removeCallbacks(autoRunnable);
        if (autoRefresh && inDetail && getUserVisibleHint()) {
            handler.postDelayed(autoRunnable, AUTO_REFRESH_MS);
        }
    }

    private void fetchResults() {
        if (currentComp == null || fetching) return;
        fetching = true;
        detailStatus.setText(haveData ? statusText() + "  Refreshing..." : "Loading results...");
        final Comp comp = currentComp;
        WcaHttp.get(API + "/competitions/" + Uri.encode(comp.wcaId) + "/results", new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                fetching = false;
                if (!isAdded() || currentComp != comp) return;
                try {
                    parseResults(body);
                    haveData = true;
                    liveOk = true;
                    detailError = null;
                    detailFetchedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    liveOk = false;
                    detailError = "Unexpected response from WCA Live";
                }
                renderEvents();
                renderResults();
                scheduleAuto();
            }

            @Override
            public void onError(String message) {
                fetching = false;
                if (!isAdded() || currentComp != comp) return;
                liveOk = false;
                detailError = WcaHttp.describe(requireContext(), message);
                renderResults();
                scheduleAuto();
            }
        });
    }

    private void parseResults(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        Map<Integer, Person> ppl = new HashMap<>();
        JSONArray pa = root.optJSONArray("persons");
        if (pa != null) {
            for (int i = 0; i < pa.length(); i++) {
                JSONObject o = pa.getJSONObject(i);
                Person p = new Person();
                p.name = o.optString("name");
                p.wcaId = o.isNull("wcaId") ? "" : o.optString("wcaId");
                Object co = o.opt("country");
                String disp = "";
                String iso = "";
                if (co instanceof JSONObject) {
                    JSONObject cj = (JSONObject) co;
                    disp = cj.optString("name", "");
                    iso = WcaFlags.code(cj.optString("iso2", cj.optString("id", "")));
                    if (iso.isEmpty()) iso = WcaFlags.code(disp);
                } else if (co != null && co != JSONObject.NULL) {
                    disp = String.valueOf(co);
                    iso = WcaFlags.code(disp);
                }
                if (iso.isEmpty()) iso = WcaFlags.code(o.optString("countryIso2", o.optString("country_iso2", "")));
                p.country = disp.isEmpty() ? iso : disp;
                p.iso = iso;
                ppl.put(o.optInt("id"), p);
            }
        }
        List<Ev> evs = new ArrayList<>();
        JSONArray ea = root.getJSONArray("events");
        for (int i = 0; i < ea.length(); i++) {
            JSONObject eo = ea.getJSONObject(i);
            Ev ev = new Ev();
            ev.id = eo.optString("eventId");
            JSONArray ra = eo.optJSONArray("rounds");
            if (ra != null) {
                for (int j = 0; j < ra.length(); j++) {
                    JSONObject ro = ra.getJSONObject(j);
                    Round rd = new Round();
                    rd.number = ro.optInt("number");
                    JSONArray rs = ro.optJSONArray("results");
                    if (rs != null) {
                        for (int k = 0; k < rs.length(); k++) {
                            JSONObject r = rs.getJSONObject(k);
                            Row row = new Row();
                            row.personId = r.optInt("personId");
                            row.ranking = r.isNull("ranking") ? 0 : r.optInt("ranking");
                            row.best = r.optLong("best");
                            row.average = r.optLong("average");
                            JSONObject rt = r.optJSONObject("recordTags");
                            row.singleTag = tagOf(r, "singleRecordTag", "single_record_tag");
                            row.avgTag = tagOf(r, "averageRecordTag", "average_record_tag");
                            if (rt != null) {
                                if (row.singleTag.isEmpty()) row.singleTag = tagOf(rt, "single");
                                if (row.avgTag.isEmpty()) row.avgTag = tagOf(rt, "average");
                            }
                            JSONArray at = r.optJSONArray("attempts");
                            if (at != null) {
                                row.attempts = new long[at.length()];
                                for (int m = 0; m < at.length(); m++) row.attempts[m] = at.optLong(m);
                            }
                            rd.rows.add(row);
                        }
                    }
                    ev.rounds.add(rd);
                }
            }
            evs.add(ev);
        }
        Collections.sort(evs, new Comparator<Ev>() {
            @Override
            public int compare(Ev a, Ev b) {
                return WcaFormat.eventOrder(a.id) - WcaFormat.eventOrder(b.id);
            }
        });

        // Keep the person's selected event if it is still there; otherwise pick the first event
        // that has results and its latest round that has results.
        String keepId = (eventIndex < events.size()) ? events.get(eventIndex).id : null;
        int keepRound = (eventIndex < events.size() && roundIndex < events.get(eventIndex).rounds.size())
                ? events.get(eventIndex).rounds.get(roundIndex).number : -1;
        events = evs;
        people = ppl;
        eventIndex = 0;
        roundIndex = 0;
        if (events.isEmpty()) {
            return;
        }
        boolean found = false;
        if (keepId != null) {
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).id.equals(keepId)) {
                    eventIndex = i;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            for (int i = 0; i < events.size(); i++) {
                if (latestRoundWithResults(events.get(i)) >= 0) {
                    eventIndex = i;
                    break;
                }
            }
        }
        if (found && keepRound >= 0) {
            List<Round> rds = events.get(eventIndex).rounds;
            for (int j = 0; j < rds.size(); j++) {
                if (rds.get(j).number == keepRound) roundIndex = j;
            }
        } else {
            roundIndex = Math.max(0, latestRoundWithResults(events.get(eventIndex)));
        }
    }

    private int latestRoundWithResults(Ev ev) {
        int idx = -1;
        for (int j = 0; j < ev.rounds.size(); j++) {
            if (!ev.rounds.get(j).rows.isEmpty()) idx = j;
        }
        return idx;
    }

    private String statusText() {
        if (liveOk) {
            return "LIVE  \u00B7  last updated " + time(detailFetchedAt)
                    + (autoRefresh ? "  \u00B7  refreshes every 30s" : "  \u00B7  auto-refresh off");
        }
        if (haveData) {
            return detailError + ". Showing data from " + time(detailFetchedAt)
                    + ", which may be out of date.";
        }
        return "";
    }

    private void renderEvents() {
        eventRow.removeAllViews();
        for (int i = 0; i < events.size(); i++) {
            final int idx = i;
            TextView p = ui.pill(WcaFormat.eventName(events.get(i).id), i == eventIndex);
            ui.setEventIcon(p, events.get(i).id, 16, ui.text);
            p.setOnClickListener(v -> {
                eventIndex = idx;
                roundIndex = Math.max(0, latestRoundWithResults(events.get(idx)));
                renderEvents();
                renderResults();
            });
            eventRow.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        }
        roundRow.removeAllViews();
        if (eventIndex < events.size()) {
            Ev ev = events.get(eventIndex);
            for (int j = 0; j < ev.rounds.size(); j++) {
                final int idx = j;
                Round rd = ev.rounds.get(j);
                String label = "Round " + rd.number + (rd.rows.isEmpty() ? " (no results)" : "");
                TextView p = ui.pill(label, j == roundIndex);
                p.setOnClickListener(v -> {
                    roundIndex = idx;
                    renderEvents();
                    renderResults();
                });
                roundRow.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            }
        }
    }

    private void renderResults() {
        resultsContainer.removeAllViews();
        if (haveData) {
            String s = statusText();
            if (!liveOk && detailError == null) s = "";
            detailStatus.setText(s);
        } else if (detailError != null) {
            detailStatus.setText(detailError + ".");
        }
        if (!haveData) {
            if (detailError != null) {
                boolean offline = detailError.startsWith("No internet");
                resultsContainer.addView(ui.text(
                        offline ? "Turn on Wi-Fi or mobile data, then tap Refresh."
                                : "Tap Refresh to try again.",
                        15, false, 0.85f), ui.matchWrap(8, 12, 8, 12));
                if (currentComp != null) {
                    final String compId = currentComp.wcaId;
                    TextView web = ui.pill("Open on the WCA website", false);
                    web.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.worldcubeassociation.org/competitions/" + Uri.encode(compId)))));
                    resultsContainer.addView(web, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 8, 8));
                }
            }
            return;
        }
        if (events.isEmpty() || eventIndex >= events.size()) {
            resultsContainer.addView(ui.text("This competition has no results on WCA Live yet.",
                    15, false, 0.85f), ui.matchWrap(8, 12, 8, 12));
            return;
        }
        Ev ev = events.get(eventIndex);
        if (roundIndex >= ev.rounds.size()) roundIndex = 0;
        if (ev.rounds.isEmpty() || ev.rounds.get(roundIndex).rows.isEmpty()) {
            resultsContainer.addView(ui.text("No results in this round yet.", 15, false, 0.85f),
                    ui.matchWrap(8, 12, 8, 12));
            return;
        }
        List<Row> rows = new ArrayList<>(ev.rounds.get(roundIndex).rows);
        final Map<Integer, Person> ppl = people;
        if (sortByName) {
            Collections.sort(rows, new Comparator<Row>() {
                @Override
                public int compare(Row a, Row b) {
                    return nameOf(ppl, a).compareToIgnoreCase(nameOf(ppl, b));
                }
            });
        } else {
            Collections.sort(rows, new Comparator<Row>() {
                @Override
                public int compare(Row a, Row b) {
                    int ra = a.ranking <= 0 ? Integer.MAX_VALUE : a.ranking;
                    int rb = b.ranking <= 0 ? Integer.MAX_VALUE : b.ranking;
                    return ra < rb ? -1 : (ra == rb ? 0 : 1);
                }
            });
        }
        boolean hasAverage = "333mbf".equals(ev.id) ? false : true;
        for (Row r : rows) {
            Person p = ppl.get(r.personId);
            String name = p == null ? "Competitor " + r.personId : p.name;
            String key = (p != null && !p.wcaId.isEmpty()) ? p.wcaId : name;
            boolean mine = store.isPinned(key);
            boolean fav = store.isFavoritePerson(key);
            resultsContainer.addView(buildRow(r, p, name, key, ev.id, hasAverage, mine || fav),
                    ui.matchWrap(0, 3, 0, 3));
        }
    }

    /** Reads a record tag (WR, CR, NR, PR, or a named continent like AsR) from the first key that has one. */
    private static String tagOf(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) continue;
            String v = o.optString(k, "").trim();
            if (v.isEmpty()) continue;
            String u = v.toUpperCase(Locale.US);
            if (u.equals("WR") || u.equals("CR") || u.equals("NR") || u.equals("PR")) return u;
            if (u.endsWith("R") && u.length() <= 3) return v;
        }
        return "";
    }

    private static String nameOf(Map<Integer, Person> ppl, Row r) {
        Person p = ppl.get(r.personId);
        return p == null ? "" : p.name;
    }

    private View buildRow(Row r, Person p, String name, String key, String eventId,
                          boolean hasAverage, boolean highlighted) {
        LinearLayout card = ui.card(highlighted);
        LinearLayout line = new LinearLayout(requireContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView rank = ui.text(r.ranking > 0 ? String.valueOf(r.ranking) : "-", 16, true, 1f);
        rank.setMinWidth(ui.dp(32));
        line.addView(rank);

        LinearLayout mid = new LinearLayout(requireContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        String flag = p == null ? "" : WcaFlags.emoji(p.iso);
        mid.addView(ui.text((flag.isEmpty() ? "" : flag + " ") + name, 16, true, 1f));
        if (p != null && (!p.country.isEmpty() || !p.wcaId.isEmpty())) {
            mid.addView(ui.text(p.country + (p.country.isEmpty() || p.wcaId.isEmpty() ? "" : "  \u00B7  ")
                    + p.wcaId, 12, false, 0.7f));
        }
        line.addView(mid, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 8, 0));
        ((LinearLayout.LayoutParams) mid.getLayoutParams()).weight = 1f;

        LinearLayout right = new LinearLayout(requireContext());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        String avg = WcaFormat.formatResult(r.average, eventId, true);
        String best = WcaFormat.formatResult(r.best, eventId, false);
        if (hasAverage && !avg.isEmpty()) {
            right.addView(ui.resultCell("avg", avg, r.avgTag, 16, true));
        }
        if (!best.isEmpty()) {
            right.addView(ui.resultCell("best", best, r.singleTag, 13, false));
        }
        line.addView(right);
        card.addView(line);

        if (showAttempts && r.attempts.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.attempts.length; i++) {
                String t = WcaFormat.formatResult(r.attempts[i], eventId, false);
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append("   ");
                sb.append(t);
            }
            if (sb.length() > 0) card.addView(ui.text(sb.toString(), 13, false, 0.85f), ui.matchWrap(36, 4, 0, 0));
        }

        card.setOnClickListener(v -> showPersonResults(r.personId, p, name));
        card.setOnLongClickListener(v -> {
            store.toggleFavoritePerson(key);
            renderResults();
            return true;
        });
        return card;
    }

    // Shows every result this person has in the competition, across all events and rounds.
    private void showPersonResults(int personId, Person p, String name) {
        final String key = (p != null && !p.wcaId.isEmpty()) ? p.wcaId : name;
        final android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(20), ui.dp(10), ui.dp(8), ui.dp(4));
        String flag = p == null ? "" : WcaFlags.emoji(p.iso);
        TextView title = ui.text((flag.isEmpty() ? "" : flag + " ") + name, 18, true, 1f);
        head.addView(title, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;
        final ImageView star = ui.iconButton(store.isFavoritePerson(key)
                ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.itemText);
        star.setContentDescription("Favorite");
        star.setOnClickListener(v -> {
            store.toggleFavoritePerson(key);
            star.setImageDrawable(ui.tinted(store.isFavoritePerson(key)
                    ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.itemText));
            renderResults();
        });
        head.addView(star);
        ImageView close = ui.iconButton(R.drawable.ic_wca_close, 24, ui.itemText);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        head.addView(close);
        root.addView(head);

        ScrollView sv = new ScrollView(requireContext());
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(16));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (p != null && (!p.country.isEmpty() || !p.wcaId.isEmpty())) {
            box.addView(ui.text(p.country + (p.country.isEmpty() || p.wcaId.isEmpty() ? "" : "  \u00B7  ")
                    + p.wcaId, 13, false, 0.7f), ui.matchWrap(0, 0, 0, 4));
        }
        boolean any = false;
        for (Ev ev : events) {
            for (Round rd : ev.rounds) {
                for (Row row : rd.rows) {
                    if (row.personId != personId) continue;
                    any = true;
                    TextView evTitle = ui.text(WcaFormat.eventName(ev.id) + "  \u00B7  Round " + rd.number,
                            15, true, 1f);
                    ui.setEventIcon(evTitle, ev.id, 18, ui.text);
                    box.addView(evTitle, ui.matchWrap(0, 12, 0, 2));
                    LinearLayout vals = new LinearLayout(requireContext());
                    vals.setOrientation(LinearLayout.HORIZONTAL);
                    vals.setGravity(Gravity.CENTER_VERTICAL);
                    vals.addView(ui.text(row.ranking > 0 ? "#" + row.ranking : "#-", 14, true, 0.9f),
                            ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 12, 0));
                    String avg = WcaFormat.formatResult(row.average, ev.id, true);
                    String best = WcaFormat.formatResult(row.best, ev.id, false);
                    if (!"333mbf".equals(ev.id) && !avg.isEmpty()) vals.addView(ui.resultCell("avg", avg, row.avgTag, 14, true));
                    if (!best.isEmpty()) vals.addView(ui.resultCell("best", best, row.singleTag, 14, false));
                    box.addView(vals);
                    StringBuilder at = new StringBuilder();
                    for (long a : row.attempts) {
                        String t = WcaFormat.formatResult(a, ev.id, false);
                        if (t.isEmpty()) continue;
                        if (at.length() > 0) at.append("   ");
                        at.append(t);
                    }
                    if (at.length() > 0) box.addView(ui.text(at.toString(), 13, false, 0.8f));
                }
            }
        }
        if (!any) box.addView(ui.text("No results yet.", 14, false, 0.85f));

        android.app.Dialog d = ui.dialog(root);
        holder[0] = d;
        d.show();
    }
}
