package com.simple.pedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/** Draws a walk's GPS route as a line, scaled to fit, with start and end dots. */
public class RouteView extends View {

    private final float density;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<double[]> route;
    private String emptyText = "";

    public RouteView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4 * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(context.getColor(R.color.accent));
        startPaint.setColor(context.getColor(R.color.accent));
        endPaint.setColor(context.getColor(R.color.heart));
        textPaint.setColor(context.getColor(R.color.text_secondary));
        textPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRoute(List<double[]> route, String emptyText) {
        this.route = route;
        this.emptyText = emptyText;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (route == null || route.size() < 2) {
            canvas.drawText(emptyText, getWidth() / 2f, getHeight() / 2f, textPaint);
            if (route != null && route.size() == 1) {
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f + 24 * density, 6 * density, startPaint);
            }
            return;
        }

        // Project lat/lon onto a flat plane (fine for walking distances).
        double cosLat = Math.cos(Math.toRadians(route.get(0)[0]));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : route) {
            double x = p[1] * cosLat, y = p[0];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        float pad = 16 * density;
        double spanX = Math.max(maxX - minX, 1e-6), spanY = Math.max(maxY - minY, 1e-6);
        double scale = Math.min((getWidth() - 2 * pad) / spanX, (getHeight() - 2 * pad) / spanY);
        // Centre the route in the view.
        double offX = (getWidth() - spanX * scale) / 2, offY = (getHeight() - spanY * scale) / 2;

        path.reset();
        float sx = 0, sy = 0, ex = 0, ey = 0;
        for (int i = 0; i < route.size(); i++) {
            double[] p = route.get(i);
            float x = (float) (offX + (p[1] * cosLat - minX) * scale);
            float y = (float) (getHeight() - offY - (p[0] - minY) * scale);
            if (i == 0) { path.moveTo(x, y); sx = x; sy = y; } else path.lineTo(x, y);
            ex = x; ey = y;
        }
        canvas.drawPath(path, linePaint);
        canvas.drawCircle(sx, sy, 6 * density, startPaint);
        canvas.drawCircle(ex, ey, 6 * density, endPaint);
    }
}
