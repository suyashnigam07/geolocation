package com.simple.pedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;
import java.util.List;

/**
 * The pulse trace drawn on a grid like ECG paper, with a dot on every clean
 * beat. It is wider than the screen, so it sits in a horizontal scroll view.
 */
public class TraceView extends View {

    /** Width of one second of trace. */
    private static final float DP_PER_SECOND = 60;

    private final float density;
    private final Paint minorGrid = new Paint();
    private final Paint majorGrid = new Paint();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] trace = new float[0];
    private List<Double> beats;

    public TraceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        int heart = context.getColor(R.color.heart);
        minorGrid.setColor(heart);
        minorGrid.setAlpha(30);
        minorGrid.setStrokeWidth(1);
        majorGrid.setColor(heart);
        majorGrid.setAlpha(70);
        majorGrid.setStrokeWidth(1.5f * density);
        linePaint.setColor(context.getColor(R.color.text_primary));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        beatPaint.setColor(heart);
        labelPaint.setColor(context.getColor(R.color.text_secondary));
        labelPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setData(float[] trace, List<Double> beats) {
        this.trace = trace;
        this.beats = beats;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float seconds = (float) trace.length / RhythmRecord.TRACE_HZ;
        int width = (int) Math.max(seconds * DP_PER_SECOND * density, MeasureSpec.getSize(widthSpec));
        setMeasuredDimension(width, MeasureSpec.getSize(heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pxPerSecond = DP_PER_SECOND * density;
        float labelSpace = labelPaint.getTextSize() + 6 * density;
        float h = getHeight() - labelSpace;

        // Grid: a fine line every 0.2 s, a strong line every second.
        float minor = pxPerSecond / 5;
        for (int i = 0; i * minor <= getWidth(); i++) {
            canvas.drawLine(i * minor, 0, i * minor, h, i % 5 == 0 ? majorGrid : minorGrid);
            if (i % 25 == 0) canvas.drawText(i / 5 + " s", i * minor + 3 * density, getHeight() - 4 * density, labelPaint);
        }
        for (int i = 0; i * minor <= h; i++) {
            canvas.drawLine(0, i * minor, getWidth(), i * minor, i % 5 == 0 ? majorGrid : minorGrid);
        }
        if (trace.length < 2) return;

        // Scale between the 2nd and 98th percentile so a single spike can't squash the trace.
        float[] sorted = trace.clone();
        Arrays.sort(sorted);
        float lo = sorted[(int) (sorted.length * 0.02)];
        float hi = sorted[(int) (sorted.length * 0.98)];
        float range = Math.max(hi - lo, 1e-3f);
        float pad = 10 * density;
        float step = pxPerSecond / RhythmRecord.TRACE_HZ;

        path.reset();
        for (int i = 0; i < trace.length; i++) {
            float v = Math.max(lo, Math.min(hi, trace[i]));
            float y = pad + (h - 2 * pad) * (1 - (v - lo) / range);
            if (i == 0) path.moveTo(0, y); else path.lineTo(i * step, y);
        }
        canvas.drawPath(path, linePaint);

        if (beats == null) return;
        for (double b : beats) {
            int i = (int) Math.round(b * RhythmRecord.TRACE_HZ);
            if (i < 0 || i >= trace.length) continue;
            float v = Math.max(lo, Math.min(hi, trace[i]));
            float y = pad + (h - 2 * pad) * (1 - (v - lo) / range);
            canvas.drawCircle(i * step, y, 3.5f * density, beatPaint);
        }
    }
}
