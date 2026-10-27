package com.simple.pedometer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Circular progress ring showing how far today's steps are towards the daily goal. */
public class StepRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float progress;
    private ValueAnimator animator;

    public StepRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float stroke = 14 * getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(context.getColor(R.color.track));
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(context.getColor(R.color.accent));
    }

    /** @param value fraction of the goal reached, 0..1 (values above 1 show a full ring). */
    public void setProgress(float value) {
        float target = Math.max(0f, Math.min(1f, value));
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(700);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = trackPaint.getStrokeWidth() / 2f;
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(bounds, 0, 360, false, trackPaint);
        if (progress > 0f) {
            canvas.drawArc(bounds, -90, 360 * progress, false, progressPaint);
        }
    }
}
