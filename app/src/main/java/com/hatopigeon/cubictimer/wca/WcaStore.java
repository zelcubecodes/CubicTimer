package com.hatopigeon.cubictimer.wca;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small persistent store for the WCA screens: saved WCA ID, favorites, recents and cached data. */
public final class WcaStore {
    private final SharedPreferences sp;
    private final Context appCtx;

    public WcaStore(Context c) {
        appCtx = c.getApplicationContext();
        sp = c.getApplicationContext().getSharedPreferences("wca_prefs", Context.MODE_PRIVATE);
    }

    public String getMyWcaId() {
        return sp.getString("my_wca_id", "");
    }

    public void setMyWcaId(String id) {
        sp.edit().putString("my_wca_id", id).apply();
    }

    // Pinned competitors are stored as [wcaId, name] pairs. Any number can be pinned.
    public List<String[]> getPinned() {
        List<String[]> list = getComps("pinned_people");
        // Older versions saved one "my WCA ID". Move it into the pinned list once.
        String legacy = getMyWcaId();
        if (!legacy.isEmpty()) {
            boolean there = false;
            for (String[] c : list) {
                if (c[0].equalsIgnoreCase(legacy)) there = true;
            }
            if (!there) {
                list.add(new String[]{legacy, legacy});
                saveComps("pinned_people", list);
            }
            setMyWcaId("");
        }
        return list;
    }

    public boolean isPinned(String wcaId) {
        if (wcaId == null || wcaId.isEmpty()) return false;
        for (String[] c : getPinned()) {
            if (c[0].equalsIgnoreCase(wcaId)) return true;
        }
        return false;
    }

    /** Pins the competitor, or unpins them if they are already pinned. Returns true if now pinned. */
    public boolean togglePinned(String wcaId, String name) {
        List<String[]> list = getPinned();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[0].equalsIgnoreCase(wcaId)) {
                list.remove(i);
                saveComps("pinned_people", list);
                return false;
            }
        }
        list.add(new String[]{wcaId, name == null || name.isEmpty() ? wcaId : name});
        saveComps("pinned_people", list);
        return true;
    }

    public boolean getAutoRefresh() {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(appCtx)
                .getBoolean("wca_auto_refresh", true);
    }

    public void setAutoRefresh(boolean on) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appCtx)
                .edit().putBoolean("wca_auto_refresh", on).apply();
    }

    // Competitions are stored as [wcaId, name] pairs.
    public List<String[]> getComps(String key) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("id"), o.optString("name")});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveComps(String key, List<String[]> list) {
        JSONArray a = new JSONArray();
        try {
            for (String[] c : list) {
                JSONObject o = new JSONObject();
                o.put("id", c[0]);
                o.put("name", c[1]);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString(key, a.toString()).apply();
    }

    public boolean isFavoriteComp(String id) {
        for (String[] c : getComps("fav_comps")) {
            if (c[0].equals(id)) return true;
        }
        return false;
    }

    public void toggleFavoriteComp(String id, String name) {
        List<String[]> list = getComps("fav_comps");
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[0].equals(id)) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) list.add(0, new String[]{id, name});
        saveComps("fav_comps", list);
    }

    public void addRecentComp(String id, String name) {
        List<String[]> list = getComps("recent_comps");
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[0].equals(id)) {
                list.remove(i);
                break;
            }
        }
        list.add(0, new String[]{id, name});
        while (list.size() > 10) list.remove(list.size() - 1);
        saveComps("recent_comps", list);
    }

    // Favorite competitors are stored by WCA ID (or by name when there is no WCA ID).
    public boolean isFavoritePerson(String key) {
        return sp.getStringSet("fav_people", new java.util.HashSet<String>()).contains(key);
    }

    public void toggleFavoritePerson(String key) {
        java.util.Set<String> set = new java.util.HashSet<>(
                sp.getStringSet("fav_people", new java.util.HashSet<String>()));
        if (!set.remove(key)) set.add(key);
        sp.edit().putStringSet("fav_people", set).apply();
    }

    // Cached person data, so previous results are still readable offline.
    public void cachePerson(String wcaId, String profileJson, String resultsJson) {
        sp.edit().putString("person_" + wcaId, profileJson)
                .putString("results_" + wcaId, resultsJson)
                .putLong("fetched_" + wcaId, System.currentTimeMillis())
                .apply();
    }

    public String cachedProfile(String wcaId) {
        return sp.getString("person_" + wcaId, null);
    }

    public String cachedResults(String wcaId) {
        return sp.getString("results_" + wcaId, null);
    }

    public long cachedAt(String wcaId) {
        return sp.getLong("fetched_" + wcaId, 0L);
    }
}
