package com.ak449lv.clientapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class NotificationService extends Service {

    private static final String BASE = "https://449lv.com";
    private static final long POLL_MS = 10_000L;
    private static final String CH_FGS = "fgs";
    private static final String CH_ORDERS = "orders";
    private static final String PREFS = "pushsrv";
    private static final String KEY_SEEN = "seen";
    private static final String ACTION_START = "START";
    private static final String ACTION_STOP = "STOP";
    private static final String TAG = "NotificationService";

    private Handler handler;
    private boolean running = false;
    private boolean fgOk = false;
    private int retryFg = 0;
    private HashSet<String> seen = new HashSet<>();

    public static boolean start(Context c) {
        try {
            Intent i = new Intent(c, NotificationService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
            android.util.Log.d(TAG, "start dispatched sdk=" + Build.VERSION.SDK_INT);
            return true;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "start failed: " + t);
            try {
                Intent i = new Intent(c, NotificationService.class).setAction(ACTION_START);
                c.startService(i);
                return true;
            } catch (Throwable t2) {
                android.util.Log.e(TAG, "fallback start failed: " + t2);
                return false;
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            fgOk = false;
            retryFg = 0;
        }
        if (handler == null) handler = new Handler(getMainLooper());
        ensureChannels();
        startAsForeground();
        if (!running) {
            running = true;
            restoreSeen();
            handler.post(poller);
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // عند مسح التطبيق من المهام: إعادة تأكيد تشغيل الخدمة (بعض الأجهزة تقتلها)
        android.util.Log.d("NotificationService", "onTaskRemoved -> keep service alive");
        try {
            Intent i = new Intent(getApplicationContext(), NotificationService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            if (handler != null && !running) {
                running = true;
                handler.post(poller);
            }
        } catch (Throwable t) {
            // تجاهل — قد تكون قيود OEM
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_ORDERS) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ORDERS, "طلبات جديدة", NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(true);
            ch.enableLights(true);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 180, 250});
            try {
                ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
            } catch (Throwable t) {
            }
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_FGS) == null) {
            NotificationChannel ch = new NotificationChannel(CH_FGS, "تشغيل في الخلفية", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private void startAsForeground() {
        if (fgOk) return;
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, CH_FGS);
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("449LV — طلبات جديدة")
                    .setContentText("يعمل في الخلفية لإشعارات الطلبات فور وصولها")
                    .setOngoing(true)
                    .setShowWhen(false);
            if (Build.VERSION.SDK_INT >= 26) {
                Intent stopIntent = new Intent(this, NotificationService.class).setAction(ACTION_STOP);
                PendingIntent stopPi = PendingIntent.getService(this, 3, stopIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(0, "إيقاف الإشعارات", stopPi);
            }
            startForeground(999, b.build());
            fgOk = true;
            retryFg = 0;
            android.util.Log.d(TAG, "startForeground success");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "startForeground failed: " + t);
            fgOk = false;
            if (retryFg < 30) {
                retryFg++;
                if (handler != null) {
                    handler.postDelayed(() -> {
                        if (running) startAsForeground();
                    }, 5000L);
                }
            }
        }
    }

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                doPoll();
            } catch (Throwable t) {
                // أخطاء الشبكة/التحليل تُتجاهل وتُعاد المحاولة في الدورة التالية
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void doPoll() {
        String cookie = null;
        try {
            cookie = CookieManager.getInstance().getCookie(BASE + "/app");
        } catch (Throwable t) {
        }
        if (cookie == null || cookie.isEmpty()) return; // لا جلسة بعد — أعد المحاولة لاحقاً

        HttpURLConnection c = null;
        try {
            URL u = new URL(BASE + "/api/push/feed");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("User-Agent", "449LV-Android/1.0");
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) return;

            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject j = new JSONObject(sb.toString());
            JSONArray arr = j.optJSONArray("orders");
            if (arr == null) return;

            String page = j.optString("page", "/menu-admin");
            int ven = j.optInt("ven", 0);

            Set<String> newIds = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id", 0);
                String key = "o" + id;
                if (id > 0 && !seen.contains(key)) newIds.add(key);
            }
            if (newIds.isEmpty()) return;

            boolean firstRun = seen.isEmpty();
            for (String k : newIds) seen.add(k);
            persistSeen();

            // أول تشغيل: نأخذ خط الأساس فقط (لا ننبّه على الطلبات القديمة السابقة للتسجيل)
            if (firstRun) return;

            String openUrl = BASE + page + (ven > 0 ? "?v=" + ven : "");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id", 0);
                String key = "o" + id;
                if (id <= 0 || !newIds.contains(key)) continue;
                showOrderNotification(o, id, openUrl);
            }
        } catch (Throwable t) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void persistSeen() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putStringSet(KEY_SEEN, seen).apply();
        } catch (Throwable t) {
        }
    }

    private void restoreSeen() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> saved = prefs.getStringSet(KEY_SEEN, null);
            if (saved != null) seen.addAll(saved);
        } catch (Throwable t) {
        }
    }

    private void showOrderNotification(JSONObject o, int id, String openUrl) {
        String brand = o.optString("brand", "");
        String branch = o.optString("branch", "");
        String type = o.optString("type", "table");
        String total = o.optInt("total", 0) + " " + o.optString("currency", "");
        String customer = o.optString("customer", "");
        String notes = o.optString("notes", "");

        String typeLabel = "table".equals(type) ? "طاولة" : "توصيل";
        String title = (brand.isEmpty() ? "449LV" : brand) + " — طلب جديد";
        StringBuilder text = new StringBuilder();
        if (!branch.isEmpty()) text.append(branch).append(" · ");
        text.append(typeLabel).append(" · ").append(total);
        if (!customer.isEmpty()) text.append(" · ").append(customer);
        if (!notes.isEmpty()) text.append("\n").append(notes);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("url", openUrl);
        PendingIntent pi = PendingIntent.getActivity(this, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder b = new Notification.Builder(this, CH_ORDERS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text.toString())
                    .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM);
            nm.notify(id, b.build());
        } else {
            @SuppressWarnings("deprecation")
            Notification.Builder b = new Notification.Builder(this)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text.toString())
                    .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
            nm.notify(id, b.build());
        }
    }
}