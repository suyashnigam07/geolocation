package com.simple.pedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Scrolling line showing the raw pulse signal while measuring. */
public class PulseWaveView extends View {

    private static final int MAX_POINTS = 150;

    private final float[] points = new float[MAX_POINTS];
    private int count;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public PulseWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(context.getColor(R.color.heart));
    }

    public void add(float value) {
        if (count == MAX_POINTS) {
            System.arraycopy(points, 1, points, 0, MAX_POINTS - 1);
            count--;
        }
        points[count++] = value;
        invalidate();
    }

    public void clear() {
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (count < 2) return;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, points[i]);
            max = Math.max(max, points[i]);
        }
        float range = Math.max(max - min, 0.5f);
        float pad = paint.getStrokeWidth();
        float h = getHeight() - 2 * pad;
        float step = (float) getWidth() / (MAX_POINTS - 1);

        path.reset();
        for (int i = 0; i < count; i++) {
            float x = i * step;
            float y = pad + h - (points[i] - min) / range * h;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }
}
