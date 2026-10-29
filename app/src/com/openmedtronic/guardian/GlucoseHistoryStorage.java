package com.openmedtronic.guardian;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages persistent storage of glucose history (up to 4032 points / 14 days)
 * and formats history according to the SiDiary CSV standard (compatible with xDrip+).
 */
public class GlucoseHistoryStorage {
    private static final String TAG = "GlucoseHistoryStorage";
    private static final String HISTORY_FILENAME = "glucose_history.csv";
    private static final String PREF_NAME = "guardian_prefs";
    private static final String KEY_CHART_POINTS = "chart_points";
    private static final String KEY_THERAPY_HISTORY = "therapy_history";
    public static final int MAX_HISTORY_POINTS = 4032; // 14 days of 5-min readings
    public static final int MAX_THERAPY_ENTRIES = 2000;

    public static final int TYPE_CGM = 0;
    public static final int TYPE_CALIBRATION = 1;
    public static final int TYPE_MANUAL_BG = 2;

    public static class TherapyEntry {
        public final long timestamp;
        public final float bolusUnits;
        public final float carbsGrams;
        public final String note;

        public TherapyEntry(long timestamp, float bolusUnits, float carbsGrams, String note) {
            this.timestamp = timestamp;
            this.bolusUnits = bolusUnits;
            this.carbsGrams = carbsGrams;
            this.note = note != null ? note : "";
        }

        public boolean hasBolus() {
            return bolusUnits > 0;
        }

        public boolean hasCarbs() {
            return carbsGrams > 0;
        }

        public boolean hasNote() {
            return note != null && !note.trim().isEmpty();
        }
    }

    public static class GlucosePoint {
        public final long timestamp;
        public final float glucoseMmol;
        public final float isig;
        public final int pointType;

        public GlucosePoint(long timestamp, float glucoseMmol, float isig, int pointType) {
            this.timestamp = timestamp;
            this.glucoseMmol = glucoseMmol;
            this.isig = isig;
            this.pointType = pointType;
        }

        public GlucosePoint(long timestamp, float glucoseMmol, float isig, boolean isCalibration) {
            this(timestamp, glucoseMmol, isig, isCalibration ? TYPE_CALIBRATION : TYPE_CGM);
        }

        public boolean isCalibration() {
            return pointType == TYPE_CALIBRATION;
        }

        public boolean isManualBg() {
            return pointType == TYPE_MANUAL_BG;
        }

        public boolean isCgm() {
            return pointType == TYPE_CGM;
        }
    }

    private static GlucoseHistoryStorage instance;
    private final Context context;
    private final File historyFile;
    private final List<GlucosePoint> inMemoryPoints = new ArrayList<>();
    private final List<TherapyEntry> therapyEntries = new ArrayList<>();
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public static synchronized GlucoseHistoryStorage getInstance(Context context) {
        if (instance == null) {
            instance = new GlucoseHistoryStorage(context.getApplicationContext());
        }
        return instance;
    }

    private GlucoseHistoryStorage(Context context) {
        this.context = context;
        this.historyFile = new File(context.getFilesDir(), HISTORY_FILENAME);
        loadHistory();
        loadTherapy();
    }

    public synchronized void addPoint(long timestamp, float glucoseMmol, float isig, boolean isCalibration) {
        addPoint(timestamp, glucoseMmol, isig, isCalibration ? TYPE_CALIBRATION : TYPE_CGM);
    }

    public synchronized void addManualBgPoint(long timestamp, float glucoseMmol) {
        addPoint(timestamp, glucoseMmol, 0f, TYPE_MANUAL_BG);
    }

