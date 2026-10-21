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
    private static final Map<String, String[]> CACHE = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();

    private WcaNames() {}

    /** Returns {name, iso2} if already known, else null. */
    public static synchronized String[] cached(String wcaId) {
        return CACHE.get(wcaId);
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

    /** Calls the listener on the main thread, straight away if the name is already known. */
    public static void lookup(final String wcaId, final Listener l) {
        String[] known = cached(wcaId);
        if (known != null) {
            l.onName(wcaId, known[0], known[1]);
            return;
        }
        synchronized (WcaNames.class) {
            if (!PENDING.add(wcaId)) {
                // already on its way; try again shortly
                WcaHttp.background(() -> {
                    try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                }, () -> {
                    String[] k = cached(wcaId);
                    if (k != null) l.onName(wcaId, k[0], k[1]);
                });
                return;
            }
        }
        WcaHttp.get(WCA + "/persons/" + wcaId, new WcaHttp.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject p = new JSONObject(body).getJSONObject("person");
                    String[] v = {p.optString("name", wcaId), p.optString("country_iso2", "")};
                    synchronized (WcaNames.class) {
                        CACHE.put(wcaId, v);
                        PENDING.remove(wcaId);
                    }
                    l.onName(wcaId, v[0], v[1]);
                } catch (Exception e) {
                    synchronized (WcaNames.class) { PENDING.remove(wcaId); }
                }
            }

            @Override
            public void onError(String message) {
                synchronized (WcaNames.class) { PENDING.remove(wcaId); }
            }
        });
    }
}
