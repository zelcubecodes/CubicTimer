package com.hatopigeon.cubictimer.wca;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Looks up a competitor's name and country from their WCA ID, and remembers the answers. */
public final class WcaNames {
    public interface Listener {
        void onName(String wcaId, String name, String iso2);
    }

    private static final String WCA = "https://www.worldcubeassociation.org/api/v0";
    private static final String STATIC_API =
            "https://raw.githubusercontent.com/robiningelbrecht/wca-rest-api/refs/heads/v1";
    private static final Map<String, String[]> CACHE = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static android.content.SharedPreferences disk = null;

    private WcaNames() {}

    /** Call once with any context so names that were found before are remembered between launches. */
    public static synchronized void init(android.content.Context c) {
        if (disk == null && c != null) {
            disk = c.getApplicationContext().getSharedPreferences("wca_names", android.content.Context.MODE_PRIVATE);
        }
    }

    private static synchronized void remember(String wcaId, String name, String iso) {
        CACHE.put(wcaId, new String[]{name, iso});
        if (disk != null) disk.edit().putString(wcaId, name + "\u0001" + iso).apply();
    }

    /** Returns {name, iso2} if already known, else null. */
    public static synchronized String[] cached(String wcaId) {
        String[] v = CACHE.get(wcaId);
        if (v != null) return v;
        if (disk != null) {
            String saved = disk.getString(wcaId, null);
            if (saved != null) {
                int i = saved.indexOf('\u0001');
                if (i > 0) {
                    v = new String[]{saved.substring(0, i), saved.substring(i + 1)};
                    CACHE.put(wcaId, v);
                    return v;
                }
            }
        }
        return null;
    }

    public interface CountryListener {
        void onCountry(String compId, String iso2);
    }

    private static final Map<String, String> COMP_COUNTRY = new HashMap<>();
    private static final Set<String> COMP_PENDING = new HashSet<>();

    /** Looks up the country (ISO code) a competition is held in, from the WCA website. */
    public static void lookupCompCountry(final String compId, final CountryListener l) {
        synchronized (WcaNames.class) {
            String known = COMP_COUNTRY.get(compId);
            if (known != null) {
                l.onCountry(compId, known);
                return;
            }
            if (!COMP_PENDING.add(compId)) return;
        }
        WcaHttp.get(WCA + "/competitions/" + compId, new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                String iso = "";
                try {
                    iso = new JSONObject(body).optString("country_iso2", "");
                } catch (Exception ignored) {
                }
                synchronized (WcaNames.class) {
                    COMP_COUNTRY.put(compId, iso);
                    COMP_PENDING.remove(compId);
                }
                l.onCountry(compId, iso);
            }

            @Override
            public void onError(String message) {
                synchronized (WcaNames.class) { COMP_PENDING.remove(compId); }
            }
        });
    }

    /** Looks up many competitors with one request; any the batch misses are looked up one by one. */
    public static void lookupBatch(final java.util.List<String> ids, final Listener l) {
        final java.util.List<String> todo = new java.util.ArrayList<>();
        for (String id : ids) {
            String[] known = cached(id);
            if (known != null) l.onName(id, known[0], known[1]);
            else if (!todo.contains(id)) todo.add(id);
        }
        if (todo.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String id : todo) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        WcaHttp.get(WCA + "/persons?wca_ids=" + sb, new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    org.json.JSONArray arr;
                    String t = body.trim();
                    if (t.startsWith("[")) {
                        arr = new org.json.JSONArray(t);
                    } else {
                        JSONObject o = new JSONObject(t);
                        arr = o.optJSONArray("result");
                        if (arr == null) arr = o.optJSONArray("persons");
                    }
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject e = arr.getJSONObject(i);
                            JSONObject p = e.optJSONObject("person") != null ? e.optJSONObject("person") : e;
                            String id = p.optString("wca_id", p.optString("id", ""));
                            String name = p.optString("name", "");
                            if (id.isEmpty() || name.isEmpty()) continue;
                            String[] v = {name, p.optString("country_iso2", "")};
                            remember(id, v[0], v[1]);
                            l.onName(id, v[0], v[1]);
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to the one-by-one lookups below
                }
                lookupOneByOne(todo, l);
            }

            @Override
            public void onError(String message) {
                lookupOneByOne(todo, l);
            }
        });
    }

    /** The WCA website refuses a burst of requests, so the leftovers are asked for a little apart. */
    private static void lookupOneByOne(final java.util.List<String> ids, final Listener l) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        int n = 0;
        for (final String id : ids) {
            if (cached(id) != null) continue;
            h.postDelayed(() -> lookup(id, l, 0), 300L * n++);
        }
    }

    /** Calls the listener on the main thread, straight away if the name is already known. */
    public static void lookup(final String wcaId, final Listener l) {
        lookup(wcaId, l, 0);
    }

    private static void lookup(final String wcaId, final Listener l, final int attempt) {
        String[] known = cached(wcaId);
        if (known != null) {
            l.onName(wcaId, known[0], known[1]);
            return;
        }
        synchronized (WcaNames.class) {
            if (!PENDING.add(wcaId)) {
                // already on its way; check again shortly
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    String[] k = cached(wcaId);
                    if (k != null) l.onName(wcaId, k[0], k[1]);
                    else if (attempt < 8) lookup(wcaId, l, attempt + 1);
                }, 900);
                return;
            }
        }
        WcaHttp.get(WCA + "/persons/" + wcaId, new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject p = new JSONObject(body).getJSONObject("person");
                    String[] v = {p.optString("name", wcaId), p.optString("country_iso2", "")};
                    if (v[0].isEmpty()) throw new Exception("no name");
                    remember(wcaId, v[0], v[1]);
                    synchronized (WcaNames.class) { PENDING.remove(wcaId); }
                    l.onName(wcaId, v[0], v[1]);
                } catch (Exception e) {
                    retry();
                }
            }

            @Override
            public void onError(String message) {
                retry();
            }

            // The WCA site sometimes refuses a burst of requests, so try again a little later.
            private void retry() {
                synchronized (WcaNames.class) { PENDING.remove(wcaId); }
                if (attempt == 1) lookupStatic(wcaId, l); // a second source, in case the site is busy
                if (attempt >= 5) return;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> lookup(wcaId, l, attempt + 1), 2000L * (attempt + 1));
            }
        });
    }

    /** Fallback source: the static ranking data, which has no request limit. Silent when it fails. */
    private static void lookupStatic(final String wcaId, final Listener l) {
        WcaHttp.get(STATIC_API + "/persons/" + wcaId + ".json", new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject o = new JSONObject(body);
                    JSONObject p = o.optJSONObject("person") != null ? o.optJSONObject("person") : o;
                    String name = p.optString("name", "");
                    Object c = p.opt("country");
                    String iso = "";
                    if (c instanceof JSONObject) {
                        JSONObject cj = (JSONObject) c;
                        iso = WcaFlags.code(cj.optString("iso2", cj.optString("id", cj.optString("name", ""))));
                    } else if (c != null) {
                        iso = WcaFlags.code(String.valueOf(c));
                    }
                    if (iso.isEmpty()) iso = WcaFlags.code(p.optString("country_iso2", p.optString("countryIso2", "")));
                    if (name.isEmpty() || cached(wcaId) != null) return;
                    remember(wcaId, name, iso);
                    l.onName(wcaId, name, iso);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onError(String message) {
            }
        });
    }
}
