package com.eightubah.mod.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.eightubah.mod.audio.Dsp;

/**
 * Equalizer graph. Port of the iOS RYEQView: 10 draggable dots on a log-f /
 * dB plot, with the live response curve. Dots move only while editing.
 */
public class EqView extends View {
    public interface Live { void run(int band); }
    public interface End { void run(); }
    public Live onLive; public End onEnd;
    public boolean editing;
    private int sel = -1;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EqView(Context c) {
        super(c);
        setBackground(Ui.round(Color.rgb(31, 33, 41), Ui.dp(c, 10)));
    }

    private RectF plot() {
        float pad = Ui.dp(getContext(), 1);
        return new RectF(Ui.dp(getContext(), 36), Ui.dp(getContext(), 8), getWidth() - Ui.dp(getContext(), 8), getHeight() - Ui.dp(getContext(), 18));
    }
    private float xFor(double f) { RectF r = plot(); return (float) (r.left + r.width() * (Math.log10(f / 20.0) / 3.0)); }
    private double fFor(float x) { RectF r = plot(); return 20.0 * Math.pow(10.0, 3.0 * (x - r.left) / r.width()); }
    private float yFor(double g) { RectF r = plot(); return (float) (r.top + r.height() * (0.5 - g / 24.0)); }
    private double gFor(float y) { RectF r = plot(); return (0.5 - (y - r.top) / r.height()) * 24.0; }

    private int nearest(float px, float py) {
        int best = -1; float bd = 1e9f;
        for (int i = 0; i < Dsp.RYEQ_N; i++) { float dx = xFor(Dsp.gEqF[i]) - px, dy = yFor(Dsp.gEqG[i]) - py, d = (float) Math.sqrt(dx * dx + dy * dy); if (d < bd) { bd = d; best = i; } }
        return best;
    }

    private static final int[] DOT = {0xFF9E54F2, 0xFF4F66FA, 0xFFFF4F59, 0xFFFF734D, 0xFFFFA32E, 0xFFB3F226, 0xFF0DD9B3, 0xFFFF4099, 0xFF1AE6F2, 0xFF26A6FF};

    @Override protected void onDraw(Canvas g) {
        RectF r = plot();
        p.setTextSize(Ui.dp(getContext(), 9));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Ui.dp(getContext(), 1));
        double[] freqs = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
        for (double f : freqs) {
            float x = xFor(f); p.setColor(0x12FFFFFF); g.drawLine(x, r.top, x, r.bottom, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0x80FFFFFF);
            String t = f >= 1000 ? ((int) (f / 1000)) + "k" : "" + (int) f;
            g.drawText(t, x - Ui.dp(getContext(), 6), r.bottom + Ui.dp(getContext(), 11), p);
            p.setStyle(Paint.Style.STROKE);
        }
        int[] dbs = {12, 6, 0, -6, -12};
        for (int d : dbs) {
            float y = yFor(d); p.setColor(d == 0 ? 0x2EFFFFFF : 0x12FFFFFF); g.drawLine(r.left, y, r.right, y, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0x80FFFFFF); g.drawText((d >= 0 ? "+" : "") + d, Ui.dp(getContext(), 6), y - Ui.dp(getContext(), 5), p);
            p.setStyle(Paint.Style.STROKE);
        }
        // response curve
        g.save(); g.clipRect(r);
        p.setColor(Dsp.gEqOn ? 0xF2FFFFFF : 0x66FFFFFF); p.setStrokeWidth(Ui.dp(getContext(), 1.4f));
        float prevX = 0, prevY = 0; boolean first = true;
        for (float x = r.left; x <= r.right; x += 2) {
            double f = fFor(x), db = 0; for (int i = 0; i < Dsp.RYEQ_N; i++) db += Dsp.eqBandDb(Dsp.gEqT[i], Dsp.gEqF[i], Dsp.gEqG[i], Dsp.gEqQ[i], f);
            db += Dsp.gEqPre; float y = yFor(Math.max(-18, Math.min(18, db)));
            if (first) { first = false; } else g.drawLine(prevX, prevY, x, y, p);
            prevX = x; prevY = y;
        }
        g.restore();
        // dots
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < Dsp.RYEQ_N; i++) {
            float cx = xFor(Dsp.gEqF[i]), cy = yFor(Dsp.gEqG[i]), rad = (i == sel) ? Ui.dp(getContext(), 8) : Ui.dp(getContext(), 5.5f);
            p.setColor(DOT[i % 10]); g.drawCircle(cx, cy, rad, p);
            if (i == sel) { p.setStyle(Paint.Style.STROKE); p.setColor(Color.WHITE); p.setStrokeWidth(Ui.dp(getContext(), 2)); g.drawCircle(cx, cy, rad, p); p.setStyle(Paint.Style.FILL); }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!editing) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sel = nearest(e.getX(), e.getY()); if (sel < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate(); if (onLive != null) onLive.run(sel); return true;
            case MotionEvent.ACTION_MOVE:
                if (sel < 0) return false;
                double lo = sel > 0 ? Dsp.gEqF[sel - 1] * 1.06 : 20.0, hi = sel < Dsp.RYEQ_N - 1 ? Dsp.gEqF[sel + 1] / 1.06 : 20000.0;
                double f = Math.max(lo, Math.min(hi, fFor(e.getX()))), gg = Math.max(-12.0, Math.min(12.0, gFor(e.getY())));
                Dsp.gEqF[sel] = (float) f; Dsp.gEqG[sel] = (float) gg; Dsp.gEqVer++;
                invalidate(); if (onLive != null) onLive.run(sel); return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                sel = -1; invalidate(); if (onEnd != null) onEnd.run();
                getParent().requestDisallowInterceptTouchEvent(false); return true;
        }
        return false;
    }
}
