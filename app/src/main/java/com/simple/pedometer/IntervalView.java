package com.simple.pedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * Time between consecutive heartbeats. A regular rhythm gives a gentle wave
 * (it naturally speeds up and slows down with breathing); an irregular one
 * jumps up and down at random.
 */
public class IntervalView extends View {

    private final float density;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<Integer> intervals;

    public IntervalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        linePaint.setColor(context.getColor(R.color.heart));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.5f * density);
        dotPaint.setColor(context.getColor(R.color.heart));
        meanPaint.setColor(context.getColor(R.color.text_secondary));
        meanPaint.setStyle(Paint.Style.STROKE);
        meanPaint.setStrokeWidth(1 * density);
        meanPaint.setPathEffect(new DashPathEffect(new float[]{6 * density, 5 * density}, 0));
        labelPaint.setColor(context.getColor(R.color.text_secondary));
        labelPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setIntervals(List<Integer> intervals) {
        this.intervals = intervals;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (intervals == null || intervals.size() < 2) return;
        int min = Integer.MAX_VALUE, max = 0;
        long sum = 0;
        for (int i : intervals) {
            min = Math.min(min, i);
            max = Math.max(max, i);
            sum += i;
        }
        int mean = (int) (sum / intervals.size());
        // Always show at least a 400 ms range so a steady rhythm looks steady.
        int lo = Math.min(min, mean - 200), hi = Math.max(max, mean + 200);

        float labelWidth = labelPaint.measureText("0000 ms") + 6 * density;
        float pad = 8 * density;
        float w = getWidth() - labelWidth, h = getHeight() - 2 * pad;
        float step = w / (intervals.size() - 1);

        float meanY = pad + h * (1 - (float) (mean - lo) / (hi - lo));
        canvas.drawLine(labelWidth, meanY, getWidth(), meanY, meanPaint);
        canvas.drawText(hi + " ms", 0, pad + labelPaint.getTextSize() / 2, labelPaint);
        canvas.drawText(mean + " ms", 0, meanY + labelPaint.getTextSize() / 3, labelPaint);
        canvas.drawText(lo + " ms", 0, pad + h, labelPaint);

        path.reset();
        for (int i = 0; i < intervals.size(); i++) {
            float x = labelWidth + i * step;
            float y = pad + h * (1 - (float) (intervals.get(i) - lo) / (hi - lo));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            canvas.drawCircle(x, y, 2.5f * density, dotPaint);
        }
        canvas.drawPath(path, linePaint);
    }
}
