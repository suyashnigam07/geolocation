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
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(context.getColor(R.color.track));
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(context.getColor(R.color.accent));
    }

    public void setColor(int color) {
        progressPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // 14dp on the big ring, proportionally thinner on small ones.
        float stroke = Math.min(14 * getResources().getDisplayMetrics().density, w * 0.07f);
        trackPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);
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
