package com.hatopigeon.cubictimer.wca;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * A page for one competition, built from what the WCA publishes: the details, the competitor list
 * with a psych sheet per event, the events with their formats and limits, the schedule and the
 * podiums. The same page is used in WCA Results and in WCA Live.
 */
public class WcaCompPage {
    /** Called when a competitor is tapped (only for people that have a WCA ID). */
    public interface PersonOpener {
        void open(String wcaId);
    }

    private interface Done {
        void run(String error);
    }

    private static final String WCA = "https://www.worldcubeassociation.org/api/v0";
    private static final int ROWS_PER_PAGE = 60;
    private static final String[] TABS = {"Info", "Competitors", "Events", "Schedule", "Results", "Podiums"};

    // Kept in memory only, so reopening a competition is quick but nothing is stored on disk.
    private static final Map<String, JSONObject> INFO = new HashMap<>();
    private static final Map<String, Wcif> WCIF = new HashMap<>();
    private static final Map<String, JSONArray> RESULTS = new HashMap<>();

    // ---- models
    private static class Pb {
        long single, average;
        int singleRank, averageRank;
    }

    private static class Per {
        String name = "", wcaId = "", iso = "";
        Set<String> events = new HashSet<>();
        Map<String, Pb> pbs = new HashMap<>();
    }

    private static class Rnd {
        String format = "";
        long limit = 0;
        boolean cumulative;
        int cutoffAttempts;
        long cutoffResult;
        String advType = "";
        long advLevel;
    }

    private static class Ev {
        String id = "";
        List<Rnd> rounds = new ArrayList<>();
    }

    private static class Act {
        String name = "", code = "", room = "", roomColor = "", venue = "";
        long start, end;
        TimeZone tz = TimeZone.getTimeZone("UTC");
        List<Act> children = new ArrayList<>();
    }

    private static class Wcif {
        List<Per> persons = new ArrayList<>();
        List<Ev> events = new ArrayList<>();
        List<Act> acts = new ArrayList<>();
        Set<String> rooms = new java.util.LinkedHashSet<>();
        int venues = 0;
    }

    // ---- state
    private final Context ctx;
    private final WcaUi ui;
    private final String compId;
    private final String compName;
    private final PersonOpener opener;
    private final Runnable openLive;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final WcaStore store;

    private LinearLayout root;
    private LinearLayout actions;
    private LinearLayout content;
    private final List<TextView> tabPills = new ArrayList<>();
    private int tab = 0;
    private int token = 0;

    private boolean wcifLoading, resultsLoading;
    private final List<Done> wcifWaiters = new ArrayList<>();
    private final List<Done> resultsWaiters = new ArrayList<>();

    private String selEvent = null;
    private boolean basisAverage = true;
    private boolean basisTouched = false;
    private int shown = ROWS_PER_PAGE;
    private int schedDay = -1;
    private String schedRoom = null;
    private boolean schedGroups = false;
    private int schedView = 0; // 0 calendar, 1 table
    private final Set<String> schedOff = new HashSet<>();

    public WcaCompPage(Context ctx, String compId, String compName, PersonOpener opener, Runnable openLive) {
        this.ctx = ctx;
        this.ui = new WcaUi(ctx);
        this.compId = compId;
        this.compName = compName;
        this.opener = opener;
        this.openLive = openLive;
        this.store = new WcaStore(ctx);
    }

    // ---------------------------------------------------------------- layout

