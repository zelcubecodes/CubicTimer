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
import java.util.Arrays;
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
    private LinearLayout suggestBox;
    private Runnable suggestRunnable = () -> { };
    private int suggestToken = 0;
    private boolean quietInput = false;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
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
    private TextView footer;
    private String lastShownId = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ui = new WcaUi(requireContext());
        store = new WcaStore(requireContext());
        WcaNames.init(requireContext());

        scroll = new ScrollView(requireContext());
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(ui.dp(12), ui.dp(80), ui.dp(12), ui.dp(64));

        LinearLayout page = new LinearLayout(requireContext());
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = ui.input("Search");
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

        suggestBox = new LinearLayout(requireContext());
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        suggestBox.setBackground(ui.rounded(ui.itemBg, ui.dp(8)));
        suggestBox.setVisibility(View.GONE);
        page.addView(suggestBox, ui.matchWrap(0, 0, 0, 8));
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (quietInput) return;
                final String q = e.toString().trim();
                uiHandler.removeCallbacks(suggestRunnable);
                if (q.length() < 2) {
                    suggestToken++;
                    suggestBox.removeAllViews();
                    suggestBox.setVisibility(View.GONE);
                    return;
                }
                suggestRunnable = () -> runSuggest(q);
                uiHandler.postDelayed(suggestRunnable, 350);
            }
        });

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
        footer = ui.status(12);
        page.addView(footer, ui.matchWrap(4, 10, 4, 4));

        status.setText("Search a name, WCA ID or competition. Star a profile to keep it here.");
        checkPendingPerson();
        return scroll;
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser && input != null) {
            renderPinned();
            checkPendingPerson();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from Rankings or Records, or after starring someone in WCA Live.
        if (input != null && getUserVisibleHint()) {
            renderPinned();
            checkPendingPerson();
        }
    }

    /** Opens the competitor another tab asked for (for example from the WCA Live pop-up). */
    private void checkPendingPerson() {
        String id = store.takePendingPerson();
        if (id != null) {
            setQuery(id);
            loadPerson(id);
        }
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
                setQuery(p[0]);
                loadPerson(p[0]);
            });
            // A starred person saved with only an ID gets their name filled in.
            if (p[1].isEmpty() || p[1].equals(p[0])) {
                final TextView chipF = chip;
                WcaNames.lookup(p[0], (wcaId, nm, iso) -> {
                    if (isAdded()) chipF.setText(nm + " (" + wcaId + ")");
                });
            }
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

    private void hideSuggestions() {
        uiHandler.removeCallbacks(suggestRunnable);
        suggestToken++;
        if (suggestBox != null) {
            suggestBox.removeAllViews();
            suggestBox.setVisibility(View.GONE);
        }
    }

    /** Puts text in the search box without opening the drop-down. */
    private void setQuery(String text) {
        quietInput = true;
        input.setText(text);
        quietInput = false;
        hideSuggestions();
    }

    // ---------------------------------------------------------------- search (competitors + competitions)

    private static final Pattern COMP_ID = Pattern.compile("^[A-Za-z0-9]+20\\d\\d$");

    private static class Hit {
        String id = "", title = "", sub = "", avatar = "", iso = "";
    }

    private interface HitsDone {
        void done(List<Hit> people, List<Hit> comps, boolean failed);
    }

    private void parseComps(String text, List<Hit> out) throws Exception {
        String t = text.trim();
        JSONArray arr;
        if (t.startsWith("[")) {
            arr = new JSONArray(t);
        } else {
            arr = new JSONObject(t).optJSONArray("result");
        }
        if (arr == null) return;
        for (int i = 0; i < arr.length() && out.size() < 25; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Hit h = new Hit();
            h.id = o.optString("id");
            h.title = o.optString("name");
            if (h.id.isEmpty() || h.title.isEmpty()) continue;
            h.iso = WcaFlags.code(o.optString("country_iso2"));
            String city = o.optString("city");
            String when = WcaCompPage.dates(o.optString("start_date"), o.optString("end_date"));
            h.sub = when + (city.isEmpty() ? "" : "  \u00B7  " + city)
                    + (h.iso.isEmpty() ? "" : ", " + WcaCompPage.countryName(h.iso));
            out.add(h);
        }
    }

    private void fetchComps(final String q, final List<Hit> out, final boolean fallback,
                            final Runnable ok, final Runnable fail) {
        String url = fallback ? WCA + "/competitions?q=" + Uri.encode(q) + "&sort=-start_date"
                : WCA + "/search/competitions?q=" + Uri.encode(q);
        WcaHttp.get(url, new WcaHttp.Callback() {
            @Override
            public void onSuccess(String text) {
                try {
                    parseComps(text, out);
                } catch (Exception e) {
                    if (!fallback) {
                        fetchComps(q, out, true, ok, fail);
                        return;
                    }
                }
                ok.run();
            }

            @Override
            public void onError(String message) {
                if (!fallback) fetchComps(q, out, true, ok, fail);
                else fail.run();
            }
        });
    }

    /** Looks up competitors and competitions at the same time, like the search on the WCA website. */
    private void fetchHits(final String q, final HitsDone done) {
        final List<Hit> people = new ArrayList<>();
        final List<Hit> comps = new ArrayList<>();
        final int[] left = {2};
        final int[] failed = {0};
        final Runnable finish = () -> {
            left[0]--;
            if (left[0] == 0) done.done(people, comps, failed[0] == 2);
        };
        WcaHttp.get(WCA + "/search/users?persons_table=true&q=" + Uri.encode(q), new WcaHttp.Callback() {
            @Override
            public void onSuccess(String text) {
                try {
                    JSONArray arr = new JSONObject(text).optJSONArray("result");
                    if (arr != null) {
                        for (int i = 0; i < arr.length() && people.size() < 25; i++) {
                            JSONObject o = arr.getJSONObject(i);
                            Hit h = new Hit();
                            h.id = o.optString("wca_id");
                            if (h.id.isEmpty() || "null".equals(h.id)) continue;
                            h.title = o.optString("name");
                            h.iso = WcaFlags.code(o.optString("country_iso2"));
                            h.sub = h.id;
                            h.avatar = WcaUi.avatarUrl(o, false);
                            people.add(h);
                        }
                    }
                } catch (Exception e) {
                    failed[0]++;
                }
                finish.run();
            }

            @Override
            public void onError(String message) {
                failed[0]++;
                finish.run();
            }
        });
        fetchComps(q, comps, false, finish, () -> {
            failed[0]++;
            finish.run();
        });
    }

    private View hitRow(final Hit h, final boolean comp, boolean inCard) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (!inCard) row.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        if (!comp) {
            row.addView(ui.avatar(h.title, h.avatar, 40), ui.lp(ui.dp(40), ui.dp(40), 0, 0, 12, 0));
        }
        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        String flag = WcaFlags.emoji(h.iso);
        col.addView(ui.text((flag.isEmpty() ? "" : flag + " ") + h.title, 15, true, 1f));
        if (!h.sub.isEmpty()) col.addView(ui.text(h.sub, 12, false, 0.75f));
        row.addView(col, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) col.getLayoutParams()).weight = 1f;
        row.setOnClickListener(v -> {
            hideKeyboard();
            hideSuggestions();
            if (comp) {
                setQuery("");
                showCompetitionPage(h.id, h.title);
            } else {
                setQuery(h.id);
                loadPerson(h.id);
            }
        });
        return row;
    }

    private void addSection(LinearLayout into, String title, List<Hit> hits, int limit,
                            boolean comp, boolean cards) {
        if (hits.isEmpty()) return;
        TextView label = ui.text(title, 12, true, 0.7f);
        if (cards) {
            into.addView(label, ui.matchWrap(4, 10, 4, 4));
        } else {
            label.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(2));
            into.addView(label);
        }
        for (int i = 0; i < hits.size() && i < limit; i++) {
            View r = hitRow(hits.get(i), comp, cards);
            if (cards) {
                LinearLayout card = ui.card(false);
                card.addView(r);
                into.addView(card, ui.matchWrap(0, 3, 0, 3));
            } else {
                into.addView(r);
            }
        }
    }

    private void hideSuggestionsOnly() {
        suggestBox.removeAllViews();
        suggestBox.setVisibility(View.GONE);
    }

    /** Drop-down under the search box: matching competitors and competitions. */
    private void runSuggest(final String q) {
        if (q.length() < 2 || !isAdded()) return;
        final int tokenNow = ++suggestToken;
        suggestBox.removeAllViews();
        suggestBox.setVisibility(View.VISIBLE);
        suggestBox.addView(ui.text("Searching...", 14, false, 0.8f), ui.matchWrap(14, 12, 14, 12));
        ui.recolor(suggestBox, ui.text, ui.safe);
        fetchHits(q, (people, comps, failed) -> {
            if (!isAdded() || tokenNow != suggestToken) return;
            suggestBox.removeAllViews();
            if (failed) {
                suggestBox.addView(ui.text("Could not reach the WCA website.", 14, false, 0.8f),
                        ui.matchWrap(14, 12, 14, 12));
            } else if (people.isEmpty() && comps.isEmpty()) {
                suggestBox.addView(ui.text("Nothing found.", 14, false, 0.8f),
                        ui.matchWrap(14, 12, 14, 12));
            } else {
                addSection(suggestBox, "COMPETITORS", people, 5, false, false);
                addSection(suggestBox, "COMPETITIONS", comps, 5, true, false);
                suggestBox.addView(new View(requireContext()), ui.matchWrap(0, 0, 0, 6));
            }
            ui.recolor(suggestBox, ui.text, ui.safe);
        });
    }

    private void search() {
        hideKeyboard();
        hideSuggestions();
        String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            status.setText("Type a name, WCA ID or competition.");
            return;
        }
        if (WCA_ID.matcher(q).matches()) {
            loadPerson(q.toUpperCase(Locale.US));
        } else if (COMP_ID.matcher(q).matches()) {
            showCompetitionPage(q, q);
        } else {
            searchAll(q);
        }
    }

    private void searchAll(String q) {
        status.setText("Searching...");
        footer.setText("");
        body.removeAllViews();
        openId = null;
        fetchHits(q, (people, comps, failed) -> {
            if (!isAdded()) return;
            body.removeAllViews();
            if (failed) {
                status.setText("Can't reach WCA. Check your connection.");
                return;
            }
            addSection(body, "COMPETITORS", people, 25, false, true);
            addSection(body, "COMPETITIONS", comps, 25, true, true);
            status.setText(people.isEmpty() && comps.isEmpty()
                    ? "Nothing found."
                    : "Tap a result to open it.");
        });
    }

    // ---------------------------------------------------------------- profile

    private void loadPerson(final String wcaId) {
        status.setText("Loading " + wcaId + "...");
        footer.setText("");
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
                    ? " WCA refused the request."
                    : (why.startsWith("No internet") ? " Connect to open " + wcaId + "."
                    : " Try again.");
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

            // Header card: picture, name, country, WCA ID, and a row of numbers
            LinearLayout head = ui.card(false);
            final String pinId = id;
            final String pinName = name;
            LinearLayout top = new LinearLayout(requireContext());
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            final String bigUrl = WcaUi.avatarUrl(person, true);
            android.widget.FrameLayout pic = ui.avatar(name.isEmpty() ? id : name, bigUrl, 76);
            ui.makeZoomable(pic, bigUrl);
            top.addView(pic, ui.lp(ui.dp(76), ui.dp(76), 0, 0, 14, 0));
            LinearLayout info = new LinearLayout(requireContext());
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(ui.text(name.isEmpty() ? id : name, 20, true, 1f));
            String countryName = WcaCompPage.countryName(WcaFlags.code(country));
            if (!countryName.isEmpty()) {
                info.addView(ui.text((flag.isEmpty() ? "" : flag + " ") + countryName, 14, false, 0.9f),
                        ui.matchWrap(0, 2, 0, 0));
            }
            info.addView(ui.text(id, 13, false, 0.7f), ui.matchWrap(0, 2, 0, 0));
            top.addView(info, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0));
            ((LinearLayout.LayoutParams) info.getLayoutParams()).weight = 1f;

            LinearLayout actions = new LinearLayout(requireContext());
            actions.setOrientation(LinearLayout.VERTICAL);
            final android.widget.ImageView star = ui.iconButton(store.isPinned(pinId)
                    ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.safe);
            star.setContentDescription("Star this competitor");
            star.setOnClickListener(v -> {
                boolean now = store.togglePinned(pinId, pinName);
                if (store.isFavoritePerson(pinId) != now) store.toggleFavoritePerson(pinId);
                star.setImageDrawable(ui.tinted(now ? R.drawable.ic_wca_star_filled
                        : R.drawable.ic_wca_star_outline, 24, ui.safe));
                renderPinned();
            });
            actions.addView(star);
            android.widget.ImageView refreshIcon = ui.iconButton(R.drawable.ic_wca_refresh, 24, ui.safe);
            refreshIcon.setContentDescription("Refresh");
            refreshIcon.setOnClickListener(v -> loadPerson(pinId));
            actions.addView(refreshIcon);
            top.addView(actions);
            head.addView(top);

            // Row 1 always has two equal boxes; row 2 (medals, records) keeps the same box size even when only one exists.
            LinearLayout stats = new LinearLayout(requireContext());
            stats.setOrientation(LinearLayout.HORIZONTAL);
            int comps = root.optInt("competition_count", -1);
            stats.addView(statBox(comps >= 0 ? String.valueOf(comps) : "-", "competitions", ""), statLp());
            stats.addView(statBox(String.valueOf(completedSolves(resultsJson)), "completed solves", ""), statLp());
            head.addView(stats, ui.matchWrap(0, 14, 0, 0));

            JSONObject medals = root.optJSONObject("medals");
            JSONObject recs = root.optJSONObject("records");
            boolean hasMedals = medals != null && medals.optInt("total", 0) > 0;
            boolean hasRecs = recs != null && recs.optInt("total", 0) > 0;
            if (hasMedals || hasRecs) {
                LinearLayout row2 = new LinearLayout(requireContext());
                row2.setOrientation(LinearLayout.HORIZONTAL);
                if (hasMedals) {
                    LinearLayout m = new LinearLayout(requireContext());
                    m.setOrientation(LinearLayout.HORIZONTAL);
                    m.setGravity(Gravity.CENTER);
                    m.addView(ui.medalCount(1, medals.optInt("gold"), 15));
                    m.addView(ui.medalCount(2, medals.optInt("silver"), 15));
                    m.addView(ui.medalCount(3, medals.optInt("bronze"), 15));
                    row2.addView(symbolBox(m, "medals"), statLp());
                } else {
                    row2.addView(new View(requireContext()), statLp());
                }
                if (hasRecs) {
                    LinearLayout r = new LinearLayout(requireContext());
                    r.setOrientation(LinearLayout.HORIZONTAL);
                    r.setGravity(Gravity.CENTER);
                    if (recs.optInt("world") > 0) r.addView(ui.recordCount("WR", recs.optInt("world"), 15));
                    if (recs.optInt("continental") > 0) r.addView(ui.recordCount("CR", recs.optInt("continental"), 15));
                    if (recs.optInt("national") > 0) r.addView(ui.recordCount("NR", recs.optInt("national"), 15));
                    row2.addView(symbolBox(r, "records"), statLp());
                } else {
                    row2.addView(new View(requireContext()), statLp());
                }
                head.addView(row2, ui.matchWrap(0, 6, 0, 0));
            }
            body.addView(head, ui.matchWrap(0, 4, 0, 4));

            if (fromCache) {
                status.setText("");
                footer.setText("Saved copy \u00B7 " + dateTime(dataTime));
            } else {
                status.setText("");
                footer.setText("Live from WCA \u00B7 " + dateTime(dataTime));
            }

            addPersonalRecords(root.optJSONObject("personal_records"));
            JSONArray allResults = new JSONArray(resultsJson);
            addRecordsList(allResults);
            addChampionshipPodiums(allResults, country);
            addHistory(allResults);
        } catch (Exception e) {
            e.printStackTrace();
            status.setText("Couldn't read data for " + openId + ".");
        }
    }

    /** Number of solves that were not DNF/DNS, from the person's results. */
    private static int completedSolves(String resultsJson) {
        int n = 0;
        try {
            JSONArray arr = new JSONArray(resultsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray at = arr.getJSONObject(i).optJSONArray("attempts");
                if (at == null) continue;
                for (int k = 0; k < at.length(); k++) if (at.optLong(k) > 0) n++;
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** A stat box that holds symbols (medals, record badges) with a small label under them. */
    private View symbolBox(View symbols, String label) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10));
        box.setBackground(ui.rounded(androidx.core.graphics.ColorUtils.blendARGB(ui.itemBg, ui.safe, 0.08f), ui.dp(10)));
        box.addView(symbols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(30)));
        TextView l = ui.text(label, 11, false, 0.75f);
        l.setTextColor(ui.safe);
        l.setAlpha(0.75f);
        box.addView(l);
        return box;
    }

    private LinearLayout.LayoutParams statLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(ui.dp(3), 0, ui.dp(3), 0);
        return lp;
    }

    private View statBox(String value, String label, String sub) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10));
        box.setBackground(ui.rounded(androidx.core.graphics.ColorUtils.blendARGB(ui.itemBg, ui.safe, 0.08f), ui.dp(10)));
        TextView v = ui.text(value, 20, true, 1f);
        v.setTextColor(ui.safe);
        box.addView(v);
        TextView l = ui.text(label, 11, false, 0.75f);
        l.setTextColor(ui.safe);
        l.setAlpha(0.75f);
        box.addView(l);
        if (!sub.isEmpty()) {
            TextView sb = ui.text(sub, 10, false, 0.6f);
            sb.setTextColor(ui.safe);
            sb.setAlpha(0.6f);
            sb.setGravity(Gravity.CENTER);
            box.addView(sb);
        }
        return box;
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
            ui.setEventIcon(title, ev, 20, ui.safe);
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

    // ---------------------------------------------------------------- records and championship podiums

    private String compName(String cid) {
        return compInfo != null && compInfo.containsKey(cid) ? compInfo.get(cid)[0] : cid;
    }

    private String compDate(String cid) {
        return compInfo != null && compInfo.containsKey(cid) ? compInfo.get(cid)[1] : "";
    }

    private TextView compLink(final String cid, int widthDp) {
        final String cname = compName(cid);
        TextView link = cell(cname, widthDp, false);
        link.setTextColor(0xFFE8531F);
        link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        link.setOnClickListener(v -> showCompetitionPage(cid, cname));
        return link;
    }

    private View iconBox(android.graphics.drawable.Drawable d, int widthDp) {
        android.widget.ImageView iv = new android.widget.ImageView(requireContext());
        iv.setImageDrawable(d);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(widthDp), ui.dp(26)));
        return iv;
    }

    /** Every result that set a world, continental or national record (rows tagged WR, CR, NR...). */
    private boolean showRecordsTable = false, showPodiumsTable = false;

    /** A heading that shows or hides its table when tapped. */
    private void addToggleSection(String title, final View content, final boolean podiums) {
        final TextView head = ui.label("", 18, true);
        final String t = title;
        final Runnable apply = () -> {
            boolean on = podiums ? showPodiumsTable : showRecordsTable;
            head.setText(t + (on ? "  \u25B4" : "  \u25BE"));
            content.setVisibility(on ? View.VISIBLE : View.GONE);
        };
        head.setOnClickListener(v -> {
            if (podiums) showPodiumsTable = !showPodiumsTable; else showRecordsTable = !showRecordsTable;
            apply.run();
        });
        apply.run();
        body.addView(head, ui.matchWrap(0, 12, 0, 6));
        body.addView(content, ui.matchWrap(0, 0, 0, 0));
    }

    private void addRecordsList(JSONArray results) {
        List<JSONObject> rows = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        List<Boolean> isAvg = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String st = regionTag(r, "regional_single_record");
            String at = regionTag(r, "regional_average_record");
            if (!st.isEmpty()) { rows.add(r); tags.add(st); isAvg.add(false); }
            if (!at.isEmpty()) { rows.add(r); tags.add(at); isAvg.add(true); }
        }
        if (rows.isEmpty()) return;
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final List<JSONObject> fr = rows;
        Arrays.sort(order, (x, y) -> {
            int c = compDate(fr.get(y).optString("competition_id")).compareTo(compDate(fr.get(x).optString("competition_id")));
            if (c != 0) return c;
            return WcaFormat.eventOrder(fr.get(x).optString("event_id")) - WcaFormat.eventOrder(fr.get(y).optString("event_id"));
        });
        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = ui.card(false);
        card.setPadding(0, 0, 0, 0);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(requireContext());
        LinearLayout table = new LinearLayout(requireContext());
        table.setOrientation(LinearLayout.VERTICAL);
        hs.addView(table);
        LinearLayout header = tableRow(0);
        header.addView(cell("", 52, true));
        header.addView(cell("Event", 44, true));
        header.addView(cell("Type", 76, true));
        header.addView(cell("Result", 96, true));
        header.addView(cell("Competition", 220, true));
        table.addView(header);
        int shownRows = Math.min(order.length, 30);
        for (int k = 0; k < shownRows; k++) {
            int i = order[k];
            JSONObject r = rows.get(i);
            String ev = r.optString("event_id");
            boolean avg = isAvg.get(i);
            LinearLayout line = tableRow(k % 2 == 0 ? 2 : 3);
            LinearLayout chip = new LinearLayout(requireContext());
            chip.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            chip.addView(ui.badge(tags.get(i)));
            line.addView(chip);
            line.addView(iconBox(ui.eventIcon(ev, 22, ui.safe), 44));
            line.addView(cell(avg ? "Average" : "Single", 76, false));
            line.addView(cell(WcaFormat.formatResult(r.optLong(avg ? "average" : "best"), ev, avg), 96, true));
            line.addView(compLink(r.optString("competition_id"), 220));
            table.addView(line);
        }
        card.addView(hs);
        wrap.addView(card, ui.matchWrap(0, 4, 0, 4));
        if (order.length > shownRows) {
            wrap.addView(ui.text("+" + (order.length - shownRows) + " older", 12, false, 0.7f),
                    ui.matchWrap(4, 2, 4, 4));
        }
        addToggleSection("Records", wrap, false);
    }

    private static final String[][] DEMONYMS = {
            {"PH", "philippine", "philippines", "filipino"}, {"ID", "indonesia", "indonesian"},
            {"MY", "malaysia", "malaysian"}, {"SG", "singapore"}, {"TH", "thai", "thailand"},
            {"VN", "vietnam", "vietnamese"}, {"JP", "japan", "japanese"}, {"KR", "korea", "korean"},
            {"IN", "india", "indian"}, {"AU", "australia", "australian"}, {"NZ", "new zealand"},
            {"US", "usa", "united states", "american"}, {"CA", "canada", "canadian"},
            {"MX", "mexico", "mexican"}, {"BR", "brazil", "brazilian"}, {"AR", "argentina", "argentine"},
            {"CL", "chile", "chilean"}, {"CO", "colombia", "colombian"}, {"PE", "peru", "peruvian"},
            {"GB", "uk", "united kingdom", "british"}, {"IE", "ireland", "irish"}, {"FR", "france", "french"},
            {"DE", "germany", "german"}, {"ES", "spain", "spanish"}, {"IT", "italy", "italian"},
            {"PL", "poland", "polish"}, {"NL", "netherlands", "dutch"}, {"BE", "belgium", "belgian"},
            {"SE", "sweden", "swedish"}, {"NO", "norway", "norwegian"}, {"FI", "finland", "finnish"},
            {"DK", "denmark", "danish"}, {"CZ", "czech"}, {"TR", "turkey", "turkish"},
            {"UA", "ukraine", "ukrainian"}, {"RU", "russia", "russian"}, {"CN", "china", "chinese"},
            {"HK", "hong kong"}, {"TW", "taiwan"}, {"ZA", "south africa", "south african"},
            {"EG", "egypt", "egyptian"}, {"NG", "nigeria", "nigerian"}
    };

    /**
     * The WCA data this app reads does not say which competitions are championships, so this goes by
     * the competition's name: World, continental, or the person's own national championship.
     * Returns 0 for none, otherwise 1 world, 2 continental, 3 national.
     */
    private static int championshipLevel(String name, String personIso) {
        String n = name.toLowerCase(Locale.US);
        if (!n.contains("championship")) return 0;
        if (n.contains("world championship") || n.contains("world rubik")) return 1;
        String[] conts = {"asian championship", "european championship", "african championship",
                "north american championship", "south american championship", "oceanian championship",
                "latin american championship", "asia championship", "europe championship"};
        for (String c : conts) if (n.contains(c)) return 2;
        if (n.contains("national championship") || n.contains("nationals")) return 3;
        String iso = personIso == null ? "" : personIso.toUpperCase(Locale.US);
        for (String[] d : DEMONYMS) {
            if (!d[0].equals(iso)) continue;
            for (int i = 1; i < d.length; i++) {
                if (n.matches(".*\\b" + java.util.regex.Pattern.quote(d[i]) + "\\b.*championship.*")) return 3;
            }
        }
        return 0;
    }

    /** Top-three finishes in the final of a World, continental or national championship. */
    private void addChampionshipPodiums(JSONArray results, String personIso) {
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String rt = r.optString("round_type_id");
            int pos = r.optInt("pos", 0);
            if (!(rt.equals("f") || rt.equals("c")) || pos < 1 || pos > 3) continue;
            if (r.optLong("best") <= 0) continue;
            if (championshipLevel(compName(r.optString("competition_id")), personIso) == 0) continue;
            rows.add(r);
        }
        if (rows.isEmpty()) return;
        Collections.sort(rows, (x, y) -> {
            int c = compDate(y.optString("competition_id")).compareTo(compDate(x.optString("competition_id")));
            if (c != 0) return c;
            return WcaFormat.eventOrder(x.optString("event_id")) - WcaFormat.eventOrder(y.optString("event_id"));
        });
        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = ui.card(false);
        card.setPadding(0, 0, 0, 0);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(requireContext());
        LinearLayout table = new LinearLayout(requireContext());
        table.setOrientation(LinearLayout.VERTICAL);
        hs.addView(table);
        LinearLayout header = tableRow(0);
        header.addView(cell("", 44, true));
        header.addView(cell("Event", 44, true));
        header.addView(cell("Single", 92, true));
        header.addView(cell("Average", 92, true));
        header.addView(cell("Championship", 220, true));
        table.addView(header);
        for (int k = 0; k < rows.size(); k++) {
            JSONObject r = rows.get(k);
            String ev = r.optString("event_id");
            LinearLayout line = tableRow(k % 2 == 0 ? 2 : 3);
            line.addView(iconBox(ui.medalIcon(r.optInt("pos"), 24), 44));
            line.addView(iconBox(ui.eventIcon(ev, 22, ui.safe), 44));
            line.addView(cell(WcaFormat.formatResult(r.optLong("best"), ev, false), 92, true));
            line.addView(cell(r.optLong("average") > 0 ? WcaFormat.formatResult(r.optLong("average"), ev, true) : "", 92, true));
            line.addView(compLink(r.optString("competition_id"), 220));
            table.addView(line);
        }
        card.addView(hs);
        wrap.addView(card, ui.matchWrap(0, 4, 0, 4));
        addToggleSection("Championship podiums", wrap, true);
    }

    private static final int W_COMP = 190, W_ROUND = 112, W_PLACE = 56, W_SINGLE = 100, W_AVG = 100, W_SOLVES = 290;

    private LinearLayout historyBox;
    private JSONArray historyResults;

    private void addHistory(JSONArray results) throws Exception {
        historyResults = results;
        historyBox = new LinearLayout(requireContext());
        historyBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(historyBox, ui.matchWrap(0, 0, 0, 0));
        refillHistory();
    }

    /** Rebuilds only the Previous results block, so the other tables keep their state. */
    private void refillHistory() {
        LinearLayout saved = body;
        body = historyBox;
        try {
            historyBox.removeAllViews();
            buildHistory(historyResults);
        } catch (Exception ignored) {
        } finally {
            body = saved;
        }
    }

    private void buildHistory(JSONArray results) throws Exception {
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
                refillHistory();
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
        // A result is a personal record when it beat everything the person did before it.
        final Map<String, boolean[]> prFlags = new java.util.HashMap<>();
        List<JSONObject> chron = new ArrayList<>(rows);
        Collections.sort(chron, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                String ia = a.optString("competition_id"), ib = b.optString("competition_id");
                String da = info != null && info.containsKey(ia) ? info.get(ia)[1] : "9999-99-99";
                String db = info != null && info.containsKey(ib) ? info.get(ib)[1] : "9999-99-99";
                int c = da.compareTo(db);
                if (c != 0) return c;
                c = ia.compareTo(ib);
                if (c != 0) return c;
                return WcaFormat.roundRank(a.optString("round_type_id"))
                        - WcaFormat.roundRank(b.optString("round_type_id"));
            }
        });
        long bestSingle = 0, bestAverage = 0;
        for (JSONObject r : chron) {
            boolean[] f = new boolean[2];
            long b = r.optLong("best");
            long a = r.optLong("average");
            if (b > 0 && (bestSingle == 0 || b < bestSingle)) {
                f[0] = true;
                bestSingle = b;
            }
            if (a > 0 && (bestAverage == 0 || a < bestAverage)) {
                f[1] = true;
                bestAverage = a;
            }
            prFlags.put(r.optString("competition_id") + "|" + r.optString("round_type_id"), f);
        }

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
        ui.setEventIcon(evName, historyEvent, 20, ui.safe);
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
                link.setOnClickListener(v -> showCompetitionPage(cid, linkName));
                line.addView(link);
            }
            lastComp = cid;
            line.addView(cell(WcaFormat.roundName(r.optString("round_type_id")), W_ROUND, false));
            int pos = r.optInt("pos", 0);
            line.addView(cell(pos > 0 ? String.valueOf(pos) : "", W_PLACE, false));
            boolean[] pr = prFlags.get(cid + "|" + r.optString("round_type_id"));
            String sTag = regionTag(r, "regional_single_record");
            String aTag = regionTag(r, "regional_average_record");
            if (sTag.isEmpty() && pr != null && pr[0]) sTag = "PR";
            if (aTag.isEmpty() && pr != null && pr[1]) aTag = "PR";
            line.addView(resultCell(WcaFormat.formatResult(r.optLong("best"), ev, false), sTag, W_SINGLE));
            line.addView(resultCell(WcaFormat.formatResult(r.optLong("average"), ev, true), aTag, W_AVG));
            line.addView(cell(solvesText(r.optJSONArray("attempts"), ev), W_SOLVES, false));
            table.addView(line);
        }
        card.addView(hs);
        body.addView(card, ui.matchWrap(0, 4, 0, 4));

        if (limit < rows.size()) {
            TextView more = ui.pill("Show more (" + (rows.size() - limit) + " left)", false);
            more.setOnClickListener(v -> {
                shown += PAGE_SIZE;
                refillHistory();
            });
            body.addView(more, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));
        }
        body.addView(ui.text("Tap a competition name to open its page. Results come from the WCA website "
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
        LinearLayout f = ui.resultCell("", value, tag, 14, true, true);
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

    // ---------------------------------------------------------------- competition page

    /** The competition page: details, competitors and psych sheets, events, schedule and podiums. */
    private void showCompetitionPage(final String cid, final String name) {
        body.removeAllViews();
        status.setText("");
        footer.setText("");
        // Same header and tabs as the competition in WCA Live: back, name, star, refresh, then two tabs.
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = ui.text("\u2190", 26, true, 1f);
        back.setPadding(ui.dp(8), ui.dp(4), ui.dp(16), ui.dp(4));
        back.setOnClickListener(v -> render());
        header.addView(back);
        TextView title = ui.text(name, 18, true, 1f);
        header.addView(title, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;
        final android.widget.ImageView star = ui.iconButton(store.isFavoriteComp(cid)
                ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.text);
        star.setOnClickListener(v -> {
            store.toggleFavoriteComp(cid, name);
            star.setImageDrawable(ui.tinted(store.isFavoriteComp(cid)
                    ? R.drawable.ic_wca_star_filled : R.drawable.ic_wca_star_outline, 24, ui.text));
        });
        header.addView(star);
        final WcaCompPage[] holder = new WcaCompPage[1];
        android.widget.ImageView refresh = ui.iconButton(R.drawable.ic_wca_refresh, 24, ui.text);
        refresh.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].reload();
        });
        header.addView(refresh);
        body.addView(header, ui.matchWrap(0, 0, 0, 6));
        LinearLayout modeRow = new LinearLayout(requireContext());
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView live = ui.pill("Live results", false);
        live.setOnClickListener(v -> openCompetition(cid, name));
        modeRow.addView(live, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        modeRow.addView(ui.pill("Competition", true));
        body.addView(modeRow, ui.matchWrap(0, 0, 0, 6));
        WcaCompPage page = new WcaCompPage(requireContext(), cid, name, id -> {
            setQuery(id);
            loadPerson(id);
            scroll.smoothScrollTo(0, 0);
        }, null);
        holder[0] = page;
        body.addView(page.view(), ui.matchWrap(0, 0, 0, 0));
        scroll.post(() -> scroll.scrollTo(0, 0));
    }
}