    public synchronized void addPoint(long timestamp, float glucoseMmol, float isig, int pointType) {
        // Filter out corrupted points, invalid numbers, and warmup placeholder (65535)
        if (glucoseMmol < 2.0f || glucoseMmol > 25.0f || isig == 65535) {
            return;
        }

        // Deduplicate rapid consecutive packets of the same type
        for (int i = inMemoryPoints.size() - 1; i >= Math.max(0, inMemoryPoints.size() - 24); i--) {
            GlucosePoint existing = inMemoryPoints.get(i);
            if (existing.pointType == pointType) {
                long thresholdMs = (pointType == TYPE_CGM) ? 90_000L : 10_000L;
                if (Math.abs(timestamp - existing.timestamp) < thresholdMs) {
                    return; // Skip duplicate packet
                }
            }
        }

        final GlucosePoint pt = new GlucosePoint(timestamp, glucoseMmol, isig, pointType);
        boolean needsSort = !inMemoryPoints.isEmpty()
                && timestamp < inMemoryPoints.get(inMemoryPoints.size() - 1).timestamp;
        inMemoryPoints.add(pt);

        if (needsSort) {
            sortPointsLocked();
        }

        boolean trimmed = false;
        while (inMemoryPoints.size() > MAX_HISTORY_POINTS) {
            inMemoryPoints.remove(0);
            trimmed = true;
        }

        final List<GlucosePoint> snapshot = new ArrayList<>(inMemoryPoints);
        final boolean shouldRewriteAll = needsSort || trimmed;

        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (shouldRewriteAll) {
                    writePointsSnapshotToFile(snapshot);
                } else {
                    appendPointToFileSync(pt);
                }
                syncLegacyPrefsSnapshot(snapshot);
            }
        });
    }

    private void sortPointsLocked() {
        Collections.sort(inMemoryPoints, new Comparator<GlucosePoint>() {
            @Override
            public int compare(GlucosePoint a, GlucosePoint b) {
                return Long.compare(a.timestamp, b.timestamp);
            }
        });
    }

    private void appendPointToFileSync(GlucosePoint pt) {
        synchronized (historyFile) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(historyFile, true))) {
                bw.write(String.format(Locale.US, "%d,%.2f,%.1f,%d\n",
                        pt.timestamp, pt.glucoseMmol, pt.isig, pt.pointType));
            } catch (IOException e) {
                Log.e(TAG, "Error appending glucose point", e);
            }
        }
    }

    private void writePointsSnapshotToFile(List<GlucosePoint> snapshot) {
        synchronized (historyFile) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(historyFile, false))) {
                for (GlucosePoint pt : snapshot) {
                    bw.write(String.format(Locale.US, "%d,%.2f,%.1f,%d\n",
                            pt.timestamp, pt.glucoseMmol, pt.isig, pt.pointType));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error rewriting glucose history file", e);
            }
        }
    }

    public synchronized void rewriteFile() {
        final List<GlucosePoint> snapshot = new ArrayList<>(inMemoryPoints);
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                writePointsSnapshotToFile(snapshot);
            }
        });
    }

    public synchronized void loadHistory() {
        inMemoryPoints.clear();
        boolean hadArtifacts = false;

        synchronized (historyFile) {
            if (historyFile.exists() && historyFile.length() > 0) {
                try (BufferedReader br = new BufferedReader(new FileReader(historyFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split(",");
                        if (parts.length >= 3) {
                            long ts = Long.parseLong(parts[0]);
                            float val = Float.parseFloat(parts[1]);
                            float isig = Float.parseFloat(parts[2]);
                            int ptType = TYPE_CGM;
                            if (parts.length >= 4) {
                                try {
                                    ptType = Integer.parseInt(parts[3]);
                                } catch (NumberFormatException ignored) {}
                            }

                            if (val >= 2.0f && val <= 25.0f && isig != 65535) {
                                if (!inMemoryPoints.isEmpty()) {
                                    GlucosePoint last = inMemoryPoints.get(inMemoryPoints.size() - 1);
                                    if (ptType == TYPE_CGM && last.pointType == TYPE_CGM && Math.abs(ts - last.timestamp) < 90_000L) {
                                        hadArtifacts = true;
                                        continue; // Skip duplicate CGM packet
                                    }
                                }
                                inMemoryPoints.add(new GlucosePoint(ts, val, isig, ptType));
                            } else {
                                hadArtifacts = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading history file", e);
                }
            } else {
                // Migrate from legacy SharedPreferences if history file is empty
                migrateFromLegacyPrefs();
            }
        }

        sortPointsLocked();

        while (inMemoryPoints.size() > MAX_HISTORY_POINTS) {
            inMemoryPoints.remove(0);
            hadArtifacts = true;
        }

        if (hadArtifacts) {
            final List<GlucosePoint> snapshot = new ArrayList<>(inMemoryPoints);
            ioExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    writePointsSnapshotToFile(snapshot);
                    syncLegacyPrefsSnapshot(snapshot);
                }
            });
        }
    }

    private void migrateFromLegacyPrefs() {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_CHART_POINTS, null);
        if (saved == null || saved.isEmpty()) return;

        try {
            String[] tokens = saved.split(";");
            for (String t : tokens) {
                String[] parts = t.split(",");
                if (parts.length >= 3) {
                    long time = Long.parseLong(parts[0]);
                    float val = Float.parseFloat(parts[1]);
                    float isig = Float.parseFloat(parts[2]);
                    if (val >= 2.2f && val <= 25.0f && isig != 65535) {
                        inMemoryPoints.add(new GlucosePoint(time, val, isig, false));
                    }
                }
            }
            rewriteFile();
        } catch (Exception e) {
            Log.e(TAG, "Error migrating legacy prefs", e);
        }
    }

    private void syncLegacyPrefsSnapshot(List<GlucosePoint> snapshot) {
        int startIdx = Math.max(0, snapshot.size() - 288);
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < snapshot.size(); i++) {
            GlucosePoint p = snapshot.get(i);
            if (sb.length() > 0) sb.append(";");
            sb.append(p.timestamp).append(",").append(p.glucoseMmol).append(",").append(p.isig);
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CHART_POINTS, sb.toString())
                .apply();
    }

    public synchronized List<GlucosePoint> getAllPoints() {
        return new ArrayList<>(inMemoryPoints);
    }

    public synchronized long getSensorSessionStartTime() {
        if (inMemoryPoints.isEmpty()) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long maxSensorAgeMs = 7L * 24L * 3600_000L;
        int sessionStartIndex = 0;

        for (int i = inMemoryPoints.size() - 1; i > 0; i--) {
            long prevTime = inMemoryPoints.get(i - 1).timestamp;
            long currTime = inMemoryPoints.get(i).timestamp;
            long gap = currTime - prevTime;
            if (gap > 2L * 3600_000L) {
                sessionStartIndex = i;
                break;
            }
            if (now - prevTime > maxSensorAgeMs) {
                sessionStartIndex = i;
                break;
            }
        }
        return inMemoryPoints.get(sessionStartIndex).timestamp;
    }

    public synchronized int getCalibrationCountSince(long sinceMs) {
        if (sinceMs <= 0) {
            sinceMs = getSensorSessionStartTime();
        }
        int count = 0;
        for (GlucosePoint p : inMemoryPoints) {
            if (p.isCalibration() && p.timestamp >= sinceMs) {
                count++;
            }
        }
        return count;
    }

    public synchronized List<GlucoseChartView.Point> getChartPoints(int maxCount) {
        List<GlucoseChartView.Point> list = new ArrayList<>();
        int start = Math.max(0, inMemoryPoints.size() - maxCount);
        for (int i = start; i < inMemoryPoints.size(); i++) {
            GlucosePoint gp = inMemoryPoints.get(i);
            list.add(new GlucoseChartView.Point(gp.timestamp, gp.glucoseMmol, gp.isig, gp.pointType));
        }
        return list;
    }

    public synchronized void clear() {
        inMemoryPoints.clear();
        clearTherapy();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (historyFile) {
                    if (historyFile.exists()) {
                        historyFile.delete();
                    }
                }
            }
        });
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CHART_POINTS)
                .apply();
    }

    public synchronized boolean deletePoint(long timestamp) {
        boolean removed = false;
        for (int i = inMemoryPoints.size() - 1; i >= 0; i--) {
            GlucosePoint pt = inMemoryPoints.get(i);
            if (pt.timestamp == timestamp) {
                if (pt.isCalibration()) {
                    return false; // Calibrations are protected from deletion
                }
                inMemoryPoints.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            final List<GlucosePoint> snapshot = new ArrayList<>(inMemoryPoints);
            ioExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    writePointsSnapshotToFile(snapshot);
                    syncLegacyPrefsSnapshot(snapshot);
                }
            });
        }
        return removed;
    }


    public synchronized void loadTherapy() {
        therapyEntries.clear();
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String jsonStr = sp.getString(KEY_THERAPY_HISTORY, null);
        if (jsonStr != null && !jsonStr.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    long ts = obj.optLong("timestamp", 0L);
                    float bolus = (float) obj.optDouble("bolus", 0.0);
                    float carbs = (float) obj.optDouble("carbs", 0.0);
                    String note = obj.optString("note", "");
                    if (ts > 0 && (bolus > 0 || carbs > 0 || !note.isEmpty())) {
                        therapyEntries.add(new TherapyEntry(ts, bolus, carbs, note));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading therapy history", e);
            }
        }
        sortTherapyLocked();
    }

    private void saveTherapyToPrefsLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (TherapyEntry entry : therapyEntries) {
                JSONObject obj = new JSONObject();
                obj.put("timestamp", entry.timestamp);
                obj.put("bolus", entry.bolusUnits);
                obj.put("carbs", entry.carbsGrams);
                obj.put("note", entry.note);
                arr.put(obj);
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_THERAPY_HISTORY, arr.toString())
                    .apply();
        } catch (JSONException e) {
            Log.e(TAG, "Error serializing therapy history", e);
        }
    }

    private void sortTherapyLocked() {
        Collections.sort(therapyEntries, new Comparator<TherapyEntry>() {
            @Override
            public int compare(TherapyEntry a, TherapyEntry b) {
                return Long.compare(a.timestamp, b.timestamp);
            }
        });
    }

    public synchronized void saveTherapy(long timestamp, float bolus, float carbs, String note) {
        if (bolus <= 0 && carbs <= 0 && (note == null || note.trim().isEmpty())) {
            return;
        }
        therapyEntries.add(new TherapyEntry(timestamp, bolus, carbs, note));
        sortTherapyLocked();
        while (therapyEntries.size() > MAX_THERAPY_ENTRIES) {
            therapyEntries.remove(0);
        }
        saveTherapyToPrefsLocked();
    }

    public synchronized List<TherapyEntry> getTherapyHistory() {
        return new ArrayList<>(therapyEntries);
    }

    public synchronized List<TherapyEntry> getTherapyHistory(long startTime, long endTime) {
        List<TherapyEntry> result = new ArrayList<>();
        for (TherapyEntry entry : therapyEntries) {
            if (entry.timestamp >= startTime && entry.timestamp <= endTime) {
                result.add(entry);
            }
        }
        return result;
    }

    public synchronized boolean deleteTherapy(long timestamp) {
        boolean removed = false;
        for (int i = therapyEntries.size() - 1; i >= 0; i--) {
            if (therapyEntries.get(i).timestamp == timestamp) {
                therapyEntries.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            saveTherapyToPrefsLocked();
        }
        return removed;
    }

    public synchronized void clearTherapy() {
        therapyEntries.clear();
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_THERAPY_HISTORY)
                .apply();
    }

    /**
     * Generates a standard CSV export identical to xDrip+'s ExportToSiDiary format.
     * Header: DAY;TIME;UDT_CGMS;BG_LEVEL;CH_GR;BOLUS;REMARK
     * UDT_CGMS and BG_LEVEL are in integer mg/dL.
     */
    public synchronized String generateSiDiaryCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("DAY;TIME;UDT_CGMS;BG_LEVEL;CH_GR;BOLUS;REMARK\r\n");

        Date dateObj = new Date();
        int p = 0;
        int t = 0;
        int pointsSize = inMemoryPoints.size();
        int therapySize = therapyEntries.size();

        while (p < pointsSize || t < therapySize) {
            boolean takePoint;
            if (p < pointsSize && t < therapySize) {
                takePoint = inMemoryPoints.get(p).timestamp <= therapyEntries.get(t).timestamp;
            } else {
                takePoint = (p < pointsSize);
            }

            if (takePoint) {
                GlucosePoint pt = inMemoryPoints.get(p++);
                dateObj.setTime(pt.timestamp);
                String dayStr = dayFormat.format(dateObj);
                String timeStr = timeFormat.format(dateObj);
                int mgDl = Math.round(pt.glucoseMmol * 18.0182f);

                if (pt.isCalibration()) {
                    sb.append(String.format(Locale.US, "%s;%s;;%d;;;Calibration\r\n", dayStr, timeStr, mgDl));
                } else if (pt.isManualBg()) {
                    sb.append(String.format(Locale.US, "%s;%s;;%d;;;\r\n", dayStr, timeStr, mgDl));
                } else {
                    sb.append(String.format(Locale.US, "%s;%s;%d;;;;\r\n", dayStr, timeStr, mgDl));
                }
            } else {
                TherapyEntry te = therapyEntries.get(t++);
                dateObj.setTime(te.timestamp);
                String dayStr = dayFormat.format(dateObj);
                String timeStr = timeFormat.format(dateObj);

                String carbsStr = te.carbsGrams > 0 ? (te.carbsGrams == Math.round(te.carbsGrams) ? String.valueOf(Math.round(te.carbsGrams)) : String.format(Locale.US, "%.1f", te.carbsGrams)) : "";
                String bolusStr = te.bolusUnits > 0 ? String.format(Locale.US, "%.1f", te.bolusUnits) : "";
                String noteStr = te.note != null ? te.note.replace(';', ',').replace('\r', ' ').replace('\n', ' ').trim() : "";

                sb.append(String.format(Locale.US, "%s;%s;;;%s;%s;%s\r\n", dayStr, timeStr, carbsStr, bolusStr, noteStr));
            }
        }
        return sb.toString();
    }

    /**
     * Writes SiDiary CSV file to app external files or public downloads.
     */
    public synchronized File saveSiDiaryExportFile() throws IOException {
        String filename = "MMT7821_SiDiary_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".csv";

        File targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (targetDir == null || !targetDir.exists() || !targetDir.canWrite()) {
            targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (targetDir == null) {
            targetDir = context.getFilesDir();
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        File outFile = new File(targetDir, filename);
        String csvContent = generateSiDiaryCsv();
        try (FileOutputStream fos = new FileOutputStream(outFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(csvContent);
            writer.flush();
        }
        return outFile;
    }
}
