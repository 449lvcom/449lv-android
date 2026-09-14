package com.ak449lv.clientapp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
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
    private static final String CH_LOGIN = "login";
    private static final String PREFS = "pushsrv";
    private static final String KEY_SEEN = "seen";
    private static final String KEY_SOUND = "notif_sound";
    private static final String KEY_LOGIN_NOTIFIED = "login_notified";
    private static final String ACTION_START = "START";
    private static final String ACTION_STOP = "STOP";
    private static final String ACTION_RESTART = "com.ak449lv.clientapp.RESTART";
    private static final long RESTART_ALARM_MS = 15 * 60 * 1000L;
    private static final int RESTART_ALARM_REQ = 77;
    private static final String TAG = "NotificationService";
    private static final String KEY_COOKIE = "cookie";
    private static final String KEY_DIAG = "diag";
    private static final String KEY_DIAG_TS = "diag_ts";

    private static volatile boolean sRunning = false;

    private Handler handler;
    private final Object pollLock = new Object();
    private boolean pollInFlight = false;
    private boolean running = false;
    private boolean fgOk = false;
    private int retryFg = 0;
    private int badAuth = 0;
    private static String sChannelSound = null;
    private static String sOrdersCh = CH_ORDERS;
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

    public static boolean isActive() {
        return sRunning;
    }

    public static Uri soundUri(Context ctx, String name) {
        try {
            if (name == null) name = "system";
            name = name.trim();
            if (name.isEmpty()) name = "system";
            if ("silent".equals(name)) return null;
            switch (name) {
                case "bell":
                    return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.bell);
                case "chime":
                    return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.chime);
                case "ding":
                    return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.ding);
                case "alert":
                    return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.alert);
                case "fanfare":
                    return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.fanfare);
                default:
                    return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "soundUri failed: " + t);
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
    }

    public static void stopService(Context c) {
        try {
            Intent i = new Intent(c, NotificationService.class).setAction(ACTION_STOP);
            c.startService(i);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "stopService failed: " + t);
        }
    }

    public static void sendTestNotification(Context ctx) {
        try {
            ensureChannels(ctx);
            String snd = "system";
            try {
                snd = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SOUND, "system");
            } catch (Throwable t) {
            }
            if (snd == null || snd.isEmpty()) snd = "system";
            applySound(ctx, snd);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("url", BASE + "/menu-admin");
            PendingIntent pi = PendingIntent.getActivity(ctx, 779977, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = "449LV — إشعار تجريبي";
            String text = "إشعارات الطلبات تعمل بشكل صحيح على هذا الجهاز ✓";
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(ctx, ordersCh());
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
            }
            nm.notify(779977, b.build());
        } catch (Throwable t) {
            android.util.Log.e(TAG, "sendTestNotification failed: " + t);
        }
    }

    private static String ordersCh() {
        return sOrdersCh;
    }

    public static void applySound(Context ctx, String name) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            if (name == null) name = "system";
            String n = name.trim();
            if (n.isEmpty()) n = "system";
            if (n.equals(sChannelSound)) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Uri u = soundUri(ctx, n);
            String newId = CH_ORDERS + "_" + n;
            if (!newId.equals(sOrdersCh)) {
                try {
                    NotificationChannel oldCh = nm.getNotificationChannel(sOrdersCh);
                    if (oldCh != null) nm.deleteNotificationChannel(sOrdersCh);
                } catch (Throwable t) {
                }
                try {
                    NotificationChannel baseCh = nm.getNotificationChannel(CH_ORDERS);
                    if (baseCh != null) nm.deleteNotificationChannel(CH_ORDERS);
                } catch (Throwable t) {
                }
                sOrdersCh = newId;
            }
            NotificationChannel ch = new NotificationChannel(newId, "طلبات جديدة", NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(true);
            ch.enableLights(true);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 180, 250});
            if ("silent".equals(n)) {
                ch.setSound(null, null);
            } else {
                ch.setSound(u, new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            nm.createNotificationChannel(ch);
            sChannelSound = n;
            try {
                ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SOUND, n).apply();
            } catch (Throwable t) {
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "applySound failed: " + t);
        }
    }

    private void scheduleRestartAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, RestartReceiver.class).setAction(ACTION_RESTART);
            PendingIntent pi = PendingIntent.getBroadcast(this, RESTART_ALARM_REQ, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + RESTART_ALARM_MS, RESTART_ALARM_MS, pi);
            android.util.Log.d(TAG, "restart alarm scheduled");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "scheduleRestartAlarm failed: " + t);
        }
    }

    private void cancelRestartAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, RestartReceiver.class).setAction(ACTION_RESTART);
            PendingIntent pi = PendingIntent.getBroadcast(this, RESTART_ALARM_REQ, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        } catch (Throwable t) {
        }
    }

    private void handleAuthSkip() {
        badAuth++;
        if (badAuth >= 6) {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (!prefs.getBoolean(KEY_LOGIN_NOTIFIED, false)) {
                    prefs.edit().putBoolean(KEY_LOGIN_NOTIFIED, true).apply();
                    showLoginNeededNotification();
                }
            } catch (Throwable t) {
            }
        }
    }

    private void clearAuthSkip() {
        badAuth = 0;
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LOGIN_NOTIFIED, false).apply();
        } catch (Throwable t) {
        }
    }

    private void showLoginNeededNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("url", BASE + "/menu-admin");
            PendingIntent pi = PendingIntent.getActivity(this, 779976, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, CH_LOGIN);
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("449LV — الجلسة انتهت")
                    .setContentText("افتح التطبيق وسجّل الدخول ليستمر استقبال إشعارات الطلبات")
                    .setStyle(new Notification.BigTextStyle().bigText("انتهت صلاحية جلسة تسجيل الدخول؛ افتح التطبيق وسجّل الدخول من جديد ليستمر استقبال إشعارات الطلبات على هذا الجهاز."))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_DEFAULT).setDefaults(Notification.DEFAULT_ALL);
            }
            nm.notify(779976, b.build());
        } catch (Throwable t) {
            android.util.Log.e(TAG, "showLoginNeeded failed: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;
            sRunning = false;
            cancelRestartAlarm();
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
            sRunning = true;
            restoreSeen();
            scheduleRestartAlarm();
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
            if (!running) {
                running = true;
                sRunning = true;
                if (handler == null) handler = new Handler(getMainLooper());
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
        sRunning = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        cancelRestartAlarm();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannels() {
        ensureChannels(this);
    }

    private static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (sOrdersCh.equals(CH_ORDERS) && nm.getNotificationChannel(CH_ORDERS) == null) {
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
        if (nm.getNotificationChannel(CH_LOGIN) == null) {
            NotificationChannel ch = new NotificationChannel(CH_LOGIN, "الجلسة", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            ch.enableLights(true);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 200, 150});
            try {
                ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null);
            } catch (Throwable t) {
            }
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
            synchronized (pollLock) {
                if (pollInFlight) {
                    handler.postDelayed(this, POLL_MS);
                    return;
                }
                pollInFlight = true;
            }
            try {
                new Thread(() -> {
                    try {
                        doPoll();
                    } catch (Throwable t) {
                        // أخطاء الشبكة/التحليل تُتجاهل وتُعاد المحاولة في الدورة التالية
                    }
                    synchronized (pollLock) {
                        pollInFlight = false;
                    }
                }, "push-poll").start();
            } catch (Throwable t) {
                synchronized (pollLock) {
                    pollInFlight = false;
                }
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void doPoll() {
        String cookie = null;
        boolean fromPrefs = false;
        try {
            cookie = CookieManager.getInstance().getCookie(BASE + "/app");
        } catch (Throwable t) {
        }
        if (cookie == null || cookie.isEmpty()) {
            // عند تشغيل الخدمة في عملية جديدة بلا WebView (كإعادة التشغيل بعد الإقلاع)
            // لا يكون مخزن الكوكيز مهيّأ — نعتمد على الكوكي المحفوظ من آخر فتح للتطبيق
            try {
                cookie = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_COOKIE, null);
                fromPrefs = !(cookie == null || cookie.isEmpty());
            } catch (Throwable t) {
            }
        }
        if (cookie == null || cookie.isEmpty()) {
            // لا جلسة بعد: لا نتوقف بصمت — نسجّل وننبّه أن التطبيق يحتاج فتحه وتسجيل الدخول
            setDiag("no-cookie orders=0");
            handleAuthSkip();
            return;
        }

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
            if (code != 200) {
                setDiag("code=" + code + " orders=0 cookie=" + cookie.length() + " prefs=" + fromPrefs);
                handleAuthSkip();
                return;
            }
            clearAuthSkip();

            // تحديث الكوكي المحفوظ بعد كل نجاح (يستمر العمل بعد إعادة التشغيل)
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_COOKIE, cookie).apply();
            } catch (Throwable t) {
            }

            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject j = new JSONObject(sb.toString());
            JSONArray arr = j.optJSONArray("orders");
            if (arr == null) {
                setDiag("code=200 orders=null");
                return;
            }

            // تحديث نغمة الإشعارات حسب إعدادات المنيو (تُطبَّق على قناة الطلبات)
            if (arr.length() > 0) {
                JSONObject first = arr.optJSONObject(0);
                if (first != null) applySound(this, first.optString("sound", "system"));
            }

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
            setDiag("code=200 orders=" + arr.length() + " new=" + newIds.size() + " seen=" + seen.size() + " cookie=" + cookie.length());
            if (newIds.isEmpty()) return;

            for (String k : newIds) seen.add(k);
            persistSeen();

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
            String msg = t.getMessage();
            if (msg != null && msg.length() > 140) msg = msg.substring(0, 140);
            setDiag("err: " + (msg == null ? t.getClass().getSimpleName() : msg));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void setDiag(String s) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_DIAG, s)
                    .putLong(KEY_DIAG_TS, System.currentTimeMillis())
                    .apply();
        } catch (Throwable t) {
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
            Notification.Builder b = new Notification.Builder(this, ordersCh())
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