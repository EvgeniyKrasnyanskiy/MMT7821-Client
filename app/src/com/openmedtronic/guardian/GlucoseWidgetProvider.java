package com.openmedtronic.guardian;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight, battery-efficient home screen desktop widget for MMT7821-Client.
 * Displays current glucose (mmol/L & mg/dL), trend arrow,
 * calibration status/countdown, and transmitter battery.
 */
public class GlucoseWidgetProvider extends AppWidgetProvider {

    private static final String PREFS_NAME = "guardian_prefs";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAllWidgets(Context context) {
        if (context == null) return;
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        ComponentName thisWidget = new ComponentName(context, GlucoseWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
        if (appWidgetIds != null && appWidgetIds.length > 0) {
            for (int appWidgetId : appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId);
            }
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_glucose);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        boolean useMgDl = prefs.getBoolean("use_mgdl", false);
        GuardianBleService service = GuardianBleService.getInstance();
        boolean isConnected = (service != null && service.isDeviceConnected());
        boolean inWarmup = (service != null) ? service.isInWarmup() : prefs.getBoolean("is_in_warmup", false);
        boolean calPending = (service != null) ? service.isCalPending() : prefs.getBoolean("is_cal_pending", false);
        boolean calRequired = (service != null) ? service.isCalRequired() : prefs.getBoolean("is_cal_required", false);
        long warmupRemainMs = (service != null) ? service.getWarmupRemainingMs() : 0;

        float glucoseMmol = (service != null && service.getLastGlucoseMmol() > 0) ?
                service.getLastGlucoseMmol() : prefs.getFloat("last_mmol", 0f);
        float glucoseMgDl = (service != null && service.getLastGlucoseMgDl() > 0) ?
                service.getLastGlucoseMgDl() : prefs.getFloat("last_mgdl", 0f);
        double trendRate = (service != null) ? service.getLastTrendRate() : (double) prefs.getFloat("last_trend", Float.NaN);
        long lastMeasureMs = (service != null && service.getLastMeasurementTimeMs() > 0) ?
                service.getLastMeasurementTimeMs() : prefs.getLong("last_measurement_time", 0);
        long lastCalMs = prefs.getLong("cal_time", 0);
        int battery = (isConnected && service != null && service.getBatteryLevel() >= 0) ?
                service.getBatteryLevel() : -1;

        long now = System.currentTimeMillis();
        boolean isStale = (lastMeasureMs > 0 && (now - lastMeasureMs) > 11 * 60 * 1000L);
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        try {
            // 1. Glucose & Trend Arrow
            if (!isConnected) {
                views.setTextViewText(R.id.textWidgetGlucose, "--");
                views.setTextColor(R.id.textWidgetGlucose, Color.parseColor("#888888"));
                views.setTextViewText(R.id.textWidgetTrend, "");
                views.setTextColor(R.id.textWidgetTrend, Color.parseColor("#888888"));
                views.setTextViewText(R.id.textWidgetUnit, "");
                String lastTime = (lastMeasureMs > 0) ? timeFmt.format(new Date(lastMeasureMs)) : "--:--";
                views.setTextViewText(R.id.textWidgetSub, "⚠️ Disconnected (" + lastTime + ")");
            } else if (inWarmup) {
                views.setTextViewText(R.id.textWidgetGlucose, "--");
                views.setTextColor(R.id.textWidgetGlucose, Color.parseColor("#4FC3F7"));
                views.setTextViewText(R.id.textWidgetTrend, "⏳");
                views.setTextColor(R.id.textWidgetTrend, Color.parseColor("#4FC3F7"));
                views.setTextViewText(R.id.textWidgetUnit, "");

                long totalSec = Math.max(0, warmupRemainMs / 1000);
                long hours = totalSec / 3600;
                long mins = (totalSec % 3600) / 60;
                views.setTextViewText(R.id.textWidgetSub, String.format(Locale.US, "Warm-up: %02d:%02d", hours, mins));
            } else if (calPending) {
                views.setTextViewText(R.id.textWidgetGlucose, "--");
                views.setTextColor(R.id.textWidgetGlucose, Color.parseColor("#4FC3F7"));
                views.setTextViewText(R.id.textWidgetTrend, "⏳");
                views.setTextColor(R.id.textWidgetTrend, Color.parseColor("#4FC3F7"));
                views.setTextViewText(R.id.textWidgetUnit, "");
                views.setTextViewText(R.id.textWidgetSub, "Processing cal...");
            } else if (calRequired) {
                views.setTextViewText(R.id.textWidgetGlucose, "--");
                views.setTextColor(R.id.textWidgetGlucose, Color.parseColor("#FFD54F"));
                views.setTextViewText(R.id.textWidgetTrend, "");
                views.setTextColor(R.id.textWidgetTrend, Color.parseColor("#FFD54F"));
                views.setTextViewText(R.id.textWidgetUnit, "");
                views.setTextViewText(R.id.textWidgetSub, "Calibrate sensor");
            } else if (isStale) {
                views.setTextViewText(R.id.textWidgetGlucose, "--");
                views.setTextColor(R.id.textWidgetGlucose, Color.parseColor("#888888"));
                views.setTextViewText(R.id.textWidgetTrend, "");
                views.setTextColor(R.id.textWidgetTrend, Color.parseColor("#888888"));
                views.setTextViewText(R.id.textWidgetUnit, "");
                String timeStr = (lastMeasureMs > 0) ? timeFmt.format(new Date(lastMeasureMs)) : "--:--";
                views.setTextViewText(R.id.textWidgetSub, "⚠️ No Signal (" + timeStr + ")");
            } else if (glucoseMmol > 0) {
                if (useMgDl) {
                    views.setTextViewText(R.id.textWidgetGlucose, String.format(Locale.US, "%d", Math.round(glucoseMgDl)));
                } else {
                    views.setTextViewText(R.id.textWidgetGlucose, String.format(Locale.US, "%.1f", glucoseMmol));
                }

                int glucoseColor;
                if (glucoseMmol < 3.9f) {
                    glucoseColor = Color.parseColor("#FF5252");
                } else if (glucoseMmol > 10.0f) {
                    glucoseColor = Color.parseColor("#FFB300");
                } else {
                    glucoseColor = Color.parseColor("#00E676");
                }
                views.setTextColor(R.id.textWidgetGlucose, glucoseColor);

                String arrow = MainActivity.getTrendArrow(trendRate);
                views.setTextViewText(R.id.textWidgetTrend, arrow);
                views.setTextColor(R.id.textWidgetTrend, glucoseColor);

                views.setTextViewText(R.id.textWidgetUnit, useMgDl ? "mg/dL" : "mmol/L");

                String timeStr = (lastMeasureMs > 0) ? timeFmt.format(new Date(lastMeasureMs)) : "--:--";
                views.setTextViewText(R.id.textWidgetSub, "🕒 " + timeStr);
            } else {
                views.setTextViewText(R.id.textWidgetGlucose, "--");
                views.setTextColor(R.id.textWidgetGlucose, Color.parseColor("#888888"));
                views.setTextViewText(R.id.textWidgetTrend, "");
                views.setTextColor(R.id.textWidgetTrend, Color.parseColor("#888888"));
                views.setTextViewText(R.id.textWidgetUnit, "");
                views.setTextViewText(R.id.textWidgetSub, "Waiting for sensor...");
            }

            // 2. Calibration Info Badge
            if (!isConnected) {
                views.setTextViewText(R.id.textWidgetCal, "⚠️ Offline");
                views.setTextColor(R.id.textWidgetCal, Color.parseColor("#888888"));
            } else if (inWarmup) {
                views.setTextViewText(R.id.textWidgetCal, "⏳ Warm-up");
                views.setTextColor(R.id.textWidgetCal, Color.parseColor("#4FC3F7"));
            } else if (calPending) {
                views.setTextViewText(R.id.textWidgetCal, "⏳ In progress");
                views.setTextColor(R.id.textWidgetCal, Color.parseColor("#4FC3F7"));
            } else if (calRequired) {
                views.setTextViewText(R.id.textWidgetCal, "🩸 Cal now");
                views.setTextColor(R.id.textWidgetCal, Color.parseColor("#FF5252"));
            } else if (lastCalMs <= 0) {
                views.setTextViewText(R.id.textWidgetCal, "🩸 --");
                views.setTextColor(R.id.textWidgetCal, Color.parseColor("#888888"));
            } else {
                long calIntervalMs = 12 * 3600 * 1000L;
                long elapsedCalMs = now - lastCalMs;
                if (elapsedCalMs < calIntervalMs) {
                    long remainMs = calIntervalMs - elapsedCalMs;
                    long remainHours = remainMs / (3600 * 1000L);
                    long remainMins = (remainMs % (3600 * 1000L)) / (60 * 1000L);
                    if (remainHours > 0) {
                        views.setTextViewText(R.id.textWidgetCal, String.format(Locale.US, "🩸 %dh", remainHours));
                    } else {
                        views.setTextViewText(R.id.textWidgetCal, String.format(Locale.US, "🩸 %dm", Math.max(1, remainMins)));
                    }
                    views.setTextColor(R.id.textWidgetCal, remainHours <= 2 ? Color.parseColor("#FFAB00") : Color.parseColor("#81C784"));
                } else {
                    views.setTextViewText(R.id.textWidgetCal, "🩸 12h+");
                    views.setTextColor(R.id.textWidgetCal, Color.parseColor("#FFAB00"));
                }
            }

            // 3. Transmitter Battery Level
            if (battery >= 0) {
                views.setTextViewText(R.id.textWidgetBattery, String.format(Locale.US, "🔋 %d%%", battery));
                views.setTextColor(R.id.textWidgetBattery, battery <= 20 ? Color.parseColor("#FF5252") : Color.parseColor("#76FF03"));
            } else {
                views.setTextViewText(R.id.textWidgetBattery, "🔋 --%");
                views.setTextColor(R.id.textWidgetBattery, Color.parseColor("#888888"));
            }

            // 4. Tap anywhere on the widget to open MainActivity
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e("GlucoseWidgetProvider", "Error updating widget " + appWidgetId, e);
        }
    }
}
