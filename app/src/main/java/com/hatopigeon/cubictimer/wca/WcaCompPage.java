package com.hatopigeon.cubictimer.wca;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
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
    private static final String[] TABS = {"Info", "Competitors", "Events", "Schedule", "Podiums"};

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

    public WcaCompPage(Context ctx, String compId, String compName, PersonOpener opener, Runnable openLive) {
        this.ctx = ctx;
        this.ui = new WcaUi(ctx);
        this.compId = compId;
        this.compName = compName;
        this.opener = opener;
        this.openLive = openLive;
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
            default: renderPodiums(); break;
        }
    }

    // ---------------------------------------------------------------- small helpers

    private TextView tv(String s, float sp, boolean bold, float alpha) {
        TextView t = ui.text(s, sp, bold, alpha);
        t.setTextColor(ui.itemText);
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
        if (shaded) row.setBackgroundColor(ColorUtils.blendARGB(ui.itemBg, ui.itemText, 0.07f));
        return row;
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
            d = ui.eventIcon(eventId, 20, ui.itemText);
            iconCache.put(eventId, d);
        }
        return d;
    }

    private static String countryName(String iso) {
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
        String t = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
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

    private static String dates(String start, String end) {
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

    private void ensureInfo(final Done done) {
        if (INFO.containsKey(compId)) {
            done.run(null);
            return;
        }
        WcaHttp.get(WCA + "/competitions/" + Uri.encode(compId), new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    INFO.put(compId, new JSONObject(body));
                    done.run(null);
                } catch (Exception e) {
                    done.run("Could not read the competition details");
                }
            }

            @Override
            public void onError(String message) {
                done.run(WcaHttp.describe(ctx, message));
            }
        });
    }

    private void ensureWcif(final Done done) {
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
        final int my = token;
        JSONObject cached = INFO.get(compId);
        if (cached == null) {
            loadingText("Loading competition...");
            ensureInfo(err -> {
                if (my != token) return;
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
                Drawable ic = ui.eventIcon(id, 28, ui.itemText);
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
            if (err != null || my != token) return;
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

        IconStrip(Context c, List<Drawable> icons, int slotPx, int sizePx) {
            super(c);
            this.icons = icons;
            this.slot = slotPx;
            this.size = sizePx;
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

    private void renderCompetitors() {
        final int my = token;
        final Wcif w = WCIF.get(compId);
        if (w == null) {
            loadingText("Loading competitors...");
            ensureWcif(err -> {
                if (my != token) return;
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
            Drawable ic = ui.eventIcon(id, 28, ui.itemText);
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

    private void addShowMore(int total, int limit) {
        if (limit >= total) return;
        TextView more = ui.pill("Show more (" + (total - limit) + " left)", false);
        more.setOnClickListener(v -> {
            shown += ROWS_PER_PAGE;
            renderCompetitors();
        });
        content.addView(more, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));
    }

    private void openPerson(Per p) {
        if (opener != null && !p.wcaId.isEmpty()) opener.open(p.wcaId);
    }

    private void renderCompetitorTable(Wcif w) {
        List<Per> list = new ArrayList<>(w.persons);
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        content.addView(ui.text(list.size() + " competitors, in alphabetical order. Tap an event above for its psych sheet.",
                13, false, 0.8f), ui.matchWrap(4, 0, 4, 6));

        final int slot = ui.dp(26), size = ui.dp(20);
        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = tableRow(false, true);
        header.addView(cell("Competitor", 176, true, Gravity.START));
        header.addView(cell("Representing", 128, true, Gravity.START));
        List<Drawable> all = new ArrayList<>();
        for (Ev e : w.events) all.add(icon(e.id));
        header.addView(new IconStrip(ctx, all, slot, size));
        table.addView(header);

        int limit = Math.min(shown, list.size());
        for (int i = 0; i < limit; i++) {
            final Per p = list.get(i);
            LinearLayout row = tableRow(i % 2 == 1, false);
            row.addView(cell(withFlag(p.name, p.iso), 176, false, Gravity.START));
            row.addView(cell(countryName(p.iso), 128, false, Gravity.START));
            List<Drawable> mine = new ArrayList<>();
            for (Ev e : w.events) mine.add(p.events.contains(e.id) ? icon(e.id) : null);
            row.addView(new IconStrip(ctx, mine, slot, size));
            if (!p.wcaId.isEmpty()) row.setOnClickListener(v -> openPerson(p));
            table.addView(row);
        }
        content.addView(tableCard(table), ui.matchWrap(0, 4, 0, 4));
        addShowMore(list.size(), limit);
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

        TextView title = tv(WcaFormat.eventName(evId) + "  \u00B7  " + list.size()
                + (list.size() == 1 ? " competitor" : " competitors"), 14, true, 1f);
        ui.setEventIcon(title, evId, 20, ui.text);
        title.setTextColor(ui.text);
        content.addView(title, ui.matchWrap(4, 2, 4, 2));
        content.addView(ui.text("Ranked by " + (avg ? "average" : "single")
                + ". Tap Single or Average to switch.", 13, false, 0.8f), ui.matchWrap(4, 0, 4, 6));
        if (list.isEmpty()) {
            content.addView(ui.text("Nobody is registered for this event.", 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }

        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = tableRow(false, true);
        header.addView(cell("#", 36, true, Gravity.START));
        header.addView(cell("Competitor", 190, true, Gravity.START));
        header.addView(cell("WR", 52, true, Gravity.END));
        TextView hs = cell(avg ? "Single" : "Single \u25BE", 86, true, Gravity.END);
        hs.setOnClickListener(v -> {
            basisAverage = false;
            basisTouched = true;
            shown = ROWS_PER_PAGE;
            renderCompetitors();
        });
        header.addView(hs);
        if (!noAvg) {
            TextView ha = cell(avg ? "Average \u25BE" : "Average", 86, true, Gravity.END);
            ha.setOnClickListener(v -> {
                basisAverage = true;
                basisTouched = true;
                shown = ROWS_PER_PAGE;
                renderCompetitors();
            });
            header.addView(ha);
            header.addView(cell("WR", 52, true, Gravity.END));
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
            row.addView(cell(withFlag(p.name, p.iso), 190, false, Gravity.START));
            row.addView(cell(pb != null && pb.singleRank > 0 ? String.valueOf(pb.singleRank) : "", 52, false, Gravity.END));
            row.addView(cell(pb != null && pb.single > 0 ? WcaFormat.formatResult(pb.single, evId, false) : "",
                    86, !avg, Gravity.END));
            if (!noAvg) {
                row.addView(cell(pb != null && pb.average > 0 ? WcaFormat.formatResult(pb.average, evId, true) : "",
                        86, avg, Gravity.END));
                row.addView(cell(pb != null && pb.averageRank > 0 ? String.valueOf(pb.averageRank) : "", 52, false, Gravity.END));
            }
            if (!p.wcaId.isEmpty()) row.setOnClickListener(x -> openPerson(p));
            table.addView(row);
        }
        content.addView(tableCard(table), ui.matchWrap(0, 4, 0, 4));
        addShowMore(list.size(), limit);
    }

    // ---------------------------------------------------------------- Events tab

    private static String formatName(String f) {
        switch (f) {
            case "a": return "Average of 5";
            case "m": return "Mean of 3";
            case "1": return "Best of 1";
            case "2": return "Best of 2";
            case "3": return "Best of 3";
            default: return f.isEmpty() ? "-" : f;
        }
    }

    private static String roundTitle(int index, int total) {
        int n = index + 1;
        if (n == total) return "Final";
        if (total >= 3 && n == total - 1) return "Semi Final";
        String[] names = {"First Round", "Second Round", "Third Round", "Fourth Round"};
        return n >= 1 && n <= names.length ? names[n - 1] : "Round " + n;
    }

    /** 10:00 or 1:00:00 when there are no centiseconds, otherwise 1:23.45. */
    private static String timeStr(long cs) {
        if (cs <= 0) return "-";
        if (cs % 100 == 0) {
            long s = cs / 100;
            long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
            if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
            if (m > 0) return String.format(Locale.US, "%d:%02d", m, sec);
            return sec + " s";
        }
        return WcaFormat.formatCentiseconds(cs);
    }

    private static String limitText(String evId, Rnd r) {
        if (r.limit <= 0) return "-";
        return timeStr(r.limit) + (r.cumulative ? " total" : "");
    }

    private static String cutoffText(String evId, Rnd r) {
        if (r.cutoffResult <= 0 || r.cutoffAttempts <= 0) return "-";
        String v;
        if ("333fm".equals(evId) || "333mbf".equals(evId)) v = WcaFormat.formatResult(r.cutoffResult, evId, false);
        else v = timeStr(r.cutoffResult);
        return v + " in " + r.cutoffAttempts + (r.cutoffAttempts == 1 ? " attempt" : " attempts");
    }

    private static String proceedText(String evId, Rnd r, boolean last) {
        if (last) return "-";
        switch (r.advType) {
            case "ranking": return "Top " + r.advLevel;
            case "percent": return "Top " + r.advLevel + "%";
            case "attemptResult": {
                String v;
                if ("333fm".equals(evId) || "333mbf".equals(evId)) v = WcaFormat.formatResult(r.advLevel, evId, false);
                else v = timeStr(r.advLevel);
                return "Better than " + v;
            }
            default: return "-";
        }
    }

    private void renderEvents() {
        final int my = token;
        final Wcif w = WCIF.get(compId);
        if (w == null) {
            loadingText("Loading events...");
            ensureWcif(err -> {
                if (my != token) return;
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
        header.addView(cell("Round", 112, true, Gravity.START));
        header.addView(cell("Format", 112, true, Gravity.START));
        header.addView(cell("Time limit", 100, true, Gravity.START));
        header.addView(cell("Cutoff", 150, true, Gravity.START));
        header.addView(cell("Proceed", 120, true, Gravity.START));
        table.addView(header);

        for (Ev e : w.events) {
            int count = 0;
            for (Per p : w.persons) if (p.events.contains(e.id)) count++;
            LinearLayout evRow = tableRow(true, false);
            TextView name = tv(WcaFormat.eventName(e.id) + "  \u00B7  " + count
                    + (count == 1 ? " competitor" : " competitors"), 14, true, 1f);
            ui.setEventIcon(name, e.id, 20, ui.itemText);
            evRow.addView(name);
            table.addView(evRow);
            for (int i = 0; i < e.rounds.size(); i++) {
                Rnd r = e.rounds.get(i);
                LinearLayout row = tableRow(false, false);
                row.addView(cell(roundTitle(i, e.rounds.size()), 112, false, Gravity.START));
                row.addView(cell(formatName(r.format), 112, false, Gravity.START));
                row.addView(cell(limitText(e.id, r), 100, false, Gravity.START));
                row.addView(cell(cutoffText(e.id, r), 150, false, Gravity.START));
                row.addView(cell(proceedText(e.id, r, i == e.rounds.size() - 1), 120, false, Gravity.START));
                table.addView(row);
            }
        }
        content.addView(tableCard(table), ui.matchWrap(0, 4, 0, 4));
        content.addView(ui.text("Time limits and cutoffs are per solve. \"Total\" means the limit is shared "
                + "across the solves of the round.", 12, false, 0.7f), ui.matchWrap(4, 4, 4, 6));
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

    private void renderSchedule() {
        final int my = token;
        final Wcif w = WCIF.get(compId);
        if (w == null) {
            loadingText("Loading schedule...");
            ensureWcif(err -> {
                if (my != token) return;
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

        if (days.size() > 1) {
            LinearLayout dayRow = new LinearLayout(ctx);
            dayRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < days.size(); i++) {
                final int idx = i;
                String lbl = dayNames.get(days.get(i));
                TextView p = ui.pill(lbl == null ? days.get(i) : lbl.replaceFirst(",.*", "") + " " + lbl.replaceAll("^[^,]*, ", ""),
                        i == schedDay);
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

        LinearLayout opts = new LinearLayout(ctx);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        if (w.rooms.size() > 1) {
            TextView all = ui.pill("All rooms", schedRoom == null);
            all.setOnClickListener(v -> {
                schedRoom = null;
                renderSchedule();
            });
            opts.addView(all, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            for (final String room : w.rooms) {
                TextView p = ui.pill(room, room.equals(schedRoom));
                p.setOnClickListener(v -> {
                    schedRoom = room;
                    renderSchedule();
                });
                opts.addView(p, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0));
            }
        }
        TextView groups = ui.pill(schedGroups ? "Hide groups" : "Show groups", schedGroups);
        groups.setOnClickListener(v -> {
            schedGroups = !schedGroups;
            renderSchedule();
        });
        opts.addView(groups);
        HorizontalScrollView os = new HorizontalScrollView(ctx);
        os.setHorizontalScrollBarEnabled(false);
        os.addView(opts);
        content.addView(os, ui.matchWrap(0, 0, 0, 8));

        String dayWanted = days.get(schedDay);
        TimeZone tzForDay = null;
        LinearLayout card = ui.card(false);
        card.setPadding(0, ui.dp(4), 0, ui.dp(4));
        int count = 0;
        for (Act a : w.acts) {
            if (!dayWanted.equals(dayKey(a.start, a.tz))) continue;
            if (schedRoom != null && !schedRoom.equals(a.room)) continue;
            if (tzForDay == null) tzForDay = a.tz;
            addActivityRow(card, a, w.rooms.size() > 1, w.venues > 1, false, count % 2 == 1);
            count++;
            if (schedGroups) {
                for (Act child : a.children) {
                    addActivityRow(card, child, w.rooms.size() > 1, false, true, count % 2 == 1);
                    count++;
                }
            }
        }
        if (count == 0) {
            content.addView(ui.text("Nothing scheduled in this room on this day.", 14, false, 0.85f),
                    ui.matchWrap(4, 8, 4, 8));
            return;
        }
        content.addView(ui.text(dayNames.get(dayWanted) + (tzForDay == null ? ""
                : "  \u00B7  times in " + tzForDay.getID().replace('_', ' ')), 13, true, 0.85f), ui.matchWrap(4, 0, 4, 6));
        content.addView(card, ui.matchWrap(0, 0, 0, 4));
    }

    private void addActivityRow(LinearLayout card, Act a, boolean showRoom, boolean showVenue,
                                boolean child, boolean shaded) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ui.dp(child ? 30 : 14), ui.dp(8), ui.dp(14), ui.dp(8));
        if (shaded) row.setBackgroundColor(ColorUtils.blendARGB(ui.itemBg, ui.itemText, 0.07f));
        row.addView(tv(clock(a.start, a.tz) + " - " + clock(a.end, a.tz), 12, false, 0.7f));
        String evCode = a.code.contains("-") ? a.code.substring(0, a.code.indexOf('-')) : a.code;
        TextView name = tv(a.name, child ? 13 : 15, !child, 1f);
        ui.setEventIcon(name, evCode, child ? 16 : 20, ui.itemText);
        row.addView(name);
        if (showRoom && !a.room.isEmpty()) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            int dot = 0xFF9E9E9E;
            try {
                if (a.roomColor != null && a.roomColor.startsWith("#")) dot = Color.parseColor(a.roomColor);
            } catch (Exception ignored) {
            }
            View d = new View(ctx);
            d.setBackground(ui.rounded(dot, ui.dp(5)));
            line.addView(d, ui.lp(ui.dp(10), ui.dp(10), 0, 0, 6, 0));
            line.addView(tv(a.room + (showVenue && !a.venue.isEmpty() ? "  \u00B7  " + a.venue : ""), 12, false, 0.75f));
            row.addView(line, ui.matchWrap(0, 2, 0, 0));
        }
        card.addView(row, ui.matchWrap(0, 0, 0, 0));
    }

    // ---------------------------------------------------------------- Podiums tab

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
        final int my = token;
        JSONArray all = RESULTS.get(compId);
        if (all == null) {
            loadingText("Loading podiums...");
            ensureResults(err -> {
                if (my != token) return;
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
            ui.setEventIcon(title, ev, 22, ui.itemText);
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
