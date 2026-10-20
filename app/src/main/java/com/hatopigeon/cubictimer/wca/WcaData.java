package com.hatopigeon.cubictimer.wca;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Competition names and dates from the bundled WCA results export (not live data). */
public final class WcaData {
    private static Map<String, String[]> comps;

    private WcaData() {}

    /** Call on a background thread. Returns id -> {name, date}. */
    public static synchronized Map<String, String[]> competitions(Context c) {
        if (comps != null) return comps;
        Map<String, String[]> map = new HashMap<>();
        try (InputStream in = c.getApplicationContext().getAssets().open("wca_competitions.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject root = new JSONObject(out.toString("UTF-8"));
            JSONArray arr = root.getJSONArray("c");
            for (int i = 0; i < arr.length(); i++) {
                JSONArray row = arr.getJSONArray(i);
                map.put(row.getString(0), new String[]{row.getString(1), row.getString(2)});
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        comps = map;
        return comps;
    }
}
