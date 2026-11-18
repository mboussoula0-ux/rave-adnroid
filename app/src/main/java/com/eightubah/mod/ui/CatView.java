package com.eightubah.mod.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The oneko cat drawing. Direct port of the iOS RYCatView drawRect. Drawn in a
 * 52x40 "point" space scaled by density; facing flips horizontally.
 */
public class CatView extends View {
    public float phase; public int facing = 1, mode; public boolean blink;
    private final Paint pk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float d;

    public CatView(Context c) { super(c); setWillNotDraw(false); d = c.getResources().getDisplayMetrics().density; }

    private final int fur = Color.rgb(247, 247, 247), ink = Color.rgb(31, 31, 31), pink = Color.rgb(255, 153, 179);

    @Override protected void onDraw(Canvas g) {
        g.save();
        g.scale(d, d);
        if (facing < 0) { g.translate(52, 0); g.scale(-1, 1); }
        float ph = phase; boolean shut = mode == 2 || blink;
        float wag = (float) Math.sin(ph * (mode == 0 ? 0.7 : 0.25)) * 4;

        Path tail = new Path();
        if (mode == 2) { tail.moveTo(10, 30); tail.quadTo(14, 39, 32, 37); }
        else { tail.moveTo(10, 24); tail.quadTo(-2 + wag, 18, 4 + wag * 0.3f, 8); }
        pk.setStyle(Paint.Style.STROKE); pk.setStrokeCap(Paint.Cap.ROUND);
        pk.setColor(ink); pk.setStrokeWidth(5); g.drawPath(tail, pk);
        pk.setColor(fur); pk.setStrokeWidth(2.8f); g.drawPath(tail, pk);

        if (mode != 2) {
            float[] xs = {12, 18, 29, 35};
            for (int i = 0; i < 4; i++) {
                float sw = mode == 0 ? (float) Math.sin(ph + (i % 2) * Math.PI) * 3 : 0;
                RectF leg = new RectF(xs[i] - 2.5f + sw, 27, xs[i] + 2.5f + sw, 36);
                pk.setStyle(Paint.Style.FILL); pk.setColor(fur); g.drawRoundRect(leg, 2.5f, 2.5f, pk);
                pk.setStyle(Paint.Style.STROKE); pk.setColor(ink); pk.setStrokeWidth(1.5f); g.drawRoundRect(leg, 2.5f, 2.5f, pk);
            }
        }
        RectF body = mode == 2 ? new RectF(6, 22, 44, 36) : new RectF(8, 16, 42, 32);
        pk.setStyle(Paint.Style.FILL); pk.setColor(fur); g.drawOval(body, pk);
        pk.setStyle(Paint.Style.STROKE); pk.setColor(ink); pk.setStrokeWidth(1.5f); g.drawOval(body, pk);

        float hx = mode == 2 ? 38 : 41, hy = mode == 2 ? 28 : 17;
        Path ears = new Path();
        ears.moveTo(hx - 8, hy - 3); ears.lineTo(hx - 6, hy - 13); ears.lineTo(hx - 1, hy - 8);
        ears.moveTo(hx + 1, hy - 8); ears.lineTo(hx + 6, hy - 13); ears.lineTo(hx + 8, hy - 3);
        pk.setStyle(Paint.Style.FILL); pk.setColor(fur); g.drawPath(ears, pk);
        pk.setStyle(Paint.Style.STROKE); pk.setColor(ink); g.drawPath(ears, pk);

        RectF head = new RectF(hx - 9, hy - 8.5f, hx + 9, hy + 8.5f);
        pk.setStyle(Paint.Style.FILL); pk.setColor(fur); g.drawOval(head, pk);
        pk.setStyle(Paint.Style.STROKE); pk.setColor(ink); g.drawOval(head, pk);

        if (shut) {
            pk.setStrokeWidth(1.4f); pk.setColor(ink);
            g.drawLine(hx - 5, hy - 1, hx - 1.5f, hy - 1, pk);
            g.drawLine(hx + 2, hy - 1, hx + 5.5f, hy - 1, pk);
        } else {
            pk.setStyle(Paint.Style.FILL); pk.setColor(ink);
            g.drawOval(new RectF(hx - 4.5f, hy - 2.5f, hx - 1.5f, hy + 0.9f), pk);
            g.drawOval(new RectF(hx + 2.5f, hy - 2.5f, hx + 5.5f, hy + 0.9f), pk);
        }
        pk.setStyle(Paint.Style.FILL); pk.setColor(pink);
        g.drawOval(new RectF(hx - 0.2f, hy + 2, hx + 2.8f, hy + 4.4f), pk);
        pk.setStyle(Paint.Style.STROKE); pk.setColor(ink); pk.setStrokeWidth(0.8f);
        g.drawLine(hx + 6, hy + 3, hx + 13, hy + 1, pk);
        g.drawLine(hx + 6, hy + 4.5f, hx + 13, hy + 5.5f, pk);
        g.restore();
    }
}
