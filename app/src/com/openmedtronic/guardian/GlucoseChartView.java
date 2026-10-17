package com.openmedtronic.guardian;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Next-Gen Interactive CGM Glucose Chart:
 * - 4-hour default timeline (3 hours past + 1 hour future buffer).
 * - Smooth Monotone Cubic Bezier Splines without overshooting.
 * - Distinct visual markers for fingerstick BG checks and calibrations.
 * - Dynamic Y-axis autoscale with safe margins for hyper/hypoglycemia.
 * - Horizontal smooth pan/scroll & fling momentum through history.
 * - Pinch-to-zoom (2h to 24h timeline).
 * - Interactive Crosshair Scrubber & Glassmorphism Tooltip.
 * - "▶ Now" jump badge when reviewing past readings.
 */
public class GlucoseChartView extends View {

    public static final int TYPE_CGM = 0;
    public static final int TYPE_CALIBRATION = 1;
    public static final int TYPE_MANUAL_BG = 2;

    public static class Point {
        public final long timestamp;
        public final float glucoseMmol;
        public final float isig;
        public final int pointType;

        public Point(long timestamp, float glucoseMmol, float isig) {
            this(timestamp, glucoseMmol, isig, TYPE_CGM);
        }

        public Point(long timestamp, float glucoseMmol, float isig, int pointType) {
            this.timestamp = timestamp;
            this.glucoseMmol = glucoseMmol;
            this.isig = isig;
            this.pointType = pointType;
        }

        public boolean isCalibration() { return pointType == TYPE_CALIBRATION; }
        public boolean isManualBg() { return pointType == TYPE_MANUAL_BG; }
        public boolean isCgm() { return pointType == TYPE_CGM; }
    }

    public interface OnChartItemClickListener {
        void onTherapyClick(GlucoseHistoryStorage.TherapyEntry entry);
        void onPointClick(Point point);
    }

    private static class TherapyHit {
        final RectF rect;
        final GlucoseHistoryStorage.TherapyEntry entry;
        TherapyHit(RectF rect, GlucoseHistoryStorage.TherapyEntry entry) {
            this.rect = rect;
            this.entry = entry;
        }
    }

    private static class PointHit {
        final RectF rect;
        final Point point;
        PointHit(RectF rect, Point point) {
            this.rect = rect;
            this.point = point;
        }
    }

    private final List<TherapyHit> therapyHits = new ArrayList<>();
    private final List<PointHit> pointHits = new ArrayList<>();
    private OnChartItemClickListener onChartItemClickListener;

    public void setOnChartItemClickListener(OnChartItemClickListener listener) {
        this.onChartItemClickListener = listener;
    }

    private final List<Point> points = new ArrayList<>();
    private final List<GlucoseHistoryStorage.TherapyEntry> therapyEntries = new ArrayList<>();

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final Calendar calendar = Calendar.getInstance();

    // Reusable paths & rects to avoid allocations in onDraw
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final RectF badgeRect = new RectF();
    private final RectF pillRect = new RectF();
    private final RectF nowBadgeRect = new RectF();

    // Paints
    private Paint gridPaint;
    private Paint targetRangePaint;
    private Paint targetBorderPaint;
    private Paint linePaint;
    private Paint fillPaint;
    private Paint pointPaint;
    private Paint pointGlowPaint;
    private Paint textPaint;
    private Paint axisTextPaint;
    private Paint crosshairPaint;
    private Paint tooltipBgPaint;
    private Paint tooltipBorderPaint;
    private Paint tooltipTitlePaint;
    private Paint tooltipSubPaint;
    private Paint nowBadgePaint;
    private Paint nowBadgeBorderPaint;
    private Paint nowBadgeTextPaint;

    // Manual BG & Calibration paints
    private Paint bgMarkerPaint;
    private Paint bgMarkerBorderPaint;
    private Paint bgMarkerCorePaint;
    private Paint bgMarkerStemPaint;
    private Paint bgPillBgPaint;
    private Paint bgPillTextPaint;

    // Therapy paints (Bolus & Carbs)
    private Paint therapyStemPaint;
    private Paint therapyBadgeBgPaint;
    private Paint therapyBadgeBorderPaint;
    private Paint therapyBadgeTextPaint;
    private Paint tooltipTherapyPaint;

    // Unit toggle (mmol/L vs mg/dL)
    private boolean useMgDl = false;

    // Time Zoom Presets (3h, 6h, 12h, 24h)
    private static final int[] ZOOM_PRESETS = new int[]{3, 6, 12, 24};
    private final RectF[] zoomChipRects = new RectF[]{new RectF(), new RectF(), new RectF(), new RectF()};
    private Paint zoomChipBgPaint;
    private Paint zoomChipBorderPaint;
    private Paint zoomChipTextPaint;

    // Glucose bounds & targets (mmol/L)
    private float minRange = 2.0f;
    private float maxRange = 16.0f;
    private final float targetLow = 3.9f;
    private final float targetHigh = 10.0f;

    // Scrolling & Zooming (4h window: 3h past + 1h future buffer)
    private OverScroller scroller;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;
    private float scrollOffset = 0f;
    private float visibleHours = 4.0f; // Default 4 hours on screen
    private static final float FUTURE_HOURS = 1.0f; // 1 hour headroom ahead of current time
    private boolean userScrolledManually = false;

    // Tooltip & Scrubber
    private int selectedPointIndex = -1;
    private boolean isScrubbing = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideTooltipRunnable = new Runnable() {
        @Override
        public void run() {
            selectedPointIndex = -1;
            invalidate();
        }
    };

    public GlucoseChartView(Context context) {
        super(context);
        init(context);
    }

