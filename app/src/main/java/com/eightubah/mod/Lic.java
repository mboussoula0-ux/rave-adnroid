package com.eightubah.mod;

import android.net.Uri;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Licence (server-checked key, one device per key). Direct port of the iOS
 * RYLic* functions. The key is activated once, then cached locally; a silent
 * background re-check flips back to locked only on a definite "no" from the
 * server.
 */
public final class Lic {
    private Lic() {}

    private static final String API = "https://empty-moon-c2f7.mboussoula0.workers.dev";

    public interface Done { void run(boolean ok, String reason); }

    public static String deviceId() {
        String d = Cfg.s("devId", null);
        if (d != null && d.length() > 0) return d;
        String v = UUID.randomUUID().toString();
        Cfg.s("devId", v);
        return v;
    }

    public static String hash(String s) {
        try {
            byte[] d = ("8ubah|" + (s == null ? "" : s)).getBytes("UTF-8");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(d);
            StringBuilder o = new StringBuilder();
            for (byte b : h) o.append(String.format("%02x", b));
            return o.toString();
        } catch (Throwable t) { return ""; }
    }

    private static String pct(String s) { return Uri.encode(s == null ? "" : s); }

    /** POST /activate with the typed key. */
    public static void activate(final String key, final Done done) {
        final String h = hash(key);
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false; String reason = "network";
                try {
                    URL u = new URL(API + "/activate");
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(10000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    JSONObject body = new JSONObject();
                    body.put("key", key); body.put("device", deviceId());
                    OutputStream os = c.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8")); os.close();
                    String resp = readAll(c);
                    JSONObject j = new JSONObject(resp);
                    ok = j.optBoolean("ok", false);
                    reason = j.optString("reason", reason);
                } catch (Throwable t) { reason = "network"; }
                final boolean fok = ok; final String freason = reason;
                XEntry.main(new Runnable() { public void run() {
                    if (fok) {
                        Cfg.s("licHash", h);
                        Cfg.b("unlocked", true);
                        Cfg.f("licTs", (float) (System.currentTimeMillis() / 1000.0));
                    }
                    if (done != null) done.run(fok, freason);
                }});
            }
        }).start();
    }

    /** Silent background re-check of the saved key. No UI. */
    public static void revalidate() {
        final String h = Cfg.s("licHash", null);
        if (h == null || h.length() == 0 || Cfg.locked()) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String urlStr = API + "/check?hash=" + pct(h) + "&device=" + pct(deviceId());
                    URL u = new URL(urlStr);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    String resp = readAll(c);
                    if (resp == null) return; // offline: keep whatever we had
                    JSONObject j = new JSONObject(resp);
                    if (j.optBoolean("ok", false)) return;
                    String why = j.optString("reason", null);
                    if (why == null) return;
                    List<String> hard = Arrays.asList("global_off", "not_found", "off", "expired", "device_mismatch");
                    boolean isHard = hard.contains(why);
                    boolean unbound = "not_bound".equals(why)
                            && (System.currentTimeMillis() / 1000.0 - Cfg.f("licTs", 0)) > 600;
                    if (!isHard && !unbound) return;
                    XEntry.main(new Runnable() { public void run() { Cfg.b("unlocked", false); } });
                } catch (Throwable t) { /* offline: never punish a dropped connection */ }
            }
        }).start();
    }

    private static String readAll(HttpURLConnection c) {
        try {
            java.io.InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) { return null; }
    }
}
