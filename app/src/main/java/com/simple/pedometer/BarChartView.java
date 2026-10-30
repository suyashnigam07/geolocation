package com.simple.pedometer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * Simple vertical bar chart: one bar per day with its value above it, the day
 * name below it and a dashed line marking the daily goal. The last bar (today)
 * is highlighted.
 */
public class BarChartView extends View {

    private final float density;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goalTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final Path goalPath = new Path();

    private double[] values = new double[0];
    private String[] labels = new String[0];
    private double goal;
    private DoubleFunction<String> format = v -> shortNumber((int) Math.round(v));
    private float growth = 1f;

    public BarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;

        barPaint.setColor(context.getColor(R.color.bar));
        highlightPaint.setColor(context.getColor(R.color.accent));

        valuePaint.setColor(context.getColor(R.color.text_primary));
        valuePaint.setTextSize(11 * sp);
        valuePaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setColor(context.getColor(R.color.text_secondary));
        labelPaint.setTextSize(12 * sp);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        goalPaint.setColor(context.getColor(R.color.text_secondary));
        goalPaint.setStyle(Paint.Style.STROKE);
        goalPaint.setStrokeWidth(1 * density);
        goalPaint.setPathEffect(new DashPathEffect(new float[]{6 * density, 5 * density}, 0));

        goalTextPaint.setColor(context.getColor(R.color.text_secondary));
        goalTextPaint.setTextSize(10 * sp);
        goalTextPaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setData(int[] values, String[] labels, int goal) {
        double[] asDouble = new double[values.length];
        for (int i = 0; i < values.length; i++) asDouble[i] = values[i];
        setData(asDouble, labels, goal, v -> shortNumber((int) Math.round(v)));
    }

    /**
     * @param goal   draws a dashed goal line at this value; 0 for none
     * @param format turns a value into the label shown above its bar
     */
    public void setData(double[] values, String[] labels, double goal, DoubleFunction<String> format) {
        // Grow the bars in only the first time; later updates just redraw.
        boolean firstData = this.values.length == 0;
        this.values = values;
        this.labels = labels;
        this.goal = goal;
        this.format = format;
        if (firstData) {
            animateGrowth();
        } else {
            invalidate();
        }
    }

    private void animateGrowth() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(600);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            growth = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int count = values.length;
        if (count == 0) return;

        float top = valuePaint.getTextSize() + 8 * density;
        float bottom = getHeight() - labelPaint.getTextSize() - 10 * density;
        float chartHeight = bottom - top;
        float slot = (float) getWidth() / count;
        float barWidth = Math.min(slot * 0.55f, 36 * density);
        float radius = Math.min(barWidth / 2f, 8 * density);

        double max = goal;
        for (double v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;

        // Daily goal line.
        if (goal > 0) {
            float y = (float) (bottom - chartHeight * goal / max);
            goalPath.reset();
            goalPath.moveTo(0, y);
            goalPath.lineTo(getWidth(), y);
            canvas.drawPath(goalPath, goalPaint);
            canvas.drawText("Goal " + format.apply(goal), getWidth(), y - 4 * density, goalTextPaint);
        }

        for (int i = 0; i < count; i++) {
            float cx = slot * i + slot / 2f;
            float h = (float) (chartHeight * values[i] / max * growth);
            // Show a small stub for days with very few steps so every day is visible.
            h = Math.max(h, values[i] > 0 ? radius * 2 : 3 * density);
            bar.set(cx - barWidth / 2f, bottom - h, cx + barWidth / 2f, bottom);
            boolean today = i == count - 1;
            canvas.drawRoundRect(bar, radius, radius, today ? highlightPaint : barPaint);

            canvas.drawText(format.apply(values[i]), cx, bar.top - 6 * density, valuePaint);
            labelPaint.setFakeBoldText(today);
            labelPaint.setColor(getContext().getColor(today ? R.color.text_primary : R.color.text_secondary));
            canvas.drawText(labels[i], cx, getHeight() - 2 * density, labelPaint);
        }
    }

    private static String shortNumber(int n) {
        if (n >= 10000) return String.format(Locale.getDefault(), "%.0fk", n / 1000f);
        if (n >= 1000) return String.format(Locale.getDefault(), "%.1fk", n / 1000f);
        return String.valueOf(n);
    }
}
