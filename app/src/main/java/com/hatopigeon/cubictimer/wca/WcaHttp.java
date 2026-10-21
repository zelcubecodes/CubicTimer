package com.hatopigeon.cubictimer.wca;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP helper. Requests run on a background thread and the callbacks run on the main
 * thread. Nothing is polled in the background: every request starts from a user action or from
 * a screen that is visible.
 */
public final class WcaHttp {
    public interface Callback {
        void onSuccess(String body);

        void onError(String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String USER_AGENT = "ZelCubesTimer/1.0 (Android; open-source speedcubing timer)";

    private WcaHttp() {}

    public static void get(String url, Callback cb) {
        request("GET", url, null, cb);
    }

    public static void postJson(String url, String json, Callback cb) {
        request("POST", url, json, cb);
    }

    /** Runs {@code work} off the main thread, then {@code done} on the main thread. */
    public static void background(Runnable work, Runnable done) {
        EXECUTOR.execute(() -> {
            try {
                work.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
            MAIN.post(done);
        });
    }

    /** True when the device has a network that claims to reach the internet. */
    public static boolean isOnline(android.content.Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    c.getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true; // cannot tell, so let the request decide
        }
    }

    /** Turns a raw request error into a short message a person can understand. */
    public static String describe(android.content.Context c, String raw) {
        if (!isOnline(c)) return "No internet connection";
        String m = raw == null ? "" : raw;
        if (m.startsWith("UnknownHost") || m.startsWith("ConnectException")
                || m.startsWith("SocketTimeout") || m.startsWith("NoRouteToHost")
                || m.startsWith("SSLException")) {
            return "No internet access";
        }
        if (m.startsWith("HTTP 404")) return "Not found on the WCA server";
        if (m.startsWith("HTTP")) return "WCA server error (" + m + "). Try again later";
        return "Could not reach the WCA server";
    }

    private static void request(String method, String url, String body, Callback cb) {
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "application/json");
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = conn.getResponseCode();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String text = in == null ? "" : read(in);
                if (code >= 200 && code < 300) {
                    MAIN.post(() -> cb.onSuccess(text));
                } else {
                    MAIN.post(() -> cb.onError("HTTP " + code));
                }
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                MAIN.post(() -> cb.onError(msg));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}