    public GlucoseChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public GlucoseChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        scroller = new OverScroller(context);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.parseColor("#262626"));
        gridPaint.setStrokeWidth(dpToPx(1));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{dpToPx(4), dpToPx(4)}, 0));

        targetRangePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetRangePaint.setColor(Color.parseColor("#1200E676")); // Translucent green target band
        targetRangePaint.setStyle(Paint.Style.FILL);

        targetBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetBorderPaint.setColor(Color.parseColor("#3800E676"));
        targetBorderPaint.setStrokeWidth(dpToPx(1));
        targetBorderPaint.setStyle(Paint.Style.STROKE);
        targetBorderPaint.setPathEffect(new DashPathEffect(new float[]{dpToPx(6), dpToPx(4)}, 0));

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(dpToPx(2.8f));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setStyle(Paint.Style.FILL);

        pointGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointGlowPaint.setStyle(Paint.Style.STROKE);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#888888"));
        textPaint.setTextSize(dpToPx(10));

        axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisTextPaint.setColor(Color.parseColor("#777777"));
        axisTextPaint.setTextSize(dpToPx(10));

        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(Color.parseColor("#66FFFFFF"));
        crosshairPaint.setStrokeWidth(dpToPx(1.2f));
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{dpToPx(4), dpToPx(3)}, 0));

        // Tooltip paints
        tooltipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipBgPaint.setColor(Color.parseColor("#F0181818"));
        tooltipBgPaint.setStyle(Paint.Style.FILL);

        tooltipBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipBorderPaint.setStyle(Paint.Style.STROKE);
        tooltipBorderPaint.setStrokeWidth(dpToPx(1.5f));

        tooltipTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipTitlePaint.setTextSize(dpToPx(13));
        tooltipTitlePaint.setFakeBoldText(true);

        tooltipSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipSubPaint.setColor(Color.parseColor("#B0B0B0"));
        tooltipSubPaint.setTextSize(dpToPx(10.5f));

        // "▶ Now" badge paints
        nowBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nowBadgePaint.setColor(Color.parseColor("#E6222222"));
        nowBadgePaint.setStyle(Paint.Style.FILL);

        nowBadgeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nowBadgeBorderPaint.setColor(Color.parseColor("#00E676"));
        nowBadgeBorderPaint.setStrokeWidth(dpToPx(1.2f));
        nowBadgeBorderPaint.setStyle(Paint.Style.STROKE);

        nowBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nowBadgeTextPaint.setColor(Color.parseColor("#00E676"));
        nowBadgeTextPaint.setTextSize(dpToPx(11));
        nowBadgeTextPaint.setFakeBoldText(true);

        // Manual BG & Calibration paints
        bgMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgMarkerPaint.setColor(Color.parseColor("#FF5252"));
        bgMarkerPaint.setStyle(Paint.Style.FILL);

        bgMarkerBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgMarkerBorderPaint.setColor(Color.WHITE);
        bgMarkerBorderPaint.setStrokeWidth(dpToPx(1.6f));
        bgMarkerBorderPaint.setStyle(Paint.Style.STROKE);

        bgMarkerCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgMarkerCorePaint.setColor(Color.WHITE);
        bgMarkerCorePaint.setStyle(Paint.Style.FILL);

        bgMarkerStemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgMarkerStemPaint.setColor(Color.parseColor("#80FF5252"));
        bgMarkerStemPaint.setStrokeWidth(dpToPx(1f));
        bgMarkerStemPaint.setStyle(Paint.Style.STROKE);
        bgMarkerStemPaint.setPathEffect(new DashPathEffect(new float[]{dpToPx(3), dpToPx(3)}, 0));

        bgPillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPillBgPaint.setColor(Color.parseColor("#E6202020"));
        bgPillBgPaint.setStyle(Paint.Style.FILL);

        bgPillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPillTextPaint.setColor(Color.parseColor("#FF8A80"));
        bgPillTextPaint.setTextSize(dpToPx(9.5f));
        bgPillTextPaint.setFakeBoldText(true);

        // Zoom chips paints
        zoomChipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        zoomChipBgPaint.setStyle(Paint.Style.FILL);

        zoomChipBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        zoomChipBorderPaint.setStyle(Paint.Style.STROKE);
        zoomChipBorderPaint.setStrokeWidth(dpToPx(1f));

        zoomChipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        zoomChipTextPaint.setTextSize(dpToPx(10.5f));
        zoomChipTextPaint.setTextAlign(Paint.Align.CENTER);
        zoomChipTextPaint.setFakeBoldText(true);

        // Therapy paints setup
        therapyStemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        therapyStemPaint.setStyle(Paint.Style.STROKE);
        therapyStemPaint.setStrokeWidth(dpToPx(1f));
        therapyStemPaint.setPathEffect(new DashPathEffect(new float[]{dpToPx(3), dpToPx(3)}, 0));

        therapyBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        therapyBadgeBgPaint.setColor(Color.parseColor("#E6181818"));
        therapyBadgeBgPaint.setStyle(Paint.Style.FILL);

        therapyBadgeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        therapyBadgeBorderPaint.setStyle(Paint.Style.STROKE);
        therapyBadgeBorderPaint.setStrokeWidth(dpToPx(1.2f));

        therapyBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        therapyBadgeTextPaint.setTextSize(dpToPx(10f));
        therapyBadgeTextPaint.setFakeBoldText(true);

        tooltipTherapyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipTherapyPaint.setTextSize(dpToPx(10.5f));
        tooltipTherapyPaint.setFakeBoldText(true);

        setClickable(true);
        setFocusable(true);

        initGestureDetectors(context);
    }

    public void setUseMgDl(boolean useMgDl) {
        this.useMgDl = useMgDl;
        invalidate();
    }

    public boolean isUseMgDl() {
        return useMgDl;
    }

    private void initGestureDetectors(Context context) {
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                for (int i = 0; i < ZOOM_PRESETS.length; i++) {
                    if (zoomChipRects[i].contains(e.getX(), e.getY())) {
                        visibleHours = ZOOM_PRESETS[i];
                        clampScroll();
                        invalidate();
                        return true;
                    }
                }
                if (isScrolledBack() && nowBadgeRect.contains(e.getX(), e.getY())) {
                    scrollToNow(true);
                    return true;
                }

                // Check therapy badges (tap to view/delete)
                if (onChartItemClickListener != null) {
                    for (int i = therapyHits.size() - 1; i >= 0; i--) {
                        TherapyHit th = therapyHits.get(i);
                        if (th.rect.contains(e.getX(), e.getY())) {
                            onChartItemClickListener.onTherapyClick(th.entry);
                            return true;
                        }
                    }

                    // Check manual BG & calibration markers
                    for (int i = pointHits.size() - 1; i >= 0; i--) {
                        PointHit ph = pointHits.get(i);
                        if (ph.rect.contains(e.getX(), e.getY())) {
                            onChartItemClickListener.onPointClick(ph.point);
                            return true;
                        }
                    }

                    // Check if tooltip is tapped
                    if (selectedPointIndex >= 0 && selectedPointIndex < points.size()) {
                        if (pillRect.contains(e.getX(), e.getY())) {
                            Point selPt = points.get(selectedPointIndex);
                            GlucoseHistoryStorage.TherapyEntry nearTherapy = findNearestTherapy(selPt.timestamp);
                            if (nearTherapy != null) {
                                onChartItemClickListener.onTherapyClick(nearTherapy);
                                return true;
                            } else if (selPt.isManualBg() || selPt.isCalibration()) {
                                onChartItemClickListener.onPointClick(selPt);
                                return true;
                            }
                        }
                    }
                }

                selectNearestPoint(e.getX());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                userScrolledManually = true;
                scrollOffset += distanceX;
                clampScroll();
                selectNearestPoint(e2.getX());
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                float maxScroll = getMaxScroll();
                scroller.fling((int) scrollOffset, 0, (int) -velocityX, 0, 0, (int) maxScroll, 0, 0);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                isScrubbing = true;
                selectNearestPoint(e.getX());
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                float scale = detector.getScaleFactor();
                if (scale > 0.01f) {
                    visibleHours /= scale;
                    visibleHours = Math.max(2.0f, Math.min(24.0f, visibleHours));
                    clampScroll();
                    invalidate();
                }
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaleGestureDetector.onTouchEvent(event);
        if (!scaleGestureDetector.isInProgress()) {
            gestureDetector.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                for (int i = 0; i < ZOOM_PRESETS.length; i++) {
                    if (zoomChipRects[i].contains(event.getX(), event.getY())) {
                        visibleHours = ZOOM_PRESETS[i];
                        clampScroll();
                        invalidate();
                        return true;
                    }
                }
                if (isScrolledBack() && nowBadgeRect.contains(event.getX(), event.getY())) {
                    scrollToNow(true);
                    return true;
                }
                boolean hitInteractive = false;
                for (int i = therapyHits.size() - 1; i >= 0; i--) {
                    if (therapyHits.get(i).rect.contains(event.getX(), event.getY())) {
                        hitInteractive = true;
                        break;
                    }
                }
                if (!hitInteractive) {
                    for (int i = pointHits.size() - 1; i >= 0; i--) {
                        if (pointHits.get(i).rect.contains(event.getX(), event.getY())) {
                            hitInteractive = true;
                            break;
                        }
                    }
                }
                if (!hitInteractive && selectedPointIndex >= 0 && pillRect.contains(event.getX(), event.getY())) {
                    hitInteractive = true;
                }
                if (!hitInteractive) {
                    selectNearestPoint(event.getX());
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!scaleGestureDetector.isInProgress() && isScrubbing) {
                    selectNearestPoint(event.getX());
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isScrubbing = false;
                mainHandler.removeCallbacks(hideTooltipRunnable);
                mainHandler.postDelayed(hideTooltipRunnable, 4000);
                break;
        }

        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.getCurrX();
            clampScroll();
            postInvalidateOnAnimation();
        }
    }

    private void selectNearestPoint(float screenX) {
        synchronized (this) {
            if (points.isEmpty()) return;

            float padLeft = dpToPx(16);
            float padRight = dpToPx(42);
            float plotW = getWidth() - padLeft - padRight;
            if (plotW <= 0) return;

            float spacing = getPointSpacing(plotW);
            float minDistance = Float.MAX_VALUE;
            int bestIdx = -1;
            long minTs = points.get(0).timestamp;

            for (int i = 0; i < points.size(); i++) {
                float slotOffset = (points.get(i).timestamp - minTs) / (5 * 60 * 1000f);
                float ptX = padLeft + slotOffset * spacing - scrollOffset;
                float dist = Math.abs(ptX - screenX);
                if (points.get(i).isManualBg() || points.get(i).isCalibration()) {
                    dist *= 0.6f; // Bias towards manual readings
                }
                if (dist < minDistance) {
                    minDistance = dist;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                selectedPointIndex = bestIdx;
                mainHandler.removeCallbacks(hideTooltipRunnable);
                invalidate();
            }
        }
    }

    public synchronized void addPoint(long timestamp, float glucoseMmol, float isig) {
        addPoint(timestamp, glucoseMmol, isig, TYPE_CGM);
    }

    public synchronized void addManualBgPoint(long timestamp, float glucoseMmol) {
        addPoint(timestamp, glucoseMmol, 0f, TYPE_MANUAL_BG);
    }

    public synchronized void addPoint(long timestamp, float glucoseMmol, float isig, int pointType) {
        if (glucoseMmol < 2.0f || glucoseMmol > 25.0f || isig == 65535) {
            return;
        }
        for (int i = points.size() - 1; i >= Math.max(0, points.size() - 12); i--) {
            Point existing = points.get(i);
            if (existing.pointType == pointType) {
                long thresholdMs = (pointType == TYPE_CGM) ? 90_000L : 10_000L;
                if (Math.abs(timestamp - existing.timestamp) < thresholdMs) {
                    return; // Ignore duplicate packet
                }
            }
        }

        boolean needsSort = !points.isEmpty() && timestamp < points.get(points.size() - 1).timestamp;
        points.add(new Point(timestamp, glucoseMmol, isig, pointType));
        if (needsSort) {
            Collections.sort(points, new Comparator<Point>() {
                @Override
                public int compare(Point a, Point b) {
                    return Long.compare(a.timestamp, b.timestamp);
                }
            });
        }
        while (points.size() > 384) {
            points.remove(0);
        }

        if (!userScrolledManually || !isScrolledBack()) {
            scrollToNow(false);
        } else {
            postInvalidate();
        }
    }

    public synchronized void setPoints(List<Point> newPoints) {
        points.clear();
        if (newPoints != null) {
            for (Point p : newPoints) {
                if (p.glucoseMmol >= 2.0f && p.glucoseMmol <= 25.0f && p.isig != 65535) {
                    if (!points.isEmpty()) {
                        Point last = points.get(points.size() - 1);
                        if (p.isCgm() && last.isCgm() && Math.abs(p.timestamp - last.timestamp) < 90_000L) {
                            continue;
                        }
                    }
                    points.add(p);
                }
            }
            Collections.sort(points, new Comparator<Point>() {
                @Override
                public int compare(Point a, Point b) {
                    return Long.compare(a.timestamp, b.timestamp);
                }
            });
            while (points.size() > 384) {
                points.remove(0);
            }
        }
        scrollToNow(false);
    }

    public synchronized List<Point> getPoints() {
        return new ArrayList<>(points);
    }

    public synchronized void setTherapyEntries(List<GlucoseHistoryStorage.TherapyEntry> entries) {
        therapyEntries.clear();
        if (entries != null) {
            therapyEntries.addAll(entries);
        }
        postInvalidate();
    }

    public synchronized void addTherapyEntry(GlucoseHistoryStorage.TherapyEntry entry) {
        if (entry != null) {
            therapyEntries.add(entry);
            postInvalidate();
        }
    }

    public synchronized List<GlucoseHistoryStorage.TherapyEntry> getTherapyEntries() {
        return new ArrayList<>(therapyEntries);
    }

    public synchronized void clear() {
        points.clear();
        therapyEntries.clear();
        selectedPointIndex = -1;
        scrollOffset = 0f;
        userScrolledManually = false;
        postInvalidate();
    }

    private void scrollToNow(boolean animate) {
        float maxScroll = getMaxScroll();
        if (animate) {
            userScrolledManually = false;
            scroller.startScroll((int) scrollOffset, 0, (int) (maxScroll - scrollOffset), 0, 350);
            postInvalidateOnAnimation();
        } else {
            scrollOffset = maxScroll;
            postInvalidate();
        }
    }

    private boolean isScrolledBack() {
        return (getMaxScroll() - scrollOffset) > dpToPx(35);
    }

    private float getPointSpacing(float plotW) {
        float visiblePointsCount = Math.max(12f, visibleHours * 12f);
        return plotW / (visiblePointsCount - 1f);
    }

    private float getFutureSlots() {
        return Math.max(0f, FUTURE_HOURS * 12f);
    }

    private float getContentWidth(float plotW) {
        synchronized (this) {
            int n = points.size();
            if (n <= 1) return plotW;
            float spacing = getPointSpacing(plotW);
            float futureSlots = getFutureSlots();
            long timeSpanMs = points.get(n - 1).timestamp - points.get(0).timestamp;
            float timeSlots = Math.max(1f, timeSpanMs / (5 * 60 * 1000f));
            return (timeSlots + futureSlots) * spacing;
        }
    }

    private float getMaxScroll() {
        float padLeft = dpToPx(16);
        float padRight = dpToPx(42);
        float plotW = Math.max(0, getWidth() - padLeft - padRight);
        float contentW = getContentWidth(plotW);
        return Math.max(0f, contentW - plotW);
    }

    private void clampScroll() {
        float maxScroll = getMaxScroll();
        if (scrollOffset < 0f) scrollOffset = 0f;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padLeft = dpToPx(16);
        float padRight = dpToPx(42);
        float padTop = dpToPx(18);
        float padBottom = dpToPx(24);

        float plotW = w - padLeft - padRight;
        float plotH = h - padTop - padBottom;
        if (plotW <= 0 || plotH <= 0) return;

        clampScroll();

        // 1. Dynamic Autoscale Y Range calculation
        float curMin = targetLow - 0.6f;
        float curMax = targetHigh + 1.2f;

        synchronized (this) {
            for (Point p : points) {
                if (p.glucoseMmol > 0) {
                    if (p.glucoseMmol < curMin) curMin = p.glucoseMmol - 0.6f;
                    if (p.glucoseMmol > curMax) curMax = p.glucoseMmol + 1.2f;
                }
            }
        }
        minRange = Math.max(1.5f, curMin);
        maxRange = Math.max(12.0f, curMax);

        // 2. Draw Target Range (3.9 - 10.0 mmol/L) band and borders
        float targetYTop = valToY(targetHigh, padTop, plotH);
        float targetYBottom = valToY(targetLow, padTop, plotH);
        canvas.drawRect(padLeft, targetYTop, padLeft + plotW, targetYBottom, targetRangePaint);
        canvas.drawLine(padLeft, targetYTop, padLeft + plotW, targetYTop, targetBorderPaint);
        canvas.drawLine(padLeft, targetYBottom, padLeft + plotW, targetYBottom, targetBorderPaint);

        // 3. Draw horizontal grid lines & Y-axis labels
        float[] gridLevels = new float[]{4.0f, 7.0f, 10.0f, 13.0f, 16.0f, 19.0f, 22.0f};
        for (float level : gridLevels) {
            if (level >= minRange && level <= maxRange) {
                float y = valToY(level, padTop, plotH);
                canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint);
                String label = useMgDl ? String.valueOf(Math.round(level * 18.0182f)) : String.format(Locale.US, "%.1f", level);
                canvas.drawText(label, padLeft + plotW + dpToPx(6), y + dpToPx(3.5f), axisTextPaint);
            }
        }

        synchronized (this) {
            therapyHits.clear();
            pointHits.clear();
            if (points.isEmpty()) {
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Waiting for sensor data (~5 min)...", padLeft + plotW / 2f, padTop + plotH / 2f, textPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);
                return;
            }

            int n = points.size();
            float spacing = getPointSpacing(plotW);
            long minTs = points.get(0).timestamp;

            float[] ptX = new float[n];
            float[] ptY = new float[n];
            for (int i = 0; i < n; i++) {
                float slotOffset = (points.get(i).timestamp - minTs) / (5 * 60 * 1000f);
                ptX[i] = padLeft + slotOffset * spacing - scrollOffset;
                ptY[i] = valToY(points.get(i).glucoseMmol, padTop, plotH);
            }

            Point latestCgm = null;
            for (int i = n - 1; i >= 0; i--) {
                if (points.get(i).isCgm()) {
                    latestCgm = points.get(i);
                    break;
                }
            }
            if (latestCgm == null) latestCgm = points.get(n - 1);
            int trendColor = getGlucoseColor(latestCgm.glucoseMmol);

            float posHigh = Math.max(0f, Math.min(1f, (valToY(targetHigh, padTop, plotH) - padTop) / plotH));
            float posLow = Math.max(0f, Math.min(1f, (valToY(targetLow, padTop, plotH) - padTop) / plotH));
            if (posHigh > posLow) {
                float tmp = posHigh;
                posHigh = posLow;
                posLow = tmp;
            }

            LinearGradient lineGradient = new LinearGradient(
                    0, padTop, 0, padTop + plotH,
                    new int[]{
                            Color.parseColor("#FFAB00"),
                            Color.parseColor("#FFAB00"),
                            Color.parseColor("#00E676"),
                            Color.parseColor("#00E676"),
                            Color.parseColor("#FF5252"),
                            Color.parseColor("#FF5252")
                    },
                    new float[]{
                            0f,
                            posHigh,
                            posHigh,
                            posLow,
                            posLow,
                            1f
                    },
                    Shader.TileMode.CLAMP
            );
            linePaint.setShader(lineGradient);

            // 4. Clip chart area
            canvas.save();
            canvas.clipRect(padLeft, padTop, padLeft + plotW, padTop + plotH);

            // 5. Separate CGM points for continuous spline interpolation
            List<Integer> cgmIndices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (points.get(i).isCgm()) {
                    cgmIndices.add(i);
                }
            }

            int cgmCount = cgmIndices.size();
            if (cgmCount > 0) {
                linePath.reset();
                fillPath.reset();

                int firstIdx = cgmIndices.get(0);
                linePath.moveTo(ptX[firstIdx], ptY[firstIdx]);
                fillPath.moveTo(ptX[firstIdx], padTop + plotH);
                fillPath.lineTo(ptX[firstIdx], ptY[firstIdx]);

                if (cgmCount == 2) {
                    int nextIdx = cgmIndices.get(1);
                    linePath.lineTo(ptX[nextIdx], ptY[nextIdx]);
                    fillPath.lineTo(ptX[nextIdx], ptY[nextIdx]);
                } else if (cgmCount > 2) {
                    for (int k = 0; k < cgmCount - 1; k++) {
                        int idx0 = cgmIndices.get((k > 0) ? k - 1 : k);
                        int idx1 = cgmIndices.get(k);
                        int idx2 = cgmIndices.get(k + 1);
                        int idx3 = cgmIndices.get((k + 2 < cgmCount) ? k + 2 : k + 1);

                        float x0 = ptX[idx0], y0 = ptY[idx0];
                        float x1 = ptX[idx1], y1 = ptY[idx1];
                        float x2 = ptX[idx2], y2 = ptY[idx2];
                        float x3 = ptX[idx3], y3 = ptY[idx3];

                        float dx1 = x2 - x0;
                        float dy1 = y2 - y0;
                        float dx2 = x3 - x1;
                        float dy2 = y3 - y1;

                        float segDx = x2 - x1;

                        float cp1x = x1 + segDx * 0.35f;
                        float cp1y = y1 + (dy1 / (dx1 != 0 ? dx1 : 1f)) * segDx * 0.35f;

                        float cp2x = x2 - segDx * 0.35f;
                        float cp2y = y2 - (dy2 / (dx2 != 0 ? dx2 : 1f)) * segDx * 0.35f;

                        if ((y1 - y0) * (y2 - y1) <= 0) cp1y = y1;
                        if ((y2 - y1) * (y3 - y2) <= 0) cp2y = y2;

                        float minY = Math.min(y1, y2);
                        float maxY = Math.max(y1, y2);
                        cp1y = Math.max(minY, Math.min(maxY, cp1y));
                        cp2y = Math.max(minY, Math.min(maxY, cp2y));

                        linePath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2);
                        fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2);
                    }
                }

                int lastCgmIdx = cgmIndices.get(cgmCount - 1);
                fillPath.lineTo(ptX[lastCgmIdx], padTop + plotH);
                fillPath.close();

                LinearGradient gradient = new LinearGradient(
                        0, padTop, 0, padTop + plotH,
                        trendColor & 0x38FFFFFF,
                        0x00000000,
                        Shader.TileMode.CLAMP
                );
                fillPaint.setShader(gradient);
                canvas.drawPath(fillPath, fillPaint);
                canvas.drawPath(linePath, linePaint);

                float dotRadius = dpToPx(2.5f);
                for (int idx : cgmIndices) {
                    if (ptX[idx] < padLeft - dpToPx(10) || ptX[idx] > padLeft + plotW + dpToPx(10)) continue;
                    pointPaint.setColor(getGlucoseColor(points.get(idx).glucoseMmol));
                    canvas.drawCircle(ptX[idx], ptY[idx], dotRadius, pointPaint);
                }
            }

            // 6. Draw Manual Blood Glucose & Calibration Markers
            for (int i = 0; i < n; i++) {
                Point p = points.get(i);
                if (!p.isManualBg() && !p.isCalibration()) continue;

                float mx = ptX[i];
                float my = ptY[i];
                if (mx < padLeft - dpToPx(20) || mx > padLeft + plotW + dpToPx(20)) continue;

                pointHits.add(new PointHit(new RectF(mx - dpToPx(16), my - dpToPx(24), mx + dpToPx(16), my + dpToPx(16)), p));

                canvas.drawLine(mx, my + dpToPx(8), mx, Math.min(padTop + plotH, my + dpToPx(28)), bgMarkerStemPaint);

                int markerColor = p.isCalibration() ? Color.parseColor("#FFD54F") : Color.parseColor("#FF5252");
                bgMarkerPaint.setColor(markerColor);

                canvas.drawCircle(mx, my, dpToPx(6.5f), bgMarkerPaint);
                canvas.drawCircle(mx, my, dpToPx(6.5f), bgMarkerBorderPaint);
                canvas.drawCircle(mx, my, dpToPx(2.5f), bgMarkerCorePaint);

                if (i != selectedPointIndex) {
                    String valStr = useMgDl ? String.valueOf(Math.round(p.glucoseMmol * 18.0182f)) : String.format(Locale.US, "%.1f", p.glucoseMmol);
                    float valW = bgPillTextPaint.measureText(valStr);
                    float pillW = valW + dpToPx(8);
                    float pillH = dpToPx(15);
                    float pillX = mx - pillW / 2f;
                    float pillY = my - dpToPx(22);

                    badgeRect.set(pillX, pillY, pillX + pillW, pillY + pillH);
                    canvas.drawRoundRect(badgeRect, dpToPx(4), dpToPx(4), bgPillBgPaint);
                    bgPillTextPaint.setColor(markerColor);
                    canvas.drawText(valStr, pillX + dpToPx(4), pillY + dpToPx(11), bgPillTextPaint);
                }
            }

            // 6b. Draw Therapy Badges & Vertical Dashed Stems (Bolus & Carbs)
            drawTherapyMarkers(canvas, padLeft, padTop, plotW, plotH, minTs, spacing);

            // 7. Highlight Selected Point / Latest Point
            int highlightIdx = (selectedPointIndex >= 0 && selectedPointIndex < n) ? selectedPointIndex : (n - 1);
            float hx = ptX[highlightIdx];
            float hy = ptY[highlightIdx];
            Point hp = points.get(highlightIdx);
            int hColor = (hp.isManualBg() || hp.isCalibration()) ?
                    (hp.isCalibration() ? Color.parseColor("#FFD54F") : Color.parseColor("#FF5252")) :
                    getGlucoseColor(hp.glucoseMmol);

            if (selectedPointIndex >= 0) {
                canvas.drawLine(hx, padTop, hx, padTop + plotH, crosshairPaint);
            }

            pointGlowPaint.setColor(hColor & 0x44FFFFFF);
            pointGlowPaint.setStrokeWidth(dpToPx(4.5f));
            canvas.drawCircle(hx, hy, dpToPx(7.5f), pointGlowPaint);

            pointPaint.setColor(hColor);
            canvas.drawCircle(hx, hy, dpToPx(4.2f), pointPaint);

            pointPaint.setColor(Color.WHITE);
            canvas.drawCircle(hx, hy, dpToPx(2.2f), pointPaint);

            canvas.restore();

            // 7b. Draw Zoom Preset Chips (3h, 6h, 12h, 24h)
            drawZoomChips(canvas, padLeft, padTop, plotW);

            // 8. Time Axis Markers at the Bottom
            drawTimeAxis(canvas, padLeft, padTop, plotW, plotH, ptX);

            // 9. Floating Crosshair Tooltip
            if (selectedPointIndex >= 0 && selectedPointIndex < n) {
                drawTooltip(canvas, padLeft, padTop, plotW, plotH, hx, hy, points.get(selectedPointIndex), hColor);
            }

            // 10. "▶ Now" Jump Badge
            if (isScrolledBack()) {
                drawNowBadge(canvas, padLeft, padTop, plotW, plotH);
            }
        }
    }

    private void drawTimeAxis(Canvas canvas, float padLeft, float padTop, float plotW, float plotH, float[] ptX) {
        int n = points.size();
        if (n == 0) return;

        axisTextPaint.setTextAlign(Paint.Align.CENTER);
        float timeY = padTop + plotH + dpToPx(15);
        float spacing = getPointSpacing(plotW);
        long minTs = points.get(0).timestamp;
        float latestX = ptX[n - 1];

        boolean drewNow = false;
        if (latestX >= padLeft + dpToPx(15) && latestX <= padLeft + plotW - dpToPx(15)) {
            axisTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Now", latestX, timeY, axisTextPaint);
            canvas.drawLine(latestX, padTop + plotH, latestX, padTop + plotH + dpToPx(3), gridPaint);
            drewNow = true;
        }

        long startVisibleTs = minTs + (long) ((scrollOffset / spacing) * 5 * 60 * 1000f);
        long endVisibleTs = minTs + (long) (((scrollOffset + plotW) / spacing) * 5 * 60 * 1000f);

        calendar.setTimeInMillis(startVisibleTs);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long hourTs = calendar.getTimeInMillis();
        if (hourTs < startVisibleTs) {
            calendar.add(Calendar.HOUR_OF_DAY, 1);
            hourTs = calendar.getTimeInMillis();
        }

        float lastDrawnHourX = -1000f;
        while (hourTs <= endVisibleTs) {
            float slotOffset = (hourTs - minTs) / (5 * 60 * 1000f);
            float hourX = padLeft + slotOffset * spacing - scrollOffset;
            if (hourX >= padLeft + dpToPx(15) && hourX <= padLeft + plotW - dpToPx(15)) {
                if ((!drewNow || Math.abs(hourX - latestX) > dpToPx(42)) && (hourX - lastDrawnHourX >= dpToPx(44))) {
                    String hourStr = timeFormat.format(calendar.getTime());
                    canvas.drawText(hourStr, hourX, timeY, axisTextPaint);
                    canvas.drawLine(hourX, padTop + plotH, hourX, padTop + plotH + dpToPx(4), gridPaint);
                    lastDrawnHourX = hourX;
                }
            }
            calendar.add(Calendar.HOUR_OF_DAY, 1);
            hourTs = calendar.getTimeInMillis();
        }

        axisTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawZoomChips(Canvas canvas, float padLeft, float padTop, float plotW) {
        float chipW = dpToPx(34);
        float chipH = dpToPx(22);
        float gap = dpToPx(6);
        float totalW = ZOOM_PRESETS.length * chipW + (ZOOM_PRESETS.length - 1) * gap;
        float startX = padLeft + (plotW - totalW) / 2f;
        float startY = padTop + dpToPx(6);
        Paint.FontMetrics fm = zoomChipTextPaint.getFontMetrics();

        for (int i = 0; i < ZOOM_PRESETS.length; i++) {
            float cx = startX + i * (chipW + gap);
            RectF rect = zoomChipRects[i];
            rect.set(cx, startY, cx + chipW, startY + chipH);

            boolean active = Math.abs(visibleHours - ZOOM_PRESETS[i]) < 0.4f;
            if (active) {
                zoomChipBgPaint.setColor(Color.parseColor("#3300E676"));
                zoomChipBorderPaint.setColor(Color.parseColor("#00E676"));
                zoomChipBorderPaint.setStrokeWidth(dpToPx(1.4f));
                zoomChipTextPaint.setColor(Color.parseColor("#00E676"));
            } else {
                zoomChipBgPaint.setColor(Color.parseColor("#CC1E1E1E"));
                zoomChipBorderPaint.setColor(Color.parseColor("#44FFFFFF"));
                zoomChipBorderPaint.setStrokeWidth(dpToPx(1f));
                zoomChipTextPaint.setColor(Color.parseColor("#AAAAAA"));
            }

            canvas.drawRoundRect(rect, dpToPx(6), dpToPx(6), zoomChipBgPaint);
            canvas.drawRoundRect(rect, dpToPx(6), dpToPx(6), zoomChipBorderPaint);

            String label = ZOOM_PRESETS[i] + "h";
            float textY = rect.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, rect.centerX(), textY, zoomChipTextPaint);
        }
    }

    private void drawTooltip(Canvas canvas, float padLeft, float padTop, float plotW, float plotH,
                            float hx, float hy, Point pt, int color) {
        int mgDl = Math.round(pt.glucoseMmol * 18.0182f);
        String title;
        String sub;

        if (useMgDl) {
            if (pt.isManualBg()) {
                title = String.format(Locale.US, "🩸 Meter BG: %d mg/dL • %.1f", mgDl, pt.glucoseMmol);
                sub = String.format(Locale.US, "%s  •  Fingerstick Check", timeFormat.format(new Date(pt.timestamp)));
            } else if (pt.isCalibration()) {
                title = String.format(Locale.US, "🩸 Calibration: %d mg/dL • %.1f", mgDl, pt.glucoseMmol);
                sub = String.format(Locale.US, "%s  •  Transmitter Calibrated", timeFormat.format(new Date(pt.timestamp)));
            } else {
                title = String.format(Locale.US, "%d mg/dL  •  %.1f mmol/L", mgDl, pt.glucoseMmol);
                if (Math.abs(pt.isig - mgDl) <= 2.0f || pt.isig <= 0f) {
                    sub = String.format(Locale.US, "%s  •  Sensor SG: %d mg/dL",
                            timeFormat.format(new Date(pt.timestamp)), Math.round(pt.isig > 0 ? pt.isig : mgDl));
                } else {
                    sub = String.format(Locale.US, "%s  •  ISIG: %.1f nA",
                            timeFormat.format(new Date(pt.timestamp)), pt.isig);
                }
            }
        } else {
            if (pt.isManualBg()) {
                title = String.format(Locale.US, "🩸 Meter BG: %.1f • %d mg/dL", pt.glucoseMmol, mgDl);
                sub = String.format(Locale.US, "%s  •  Fingerstick Check", timeFormat.format(new Date(pt.timestamp)));
            } else if (pt.isCalibration()) {
                title = String.format(Locale.US, "🩸 Calibration: %.1f • %d mg/dL", pt.glucoseMmol, mgDl);
                sub = String.format(Locale.US, "%s  •  Transmitter Calibrated", timeFormat.format(new Date(pt.timestamp)));
            } else {
                title = String.format(Locale.US, "%.1f mmol/L  •  %d mg/dL", pt.glucoseMmol, mgDl);
                if (Math.abs(pt.isig - mgDl) <= 2.0f || pt.isig <= 0f) {
                    sub = String.format(Locale.US, "%s  •  Sensor SG: %d mg/dL",
                            timeFormat.format(new Date(pt.timestamp)), Math.round(pt.isig > 0 ? pt.isig : mgDl));
                } else {
                    sub = String.format(Locale.US, "%s  •  ISIG: %.1f nA",
                            timeFormat.format(new Date(pt.timestamp)), pt.isig);
                }
            }
        }

        GlucoseHistoryStorage.TherapyEntry nearestTherapy = findNearestTherapy(pt.timestamp);
        String therapyStr = null;
        int therapyAccentColor = Color.parseColor("#00E5FF");
        if (nearestTherapy != null && (nearestTherapy.hasBolus() || nearestTherapy.hasCarbs())) {
            StringBuilder sb = new StringBuilder();
            if (nearestTherapy.hasCarbs()) {
                String carbStr = (nearestTherapy.carbsGrams == (long) nearestTherapy.carbsGrams)
                        ? String.format(Locale.US, "%.0fg", nearestTherapy.carbsGrams)
                        : String.format(Locale.US, "%.1fg", nearestTherapy.carbsGrams);
                sb.append("🍞 ").append(carbStr);
            }
            if (nearestTherapy.hasBolus()) {
                if (sb.length() > 0) sb.append("  |  ");
                String bolusStr = (nearestTherapy.bolusUnits == (long) nearestTherapy.bolusUnits)
                        ? String.format(Locale.US, "%.0fU", nearestTherapy.bolusUnits)
                        : String.format(Locale.US, "%.1fU", nearestTherapy.bolusUnits);
                sb.append("💉 ").append(bolusStr);
            }
            if (nearestTherapy.note != null && !nearestTherapy.note.trim().isEmpty()) {
                sb.append("  •  ").append(nearestTherapy.note.trim());
            }
            therapyStr = sb.toString();
            if (!nearestTherapy.hasBolus()) {
                therapyAccentColor = Color.parseColor("#FFAB00");
            } else {
                therapyAccentColor = Color.parseColor("#00E5FF");
            }
        }

        float titleW = tooltipTitlePaint.measureText(title);
        float subW = tooltipSubPaint.measureText(sub);
        float therapyW = (therapyStr != null) ? tooltipTherapyPaint.measureText(therapyStr) : 0f;
        float pillW = Math.max(Math.max(titleW, subW), therapyW) + dpToPx(24);
        float pillH = (therapyStr != null) ? dpToPx(62) : dpToPx(44);

        float pillX = hx - pillW / 2f;
        pillX = Math.max(padLeft + dpToPx(4), Math.min(padLeft + plotW - pillW - dpToPx(4), pillX));

        float pillY = hy - pillH - dpToPx(14);
        if (pillY < padTop + dpToPx(4)) {
            pillY = hy + dpToPx(16);
        }
        if (pillY + pillH > padTop + plotH - dpToPx(4)) {
            pillY = padTop + plotH - pillH - dpToPx(4);
        }
        pillY = Math.max(padTop + dpToPx(4), pillY);

        pillRect.set(pillX, pillY, pillX + pillW, pillY + pillH);
        canvas.drawRoundRect(pillRect, dpToPx(8), dpToPx(8), tooltipBgPaint);

        tooltipBorderPaint.setColor(color);
        canvas.drawRoundRect(pillRect, dpToPx(8), dpToPx(8), tooltipBorderPaint);

        tooltipTitlePaint.setColor(color);
        canvas.drawText(title, pillX + dpToPx(12), pillY + dpToPx(19), tooltipTitlePaint);
        canvas.drawText(sub, pillX + dpToPx(12), pillY + dpToPx(36), tooltipSubPaint);

        if (therapyStr != null) {
            tooltipTherapyPaint.setColor(therapyAccentColor);
            canvas.drawText(therapyStr, pillX + dpToPx(12), pillY + dpToPx(53), tooltipTherapyPaint);
        }
    }

    private GlucoseHistoryStorage.TherapyEntry findNearestTherapy(long timestamp) {
        if (therapyEntries.isEmpty()) return null;
        long windowMs = (long) (7.5f * 60 * 1000L); // ±7.5 minutes
        GlucoseHistoryStorage.TherapyEntry best = null;
        long minDiff = Long.MAX_VALUE;
        for (GlucoseHistoryStorage.TherapyEntry entry : therapyEntries) {
            long diff = Math.abs(entry.timestamp - timestamp);
            if (diff <= windowMs && diff < minDiff) {
                minDiff = diff;
                best = entry;
            }
        }
        return best;
    }

    private void drawTherapyMarkers(Canvas canvas, float padLeft, float padTop, float plotW, float plotH,
                                    long minTs, float spacing) {
        if (therapyEntries.isEmpty()) return;

        List<RectF> placedBadges = new ArrayList<>();

        // Zoom chips bounds to avoid overlapping with top chips
        float chipW = dpToPx(34);
        float gap = dpToPx(6);
        float totalZoomW = ZOOM_PRESETS.length * chipW + (ZOOM_PRESETS.length - 1) * gap;
        float zoomStartX = padLeft + (plotW - totalZoomW) / 2f;
        float zoomEndX = zoomStartX + totalZoomW;
        float zoomBottomY = padTop + dpToPx(6) + dpToPx(22);

        for (int i = 0; i < therapyEntries.size(); i++) {
            GlucoseHistoryStorage.TherapyEntry entry = therapyEntries.get(i);
            if (!entry.hasBolus() && !entry.hasCarbs()) continue;

            float slotOffset = (entry.timestamp - minTs) / (5 * 60 * 1000f);
            float entryX = padLeft + slotOffset * spacing - scrollOffset;

            // Cull if completely off-screen
            if (entryX < padLeft - dpToPx(60) || entryX > padLeft + plotW + dpToPx(60)) {
                continue;
            }

            boolean hasBolus = entry.hasBolus();
            boolean hasCarbs = entry.hasCarbs();

            String bolusStr = (entry.bolusUnits == (long) entry.bolusUnits)
                    ? String.format(Locale.US, "%.0fU", entry.bolusUnits)
                    : String.format(Locale.US, "%.1fU", entry.bolusUnits);
            String carbStr = (entry.carbsGrams == (long) entry.carbsGrams)
                    ? String.format(Locale.US, "%.0fg", entry.carbsGrams)
                    : String.format(Locale.US, "%.1fg", entry.carbsGrams);

            String label;
            int accentColor;
            if (hasBolus && hasCarbs) {
                label = "🍞 " + carbStr + " · 💉 " + bolusStr;
                accentColor = Color.parseColor("#00E5FF");
            } else if (hasBolus) {
                label = "💉 " + bolusStr;
                accentColor = Color.parseColor("#00E5FF");
            } else {
                label = "🍞 " + carbStr;
                accentColor = Color.parseColor("#FFAB00");
            }

            float textW = therapyBadgeTextPaint.measureText(label);
            float badgeW = textW + dpToPx(10);
            float badgeH = dpToPx(17);
            float badgeX = entryX - badgeW / 2f;

            // Base Y: if under zoom chips, drop below them
            float baseY = padTop + dpToPx(6);
            if (badgeX + badgeW >= zoomStartX - dpToPx(4) && badgeX <= zoomEndX + dpToPx(4)) {
                baseY = zoomBottomY + dpToPx(5);
            }

            float badgeY = baseY;
            int level = 0;
            boolean collides;
            do {
                collides = false;
                for (RectF prev : placedBadges) {
                    if (Math.abs(badgeX - prev.left) < (badgeW + dpToPx(4))
                            && Math.abs(badgeY - prev.top) < (badgeH + dpToPx(3))) {
                        level++;
                        badgeY = baseY + level * (badgeH + dpToPx(3));
                        collides = true;
                        break;
                    }
                }
            } while (collides && level < 4);

            RectF bRect = new RectF(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH);
            placedBadges.add(bRect);
            therapyHits.add(new TherapyHit(new RectF(badgeX - dpToPx(4), badgeY - dpToPx(4), badgeX + badgeW + dpToPx(4), badgeY + badgeH + dpToPx(4)), entry));

            // 1. Dashed vertical stem line from badge bottom to chart bottom
            therapyStemPaint.setColor(accentColor & 0x66FFFFFF);
            canvas.drawLine(entryX, badgeY + badgeH, entryX, padTop + plotH, therapyStemPaint);

            // 2. High-contrast rounded badge background
            canvas.drawRoundRect(bRect, dpToPx(4), dpToPx(4), therapyBadgeBgPaint);

            // 3. Colored border
            therapyBadgeBorderPaint.setColor(accentColor);
            canvas.drawRoundRect(bRect, dpToPx(4), dpToPx(4), therapyBadgeBorderPaint);

            // 4. Badge text
            therapyBadgeTextPaint.setColor(hasBolus && hasCarbs ? Color.WHITE : accentColor);
            Paint.FontMetrics fm = therapyBadgeTextPaint.getFontMetrics();
            float textY = bRect.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, badgeX + dpToPx(5), textY, therapyBadgeTextPaint);
        }
    }

    private void drawNowBadge(Canvas canvas, float padLeft, float padTop, float plotW, float plotH) {
        float badgeW = dpToPx(62);
        float badgeH = dpToPx(26);
        float badgeX = padLeft + plotW - badgeW - dpToPx(6);
        float badgeY = padTop + plotH - badgeH - dpToPx(8);

        nowBadgeRect.set(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH);
        canvas.drawRoundRect(nowBadgeRect, dpToPx(13), dpToPx(13), nowBadgePaint);
        canvas.drawRoundRect(nowBadgeRect, dpToPx(13), dpToPx(13), nowBadgeBorderPaint);

        nowBadgeTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("▶ Now", badgeX + badgeW / 2f, badgeY + dpToPx(17), nowBadgeTextPaint);
    }

    private float valToY(float val, float padTop, float plotH) {
        float clamped = Math.max(minRange, Math.min(maxRange, val));
        float ratio = (clamped - minRange) / (maxRange - minRange);
        return padTop + plotH * (1.0f - ratio);
    }

    private int getGlucoseColor(float mmol) {
        if (mmol < targetLow) {
            return Color.parseColor("#FF5252");
        } else if (mmol > targetHigh) {
            return Color.parseColor("#FFAB00");
        } else {
            return Color.parseColor("#00E676");
        }
    }

    private float dpToPx(float dp) {
        return dp * getContext().getResources().getDisplayMetrics().density;
    }
}
