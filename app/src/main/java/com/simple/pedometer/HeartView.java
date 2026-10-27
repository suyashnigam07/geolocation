package com.simple.pedometer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A heart-shaped ring. The outline fills up with {@link #setProgress(float)}
 * and {@link #beat()} gives it a short "pump" animation.
 */
public class HeartView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path();
    private final Path segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private float progress;
    private ValueAnimator animator;

    public HeartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setColor(context.getColor(R.color.heart_soft));
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeJoin(Paint.Join.ROUND);
        trackPaint.setColor(context.getColor(R.color.track));
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeJoin(Paint.Join.ROUND);
        progressPaint.setColor(context.getColor(R.color.heart));
    }

    /** @param value 0..1, how much of the outline is drawn in red. */
    public void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    public void beat() {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(1.12f, 1f);
        animator.setDuration(280);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            setScaleX(s);
            setScaleY(s);
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Heart drawn in a 1x1 box, starting at the top dip so progress runs clockwise.
        heart.reset();
        heart.moveTo(0.50f, 0.20f);
        heart.cubicTo(0.51f, 0.13f, 0.56f, 0.07f, 0.64f, 0.06f);
        heart.cubicTo(0.78f, 0.04f, 0.94f, 0.14f, 0.96f, 0.32f);
        heart.cubicTo(0.98f, 0.50f, 0.82f, 0.70f, 0.50f, 0.92f);
        heart.cubicTo(0.18f, 0.70f, 0.02f, 0.50f, 0.04f, 0.32f);
        heart.cubicTo(0.06f, 0.14f, 0.22f, 0.04f, 0.36f, 0.06f);
        heart.cubicTo(0.44f, 0.07f, 0.49f, 0.13f, 0.50f, 0.20f);
        heart.close();

        // Outline thickness scales with the size of the heart.
        float stroke = Math.max(4f, w * 0.05f);
        trackPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);
        float inset = stroke / 2f;
        Matrix m = new Matrix();
        m.setScale(w - 2 * inset, h - 2 * inset);
        m.postTranslate(inset, inset);
        heart.transform(m);
        measure.setPath(heart, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(heart, fillPaint);
        canvas.drawPath(heart, trackPaint);
        if (progress > 0f) {
            segment.reset();
            measure.getSegment(0, measure.getLength() * progress, segment, true);
            canvas.drawPath(segment, progressPaint);
        }
    }
}
