package com.simple.pedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Tiny 7-day bar chart for a friend row; today's bar is highlighted. */
public class MiniBarsView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private int[] values = new int[0];

    public MiniBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        barPaint.setColor(context.getColor(R.color.bar));
        todayPaint.setColor(context.getColor(R.color.accent));
    }

    public void setValues(int[] values) {
        this.values = values;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (values.length == 0) return;
        int max = 1;
        for (int v : values) max = Math.max(max, v);
        float slot = (float) getWidth() / values.length;
        float width = slot * 0.6f;
        float radius = width / 2;
        for (int i = 0; i < values.length; i++) {
            float h = Math.max(radius * 2, getHeight() * (float) values[i] / max);
            float x = slot * i + (slot - width) / 2;
            bar.set(x, getHeight() - h, x + width, getHeight());
            canvas.drawRoundRect(bar, radius, radius, i == values.length - 1 ? todayPaint : barPaint);
        }
    }
}
