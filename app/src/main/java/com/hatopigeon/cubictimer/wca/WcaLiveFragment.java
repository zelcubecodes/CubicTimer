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
import com.hatopigeon.cubictimer.fragment.TimerFragmentMain;

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
        boolean advancing;
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
    private TextView sortPill;
    private ScrollView resultsScroll;
    private HorizontalScrollView evScrollView, rdScrollView;
    private LinearLayout optsRow;
    private ScrollView compScroll;
    private TextView modeResults, modeComp;
    private WcaCompPage compPage = null;
    private String compPageFor = "";
    private boolean compMode = false;

    private Comp currentComp = null;
    private boolean inDetail = false;
    private List<Ev> events = new ArrayList<>();
    private Map<Integer, Person> people = new HashMap<>();
    private int eventIndex = 0;
    private int roundIndex = 0;
    private boolean showAttempts = false;
    private final List<TextView> tabTexts = new ArrayList<>();
    private final List<View> tabLines = new ArrayList<>();
    private String searchQuery = null;
    private Ev curEv;
    private Round curRd;
    private LinearLayout suggestBox;
    private Runnable suggestRunnable = () -> { };
    private int suggestToken = 0;
    private int visibleCount = 30;
    private LinearLayout recordsBox;
    private boolean recordsLoading, recordsLoaded;
    private String recordsError = null;
    private int recordsShown = 10;
    private final List<String[]> records = new ArrayList<>();
    private static final String REC_Q1 = "{ recentRecords { id tag type attemptResult result { person { name "
            + "country { iso2 name } } round { competitionEvent { event { id name } } } } } }";
    private static final String REC_Q2 = "{ recentRecords { tag type attemptResult result { person { name "
            + "country { name } } round { competitionEvent { event { id name } } } } } }";
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
        WcaNames.init(requireContext());
        autoRefresh = store.getAutoRefresh();
        showAttempts = store.getShowAttempts();

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
        boolean attemptsSetting = store.getShowAttempts();
        if (attemptsSetting != showAttempts) {
            showAttempts = attemptsSetting;
            if (haveData && resultsContainer != null) renderResults();
        }
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
        if (!inDetail && !recordsLoaded && !recordsLoading) loadRecords();
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

        page.addView(ui.text("WCA Live", 22, true, 1f), ui.matchWrap(4, 0, 4, 4));

        LinearLayout searchRow = new LinearLayout(requireContext());
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchInput = ui.input("Search competitions");
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(suggestRunnable);
                runSuggest(searchInput.getText().toString().trim());
                return true;
            }
            return false;
        });
        searchRow.addView(searchInput, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) searchInput.getLayoutParams()).weight = 1f;
        page.addView(searchRow, ui.matchWrap(0, 0, 0, 6));

        suggestBox = new LinearLayout(requireContext());
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        suggestBox.setBackground(ui.rounded(ui.itemBg, ui.dp(8)));
        suggestBox.setVisibility(View.GONE);
        page.addView(suggestBox, ui.matchWrap(0, 0, 0, 8));
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                final String q = e.toString().trim();
                handler.removeCallbacks(suggestRunnable);
                if (q.length() < 2) {
                    suggestToken++;
                    suggestBox.removeAllViews();
                    suggestBox.setVisibility(View.GONE);
                    return;
                }
                suggestRunnable = () -> runSuggest(q);
                handler.postDelayed(suggestRunnable, 350);
            }
        });

        LinearLayout tabCard = new LinearLayout(requireContext());
        tabCard.setOrientation(LinearLayout.HORIZONTAL);
        tabCard.setBackground(ui.rounded(ui.itemBg, ui.dp(8)));
        String[] names = {"UPCOMING", "PAST MONTH", "FAVORITES", "RECENT"};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = ui.text(names[i], 12, true, 1f);
            t.setTextColor(ui.itemText);
            t.setGravity(Gravity.CENTER);
            t.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), ui.dp(12));
            View line = new View(requireContext());
            col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3)));
            col.setOnClickListener(v -> {
                filterMode = mode;
                visibleCount = 30;
                styleTabs();
                renderList();
            });
            tabTexts.add(t);
            tabLines.add(line);
            tabCard.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        styleTabs();
        page.addView(tabCard, ui.matchWrap(0, 0, 0, 8));

        listStatus = ui.status(13);
        page.addView(listStatus, ui.matchWrap(4, 0, 4, 6));

        ScrollView scroll = new ScrollView(requireContext());
        listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout homeColumn = new LinearLayout(requireContext());
        homeColumn.setOrientation(LinearLayout.VERTICAL);
        // The competitions have their own scroller, so Recent records stays reachable underneath.
        ScrollView listScroll = new ScrollView(requireContext()) {
            private float lastY;

            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        lastY = ev.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dy = ev.getY() - lastY;
                        lastY = ev.getY();
                        // at the top or bottom of this list, let the page scroll instead
                        boolean canMove = canScrollVertically(dy < 0 ? 1 : -1);
                        getParent().requestDisallowInterceptTouchEvent(canMove);
                        break;
                    default:
                        break;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        homeColumn.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(340)));
        recordsBox = new LinearLayout(requireContext());
        recordsBox.setOrientation(LinearLayout.VERTICAL);
        homeColumn.addView(recordsBox, ui.matchWrap(0, 20, 0, 16));
        scroll.addView(homeColumn, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void styleTabs() {
        for (int k = 0; k < tabTexts.size(); k++) {
            boolean sel = k == filterMode;
            tabTexts.get(k).setAlpha(sel ? 1f : 0.6f);
            tabLines.get(k).setBackgroundColor(sel ? 0xFFE91E63 : 0x00000000);
        }
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

    /** Looks up competitions of any date that match the typed text and shows them in a drop-down. */
    private void runSuggest(final String q) {
        if (q.length() < 2) return;
        final int token = ++suggestToken;
        suggestBox.removeAllViews();
        suggestBox.setVisibility(View.VISIBLE);
        suggestBox.addView(ui.text("Searching...", 14, false, 0.8f), ui.matchWrap(14, 12, 14, 12));
        suggestBox.setBackground(ui.rounded(ui.itemBg, ui.dp(8)));
        Calendar from = Calendar.getInstance();
        from.add(Calendar.YEAR, -20);
        try {
            JSONObject vars = new JSONObject();
            vars.put("from", isoDate(from));
            vars.put("limit", 8);
            vars.put("filter", q);
            JSONObject body = new JSONObject();
            body.put("query", "query($from: Date, $filter: String, $limit: Int) "
                    + "{ competitions(from: $from, filter: $filter, limit: $limit) "
                    + "{ id wcaId name shortName startDate endDate } }");
            body.put("variables", vars);
            WcaHttp.postJson(API, body.toString(), new WcaHttp.Callback() {
                @Override
                public void onSuccess(String text) {
                    if (!isAdded() || token != suggestToken) return;
                    suggestBox.removeAllViews();
                    try {
                        JSONArray arr = new JSONObject(text).getJSONObject("data").getJSONArray("competitions");
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
                        Collections.sort(out, new Comparator<Comp>() {
                            @Override
                            public int compare(Comp a, Comp b) {
                                return b.start.compareTo(a.start);
                            }
                        });
                        if (out.isEmpty()) {
                            suggestBox.addView(ui.text("No competitions found.", 14, false, 0.8f),
                                    ui.matchWrap(14, 12, 14, 12));
                        }
                        for (final Comp c : out) {
                            suggestBox.addView(buildSuggestRow(c));
                        }
                    } catch (Exception e) {
                        suggestBox.addView(ui.text("Unexpected response from WCA Live.", 14, false, 0.8f),
                                ui.matchWrap(14, 12, 14, 12));
                    }
                }

                @Override
                public void onError(String message) {
                    if (!isAdded() || token != suggestToken) return;
                    suggestBox.removeAllViews();
                    suggestBox.addView(ui.text(WcaHttp.describe(requireContext(), message) + ".", 14, false, 0.8f),
                            ui.matchWrap(14, 12, 14, 12));
                }
            });
        } catch (Exception e) {
            suggestBox.removeAllViews();
            suggestBox.setVisibility(View.GONE);
        }
    }

    private View buildSuggestRow(final Comp c) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        final TextView flag = ui.text("", 22, false, 1f);
        flag.setGravity(Gravity.CENTER);
        row.addView(flag, ui.lp(ui.dp(36), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        WcaNames.lookupCompCountry(c.wcaId, (id, iso) -> {
            if (isAdded()) flag.setText(WcaFlags.emoji(iso));
        });
        LinearLayout mid = new LinearLayout(requireContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(ui.text(c.name, 15, true, 1f));
        if (!c.start.isEmpty()) mid.addView(ui.text(fmtDates(c.start, c.end), 12, false, 0.8f));
        row.addView(mid, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) mid.getLayoutParams()).weight = 1f;
        ui.recolor(row, ui.text, ui.itemText);
        row.setOnClickListener(v -> {
            hideKeyboard();
            suggestToken++;
            suggestBox.removeAllViews();
            suggestBox.setVisibility(View.GONE);
            searchInput.setText("");
            openCompetition(c);
        });
        return row;
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
        if (filter == null) from.add(Calendar.DAY_OF_MONTH, -31);
        else from.add(Calendar.YEAR, -20);
        try {
            JSONObject vars = new JSONObject();
            vars.put("from", isoDate(from));
            vars.put("limit", 250);
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
        recordsBox.setVisibility(filterMode <= 1 && searchQuery == null ? View.VISIBLE : View.GONE);
        List<Comp> items = new ArrayList<>();
        final String today = isoDate(Calendar.getInstance());
        if (filterMode <= 1) {
            if (searchQuery != null) {
                items.addAll(loadedComps);
                Collections.sort(items, new Comparator<Comp>() {
                    @Override
                    public int compare(Comp a, Comp b) {
                        return b.start.compareTo(a.start);
                    }
                });
            } else {
                Calendar pm = Calendar.getInstance();
                pm.add(Calendar.DAY_OF_MONTH, -30);
                String monthAgo = isoDate(pm);
                for (Comp c : loadedComps) {
                    boolean finished = c.end.compareTo(today) < 0;
                    if (filterMode == 0 && !finished) items.add(c);
                    if (filterMode == 1 && finished && c.end.compareTo(monthAgo) >= 0) items.add(c);
                }
                final int mode = filterMode;
                Collections.sort(items, new Comparator<Comp>() {
                    @Override
                    public int compare(Comp a, Comp b) {
                        if (mode == 0) {
                            int ra = statusRank(a), rb = statusRank(b);
                            if (ra != rb) return ra - rb;
                            return a.start.compareTo(b.start);   // soonest first
                        }
                        return b.end.compareTo(a.end);             // newest first
                    }
                });
            }
        } else {
            List<String[]> saved = store.getComps(filterMode == 2 ? "fav_comps" : "recent_comps");
            for (String[] sv : saved) {
                Comp c = new Comp();
                c.id = sv[0];
                c.wcaId = sv[0];
                c.name = sv[1];
                items.add(c);
            }
        }

        if (filterMode <= 1) {
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
            String msg = filterMode == 2 ? "No favorite competitions yet. Tap the star on a competition."
                    : filterMode == 3 ? "No recently viewed competitions yet."
                    : (listError != null ? "" : "No competitions found.");
            if (!msg.isEmpty()) listContainer.addView(ui.text(msg, 15, false, 0.8f), ui.matchWrap(8, 12, 8, 12));
            return;
        }

        int limit = Math.min(visibleCount, items.size());
        for (int i = 0; i < limit; i++) {
            listContainer.addView(buildCompCard(items.get(i)), ui.matchWrap(0, 4, 0, 4));
        }
        if (limit < items.size()) {
            TextView more = ui.pill("Show more (" + (items.size() - limit) + " left)", false);
            more.setOnClickListener(v -> {
                visibleCount += 30;
                renderList();
            });
            listContainer.addView(more, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 4));
        }
    }

    private static String fmtDates(String start, String end) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date a = in.parse(start);
            Date b = end == null || end.isEmpty() ? a : in.parse(end);
            if (a == null || b == null) return start;
            SimpleDateFormat md = new SimpleDateFormat("MMM d", Locale.ENGLISH);
            SimpleDateFormat d = new SimpleDateFormat("d", Locale.ENGLISH);
            SimpleDateFormat y = new SimpleDateFormat("yyyy", Locale.ENGLISH);
            SimpleDateFormat m = new SimpleDateFormat("MMM", Locale.ENGLISH);
            if (start.equals(end) || end == null || end.isEmpty()) return md.format(a) + ", " + y.format(a);
            if (m.format(a).equals(m.format(b)) && y.format(a).equals(y.format(b))) {
                return md.format(a) + " - " + d.format(b) + ", " + y.format(a);
            }
            return md.format(a) + " - " + md.format(b) + ", " + y.format(b);
        } catch (Exception e) {
            return start;
        }
    }

    private View buildCompCard(Comp c) {
        LinearLayout card = ui.card(false);
        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        final TextView flag = ui.text("", 26, false, 1f);
        flag.setGravity(Gravity.CENTER);
        top.addView(flag, ui.lp(ui.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        WcaNames.lookupCompCountry(c.wcaId, (id, iso) -> {
            if (isAdded()) flag.setText(WcaFlags.emoji(iso));
        });

        LinearLayout mid = new LinearLayout(requireContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(ui.text(c.name, 16, true, 1f));
        if (!c.start.isEmpty()) {
            mid.addView(ui.text(fmtDates(c.start, c.end), 13, false, 0.8f));
        }
        top.addView(mid, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        ((LinearLayout.LayoutParams) mid.getLayoutParams()).weight = 1f;

        final ImageView star = ui.iconButton(store.isFavoriteComp(c.wcaId)
                ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.safe);
        star.setOnClickListener(v -> {
            store.toggleFavoriteComp(c.wcaId, c.name);
            star.setImageDrawable(ui.tinted(store.isFavoriteComp(c.wcaId)
                    ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.safe));
            if (filterMode == 2) renderList();
        });
        top.addView(star);
        card.addView(top);
        card.setOnClickListener(v -> openCompetition(c));
        return card;
    }

    // ---------------------------------------------------------------- recent records

    private void loadRecords() {
        if (recordsLoading) return;
        recordsLoading = true;
        recordsError = null;
        renderRecords();
        requestRecords(REC_Q1, true);
    }

    private void requestRecords(final String query, final boolean mayRetry) {
        try {
            JSONObject body = new JSONObject();
            body.put("query", query);
            WcaHttp.postJson(API, body.toString(), new WcaHttp.Callback() {
                @Override
                public void onSuccess(String text) {
                    if (!isAdded()) return;
                    try {
                        JSONObject root = new JSONObject(text);
                        JSONObject data = root.optJSONObject("data");
                        JSONArray arr = data == null ? null : data.optJSONArray("recentRecords");
                        if (arr == null) {
                            if (mayRetry) { requestRecords(REC_Q2, false); return; }
                            throw new Exception("no records field");
                        }
                        List<String[]> out = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            JSONObject res = o.optJSONObject("result");
                            JSONObject person = res == null ? null : res.optJSONObject("person");
                            JSONObject country = person == null ? null : person.optJSONObject("country");
                            JSONObject round = res == null ? null : res.optJSONObject("round");
                            JSONObject ce = round == null ? null : round.optJSONObject("competitionEvent");
                            JSONObject ev = ce == null ? null : ce.optJSONObject("event");
                            String evId = ev == null ? "" : ev.optString("id");
                            String evName = WcaFormat.eventName(evId);
                            if ((evName == null || evName.isEmpty() || evName.equals(evId)) && ev != null) {
                                evName = ev.optString("name", evId);
                            }
                            out.add(new String[]{
                                    o.optString("tag"), o.optString("type"), evId, evName,
                                    String.valueOf(o.optLong("attemptResult")),
                                    person == null ? "" : person.optString("name"),
                                    country == null ? "" : country.optString("name"),
                                    country == null ? "" : country.optString("iso2")});
                        }
                        records.clear();
                        records.addAll(out);
                        recordsLoaded = true;
                        recordsError = null;
                    } catch (Exception e) {
                        recordsError = "Couldn't read the recent records";
                    }
                    recordsLoading = false;
                    renderRecords();
                }

                @Override
                public void onError(String message) {
                    if (!isAdded()) return;
                    recordsLoading = false;
                    recordsError = WcaHttp.describe(requireContext(), message).startsWith("No internet")
                            ? "No internet connection" : "Couldn't load recent records";
                    renderRecords();
                }
            });
        } catch (Exception e) {
            recordsLoading = false;
            recordsError = "Couldn't load recent records";
            renderRecords();
        }
    }

    private void renderRecords() {
        recordsBox.removeAllViews();
        LinearLayout card = ui.card(false);
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(12));
        card.addView(ui.text("Recent records", 18, true, 0.85f), ui.matchWrap(0, 0, 0, 8));
        if (recordsLoading && records.isEmpty()) {
            card.addView(ui.text("Loading recent records...", 14, false, 0.8f));
        } else if (recordsError != null && records.isEmpty()) {
            card.addView(ui.text(recordsError + ".", 14, false, 0.8f));
        } else if (records.isEmpty()) {
            card.addView(ui.text("No recent records.", 14, false, 0.8f));
        }
        int limit = Math.min(recordsShown, records.size());
        for (int i = 0; i < limit; i++) {
            String[] r = records.get(i);
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tag = new TextView(requireContext());
            tag.setText(r[0]);
            tag.setTextSize(15);
            tag.setTextColor(android.graphics.Color.BLACK);
            tag.setTag("badge");
            tag.setTypeface(tag.getTypeface(), android.graphics.Typeface.BOLD);
            tag.setGravity(Gravity.CENTER);
            tag.setBackground(ui.rounded(WcaUi.badgeColor(r[0]), ui.dp(8)));
            row.addView(tag, ui.lp(ui.dp(52), ui.dp(40), 0, 0, 14, 0));

            LinearLayout mid = new LinearLayout(requireContext());
            mid.setOrientation(LinearLayout.VERTICAL);
            boolean avg = "average".equalsIgnoreCase(r[1]);
            String time = WcaFormat.formatResult(Long.parseLong(r[4]), r[2], avg);
            String lead = r[3] + " " + (avg ? "average" : "single") + " of ";
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(lead + time);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    lead.length(), sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView main = ui.text("", 15, false, 1f);
            main.setText(sb);
            mid.addView(main);
            String flag = WcaFlags.emoji(r[7]);
            String who = (flag.isEmpty() ? "" : flag + " ") + r[5] + (r[6].isEmpty() ? "" : " from " + r[6]);
            mid.addView(ui.text(who, 14, false, 0.8f));
            row.addView(mid, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
            ((LinearLayout.LayoutParams) mid.getLayoutParams()).weight = 1f;
            card.addView(row, ui.matchWrap(0, 8, 0, 8));
        }
        if (limit < records.size()) {
            TextView more = ui.pill("Show more", false);
            more.setOnClickListener(v -> {
                recordsShown += 10;
                renderRecords();
            });
            card.addView(more, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 4));
        }
        recordsBox.addView(card, ui.matchWrap(0, 0, 0, 0));
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
        ImageView refresh = ui.iconButton(R.drawable.ic_wca_refresh, 24, ui.text);
        refresh.setContentDescription("Refresh");
        refresh.setOnClickListener(v -> {
            if (compMode && compPage != null) compPage.reload();
            else fetchResults();
        });
        header.addView(refresh);
        page.addView(header, ui.matchWrap(0, 0, 0, 6));

        LinearLayout modeRow = new LinearLayout(requireContext());
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeResults = ui.pill("Live results", true);
        modeResults.setOnClickListener(v -> setMode(false));
        modeComp = ui.pill("Competition", false);
        modeComp.setOnClickListener(v -> setMode(true));
        modeRow.addView(modeResults, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        modeRow.addView(modeComp);
        page.addView(modeRow, ui.matchWrap(0, 0, 0, 6));

        detailStatus = ui.status(13);
        page.addView(detailStatus, ui.matchWrap(4, 0, 4, 6));

        eventRow = new LinearLayout(requireContext());
        eventRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView evScroll = new HorizontalScrollView(requireContext());
        evScroll.setHorizontalScrollBarEnabled(false);
        evScroll.addView(eventRow);
        evScrollView = evScroll;
        page.addView(evScroll, ui.matchWrap(0, 0, 0, 6));

        roundRow = new LinearLayout(requireContext());
        roundRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView rdScroll = new HorizontalScrollView(requireContext());
        rdScroll.setHorizontalScrollBarEnabled(false);
        rdScroll.addView(roundRow);
        rdScrollView = rdScroll;
        page.addView(rdScroll, ui.matchWrap(0, 0, 0, 6));

        LinearLayout opts = new LinearLayout(requireContext());
        opts.setOrientation(LinearLayout.HORIZONTAL);
        sortPill = ui.pill("Sort: rank", false);
        sortPill.setOnClickListener(v -> {
            sortByName = !sortByName;
            sortPill.setText(sortByName ? "Sort: name" : "Sort: rank");
            renderResults();
        });
        opts.addView(sortPill);
        optsRow = opts;
        page.addView(opts, ui.matchWrap(0, 0, 0, 8));

        resultsScroll = new ScrollView(requireContext());
        resultsContainer = new LinearLayout(requireContext());
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        resultsScroll.addView(resultsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(resultsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        compScroll = new ScrollView(requireContext());
        compScroll.setVisibility(View.GONE);
        page.addView(compScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    /** Switches the competition screen between the live results and the competition page. */
    private void setMode(boolean comp) {
        compMode = comp;
        ui.stylePill(modeResults, !comp);
        ui.stylePill(modeComp, comp);
        int liveVis = comp ? View.GONE : View.VISIBLE;
        evScrollView.setVisibility(liveVis);
        rdScrollView.setVisibility(liveVis);
        optsRow.setVisibility(liveVis);
        resultsScroll.setVisibility(liveVis);
        detailStatus.setVisibility(comp || detailStatus.getText().length() == 0 ? View.GONE : View.VISIBLE);
        compScroll.setVisibility(comp ? View.VISIBLE : View.GONE);
        if (comp && currentComp != null) {
            if (compPage == null || !currentComp.wcaId.equals(compPageFor)) {
                compPageFor = currentComp.wcaId;
                compPage = new WcaCompPage(requireContext(), currentComp.wcaId, currentComp.name,
                        id -> openInResults(id), null);
                compScroll.removeAllViews();
                compScroll.addView(compPage.view(), new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
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
        compPage = null;
        compPageFor = "";
        compScroll.removeAllViews();
        setMode(false);
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
                            row.advancing = r.optBoolean("advancing", false);
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
            Collections.sort(ev.rounds, new Comparator<Round>() {
                @Override
                public int compare(Round a, Round b) {
                    return a.number - b.number;
                }
            });
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
                String label = "Round " + (j + 1) + (rd.rows.isEmpty() ? " (no results)" : "");
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
        boolean finalRound = roundIndex == ev.rounds.size() - 1;
        curEv = ev;
        curRd = ev.rounds.get(roundIndex);
        for (Row r : rows) {
            Person p = ppl.get(r.personId);
            String name = p == null ? "Competitor " + r.personId : p.name;
            String key = (p != null && !p.wcaId.isEmpty()) ? p.wcaId : name;
            boolean mine = store.isPinned(key);
            boolean fav = store.isFavoritePerson(key);
            boolean green = r.advancing || (finalRound && r.ranking >= 1 && r.ranking <= 3 && r.best > 0);
            resultsContainer.addView(buildRow(r, p, name, key, ev.id, hasAverage, mine || fav, green),
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
                          boolean hasAverage, boolean highlighted, boolean green) {
        LinearLayout card = ui.card(highlighted);
        LinearLayout line = new LinearLayout(requireContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView rank = ui.text(r.ranking > 0 ? String.valueOf(r.ranking) : "-", 16, true, 1f);
        rank.setMinWidth(ui.dp(32));
        rank.setGravity(Gravity.CENTER);
        if (green) {
            rank.setBackground(ui.rounded(0xFF00E676, ui.dp(6)));
            rank.setTextColor(android.graphics.Color.BLACK);
            rank.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4));
        }
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
        final Ev evNow = curEv;
        final Round rdNow = curRd;
        right.setOnClickListener(v -> showAttemptsDialog(p, name, evNow, rdNow, r));
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
            toggleStar(p, name, key);
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
        final android.widget.FrameLayout avatar = ui.avatar(name, "", 40);
        head.addView(avatar, ui.lp(ui.dp(40), ui.dp(40), 0, 0, 12, 0));
        if (p != null && !p.wcaId.isEmpty()) {
            WcaNames.fetchAvatar(p.wcaId, url -> {
                if (isAdded()) ui.setAvatarUrl(avatar, url);
            });
        }
        TextView title = ui.text((flag.isEmpty() ? "" : flag + " ") + name, 18, true, 1f);
        head.addView(title, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;
        if (p != null && !p.wcaId.isEmpty()) {
            final String openId = p.wcaId;
            title.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                openInResults(openId);
            });
        }
        final ImageView star = ui.iconButton(isStarred(p, key)
                ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.safe);
        star.setContentDescription("Favorite");
        star.setOnClickListener(v -> {
            boolean now = toggleStar(p, name, key);
            star.setImageDrawable(ui.tinted(now
                    ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.safe));
            renderResults();
        });
        head.addView(star);
        ImageView close = ui.iconButton(R.drawable.ic_wca_close, 24, ui.safe);
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
            LinearLayout table = null;
            for (int ri = 0; ri < ev.rounds.size(); ri++) {
                Round rd = ev.rounds.get(ri);
                for (Row row : rd.rows) {
                    if (row.personId != personId) continue;
                    any = true;
                    if (table == null) {
                        TextView evTitle = ui.text(WcaFormat.eventName(ev.id), 16, true, 1f);
                        ui.setEventIcon(evTitle, ev.id, 20, ui.text);
                        box.addView(evTitle, ui.matchWrap(0, 12, 0, 6));
                        table = new LinearLayout(requireContext());
                        table.setOrientation(LinearLayout.VERTICAL);
                        table.setBackground(ui.rounded(
                                androidx.core.graphics.ColorUtils.blendARGB(ui.itemBg, ui.itemText, 0.06f), ui.dp(8)));
                        table.addView(personRow(null, "#", "Round", "Average", "Best", null, null, false, true));
                        box.addView(table);
                    }
                    boolean isFinal = ri == ev.rounds.size() - 1;
                    boolean green = row.advancing
                            || (isFinal && row.ranking >= 1 && row.ranking <= 3 && row.best > 0);
                    String avg = "333mbf".equals(ev.id) ? "" : WcaFormat.formatResult(row.average, ev.id, true);
                    String best = WcaFormat.formatResult(row.best, ev.id, false);
                    View pr = personRow(null, row.ranking > 0 ? String.valueOf(row.ranking) : "-",
                            roundLabel(ev, rd), avg, best, row.avgTag, row.singleTag, green, false);
                    final Ev evF = ev;
                    final Round rdF = rd;
                    final Row rowF = row;
                    pr.setOnClickListener(v -> showAttemptsDialog(p, name, evF, rdF, rowF));
                    table.addView(pr);
                }
            }
        }
        if (!any) box.addView(ui.text("No results yet.", 14, false, 0.85f));

        android.app.Dialog d = ui.dialog(root);
        holder[0] = d;
        d.show();
    }

    /** Named from the round's place in the event's list, so the numbering always follows the order. */
    private static String roundLabel(Ev ev, Round rd) {
        int total = ev.rounds.size();
        int n = ev.rounds.indexOf(rd) + 1;
        if (n <= 0) n = rd.number;
        if (n == total) return "Final";
        if (total >= 3 && n == total - 1) return "Semi Final";
        String[] names = {"First Round", "Second Round", "Third Round", "Fourth Round"};
        return n >= 1 && n <= names.length ? names[n - 1] : "Round " + n;
    }

    /** One line of the competitor pop-up table: rank (green when advancing), round, average, best. */
    private View personRow(Object unused, String rank, String round, String avg, String best,
                           String avgTag, String bestTag, boolean green, boolean header) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(2), ui.dp(8), ui.dp(2));
        TextView rk = ui.text(rank, 14, header, 1f);
        rk.setGravity(Gravity.CENTER);
        if (green) {
            rk.setBackground(ui.rounded(0xFF00E676, ui.dp(6)));
            rk.setTextColor(android.graphics.Color.BLACK);
        }
        row.addView(rk, ui.lp(ui.dp(36), ui.dp(34), 6, 0, 6, 0));
        TextView rd = ui.text(round, 13, header, 1f);
        row.addView(rd, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) rd.getLayoutParams()).weight = 1f;
        row.addView(cellOf(avg, avgTag, header, true), ui.lp(ui.dp(100), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        row.addView(cellOf(best, bestTag, header, false), ui.lp(ui.dp(100), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        return row;
    }

    private View cellOf(String value, String tag, boolean header, boolean bold) {
        if (header) {
            TextView t = ui.text(value, 14, true, 1f);
            t.setGravity(Gravity.END);
            t.setPadding(0, 0, ui.dp(28), 0);
            return t;
        }
        if (value.isEmpty()) return new View(requireContext());
        return ui.resultCell("", value, tag, 14, bold);
    }

    /** The solves behind one result; on a five-solve average the best and worst are in brackets. */
    private static String[] attemptStrings(long[] v, String ev) {
        int n = v.length;
        String[] out = new String[n];
        boolean allSet = n > 0;
        for (long x : v) if (x == 0) allSet = false;
        int bi = -1, wi = -1;
        if (n == 5 && allSet && !"333mbf".equals(ev)) {
            for (int i = 0; i < n; i++) if (v[i] > 0 && (bi < 0 || v[i] < v[bi])) bi = i;
            for (int i = n - 1; i >= 0; i--) {
                if (i == bi) continue;
                if (wi < 0) { wi = i; continue; }
                boolean iBad = v[i] < 0, wBad = v[wi] < 0;
                if (iBad && !wBad) wi = i;
                else if (iBad == wBad && !iBad && v[i] > v[wi]) wi = i;
            }
        }
        for (int i = 0; i < n; i++) {
            String t = WcaFormat.formatResult(v[i], ev, false);
            out[i] = t.isEmpty() ? "" : (i == bi || i == wi ? "(" + t + ")" : t);
        }
        return out;
    }

    /** A pop-up with the average, the best and every solve of one result. */
    private void showAttemptsDialog(Person p, String name, Ev ev, Round rd, Row row) {
        if (ev == null || rd == null) return;
        final android.app.Dialog[] holder = new android.app.Dialog[1];
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(20), ui.dp(10), ui.dp(8), ui.dp(2));
        String flag = p == null ? "" : WcaFlags.emoji(p.iso);
        TextView title = ui.text((flag.isEmpty() ? "" : flag + " ") + name, 18, true, 1f);
        head.addView(title, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;
        final String pastId = p != null ? p.wcaId : "";
        if (!pastId.isEmpty()) {
            title.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                openInResults(pastId);
            });
        }
        ImageView close = ui.iconButton(R.drawable.ic_wca_close, 24, ui.safe);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        head.addView(close);
        root.addView(head);

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(18));
        TextView sub = ui.text(WcaFormat.eventName(ev.id) + "  \u00B7  " + roundLabel(ev, rd), 14, false, 0.85f);
        ui.setEventIcon(sub, ev.id, 18, ui.text);
        box.addView(sub, ui.matchWrap(0, 0, 0, 10));

        boolean mbf = "333mbf".equals(ev.id);
        String avg = mbf ? "" : WcaFormat.formatResult(row.average, ev.id, true);
        String best = WcaFormat.formatResult(row.best, ev.id, false);
        if (!avg.isEmpty()) {
            box.addView(ui.text("Average", 13, false, 0.7f));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            box.addView(ui.resultCell("", avg, row.avgTag, 26, true), alp);
        }
        if (!best.isEmpty()) {
            box.addView(ui.text("Best", 13, false, 0.7f), ui.matchWrap(0, 6, 0, 0));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            box.addView(ui.resultCell("", best, row.singleTag, 20, false), blp);
        }
        box.addView(ui.text("Solves", 13, false, 0.7f), ui.matchWrap(0, 12, 0, 4));
        String[] at = attemptStrings(row.attempts, ev.id);
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ui.rounded(
                androidx.core.graphics.ColorUtils.blendARGB(ui.itemBg, ui.itemText, 0.06f), ui.dp(8)));
        boolean any = false;
        for (int i = 0; i < at.length; i++) {
            if (at[i].isEmpty()) continue;
            any = true;
            LinearLayout line = new LinearLayout(requireContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
            line.addView(ui.text(String.valueOf(i + 1), 14, false, 0.6f),
                    ui.lp(ui.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
            line.addView(ui.text(at[i], 18, true, 1f));
            list.addView(line);
        }
        if (!any) list.addView(ui.text("No solves recorded yet.", 14, false, 0.8f), ui.matchWrap(14, 10, 14, 10));
        box.addView(list);
        if (!pastId.isEmpty()) {
            TextView past = ui.pill("Previous results", false);
            past.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                openInResults(pastId);
            });
            box.addView(past, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        }
        root.addView(box);

        android.app.Dialog d = ui.dialog(root);
        holder[0] = d;
        d.show();
    }

    private boolean isStarred(Person p, String key) {
        String wcaId = p != null ? p.wcaId : "";
        return store.isFavoritePerson(key) || (!wcaId.isEmpty() && store.isPinned(wcaId));
    }

    /** Stars or unstars a competitor here and in WCA Results at the same time. */
    private boolean toggleStar(Person p, String name, String key) {
        String wcaId = p != null ? p.wcaId : "";
        boolean now = !isStarred(p, key);
        if (store.isFavoritePerson(key) != now) store.toggleFavoritePerson(key);
        if (!wcaId.isEmpty() && store.isPinned(wcaId) != now) store.togglePinned(wcaId, name);
        return now;
    }

    /** Switches to the WCA Results tab and opens this competitor there. */
    private void openInResults(String wcaId) {
        store.setPendingPerson(wcaId);
        View pager = requireActivity().findViewById(R.id.pager);
        if (pager instanceof androidx.viewpager.widget.ViewPager) {
            ((androidx.viewpager.widget.ViewPager) pager).setCurrentItem(TimerFragmentMain.RESULTS_PAGE, true);
        } else {
            store.takePendingPerson();
        }
    }
}