    public View view() {
        if (root != null) return root;
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (openLive != null) {
            TextView live = ui.pill("Results in WCA Live", true);
            live.setOnClickListener(v -> openLive.run());
            actions.addView(live, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        }
        TextView web = ui.pill("WCA website", false);
        web.setOnClickListener(v -> ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.worldcubeassociation.org/competitions/" + Uri.encode(compId)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        actions.addView(web);
        HorizontalScrollView actionScroll = new HorizontalScrollView(ctx);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        root.addView(actionScroll, ui.matchWrap(0, 4, 0, 8));

        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView p = ui.pill(TABS[i], i == tab);
            p.setOnClickListener(v -> {
                tab = idx;
                shown = ROWS_PER_PAGE;
                showTab();
            });
            tabPills.add(p);
            tabRow.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        }
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabRow);
        root.addView(tabScroll, ui.matchWrap(0, 0, 0, 8));

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, ui.matchWrap(0, 0, 0, 24));
        showTab();
        return root;
    }

    /** Forgets what was loaded for this competition and loads it again. */
    public void reload() {
        INFO.remove(compId);
        WCIF.remove(compId);
        RESULTS.remove(compId);
        if (root != null) showTab();
    }

    private void showTab() {
        token++;
        for (int i = 0; i < tabPills.size(); i++) ui.stylePill(tabPills.get(i), i == tab);
        content.removeAllViews();
        switch (tab) {
            case 0: renderInfo(); break;
            case 1: renderCompetitors(); break;
            case 2: renderEvents(); break;
            case 3: renderSchedule(); break;
            case 4: renderResultsTab(); break;
            default: renderPodiums(); break;
        }
    }

    // ---------------------------------------------------------------- small helpers

    private TextView tv(String s, float sp, boolean bold, float alpha) {
        TextView t = ui.text(s, sp, bold, alpha);
        t.setTextColor(ui.safe);
        return t;
    }

    private void loadingText(String s) {
        content.removeAllViews();
        content.addView(ui.text(s, 14, false, 0.85f), ui.matchWrap(4, 8, 4, 8));
    }

    private void errorBox(String error) {
        content.removeAllViews();
        content.addView(ui.text(error + ".", 14, false, 0.9f), ui.matchWrap(4, 8, 4, 8));
        TextView retry = ui.pill("Try again", false);
        retry.setOnClickListener(v -> showTab());
        content.addView(retry, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 4, 0, 4));
    }

    private LinearLayout tableRow(boolean shaded, boolean header) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(12), ui.dp(header ? 10 : 8), ui.dp(12), ui.dp(header ? 10 : 8));
        if (shaded) row.setBackgroundColor(ColorUtils.blendARGB(ui.itemBg, ui.safe, 0.07f));
        return row;
    }

    private boolean starred(Per p) {
        return !p.wcaId.isEmpty() && store.isPinned(p.wcaId);
    }

    private void markStarred(LinearLayout row, Per p) {
        if (starred(p)) row.setBackgroundColor(ColorUtils.blendARGB(ui.itemBg, ui.safe, 0.2f));
    }

    private static final int LINK = 0xFFE8531F;

    private String peopleLine(List<Per> list) {
        int first = 0;
        for (Per p : list) if (p.wcaId.isEmpty()) first++;
        int ret = list.size() - first;
        return first + " first-timers + " + ret + " returners = " + list.size() + " people";
    }

    private TextView cell(String s, int widthDp, boolean bold, int gravity) {
        TextView t = tv(s, 14, bold, 1f);
        t.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setGravity(gravity);
        t.setPadding(0, 0, ui.dp(8), 0);
        return t;
    }

    private LinearLayout tableCard(LinearLayout table) {
        LinearLayout card = ui.card(false);
        card.setPadding(0, 0, 0, 0);
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.addView(table);
        card.addView(hs);
        return card;
    }

    private Drawable icon(String eventId) {
        Drawable d = iconCache.get(eventId);
        if (d == null && !iconCache.containsKey(eventId)) {
            d = ui.eventIcon(eventId, 20, ui.safe);
            iconCache.put(eventId, d);
        }
        return d;
    }

    static String countryName(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        String n = new Locale("", iso).getDisplayCountry(Locale.ENGLISH);
        return n == null || n.isEmpty() ? iso : n;
    }

    private String withFlag(String name, String iso) {
        String f = WcaFlags.emoji(iso);
        return (f.isEmpty() ? "" : f + " ") + name;
    }

    private static String stripMarkdown(String s) {
        if (s == null) return "";
        String t = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "").replaceAll("<[^>]+>", "");
        t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("[*_`>#]", "").replaceAll("\r", "").replaceAll("\n{3,}", "\n\n");
        return t.trim();
    }

    private static String names(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("name");
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }

    static String dates(String start, String end) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date a = in.parse(start);
            Date b = end == null || end.isEmpty() ? a : in.parse(end);
            SimpleDateFormat md = new SimpleDateFormat("MMM d", Locale.ENGLISH);
            SimpleDateFormat d = new SimpleDateFormat("d", Locale.ENGLISH);
            SimpleDateFormat y = new SimpleDateFormat("yyyy", Locale.ENGLISH);
            SimpleDateFormat m = new SimpleDateFormat("MMM", Locale.ENGLISH);
            if (a == null || b == null) return start;
            if (start.equals(end)) return md.format(a) + ", " + y.format(a);
            if (m.format(a).equals(m.format(b)) && y.format(a).equals(y.format(b))) {
                return md.format(a) + " - " + d.format(b) + ", " + y.format(a);
            }
            return md.format(a) + " - " + md.format(b) + ", " + y.format(b);
        } catch (Exception e) {
            return start;
        }
    }

    private void addInfo(LinearLayout card, String label, String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) return;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(tv(label, 13, true, 0.7f), ui.lp(ui.dp(104), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        TextView v = tv(value, 14, false, 1f);
        row.addView(v, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) v.getLayoutParams()).weight = 1f;
        card.addView(row, ui.matchWrap(0, 4, 0, 4));
    }

    // ---------------------------------------------------------------- loading

    private boolean infoLoading;
    private final List<Done> infoWaiters = new ArrayList<>();

    private void finishInfo(String err) {
        infoLoading = false;
        List<Done> w = new ArrayList<>(infoWaiters);
        infoWaiters.clear();
        for (Done d : w) d.run(err);
    }

    private void ensureInfo(final Done done) {
        if (INFO.containsKey(compId)) {
            done.run(null);
            return;
        }
        infoWaiters.add(done);
        if (infoLoading) return;
        infoLoading = true;
        WcaHttp.get(WCA + "/competitions/" + Uri.encode(compId), new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    INFO.put(compId, new JSONObject(body));
                    finishInfo(null);
                } catch (Exception e) {
                    finishInfo("Could not read the competition details");
                }
            }

            @Override
            public void onError(String message) {
                finishInfo(WcaHttp.describe(ctx, message));
            }
        });
    }

    /** Old competitions have no registration list, so the competitors are the people with results. */
    private void ensureWcif(final Done done) {
        ensureWcifReal(err -> {
            Wcif w = WCIF.get(compId);
            if (err == null && w != null && !w.persons.isEmpty()) {
                done.run(null);
                return;
            }
            ensureResults(err2 -> {
                JSONArray arr = RESULTS.get(compId);
                if (err2 != null || arr == null || arr.length() == 0) {
                    done.run(err != null ? err : err2);
                    return;
                }
                Wcif f = w != null ? w : new Wcif();
                Map<String, Per> byKey = new java.util.LinkedHashMap<>();
                Set<String> evSet = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r == null) continue;
                    String wid = r.isNull("wca_id") ? "" : r.optString("wca_id");
                    String key = wid.isEmpty() ? "n:" + r.optString("name") : wid;
                    Per per = byKey.get(key);
                    if (per == null) {
                        per = new Per();
                        per.name = r.optString("name");
                        per.wcaId = wid;
                        per.iso = WcaFlags.code(r.optString("country_iso2"));
                        byKey.put(key, per);
                    }
                    String ev = r.optString("event_id");
                    per.events.add(ev);
                    evSet.add(ev);
                }
                f.persons = new ArrayList<>(byKey.values());
                if (f.events.isEmpty()) {
                    List<String> ids = new ArrayList<>(evSet);
                    Collections.sort(ids, (x, y) -> WcaFormat.eventOrder(x) - WcaFormat.eventOrder(y));
                    for (String id : ids) {
                        Ev e = new Ev();
                        e.id = id;
                        f.events.add(e);
                    }
                }
                WCIF.put(compId, f);
                done.run(null);
            });
        });
    }

    private void ensureWcifReal(final Done done) {
        if (WCIF.containsKey(compId)) {
            done.run(null);
            return;
        }
        wcifWaiters.add(done);
        if (wcifLoading) return;
        wcifLoading = true;
        WcaHttp.get(WCA + "/competitions/" + Uri.encode(compId) + "/wcif/public", new WcaHttp.Callback() {
            @Override
            public void onSuccess(final String body) {
                final Wcif[] out = new Wcif[1];
                WcaHttp.background(() -> {
                    try {
                        out[0] = parseWcif(body);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, () -> {
                    wcifLoading = false;
                    String err = null;
                    if (out[0] == null) err = "Could not read the competition data";
                    else WCIF.put(compId, out[0]);
                    List<Done> w = new ArrayList<>(wcifWaiters);
                    wcifWaiters.clear();
                    for (Done d : w) d.run(err);
                });
            }

            @Override
            public void onError(String message) {
                wcifLoading = false;
                String err = WcaHttp.describe(ctx, message);
                if (message != null && message.startsWith("HTTP 404")) {
                    err = "The WCA has not published the competitor list for this competition";
                }
                List<Done> w = new ArrayList<>(wcifWaiters);
                wcifWaiters.clear();
                for (Done d : w) d.run(err);
            }
        });
    }

    private void ensureResults(final Done done) {
        if (RESULTS.containsKey(compId)) {
            done.run(null);
            return;
        }
        resultsWaiters.add(done);
        if (resultsLoading) return;
        resultsLoading = true;
        WcaHttp.get(WCA + "/competitions/" + Uri.encode(compId) + "/results", new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                resultsLoading = false;
                String err = null;
                try {
                    String t = body.trim();
                    JSONArray arr;
                    if (t.startsWith("[")) {
                        arr = new JSONArray(t);
                    } else {
                        JSONObject o = new JSONObject(t);
                        arr = o.optJSONArray("results");
                        if (arr == null) arr = o.optJSONArray("result");
                        if (arr == null) arr = new JSONArray();
                    }
                    RESULTS.put(compId, arr);
                } catch (Exception e) {
                    err = "Could not read the results";
                }
                List<Done> w = new ArrayList<>(resultsWaiters);
                resultsWaiters.clear();
                for (Done d : w) d.run(err);
            }

            @Override
            public void onError(String message) {
                resultsLoading = false;
                String err = WcaHttp.describe(ctx, message);
                List<Done> w = new ArrayList<>(resultsWaiters);
                resultsWaiters.clear();
                for (Done d : w) d.run(err);
            }
        });
    }

    // ---------------------------------------------------------------- WCIF parsing

    private static long parseIso(String s) {
        try {
            if (s == null || s.length() < 19) return 0;
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(s.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static Act parseAct(JSONObject a, String room, String color, String venue, TimeZone tz) {
        Act act = new Act();
        act.name = a.optString("name");
        act.code = a.optString("activityCode");
        act.start = parseIso(a.optString("startTime"));
        act.end = parseIso(a.optString("endTime"));
        act.room = room;
        act.roomColor = color;
        act.venue = venue;
        act.tz = tz;
        JSONArray ch = a.optJSONArray("childActivities");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) {
                JSONObject c = ch.optJSONObject(i);
                if (c != null) act.children.add(parseAct(c, room, color, venue, tz));
            }
            Collections.sort(act.children, (x, y) -> Long.compare(x.start, y.start));
        }
        return act;
    }

    private static Wcif parseWcif(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        Wcif w = new Wcif();
        JSONArray pa = root.optJSONArray("persons");
        if (pa != null) {
            for (int i = 0; i < pa.length(); i++) {
                JSONObject p = pa.optJSONObject(i);
                if (p == null) continue;
                JSONObject reg = p.optJSONObject("registration");
                if (reg == null) continue;
                if (!"accepted".equals(reg.optString("status", "accepted"))) continue;
                Per per = new Per();
                per.name = p.optString("name");
                per.wcaId = p.isNull("wcaId") ? "" : p.optString("wcaId");
                per.iso = WcaFlags.code(p.optString("countryIso2"));
                JSONArray re = reg.optJSONArray("eventIds");
                if (re != null) for (int k = 0; k < re.length(); k++) per.events.add(re.optString(k));
                JSONArray pbs = p.optJSONArray("personalBests");
                if (pbs != null) {
                    for (int k = 0; k < pbs.length(); k++) {
                        JSONObject b = pbs.optJSONObject(k);
                        if (b == null) continue;
                        String ev = b.optString("eventId");
                        Pb pb = per.pbs.get(ev);
                        if (pb == null) {
                            pb = new Pb();
                            per.pbs.put(ev, pb);
                        }
                        if ("average".equals(b.optString("type"))) {
                            pb.average = b.optLong("best");
                            pb.averageRank = b.optInt("worldRanking");
                        } else {
                            pb.single = b.optLong("best");
                            pb.singleRank = b.optInt("worldRanking");
                        }
                    }
                }
                w.persons.add(per);
            }
        }
        JSONArray ea = root.optJSONArray("events");
        if (ea != null) {
            for (int i = 0; i < ea.length(); i++) {
                JSONObject eo = ea.optJSONObject(i);
                if (eo == null) continue;
                Ev ev = new Ev();
                ev.id = eo.optString("id");
                JSONArray ra = eo.optJSONArray("rounds");
                if (ra != null) {
                    for (int j = 0; j < ra.length(); j++) {
                        JSONObject ro = ra.optJSONObject(j);
                        if (ro == null) continue;
                        Rnd r = new Rnd();
                        r.format = ro.optString("format");
                        JSONObject tl = ro.optJSONObject("timeLimit");
                        if (tl != null) {
                            r.limit = tl.optLong("centiseconds");
                            JSONArray cum = tl.optJSONArray("cumulativeRoundIds");
                            r.cumulative = cum != null && cum.length() > 0;
                        }
                        JSONObject co = ro.optJSONObject("cutoff");
                        if (co != null) {
                            r.cutoffAttempts = co.optInt("numberOfAttempts");
                            r.cutoffResult = co.optLong("attemptResult");
                        }
                        JSONObject ad = ro.optJSONObject("advancementCondition");
                        if (ad != null) {
                            r.advType = ad.optString("type");
                            r.advLevel = ad.optLong("level");
                        }
                        ev.rounds.add(r);
                    }
                }
                w.events.add(ev);
            }
            Collections.sort(w.events, (a, b) -> WcaFormat.eventOrder(a.id) - WcaFormat.eventOrder(b.id));
        }
        JSONObject sched = root.optJSONObject("schedule");
        JSONArray venues = sched == null ? null : sched.optJSONArray("venues");
        if (venues != null) {
            w.venues = venues.length();
            for (int v = 0; v < venues.length(); v++) {
                JSONObject vo = venues.optJSONObject(v);
                if (vo == null) continue;
                String venueName = vo.optString("name");
                String tzName = vo.optString("timezone", "UTC");
                TimeZone tz = TimeZone.getTimeZone(tzName.isEmpty() ? "UTC" : tzName);
                JSONArray rooms = vo.optJSONArray("rooms");
                if (rooms == null) continue;
                for (int r = 0; r < rooms.length(); r++) {
                    JSONObject ro = rooms.optJSONObject(r);
                    if (ro == null) continue;
                    String roomName = ro.optString("name");
                    String color = ro.optString("color");
                    w.rooms.add(roomName);
                    JSONArray acts = ro.optJSONArray("activities");
                    if (acts == null) continue;
                    for (int a = 0; a < acts.length(); a++) {
                        JSONObject ao = acts.optJSONObject(a);
                        if (ao != null) w.acts.add(parseAct(ao, roomName, color, venueName, tz));
                    }
                }
            }
            Collections.sort(w.acts, (x, y) -> Long.compare(x.start, y.start));
        }
        return w;
    }

    // ---------------------------------------------------------------- Info tab

    private void renderInfo() {
        final int my = tab;
        JSONObject cached = INFO.get(compId);
        if (cached == null) {
            loadingText("Loading competition...");
            ensureInfo(err -> {
                if (my != tab) return;
                if (err != null) errorBox(err);
                else renderInfo();
            });
            return;
        }
        JSONObject c = cached;
        final LinearLayout card = ui.card(false);
        String iso = c.optString("country_iso2");
        card.addView(tv(withFlag(c.optString("name", compName), iso), 20, true, 1f));
        addInfo(card, "Date", dates(c.optString("start_date"), c.optString("end_date")));
        String country = countryName(iso);
        String city = c.optString("city");
        addInfo(card, "Location", city + (city.isEmpty() || country.isEmpty() ? "" : ", ") + country);
        addInfo(card, "Venue", c.optString("venue").replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1"));
        addInfo(card, "Address", c.optString("venue_address"));
        addInfo(card, "Details", c.optString("venue_details"));
        addInfo(card, "Delegates", names(c.optJSONArray("delegates")));
        addInfo(card, "Organizers", names(c.optJSONArray("organizers")));
        int limitN = c.optInt("competitor_limit", 0);
        if (limitN > 0) addInfo(card, "Competitor limit", String.valueOf(limitN));
        String ro = c.optString("registration_open");
        String rc = c.optString("registration_close");
        if (ro.length() >= 10) addInfo(card, "Registration opens", ro.substring(0, 10));
        if (rc.length() >= 10) addInfo(card, "Registration closes", rc.substring(0, 10));
        String cancelled = c.optString("cancelled_at");
        if (!cancelled.isEmpty() && !"null".equals(cancelled)) addInfo(card, "Status", "Cancelled");
        JSONArray evs = c.optJSONArray("event_ids");
        if (evs != null && evs.length() > 0) {
            card.addView(tv("Events", 13, true, 0.7f), ui.matchWrap(0, 10, 0, 4));
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            HorizontalScrollView hs = new HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(wrap);
            for (int i = 0; i < evs.length(); i++) {
                String id = evs.optString(i);
                Drawable ic = ui.eventIcon(id, 28, ui.safe);
                if (ic == null) continue;
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(ic);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                iv.setContentDescription(WcaFormat.eventName(id));
                wrap.addView(iv, ui.lp(ui.dp(40), ui.dp(40), 0, 0, 4, 0));
            }
            card.addView(hs);
        }
        content.addView(card, ui.matchWrap(0, 4, 0, 4));

        String about = stripMarkdown(c.optString("information"));
        if (!about.isEmpty() && !"null".equals(about)) {
            LinearLayout aboutCard = ui.card(false);
            aboutCard.addView(tv("About", 14, true, 0.85f), ui.matchWrap(0, 0, 0, 4));
            aboutCard.addView(tv(about.length() > 1500 ? about.substring(0, 1500) + "..." : about, 14, false, 1f));
            content.addView(aboutCard, ui.matchWrap(0, 4, 0, 4));
        }

        double lat = c.optDouble("latitude_degrees", Double.NaN);
        double lon = c.optDouble("longitude_degrees", Double.NaN);
        if (!Double.isNaN(lat) && !Double.isNaN(lon) && actions.getTag() == null) {
            actions.setTag("map");
            final String geo = "geo:" + lat + "," + lon + "?q=" + lat + "," + lon;
            TextView map = ui.pill("Map", false);
            map.setOnClickListener(v -> {
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(geo))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            });
            actions.addView(map, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        }

        // The competitor count comes from the competitor list, which loads a little later.
        ensureWcif(err -> {
            if (err != null || my != tab) return;
            Wcif w = WCIF.get(compId);
            if (w == null) return;
            Set<String> countries = new HashSet<>();
            for (Per p : w.persons) if (!p.iso.isEmpty()) countries.add(p.iso);
            addInfo(card, "Competitors", w.persons.size() + " from " + countries.size()
                    + (countries.size() == 1 ? " country" : " countries"));
        });
    }

    // ---------------------------------------------------------------- Competitors tab

    private static boolean noAverage(String eventId) {
        return "333mbf".equals(eventId);
    }

    private static boolean defaultAverage(String eventId) {
        return !("333bf".equals(eventId) || "444bf".equals(eventId) || "555bf".equals(eventId)
                || "333mbf".equals(eventId));
    }

    /** One slot per competition event; the icon is only drawn where the person is registered. */
    private static class IconStrip extends View {
        private final List<Drawable> icons;
        private final int slot, size;

        interface IconTap { void tapped(int index); }

        private IconTap tap;

        IconStrip(Context c, List<Drawable> icons, int slotPx, int sizePx) {
            super(c);
            this.icons = icons;
            this.slot = slotPx;
            this.size = sizePx;
        }

        /** Tapping an icon reports its index (used by the header icons to open a psych sheet). */
        void onIconTap(IconTap t) {
            this.tap = t;
            setClickable(t != null);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (tap == null) return super.onTouchEvent(e);
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) return true;
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                int i = (int) (e.getX() / slot);
                if (i >= 0 && i < icons.size()) {
                    performClick();
                    tap.tapped(i);
                }
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(slot * icons.size(), slot);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int i = 0; i < icons.size(); i++) {
                Drawable d = icons.get(i);
                if (d == null) continue;
                canvas.save();
                canvas.translate(i * slot + (slot - size) / 2f, (slot - size) / 2f);
                d.draw(canvas);
                canvas.restore();
            }
        }
    }

    /** One number per competition event, centred in the same slots as the icon strip. */
    private static class CountStrip extends View {
        private final int[] counts;
        private final int slot;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        CountStrip(Context c, int[] counts, int slotPx, int color, float textPx) {
            super(c);
            this.counts = counts;
            this.slot = slotPx;
            paint.setColor(color);
            paint.setTextSize(textPx);
            paint.setFakeBoldText(true);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(slot * counts.length, slot);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float y = slot / 2f - (paint.descent() + paint.ascent()) / 2f;
            for (int i = 0; i < counts.length; i++) canvas.drawText(String.valueOf(counts[i]), i * slot + slot / 2f, y, paint);
        }
    }

    private void renderCompetitors() {
        final int my = tab;
        final Wcif w = WCIF.get(compId);
        if (w == null) {
            loadingText("Loading competitors...");
            ensureWcif(err -> {
                if (my != tab) return;
                if (err != null) errorBox(err);
                else renderCompetitors();
            });
            return;
        }
        content.removeAllViews();
        if (w.persons.isEmpty()) {
            content.addView(ui.text("No competitors are registered yet.", 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }
        if (selEvent != null) {
            boolean exists = false;
            for (Ev e : w.events) if (e.id.equals(selEvent)) exists = true;
            if (!exists) selEvent = null;
        }

        // Event picker: tap an event to see its psych sheet, tap it again to go back to the list.
        LinearLayout pickCard = ui.card(false);
        pickCard.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        HorizontalScrollView pickScroll = new HorizontalScrollView(ctx);
        pickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pick = new LinearLayout(ctx);
        pick.setOrientation(LinearLayout.HORIZONTAL);
        pick.setGravity(Gravity.CENTER_VERTICAL);
        pickScroll.addView(pick);
        for (final Ev e : w.events) {
            final String id = e.id;
            boolean sel = id.equals(selEvent);
            View item;
            Drawable ic = ui.eventIcon(id, 28, ui.safe);
            if (ic != null) {
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(ic);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                iv.setContentDescription(WcaFormat.eventName(id));
                item = iv;
            } else {
                TextView t = ui.pill(WcaFormat.eventName(id), sel);
                item = t;
            }
            if (sel) {
                item.setBackground(ui.rounded(ui.highlight, ui.dp(8)));
            }
            item.setAlpha(selEvent == null || sel ? 1f : 0.4f);
            item.setOnClickListener(v -> {
                selEvent = id.equals(selEvent) ? null : id;
                basisTouched = false;
                shown = ROWS_PER_PAGE;
                renderCompetitors();
            });
            pick.addView(item, ui.lp(ui.dp(44), ui.dp(44), 0, 0, 4, 0));
        }
        pickCard.addView(pickScroll);
        content.addView(pickCard, ui.matchWrap(0, 4, 0, 6));

        if (selEvent == null) renderCompetitorTable(w);
        else renderPsychSheet(w, selEvent);
    }

    private void addShowMore(int total, int limit, final Runnable redo) {
        if (limit >= total) return;
        TextView more = ui.pill("Show more (" + (total - limit) + " left)", false);
        more.setOnClickListener(v -> {
            shown += ROWS_PER_PAGE;
            redo.run();
        });
        content.addView(more, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));
    }

    private void openPerson(Per p) {
        if (opener != null && !p.wcaId.isEmpty()) opener.open(p.wcaId);
    }

    private void renderCompetitorTable(final Wcif w) {
        List<Per> list = new ArrayList<>(w.persons);
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        content.addView(ui.text(peopleLine(list) + ". Tap an event icon for its psych sheet.",
                13, false, 0.85f), ui.matchWrap(4, 0, 4, 6));

        final int slot = ui.dp(32), size = ui.dp(20);
        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = tableRow(false, true);
        header.addView(cell("Name \u25B2", 190, true, Gravity.START));
        header.addView(cell("Representing", 150, true, Gravity.START));
        header.addView(cell("Events", 64, true, Gravity.CENTER));
        List<Drawable> all = new ArrayList<>();
        for (Ev e : w.events) all.add(icon(e.id));
        IconStrip headStrip = new IconStrip(ctx, all, slot, size);
        headStrip.onIconTap(i -> {
            selEvent = w.events.get(i).id;
            basisTouched = false;
            shown = ROWS_PER_PAGE;
            renderCompetitors();
        });
        header.addView(headStrip);
        table.addView(header);

        int limit = Math.min(shown, list.size());
        for (int i = 0; i < limit; i++) {
            final Per p = list.get(i);
            LinearLayout row = tableRow(i % 2 == 1, false);
            TextView nm = cell(p.name, 190, false, Gravity.START);
            if (!p.wcaId.isEmpty()) nm.setTextColor(LINK);
            row.addView(nm);
            row.addView(cell(withFlag(countryName(p.iso), p.iso), 150, false, Gravity.START));
            int mineCount = 0;
            List<Drawable> mine = new ArrayList<>();
            for (Ev e : w.events) {
                boolean in = p.events.contains(e.id);
                if (in) mineCount++;
                mine.add(in ? icon(e.id) : null);
            }
            row.addView(cell(String.valueOf(mineCount), 64, false, Gravity.CENTER));
            row.addView(new IconStrip(ctx, mine, slot, size));
            if (!p.wcaId.isEmpty()) row.setOnClickListener(v -> openPerson(p));
            markStarred(row, p);
            table.addView(row);
        }

        // Totals for everyone, not only the rows shown so far.
        Set<String> regions = new HashSet<>();
        int[] perEvent = new int[w.events.size()];
        int totalEntries = 0;
        for (Per p : list) {
            if (!p.iso.isEmpty()) regions.add(p.iso);
            for (int k = 0; k < w.events.size(); k++) {
                if (p.events.contains(w.events.get(k).id)) {
                    perEvent[k]++;
                    totalEntries++;
                }
            }
        }
        LinearLayout foot = tableRow(false, true);
        foot.setBackgroundColor(ColorUtils.blendARGB(ui.itemBg, ui.safe, 0.14f));
        foot.addView(cell(list.size() + (list.size() == 1 ? " competitor" : " competitors"), 190, true, Gravity.START));
        foot.addView(cell(regions.size() + (regions.size() == 1 ? " region" : " regions"), 150, true, Gravity.START));
        foot.addView(cell(String.valueOf(totalEntries), 64, true, Gravity.CENTER));
        foot.addView(new CountStrip(ctx, perEvent, slot, ui.safe, ui.dp(13)));
        table.addView(foot);

        content.addView(tableCard(table), ui.matchWrap(0, 4, 0, 4));
        addShowMore(list.size(), limit, this::renderCompetitors);
    }

    private static long pbValue(Pb pb, boolean average) {
        if (pb == null) return 0;
        return average ? pb.average : pb.single;
    }

    private void renderPsychSheet(Wcif w, final String evId) {
        final boolean noAvg = noAverage(evId);
        if (!basisTouched) basisAverage = defaultAverage(evId);
        final boolean avg = basisAverage && !noAvg;

        final List<Per> list = new ArrayList<>();
        for (Per p : w.persons) if (p.events.contains(evId)) list.add(p);
        Collections.sort(list, new Comparator<Per>() {
            @Override
            public int compare(Per a, Per b) {
                Pb pa = a.pbs.get(evId), pb = b.pbs.get(evId);
                long va = pbValue(pa, avg), vb = pbValue(pb, avg);
                boolean ha = va > 0, hb = vb > 0;
                if (ha != hb) return ha ? -1 : 1;
                if (ha && va != vb) return Long.compare(va, vb);
                long oa = pbValue(pa, !avg), ob = pbValue(pb, !avg);
                boolean xa = oa > 0, xb = ob > 0;
                if (xa != xb) return xa ? -1 : 1;
                if (xa && oa != ob) return Long.compare(oa, ob);
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        TextView title = tv(WcaFormat.eventName(evId), 15, true, 1f);
        ui.setEventIcon(title, evId, 20, ui.text);
        title.setTextColor(ui.text);
        content.addView(title, ui.matchWrap(4, 2, 4, 2));
        content.addView(ui.text(peopleLine(list) + ". Tap Single or Average to change the ranking.",
                13, false, 0.85f), ui.matchWrap(4, 0, 4, 6));
        if (list.isEmpty()) {
            content.addView(ui.text("Nobody is registered for this event.", 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }

        int shade = ColorUtils.blendARGB(ui.itemBg, ui.safe, 0.12f);
        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = tableRow(false, true);
        header.addView(cell("#", 36, true, Gravity.START));
        header.addView(cell("Name", 200, true, Gravity.START));
        header.addView(cell("WR", 56, true, Gravity.END));
        TextView hs = cell(avg ? "Single" : "Single \u25B2", 92, true, Gravity.END);
        if (!avg) hs.setBackgroundColor(shade);
        hs.setOnClickListener(v -> {
            basisAverage = false;
            basisTouched = true;
            shown = ROWS_PER_PAGE;
            renderCompetitors();
        });
        header.addView(hs);
        if (!noAvg) {
            TextView ha = cell(avg ? "Average \u25B2" : "Average", 92, true, Gravity.END);
            if (avg) ha.setBackgroundColor(shade);
            ha.setOnClickListener(v -> {
                basisAverage = true;
                basisTouched = true;
                shown = ROWS_PER_PAGE;
                renderCompetitors();
            });
            header.addView(ha);
            header.addView(cell("WR", 56, true, Gravity.END));
        }
        table.addView(header);

        int limit = Math.min(shown, list.size());
        int place = 0;
        long prev = -1;
        for (int i = 0; i < limit; i++) {
            final Per p = list.get(i);
            Pb pb = p.pbs.get(evId);
            long v = pbValue(pb, avg);
            String rank = "-";
            if (v > 0) {
                if (v != prev) place = i + 1;
                prev = v;
                rank = String.valueOf(place);
            }
            LinearLayout row = tableRow(i % 2 == 1, false);
            row.addView(cell(rank, 36, false, Gravity.START));
            TextView nm = cell(withFlag(p.name, p.iso), 200, false, Gravity.START);
            if (!p.wcaId.isEmpty()) nm.setTextColor(LINK);
            row.addView(nm);
            row.addView(cell(pb != null && pb.singleRank > 0 ? String.valueOf(pb.singleRank) : "", 56, false, Gravity.END));
            row.addView(cell(pb != null && pb.single > 0 ? WcaFormat.formatResult(pb.single, evId, false) : "",
                    92, false, Gravity.END));
            if (!noAvg) {
                row.addView(cell(pb != null && pb.average > 0 ? WcaFormat.formatResult(pb.average, evId, true) : "",
                        92, false, Gravity.END));
                row.addView(cell(pb != null && pb.averageRank > 0 ? String.valueOf(pb.averageRank) : "", 56, false, Gravity.END));
            }
            if (!p.wcaId.isEmpty()) row.setOnClickListener(x -> openPerson(p));
            markStarred(row, p);
            table.addView(row);
        }
        content.addView(tableCard(table), ui.matchWrap(0, 4, 0, 4));
        addShowMore(list.size(), limit, this::renderCompetitors);
    }

    // ---------------------------------------------------------------- Events tab

    private static String formatShort(String f) {
        switch (f) {
            case "a": return "Ao5";
            case "m": return "Mo3";
            case "1": return "Bo1";
            case "2": return "Bo2";
            case "3": return "Bo3";
            default: return f.isEmpty() ? "-" : f;
        }
    }

    private static String roundTitle(int index, int total) {
        int n = index + 1;
        if (n == total) return "Final";
        if (total >= 3 && n == total - 1) return "Semi final";
        String[] names = {"First round", "Second round", "Third round", "Fourth round"};
        return n >= 1 && n <= names.length ? names[n - 1] : "Round " + n;
    }

    private static String valueStr(String evId, long v) {
        if ("333fm".equals(evId) || "333mbf".equals(evId)) return WcaFormat.formatResult(v, evId, false);
        return WcaFormat.formatCentiseconds(v);
    }

    private static String formatText(Rnd r) {
        String f = formatShort(r.format);
        if (r.cutoffResult > 0 && r.cutoffAttempts > 0) return "Bo" + r.cutoffAttempts + " / " + f;
        return f;
    }

    private static String limitText(String evId, Rnd r) {
        if (r.limit <= 0) return "-";
        return valueStr(evId, r.limit) + (r.cumulative ? " total" : "");
    }

    private static String cutoffText(String evId, Rnd r) {
        if (r.cutoffResult <= 0 || r.cutoffAttempts <= 0) return "-";
        return valueStr(evId, r.cutoffResult);
    }

    private static String proceedText(String evId, Rnd r, boolean last) {
        if (last) return "-";
        switch (r.advType) {
            case "ranking": return "Top " + r.advLevel;
            case "percent": return "Top " + r.advLevel + "%";
            case "attemptResult": return "Better than " + valueStr(evId, r.advLevel);
            default: return "-";
        }
    }

    private void renderEvents() {
        final int my = tab;
        final Wcif w = WCIF.get(compId);
        if (w == null) {
            loadingText("Loading events...");
            ensureWcif(err -> {
                if (my != tab) return;
                if (err != null) errorBox(err);
                else renderEvents();
            });
            return;
        }
        content.removeAllViews();
        if (w.events.isEmpty()) {
            content.addView(ui.text("No events are listed for this competition.", 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }
        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = tableRow(false, true);
        header.addView(cell("Event", 150, true, Gravity.START));
        header.addView(cell("Round", 112, true, Gravity.START));
        header.addView(cell("Format", 116, true, Gravity.START));
        header.addView(cell("Time limit", 116, true, Gravity.START));
        header.addView(cell("Cutoff", 100, true, Gravity.START));
        header.addView(cell("Proceed", 120, true, Gravity.START));
        table.addView(header);

        int group = 0;
        for (Ev e : w.events) {
            for (int i = 0; i < e.rounds.size(); i++) {
                Rnd r = e.rounds.get(i);
                LinearLayout row = tableRow(group % 2 == 1, false);
                TextView name = cell(i == 0 ? WcaFormat.eventName(e.id) : "", 150, false, Gravity.START);
                row.addView(name);
                row.addView(cell(roundTitle(i, e.rounds.size()), 112, false, Gravity.START));
                row.addView(cell(formatText(r), 116, false, Gravity.START));
                row.addView(cell(limitText(e.id, r), 116, false, Gravity.START));
                row.addView(cell(cutoffText(e.id, r), 100, false, Gravity.START));
                row.addView(cell(proceedText(e.id, r, i == e.rounds.size() - 1), 120, false, Gravity.START));
                table.addView(row);
            }
            group++;
        }
        content.addView(tableCard(table), ui.matchWrap(0, 4, 0, 4));
        content.addView(ui.text("Ao5 = average of 5, Mo3 = mean of 3, Bo = best of. A format like \"Bo2 / Ao5\" "
                + "means a cutoff round: beat the cutoff within the first attempts to get the rest.",
                12, false, 0.75f), ui.matchWrap(4, 4, 4, 6));
    }

    // ---------------------------------------------------------------- Schedule tab

    private static String dayKey(long t, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(t));
    }

    private static String dayLabel(long t, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("EEEE, MMM d", Locale.ENGLISH);
        f.setTimeZone(tz);
        return f.format(new Date(t));
    }

    private static String clock(long t, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        f.setTimeZone(tz);
        return f.format(new Date(t));
    }

    private static int minutesOfDay(long t, TimeZone tz) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    private static String evOf(String code) {
        return code.contains("-") ? code.substring(0, code.indexOf('-')) : code;
    }

    private static String hourLabel(int h) {
        int hh = h % 24;
        String suffix = hh < 12 ? "am" : "pm";
        int n = hh % 12 == 0 ? 12 : hh % 12;
        return n + suffix;
    }

    /** The time grid of the calendar view: hour lines, hour labels and blocks placed by time. */
    private static class CalView extends ViewGroup {
        private final int gutter, hourPx, startHour, endHour, minBlock;
        private final Paint line = new Paint();
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<int[]> geo = new ArrayList<>();

        CalView(Context c, int gutterPx, int hourPx, int startHour, int endHour, int textColor, float textPx) {
            super(c);
            this.gutter = gutterPx;
            this.hourPx = hourPx;
            this.startHour = startHour;
            this.endHour = endHour;
            this.minBlock = hourPx / 3;
            line.setColor((textColor & 0x00FFFFFF) | 0x30000000);
            line.setStrokeWidth(Math.max(1f, textPx / 12f));
            label.setColor(textColor);
            label.setTextSize(textPx);
            label.setTextAlign(Paint.Align.RIGHT);
            setWillNotDraw(false);
        }

        void addBlock(View v, int topMin, int durMin, int col, int cols) {
            geo.add(new int[]{topMin, durMin, col, cols});
            addView(v);
        }

        private int blockTop(int[] g) {
            return Math.round((g[0] - startHour * 60) * hourPx / 60f);
        }

        private int blockHeight(int[] g) {
            return Math.max(minBlock, Math.round(g[1] * hourPx / 60f)) - 2;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = Math.max(1, (endHour - startHour)) * hourPx;
            for (int i = 0; i < getChildCount(); i++) {
                int[] g = geo.get(i);
                int cw = (w - gutter) / Math.max(1, g[3]) - 2;
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(Math.max(1, cw), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(blockHeight(g), MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            for (int i = 0; i < getChildCount(); i++) {
                int[] g = geo.get(i);
                int cw = (w - gutter) / Math.max(1, g[3]);
                View v = getChildAt(i);
                int left = gutter + g[2] * cw + 1;
                int top = blockTop(g) + 1;
                v.layout(left, top, left + v.getMeasuredWidth(), top + v.getMeasuredHeight());
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int h = startHour; h <= endHour; h++) {
                float y = (h - startHour) * hourPx;
                canvas.drawLine(gutter, y, getWidth(), y, line);
                if (h < endHour) canvas.drawText(hourLabel(h), gutter - label.getTextSize() * 0.5f,
                        y + label.getTextSize() * 1.3f, label);
            }
        }
    }

    private void renderSchedule() {
        final int my = tab;
        final Wcif w = WCIF.get(compId);
        if (w == null) {
            loadingText("Loading schedule...");
            ensureWcif(err -> {
                if (my != tab) return;
                if (err != null) errorBox(err);
                else renderSchedule();
            });
            return;
        }
        content.removeAllViews();
        if (w.acts.isEmpty()) {
            content.addView(ui.text("No schedule has been published for this competition yet.", 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }
        final Set<String> evIds = new HashSet<>();
        for (Ev e : w.events) evIds.add(e.id);

        // Days, in the competition's own time zone.
        TreeSet<String> dayKeys = new TreeSet<>();
        Map<String, String> dayNames = new HashMap<>();
        for (Act a : w.acts) {
            String k = dayKey(a.start, a.tz);
            dayKeys.add(k);
            if (!dayNames.containsKey(k)) dayNames.put(k, dayLabel(a.start, a.tz));
        }
        final List<String> days = new ArrayList<>(dayKeys);
        if (schedDay < 0 || schedDay >= days.size()) {
            schedDay = 0;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            for (int i = 0; i < days.size(); i++) if (days.get(i).equals(today)) schedDay = i;
        }

        // Calendar / Table
        LinearLayout viewRow = new LinearLayout(ctx);
        viewRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] viewNames = {"Calendar", "Table"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView p = ui.pill(viewNames[i], schedView == i);
            p.setGravity(Gravity.CENTER);
            p.setOnClickListener(v -> {
                schedView = idx;
                renderSchedule();
            });
            LinearLayout.LayoutParams lp = ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, i == 0 ? 8 : 0, 0);
            lp.weight = 1f;
            viewRow.addView(p, lp);
        }
        content.addView(viewRow, ui.matchWrap(0, 0, 0, 8));

        if (days.size() > 1) {
            LinearLayout dayRow = new LinearLayout(ctx);
            dayRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < days.size(); i++) {
                final int idx = i;
                String lbl = dayNames.get(days.get(i));
                TextView p = ui.pill(lbl == null ? days.get(i)
                        : lbl.replaceFirst(",.*", "") + " " + lbl.replaceAll("^[^,]*, ", ""), i == schedDay);
                p.setOnClickListener(v -> {
                    schedDay = idx;
                    renderSchedule();
                });
                dayRow.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            }
            HorizontalScrollView ds = new HorizontalScrollView(ctx);
            ds.setHorizontalScrollBarEnabled(false);
            ds.addView(dayRow);
            content.addView(ds, ui.matchWrap(0, 0, 0, 6));
        }

        if (w.rooms.size() > 1) {
            LinearLayout roomRow = new LinearLayout(ctx);
            roomRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView all = ui.pill("All rooms", schedRoom == null);
            all.setOnClickListener(v -> {
                schedRoom = null;
                renderSchedule();
            });
            roomRow.addView(all, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            for (final String room : w.rooms) {
                TextView p = ui.pill(room, room.equals(schedRoom));
                p.setOnClickListener(v -> {
                    schedRoom = room;
                    renderSchedule();
                });
                roomRow.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            }
            HorizontalScrollView rs = new HorizontalScrollView(ctx);
            rs.setHorizontalScrollBarEnabled(false);
            rs.addView(roomRow);
            content.addView(rs, ui.matchWrap(0, 0, 0, 6));
        }

        // Events filter: All / Clear and one toggle per event (other activities always show).
        if (!w.events.isEmpty()) {
            LinearLayout filterRow = new LinearLayout(ctx);
            filterRow.setOrientation(LinearLayout.HORIZONTAL);
            filterRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView allEv = ui.pill("All", schedOff.isEmpty());
            allEv.setOnClickListener(v -> {
                schedOff.clear();
                renderSchedule();
            });
            filterRow.addView(allEv, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 6, 0));
            TextView none = ui.pill("Clear", schedOff.size() >= evIds.size());
            none.setOnClickListener(v -> {
                schedOff.addAll(evIds);
                renderSchedule();
            });
            filterRow.addView(none, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 10, 0));
            for (Ev e : w.events) {
                final String id = e.id;
                boolean on = !schedOff.contains(id);
                Drawable ic = ui.eventIcon(id, 24, ui.safe);
                if (ic == null) continue;
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(ic);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                iv.setContentDescription(WcaFormat.eventName(id));
                if (on) iv.setBackground(ui.rounded(ui.highlight, ui.dp(8)));
                iv.setAlpha(on ? 1f : 0.4f);
                iv.setOnClickListener(v -> {
                    if (schedOff.contains(id)) schedOff.remove(id);
                    else schedOff.add(id);
                    renderSchedule();
                });
                filterRow.addView(iv, ui.lp(ui.dp(40), ui.dp(40), 0, 0, 4, 0));
            }
            HorizontalScrollView fs = new HorizontalScrollView(ctx);
            fs.setHorizontalScrollBarEnabled(false);
            fs.addView(filterRow);
            LinearLayout fcard = ui.card(false);
            fcard.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
            fcard.addView(fs);
            content.addView(fcard, ui.matchWrap(0, 0, 0, 8));
        }

        String dayWanted = days.get(schedDay);
        List<Act> shownActs = new ArrayList<>();
        TimeZone tzForDay = null;
        for (Act a : w.acts) {
            if (!dayWanted.equals(dayKey(a.start, a.tz))) continue;
            if (schedRoom != null && !schedRoom.equals(a.room)) continue;
            String ev = evOf(a.code);
            if (evIds.contains(ev) && schedOff.contains(ev)) continue;
            if (tzForDay == null) tzForDay = a.tz;
            shownActs.add(a);
        }
        if (shownActs.isEmpty()) {
            content.addView(ui.text("Nothing matches these filters on this day.", 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }
        content.addView(ui.text(dayNames.get(dayWanted) + "  \u00B7  times in "
                + tzForDay.getID().replace('_', ' '), 13, true, 0.85f), ui.matchWrap(4, 0, 4, 6));
        if (schedView == 0) buildCalendar(shownActs, w.rooms.size() > 1);
        else buildTable(shownActs, w.rooms.size() > 1, w.venues > 1);
    }

    private int blockColor(Act a) {
        if (a.code.startsWith("other")) return 0xFF666666;
        try {
            if (a.roomColor != null && a.roomColor.startsWith("#")) return Color.parseColor(a.roomColor);
        } catch (Exception ignored) {
        }
        return 0xFF2F4A96;
    }

    private void buildCalendar(List<Act> acts, boolean showRoom) {
        List<Act> list = new ArrayList<>(acts);
        Collections.sort(list, (x, y) -> x.start != y.start ? Long.compare(x.start, y.start) : Long.compare(y.end, x.end));
        int minMin = Integer.MAX_VALUE, maxMin = 0;
        for (Act a : list) {
            int s0 = minutesOfDay(a.start, a.tz);
            int dur = (int) Math.max(15, (a.end - a.start) / 60000L);
            minMin = Math.min(minMin, s0);
            maxMin = Math.max(maxMin, s0 + dur);
        }
        int startHour = minMin / 60;
        int endHour = Math.min(48, (maxMin + 59) / 60);
        if (endHour <= startHour) endHour = startHour + 1;
        CalView cal = new CalView(ctx, ui.dp(46), ui.dp(64), startHour, endHour, ui.safe, ui.dp(11));

        int i = 0;
        while (i < list.size()) {
            // A cluster is a run of activities that overlap in time; they share the width side by side.
            List<Act> cluster = new ArrayList<>();
            cluster.add(list.get(i));
            long clusterEnd = list.get(i).end;
            int j = i + 1;
            while (j < list.size() && list.get(j).start < clusterEnd) {
                cluster.add(list.get(j));
                clusterEnd = Math.max(clusterEnd, list.get(j).end);
                j++;
            }
            List<Long> colEnds = new ArrayList<>();
            int[] colOf = new int[cluster.size()];
            for (int k = 0; k < cluster.size(); k++) {
                Act a = cluster.get(k);
                int c = -1;
                for (int m = 0; m < colEnds.size(); m++) {
                    if (colEnds.get(m) <= a.start) {
                        c = m;
                        break;
                    }
                }
                if (c < 0) {
                    colEnds.add(a.end);
                    c = colEnds.size() - 1;
                } else {
                    colEnds.set(c, a.end);
                }
                colOf[k] = c;
            }
            for (int k = 0; k < cluster.size(); k++) {
                Act a = cluster.get(k);
                int dur = (int) Math.max(15, (a.end - a.start) / 60000L);
                TextView t = new TextView(ctx);
                t.setTextColor(Color.WHITE);
                t.setTextSize(12);
                t.setPadding(ui.dp(6), ui.dp(3), ui.dp(4), ui.dp(2));
                t.setBackground(ui.rounded(blockColor(a), ui.dp(4)));
                t.setOverScrollMode(View.OVER_SCROLL_NEVER);
                String when = clock(a.start, a.tz).replace(" AM", "").replace(" PM", "") + " - "
                        + clock(a.end, a.tz).replace(" AM", "").replace(" PM", "");
                String room = showRoom && !a.room.isEmpty() ? "  \u00B7  " + a.room : "";
                if (dur <= 30) t.setText(when + "  " + a.name);
                else t.setText(when + room + "\n" + a.name);
                cal.addBlock(t, minutesOfDay(a.start, a.tz), dur, colOf[k], colEnds.size());
            }
            i = j;
        }
        LinearLayout card = ui.card(false);
        card.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        card.addView(cal, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(card, ui.matchWrap(0, 0, 0, 4));
    }

    private void buildTable(List<Act> acts, boolean showRoom, boolean showVenue) {
        LinearLayout card = ui.card(false);
        card.setPadding(0, ui.dp(4), 0, ui.dp(4));
        for (int i = 0; i < acts.size(); i++) addActivityRow(card, acts.get(i), showRoom, showVenue, i % 2 == 1);
        content.addView(card, ui.matchWrap(0, 0, 0, 4));
    }

    private void addActivityRow(LinearLayout card, Act a, boolean showRoom, boolean showVenue, boolean shaded) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        if (shaded) row.setBackgroundColor(ColorUtils.blendARGB(ui.itemBg, ui.safe, 0.07f));
        row.addView(tv(clock(a.start, a.tz) + " - " + clock(a.end, a.tz), 12, false, 0.7f));
        TextView name = tv(a.name, 15, true, 1f);
        ui.setEventIcon(name, evOf(a.code), 20, ui.safe);
        row.addView(name);
        if (showRoom && !a.room.isEmpty()) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            View d = new View(ctx);
            d.setBackground(ui.rounded(blockColor(a), ui.dp(5)));
            line.addView(d, ui.lp(ui.dp(10), ui.dp(10), 0, 0, 6, 0));
            line.addView(tv(a.room + (showVenue && !a.venue.isEmpty() ? "  \u00B7  " + a.venue : ""), 12, false, 0.75f));
            row.addView(line, ui.matchWrap(0, 2, 0, 0));
        }
        card.addView(row, ui.matchWrap(0, 0, 0, 0));
    }

    // ---------------------------------------------------------------- Results tab

    private String resEvent = null;
    private String resRound = null;

    private static int roundRank(String t) {
        switch (t) {
            case "0": case "h": return 0;
            case "1": case "d": return 1;
            case "2": case "e": return 2;
            case "3": case "g": return 3;
            case "b": return 4;
            default: return 5;
        }
    }

    private static String roundName(String t) {
        switch (t) {
            case "0": case "h": return "Qualification";
            case "1": case "d": return "First round";
            case "2": case "e": return "Second round";
            case "3": case "g": return "Semi final";
            case "b": return "B final";
            default: return "Final";
        }
    }

    private void renderResultsTab() {
        final int my = tab;
        JSONArray all = RESULTS.get(compId);
        if (all == null) {
            loadingText("Loading results...");
            ensureResults(err -> {
                if (my != tab) return;
                if (err != null) errorBox(err);
                else renderResultsTab();
            });
            return;
        }
        content.removeAllViews();
        // event -> round type -> rows
        Map<String, Map<String, List<JSONObject>>> byEvent = new HashMap<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r == null) continue;
            String ev = r.optString("event_id");
            String rt = r.optString("round_type_id");
            Map<String, List<JSONObject>> rounds = byEvent.get(ev);
            if (rounds == null) {
                rounds = new HashMap<>();
                byEvent.put(ev, rounds);
            }
            List<JSONObject> l = rounds.get(rt);
            if (l == null) {
                l = new ArrayList<>();
                rounds.put(rt, l);
            }
            l.add(r);
        }
        if (byEvent.isEmpty()) {
            content.addView(ui.text("No results yet. The WCA posts the results after the competition ends"
                    + (openLive != null ? "; until then see the live results." : "."), 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }
        final List<String> evs = new ArrayList<>(byEvent.keySet());
        Collections.sort(evs, (a, b) -> WcaFormat.eventOrder(a) - WcaFormat.eventOrder(b));
        if (resEvent == null || !byEvent.containsKey(resEvent)) {
            resEvent = evs.get(0);
            resRound = null;
        }
        final Map<String, List<JSONObject>> rounds = byEvent.get(resEvent);
        final List<String> rts = new ArrayList<>(rounds.keySet());
        Collections.sort(rts, (a, b) -> roundRank(a) - roundRank(b));
        if (resRound == null || !rounds.containsKey(resRound)) resRound = rts.get(rts.size() - 1);

        // Event picker
        LinearLayout pickCard = ui.card(false);
        pickCard.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        HorizontalScrollView pickScroll = new HorizontalScrollView(ctx);
        pickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pick = new LinearLayout(ctx);
        pick.setOrientation(LinearLayout.HORIZONTAL);
        pickScroll.addView(pick);
        for (final String id : evs) {
            boolean sel = id.equals(resEvent);
            View item;
            Drawable ic = ui.eventIcon(id, 28, ui.safe);
            if (ic != null) {
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(ic);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                iv.setContentDescription(WcaFormat.eventName(id));
                item = iv;
            } else {
                item = ui.pill(WcaFormat.eventName(id), sel);
            }
            if (sel) item.setBackground(ui.rounded(ui.highlight, ui.dp(8)));
            item.setAlpha(sel ? 1f : 0.45f);
            item.setOnClickListener(v -> {
                resEvent = id;
                resRound = null;
                shown = ROWS_PER_PAGE;
                renderResultsTab();
            });
            pick.addView(item, ui.lp(ui.dp(44), ui.dp(44), 0, 0, 4, 0));
        }
        pickCard.addView(pickScroll);
        content.addView(pickCard, ui.matchWrap(0, 4, 0, 6));

        TextView title = tv(WcaFormat.eventName(resEvent), 15, true, 1f);
        ui.setEventIcon(title, resEvent, 20, ui.text);
        title.setTextColor(ui.text);
        content.addView(title, ui.matchWrap(4, 2, 4, 4));

        if (rts.size() > 1) {
            LinearLayout roundRow = new LinearLayout(ctx);
            roundRow.setOrientation(LinearLayout.HORIZONTAL);
            for (final String rt : rts) {
                TextView p = ui.pill(roundName(rt), rt.equals(resRound));
                p.setOnClickListener(v -> {
                    resRound = rt;
                    shown = ROWS_PER_PAGE;
                    renderResultsTab();
                });
                roundRow.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            }
            HorizontalScrollView rs = new HorizontalScrollView(ctx);
            rs.setHorizontalScrollBarEnabled(false);
            rs.addView(roundRow);
            content.addView(rs, ui.matchWrap(0, 0, 0, 6));
        }

        final List<JSONObject> rows = new ArrayList<>(rounds.get(resRound));
        Collections.sort(rows, (a, b) -> {
            int pa = a.optInt("pos", 9999), pb = b.optInt("pos", 9999);
            return pa - pb;
        });
        int maxAttempts = 0;
        for (JSONObject r : rows) {
            JSONArray at = r.optJSONArray("attempts");
            if (at != null) maxAttempts = Math.max(maxAttempts, at.length());
        }
        final boolean noAvg = noAverage(resEvent);

        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = tableRow(false, true);
        header.addView(cell("#", 36, true, Gravity.START));
        header.addView(cell("Name", 200, true, Gravity.START));
        header.addView(cell("Best", 120, true, Gravity.START));
        if (!noAvg) header.addView(cell("Average", 120, true, Gravity.START));
        header.addView(cell("Representing", 150, true, Gravity.START));
        for (int k = 0; k < maxAttempts; k++) header.addView(cell("Solve " + (k + 1), 84, true, Gravity.END));
        table.addView(header);

        int limit = Math.min(shown, rows.size());
        for (int i = 0; i < limit; i++) {
            JSONObject r = rows.get(i);
            final String wcaId = r.isNull("wca_id") ? "" : r.optString("wca_id");
            LinearLayout row = tableRow(i % 2 == 1, false);
            row.addView(cell(r.optInt("pos") > 0 ? String.valueOf(r.optInt("pos")) : "-", 36, false, Gravity.START));
            TextView nm = cell(r.optString("name"), 200, false, Gravity.START);
            if (!wcaId.isEmpty()) nm.setTextColor(LINK);
            row.addView(nm);
            String bt = WcaFormat.formatResult(r.optLong("best"), resEvent, false);
            String bTag = regionTag(r, "regional_single_record");
            row.addView(resCell(bt, bTag));
            if (!noAvg) {
                String av = WcaFormat.formatResult(r.optLong("average"), resEvent, true);
                String aTag = regionTag(r, "regional_average_record");
                row.addView(resCell(av, av.isEmpty() ? "" : aTag));
            }
            String iso = WcaFlags.code(r.optString("country_iso2"));
            row.addView(cell(withFlag(countryName(iso), iso), 150, false, Gravity.START));
            JSONArray at = r.optJSONArray("attempts");
            for (int k = 0; k < maxAttempts; k++) {
                long v = at != null && k < at.length() ? at.optLong(k) : 0;
                row.addView(cell(WcaFormat.formatResult(v, resEvent, false), 84, false, Gravity.END));
            }
            if (!wcaId.isEmpty()) row.setOnClickListener(v -> {
                if (opener != null) opener.open(wcaId);
            });
            table.addView(row);
        }
        content.addView(tableCard(table), ui.matchWrap(0, 4, 0, 4));
        addShowMore(rows.size(), limit, this::renderResultsTab);
    }

    // ---------------------------------------------------------------- Podiums tab

    /** A result with its colour and record tag on the side, in a fixed-width column. */
    private LinearLayout resCell(String value, String tag) {
        LinearLayout c = ui.resultCell("", value, tag, 14, true, true);
        c.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private static String regionTag(JSONObject r, String key) {
        if (!r.has(key) || r.isNull(key)) return "";
        String v = r.optString(key, "").trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return "";
        String u = v.toUpperCase(Locale.US);
        if (u.equals("WR") || u.equals("NR") || u.equals("CR")) return u;
        if (u.endsWith("R") && u.length() <= 3) return v;
        return "";
    }

    private void renderPodiums() {
        final int my = tab;
        JSONArray all = RESULTS.get(compId);
        if (all == null) {
            loadingText("Loading podiums...");
            ensureResults(err -> {
                if (my != tab) return;
                if (err != null) errorBox(err);
                else renderPodiums();
            });
            return;
        }
        content.removeAllViews();
        Map<String, List<JSONObject>> byEvent = new HashMap<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r == null) continue;
            String rt = r.optString("round_type_id");
            if (!"c".equals(rt) && !"f".equals(rt)) continue;
            int pos = r.optInt("pos", 0);
            if (pos < 1 || pos > 3) continue;
            if (r.optLong("best") <= 0 && r.optLong("average") <= 0) continue;
            String ev = r.optString("event_id");
            List<JSONObject> l = byEvent.get(ev);
            if (l == null) {
                l = new ArrayList<>();
                byEvent.put(ev, l);
            }
            l.add(r);
        }
        if (byEvent.isEmpty()) {
            content.addView(ui.text("No podiums yet. The WCA posts the results after the competition ends"
                    + (openLive != null ? "; until then see the live results." : "."), 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }
        List<String> evs = new ArrayList<>(byEvent.keySet());
        Collections.sort(evs, (a, b) -> WcaFormat.eventOrder(a) - WcaFormat.eventOrder(b));
        for (String ev : evs) {
            List<JSONObject> rows = byEvent.get(ev);
            Collections.sort(rows, (a, b) -> a.optInt("pos") - b.optInt("pos"));
            LinearLayout card = ui.card(false);
            TextView title = tv(WcaFormat.eventName(ev), 16, true, 1f);
            ui.setEventIcon(title, ev, 22, ui.safe);
            card.addView(title, ui.matchWrap(0, 0, 0, 6));
            for (JSONObject r : rows) {
                card.addView(podiumRow(r, ev), ui.matchWrap(0, 4, 0, 4));
            }
            content.addView(card, ui.matchWrap(0, 4, 0, 4));
        }
    }

    private View podiumRow(JSONObject r, String ev) {
        final String wcaId = r.isNull("wca_id") ? "" : r.optString("wca_id");
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int pos = r.optInt("pos");
        TextView p = new TextView(ctx);
        p.setText(String.valueOf(pos));
        p.setTextSize(15);
        p.setTextColor(Color.BLACK);
        p.setTag("badge");
        p.setTypeface(p.getTypeface(), android.graphics.Typeface.BOLD);
        p.setGravity(Gravity.CENTER);
        int medal = pos == 1 ? 0xFFFFD54F : (pos == 2 ? 0xFFCFD8DC : 0xFFD7A072);
        p.setBackground(ui.rounded(medal, ui.dp(16)));
        line.addView(p, ui.lp(ui.dp(32), ui.dp(32), 0, 0, 12, 0));

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(tv(withFlag(r.optString("name"), r.optString("country_iso2")), 15, true, 1f));
        String sub = countryName(WcaFlags.code(r.optString("country_iso2")));
        if (!sub.isEmpty()) mid.addView(tv(sub, 12, false, 0.7f));
        line.addView(mid, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
        ((LinearLayout.LayoutParams) mid.getLayoutParams()).weight = 1f;

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        long avg = r.optLong("average");
        long best = r.optLong("best");
        boolean showAvg = avg > 0 && !noAverage(ev);
        if (showAvg) {
            right.addView(ui.resultCell("avg", WcaFormat.formatResult(avg, ev, true),
                    regionTag(r, "regional_average_record"), 16, true));
        }
        if (best > 0) {
            right.addView(ui.resultCell(showAvg ? "best" : "", WcaFormat.formatResult(best, ev, false),
                    regionTag(r, "regional_single_record"), showAvg ? 13 : 16, !showAvg));
        }
        line.addView(right);
        if (!wcaId.isEmpty()) {
            line.setOnClickListener(v -> {
                if (opener != null) opener.open(wcaId);
            });
        }
        return line;
    }
}
