package com.eightubah.mod.audio;

import android.net.Uri;
import android.util.Base64;

import com.eightubah.mod.L;
import com.eightubah.mod.XEntry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sonar (SteelSeries) EQ import. Port of the iOS RYSonar* + RYEqImportLink.
 * The link is either the import URL (…import?url=<base64 of the cdn url>) or the
 * cdn url itself; the config JSON is read tolerantly and the biggest group of
 * objects carrying a frequency+gain is taken as the equaliser.
 */
public final class SonarImport {
    private SonarImport() {}

    public interface Done { void run(String err, String name); }

    private static String b64(String s) {
        try {
            s = s.replace("-", "+").replace("_", "/");
            while (s.length() % 4 != 0) s = s + "=";
            byte[] d = Base64.decode(s, Base64.DEFAULT);
            return d == null ? null : new String(d, "UTF-8");
        } catch (Throwable t) { return null; }
    }

    // one band: double[]{f,g,q,t} or null
    private static double[] band(Object n) {
        if (!(n instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) n;
        Double f = null, g = null, q = null; String ty = null; boolean en = true;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k0 = it.next(), k = k0.toLowerCase(); Object v = o.opt(k0);
            if (v instanceof Number) {
                double dv = ((Number) v).doubleValue();
                if (k.equals("frequency") || k.equals("freq") || k.equals("hz") || k.equals("f")) f = dv;
                else if (k.equals("gain") || k.equals("gaindb") || k.equals("gain_db")) g = dv;
                else if (k.equals("q") || k.equals("qfactor") || k.equals("q_factor") || k.equals("bandwidth")) q = dv;
            } else if (v instanceof Boolean && (k.equals("enabled") || k.equals("enable") || k.equals("active"))) en = (Boolean) v;
            else if (v instanceof String && (k.equals("type") || k.equals("filtertype") || k.equals("filter_type"))) ty = ((String) v).toLowerCase();
        }
        if (f == null || g == null) return null;
        int t = 0; if (ty != null) { if (ty.contains("low") && ty.contains("shelf")) t = 1; else if (ty.contains("high") && ty.contains("shelf")) t = 2; }
        return new double[]{ f, en ? g : 0, (q != null && q > 0.1) ? q : 1.1, t };
    }

