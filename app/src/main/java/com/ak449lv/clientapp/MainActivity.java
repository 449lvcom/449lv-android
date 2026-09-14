package com.ak449lv.clientapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                saveSessionCookie();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // منح أذونات الويب (الإشعارات وغيرها) تلقائياً لموقع التطبيق فقط
                if (Build.VERSION.SDK_INT >= 21) {
                    try {
                        if (request.getOrigin() != null
                                && request.getOrigin().toString().startsWith("https://449lv.com")) {
                            request.grant(request.getResources());
                            return;
                        }
                    } catch (Throwable t) {
                    }
                    request.deny();
                }
            }
        });

        // جسر JavaScript → أندرويد: تتحكم أزرار الإشعارات في الموقع بالخدمة الأساسية داخل التطبيق
        web.addJavascriptInterface(new LvBridge(), "LvNative");

        // معالجة رابط الإشعار: فتح رابط الدخول / لوحة التحكم عند الضغط
        String url = null;
        if (getIntent() != null) {
            url = getIntent().getStringExtra("url");
        }
        if (url == null || url.isEmpty() || !url.startsWith("https://449lv.com")) {
            url = "https://449lv.com/app";
        }
        web.loadUrl(url);

        // طلب الأذونات بعد اكتمال بناء الواجهة (بعض الأجهزة تتأثر عند البناء المتزامن مع WebView)
        web.postDelayed(() -> ensurePermissions(), 800);
    }

    private void ensurePermissions() {
        try {
            requestNotificationPermission();
        } catch (Throwable t) {
        }
        // استثناء البطارية يُطلب بعد حل إذن الإشعارات (لا نُشبّك حوارين فوق بعضهما)
        if (Build.VERSION.SDK_INT < 33) {
            ensureBatteryOptExempt();
        }
        try {
            boolean ok = NotificationService.start(this);
            showStatusToast(ok);
            if (ok) {
                Toast.makeText(this, "تم تفعيل إشعارات الطلبات في الخلفية", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "تعذر تشغيل الخدمة الخلفية — سيُعاد التشغيل تلقائياً", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
        }
        // Watchdog: بعض الأجهزة (Xiaomi/Huawei/Samsung) تمنع التشغيل الخلفي من أول مرة —
        // نعيد المحاولة بفترات متباعدة أثناء فتح التطبيق
        watchdogRun(1);
    }

    private void saveSessionCookie() {
        try {
            String c = CookieManager.getInstance().getCookie("https://449lv.com/app");
            if (c != null && !c.isEmpty()) {
                getSharedPreferences("pushsrv", MODE_PRIVATE).edit().putString("cookie", c).apply();
            }
        } catch (Throwable t) {
        }
    }

    private class LvBridge {
        @android.webkit.JavascriptInterface
        public boolean isEnabled() {
            return NotificationService.isActive() && areNotificationsEnabled();
        }

        @android.webkit.JavascriptInterface
        public String getPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
            }
            return areNotificationsEnabled() ? "granted" : "denied";
        }

        @android.webkit.JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> requestNotificationPermission());
        }

        @android.webkit.JavascriptInterface
        public void sendTest() {
            NotificationService.sendTestNotification(MainActivity.this);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "أُرسل إشعار تجريبي ✓", Toast.LENGTH_SHORT).show());
        }

        @android.webkit.JavascriptInterface
        public void setSound(String name) {
            NotificationService.applySound(MainActivity.this, name);
        }

        @android.webkit.JavascriptInterface
        public void stop() {
            NotificationService.stopService(MainActivity.this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Toast.makeText(this, "تم السماح بالإشعارات", Toast.LENGTH_LONG).show();
            } else if (Build.VERSION.SDK_INT >= 33 && isPermanentlyDenied()) {
                // رفض نهائي (خيار "لا تسأل مجدداً") → نتجه مباشرة لإعدادات التطبيق
                Toast.makeText(this, "يرجى السماح بالإشعارات من إعدادات التطبيق حتى تصلك الطلبات", Toast.LENGTH_LONG).show();
                openNotificationSettings();
            } else {
                Toast.makeText(this, "لم يتم السماح بالإشعارات — سنعيد الطلب عند الفتح التالي", Toast.LENGTH_LONG).show();
            }
            // بعد انتهاء حوار الإذن اطلب استثناء البطارية (لا تداخل بين الحوارات)
            ensureBatteryOptExempt();

            // إبلاغ جافاسكريبت (زر الإشعارات في الموقع) بنتيجة طلب الإذن
            if (web != null) {
                web.evaluateJavascript(
                        "try{if(window.__lvPermCb){window.__lvPermCb(" + granted + ");window.__lvPermCb=null;}}catch(e){}",
                        null);
            }
        }
    }

    private boolean isPermanentlyDenied() {
        // true = رفض نهائي من قبل (لا يظهر حوار الإذن مجدداً)
        try {
            boolean canAsk = shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS);
            SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
            return !canAsk && p.getBoolean("notif_permission_asked", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private void showStatusToast(boolean ok) {
        String vName = "?";
        try {
            vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
        }
        String os = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String notif = areNotificationsEnabled() ? "الإشعارات مفعّلة" : "الإشعارات مقفلة";
        String svc = ok ? "الخدمة تعمل" : "الخدمة لم تُشغَّل";
        Toast.makeText(this,
                "449LV v" + vName + " · أندرويد " + os + " · " + notif + " · " + svc,
                Toast.LENGTH_LONG).show();
    }

    private void watchdogRun(final int step) {
        if (step > 6) return;
        web.postDelayed(() -> {
            NotificationService.start(this);
            watchdogRun(step + 1);
        }, 40_000L * step);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String url = intent.getStringExtra("url");
            if (url != null && !url.isEmpty() && url.startsWith("https://449lv.com")) {
                web.loadUrl(url);
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            int granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS);
            SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
            boolean askedBefore = p.getBoolean("notif_permission_asked", false);
            if (granted == PackageManager.PERMISSION_GRANTED) {
                // ممنوح بالفعل → اطلب استثناء البطارية بعد قليل (لا حوار إشعارات هذه المرة)
                if (!p.getBoolean("battery_exempt_requested", false)) {
                    web.postDelayed(this::ensureBatteryOptExempt, 1500);
                }
            } else if (!askedBefore) {
                // أول فتح → طلب فوري وظاهر
                p.edit().putBoolean("notif_permission_asked", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            } else if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                // رفض عادي سابق (غير نهائي) → نعيد الطلب
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            } else {
                // رفض نهائي → الإعدادات مباشرة
                Toast.makeText(this, "الرجاء تفعيل الإشعارات من إعدادات التطبيق", Toast.LENGTH_LONG).show();
                web.postDelayed(this::openNotificationSettings, 700);
            }
        } else if (!areNotificationsEnabled()) {
            // أندرويد 12 أو أقل: لا يوجد حوار إذن؛ إن كانت الإشعارات مقفلة نوجّه للإعدادات
            String k = "notif_settings_suggested";
            SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
            if (!p.getBoolean(k, false)) {
                p.edit().putBoolean(k, true).apply();
                web.postDelayed(this::openNotificationSettings, 600);
            }
        }
    }

    private boolean areNotificationsEnabled() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private void openNotificationSettings() {
        try {
            Intent s = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            s.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(s);
        } catch (Throwable t) {
            try {
                Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivity(s);
            } catch (Throwable ignored) {
            }
        }
    }

    private void ensureBatteryOptExempt() {
        if (Build.VERSION.SDK_INT < 23) return;
        SharedPreferences p = getSharedPreferences("pushsrv", MODE_PRIVATE);
        if (p.getBoolean("battery_exempt_requested", false)) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable ignored) {
            // بعض الأجهزة تمنع هذا العرض
        }
        p.edit().putBoolean("battery_exempt_requested", true).apply();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}