    private static void walk(Object n, List<List<double[]>> groups) {
        List<Object> kids = new ArrayList<>();
        if (n instanceof JSONObject) { JSONObject o = (JSONObject) n; java.util.Iterator<String> it = o.keys(); while (it.hasNext()) kids.add(o.opt(it.next())); }
        else if (n instanceof JSONArray) { JSONArray a = (JSONArray) n; for (int i = 0; i < a.length(); i++) kids.add(a.opt(i)); }
        else return;
        List<double[]> g = new ArrayList<>();
        for (Object k : kids) { double[] b = band(k); if (b != null) g.add(b); }
        if (!g.isEmpty()) groups.add(g);
        if (n instanceof JSONObject) {
            JSONObject o = (JSONObject) n; JSONArray fa = null, ga = null, qa = null;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k0 = it.next(); String k = k0.toLowerCase(); Object v = o.opt(k0);
                if (!(v instanceof JSONArray) || ((JSONArray) v).length() == 0 || !(((JSONArray) v).opt(0) instanceof Number)) continue;
                if (k.startsWith("freq")) fa = (JSONArray) v; else if (k.startsWith("gain")) ga = (JSONArray) v; else if (k.equals("q") || k.startsWith("qf") || k.startsWith("qs")) qa = (JSONArray) v; }
            if (fa != null && ga != null && fa.length() == ga.length()) {
                List<double[]> pg = new ArrayList<>();
                for (int i = 0; i < fa.length(); i++) pg.add(new double[]{ fa.optDouble(i), ga.optDouble(i), (qa != null && qa.length() == fa.length()) ? qa.optDouble(i) : 1.1, 0 });
                groups.add(pg);
            }
        }
        for (Object k : kids) walk(k, groups);
    }

    private static JSONObject parse(Object root, String fallbackName) {
        List<List<double[]>> groups = new ArrayList<>(); walk(root, groups);
        List<double[]> best = null; for (List<double[]> g : groups) if (best == null || g.size() > best.size()) best = g;
        if (best == null || best.isEmpty()) return null;
        List<double[]> bs = new ArrayList<>(best);
        while (bs.size() > Dsp.RYEQ_N) { int wi = 0; double w = 1e9; for (int i = 0; i < bs.size(); i++) { double a = Math.abs(bs.get(i)[1]); if (a < w) { w = a; wi = i; } } bs.remove(wi); }
        Collections.sort(bs, (x, y) -> Double.compare(x[0], y[0]));
        float[] F = new float[Dsp.RYEQ_N], G = new float[Dsp.RYEQ_N], Q = new float[Dsp.RYEQ_N]; int[] T = new int[Dsp.RYEQ_N];
        int i = 0;
        for (; i < bs.size() && i < Dsp.RYEQ_N; i++) { double[] b = bs.get(i);
            F[i] = (float) Math.max(20, Math.min(20000, b[0])); G[i] = (float) Math.max(-12, Math.min(12, b[1])); Q[i] = (float) b[2]; T[i] = (int) b[3]; }
        for (; i < Dsp.RYEQ_N; i++) { F[i] = Dsp.kEqF[i]; G[i] = 0; Q[i] = 1.1f; T[i] = 0; }
        // stable sort by F (already sorted, pad kept at end could break order) -> reorder
        Integer[] idx = new Integer[Dsp.RYEQ_N]; for (int k = 0; k < Dsp.RYEQ_N; k++) idx[k] = k;
        final float[] FF = F; java.util.Arrays.sort(idx, (x, y) -> Float.compare(FF[x], FF[y]));
        float[] F2 = new float[Dsp.RYEQ_N], G2 = new float[Dsp.RYEQ_N], Q2 = new float[Dsp.RYEQ_N]; int[] T2 = new int[Dsp.RYEQ_N];
        for (int k = 0; k < Dsp.RYEQ_N; k++) { int j = idx[k]; F2[k] = F[j]; G2[k] = G[j]; Q2[k] = Q[j]; T2[k] = T[j]; }
        String nm = fallbackName;
        if (root instanceof JSONObject && ((JSONObject) root).opt("name") instanceof String && ((JSONObject) root).optString("name").length() > 0) nm = ((JSONObject) root).optString("name");
        return MicSettings.eqDictFrom(nm, F2, G2, Q2, T2);
    }

    public static void importLink(final String link, final String forcedName, final Done done) {
        new Thread(new Runnable() { public void run() {
            String t = link == null ? "" : link.trim(); String target = null;
            Uri uc = Uri.parse(t);
            try { String q = uc.getQueryParameter("url"); if (q != null && q.length() > 0) { target = q; if (!target.startsWith("http")) target = b64(target); } } catch (Throwable e) {}
            if (target == null && t.startsWith("http")) target = t;
            if (target == null && t.length() > 20 && !t.contains(" ")) target = b64(t);
            String err = null, nm = null;
            try {
                URL u = target == null ? null : new URL(target);
                if (u == null || !"https".equals(u.getProtocol()) || !u.getHost().endsWith("steelseriescdn.com")) {
                    fin(done, L.t("الرابط غير صحيح", "Invalid link"), null); return;
                }
                HttpURLConnection c = (HttpURLConnection) u.openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(10000);
                java.io.InputStream is = c.getInputStream(); java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n; while ((n = is.read(b)) > 0) bo.write(b, 0, n); is.close();
                String body = new String(bo.toByteArray(), "UTF-8");
                Object j; try { j = new JSONObject(body); } catch (Throwable e) { j = new JSONArray(body); }
                JSONObject p = parse(j, L.t("استيراد", "Imported"));
                if (p == null) { fin(done, L.t("ما لقيت إيكولايزر بالإعداد", "No equalizer found in the config"), null); return; }
                final String base = (forcedName != null && forcedName.length() > 0) ? forcedName : p.optString("n");
                String use = base; int k = 2; while (MicSettings.isBuiltin(use)) use = base + " " + (k++);
                final JSONObject m = new JSONObject(p.toString()); m.put("n", use); final String fuse = use;
                XEntry.main(new Runnable() { public void run() { MicSettings.applyDict(m); MicSettings.saveUser(fuse); } });
                nm = use;
            } catch (Throwable e) { err = L.t("تعذر تحميل الإعداد", "Download failed"); }
            fin(done, err, nm);
        }}).start();
    }

    private static void fin(final Done done, final String err, final String nm) {
        XEntry.main(new Runnable() { public void run() { if (done != null) done.run(err, nm); } });
    }
}
